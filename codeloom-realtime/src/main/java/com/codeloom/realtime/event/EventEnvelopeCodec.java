package com.codeloom.realtime.event;

import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.EventType;
import com.codeloom.domain.session.SessionId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DateTimeException;
import java.time.Instant;

/**
 * {@link EventEnvelope} 的 JSON 编解码，也就是事件在进程之外长什么样。
 *
 * <p>{@link EventCodec} 负责事件本体与类型名加 payload 的互转；这一层在外面补上会话、
 * seq 和发生时间，这三样不属于事件本身。
 *
 * <p>Redis Pub/Sub 的消息体和 SSE 帧的 {@code data:} 刻意共用同一份格式：同一个事件
 * 在本实例和别的实例上必须是同一串字节，否则差异只会在多实例联调时暴露。
 *
 * <p>格式：
 * <pre>
 *   {
 *     "sessionId": "…",
 *     "seq": 42,
 *     "at": "2026-09-25T10:00:00.123Z",
 *     "type": "TOOL_RESULT",
 *     "payload": { … }
 *   }
 * </pre>
 * 流式增量没有 {@code seq} 键。{@code payload} 是内嵌 JSON 对象，不是转义后的字符串，
 * 这样排查时可以直接用 {@code jq} 查看。
 */
public final class EventEnvelopeCodec {

    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_SEQ = "seq";
    private static final String FIELD_AT = "at";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_PAYLOAD = "payload";

    private final EventCodec events = new EventCodec();

    /**
     * @param envelope 事件信封
     * @return 可以直接发出去的 JSON；同一个信封产出同一串字节，发生时间来自信封，
     *         这里不另取当前时间
     */
    public String write(EventEnvelope envelope) {
        return writeToNode(envelope).toString();
    }

    /**
     * 同一个信封，产出对象树而不是字符串。
     *
     * <p>给需要把信封嵌进更大响应的调用方用（例如一次拉上千条事件的 REST 历史接口），
     * 避免先序列化成字符串再解析回树。组帧逻辑只有这一份，{@link #write} 只是它加
     * 一个 {@code toString()}。
     *
     * @param envelope 事件信封
     * @return 信封的 JSON 对象树
     */
    public ObjectNode writeToNode(EventEnvelope envelope) {
        EventCodec.EncodedEvent encoded = events.encode(envelope.event());

        ObjectNode node = EventCodec.MAPPER.createObjectNode();
        node.put(FIELD_SESSION_ID, envelope.sessionId().value());
        if (envelope.seq() != null) {
            // 流式增量没有 seq，线上也就没有这个键，浏览器不会给它安 id。
            node.put(FIELD_SEQ, envelope.seq());
        }
        node.put(FIELD_AT, envelope.occurredAt().toString());
        node.put(FIELD_TYPE, encoded.type().name());
        node.set(FIELD_PAYLOAD, readTree(encoded.payload()));
        return node;
    }

    /**
     * @param json 事件信封的 JSON
     * @return 解析出的事件信封
     * @throws EventCodecException 串坏了、缺少字段、时间格式不对，或者事件类型认不出来
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
        } catch (IllegalArgumentException | NullPointerException | DateTimeException e) {
            // 事件信封跨进程传输，坏数据来源比库里的行多：版本不一致的旧实例、手工改过的消息。
            // 统一转成可辨认的异常，避免漏出空指针。
            //
            // Instant.parse 抛出的 DateTimeParseException 不继承 IllegalArgumentException，
            // 所以 DateTimeException 这一支不能省；少了它，坏时间戳会以另一种异常形状漏出去。
            throw new EventCodecException("事件信封解码失败：" + json, e);
        }
    }

    /** 把内嵌的 payload 解析成对象树；{@link EventCodec#decode} 接收的是它的字符串形式。 */
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
