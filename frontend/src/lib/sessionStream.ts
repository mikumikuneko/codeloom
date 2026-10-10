/**
 * 事件流 → 人看的条目。**这是一个纯函数**（同样的输入永远给同样的输出）。
 *
 * <h2>它和后端那个 ContextAssembler 不是一回事</h2>
 * 那个投影是**给模型看**的：只保留对话，工具调用压成 API 要求的形状，
 * 状态变化、checkpoint、用量全部丢掉。
 *
 * <p>而这个投影是**给人看**的 —— 人要看的恰恰是那些被丢掉的东西：
 * 它调了什么工具、跑没跑通、为什么停下来、这一轮花了多少。
 * 同一个事件流，两种读者，两种投影。**这是事件溯源最直接的兑现**。
 *
 * <h2>工具调用和它的结果合成一条</h2>
 * 分开显示的话，每一步工具调用都会变成两行 —— 而"它跑了 mvn test"和"跑失败了"
 * 本来就是一件事的两半。所以这里在遇到结果时**回头把结果填进那条**，
 * 而不是新加一条。
 */

import { dropsStreaming, isActive, settlesDangling } from '@/lib/sessionState'

/**
 * 一次调用**存在过、却没有结果**的几种原因。
 *
 * <p>存的是"哪一种"，不是那句话 —— 字怎么写是界面的事（见 `ToolLine` 右端那一格）。
 * 放在这里的话，同一件事会有两份文案，而它们迟早会不一样。
 */
export type Unfinished =
  /** 跑着的时候用户让它停了。进程树已经被杀了。 */
  | 'cancelled'
  /** 平台在它执行到一半的时候没了（崩溃恢复时补写的那个事实）。 */
  | 'interrupted'
  /** 用户**拒绝**了这次调用：它没执行，而且不会再有结果（后端那条 TOOL_REJECTED）。 */
  | 'rejected'
  /** 收尾时才发现它一直没有结果 —— 当时发生了什么不知道（见 closeDanglingCalls 那道兜底）。 */
  | 'no-result'

/** 一条工具调用的结果（合并进调用那一条里）。 */
export interface ToolOutcome {
  success: boolean
  output: string
  truncated: boolean
  exitCode: number | null
  /**
   * 这一次调用**实际**改动了工作区吗。
   *
   * <p>它是后端算出来、随事件落库的**事实**。从前没有它，界面只能拿一张按工具名
   * 写死的名单去猜（`run_command` 跑一条 `git status` 什么都没动，而名单说它动了）。
   * 判"这一类工具通常会改"和判"这一次真的改了"是两件事，前者只能猜。
   */
  mutated: boolean
}

/**
 * 一个文件**一步之内**改了多少行。
 *
 * <p>它是后端算好、随事件流过来的（见 {@code WorkspaceChanges}），所以这里只描述形状。
 * 路径是**项目相对路径** —— git 说的仓库路径在后端已经翻过一道。
 */
export interface ChangedFile {
  path: string
  added: number
  deleted: number
  /** 二进制文件 —— git 给不出行数，只能说"它变了" */
  binary: boolean
  /** 这一步是**新建**了这个文件，不是改了一个已有的 */
  created: boolean
  /**
   * 这条改动出自哪一个提交 —— 点开看正文时要拿它去问（见 `ProjectDiffView`）。
   *
   * <p>它跟着**文件**走、不跟着轮走：一轮里可能落好几条改动记录（批准一次之后续跑，
   * 收尾会再落一条），同一条路径于是可能出自两个提交。上面那个合并按 `...file` 摊开，
   * 所以留下的是**晚**的那一个 —— 也就是说点开看到的是**其中一次**的正文，
   * 而行数是两次的和。这一条如实记在这儿，别把它当成"全部"。
   */
  commitSha: string
}

/** 一轮改了哪些文件。 */
export interface TurnFiles {
  files: ChangedFile[]
  /** 改动太多、后端只记下了前一批 —— 界面要如实说"没记全"，而不是只报那个偏小的数字 */
  truncated: boolean
}

/**
 * 这次工具调用**还在跑吗**。
 *
 * <p>两个条件缺一不可：没有结果，**也**没有"它没跑完"这个事实。少了后半个，
 * 崩溃恢复之后那条调用会一直闪 —— 界面在说它还在跑，而它早就没了。
 *
 * <p>**这条规则只有这一处**：界面上有两处问"整轮/单个工具还在跑吗"，而 fold 里
 * 还有一处问"要不要给它补一个没跑完"（那一处在这个之上多一个条件）。各写一遍的话，
 * 将来多一种结局就会漏改一处 —— 漏掉的那处不报错，只是一直闪。
 */
export function toolIsRunning(
  outcome: ToolOutcome | null,
  unfinished: Unfinished | null,
): boolean {
  return outcome === null && unfinished === null
}

/**
 * 这一轮现在是什么状态 —— 取它**最后**一条状态变更。
 *
 * <p>界面上有两处要问它（投影要不要给悬挂的调用收尾、会话流那一栏要不要画用量行），
 * 而它们必须看**同一个事实**。所以状态进流（见 StreamItem 里那条 `state`），
 * 谁都不许去数别的迹象。
 */
