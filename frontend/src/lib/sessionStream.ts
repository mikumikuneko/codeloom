/**
 * 把事件流折叠成人看的条目。这是一个纯函数：同样的输入永远给同样的输出。
 *
 * 它和后端给模型的上下文投影目的不同：那份只保留对话和工具调用的 API 形状，
 * 这里保留人需要看到的工具调用、结果、失败原因和用量。同一个事件流，两种读者。
 *
 * 工具调用与它的结果合成一条：遇到结果时回头填进那条调用，不新增一行。
 */

import { dropsStreaming, isActive, settlesDangling } from '@/lib/sessionState'
import { parsePresentation, type ToolPresentation } from '@/lib/toolPresentation'

/**
 * 一次调用存在过、却没有结果的几种原因。
 *
 * 存的是原因，不是文案：怎么写由界面决定（见 `ToolLine` 右端那一格）。放在这里
 * 会让同一件事有两份文案，迟早不一致。
 */
export type Unfinished =
  /** 用户让这一轮停了；进程树已经被杀。 */
  | 'cancelled'
  /** 平台在它执行到一半时没了（崩溃恢复时补写的事实）。 */
  | 'interrupted'
  /** 用户拒绝了这次调用：它没执行，也不会再有结果（后端那条 TOOL_REJECTED）。 */
  | 'rejected'
  /** 收尾时才发现一直没有结果，当时发生了什么未知（closeDanglingCalls 的兜底）。 */
  | 'no-result'

/** 一条工具调用的结果（合并进调用那一条里）。 */
export interface ToolOutcome {
  success: boolean
  output: string
  truncated: boolean
  exitCode: number | null
  /**
   * 这一次调用是否实际改动了工作区。
   *
   * 这是后端算好、随事件落库的事实，不是按工具名推测的名单：`run_command` 跑一条
   * `git status` 什么都没改。判"这类工具通常会改"与判"这一次真的改了"是两件事。
   */
  mutated: boolean
  /**
   * 只给界面看的结构化结果（后端随事件落库），模型看不到。
   *
   * 缺失、坏 JSON、认不出来的形状都是 `null`，那时界面退回渲染 `output`，
   * 也就是旧事件的样子。见 {@link parsePresentation}。
   */
  presentation?: ToolPresentation | null
}

/**
 * 一个文件在一步之内改了多少行。
 *
 * 由后端算好、随事件流过来（见 `WorkspaceChanges`），这里只描述形状。路径是
 * 项目相对路径：git 的仓库路径已在后端翻过一道。
 */
export interface ChangedFile {
  path: string
  added: number
  deleted: number
  /** 二进制文件：git 给不出行数，只能说它变了 */
  binary: boolean
  /** 这一步新建了这个文件，不是修改已有文件 */
  created: boolean
  /**
   * 这条改动出自哪个提交；点开正文时拿它去问（见 `ProjectDiffView`）。
   *
   * 它跟着文件走，不跟着轮走：一轮里可能落几条改动记录（批准后续跑、收尾再落
   * 一条），同一路径于是可能出自两个提交。按路径合并后留下的是较晚的那个，所以
   * 点开看到的是其中一次的正文，而行数是几次的和。
   */
  commitSha: string
}

/** 一轮改了哪些文件。 */
export interface TurnFiles {
  files: ChangedFile[]
  /** 改动太多，后端只记下了前一批；界面要如实显示"没记全"，不能只报偏小的数字 */
  truncated: boolean
}

/**
 * 这次工具调用还在跑吗。
 *
 * 两个条件缺一不可：没有结果，也没有"没跑完"这个事实。少了后者，崩溃恢复之后
 * 那条调用会一直显示成在跑。这条规则只放在这一处，fold 里补"没跑完"的那一处
 * 也复用它：各写一遍的话，多一种结局就会漏改一处，而漏掉的那处不报错。
 */
export function toolIsRunning(
  outcome: ToolOutcome | null,
  unfinished: Unfinished | null,
): boolean {
  return outcome === null && unfinished === null
}

