package com.codeloom.realtime.sse;

import com.codeloom.domain.event.AssistantDelta;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.event.UserMessage;
import com.codeloom.domain.session.SessionId;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 帧的格式。
 *
 * <p>这里的两条规则都**错了也不会报错**，只会让客户端在某个边角上表现不对：
 * {@code id:} 是断线重连的游标（给流式增量也安上一个，重连就会从错误的地方接着拉），
 * {@code data} 必须是**已经成形的 JSON**（再序列化一遍会得到一层引号和转义，
 * 客户端解析出来是个字符串而不是对象）。所以要能把帧单独拿出来看。
 *
 * <p>不需要中间件，也不需要架真连接 —— 见 {@link SseFrames} 的类注释。
 */
class SseFramesTest {

    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();
    private final SessionId sessionId = SessionId.generate();

    @Test
    @DisplayName("已落库的事件：帧里有 id:，它就是重连要回传的游标")
    void persistedEventsCarryTheirSequenceAsId() {
        EventEnvelope envelope =
                new EventEnvelope(sessionId, 42L, Instant.now(), new UserMessage("你好"));

        String wire = wire(SseFrames.of(envelope, codec));

        assertThat(wire).contains("id:42");
        assertThat(wire).contains("data:");
    }

    @Test
    @DisplayName("【关键】流式增量**没有** id: —— 否则重连会从一条不存在的 seq 接着拉")
    void ephemeralDeltasCarryNoId() {
        EventEnvelope delta =
                new EventEnvelope(sessionId, null, Instant.now(), new AssistantDelta("正在打字"));

        String wire = wire(SseFrames.of(delta, codec));

        assertThat(wire).doesNotContain("id:");
        // 但它照样要发出去 —— 看的人正是靠它看到"模型在打字"
        assertThat(wire).contains("data:").contains("正在打字");
    }

    @Test
    @DisplayName("data 里是原样的 JSON，不是被再序列化一遍的字符串")
    void thePayloadIsRawJsonNotAReEncodedString() {
        EventEnvelope envelope =
                new EventEnvelope(sessionId, 7L, Instant.now(), new UserMessage("你好"));

        String wire = wire(SseFrames.of(envelope, codec));

        // 多一层序列化的表现是：内层引号被转义成 \"，而且外面会多一对引号。
        // 断言"原样的 JSON 逐字出现在帧里"正好排掉这两种
        assertThat(wire).contains(codec.write(envelope));
    }

    @Test
    @DisplayName("开流那一帧是**注释**：以 : 开头、没有 data —— 客户端会忽略它，它只负责顶开连接")
    void theOpeningFrameIsAComment() {
        String wire = wire(SseFrames.opening());

        assertThat(wire).startsWith(":").contains("codeloom stream open");
        assertThat(wire).doesNotContain("data:").doesNotContain("id:");
    }

    // ------------------------------------------------------------------

    /** 把一帧的所有片段拼成最终写出去的那串字节。 */
    private static String wire(SseEmitter.SseEventBuilder frame) {
        return frame.build().stream()
                .map(part -> String.valueOf(part.getData()))
                .collect(Collectors.joining());
    }
}