export function turnStateOf(turn: StreamItem[]): string | null {
  const last = turn.findLast((item) => item.kind === 'state')
  return last === undefined ? null : last.to
}

/**
 * 这一轮**还在被推进**吗 —— 看它最后一条状态，不看 item 的形状。
 *
 * <h2>为什么不看形状</h2>
 * 从前这里问的是"最后一条 item 是不是一条还在跑的工具"。那个判据会**随流的形状漂**：
 * 后来往流里加了一种条目（状态本身也进流），工具后面就跟着一条状态条目，
 * 于是"工具正在跑"再也判不出来 —— 症状是**Esc 打断在工具执行期间失灵**，
 * 而那恰好是最需要打断的时候（一次构建可能几十秒）。
 *
 * <p>状态是"这一轮在被推进"的**事实**（后端每一步都落一条），从它推才是稳的。
 *
 * @param turn 这一轮的那些条目。**传整条流也行**：最后一条状态一定属于最后那一轮。
 */
export function turnIsActive(turn: StreamItem[]): boolean {
  return isActive(turnStateOf(turn))
}

/** 一轮改动的增删合计。两处都在算它（会话流那一行、回滚面板那一行），算的地方只留一个。 */
export function changeTotals(files: ChangedFile[]): { added: number; deleted: number } {
  return {
    added: files.reduce((sum, file) => sum + file.added, 0),
    deleted: files.reduce((sum, file) => sum + file.deleted, 0),
  }
}

/** 一轮的改动。{@code turn} 就是**块号**（见 {@link groupIntoTurns}），和"第几轮"是同一条尺子。 */
export interface TurnChanges extends TurnFiles {
  turn: number
}

/**
 * 把这条流切成**块**：一块 = 一句用户消息 + 它之后发生的事。
 *
 * <h2>为什么"哪几条算一轮"只能定义一次</h2>
 * 会话流按它分块、回滚面板按它标位置、"这一轮改了哪些文件"按它挂号 ——
 * 三处各数一遍的话，两套编号一旦错开，"改了 N 个文件"就挂到**别人那一轮**头上，
 * 而那看起来完全像真的。这里为 checkpoint 栽过一次：它多占一块，后面每一轮整体错一格。
 * 所以分块只有这一个函数，{@link turnChanges} 收的就是它的产出。
 *
 * <p>**回滚标记（checkpoint）不进分块**：它不显示（渲染时返回 null），
 * 留着只会让开头多出一块**空块** —— 而空块自己没有高度，`space-y` 却会给它下一个兄弟留白。
 */
export function groupIntoTurns(items: StreamItem[]): StreamItem[][] {
  const turns: StreamItem[][] = []
  /** 第一条用户消息**之前**落下的那些（状态条目、通知）—— 它们属于**那一轮**，不是单独一块 */
  const leading: StreamItem[] = []
  for (const item of items) {
    if (item.kind === 'checkpoint') {
      continue
    }
    if (item.kind === 'user') {
      // **块由用户消息起。** 这样块号就是"第几条用户消息"，而"第 k 轮"在整个界面上
      // 只此一套下标 —— 悬停那行的「第 N 轮」和回滚面板按消息顺序取的那句，用的是同一个 k
      turns.push(leading.splice(0))
      turns[turns.length - 1].push(item)
      continue
    }
    if (turns.length === 0) {
      // 还没见过用户消息：先攒着，等那一块建起来再并进去
      leading.push(item)
      continue
    }
    turns[turns.length - 1].push(item)
  }
  // 一句用户消息都还没有（会话刚建好）时，开头那些自成一块 —— 不然它们没地方显示
  if (turns.length === 0 && leading.length > 0) {
    turns.push(leading)
  }
  return turns
}

/**
 * 每一轮改了哪些文件 —— **从流里数出来**，不再另发请求。
 *
 * <p>轮次号就是**块号**（见 {@link groupIntoTurns}）。一轮里可能落好几条改动记录
 *（批准一次之后续跑，收尾时会再落一条），**按路径相加**而不是只留最后一条 ——
 * 那会把前一次的改动从账上抹掉。
 *
 * <p>它按**已经分好的块**数，不自己再数一遍用户消息：两种数法一旦错开，
 * "这一轮改了哪些文件"就会挂到别人那一轮头上，而那看起来完全像真的。
 *
 * <p>写法上是个纯函数，和 {@link fold} 一样：同样的流永远给同样的结果。
 * 于是会话流显示的、回滚面板列的，是**同一份推导**，不可能各说各的。
 */
export function turnChanges(turns: StreamItem[][]): TurnChanges[] {
  return turns.flatMap((block, turn) => {
    const files = new Map<string, ChangedFile>()
    let truncated = false
    for (const item of block) {
      mergeChanges(files, item)
      if (item.kind === 'changes' && item.truncated) {
        truncated = true
      }
    }
    // 一轮可能落好几条改动记录，也可能一条都没有（没写任何文件的那些轮）
    return files.size === 0 ? [] : [{ turn, files: [...files.values()], truncated }]
  })
}

