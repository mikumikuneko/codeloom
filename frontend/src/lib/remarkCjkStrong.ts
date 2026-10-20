import { attention } from 'micromark-core-commonmark'
import { classifyCharacter } from 'micromark-util-classify-character'
import { codes, constants } from 'micromark-util-symbol'
import type { Construct, Extension, State, Tokenizer } from 'micromark-util-types'
import type { Plugin } from 'unified'
// 这个 import 只用它的副作用：它给 unified 的 Data 补上了 micromarkExtensions 的类型
import type {} from 'remark-parse'

/**
 * 让中文里的 `**加粗**` 能正常收尾。
 *
 * 中文写作里 `**结论：**后面接着写` 很常见：冒号在粗体内，粗体紧挨下一个汉字，
 * 中间没有空格。CommonMark 判它不成立，界面上原样显示星号。
 *
 * CommonMark 规定收尾的 `**` 必须"右翼"：前面不能是空白，且要么前面不是标点，
 * 要么后面是空白或标点。后半个条件是为英文设计的（英文标点后本该有空格）；
 * 中文不写空格，中文标点（`：`、`。`、`、`）在 Unicode 里确实是标点，于是前后
 * 两个条件都不成立。
 *
 * 所以只加一条：收尾的是两个以上星号、前一个字符是标点、后一个字符是汉字/假名/
 * 谚文时允许收尾。刻意不管单个 `*`（斜体）：它在数学和通配符里到处都是，放开会
 * 带来大量误判，而中文里用单星号强调本来就少见。
 *
 * 修在语法层，不替换文本里的星号：后者会把代码块里的内容一起改坏。
 */

/** 汉字、平假名、片假名、谚文、注音符号。中文标点不在内，这是关键。 */
const CJK = /[\p{Script_Extensions=Han}\p{Script_Extensions=Hiragana}\p{Script_Extensions=Katakana}\p{Script_Extensions=Hangul}\p{Script_Extensions=Bopomofo}]/u

function isCjk(code: number | null): boolean {
  return code !== null && code > 0 && CJK.test(String.fromCodePoint(code))
}

/**
 * 与 micromark 的 attention 分词器逐行对应，只多一行判断。
 *
 * 照抄是因为收尾是否成立取决于前后字符各是什么类，而那两个字符只有分词器手上
 * 有（`this.previous` 和当前 `code`）；在更外层猜等于重写 CommonMark 规则。
 */
const tokenizeCjkAttention: Tokenizer = function (effects, ok, nok) {
  const configured = this.parser.constructs.attentionMarkers.null
  if (configured === undefined) {
    throw new Error('micromark 的 attentionMarkers 没配好：中文加粗那条规则没法生效')
  }
  // 再赋一个 const：上面那句检查收窄了类型，而下面两个闭包在它之前声明提升，
  // 直接引用 `configured` 拿不到收窄。
  const attentionMarkers = configured
  const previous = this.previous
  const before = classifyCharacter(previous)

  let marker: number | null = null
  return start

  function start(code: number | null): State | undefined {
    // 这个构造只挂在星号上，别的字符轮不到它。
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

    // 以下两行是 micromark 原样：`open` / `close` 就是左右翼。
    const open = !after
      || (after === constants.characterGroupPunctuation && before)
      || (attentionMarkers.includes(code) && code !== codes.asterisk)
    const commonMarkClose = !before
      || (before === constants.characterGroupPunctuation && after)
      || (attentionMarkers.includes(previous) && previous !== codes.asterisk)

    // 唯一多出来的一条：中文标点收尾。
    const markerCount = token.end.offset - token.start.offset
    const cjkClose = markerCount >= 2
      && before === constants.characterGroupPunctuation
      && isCjk(code)

    // Boolean(...) 是必须的：中间那一项的值是 before 本身（1 或 2），
    // 不包一层这里拿到的不是布尔。
    token._open = Boolean(open)
    token._close = Boolean(commonMarkClose || cjkClose)
    return ok(code)
  }
}

const cjkAttention: Construct = {
  name: 'cjkAttention',
  // 配对（哪一对星号互相认领）照用 micromark 的规则：这里只改"能不能收尾"，
  // 配对和嵌套一个字都不动。
  resolveAll: attention.resolveAll,
  tokenize: tokenizeCjkAttention,
}

/**
 * 挂进 remark 的解析链。
 *
 * `micromarkExtensions` 是 remark-parse 约定的入口，这里只把上面的扩展塞进去。
 */
export const remarkCjkStrong: Plugin = function () {
  const data = this.data()
  const extensions: Extension[] = data.micromarkExtensions ?? (data.micromarkExtensions = [])
  extensions.push({ text: { [codes.asterisk]: cjkAttention } })
}
