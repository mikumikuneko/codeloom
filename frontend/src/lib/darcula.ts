import type { ThemeRegistration } from 'shiki/core'

/**
 * JetBrains Darcula：照 IDEA 那套配色写的 TextMate 主题。
 *
 * 注意拼写：Darcula 是 IDEA 这套（橙关键字、黄方法名、灰注释），Dracula 是另一套
 * 紫粉调主题，两者无关。shiki 内置的是后者，所以这里只能自己写。
 *
 * 它是等价而不是逐像素一致：IDEA 的颜色由 Java 词法器决定，这里把同一套颜色映射
 * 到 TextMate scope。能对上的（关键字、方法名、注释、字符串、数字、注解）照搬
 * 原值；对不上的（字段等需要语义分析的）退回默认前景色，猜着上色会让同一个东西
 * 在不同地方颜色不同。
 *
 * 默认前景是 #A9B7C6，这是全站唯一没有跟着我们的前景色走的地方：Darcula 的观感
 * 很大一部分来自这层微蓝的灰。实测（2026-10-05，WCAG 相对亮度）：同一套 token 在
 * IDEA 的 #2b2b2b 上，注释 3.59、字符串 3.52、常量 3.71，三条都不及 AA 的 4.5；
 * 在 #0a0a0c 上是 5.01 / 4.92 / 5.19，全部过线。所以不需要换主题，也不需要把
 * 前景提亮成我们自己的白。
 */
export const DARCULA = {
  name: 'codeloom-darcula',
  type: 'dark',
  colors: {
    // 背景用我们的；这个名字只为完整性，实际渲染由 CodeLines 铺底色。
    'editor.background': '#0a0a0c',
    'editor.foreground': '#a9b7c6',
  },
  settings: [
    // TextMate 规则后面的覆盖前面的，所以顺序有含义：越具体的越靠后。
    { settings: { foreground: '#a9b7c6' } },

    { scope: ['comment', 'punctuation.definition.comment'], settings: { foreground: '#808080' } },

    /*
     * 关键字：public / final / class / extends / int / boolean / return / new
     *
     * 不能写 storage 一个词盖过去（实测）：Java 的 TextMate 语法把三样都归在
     * storage 底下。
     *
     * storage.modifier（public / final / class / extends）在 IDEA 里是橙色；
     * storage.type.primitive（int / long / boolean）也是橙色，它们在 Java 里本就是
     * 关键字；storage.type（String / List 和所有类名）在 IDEA 里是默认色。
     *
     * 写成 ['keyword', 'storage'] 会让第三类也变橙，而 Java 文件里类型名出现得
     * 远多于关键字，结果不是高亮不一样，是整片颜色都错了。
     */
    {
      scope: ['keyword', 'storage.modifier', 'storage.type.primitive'],
      settings: { foreground: '#cc7832' },
    },
    // 运算符不是关键字：=、(、. 在 Darcula 里就是默认色。
    { scope: ['keyword.operator'], settings: { foreground: '#a9b7c6' } },
    // 注解（@Override 之类）是橄榄色；必须排在 keyword/storage 之后才盖得住。
    {
      scope: ['storage.type.annotation', 'meta.declaration.annotation', 'punctuation.decorator'],
      settings: { foreground: '#bbb529' },
    },

    { scope: ['string', 'punctuation.definition.string'], settings: { foreground: '#6a8759' } },
    { scope: ['constant.numeric'], settings: { foreground: '#6897bb' } },
    { scope: ['constant.language', 'constant.character.escape'], settings: { foreground: '#9876aa' } },

    // 方法名：声明和调用同色，这是 IDEA 观感的一部分。
    { scope: ['entity.name.function', 'support.function'], settings: { foreground: '#ffc66d' } },

    // 类名、类型名在 Darcula 里就是默认前景色，不上色。
    {
      scope: ['entity.name.type', 'entity.name.class', 'support.class', 'support.type'],
      settings: { foreground: '#a9b7c6' },
    },

    // 标记语言的标签与属性（HTML/XML 也走这个主题）。
    { scope: ['entity.name.tag'], settings: { foreground: '#e8bf6a' } },
    { scope: ['entity.other.attribute-name'], settings: { foreground: '#bababa' } },

    /*
     * Markdown（以及任何走 markup.* 的语法）。
     *
     * 这一组曾一条都没有，是真 bug：Markdown scope 全在 markup.* 下，上面那些规则
     * 一条都盖不到，每个 token 掉回默认前景色，整片一个颜色（拿真 Markdown 跑
     * codeToTokens 验过；同一主题下 Java 每行多色，Markdown 每行只有 #A9B7C6）。
     *
     * 颜色来自同一原则：能对上的照搬原值。标题在 IDEA 的 Markdown 里靠字重而不是
     * 颜色，所以拿默认色加粗；行内代码与字符串是同一类字面量，用同一个绿；标记号
     * （#、-）与注释同色，它们是记号不是内容。
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
    // 链接用数字那档蓝：都是指向别处的东西，同一档颜色说得通。
    { scope: ['markup.underline.link', 'string.other.link'], settings: { foreground: '#6897bb' } },
  ],
} satisfies ThemeRegistration