/**
 * 每一轮改了哪些文件，**按后端那个轮次号**索引（见 `WorkspaceChanges.turnIndex`）。
 *
 * <h2>为什么这里不跟着会话流用"块号"</h2>
 * 因为读它的是**回滚面板**，而那个面板里的每一行本来就是一个后端号
 *（它列的是 checkpoint，号是后端推的）。两条路各自和自己那套对齐：
 * 会话流按块号挂号、回滚面板按后端号挂号 —— 谁也不去猜另一套，就不会错开。
 *
 * <p>老事件没有这个号（字段是后加的），那些改动**进不了这张表** ——
 * 这比按位置猜一个号塞进去诚实：猜错的话，那句话配的就是别人的改动。
 */
export function changesByTurnIndex(items: StreamItem[]): Map<number, TurnFiles> {
  const byTurn = new Map<number, { files: Map<string, ChangedFile>; truncated: boolean }>()
  for (const item of items) {
    if (item.kind !== 'changes' || item.turnIndex === null) {
      continue
    }
    let entry = byTurn.get(item.turnIndex)
    if (entry === undefined) {
      entry = { files: new Map(), truncated: false }
      byTurn.set(item.turnIndex, entry)
    }
    mergeChanges(entry.files, item)
    if (item.truncated) {
      entry.truncated = true
    }
  }
  return new Map([...byTurn].map(([turn, e]) => [turn, { files: [...e.files.values()], truncated: e.truncated }]))
}

/**
 * 把一条改动并进"按路径"的账里。
 *
 * <p>**按路径相加**而不是只留最后一条：一轮里可能落好几条改动记录
 *（批准一次之后续跑，收尾时会再落一条），只留最后一条会把前一次的改动从账上抹掉。
 */
function mergeChanges(into: Map<string, ChangedFile>, item: StreamItem): void {
  if (item.kind !== 'changes') {
    return
  }
  for (const file of item.files) {
    const seen = into.get(file.path)
    into.set(file.path, seen === undefined ? file : {
      ...file,
      added: seen.added + file.added,
      deleted: seen.deleted + file.deleted,
      binary: seen.binary || file.binary,
      created: seen.created || file.created,
    })
  }
}

export type StreamItem = {
  /**
   * 这一条对应的事件 id。**引用回复指向的就是它**。
   *
   * <p>只有已落库的事件才有（流式增量没有），所以是可选的 —— 而它由
   * {@link fold} 在出口统一盖上，不在每个分支里各写一遍：那种写法迟早会漏掉一支，
   * 而漏掉的那一支在界面上表现为"这一条没法引用"，不会报错。
   */
  seq?: number
} & (
  | { kind: 'user'; text: string; at: string | null }
  | { kind: 'agent'; text: string; model: string | null; reasoning: string | null }
  | { kind: 'note'; fromUserId: string; text: string }
  | {
      kind: 'tool'
      callId: string
      name: string
      args: string
      outcome: ToolOutcome | null
      unfinished: Unfinished | null
    }
  | {
      /**
       * 一次模型调用失败了，后端正在等一下再试。
       *
       * <p>从前重试藏在 provider 客户端里，那几秒**界面上什么都没有** ——
       * 看的人只觉得"它卡住了"。后端现在把它落成事件（见 LlmRetryScheduled），
       * 这里照着显示。
       */
      kind: 'retry'
      /** 第几次尝试（从 1 开始）：1 表示"第一次失败了，正在试第二次" */
      attempt: number
      maxAttempts: number
      delayMs: number
      /** 为什么 —— 稳定的分类（限流 / 服务端故障 / 网络错误），不是服务商那句英文原文 */
      reason: string
    }
  | {
      kind: 'approval'
      callId: string
      approved: boolean | null
      /** 谁答的。**是个用户 id** —— 名字在渲染时按它现查（用户名会变，唯一也不代表它是身份） */
      by: string | null
      /**
       * 后端为什么要问 —— 一句话，摆在人点"批准"的地方。
       *
       * 判据有三种（程序不在免审批名单里 / 动到了工作区外面 / 这行命令看不懂），
       * 要人过目的东西完全不同。**老事件里没有这一项**（那时我们只记"谁答的"、
       * 不记"为什么问"），那时它是空串。
       */
      reason: string
    }
  /**
   * 一行小字。
   *
   * <p>{@code turnEnd} 只在**这一轮就此收场**的两条上出现 —— 失败了、或者被打断
   * （按 Esc / 拒绝且没留指示）。它给下游一个能判别的标记："这一轮没有正常跑完"。
   * 它的用处：那种轮次**不报"用时"**（见 TurnTail）——
   * 一个没跑完的轮次，那个数字说明不了什么。
   */
  | {
      kind: 'notice'
      tone: NoticeTone
      text: string
      turnEnd?: TurnEnd
      /**
       * **某个人做的一个动作**。存的是**用户 id** ——
       * 名字在渲染时按它现查，所以改过用户名之后界面上跟着变（见后端 {@code SessionRewound}）。
       * 没有它的是"平台自己发生的事"（同步、压缩），那种没有主语。
       */
      actorId?: string | null
    }
  /**
   * 这一轮改了哪些文件。
   *
   * <p>**它由后端在收尾时算好、随事件流过来** —— 不是前端去问 git，也不是前端另发一个请求
   * 换一份回来。这一条是从"读的时候各算一遍"搬到"写的时候算一次"的落点，
   * 理由见后端 {@code WorkspaceChanges} 的类注释。
   *
   * <p>它自己不占一行：跟着那一轮的尾巴显示（见 {@code ChangesRow}）。
   */
  /**
   * 一条状态变更。**不显示**（渲染时返回 null）—— 它留在流里只为一件事：
   * 让"这一轮现在什么状态"只有**一个**事实来源（见 {@link turnStateOf}）。
   *
   * <p>从前状态不进流，于是会话流那一栏只能**去数**"这一块里还有没有没答复的审批卡片"
   * 来回答"还挂着等人批吗" —— 同一个问题两个来源，两边不同步就自相矛盾。
   */
  | { kind: 'state'; to: string; reason: string }
  | {
      kind: 'changes'
      files: ChangedFile[]
      truncated: boolean
      /** 这是第几轮（后端那个号，见 `WorkspaceChanges.turnIndex`）。加这个字段之前落的事件没有 */
      turnIndex: number | null
    }
  /**
   * 一个回滚点。**它不显示**（渲染时给 null），留在列表里只为两件事：
   *
   * <ul>
   *   <li>回滚要把流截断到准确的位置 —— 认的是**这一条的 {@link #seq}**（每条事件都有，
   *       见 {@link fold} 出口那道统一盖章），见 {@link foldOne} 里
   *       {@code SESSION_REWOUND} 那一支。</li>
   *   <li>{@code turn} 给回滚面板看：它是"到这儿为止完成了几次交互"，列表按它去重、
   *       也按它去查"这一轮改了哪些文件"。</li>
   * </ul>
   *
   * <p>**不带 sha。**它一度带着（回滚从前是拿 sha 找目标的），而 sha 在一条会话里
   * 会重复出现（一轮什么都没改时），拿它定位正是那个"退过头"的 bug。现在没有一处读它 ——
   * 于是它不该在这里。
   */
  | { kind: 'checkpoint'; turn: number }
  | {
      kind: 'usage'
      input: number
      output: number
      cached: number
      context: ContextReading | null
      at: string | null
    }
)

