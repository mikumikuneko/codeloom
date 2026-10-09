package com.codeloom.domain.event;

/**
 * 一轮跑完之后结算的用量。
 *
 * <h2>为什么按「轮」而不是按「次模型调用」记</h2>
 * 一轮里模型会被调用很多次（每次工具往返都是一次）。逐次落库会让本就在膨胀的
 * {@code event} 表再涨一大截，而**中间那些数字没人要看** —— 要回答的问题是
 * "这一轮花了多少、跑的是哪个模型"，粒度到轮就够。轮的总量已经由
 * {@code TokenUsage.plus} 累加好了。
 *
 * <h2>这条事件不进上下文</h2>
 * 它改变不了模型该看到的东西，而且它**每一轮都会出现** —— 一旦进了上下文，
 * 每一轮的消息前缀都会变，prompt 缓存就此失效。所以投影时明确地不选它
 * （见 {@code ContextAssembler}），但仍然穷尽列出该类型。
 *
 * <h2>刻意没有「缓存写入量」这个字段</h2>
 * {@code TokenUsage} 把缓存归一化成一个「命中」数，而 Anthropic 那类 provider
 * 的**缓存写入是加价的**，需要单独一个数。归一化对多 provider 是对的
 * （各家口径差别太大，不归一只会让上层到处判分支），代价就是这里表达不了它 ——
 * 真接上那种 provider 时再加，而不是现在先摆一个恒为 0 的字段。
 *
 * @param inputTokens       输入**总量**，含下面那个命中缓存的部分
 *                          （和 {@code TokenUsage} 同一个口径 —— provider 报的
 *                          {@code prompt_tokens} 本来就是总数，换个口径记账迟早换算错）
 * @param outputTokens      输出
 * @param reasoningTokens   输出里**花在思考上的**那部分。它是 {@code outputTokens}
 *                          的细分而不是并列项 —— 和 {@code cachedInputTokens} 与
 *                          {@code inputTokens} 的关系一样。provider 不报时是 0
 * @param cachedInputTokens 输入里命中缓存的那部分。**单价低得多，所以要单独记** ——
 *                          只记一个总数的话，"这次调用省了多少"就永远算不出来
 * @param contextTokens     这一轮**收尾时上下文有多大** —— 也就是最后一次模型调用
 *                          发出去的那个请求，服务商数出来的输入 token 数。
 *                          它不是前面几个数加减出来的：那几个是**这一轮一共花了多少**，
 *                          而这个是**当时那份上下文有多大**，两个量。
 *                          <p>{@code null} = **没有读数**：这一轮一次模型都没调成
 *                          （一上来就被取消、或者服务商压根没报用量）。不是 0 ——
 *                          "上下文正好是 0"根本不可能发生，拿 0 当"没有"用，
 *                          读的人就永远得先知道那条暗号
 * @param contextWindow     当时的模型窗口。它和 {@code model} 一起记，是因为
 *                          **换模型会让窗口变** —— 一条会话中途换了模型时，
 *                          旧轮次的分母该是旧的那个，翻回去看的时候才对得上。
 *                          和上面那个同来同去：没有读数时也是 {@code null}
 * @param model             服务商回报的模型名。它**可能和请求时写的不同**
 *                          （别名、路由），记下来才答得出"这条会话到底跑的哪个模型"
 */
public record TurnTokensUsed(int inputTokens,
                             int outputTokens,
                             int reasoningTokens,
                             int cachedInputTokens,
                             Integer contextTokens,
                             Integer contextWindow,
                             String model) implements PersistentEvent {

    public TurnTokensUsed {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("用量不能为负，收到：" + inputTokens + "/" + outputTokens
                    + "/" + reasoningTokens + "/" + cachedInputTokens);
        }
        if (contextTokens != null && contextTokens < 0 || contextWindow != null && contextWindow < 0) {
            throw new IllegalArgumentException("上下文读数不能为负，收到："
                    + contextTokens + "/" + contextWindow);
        }
    }

    /**
     * 这次读数能不能拿来画。
     *
     * <p>**两个都得有**：少一个，那个环要么没分子、要么没分母。
     * 而"有没有"这件事由 {@code null} 回答 —— 不是由 0 回答（那正是这两个字段
     * 从基本类型改成包装类型的理由，见它们的 {@code @param}）。
     */
    public boolean hasContext() {
        return contextTokens != null && contextWindow != null;
    }

    /** 计费口径上的总量。缓存命中的那部分**也在内**，只是单价低。 */
    public long totalTokens() {
        return (long) inputTokens + outputTokens;
    }
}
