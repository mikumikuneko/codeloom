/**
 * 把一段还在长的 markdown 切成"已写完的块"与"正在写的块"。
 *
 * 不能整段重渲染：模型逐块吐字，每帧都把累积全文交给解析器，代价随长度平方增长。
 * 切开之后已写完的块是不再变化的字符串，上层按字符串记忆，每帧只有末尾一块过
 * 解析器。
 *
 * 按空行切是合法的：markdown 里空行就是块的分界。围栏代码块是唯一例外（代码里
 * 可以有空行），所以记着围栏开合，只在围栏外认空行。
 *
 * 切错了只是一时观感（例如松散列表被切成两个，中间多点空隙）；消息落地后上层
 * 整段渲染一次，那时是唯一正确的结果。
 */

/** 围栏的开合记号：三个以上的 ` 或 ~，前面最多三个空格。 */
const FENCE = /^ {0,3}(`{3,}|~{3,})/

/**
 * @param text 到目前为止收到的全部正文
 * @returns `blocks` 是已经封口的块（顺序即出现顺序），`tail` 是还没封口的那一块
 */
export function splitStreamingBlocks(text: string): { blocks: string[]; tail: string } {
  const blocks: string[] = []
  let current: string[] = []
  /** 当前围栏的开头记号（` 还是 ~，以及有几个）；null 表示不在围栏里 */
  let fence: string | null = null

  function seal() {
    if (current.length > 0) {
      blocks.push(current.join('\n'))
      current = []
    }
  }

  for (const line of text.split('\n')) {
    // 围栏外的空行就是块的分界。
    if (fence === null && line.trim() === '') {
      seal()
      continue
    }

    const marker = FENCE.exec(line)?.[1] ?? null
    if (marker !== null) {
      if (fence === null) {
        fence = marker
      } else if (marker[0] === fence[0] && marker.length >= fence.length) {
        // 收尾围栏必须和开头同一种记号（``` 不能用 ~~~ 收），这是 CommonMark 的规矩。
        fence = null
      }
    }
    current.push(line)
  }

  // 末尾没有空行收尾：这一块还在长，不能冻住它。
  return { blocks, tail: current.join('\n') }
}

/**
 * 一份切好的代码：已写完的行、正在写的行，以及解析器补的换行。
 *
 * @param complete 已写完的行，带着最后一个换行交给高亮器
 * @param partial  正在写的行，不上色，原样接在后面
 * @param trailingBreak 解析器补的收尾换行，要还回去（见 splitPartialLine）
 */
export interface CodeParts {
  complete: string
  partial: string
  trailingBreak: string
}

/**
 * 把一段还在长的代码切成"已写完的行"和"正在写的行"。
 *
 * 块级切分拿围栏代码块没办法：未等到收尾围栏的整段都是"正在写的块"。问题集中在
 * 最后一行：模型逐字吐，前面各行在换行后不再变，只有最后一行每帧在变。切开后
 * 交给高亮器的文本按值相等（最后一行在 partial 里），effect 依赖不变，高亮器
 * 不会被唤醒。最后一行先不上色，等换行再上：否则它的颜色会随字逐个加上反复
 * 变化，看起来像在闪。
 *
 * 两个不显然的细节：
 *
 * 1. react-markdown 给块级代码补过一个收尾换行（源码 ```java\nint a;} 交出来的是
 *    "int a;\n"）。不先摘掉它，最后一行后面永远跟着空行，partial 永远是空的，
 *    这段代码就变成空转。
 * 2. "最后一行没写完"从字符串上看不出来：源码 "int a;"（仍在写）和 "int a;\n"
 *    （刚回车）交出来都是 "int a;\n"。判据只能回到原始正文：它最后一行没换行，
 *    且这一段顶在末尾。所以这个函数收两个参数。
 *
 * 真正的增量分词做不到：需要第 K 行处的语法状态，而 shiki 不暴露它；跨行结构
 * （块注释、多行字符串、Python 三引号）让语法状态穿过行边界，缓存前半段 token
 * 会出错，后面新增的一行可能重新解释前面某行。
 *
 * @param code   react-markdown 交给代码块的文本，带着它补的收尾换行
 * @param source 这一整段正文；判"是否还在写"只能看它，理由见第 2 条
 */
export function splitPartialLine(code: string, source: string): CodeParts {
  const trailingBreak = code.endsWith('\n') ? '\n' : ''
  const body = trailingBreak === '' ? code : code.slice(0, -1)

  // 只有顶在整个正文末尾、且正文最后一行还没换行的那一块才真在写。少了前半句，
  // 尾巴里已经收尾的代码块会被一起摘掉最后一行。
  const writing = !source.endsWith('\n') && source.trimEnd().endsWith(body.trimEnd())
  if (!writing) {
    return { complete: body, partial: '', trailingBreak }
  }

  const cut = body.lastIndexOf('\n')
  return cut < 0
    ? { complete: '', partial: body, trailingBreak }
    : { complete: body.slice(0, cut + 1), partial: body.slice(cut + 1), trailingBreak }
}
