package com.codeloom.app.web;

import com.codeloom.app.merge.ConflictPendingException;
import com.codeloom.app.merge.StaleBranchException;
import com.codeloom.domain.port.LeaseUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把领域抛出的输入校验失败翻译成 400。
 *
 * <p>不在 DTO 上加校验注解，是因为领域的紧凑构造器已守住全部不变量且消息可读；
 * 抄一遍会让同一套规则有两份，迟早不一致，而这类不一致最难发现。
 *
 * <p>代价：{@code IllegalArgumentException} 也会覆盖自身代码的 bug（例如给不该传 null 的
 * 地方传了 null），它们会被报成 400 而不是 500，即客户端错误与服务端错误混在一起。
 * 可以接受的前提是领域只把它用于输入校验；一旦表示别的含义，这条映射就该拆细。
 *
 * <p>响应统一用 {@code ProblemDetail}（RFC 7807）。
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {

    /**
     * @param e 领域抛出的输入校验失败
     * @return 400 响应，detail 为领域消息
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("请求内容不合法");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * 把 {@code ResponseStatusException} 转成同样的 ProblemDetail。
     *
     * <p>Boot 的 {@code server.error.include-message} 默认是 {@code never}，框架异常消息不会
     * 进入响应体；不放行我们自己写的 reason 时，客户端只能拿到裸状态码。而 409 在本项目有
     * 多种含义（会话正在执行、分支落后、合并有冲突），处置方式完全不同。
     *
     * <p>带出 reason 的风险可控：这些消息本来就是写给调用方的；意外异常仍走 Boot 默认路径。
     *
     * @param e 携带状态码与 reason 的异常
     * @return 对应状态的 ProblemDetail
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail onResponseStatus(ResponseStatusException e) {
        ProblemDetail problem = ProblemDetail.forStatus(e.getStatusCode());
        problem.setTitle("请求无法完成");
        problem.setDetail(e.getReason());
        return problem;
    }

    /**
     * 租约服务不可达时返回 503 而不是 500：服务本身正常，只是依赖的中间件连不上，
     * 正确处置是退避重试。与"没抢到锁"（409）是两面：409 说明树有人管，503 说明连谁管着
     * 都不知道；两者都不该被客户端当成"可以先跑着"。
     *
     * @param e 租约中间件不可用
     * @return 503 响应
     */
    @ExceptionHandler(LeaseUnavailableException.class)
    public ProblemDetail onLeaseUnavailable(LeaseUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("执行租约服务连不上");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * 合并前发现分支落后于主干：409，并带 {@code commitsBehind}。不是 500 ——
     * 服务端没有故障，"出发得早、别人先到了"是并发事实，按提示再操作一次即可。
     *
     * @param e 落后异常
     * @return 409 响应，附落后提交数
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
     * 合并有冲突：409，并在 {@code conflictingPaths} 扩展字段里给出清单，客户端无需再发一次请求。
     *
     * @param e 冲突异常
     * @return 409 响应，附冲突文件
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