/**
 * 这一轮现在是什么状态，取它最后一条状态变更。
 *
 * 投影是否要给悬挂的调用收尾、会话流是否要画用量行，都必须看这同一个事实。
 * 状态以条目形式进流（见 StreamItem 的 `state`），不要在别处数迹象。
 */
export function turnStateOf(turn: StreamItem[]): string | null {
  const last = turn.findLast((item) => item.kind === 'state')
  return last === undefined ? null : last.to
}

/**
 * 这一轮是否还在被推进，只看它最后一条状态，不看条目形状。
 *
 * 形状判据会随流演化失效：新增一种条目后，"最后一条是还在跑的工具"不再成立，
 * 症状是 Esc 在工具执行期间打断失灵。状态是后端每一步都落的事实。理由见
 * `docs/decisions/bug-fix/2026-10-10-turn-activity-comes-from-the-state-not-the-shape.md`。
 *
 * @param turn 这一轮的条目；传整条流也可以，最后一条状态一定属于最后一轮
 */
export function turnIsActive(turn: StreamItem[]): boolean {
  return isActive(turnStateOf(turn))
}

/**
 * 这一轮是否已经跑完；用量行只在这时显示。
 *
 * 不能只判 `AWAITING_APPROVAL`：挂起等人批时这一轮会先结算一次，批准之后继续跑。
 * 判据是终态（`WAITING_USER` / `FAILED`），或者这一轮留下了 `turnEnd`（失败 /
 * 打断 / 拒绝）。历史轮次（`live === false`）只要不是停在审批上也算结束：
 * 老数据可能没有状态条目，不能把它们全藏掉。
 *
 * 批准之后那一瞬，后端会先把状态推到 `WAITING_USER` 再开始续跑。若这一轮最后
 * 一条实质条目是刚被批准的审批，说明续跑还没有产出，同样不能显示。
 *
 * 理由见 `docs/decisions/bug-fix/2026-10-18-the-usage-line-waits-for-the-turn-to-finish.md`。
 *
 * @param turn    这一轮的条目
 * @param live    是否为最后一轮（只有它可能还在跑）
 * @param stopped 这一轮是否留下了 `turnEnd` 通知（失败 / 打断 / 拒绝）
 */
export function usageLineVisible(turn: StreamItem[], live: boolean, stopped: boolean): boolean {
  const state = turnStateOf(turn)
  if (stopped) {
    return true
  }
  if (state === 'WAITING_USER' || state === 'FAILED') {
    // 批准之后、续跑开始之前那一下：最后一条实质条目是那条审批，说明还没有新产出
    return !(live && state === 'WAITING_USER' && justResolvedApproval(turn))
  }
  // 历史轮次：没有状态条目（老数据）或者停在某个非审批状态，都算已经结束。
  // 停在 AWAITING_APPROVAL 的旧轮次仍然藏着 —— 它确实还在等人，只是人已经走了。
  return !live && state !== 'AWAITING_APPROVAL'
}

/** 这一轮最后一条实质条目是否是刚被批准的调用。状态、用量、检查点不算实质。 */
function justResolvedApproval(turn: StreamItem[]): boolean {
  const last = turn.findLast(
    (item) => item.kind !== 'state' && item.kind !== 'checkpoint' && item.kind !== 'usage',
  )
  return last?.kind === 'approval' && last.approved === true
}

/** 一轮改动的增删合计。会话流和回滚面板都调这里，不各算一遍。 */
export function changeTotals(files: ChangedFile[]): { added: number; deleted: number } {
  return {
    added: files.reduce((sum, file) => sum + file.added, 0),
    deleted: files.reduce((sum, file) => sum + file.deleted, 0),
  }
}

/** 一轮的改动。`turn` 是块号（见 {@link groupIntoTurns}），与"第几轮"是同一把尺子。 */
export interface TurnChanges extends TurnFiles {
  turn: number
}

