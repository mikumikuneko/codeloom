import { attention } from 'micromark-core-commonmark'
import { classifyCharacter } from 'micromark-util-classify-character'
import { codes, constants } from 'micromark-util-symbol'
import type { Construct, Extension, State, Tokenizer } from 'micromark-util-types'
import type { Plugin } from 'unified'
// 这个 import 只用它的副作用：它给 unified 的 Data 补上了 micromarkExtensions 的类型
import type {} from 'remark-parse'

/**
 * 让**中文**里的 `**加粗**` 能正常收尾。
 *
 * <h2>它修的是哪一句话</h2>
 * 中文写作里 `**结论：**后面接着写` 这种形状到处都是 —— 冒号在粗体里面，
 * 粗体紧挨着下一个汉字，中间没有空格。而 CommonMark 判它**不成立**，
 * 于是界面上原样显示四个星号：
 *
 * <pre>
 *   **注意：**下一步这样做     →  原样显示（错）
 *   **注意：** 下一步这样做    →  加粗正常（多一个空格才行）
 *   **注意:**下一步这样做      →  原样显示（半角冒号也一样）
 *   **第一点。**第二点         →  原样显示
 * </pre>
 *
 * <h2>原因是一条给英文写的规则</h2>
 * CommonMark 规定：收尾的 `**` 必须「右翼」—— 前面不能是空白，并且
 * <b>要么前面不是标点、要么后面是空白或标点</b>。
 *
 * <p>那条「要么后面是空白或标点」是为英文设计的：英文里标点后面本来就该有空格。
 * 中文不写空格，而中文标点（`：`、`。`、`、`）在 Unicode 里确实是标点 ——
 * 于是前半个条件不成立、后半个也不成立，判为不能收尾。
 *
 * <h2>所以补的只有一条</h2>
 * 在 micromark 原本那个判断之外再加一条：<b>收尾的是两个以上星号、紧挨着的前一个
 * 字符是标点、后一个字符是汉字/假名/谚文</b> —— 那就是中文里那个形状，允许收尾。
 *
 * <p>刻意只认**两个以上**星号（粗体），不管单个 `*`（斜体）：单个星号在数学和
 * 通配符里到处都是，放开它换来的是一堆误判，而中文里用单星号强调本来就少见。
 *
 * <p>这条路是**在语法层修**，不是把文本里的星号替换掉 —— 后者会把代码块里的
 * 内容也一起改坏，而代码块里星号是什么意思，只有代码自己知道。
 */

/** 汉字、平假名、片假名、谚文、注音符号。**中文标点不在内**，这是关键。 */
const CJK = /[\p{Script_Extensions=Han}\p{Script_Extensions=Hiragana}\p{Script_Extensions=Katakana}\p{Script_Extensions=Hangul}\p{Script_Extensions=Bopomofo}]/u

function isCjk(code: number | null): boolean {
  return code !== null && code > 0 && CJK.test(String.fromCodePoint(code))
}

/**
 * 和 micromark 自己那个 attention 分词器**逐行对应**，只多一行判断。
 *
 * <p>照抄它的原因：收尾能不能成立，取决于「前一个字符和后一个字符各是什么类」，
 * 而那两个字符只有分词器手上有（`this.previous` 和当前 `code`）。在更外层
 * 去猜这件事，等于把 CommonMark 的规则重写一遍。
 */
const tokenizeCjkAttention: Tokenizer = function (effects, ok, nok) {
  const configured = this.parser.constructs.attentionMarkers.null
  if (configured === undefined) {
    throw new Error('micromark 的 attentionMarkers 没配好：中文加粗那条规则没法生效')
  }
  // 再赋一个 const：上面那句检查把类型收窄了，而下面那两个闭包是在它之前
  // 就被声明提升掉的，直接引用 `configured` 拿不到那次收窄
  const attentionMarkers = configured
  const previous = this.previous
  const before = classifyCharacter(previous)

  let marker: number | null = null
  return start

  function start(code: number | null): State | undefined {
    // 这个构造只挂在星号上，别的字符轮不到它
    if (code !== codes.asterisk) return nok(code)
    marker = code
    effects.enter('attentionSequence')
    return inside(code)
  }

  function inside(code: number | null): State | undefined {
    if (code === marker) {
      effects.consume(code)
      return inside
    }

    const token = effects.exit('attentionSequence')
    const after = classifyCharacter(code)

    // 以下两行是 micromark 原样：`open` / `close` 就是左右翼
    const open = !after
      || (after === constants.characterGroupPunctuation && before)
      || (attentionMarkers.includes(code) && code !== codes.asterisk)
    const commonMarkClose = !before
      || (before === constants.characterGroupPunctuation && after)
      || (attentionMarkers.includes(previous) && previous !== codes.asterisk)

    // ★ 唯一多出来的一条：**中文标点收尾**
    const markerCount = token.end.offset - token.start.offset
    const cjkClose = markerCount >= 2
      && before === constants.characterGroupPunctuation
      && isCjk(code)

    // Boolean(...) 是必须的：中间那一项 `after === X && before` 的值是 `before` 本身
    //（1 或 2），不包一层的话这里拿到的不是布尔
    token._open = Boolean(open)
    token._close = Boolean(commonMarkClose || cjkClose)
    return ok(code)
  }
}

const cjkAttention: Construct = {
  name: 'cjkAttention',
  // 配对（哪一对星号互相认领）**照用 micromark 自己那套** ——
  // 我这里只改了"能不能收尾"，配对和嵌套的规则一个字都不该变
  resolveAll: attention.resolveAll,
  tokenize: tokenizeCjkAttention,
}

/**
 * 挂进 remark 的解析链。
 *
 * <p>{@code micromarkExtensions} 是 remark-parse 约定的入口（它在那儿读这个数组），
 * 所以这里只负责把上面那个扩展塞进去，别的什么都不做。
 */
export const remarkCjkStrong: Plugin = function () {
  const data = this.data()
  const extensions: Extension[] = data.micromarkExtensions ?? (data.micromarkExtensions = [])
  extensions.push({ text: { [codes.asterisk]: cjkAttention } })
}
