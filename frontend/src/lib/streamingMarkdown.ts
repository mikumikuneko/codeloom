/**
 * 把一段**还在长**的 markdown 切成"已经写完的几块" + "正在写的那一块"。
 *
 * <h2>为什么不能整段重渲染</h2>
 * 模型是一小块一小块吐字的，而界面每帧都要跟着变。如果每一帧都把累积的全文
 * 交给 markdown 解析器，代价就是**平方级**的：回复长到几千字时，前一千字会被
 * 重复解析几百遍，而那正好是它该最顺的时候。
 *
 * <p>切开之后，已经写完的那几块是**不会再变的字符串** —— 上层按字符串做记忆，
 * 它们就一帧也不会再被解析。每帧真正过解析器的，只有末尾那一块。
 *
 * <h2>凭什么可以按空行切</h2>
 * 因为 markdown 里空行就是块与块的分界。围栏代码块是唯一的例外
 *（代码里当然可以有空行），所以下面记着围栏的开合，只在围栏**外面**认空行。
 *
 * <h2>切错了会怎样</h2>
 * 顶多是一时的观感：比如一个"松散列表"（列表项之间有空行）会被切成两个列表，
 * 中间多一点点空隙。**它自己会好** —— 消息一落地，上层就把整段一次性渲染了，
 * 那时的结果是唯一的正确结果。所以这里不需要追求完美，只需要把常见形状切对。
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
  /** 当前围栏的开头记号（` 还是 ~，以及有几个）。null = 不在围栏里 */
  let fence: string | null = null

  function seal() {
    if (current.length > 0) {
      blocks.push(current.join('\n'))
      current = []
    }
  }

  for (const line of text.split('\n')) {
    // 围栏**外面**的空行，就是块的分界
    if (fence === null && line.trim() === '') {
      seal()
      continue
    }

    const marker = FENCE.exec(line)?.[1] ?? null
    if (marker !== null) {
      if (fence === null) {
        fence = marker
      } else if (marker[0] === fence[0] && marker.length >= fence.length) {
        // 收尾的围栏必须和开头同一种记号（``` 不能用 ~~~ 收），这是 CommonMark 的规矩
        fence = null
      }
    }
    current.push(line)
  }

  // 末尾没有空行收尾 → 这一块还在长，不能冻住它
  return { blocks, tail: current.join('\n') }
}

/**
 * 一份切好的代码：已经写完的那些行、正在写的那一行、以及解析器补的那个换行。
 *
 * @param complete 已经写完的那些行（**带着它最后那个换行**，交给高亮器）
 * @param partial  正在写的那一行（**不上色**，原样接在后面）
 * @param trailingBreak 解析器补的那个收尾换行（要还回去，见下）
 */
export interface CodeParts {
  complete: string
  partial: string
  trailingBreak: string
}

/**
 * 把一段**还在长**的代码切成"已经写完的那些行"和"正在写的那一行"。
 *
 * <h2>为什么要切</h2>
 * 上面那个函数切的是**块**，而它拿围栏代码块没办法：一道还没等到收尾 ``` 的围栏
 * 永远不会封口，于是那整段代码从头到尾都是"正在写的那一块"。而问题其实集中在
 * **最后那一行**：模型是逐字往外吐的，前面那些行在各自换行之后就再没变过，
 * 只有最后那一条每帧都在变。
 *
 * <p>切开之后，交给高亮器的那串文本**按值相等**（最后一行在长，但它在 partial 里），
 * 于是 effect 的依赖没变、高亮器一次也不会被叫醒。而最后那一行**先不上色**，
 * 等它换行了再上 —— 那不只是省事：正在写的那一行，它的颜色本来就会随着字一个个
 * 加上来而反复变化（语法判断在变），看起来像在闪。
 *
 * <h2>两道"看不出来"的细节，都在下面几行里</h2>
 * <ol>
 *   <li><b>react-markdown 给块级代码补过一个收尾换行</b>（它从 markdown 里解析出来的
 *       值本身**不带**这个换行 —— 实测：源码 {@code ```java\nint a;} 交出来的是
 *       {@code "int a;\n"}）。不先摘掉它的话，最后那行后面永远还跟着一个空行，
 *       切出来的 partial 永远是空的 —— 这段代码就会变成一句空转。</li>
 *   <li><b>"最后一行没写完"这件事，从这个字符串上看不出来。</b> 源码
 *       {@code "int a;"}（还在写）和 {@code "int a;\n"}（刚敲完回车）交出来的
 *       都是 {@code "int a;\n"} —— 那个区别被解析器抹平了。所以判据只能回到
 *       **原始正文**上：它最后一行没换行、而且这一段顶在它的末尾，才算。
 *       这也是为什么这个函数要收两个参数。</li>
 * </ol>
 *
 * <h2>为什么这是能做到的极限</h2>
 * 真正的"增量分词"做不到 —— 那要求能从第 K 行接着往下分词，也就要求拿到
 * **第 K 行处的语法状态**，而 shiki 不暴露它。这不是懒：跨行结构（块注释、
 * 多行字符串、Python 的三引号）让语法状态穿过行边界，所以自己缓存前半段的 token
 * 是**会错的** —— 后面新加的一行完全可能把前面某一行重新解释。
 *
 * @param code   react-markdown 交给代码块的文本（**带着它补的那个收尾换行**）
 * @param source 这一整段正文（{@code Markdown} 收到的那串）——
 *               判"是不是还在写"只能看它，理由见上面第二条
 */
export function splitPartialLine(code: string, source: string): CodeParts {
  const trailingBreak = code.endsWith('\n') ? '\n' : ''
  const body = trailingBreak === '' ? code : code.slice(0, -1)

  // 只有**顶在整个正文末尾**、而且正文最后一行还没换行的那一块，才是真在写的。
  // 少了前半句，尾巴里前面那些已经收尾的代码块会被一起摘掉最后一行
  const writing = !source.endsWith('\n') && source.trimEnd().endsWith(body.trimEnd())
  if (!writing) {
    return { complete: body, partial: '', trailingBreak }
  }

  const cut = body.lastIndexOf('\n')
  return cut < 0
    ? { complete: '', partial: body, trailingBreak }
    : { complete: body.slice(0, cut + 1), partial: body.slice(cut + 1), trailingBreak }
}