/** 小字的调性。**只有"异常"上色** —— 正常的事情不需要颜色。 */
export type NoticeTone = 'plain' | 'good' | 'bad'

/** 这一轮是怎么收的场。只有"没正常跑完"的两种 —— 正常收尾不需要标记。 */
export type TurnEnd = 'failed' | 'stopped'

/**
 * 一轮收尾时，**上下文有多大**。
 *
 * <h2>它和 {@link StreamItem} 里那几个 token 数是两笔账</h2>
 * `input` / `output` 回答的是"这一轮花了多少"（一轮里可能调了好几次模型，它们是**和**）；
 * 而这个回答的是"当时送进去的那份上下文有多大"（**最后一次调用**的输入）。
 * 两者没有加减关系 —— 一次调用就完成的一轮里它们恰好相等，那也正是最容易搞混的地方。
 *
 * <p>数由**服务商亲口报出来**（`prompt_tokens`），不是按字符估的。
 * 没有读数的轮次是 {@code null} 而不是 0：两种情况（这一轮压根没调过模型 /
 * 这条事件是加这个字段之前落的）都该**什么都不画**，不是一个空环。
 */
export interface ContextReading {
  tokens: number
  /** 当时的模型窗口。**跟着每一轮走**，因为换模型会让它变 */
  window: number
}

/** 服务端推来的一帧。`seq` 只有已落库的事件才有（流式增量没有）。 */
export interface Frame {
  seq?: number
  at?: string
  type: string
  payload: Record<string, unknown>
}

/** 流式生成的正文/思考。**它不属于 items** —— 它是"正在长出来"的那一条。 */
export interface Streaming {
  text: string
  reasoning: string
}

export const EMPTY_STREAMING: Streaming = { text: '', reasoning: '' }

/**
 * 到眼下为止，这条流里**确实改过工作区**的动作有几条。
 *
 * <h2>为什么是一个数，而不是一条"变了"的事件</h2>
 * 因为这个投影是**纯函数**：同一条流永远算出同一个数。于是断线重连、切走再切回来
 * 那两次历史重放，算出来的数和原来一样 —— 看的人只要问"它涨了没有"就够了，
 * 既不会漏，也不会因为重放而重复。若改成"折进列表时顺手发一个通知"，
 * 这个性质立刻就没了：通知会跟着重放的每一帧重新发一遍。
 *
 * <p>数的是**后端报的那个事实**（`outcome.mutated`），不是"这个工具通常会改"。
 * 从前这里是 `TOOLS_THAT_WRITE.has(item.name)` —— 一张按工具名写死的名单，
 * 它有两处错：把"这一类工具通常会改"当成了"这一次真的改了"（`run_command`
 * 跑一条 `git status` 什么都没动，却会让树重拉一次），以及**加第八个工具的那天
 * 它不会跟着变**，症状是"树不刷新了"，没人会想到是这里。
 *
 * <p>也不再另外要求 `success`：那个字段说的是"结果好不好"，和"动没动过磁盘"是两件事
 * —— 退出码非零的命令完全可能已经写了文件（格式化器修完报错退出、编译产出部分产物）。
 * 后端在算这个字段时已经把这条想进去了，这里再叠一个判断等于把它推翻。
 */
