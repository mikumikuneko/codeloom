package com.codeloom.app.sse;

import com.codeloom.app.auth.CurrentUser;
import com.codeloom.app.auth.ProjectAccess;
import com.codeloom.domain.event.EventEnvelope;
import com.codeloom.domain.port.EventBus;
import com.codeloom.domain.session.SessionId;
import com.codeloom.domain.user.User;
import com.codeloom.realtime.event.EventEnvelopeCodec;
import com.codeloom.realtime.sse.SessionStreamService;
import com.codeloom.realtime.sse.SseFrames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 一条会话的事件流，走 SSE 下行，断线重连靠浏览器自动带上的 {@code Last-Event-ID} 补历史。
 *
 * <p>帧的 {@code id:} 只有已落库的事件才有：它是重连游标，给打字中的流式增量安上它会让
 * 客户端从一个不存在的 seq 接着拉。类型放在 {@code data} 的 JSON 里，不用 {@code event:}
 * 字段，避免同一信息存两份而不一致。
 *
 * <p>认证与授权缺一不可：认证由 Spring Security 用会话 cookie 完成（{@code EventSource}
 * 发不了自定义请求头），未登录为 401；授权由 {@link ProjectAccess#requireVisible} 完成，
 * 因为"登录了"不等于"能看这条会话"，只做认证时换个 sessionId 就能订阅别人的流。
 *
 * <p>控制器放在 app 而不是 realtime：授权类住在 app，依赖方向是 {@code app → realtime}。
 * 凡是需要授权的入口都在 app，realtime 只管消息怎么流
 * （{@link SessionStreamService}、{@link EventBus}、SSE 帧格式），不管谁可以看。
 *
 * <p>通道与认证的理由见
 * {@code docs/decisions/architecture/2026-09-24-two-realtime-channels-and-cookie-auth.md}。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionStreamController {

    /**
     * 连接的最长寿命，默认 30 分钟。刻意不设成永不过期：客户端静默消失（拔网线、进程被杀）
     * 时服务端只能靠写失败发现，而没有事件的会话永远不会写失败，会一直占着订阅。
     * 到期后浏览器自动重连并带 {@code Last-Event-ID}，不会丢事件。
     *
     * <p>可配置是为了测试能调到 1 秒；否则开了流的测试要等 30 分钟，容器关闭也会一直等。
     */
    private final Duration streamTimeout;

    private final SessionStreamService streams;
    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    public SessionStreamController(SessionStreamService streams,
                                   CurrentUser currentUser,
                                   ProjectAccess access,
                                   @Value("${codeloom.stream.timeout}") Duration streamTimeout) {
        this.streams = streams;
        this.currentUser = currentUser;
        this.access = access;
        this.streamTimeout = streamTimeout;
    }

    /**
     * 订阅一条会话的事件流。
     *
     * @param lastEventId 断线重连的游标；为空表示从头补历史
     * @return SSE 连接
     */
    @GetMapping(path = "/api/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Principal principal,
                            @PathVariable String sessionId,
                            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        // 授权必须在建 emitter 之前：一旦返回 emitter，响应头和 200 已经在路上，
        // 再抛异常只会变成流里的一帧错误，客户端看到"连上了然后坏了"而不是干净的 404。
        // 用 requireVisible 而不是 requireDriver：观战需要看到别人的会话，但只对成员开放
        User me = currentUser.require(principal);
        access.requireVisible(me.id(), SessionId.of(sessionId));

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        AtomicReference<EventBus.Subscription> subscription = new AtomicReference<>();

        // 正常关闭、超时、写失败都要关订阅，漏掉任何一个，订阅表会随历史连接数一直增长
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> {
            // 超时不能只关订阅，还必须 complete() 掉 emitter：连接背后是带超时的
            // DeferredResult，结果未设置时框架会设 AsyncRequestTimeoutException 并走错误分派，
            // 用已定死 text/event-stream 的响应去渲染 JSON 错误体（日志里的
            // "No converter for LinkedHashMap with preset Content-Type 'text/event-stream'"
            // 和 "Session was invalidated" 都来自这条路径）。complete() 设置结果后错误路径不再走
            close(subscription);
            emitter.complete();
        });
        // 不要在 onError 里 complete()：send 出错后不该再调它
        emitter.onError(cause -> close(subscription));

        Consumer<EventEnvelope> sink = envelope -> {
            if (!write(emitter, envelope)) {
                // 写不进去说明客户端已经不在了；关掉订阅，后续不再发送
                close(subscription);
            }
        };

        try {
            subscription.set(streams.stream(SessionId.of(sessionId), lastEventId, sink));
            // 立刻发一帧注释，把响应头刷出去 —— 理由见 SseFrames.opening()
            emitter.send(SseFrames.opening());
        } catch (RuntimeException | IOException e) {
            close(subscription);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * @param envelope 要推送的事件
     * @return false 表示连接已写不进去（客户端走了）
     */
    private boolean write(SseEmitter emitter, EventEnvelope envelope) {
        try {
            emitter.send(SseFrames.of(envelope, codec));
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    private static void close(AtomicReference<EventBus.Subscription> subscription) {
        EventBus.Subscription opened = subscription.getAndSet(null);
        if (opened != null) {
            opened.close();
        }
    }
}