/**
 * 把流切成块：一块 = 一句用户消息 + 它之后发生的事。
 *
 * 会话流分块、回滚面板标位置、"这一轮改了哪些文件"挂号都依赖这个定义；各数一遍
 * 会让编号错开，把改动挂到别人那一轮。分块只有这一个函数。
 *
 * 回滚标记（checkpoint）不进分块：它不显示，留着会让开头多出一个空块，而空块
 * 没有高度，`space-y` 却会给下一个兄弟留白。块由用户消息起，块号即第几条用户
 * 消息。理由见
 * `docs/decisions/bug-fix/2026-10-10-turns-are-cut-by-user-messages.md`。
 */
export function groupIntoTurns(items: StreamItem[]): StreamItem[][] {
  const turns: StreamItem[][] = []
  /** 第一条用户消息之前的条目（状态、通知）：它们属于那一轮，不是单独一块 */
  const leading: StreamItem[] = []
  for (const item of items) {
    if (item.kind === 'checkpoint') {
      continue
    }
    if (item.kind === 'user') {
      // 块由用户消息起：块号就是"第几条用户消息"，整个界面只此一套下标。
      turns.push(leading.splice(0))
      turns[turns.length - 1].push(item)
      continue
    }
    if (turns.length === 0) {
      // 还没见过用户消息：先攒着，等那一块建起来再并进去。
      leading.push(item)
      continue
    }
    turns[turns.length - 1].push(item)
  }
  // 一句用户消息都没有（会话刚建好）时，开头那些自成一块，否则没地方显示。
  if (turns.length === 0 && leading.length > 0) {
    turns.push(leading)
  }
  return turns
}