export function workspaceWrites(items: StreamItem[]): number {
  let count = 0
  for (const item of items) {
    if (item.kind === 'tool' && item.outcome?.mutated === true) {
      count += 1
    }
  }
  return count
}

/** 把一帧折进条目列表，返回新的列表（不改原来的）。 */
export function fold(items: StreamItem[], frame: Frame): StreamItem[] {
  const before = items.length
  const next = foldOne(items, frame)

  // **新追加的每一条都盖上这一帧的 seq** —— 引用回复指向的就是它。
  //
  // 在这里统一盖，而不是让下面八个分支各写一遍：那种写法迟早会漏掉一支，
  // 而漏掉的那一支在界面上表现为"这一条没法引用"，不会报错。
  //
  // 只盖章**新追加**的：有些分支是"就地补上结果"（工具跑完了给它填 outcome），
  // 那种长度没变，而那条工具第一次出现时就盖过了 —— 它的 seq 不该被后面的帧改掉
  if (next.length > before && frame.seq !== undefined) {
    return next.map((item, at) => (at >= before ? { ...item, seq: frame.seq } : item))
  }
  return next
}

function foldOne(items: StreamItem[], frame: Frame): StreamItem[] {
  const p = frame.payload ?? {}

  switch (frame.type) {
    case 'USER_MESSAGE':
      return [...items, { kind: 'user', text: str(p.text), at: nullableStr(frame.at) }]

    case 'ASSISTANT_MESSAGE':
      // 完整回复到了：流式那一段到此为止，由调用方清空缓冲。
      // **思考过程留一份** —— 见 agent 那条的注释：它从前就是在这里被丢掉的
      return [
        ...items,
        {
          kind: 'agent',
          text: str(p.text),
          model: nullableStr(p.model),
          reasoning: nullableStr(p.reasoning),
        },
      ]

    case 'AGENT_NOTE_DELIVERED':
      return [
        ...items,
        { kind: 'note', fromUserId: idOf(p.fromUserId), text: str(p.text) },
      ]

    case 'PLATFORM_INSTRUCTION': {
      // **平台写给模型的原文不给人看。**
      //
      // 它不是"一句提示"，是**提示词**：里面带着对模型的命令（"不要道歉，
      // 也不要复述已经说过的内容"），端到人的界面上读起来像界面在训人。
      // Claude Code 也是这么处理的 —— 它把这类文本换成一句人话再显示
      //（见它 UserTextMessage 里对中断消息的处理：原文一个字不露，
      // 只显示"Interrupted · What should Claude do instead?"）。
      //
      // 按 `reason` 认，按正文认是靠不住的（那串字随时会改）。
      // **认不出来就什么都不显示** —— 宁可少说一句，也不把提示词原文端上来
      const said =
        str(p.reason) === 'output-truncated'
          ? '上一步的输出被长度上限截断了，它正在拆成几步重做'
          : str(p.reason) === 'verification-failed'
            ? '平台的自动验证没过，它正在修'
            : ''
      return said === '' ? items : [...items, { kind: 'notice', tone: 'plain', text: said }]
    }

    case 'TOOL_CALL_REQUESTED':
      return [
        ...items,
        {
          kind: 'tool',
          callId: str(p.callId),
          name: str(p.toolName),
          args: str(p.argumentsJson),
          outcome: null,
          unfinished: null,
        },
      ]

    case 'TOOL_RESULT': {
      // ★ 回头把结果填进那条调用里，而不是新加一条 —— 见类注释
      const callId = str(p.callId)
      const outcome: ToolOutcome = {
        success: p.success === true,
        output: str(p.output),
        truncated: p.truncated === true,
        exitCode: typeof p.exitCode === 'number' ? p.exitCode : null,
        // 老事件里没有这一项（那时它还只是个没落库的事实）—— 缺失按"没改"处理
        mutated: p.mutated === true,
      }
      return items.map((item) =>
        item.kind === 'tool' && item.callId === callId ? { ...item, outcome } : item,
      )
    }

    case 'TOOL_INTERRUPTED':
    case 'TOOL_CANCELLED': {
      // 这两个是"那次调用没有结果"的**事实**：一轮在工具执行中途死了 / 被取消了。
      //
      // ★ 折回**那条调用自己**，而不是旁边加一条小字。从前是加小字，于是那条调用
      //   永远停在 outcome 为空的样子 —— 而界面上 outcome 为空就等于"正在跑"，
      //   于是它一直闪。这里填的是第三种结局：没跑完（**不是失败** —— 它没失败，
      //   它是没跑成，而且模型的上下文里收到的也是这个区分）
      const callId = str(p.callId)
      const unfinished: Unfinished = frame.type === 'TOOL_INTERRUPTED' ? 'interrupted' : 'cancelled'
      return items.map((item) =>
        item.kind === 'tool' && item.callId === callId ? { ...item, unfinished } : item,
      )
    }

    case 'LLM_RETRY_SCHEDULED':
      return [
        ...items,
        {
          kind: 'retry',
          attempt: num(p.attempt),
          maxAttempts: num(p.maxAttempts),
          delayMs: num(p.delayMs),
          reason: str(p.reason),
        },
      ]

    case 'TOOL_APPROVAL_REQUESTED':
      return [
        ...items,
        {
          kind: 'approval',
          callId: str(p.callId),
          approved: null,
          by: null,
          reason: str(p.reason),
        },
      ]

    case 'TOOL_APPROVAL_RESOLVED': {
      const callId = str(p.callId)
      return items.map((item) =>
        item.kind === 'approval' && item.callId === callId
          ? { ...item, approved: p.approved === true, by: idOf(p.resolvedByUserId) || null }
          : item,
      )
    }

    case 'TOOL_REJECTED': {
      // 拒绝 = 这次调用**到此为止**（后端为它落的收尾事件，见 ToolRejected）。
      //
      // ★ **这一支不能少**：界面上"还在跑"的判据是"既没有结果、也没有'没跑完'"
      //   （见 ToolLine），而拒绝之后那次调用永远不会有结果 —— 少了它，
      //   那一行会一直闪。这个 bug 是实际用出来的：拒绝之后那行闪个不停
      const callId = str(p.callId)
      return items.map((item) =>
        item.kind === 'tool' && item.callId === callId ? { ...item, unfinished: 'rejected' } : item,
      )
    }

    case 'VERIFICATION_RESULT':
      return [
        ...items,
        {
          kind: 'notice',
          tone: p.passed === true ? 'good' : 'bad',
          text: p.passed === true
            ? `验证通过（${str(p.command)}）`
            : `验证没过（${str(p.command)}）：${str(p.summary)}`,
        },
      ]

    case 'SESSION_STATE_CHANGED': {
      const to = str(p.to)
      const reason = str(p.reason)
      /** 状态本身也进流 —— 见 StreamItem 上那条 `state`。它不显示，只当事实来源 */
      const stateItem = { kind: 'state' as const, to, reason }

      // 这一轮**收尾了**没有 —— 谁算收尾由状态自己那张表说（见 lib/sessionState）。
      // **等人批不算**：那条调用还在等一个人，它没结束
      const settled = settlesDangling(to) ? closeDanglingCalls(items) : items

      // 只显示"值得停一下"的那两个：出错了、或者挂在等人批。
      // 其余状态变化（思考中、执行中、回到等用户）是过程的细节，
      // 全列出来会把流变成一串状态日志
      if (to === 'FAILED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'bad', text: `这一轮失败了：${reason}`, turnEnd: 'failed' },
          stateItem,
        ]
      }
      // **用户按 Esc 停的那一轮**：一行暗字，不是错误。Claude Code 里那句话是
      // "Interrupted · What should Claude do instead?" —— 暗色、挂在被中断的消息后面、
      // 而且接着问一句下一步。它是一条独立的事实（谁停的、什么时候），只是不冒充出错
      if (to === 'WAITING_USER' && reason === 'CANCELLED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'plain', text: '已打断 · 接下来要它做什么？', turnEnd: 'stopped' },
          stateItem,
        ]
      }
      // **拒绝且没留下指示**：那一轮也停住了（见后端的 ApprovalService），
      // 和按 Esc 是同一个处境 —— 所以同一句话
      if (to === 'WAITING_USER' && reason === 'REJECTED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'plain', text: '已停下 · 接下来要它做什么？', turnEnd: 'stopped' },
          stateItem,
        ]
      }
      // 等人批**不写小字** —— 那件事由**待批卡片自己**表达：它就在那次调用的下面，
      // 写着"等你批准一次调用"、带允许一次/拒绝两个按钮，而答复到了之后它会
      // **原地变成**"root 批准了那次调用"。
      //
      // 从前这里另外追加一条"停下来等你批准一次调用"，两个后果都实际发生了：
      // 等的时候同一句话出现两遍；批完之后那条小字还留在下面 ——
      // **界面上读成"批准了…停下来等你批准"**，看着像个 bug（它确实是）。
      //
      // 教训：状态式的话（"停下来等你…"）不能写进追加式的流里 —— 流没有让话过期的机制，
      // 而状态会过去。要进流的话，得写成一件**带时间的事实**（谁、什么时候做了什么）。
      return [...settled, stateItem]
    }

    case 'CHECKPOINT_CREATED':
      // checkpoint **不显示**：每一轮结束都有它，画出来就是每轮加一行噪音。
      // 但它必须**留在列表里** —— 回滚要靠它把流截断到准确的位置（下一支）
      return [...items, { kind: 'checkpoint', turn: num(p.turnIndex) }]

    case 'SESSION_SYNCED':
      return [...items, { kind: 'notice', tone: 'plain', text: '同步了主干的最新改动' }]

    case 'SESSION_REWOUND': {
      // **回滚要连流一起截断。** Claude Code 就是这么做的：恢复对话 = 把选中的那句话
      // 及其之后的全部删掉，对话从那儿重新往下长（它那边是
      // `setMessages(prev.slice(0, messageIndex))`，顺带换一个对话 id 分叉出去）。
      //
      // 截到**哪一条**：认**序号**（`toCheckpointSeq`），不认 sha。一轮什么都没改时
      // 两条 checkpoint 的 sha 一模一样，按 sha 找只能猜 —— 而从前猜的方向恰好是
      // "退最初那条"，于是"退到第三句之前"执行成了"整个对话清空"。
      // 后端（`ContextAssembler` 的 `checkpointIndexes`）现在也认序号，两边算的是
      // 各自投影上的同一个点。
      //
      // 找不到就**不截断**：那意味着这个位置不在手上这段流里（比如流是从中间某处开始
      // 读的）。那种时候清空整段是最坏的选择 —— 界面会凭空少掉一整段对话。
      //
      // 序号缺了（事件没记下退到哪儿）同样当成"找不到" —— 不去拿 sha 猜一条出来
      const seq = p.toCheckpointSeq
      const at =
        typeof seq === 'number'
          ? items.findIndex((item) => item.kind === 'checkpoint' && item.seq === seq)
          : -1
      const kept = at < 0 ? items : items.slice(0, at + 1)
      // 提示要念的那句话在**被截掉的那一段**里，所以传的是截断前的 `items` 而不是 `kept`
      return [...kept, {
        kind: 'notice',
        tone: 'plain',
        text: rewindNotice(items, at),
        // 谁退的：存 id，名字在渲染时查（见 Item 上那个字段的说明）
        actorId: idOf(p.byUserId) || null,
      }]
    }

    case 'WORKSPACE_CHANGES':
      // 后端收尾时算好的"这一轮改了什么"。**哪些文件、各多少行**跟着流走，
      // 于是读的人照着读就行 —— 不用再问 git，也不受"那个提交还在不在"的影响。
      //
      // 只有**正文**是按需去取的（点开某一个文件时，见 ProjectDiffView），
      // 所以那个提交被回收之后正文会问不出来 —— 那时界面上要如实说，不编
      return [...items, {
        kind: 'changes',
        files: Array.isArray(p.files) ? p.files.map((file) => changedFile(file, str(p.commitSha))) : [],
        truncated: p.truncated === true,
        // 缺字段就是"加这个字段之前落的" —— **不能默认成 0**：0 是一个合法的轮次号
        turnIndex: typeof p.turnIndex === 'number' ? p.turnIndex : null,
      }]

    case 'MODEL_CHANGED':
      return [
        ...items,
        { kind: 'notice', tone: 'plain', text: `换模型：${str(p.fromModel)} → ${str(p.toModel)}` },
      ]

    case 'CONTEXT_COMPACTED':
      return [...items, { kind: 'notice', tone: 'plain', text: '上下文太长，早前的内容被压缩了' }]

    case 'TURN_TOKENS_USED': {
      const input = num(p.inputTokens)
      const output = num(p.outputTokens)
      // 两个都是 0 就别铺了：那是个空事件，写出来只是一行噪音
      if (input === 0 && output === 0) {
        return items
      }
      // 这两个数是**这一轮收尾时**的读数，不是前面那几个的和。缺一个就不画：
      // 分母为零的环没有意义，而 0/64000 会画出一个"上下文是空的"的假象
      const tokens = num(p.contextTokens)
      const window = num(p.contextWindow)
      return [
        ...items,
        {
          kind: 'usage',
          input,
          output,
          // 输入里**命中缓存**的那部分。它的单价低得多，所以在 BYOK 下
          // 这一项直接是钱 —— 见 UsageRow
          cached: num(p.cachedInputTokens),
          context: tokens > 0 && window > 0 ? { tokens, window } : null,
          at: nullableStr(frame.at),
        },
      ]
    }

    // 流式增量和"正文被清过"这两类不在这里产生条目：
    // 前者由调用方喂进 streaming 缓冲，后者只是给模型省的，人不需要知道
    case 'ASSISTANT_DELTA':
    case 'REASONING_DELTA':
    case 'TOOL_RESULTS_CLEARED':
    case 'SESSION_STARTED':
      return items

    default:
      // 认不出来的类型**安静跳过**：后端加一种事件时，前端旧版本不该因此崩掉，
      // 也不该冒出一行"未知事件" —— 那对读流的人没有意义
      return items
  }
}

