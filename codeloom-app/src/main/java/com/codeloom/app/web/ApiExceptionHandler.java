package com.codeloom.app.web;

import com.codeloom.app.merge.ConflictPendingException;
import com.codeloom.app.merge.StaleBranchException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把「领域说这个输入不合法」翻译成 400。
 *
 * <h2>为什么是这一条规则，而不是在每个 DTO 上加校验注解</h2>
 * 领域的紧凑构造器已经把所有不变量守住了，而且那些消息是好的
 * （{@code "maxTokens 必须是正数，收到 0"}）。再在 DTO 上抄一遍，
 * 就等于同一套规则有两份，迟早会不一致 —— 而"客户端拦住了、领域没拦住"
 * 或者反过来的那种不一致，最难发现。
 *
 * <h2>代价要说清楚</h2>
 * {@code IllegalArgumentException} 也覆盖另外一类情况：我们自己代码里的 bug
 * （比如传了个 null 给不该传的地方）。那些会被报成 400，而不是 500 ——
 * 也就是**把"客户端的错"和"我们的错"混在了一起**。
 *
 * <p>认可这个代价的理由：领域里抛它的地方全都是"校验输入"这一个用途
 * （见各个 record 的紧凑构造器），而且那些消息本身会让原因一眼可见。
 * 哪天领域开始拿它表示别的意思，这条映射就该拆细了。
 *
 * <p>返回的是 {@code ProblemDetail}（RFC 7807），不是自己编的形状 ——
 * 它已经是一个标准，客户端和调试工具都认得。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("请求内容不合法");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * 把 {@code ResponseStatusException} 转成和上面一样的形状。
     *
     * <h2>为什么不接这一步，客户端就只能看到一个光秃秃的状态码</h2>
     * Boot 的 {@code server.error.include-message} 默认是 {@code never} ——
     * 框架抛出的异常消息**不会**进响应体。那是好的默认（它拦住了内部信息泄露），
     * 但它同样拦掉了我们自己写的那句 reason，于是：
     *
     * <pre>
     *   409 {"status":409,"error":"Conflict","path":"..."}
     * </pre>
     *
     * 而 409 在我们这儿有好几种含义 —— "这条会话正在执行中"、"这条分支落后于主干"、
     * "合并有冲突、需要裁决"，处置方式完全不同。客户端分不出来，
     * 就只能把状态码直接甩给用户。
     *
     * <p>把 reason 带出去的风险是可控的：这些 message 是我们自己写给调用方看的。
     * 真正意外的异常仍然走 Boot 的默认路径，不泄露任何东西。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onResponseStatus(ResponseStatusException e) {
        ProblemDetail problem = ProblemDetail.forStatus(e.getStatusCode());
        problem.setTitle("请求无法完成");
        problem.setDetail(e.getReason());
        return problem;
    }

    /**
     * 合并之前发现这条分支落后于主干 —— **409**，而且文案要说清"再点一次就行"。
     *
     * <p>不是 500：服务端没有任何东西坏掉，"你出发得早、别人先到了"是一个**并发事实**，
     * 客户端按它照做就能过去。
     */
    @ExceptionHandler(StaleBranchException.class)
    public ProblemDetail onStaleBranch(StaleBranchException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("这条分支落后于主干");
        problem.setDetail(e.getMessage());
        problem.setProperty("commitsBehind", e.behind());
        return problem;
    }

    /**
     * 合并有冲突。**把冲突清单一起放进响应体**（{@code conflictingPaths} 扩展字段）——
     * 客户端拿到 409 之后立刻要知道"裁决哪些文件"，让它再发一个请求去问是没有必要的往返。
     */
    @ExceptionHandler(ConflictPendingException.class)
    public ProblemDetail onConflictPending(ConflictPendingException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("合并有冲突，需要裁决");
        problem.setDetail(e.getMessage());
        problem.setProperty("conflictingPaths", e.conflictingPaths());
        return problem;
    }
}