/**
 * 每一轮改了哪些文件，从流里数出来，不另发请求。
 *
 * 轮次号是块号（见 {@link groupIntoTurns}）。一轮可能落多条改动记录（批准后续跑、
 * 收尾再落一条），按路径相加而不是只留最后一条，否则会抹掉前一次。它按已经分好
 * 的块数，不自己再数用户消息。
 *
 * 纯函数，和 {@link fold} 一样：同样的流给同样的结果，所以会话流与回滚面板读的是
 * 同一份推导。
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
    // 一轮可能落多条改动记录，也可能一条都没有（没写任何文件的那些轮）。
    return files.size === 0 ? [] : [{ turn, files: [...files.values()], truncated }]
  })
}

/**
 * 每一轮改了哪些文件，按后端的轮次号索引（见 `WorkspaceChanges.turnIndex`）。
 *
 * 读它的是回滚面板，而面板里每一行本来就是一个后端号（它列的是 checkpoint，
 * 号由后端推）。所以会话流按块号挂号，回滚面板按后端号挂号，谁也不去猜另一套。
 *
 * 老事件没有这个号，那些改动进不了这张表；按位置猜一个号会把它配到别人的改动上。
 * 理由见
 * `docs/decisions/bug-fix/2026-10-10-frontend-stops-counting-turns-itself.md`。
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
 * 把一条改动并进按路径的账里。
 *
 * 按路径相加而不是只留最后一条：一轮可能落多条改动记录（批准后续跑、收尾再落
 * 一条），只留最后一条会抹掉前一次。
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
   * 这一条对应的事件 id；引用回复指向的就是它。
   *
   * 只有已落库的事件才有（流式增量没有），所以可选。由 {@link fold} 在出口统一
   * 盖上，不在各分支各写一遍：漏掉的分支表现为"这一条没法引用"，不会报错。
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
       * 一次模型调用失败，后端正在等待后重试。
       *
       * 重试落成事件而不是藏在 provider 客户端里：否则等待的那几秒界面上什么都没有。
       */
      kind: 'retry'
      /** 第几次尝试（从 1 开始）：1 表示第一次失败，正在试第二次 */
      attempt: number
      maxAttempts: number
      delayMs: number
      /** 稳定的失败分类（限流 / 服务端故障 / 网络错误），不是服务商原文 */
      reason: string
    }
  | {
      kind: 'approval'
      callId: string
      approved: boolean | null
      /**
       * 后端为什么要问，摆在批准按钮旁边。
       *
       * 判据有三种（程序不在免审批名单、动到了工作区外、命令看不懂），要人过目的
       * 东西各不相同。老事件没有这一项，那时是空串。
       */
      reason: string
    }
  /**
   * 一行小字。
   *
   * `turnEnd` 只在"这一轮就此收场"时出现：失败，或被打断（按 Esc / 拒绝且没留
   * 指示）。下游据此知道这一轮没有正常跑完，例如不报"用时"（见 TurnTail）。
   */
  | {
      kind: 'notice'
      tone: NoticeTone
      text: string
      turnEnd?: TurnEnd
      /**
       * 某个人做的一个动作。存用户 id；名字在渲染时现查，改名后界面跟着变。
       * 没有它的是平台自己的动作（同步、压缩），那种没有主语。
       */
      actorId?: string | null
    }
  /**
   * 一条状态变更。不显示（渲染时返回 null），留在流里只为让"这一轮现在什么状态"
   * 只有一个事实来源（见 {@link turnStateOf}）。
   */
  | { kind: 'state'; to: string; reason: string }
  /**
   * 这一轮改了哪些文件。
   *
   * 由后端在收尾时算好、随事件流过来，不是前端去问 git 或另发请求。它自己不占
   * 一行，跟着那一轮的尾巴显示（见 `ChangesRow`）。
   */
  | {
      kind: 'changes'
      files: ChangedFile[]
      truncated: boolean
      /** 这是第几轮（后端号，见 `WorkspaceChanges.turnIndex`）；加字段之前的事件没有 */
      turnIndex: number | null
    }
  /**
   * 一个回滚点。不显示（渲染时返回 null），留在列表里只为两件事：
   *
   * 回滚要把流截断到准确的位置，认的是这一条的 `seq`（由 {@link fold} 统一盖上），
   * 见 {@link foldOne} 里 `SESSION_REWOUND` 那一支；`turn` 给回滚面板用：它表示
   * "到这儿为止完成了几次交互"，列表按它去重，也按它查"这一轮改了哪些文件"。
   *
   * 不带 sha：sha 在一条会话里会重复（一轮什么都没改时），不能用来定位。
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

/** 小字的调性。只有异常上色，正常的事情不需要颜色。 */
export type NoticeTone = 'plain' | 'good' | 'bad'

/** 这一轮怎么收场。只有没正常跑完的两种；正常收尾不需要标记。 */
export type TurnEnd = 'failed' | 'stopped'

/**
 * 一轮收尾时的上下文大小。
 *
 * 它与 `StreamItem` 里的 input/output 是两笔账：input/output 是这一轮所有模型
 * 调用的和，这里回答最后一次调用送进去的上下文有多大。两者没有加减关系；一次
 * 调用就完成的一轮里恰好相等，那正是最容易搞混的地方。
 *
 * 数由服务商报出（`prompt_tokens`），不是按字符估算。没有读数的轮次是 null 而
 * 不是 0：没调过模型和字段尚未出现都应什么都不画，而不是一个空环。
 */
export interface ContextReading {
  tokens: number
  /** 当时的模型窗口；跟着每一轮走，因为换模型会让它变 */
  window: number
}

/** 服务端推来的一帧。`seq` 只有已落库的事件才有，流式增量没有。 */
export interface Frame {
  seq?: number
  at?: string
  type: string
  payload: Record<string, unknown>
}

/** 流式生成的正文与思考。它不属于 items，是正在长出来的那一条。 */
export interface Streaming {
  text: string
  reasoning: string
}

export const EMPTY_STREAMING: Streaming = { text: '', reasoning: '' }

