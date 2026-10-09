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
 * 一条会话的事件流，走 SSE 下行。
 *
 * <pre>
 *   GET /api/sessions/{sessionId}/stream
 *   Last-Event-ID: 42          ← 断线重连时浏览器自动带上（它自己记的）
 * </pre>
 *
 * <h2>帧长什么样</h2>
 * <pre>
 *   id: 42
 *   data: {"sessionId":"…","seq":42,"at":"…","type":"TOOL_RESULT","payload":{…}}
 *
 * </pre>
 * <ul>
 *   <li>{@code id:} **只有已落库的事件才有**。它是重连时要回传的游标，
 *       给一条正在打字中的流式增量安上它，客户端就会从一个不存在的 seq 接着拉。
 *   <li>类型放在 {@code data} 里、**不用 {@code event:} 字段**：那个 JSON 本来就要带
 *       {@code type}（反序列化端靠它判别），再写一个 {@code event:} 就是同一个信息存两份，
 *       而两份就有机会不一致。
 * </ul>
 *
 * <h2>鉴权：两件事，缺一不可</h2>
 * <ol>
 *   <li><b>认证</b>由 Spring Security 兜（{@code SecurityConfig}：
 *       {@code anyRequest().authenticated()}）。身份仍然是**会话 cookie** ——
 *       {@code EventSource} 发不了自定义请求头，所以这条通道上除了 cookie 没有别的办法
 *       带凭据。未登录访问得到 401。
 *   <li><b>授权</b>由本类自己兜（{@link ProjectAccess#requireVisible}）：
 *       「登录了」不等于「你能看这条会话」。别处的会话接口都过这一道，这条通道也不例外 ——
 *       一个只做了认证的端点，在 URL 里换一个 sessionId 就能订阅别人的事件流。
 * </ol>
 *
 * <p><b>为什么这个控制器住在 app 而不是 realtime：</b>因为上面那第 2 条。
 * 授权类 {@code ProjectAccess} 住在 app，而依赖方向是 {@code app → realtime} ——
 * realtime 够不到它；放在 realtime 的话那一层就只剩认证可做。
 *
 * <p>于是分工是：**凡是需要授权的入口都在 app**
 * （所有控制器、WebSocket 端点注册都在这里），而 realtime 管的是
 * "消息怎么流"（{@link SessionStreamService}、{@link EventBus}、SSE 帧格式），
 * 不管"谁可以看"。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionStreamController {

    /**
     * 连接的最长寿命，默认 30 分钟。
     *
     * <p>**刻意不设成"永不过期"**：客户端静默消失（拔网线、进程被杀）时，
     * 服务端这边没有通知，只能靠写失败来发现。而一条正好没有任何事件的会话
     * 永远不会有写失败的机会 —— 那样它就会一直占着一个订阅。
     * 到期后浏览器会自动重连并带上 {@code Last-Event-ID}，什么都不会丢。
     *
     * <p>做成可配是为了测试能调到 1 秒 —— 否则每条开了流的测试都要等 30 分钟才会
     * 让那个异步请求结束，而容器关闭时会一直等它。
     */
    private final Duration streamTimeout;

    private final SessionStreamService streams;
    private final CurrentUser currentUser;
    private final ProjectAccess access;
    private final EventEnvelopeCodec codec = new EventEnvelopeCodec();

    public SessionStreamController(SessionStreamService streams,
                                   CurrentUser currentUser,
                                   ProjectAccess access,
                                   @Value("${codeloom.stream.timeout:30m}") Duration streamTimeout) {
        this.streams = streams;
        this.currentUser = currentUser;
        this.access = access;
        this.streamTimeout = streamTimeout;
    }

    @GetMapping(path = "/api/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Principal principal,
                            @PathVariable String sessionId,
                            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        // ★ 授权必须在**建 emitter 之前**。
        //
        //   一旦把 emitter 返回出去，响应头和 200 就已经在路上了 —— 那时候再抛异常
        //   只会变成"流里面的一帧错误"，客户端看到的是"连上了，然后坏了"，
        //   而它该看到的是一个干净的 404。这也是为什么校验不能塞进下面的 try 里。
        //
        //   用的是 requireVisible 而不是 requireDriver：**观战要看得到别人的会话**。
        //   这条通道是观战的主干，所以它必须对项目成员开放 —— 但只对成员开放。
        User me = currentUser.require(principal);
        access.requireVisible(me.id(), SessionId.of(sessionId));

        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        AtomicReference<EventBus.Subscription> subscription = new AtomicReference<>();

        // 客户端消失有好几种方式（正常关、超时、写失败），每一种都得把订阅关掉 ——
        // 漏掉任何一个，订阅表就会随着"历史上连过的连接数"一直长
        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(cause -> close(subscription));

        Consumer<EventEnvelope> sink = envelope -> {
            if (!write(emitter, envelope)) {
                // 写不进去就是"这个客户端已经不在了"。关掉订阅，后面的就不用再往这儿发了
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
     * @return false 表示这个连接已经写不进去了（客户端走了）
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
