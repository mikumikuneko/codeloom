import type { ThemeRegistration } from 'shiki/core'

/**
 * JetBrains **Darcula** —— 照 IDEA 那套配色写的 TextMate 主题。
 *
 * <h2>注意拼写：Darcula ≠ Dracula</h2>
 * IDEA 的这套叫 <b>Darcula</b>（橙关键字、黄方法名、灰注释）；
 * 另有一套很有名的叫 <b>Dracula</b>（紫粉调）—— 两者半点关系都没有。
 * shiki 内置的是**后者**，所以这里只能自己写。
 *
 * <h2>它是"等价"而不是"逐像素一样"</h2>
 * IDEA 的颜色是它的 **Java 词法器**决定的，而这里是把同一套颜色映射到 **TextMate 的
 * scope** 上。两边的切分方式不一样（比如 IDEA 区分"字段/参数/局部变量"，
 * 而 TextMate 的 Java 语法只在部分位置标得出这个区别），所以：
 *
 * <ul>
 *   <li>能对上的（关键字、方法名、注释、字符串、数字、注解）**照搬原值**；</li>
 *   <li>对不上的（字段那种需要语义分析的）**退回默认前景色** —— 猜着上色会让
 *       "同一个东西在不同地方颜色不一样"，那比没有颜色更让人分心。</li>
 * </ul>
 *
 * <h2>默认前景是 {@code #A9B7C6}，不是我们自己的前景色</h2>
 * 这是**全站唯一一处没有跟着我们的前景色走的地方**，而且是刻意的：Darcula 的观感有很大
 * 一部分来自这层微微偏蓝的灰。跟着它，代码那一片才是"你在 IDEA 里读到的那个样子"。
 *
 * <p>底越暗它越吃亏这件事**量过了，是反的**（2026-10-05，用 WCAG 相对亮度算的）：
 * 同一套 token 在 IDEA 的 {@code #2b2b2b} 上，注释 3.59、字符串 3.52、常量 3.71 ——
 * **三条都不及格**（AA 线 4.5）；搬到我们现在的 {@code #0a0a0c} 上是 5.01 / 4.92 / 5.19，
 * 全都过线。所以"给 #2b2b2b 调的主题放到更暗的底上会水土不服"这个直觉在这一套上不成立，
 * 不需要换主题，也不需要把前景提亮成我们自己的白。
 */