/**
 * 到眼下为止，这条流里确实改过工作区的动作有几条。
 *
 * 返回计数而不是一条"变了"的事件，是因为这个投影必须是纯函数：断线重连、切走再
 * 切回时历史会重放，计数结果不变，调用方只需看它有没有涨；若在这里发通知，通知
 * 会随每一帧重放再发一遍。
 *
 * 数的是后端报的 `outcome.mutated`，不是按工具名推测的名单。也不看 `success`：
 * 退出码非零的命令仍可能写了文件，后端算 mutated 时已经考虑过这一条，这里再叠
 * 一个判断等于推翻它。
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

/** 把一帧折进条目列表，返回新列表，不改原来的。 */
export function fold(items: StreamItem[], frame: Frame): StreamItem[] {
  const before = items.length
  const next = foldOne(items, frame)

  // 新追加的每一条都盖上这一帧的 seq，引用回复指向的就是它。统一在这里盖，
  // 而不是让下面各分支各写一遍：漏掉的分支只会让那条无法引用，不会报错。
  //
  // 只盖新追加的：就地补结果的分支长度不变，那条工具第一次出现时已经盖过 seq，
  // 不该被后面的帧改掉。
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
      // 完整回复到了：流式那一段到此为止，由调用方清空缓冲。思考过程也留一份。
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
      // 平台写给模型的原文不给人看。它不是一句提示，而是提示词：里面有对模型的
      // 命令，端到人面前读起来像界面在训人。按 reason 认，不按正文认（那串字随时
      // 会改）；认不出来就什么都不显示，宁可少说一句。见
      // docs/decisions/feature/2026-10-02-platform-text-never-reaches-the-ui.md。
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
      // 回头把结果填进那条调用里，而不是新加一条，见类注释。
      const callId = str(p.callId)
      const outcome: ToolOutcome = {
        success: p.success === true,
        output: str(p.output),
        truncated: p.truncated === true,
        exitCode: typeof p.exitCode === 'number' ? p.exitCode : null,
        // 老事件没有这一项，缺失按"没改"处理。
        mutated: p.mutated === true,
        // 旧事件没有结构化结果，解析出来是 null，界面照旧渲染文本。
        presentation: parsePresentation(p.presentation),
      }
      return items.map((item) =>
        item.kind === 'tool' && item.callId === callId ? { ...item, outcome } : item,
      )
    }

    case 'TOOL_INTERRUPTED':
    case 'TOOL_CANCELLED': {
      // 这两个是"那次调用没有结果"的事实：一轮在工具执行中途死了 / 被取消。
      // 折回那条调用本身，而不是旁边加一条小字：outcome 为空且没有结局就等于
      // "正在跑"，会让那一行一直闪。这里填的是第三种结局：没跑完，不是失败。
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
          reason: str(p.reason),
        },
      ]

    case 'TOOL_APPROVAL_RESOLVED': {
      const callId = str(p.callId)
      return items.map((item) =>
        item.kind === 'approval' && item.callId === callId
          ? { ...item, approved: p.approved === true }
          : item,
      )
    }

    case 'TOOL_REJECTED': {
      // 拒绝是这次调用的终局（后端落的 ToolRejected）。这一支不能少：判"还在跑"
      // 要求既没有结果也没有"没跑完"，而拒绝之后那次调用永远不会有结果，缺了它
      // 那一行会一直闪。见
      // docs/decisions/bug-fix/2026-10-05-a-rejected-call-gets-its-own-event.md。
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
      /** 状态本身也进流（见 StreamItem 的 state）。它不显示，只当事实来源。 */
      const stateItem = { kind: 'state' as const, to, reason }

      // 这一轮是否收尾由状态表决定（见 lib/sessionState）。等人批不算收尾：
      // 那条调用还在等一个人。
      const settled = settlesDangling(to) ? closeDanglingCalls(items) : items

      // 只显示值得停一下的两种：出错、挂在等人批。其余状态变化是过程细节，
      // 全列出来会把流变成一串状态日志。
      if (to === 'FAILED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'bad', text: `这一轮失败了：${reason}`, turnEnd: 'failed' },
          stateItem,
        ]
      }
      // 用户按 Esc 停的那一轮：一行暗字，不是错误。它是一条独立的事实
      // （谁停的、什么时候），只是不冒充出错。
      if (to === 'WAITING_USER' && reason === 'CANCELLED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'plain', text: '已打断 · 接下来要它做什么？', turnEnd: 'stopped' },
          stateItem,
        ]
      }
      // 拒绝且没留下指示：那一轮也停住了，与按 Esc 是同一处境，所以同一句话。
      if (to === 'WAITING_USER' && reason === 'REJECTED') {
        return [
          ...settled,
          { kind: 'notice', tone: 'plain', text: '已停下 · 接下来要它做什么？', turnEnd: 'stopped' },
          stateItem,
        ]
      }
      // 等人批不写小字：那件事由待批卡片自己表达（见 SessionStreamView 的 approval
      // 分支）。状态式的话不能写进追加式的流：流没有让话过期的机制，答复之后那句
      // "停下来等你批准"会留在原地，读起来像 bug。
      return [...settled, stateItem]
    }

    case 'CHECKPOINT_CREATED':
      // checkpoint 不显示：每轮结束都有它，画出来就是每轮加一行噪音。但它必须
      // 留在列表里，回滚靠它把流截断到准确的位置（下一支）。
      return [...items, { kind: 'checkpoint', turn: num(p.turnIndex) }]

    case 'SESSION_SYNCED':
      return [...items, { kind: 'notice', tone: 'plain', text: '同步了主干的最新改动' }]

    case 'SESSION_REWOUND': {
      // 回滚要连流一起截断：恢复对话 = 把选中的那句话及其之后的内容删掉，对话
      // 从那里重新往下长。
      //
      // 截到哪一条认序号（toCheckpointSeq），不认 sha：一轮什么都没改时两条
      // checkpoint 的 sha 相同，按 sha 找只能猜，曾把"退到第三句之前"执行成清空
      // 整个对话。
      //
      // 找不到就不截断：那表示位置不在手上这段流里（例如从中间开始读），清空整段
      // 会凭空少掉一整段对话。序号缺失同样当成找不到，不拿 sha 猜。
      const seq = p.toCheckpointSeq
      const at =
        typeof seq === 'number'
          ? items.findIndex((item) => item.kind === 'checkpoint' && item.seq === seq)
          : -1
      const kept = at < 0 ? items : items.slice(0, at + 1)
      // 要念的那句话在被截掉的那一段里，所以传截断前的 items 而不是 kept。
      return [...kept, {
        kind: 'notice',
        tone: 'plain',
        text: rewindNotice(items, at),
        // 谁退的：存 id，名字在渲染时现查。
        actorId: idOf(p.byUserId) || null,
      }]
    }

    case 'WORKSPACE_CHANGES':
      // 后端收尾时算好的"这一轮改了什么"：哪些文件、各多少行随流走，读的人照着
      // 读，不用再问 git。只有正文是按需取（点开文件时，见 ProjectDiffView），
      // 所以提交被回收后正文会问不出来，那时界面要如实显示，不编。
      return [...items, {
        kind: 'changes',
        files: Array.isArray(p.files) ? p.files.map((file) => changedFile(file, str(p.commitSha))) : [],
        truncated: p.truncated === true,
        // 缺字段表示"加这个字段之前落的"，不能默认成 0：0 是合法的轮次号。
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
      // 两个都是 0 就别铺了：空事件写出来只是一行噪音。
      if (input === 0 && output === 0) {
        return items
      }
      // 这两个数是这一轮收尾时的读数，不是前面几次的和。缺一个就不画：
      // 分母为零的环没有意义，0/64000 会画出"上下文是空的"假象。
      const tokens = num(p.contextTokens)
      const window = num(p.contextWindow)
      return [
        ...items,
        {
          kind: 'usage',
          input,
          output,
          // 输入里命中缓存的部分。单价低得多，在 BYOK 下直接是钱（见 UsageRow）。
          cached: num(p.cachedInputTokens),
          context: tokens > 0 && window > 0 ? { tokens, window } : null,
          at: nullableStr(frame.at),
        },
      ]
    }

    // 流式增量和"正文被清过"不在这里产生条目：前者由调用方喂进流式缓冲，
    // 后者只给模型省，人不需要知道。
    case 'ASSISTANT_DELTA':
    case 'REASONING_DELTA':
    case 'TOOL_RESULTS_CLEARED':
    case 'SESSION_STARTED':
      return items

    default:
      // 认不出来的类型安静跳过：前端旧版本遇到新事件不应崩溃，也不该冒出一行
      // "未知事件"，那对读流的人没有意义。
      return items
  }
}

