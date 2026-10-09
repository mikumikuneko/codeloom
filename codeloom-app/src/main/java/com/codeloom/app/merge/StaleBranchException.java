package com.codeloom.app.merge;

/**
 * 合并之前发现这条分支**落后于主干** —— 所以这次合并不接受。
 *
 * <h2>为什么是"拒绝"而不是"照合不误"</h2>
 * 落后者直接去合，git 会在**主干**上撞出冲突、把主干的工作区占住（未完成的合并），
 * 直到有人裁决完。而先同步一次的话，那一刻的合并是**快进**，根本不可能冲突。
 *
 * <p>也就是说：拒绝的代价是"再点一次"（而且第二次会先自动同步），
 * 而照合的代价是"主干被占住 + 一次本可以避免的裁决"。
 *
 * <h2>什么时候会真出现</h2>
 * 罕见 —— 合并会**先自动同步**，同步完紧接着就在项目锁里查这一条。中间那个窗口只有毫秒级，
 * 要正好被另一个会话的合并插进去才会撞上。所以它的文案要说清"再点一次就行"，
 * 而不是让人以为出了什么大事。
 */
public class StaleBranchException extends RuntimeException {

    private final transient int behind;

    public StaleBranchException(int behind) {
        super("这条分支还落后主干 " + behind + " 个提交 —— 刚有别的会话合进去了。"
                + "再点一次合并即可：那一次会先把主干同步进来，然后就是快进，不会再撞。");
        this.behind = behind;
    }

    /** 落后多少个提交。 */
    public int behind() {
        return behind;
    }
}