/** 把一帧折进流式缓冲。正文和思考分开，因为它们要显示在**不同的位置**。 */
export function foldStreaming(current: Streaming, frame: Frame): Streaming {
  const p = frame.payload ?? {}
  if (frame.type === 'ASSISTANT_DELTA') {
    return { ...current, text: current.text + str(p.text) }
  }
  if (frame.type === 'REASONING_DELTA') {
    return { ...current, reasoning: current.reasoning + str(p.text) }
  }
  // 完整回复到了：缓冲交出去，重新开始。**这一步不能漏** ——
  // 漏了的话，下一轮的流式内容会接在上一轮的屁股后面
  if (frame.type === 'ASSISTANT_MESSAGE') {
    return EMPTY_STREAMING
  }
  // **一轮收了尾也要清。** 光等 ASSISTANT_MESSAGE 是不够的：**被打断的那一轮永远
  // 不会有它**（模型没说完），于是缓冲一直挂着 —— 界面上就表现为"它还在跑"，
  // 而它早就停了。哪些状态算"这截不会再长了"由状态自己那张表说（见 lib/sessionState）
  if (frame.type === 'SESSION_STATE_CHANGED') {
    if (dropsStreaming(str(p.to))) {
      return EMPTY_STREAMING
    }
  }
  return current
}

