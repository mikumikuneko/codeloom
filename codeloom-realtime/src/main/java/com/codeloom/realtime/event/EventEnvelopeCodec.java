package com.codeloom.realtime.event;

import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.session.SessionId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * {@link EventEnvelope} 的 JSON 编解码 —— **流上那一条**在进程之外长什么样。
 *
 * <h2>这一层和 {@link EventCodec} 的分工</h2>
 * {@code EventCodec} 管的是「事件本体 ↔ 类型名 + payload」；这一层在外面再包一层信封，
 * 补上**这条事件属于哪条会话、序号是多少、什么时候发生的** —— 那三样都不是事件自带的
 * （它们是存储层或传输层的事，见 {@code Event} 的类注释）。
 *
 * <p>它被两个地方用：Redis Pub/Sub 的消息体（跨实例扇出），和 SSE 帧的 {@code data:}。
 * **刻意用同一份格式** —— 让"本实例推给浏览器的"和"另一个实例推给它的"是同一串字节，
 * 否则同一个事件在不同实例上会长得不一样，而那种差异只会在多实例联调时才冒出来。
 *
 * <h2>格式</h2>
 * <pre>
 *   {
 *     "sessionId": "…",
 *     "seq": 42,                       // 流式增量没有这个键（见 EventEnvelope）
 *     "at": "2026-09-25T10:00:00.123Z",
 *     "type": "TOOL_RESULT",           // 判别字段，解码靠它
 *     "payload": { … }                 // 事件本体，内嵌成真正的 JSON 对象
 *   }
 * </pre>
 *
 * <p>{@code payload} 是**内嵌对象**而不是转义过的字符串。后者（把 JSON 再序列化一次塞进字符串）
 * 写成文本能跑，但读起来是一坨反斜杠，而且排查时没法直接用 {@code jq} 剥开看。
 */
public final class EventEnvelopeCodec {

    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_AT = "at";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PAYLOAD = "payload";

    private final EventCodec events = new EventCodec();

    /** @return 可以直接发出去的 JSON。同一个信封产出同一串字节 —— 落款时间由信封带来，这里不另取当前时间 */
    public String write(EventEnvelope envelope) {
        return writeToNode(envelope).toString();
    }

    /**
     * 同一个信封，产出**对象树**而不是字符串。
     *
     * <p>给"要把它作为一个对象嵌进更大的响应里"的场合用（REST 的历史接口，一次能拉上千条事件）。
     * 走 {@link #write} 的话会多两次转换：整份信封序列化成字符串、调用方再解析回树。
     * 而那个接口一次能拉上千条事件。
     *
     * <p>组帧逻辑只有这一份，{@code write} 就是它加一个 {@code toString()}。
     */
    public ObjectNode writeToNode(EventEnvelope envelope) {
        EventCodec.EncodedEvent encoded = events.encode(envelope.event());

        ObjectNode node = EventCodec.MAPPER.createObjectNode();
        node.put(FIELD_SESSION_ID, envelope.sessionId().value());
        if (envelope.seq() != null) {
            // 流式增量就走这一个分支的不同 —— 它没有 seq，于是线上也就没有这个键，
            // 浏览器那边也就不会给它安一个 id（见 EventEnvelope 的类注释）
            node.put(FIELD_SEQ, envelope.seq());
        }
        node.put(FIELD_AT, envelope.occurredAt().toString());
        node.put(FIELD_TYPE, encoded.type().name());
        node.set(FIELD_PAYLOAD, readTree(encoded.payload()));
        return node;
    }

    /**
     * @throws EventCodecException 串坏了、或者里面的事件类型认不出来
     */
    public EventEnvelope read(String json) {
        JsonNode node = parse(json);
        EventType type = parseType(require(node, FIELD_TYPE).asText());
        try {
            return new EventEnvelope(
                    SessionId.of(require(node, FIELD_SESSION_ID).asText()),
                    node.hasNonNull(FIELD_SEQ) ? node.get(FIELD_SEQ).asLong() : null,
                    Instant.parse(require(node, FIELD_AT).asText()),
                    events.decode(type, require(node, FIELD_PAYLOAD).toString()));
        } catch (IllegalArgumentException | NullPointerException e) {
            // 上面那一串都可能因为某个键缺失或格式不对而抛。事件信封是**跨进程**的
            // 传输格式，坏数据的来源比库里的行多得多（版本不一致的旧实例、手工改过的消息），
            // 所以这里统一转成可辨认的异常，而不是漏一个 NullPointerException 出去
            throw new EventCodecException("事件信封解码失败：" + json, e);
        }
    }

    /** payload 是内嵌对象，拆出来直接当字符串喂给 {@link EventCodec#decode}。 */
    private JsonNode readTree(String payloadJson) {
        try {
            return EventCodec.MAPPER.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            throw new EventCodecException("事件 payload 不是合法 JSON：" + payloadJson, e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return EventCodec.MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new EventCodecException("事件信封不是合法 JSON：" + json, e);
        }
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new EventCodecException("事件信封缺少字段 " + field + "：" + node);
        }
        return value;
    }

    private static EventType parseType(String raw) {
        return EventTypes.parse(raw, "事件信封里");
    }
}