/** 把一帧折进流式缓冲。正文与思考分开，因为它们显示在不同位置。 */
export function foldStreaming(current: Streaming, frame: Frame): Streaming {
  const p = frame.payload ?? {}
  if (frame.type === 'ASSISTANT_DELTA') {
    return { ...current, text: current.text + str(p.text) }
  }
  if (frame.type === 'REASONING_DELTA') {
    return { ...current, reasoning: current.reasoning + str(p.text) }
  }
  // 完整回复到了：缓冲交出去并重新开始。漏了这一步，下一轮的流式内容会接在
  // 上一轮后面。
  if (frame.type === 'ASSISTANT_MESSAGE') {
    return EMPTY_STREAMING
  }
  // 一轮收尾也要清：被打断的那一轮永远不会有 ASSISTANT_MESSAGE，缓冲会一直挂着，
  // 界面表现为它还在跑。哪些状态清理由 lib/sessionState 的表决定。
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
 * 这是兜底，不是正路：正常路径上每条调用都有自己的收尾（结果 / 取消 / 中断 /
 * 拒绝），一旦哪条路漏写收尾，那一行会一直闪。
 *
 * 批过的调用不能扫：它没跑完是因为马上要跑（批准后状态短暂回到"等用户"，然后
 * 继续执行），扫了会把一条即将运行的调用错报成"没有结果"。
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
 * 回滚提示的措辞。
 *
 * 不说"回滚到第 N 轮"：N 是"完成过几次交互"的计数器，不是人眼里的位置，读起来
 * 像退错了，却没说明到底退到哪儿。改为念出被截掉的第一句用户消息，即"从这儿
 * 开始没有了"。
 *
 * @param before 回滚前那份完整列表：要念的话在被截掉的那一段里
 * @param at     位置标记在 before 里的下标；-1 表示没找到（于是没截断）
 *
 * 提示不包含"谁退的"：名字在渲染时按 id 现查（见 {@link StreamItem} 的 actorId）。
 */
function rewindNotice(before: StreamItem[], at: number): string {
  // at < 0 表示没找到位置、什么都没截；此时"退掉了哪句话"没有答案，
  // 不能拿"最后一条之后"充数。
  const dropped = at < 0 ? [] : before.slice(at + 1)
  const next = dropped.find((item) => item.kind === 'user')
  // 一句都没退掉（退到最新位置）时说"回滚了这条会话"：硬念一句就是编的。
  return next?.kind === 'user' ? `回滚到「${next.text}」之前` : '回滚了这条会话'
}

/** 一条改动记录。字段缺失按"没有"处理：数据从线上流里来，不能假设完整。 */
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
 * 领域侧是值对象，序列化出来是 `{"value":"…"}`；裸字符串也认，于是哪天形状改了
 * 这里不用跟着改。
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
 * 一个 token 数的写法：带千位分隔符。
 *
 * 一轮的输入常到五位数，分隔符让人一眼看出量级，不必数位数。不用 "12.2K" 这种
 * 缩写：那是给几万几十万的上下文量准备的，几千的用量缩写反而要换算。
 */
export function formatTokens(value: number): string {
  return value.toLocaleString('en-US')
}