/**
 * 一轮收尾之后，把还挂着的调用补成"没有结果"。
 *
 * <h2>它是兜底，不是正路</h2>
 * 正常路径上每条调用都有自己的收尾（结果 / 被取消 / 进程中断 / 被拒绝），所以这一扫
 * 什么都不做。而一旦哪条路**漏写了收尾**，界面上那一行就会一直闪 —— 这个 bug 真踩过，
 * 而且踩了两回（拒绝那条路），所以宁可多一道网。
 *
 * <h2>批过的调用**不能扫**</h2>
 * 它没跑完是因为**马上要跑**：批准之后会话状态会短暂回到"等用户"，然后接着执行。
 * 扫了它，一条明明跑起来的调用会被说成"没有结果" —— 那就从"少报"变成了"错报"。
 */
function closeDanglingCalls(items: StreamItem[]): StreamItem[] {
  const approvedPending = new Set<string>()
  for (const item of items) {
    if (item.kind === 'approval' && item.approved === true) {
      approvedPending.add(item.callId)
    }
  }
  return items.map((item) =>
    item.kind === 'tool' &&
    toolIsRunning(item.outcome, item.unfinished) &&
    !approvedPending.has(item.callId)
      ? { ...item, unfinished: 'no-result' }
      : item,
  )
}

