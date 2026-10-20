package com.codeloom.realtime.sse;

import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 帧的构造。单独成类是为了可测：这一层有两条规则，错了不会报错，只会让客户端
 * 在边角上表现异常。
 *
 * <p>{@code id:} 只给已落库的事件写。它是断线重连的游标，给流式增量也写会让客户端从
 * 不存在的 seq 接着拉，表现为重连后少一段。{@code data} 必须是已经成形的 JSON，不能再
 * 让消息转换器序列化一遍，否则会多出引号和转义，客户端解析出字符串而不是对象。
 *
 * <p>{@link SseEmitter.SseEventBuilder#build()} 是公开的，测试可以直接检查拼出的字节，
 * 不必架一条真连接。
 *
 * <p>跨模块公开：写帧的控制器在 {@code codeloom-app}，且要经过授权。
 */
public final class SseFrames {

    /**
     * 开流时立刻发的那一帧注释。
     *
     * <p>不发的话响应头要等到第一条事件才出去；安静的会话可能很久没有事件，
     * {@code EventSource} 会一直停在“连接中”，中间代理也会认为请求没有响应。
     * 注释帧以 {@code :} 开头，客户端忽略其内容，只负责让响应头先出去。
     *
     * @return 注释帧
     */
    public static SseEmitter.SseEventBuilder opening() {
        return SseEmitter.event().comment("codeloom stream open");
    }

    /**
     * 一条事件信封转换为一帧。
     *
     * <p>使用不带 mediaType 的 {@code data} 重载：带上 {@code APPLICATION_JSON} 会走消息
     * 转换器，把一个 String 再序列化一遍。这串 JSON 已经是最终形态。
     *
     * @param envelope 事件信封
     * @param codec    信封编码器
     * @return 帧；只有已落库的事件才带 {@code id:}
     */
    public static SseEmitter.SseEventBuilder of(EventEnvelope envelope, EventEnvelopeCodec codec) {
        SseEmitter.SseEventBuilder frame = SseEmitter.event().data(codec.write(envelope));
        if (envelope.seq() != null) {
            frame.id(String.valueOf(envelope.seq()));
        }
        return frame;
    }

    private SseFrames() {
    }
}
