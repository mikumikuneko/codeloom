package com.codeloom.workspace.git;

import com.codeloom.workspace.exec.ProcessOutcome;

import java.util.List;

/**
 * git 命令没有在超时内结束，被强制终止。
 *
 * <p>与“退出码非零”分开：前者是暂时性故障（可以稍后重试），后者才是“这个提交/路径
 * 问不出来”。继承 {@link GitCommandException}，所以不区分原因的调用方行为不变。
 */
public class GitCommandTimeoutException extends GitCommandException {

    public GitCommandTimeoutException(List<String> command, ProcessOutcome outcome, String message) {
        super(command, outcome, message);
    }
}
