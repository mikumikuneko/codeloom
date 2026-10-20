/**
 * 会话状态：有哪些状态、每个状态对界面意味着什么，全仓只有这里说。
 *
 * 界面对同一个状态要问三件事：能不能打断、要不要给悬挂的调用收尾、要不要丢掉
 * 还在长的流式缓冲。三件事的答案并不相同：`AWAITING_APPROVAL` 既不能打断也不
 * 收尾（那条调用还在等人），却必须丢缓冲（模型停在工具中间，那半截文字不会再长）。
 * 因此每个状态一行同时回答三个问题，`Record<SessionState, …>` 漏一个键就编译不过。
 *
 * 只指一个状态的比较（`=== 'AWAITING_APPROVAL'`）留在原地，不必绕到这张表里。
 */

/** 后端那个状态枚举的取值。 */
export type SessionState =
  | 'IDLE'
  | 'THINKING'
  | 'EXECUTING_TOOL'
  | 'AWAITING_APPROVAL'
  | 'WAITING_USER'
  | 'FAILED'

/** 一个状态对界面意味着什么。三件事各自独立，见文件开头。 */
interface TurnMeaning {
  /** 这一轮还在被推进，也就是"它还在干活，现在能打断它"。 */
  active: boolean
  /** 收尾时给"请求了却没有结果"的调用补一条。等人批不算收尾。 */
  settlesDangling: boolean
  /** 已经在长的那半截文字作废了（这一轮不会再把它说完）。 */
  dropsStreaming: boolean
}

/**
 * 每个状态的三件事。
 *
 * `IDLE` 三个都是 false：它只出现在失败重试那两步之间（`FAILED → IDLE → THINKING`），
 * 所以既不活跃也不算收尾，这也是重试不会清掉流式缓冲的原因。
 */
const TURN: Record<SessionState, TurnMeaning> = {
  IDLE: { active: false, settlesDangling: false, dropsStreaming: false },
  THINKING: { active: true, settlesDangling: false, dropsStreaming: false },
  EXECUTING_TOOL: { active: true, settlesDangling: false, dropsStreaming: false },
  AWAITING_APPROVAL: { active: false, settlesDangling: false, dropsStreaming: true },
  WAITING_USER: { active: false, settlesDangling: true, dropsStreaming: true },
  FAILED: { active: false, settlesDangling: true, dropsStreaming: true },
}

/**
 * 线上字符串对应哪一行；认不出来当"什么都没有"。
 *
 * 参数收 `string` 而不是 `SessionState`：事件来自网络，可能是这个前端还不认识的
 * 状态（后端先上了新状态）。认不出来时往"不动作"一侧倒：不活跃、不收尾、不丢缓冲。
 */
function meaningOf(state: string | null): TurnMeaning | undefined {
  return state === null ? undefined : TURN[state as SessionState]
}

/** 这一轮是否还在被推进。 */
export function isActive(state: string | null): boolean {
  return meaningOf(state)?.active === true
}

/** 收尾时是否要给悬挂的调用补一条。 */
export function settlesDangling(state: string | null): boolean {
  return meaningOf(state)?.settlesDangling === true
}

/** 是否要把还在长的那截文字丢掉。 */
export function dropsStreaming(state: string | null): boolean {
  return meaningOf(state)?.dropsStreaming === true
}
