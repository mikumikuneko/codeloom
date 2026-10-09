package com.codeloom.realtime.sse;

import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 帧的构造。**单独一个类，是为了让它可测。**
 *
 * <h2>为什么值得为这几行抽一个类</h2>
 * 这里的两条规则都是**有语义的、而且错了不会有任何报错**：
 *
 * <ul>
 *   <li>{@code id:} 只给**已落库**的事件写。它是断线重连的游标，给一条正在打字中的
 *       流式增量也安上一个，客户端重连时就会从一个不存在的 seq 接着拉 —— 表现为
 *       "重连之后少了一段"。
 *   <li>{@code data} 里放的是**已经成形的 JSON**，不能再让消息转换器序列化一遍 ——
 *       那会得到一层多余的引号和转义（同样是静默的：客户端解析出来是个字符串而不是对象）。
 * </ul>
 *
 * <p>两种错误都不会在服务端留下痕迹。要让它们有断言，就得能单独拿到"这一条信封会被
 * 拼成什么字节"—— 而那正是这个类提供的东西：{@link SseEmitter.SseEventBuilder#build()}
 * 是公开的，测试可以直接把结果拼出来看。不必为了验两行格式去架一条真连接。
 *
 * <h2>为什么它是 public</h2>
 * 从这里往哪一层写帧的那个控制器住在 {@code codeloom-app}（它要过授权，
 * 见 {@code SessionStreamController} 的类注释），所以这是个**跨模块的公开接口**，
 * 不再是同包邻居之间的私事。
 */
public final class SseFrames {

    /**
     * 开流时立刻发的那一帧**注释**。
     *
     * <p>它不是装饰：不发的话响应头要等到**第一条事件**才出去，而一条安静的会话可能
     * 很久都没有事件 —— 浏览器那边的 {@code EventSource} 会一直停在"连接中"，
     * 中间的代理也会认为这是个没响应的请求。注释帧以 {@code :} 开头，
     * 客户端会忽略它的内容，它只负责让响应头先出去。
     */
    public static SseEmitter.SseEventBuilder opening() {
        return SseEmitter.event().comment("codeloom stream open");
    }

    /**
     * 一条事件信封 → 一帧。
     *
     * <p>用不带 mediaType 的 {@code data} 重载：带上 {@code APPLICATION_JSON} 会走消息
     * 转换器，而转换器会把一个 String 再序列化一遍。这串 JSON 本来就是最终形态。
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
