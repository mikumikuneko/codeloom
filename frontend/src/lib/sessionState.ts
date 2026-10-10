/**
 * 会话状态 —— **有哪些状态、每个状态意味着什么，全仓只有这里说**。
 *
 * <h2>为什么收在一处</h2>
 * 这份词表以前散着，而且每一处只列了"我关心的那几个"：会话流那边列了三个终态、
 * 历史弹窗那边列了六个。于是**新增一个状态时没有任何东西会提醒你** ——
 * 漏掉的那处不报错，只是那个状态显示成一句原始英文，或者某次判断悄悄失效。
 *
 * <h2>为什么不是"一张名单"而是"每个状态一行、三个问题一起答"</h2>
 * 界面对同一个状态要问三件事（还能不能打断它、要不要给悬挂的调用收尾、
 * 要不要丢掉还在长的那截缓冲），而**三件事的答案并不一样**：`AWAITING_APPROVAL`
 * 既不能打断、也不收尾（那条调用还在等一个人），却必须丢缓冲（模型停在工具中间，
 * 那半截文字不会再长）。分开列名单的话，加一个状态要记得改三处，
 * 而那三处**长得还很像** —— 漏掉的不报错，只在某个边界上表现成画面不动。
 *
 * <p>收成一张表之后，加状态必须**一次性回答完三个问题**：`Record<SessionState, …>`
 * 漏一个键就编译不过。那正是项目在别处用穷尽 switch 换来的同一样东西。
 *
 * <p>**点名不会漂，名单会**：`=== 'AWAITING_APPROVAL'` 那种只指一个东西的比较
 * 留在原地就好，不必绕到这张表里来。
 */

/** 后端那个状态枚举的取值。 */
export type SessionState =
  | 'IDLE'
  | 'THINKING'
  | 'EXECUTING_TOOL'
  | 'AWAITING_APPROVAL'
  | 'WAITING_USER'
  | 'FAILED'

/** 一个状态对界面意味着什么。三件事各自独立，见文件开头的说明。 */
interface TurnMeaning {
  /** 这一轮**还在被推进** —— 也就是"它还在干活，你现在能打断它"。 */
  active: boolean
  /** 收尾时给"请求了却没有结果"的调用补一条。**等人批不算收尾**。 */
  settlesDangling: boolean
  /** 已经在长的那半截文字作废了（这一轮不会再把它说完）。 */
  dropsStreaming: boolean
}

/**
 * 每个状态的三件事。
 *
 * <p>`IDLE` 三个都是 false：它只出现在**失败重试**那两步之间（`FAILED → IDLE → THINKING`），
 * 所以它既不活跃、也不算收尾 —— 这也是重试那条路不会把流式缓冲清掉的原因。
 */
export const TURN: Record<SessionState, TurnMeaning> = {
  IDLE: { active: false, settlesDangling: false, dropsStreaming: false },
  THINKING: { active: true, settlesDangling: false, dropsStreaming: false },
  EXECUTING_TOOL: { active: true, settlesDangling: false, dropsStreaming: false },
  AWAITING_APPROVAL: { active: false, settlesDangling: false, dropsStreaming: true },
  WAITING_USER: { active: false, settlesDangling: true, dropsStreaming: true },
  FAILED: { active: false, settlesDangling: true, dropsStreaming: true },
}

/**
 * 线上来的那个字符串对应哪一行 —— **认不出来当"什么都没有"**。
 *
 * <p>参数收 `string` 而不是 `SessionState`：事件是线来的，它可能是这个前端还不认识的
 * 状态（后端先上了新状态）。往"不动作"那一侧倒：不活跃、不收尾、不丢缓冲，
 * 也就是"什么都别做，等着看"。
 */
function meaningOf(state: string | null): TurnMeaning | undefined {
  return state === null ? undefined : TURN[state as SessionState]
}

/** 这一轮还在被推进吗。 */
export function isActive(state: string | null): boolean {
  return meaningOf(state)?.active === true
}

/** 收尾时要不要给悬挂的调用补一条。 */
export function settlesDangling(state: string | null): boolean {
  return meaningOf(state)?.settlesDangling === true
}

/** 要不要把还在长的那截文字丢掉。 */
export function dropsStreaming(state: string | null): boolean {
  return meaningOf(state)?.dropsStreaming === true
}
