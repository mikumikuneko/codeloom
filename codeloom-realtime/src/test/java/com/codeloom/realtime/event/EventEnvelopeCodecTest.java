package com.codeloom.realtime.event;

import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.StoredEvent;
import com.codeloom.domain.event.ToolResult;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.session.SessionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 事件信封的线上格式。
 *
 * <p>这一层跨进程：Redis Pub/Sub 的消息体和 SSE 帧的 {@code data:} 使用同一份格式。
 * 因此它和 {@code EventCodec} 一样，改了就可能没人读得动旧数据；下面的断言检查格式本身，
 * 不只是能否往返。
 */
class EventEnvelopeCodecTest {

    private static final SessionId SESSION = SessionId.of("s-1");
    private static final Instant AT = Instant.parse("2026-09-25T10:00:00.123Z");

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    // ------------------------------------------------------------------

    @Test
    @DisplayName("已落库的事件：会话、seq、时间、类型、本体一个不少")
    void persistedEventRoundTrips() {
        EventEnvelope envelope = EventEnvelope.of(new StoredEvent(SESSION, 42L, AT,
                new ToolResult("call_1", false, "boom", true, 1, 37L)));

        String json = codec.write(envelope);

        assertThat(json)
                .contains("\"sessionId\":\"s-1\"")
                .contains("\"seq\":42")
                .contains("\"at\":\"2026-09-25T10:00:00.123Z\"")
                .contains("\"type\":\"TOOL_RESULT\"");
        assertThat(codec.read(json)).isEqualTo(envelope);
    }

    @Test
    @DisplayName("流式增量：线上**没有 seq 这个键** —— 浏览器于是也不会给它安一个 id")
    void ephemeralEventCarriesNoSeq() {
        // 这决定 SSE 帧里有没有 id: 字段，而它是客户端断线重连时回传的游标。
        // 给正在打字中的片段安上 id，客户端重连后会从不存在的 seq 接着拉。
        EventEnvelope envelope = EventEnvelope.ephemeral(SESSION, AT, new AssistantDelta("半个句"));

        String json = codec.write(envelope);

        assertThat(json).doesNotContain("\"seq\"");
        assertThat(codec.read(json)).isEqualTo(envelope);
    }

    @Test
    @DisplayName("payload 是内嵌对象，不是被转义成一坨反斜杠的字符串")
    void payloadIsAnEmbeddedObject() {
        // 排查时能直接 jq .payload 剥开看，而不是对着反斜杠数转义。
        String json = codec.write(EventEnvelope.of(
                new StoredEvent(SESSION, 1L, AT, new UserMessage("看一下 README"))));

        assertThat(json).contains("\"payload\":{\"text\":\"看一下 README\"}");
    }

    @Test
    @DisplayName("同一个信封写两次是同一串字节 —— 多实例联调时同一个事件不该长得不一样")
    void writingIsDeterministic() {
        EventEnvelope envelope = EventEnvelope.of(new StoredEvent(SESSION, 7L, AT,
                new ToolResult("c", true, "ok", false, null, 1L)));

        assertThat(codec.write(envelope)).isEqualTo(codec.write(envelope));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("坏消息报错要指向哪里坏了 —— 跨进程的坏数据来源比库里的行多得多")
    void corruptMessagesAreReported() {
        assertThatThrownBy(() -> codec.read("这不是 JSON"))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("不是合法 JSON");

        assertThatThrownBy(() -> codec.read("{\"sessionId\":\"s-1\"}"))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("缺少字段 type");

        assertThatThrownBy(() -> codec.read("""
                {"sessionId":"s-1","at":"2026-09-25T10:00:00Z","type":"NOT_A_REAL_TYPE","payload":{}}
                """))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("未知的事件类型");

        // 时间戳也是坏数据的一种。DateTimeParseException 不继承 IllegalArgumentException，
        // 漏掉它这一类会以另一种异常形状漏出去（见 read 的 catch）。
        assertThatThrownBy(() -> codec.read("""
                {"sessionId":"s-1","seq":1,"at":"昨天下午","type":"USER_MESSAGE","payload":{"text":"x"}}
                """))
                .isInstanceOf(EventCodecException.class)
                .hasMessageContaining("事件信封解码失败");
    }

    @Test
    @DisplayName("信封自己守住那条不变量：落库的必须有 seq、易失的必须没有")
    void theEnvelopeItselfGuardsTheInvariant() {
        // 这条约束不能只写在写入侧，读出来时同样要成立，否则一条没有 seq 的持久事件
        // 会一路飘到 SSE 的 id 字段上。
        assertThatThrownBy(() -> new EventEnvelope(SESSION, null, AT, new UserMessage("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须带 seq");
        assertThatThrownBy(() -> new EventEnvelope(SESSION, 1L, AT, new AssistantDelta("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能带 seq");
    }

    @Test
    @DisplayName("JSON 里的 type 判别字段和本体是一致的 —— 解出来的一定是对应的那个类")
    void typeDiscriminatorMatchesTheBody() {
        // 信封记录本身不带 type（由事件类推导），所以只能写出去再解回来，
        // 验证本体仍然是原来那个类。
        EventEnvelope envelope = EventEnvelope.ephemeral(SESSION, AT, new AssistantDelta("半"));

        EventEnvelope decoded = codec.read(codec.write(envelope));

        assertThat(decoded.event()).isInstanceOf(AssistantDelta.class);
        assertThat(decoded).isEqualTo(envelope);
    }
}