export const DARCULA = {
  name: 'codeloom-darcula',
  type: 'dark',
  colors: {
    // 背景用我们的 —— 这个名字只是为了完整性，实际渲染由 CodeLines 自己铺底色
    'editor.background': '#0a0a0c',
    'editor.foreground': '#a9b7c6',
  },
  settings: [
    // TextMate 的规则是**后面的覆盖前面的**，所以顺序在这里是有含义的：
    // 越具体的越靠后
    { settings: { foreground: '#a9b7c6' } },

    { scope: ['comment', 'punctuation.definition.comment'], settings: { foreground: '#808080' } },

    /*
     * 关键字：public / final / class / extends / int / boolean / return / new
     *
     * <h2>这里**不能**写 `storage` 一个词盖过去 —— 这是实测出来的</h2>
     * Java 的 TextMate 语法把三样东西都归在 `storage` 底下：
     *
     * <ul>
     *   <li>{@code storage.modifier} —— {@code public} / {@code final} / {@code class} /
     *       {@code extends}：IDEA 里是**橙**的</li>
     *   <li>{@code storage.type.primitive} —— {@code int} / {@code long} / {@code boolean}：
     *       也是**橙**的（它们在 Java 里本来就是关键字）</li>
     *   <li>{@code storage.type} —— {@code String} / {@code List} / 所有类名：
     *       IDEA 里是**默认色**，一个字都不染</li>
     * </ul>
     *
     * <p>写成 `['keyword', 'storage']` 的话，第三类也会变橙 —— 而一个 Java 文件里
     * 类型名出现的次数远多于关键字。那不是"高亮不一样"，那是**整片颜色都错了**。
     * （这条是拿一段真代码跑 {@code codeToTokens} 打印出来的，不是看文档猜的。）
     */
    {
      scope: ['keyword', 'storage.modifier', 'storage.type.primitive'],
      settings: { foreground: '#cc7832' },
    },
    // 运算符不是关键字：`=`、`(`、`.` 在 Darcula 里就是默认色
    { scope: ['keyword.operator'], settings: { foreground: '#a9b7c6' } },
    // 注解（@Override 之类）是橄榄色 —— 要排在 keyword/storage **之后**才盖得住
    {
      scope: ['storage.type.annotation', 'meta.declaration.annotation', 'punctuation.decorator'],
      settings: { foreground: '#bbb529' },
    },

    { scope: ['string', 'punctuation.definition.string'], settings: { foreground: '#6a8759' } },
    { scope: ['constant.numeric'], settings: { foreground: '#6897bb' } },
    { scope: ['constant.language', 'constant.character.escape'], settings: { foreground: '#9876aa' } },

    // 方法名：**声明和调用同色**，这正是 IDEA 的观感来源之一
    { scope: ['entity.name.function', 'support.function'], settings: { foreground: '#ffc66d' } },

    // 类名、类型名：Darcula 里就是默认前景色（不上色）
    {
      scope: ['entity.name.type', 'entity.name.class', 'support.class', 'support.type'],
      settings: { foreground: '#a9b7c6' },
    },

    // 标记语言那几个（HTML/XML 也走这个主题）
    { scope: ['entity.name.tag'], settings: { foreground: '#e8bf6a' } },
    { scope: ['entity.other.attribute-name'], settings: { foreground: '#bababa' } },

    /*
     * Markdown（以及任何走 markup.* 的语法）。
     *
     * <h2>这一组从前**一条都没有**，那是个真 bug</h2>
     * Markdown 的 scope 全部住在 {@code markup.*} 底下（{@code markup.heading.markdown}、
     * {@code markup.bold.markdown}……），而上面那些规则一条都盖不到它。后果不是"颜色不太好看"，
     * 是**整片一个颜色**：每个 token 都掉回默认前景色。
     *
     * <p>这是拿一段真 Markdown 跑 {@code codeToTokens} 打出来的，不是看文档猜的 ——
     * 同一份主题下 Java 每行都是多色（{@code class} 橙、注释灰、字符串绿），
     * Markdown 每一行都只有 {@code #A9B7C6}。
     *
     * <h2>颜色从哪儿来</h2>
     * 和这个文件开头那条原则一样：**能对上的照搬原值**。
     * 标题在 IDEA 的 Markdown 里不靠颜色、靠**字重**，所以它拿默认色加粗；
     * 行内代码和字符串是同一个东西（一段不参与解释的字面量），用同一个绿；
     * 标记号（{@code #}、{@code -}）和注释同色 —— 它们是记号，不是内容。
     */
    { scope: ['markup.heading'], settings: { foreground: '#a9b7c6', fontStyle: 'bold' } },
    {
      scope: ['punctuation.definition.heading', 'punctuation.definition.list'],
      settings: { foreground: '#808080' },
    },
    { scope: ['markup.bold'], settings: { foreground: '#a9b7c6', fontStyle: 'bold' } },
    { scope: ['markup.italic'], settings: { foreground: '#a9b7c6', fontStyle: 'italic' } },
    { scope: ['markup.inline.raw', 'markup.raw.block', 'markup.raw'], settings: { foreground: '#6a8759' } },
    { scope: ['markup.quote'], settings: { foreground: '#808080' } },
    // 链接用数字那档蓝：都是"指向别处"的东西，同一档颜色说得通
    { scope: ['markup.underline.link', 'string.other.link'], settings: { foreground: '#6897bb' } },
  ],
} satisfies ThemeRegistration
