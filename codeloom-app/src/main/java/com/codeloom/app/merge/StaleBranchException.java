package com.codeloom.app.merge;

/**
 * 合并前发现分支落后于主干，因此拒绝这次合并。
 *
 * <p>落后者直接合，git 会在主干上撞出冲突并占住主干（未完成的合并），直到有人裁决；
 * 先同步一次再合则是快进，合并本身不会冲突——但同步那一步仍可能撞冲突，需要先裁决。
 * 拒绝的代价只是再点一次（第二次会先自动同步）。
 *
 * <p>实际很少出现：合并会先自动同步，随后在项目锁内做检查，中间只有毫秒级窗口。
 * 文案因此要说清"再点一次即可"。
 */
public class StaleBranchException extends RuntimeException {

    private final transient int behind;

    /**
     * @param behind 落后主干的提交数
     */
    public StaleBranchException(int behind) {
        super("这条分支还落后主干 " + behind + " 个提交 —— 刚有别的会话合进去了。"
                + "再点一次合并即可：那一次会先把主干同步进来，然后就是快进，不会再撞。");
        this.behind = behind;
    }

    /** @return 落后主干的提交数。 */
    public int behind() {
        return behind;
    }
}