/**
 * 回滚那条提示怎么说。
 *
 * <h2>为什么不再说"回滚到第 N 轮"</h2>
 * 那个 N 是**计数器**（"完成过几次交互"），不是人眼里的位置。它从前就写在这儿，
 * 而真发生过的是这样：用户选的是"退到写斐波那契那句之前"，屏幕上出现的是
 * "回滚到第 0 轮"。那句话本身没错（计数器确实退到了 0），可它读起来像"退错了"，
 * 而**到底退到了哪儿一个字也没说**。
 *
 * <p>所以改成把人选的那句话念出来：被截掉的第一句，就是"从这儿开始没有了"那一句。
 *
 * @param before 回滚发生**之前**那份完整的列表 —— 要念的那句话在**被截掉的那一段**里，
 *               所以这里收的必须是截断前的（收截断后的那份就永远念不出话来，
 *               只能落到"回滚了这条会话"那一支）
 * @param at     那个位置标记在 {@code before} 里的下标；-1 = 没找到（不截断，也就没退掉谁）
 *
 * <p>这条小字**不含"谁退的"** —— 名字在渲染时按 id 现查（见 {@link StreamItem} 上的 actorId）。
 */
function rewindNotice(before: StreamItem[], at: number): string {
  // at < 0 = 没找到那个位置，于是**什么都没截** —— 那种情况下"退掉了哪句话"这个问题
  // 没有答案，不能拿"最后一条之后"来充数
  const dropped = at < 0 ? [] : before.slice(at + 1)
  const next = dropped.find((item) => item.kind === 'user')
  // 一句都没退掉（退到最新那个位置 = 没动）时说"回滚了这条会话" —— 那时候
  // 确实没有哪句话被退掉，硬要念一句出来反而是编的
  return next?.kind === 'user' ? `回滚到「${next.text}」之前` : '回滚了这条会话'
}

/** 一条改动记录。字段缺了按"没有"算 —— 它从线上流里读来，不能假设一定完整。 */
function changedFile(raw: unknown, commitSha: string): ChangedFile {
  const file = (raw ?? {}) as Record<string, unknown>
  return {
    path: str(file.path),
    added: num(file.added),
    deleted: num(file.deleted),
    binary: file.binary === true,
    created: file.created === true,
    commitSha,
  }
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/**
 * 事件里的一个用户 id。
 *
 * <p>领域侧那是个值对象，序列化出来是 {@code {"value":"…"}}，不是裸字符串。
 * 两种都认 —— 于是哪天它改成裸串，这里不用跟着改。
 */
function idOf(raw: unknown): string {
  if (typeof raw === 'string') {
    return raw
  }
  return str((raw as { value?: unknown } | null)?.value)
}

function nullableStr(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null
}

function num(value: unknown): number {
  return typeof value === 'number' ? value : 0
}

/**
 * 一个 token 数的写法：**带千位分隔符**。
 *
 * <p>一轮的输入很容易到五位数，而 `123456` 和 `123,456` 对人的区别不是好看 ——
 * 是"一眼看得出量级"和"要数位数"的区别。
 *
 * <p>这里**不用** "12.2K" 那种缩写：那是给"上下文用了多少"这种几万几十万的量
 * 准备的，而一轮的用量就几千，缩写反而让人要换算一次。
 */
export function formatTokens(value: number): string {
  return value.toLocaleString('en-US')
}
