/**
 * 唯一的 HTTP 入口。
 *
 * 后端把所有失败都表示为 RFC 7807 的 `ProblemDetail`（`{title, detail, status}`），
 * 解析错误的知识只放在这里，调用点拿到的是 `ApiError` 和它给人的那句话。
 *
 * 凭据是会话 cookie，浏览器随同源请求自动带上。这里显式写 `same-origin`：改成
 * `include` 等于声明要跨源发送凭据，必须同时改后端的 SameSite 配置。
 */

import type { SessionState } from '@/lib/sessionState'

/** 后端返回的 ProblemDetail。`detail` 是给人看的那句话，`title` 是它的分类。 */
interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
  /** 合并冲突时后端额外带上这些。 */
  conflictingPaths?: string[]
  commitsBehind?: number
}

/**
 * 一次请求失败后，说给用户听的那句话。
 *
 * 后端给了人话（`ApiError` 的 `message` 就是它）就用它；连不上，或者抛出的不是
 * `ApiError` 时用 `fallback`。兜底文案只在这一处，调用点不要各自再写一份。
 *
 * @param fallback 非 HTTP 失败时的说法；用贴近动作的句子，例如"读不到这个文件"
 */
export function errorText(error: unknown, fallback = '连不上服务器'): string {
  return error instanceof ApiError ? error.message : fallback
}

/** 一次失败的请求。`status` 用来分支处理，`message` 是后端给人看的那句话。 */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    // detail 是后端特意写给调用方看的；没有就退到 title，再没有就用状态码。
    // 不能给空消息，否则界面上会出现一个没有原因的"出错了"。
    super(problem.detail || problem.title || `请求失败（HTTP ${status}）`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/**
 * 发一个请求。
 *
 * @param path 后端路径，例如 `/api/projects`。必须带前导斜杠，dev 代理按前缀匹配
 * @returns 解析好的 JSON；204 或空响应体时为 `undefined`（按 `T` 处理）
 * @throws ApiError 任何非 2xx
 */
async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    // 实时数据不留给浏览器缓存。读接口不发 `Cache-Control`，浏览器会按启发式
    // 规则自行缓存，表现为刷新页面能看到最新内容、普通切换却看到旧内容。
    // 用 `no-store` 而不是 `no-cache`：后者仍会存一份，只是每次先问服务端；
    // 工作区随时在被 agent 改，任何一份响应都不该留在缓存里。
    cache: 'no-store',
    headers: {
      // 只有带 body 的请求才加 Content-Type：GET 带上它会让某些代理把请求
      // 当成有实体，而这个区别只会在排障时显形。
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  })

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response))
  }
  return readBody<T>(response)
}

/**
 * 把错误响应体读成 ProblemDetail。
 *
 * 读不出来也要给出可显示的 `status` 与 `title`：502 之类的响应不是 JSON（网关
 * 自己生成的 HTML），再在解析上抛错只会让界面显示 "Unexpected token"。
 */
async function readProblem(response: Response): Promise<ProblemDetail> {
  try {
    return (await response.json()) as ProblemDetail
  } catch {
    return { status: response.status, title: `HTTP ${response.status}` }
  }
}

async function readBody<T>(response: Response): Promise<T> {
  // 204 没有响应体，`response.json()` 会抛。删一条会话、打断一轮都用 204
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

// ---------------------------------------------------------------------------
// 各条业务路径的薄封装
//
// 不做成 `api.projects.list()` 那样的大对象：每个函数本来就只是一次 `api()`
// 调用，再包一层只会在类型上多一份要跟着后端改的镜像。
// ---------------------------------------------------------------------------

export interface AuthUser {
  id: string
  username: string
  displayName: string
}

export const auth = {
  me: () => api<AuthUser>('/api/auth/me'),
  login: (username: string, password: string) =>
    api<AuthUser>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  register: (username: string, password: string, displayName: string) =>
    api<AuthUser>('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, displayName }),
    }),
  logout: () => api<void>('/api/auth/logout', { method: 'POST' }),
}

export interface ConfiguredKey {
  /**
   * 哪一家。删除、拉模型、建会话都用它。凭据按「账号 + 供应商」存，理由见
   * `docs/decisions/architecture/2026-09-26-keys-and-identity-are-per-provider.md`。
   */
  provider: string
  /** 显示名；用户没起过名字时后端回落到预设名 */
  name: string
  /** 密钥掩码（头八尾四，中间五个星；短 key 整把为 `****`），用来辨认填的是哪一把；读不出来时为 null */
  hint: string | null
  /** 思考强度 none/low/high/max；null = 不发送，由服务商决定默认 */
  reasoningEffort: string | null
  configuredAt: string
}

/**
 * 我们认识的一家，即界面上的预设供应商卡片。
 *
 * 没有请求地址：发请求由服务端做，地址留在服务端。这里只有给人看和点的东西。
 */
export interface ProviderPreset {
  id: string
  displayName: string
  /** 官网，只用来给人点过去 */
  officialUrl: string
  /** 这一家是否支持思考强度；决定表单里要不要显示那个下拉 */
  supportsReasoningEffort: boolean
}

export const apiKeys = {
  list: () => api<ConfiguredKey[]>('/api/auth/api-key'),

  /** 产品认识的几家（预设卡片）。与上面的清单是两件事：这里描述产品，那里是"我配了什么"。 */
  providers: () => api<ProviderPreset[]>('/api/providers'),

  /**
   * 存或换一把密钥。
   *
   * `name` 可以留空，界面就用预设名；后端把空名字写成 NULL 而不是空串，
   * 使"没起过名"和"起了个空名"是同一件事。
   *
   * 不收地址：地址由服务端按 `provider` 取。`apiKey` 留空表示只改设置（已配置过时）。
   */
  configure: (provider: string, name: string, apiKey: string, reasoningEffort: string) =>
    api<void>('/api/auth/api-key', {
      method: 'PUT',
      body: JSON.stringify({ provider, name, apiKey, reasoningEffort }),
    }),

  remove: (provider: string) =>
    api<void>(`/api/auth/api-key?provider=${encodeURIComponent(provider)}`, {
      method: 'DELETE',
    }),

  /**
   * 这一家现在有哪些模型；后端替我们去问服务商。
   *
   * 拉不到时以 502 返回，消息里区分失败类型（密钥不对、这一家没有 `/models`、
   * 连不上）。直接显示那句话，不要另编一套文案。
   */
  models: (provider: string) =>
    api<{ models: string[] }>(
      `/api/auth/api-key/models?provider=${encodeURIComponent(provider)}`,
    ),
}

// ---------------------------------------------------------------------------
// 项目
// ---------------------------------------------------------------------------

interface ProjectMember {
  id: string
  username: string
  displayName: string
}

export interface Project {
  id: string
  name: string
  /** 房主。退出时的措辞由它决定：房主退出会把项目交给对方 */
  ownerId: string
  members: ProjectMember[]
  /**
   * 项目目录名，即左边那棵树的第一行。
   *
   * 从服务端来：目录名由服务端定，客户端写死 `untitled` 会产生第二条真相，改名时对不上。
   */
  rootName: string
}

export const projects = {
  mine: () => api<Project[]>('/api/projects'),
  one: (projectId: string) => api<Project>(`/api/projects/${projectId}`),
  create: (name: string) =>
    api<Project>('/api/projects', { method: 'POST', body: JSON.stringify({ name }) }),
  /**
   * 退出项目。每个人都能调，包括房主；没有"删除项目"这个动作。
   *
   * `me` 在路径里，所以这个动作只能对自己做。退出会带走自己的会话和工作树，
   * 见 `docs/decisions/architecture/2026-10-20-leaving-takes-your-tree-with-you.md`。
   */
  leave: (projectId: string) =>
    api<void>(`/api/projects/${projectId}/members/me`, { method: 'DELETE' }),
  /**
   * 某一个提交里某个文件改了什么，返回 diff 正文。
   *
   * 两个参数都来自会话流里的"这一轮改了什么"（见 `ChangedFile`）：`commitSha` 是
   * 那次提交，`path` 是项目相对路径，正是后端要求的形状。正文可能被截断，
   * `truncated` 必须如实显示：截断的正文与"只改了这么多"看起来一模一样。
   *
   * 按需取而不写进事件流的理由见
   * `docs/decisions/feature/2026-10-10-file-diff-is-fetched-on-demand.md`。
   */
  diff: (projectId: string, commitSha: string, path: string) =>
    api<{ text: string; truncated: boolean }>(
      `/api/projects/${projectId}/diff?commitSha=${encodeURIComponent(commitSha)}` +
        `&path=${encodeURIComponent(path)}`,
    ),
}

/**
 * 下载这个项目（主干，zip）的地址。
 *
 * 不经过 `api()`：下载是浏览器顶层导航，响应带 `Content-Disposition`；同源导航
 * 会自动带 cookie，因此不必先取回 blob 再存盘，也不必塞任何请求头。语义见
 * `docs/decisions/feature/2026-10-08-download-the-project-as-zip.md`。
 */
export function projectArchiveUrl(projectId: string): string {
  return `/api/projects/${projectId}/archive`
}

// ---------------------------------------------------------------------------
// 邀请链接
// ---------------------------------------------------------------------------

/**
 * 一张发出去的邀请。服务端返回 token，不返回完整链接。
 *
 * 这个类型里出现的都是此刻仍然可用的邀请：作废的既不在清单里，也不会被这样返回。
 * 拿着链接点开的人看到的是 `{@link InvitationPreview}`，可用性在那里。
 */
export interface Invitation {
  token: string
  createdAt: string
  expiresAt: string
}

/** 点开邀请链接时看到的内容。 */
export interface InvitationPreview {
  projectId: string
  projectName: string
  /** 邀请人的用户名；对方账号被删掉时为 null */
  invitedBy: string | null
  expiresAt: string
  usable: boolean
  reason: string | null
  /**
   * 拿着这张链接的人自己处在哪一态，界面靠它决定给什么动作。
   *
   * 登录是可选的，没登录即 GUEST，所以匿名访客永远拿到 GUEST。它只说明自己
   * 在不在项目里，不透露别人的状态。
   */
  viewer: 'GUEST' | 'MEMBER' | 'INVITER'
}

/**
 * 拼出完整邀请链接。
 *
 * 服务端不知道自己在外面叫什么（反向代理、换域名、本地开发），猜错就会发出指向
 * 错误地址的链接；而"我在哪个 origin 上"是浏览器本来就知道的。理由见
 * `docs/decisions/architecture/2026-09-29-invite-links-not-username-invites.md`。
 */
export function inviteUrl(token: string): string {
  return `${window.location.origin}/invite/${token}`
}

export const invitations = {
  issue: (projectId: string) =>
    api<Invitation>(`/api/projects/${projectId}/invitations`, { method: 'POST' }),
  pending: (projectId: string) => api<Invitation[]>(`/api/projects/${projectId}/invitations`),
  revoke: (projectId: string, token: string) =>
    api<void>(`/api/projects/${projectId}/invitations/${token}`, { method: 'DELETE' }),

  /** 预览不需要登录：还没注册的人也该看见自己被邀请去哪。 */
  preview: (token: string) => api<InvitationPreview>(`/api/invitations/${token}`),
  /** 接受要登录：成员身份必须挂在某个账号上。 */
  accept: (token: string) =>
    api<Project>(`/api/invitations/${token}/accept`, { method: 'POST' }),
}

// ---------------------------------------------------------------------------
// 会话
// ---------------------------------------------------------------------------

export interface SessionModel {
  /** 哪一家。没有地址，地址是服务端的事 */
  provider: string
  modelId: string
}

export interface Session {
  id: string
  projectId: string
  ownerId: string
  /** 分支名和 HEAD 是工作区的属性：同一个人在这个项目里的几条会话看到同一对值 */
  branch: string
  headCommit: string | null
  state: SessionState
  turnIndex: number
  model: SessionModel
  /**
   * 用户说的第一句话，历史列表拿它区分"这是哪一条"。
   *
   * 不拿说话人区分：那一栏列的是同一个人的会话。还没人说过话时为 null。理由见
   * `docs/decisions/feature/2026-10-08-session-title-is-the-first-message.md`。
   */
  firstMessage: string | null
}

/**
 * 建一条会话要带的东西。
 *
 * 刻意没有系统提示词：agent 怎么干活由平台定，用户选的是端点和模型。这个字段
 * 不能出现在请求形状里，否则会让"用户能改系统提示词"看起来像个功能。
 */
interface NewSession {
  provider: string
  modelId: string
}

// ---------------------------------------------------------------------------
// 工作区里的文件
// ---------------------------------------------------------------------------

export interface FileEntry {
  name: string
  /**
   * 相对项目根的路径，`/` 分隔。
   *
   * 项目根是 `Project.rootName` 指向的目录（默认 `untitled`），它是 git 工作区根下面
   * 的一层，两者不是一回事。这里和 agent 使用同一套路径，所以会话流里的路径点一下就能
   * 在中栏打开，不需要翻译。
   */
  path: string
  directory: boolean
  /** 目录，或者读不到大小时为 null */
  sizeBytes: number | null
}

export interface FileContent {
  path: string
  content: string
  truncated: boolean
  binary: boolean
  sizeBytes: number
}

export const files = {
  /**
   * 列一层目录。
   *
   * @param path  空表示项目根目录；不递归，前端点开一层拉一层
   * @param trunk 为 true 时看主干而不是自己那棵树
   */
  list: (projectId: string, path = '', trunk = false) =>
    api<FileEntry[]>(
      `/api/projects/${projectId}/files?path=${encodeURIComponent(path)}` +
        (trunk ? '&trunk=true' : ''),
    ),

  /** @param trunk 为 true 时读主干上的它 */
  read: (projectId: string, path: string, trunk = false) =>
    api<FileContent>(
      `/api/projects/${projectId}/files/content?path=${encodeURIComponent(path)}` +
        (trunk ? '&trunk=true' : ''),
    ),

  /**
   * 把人改过的完整内容写回自己的树。
   *
   * `baseContent` 是打开时读到的那一份；磁盘上已经不是它了就说明这期间被别处改过，
   * 后端回 409，前端要提示重新读取，而不是覆盖。
   */
  save: (projectId: string, path: string, baseContent: string, content: string) =>
    api<void>(`/api/projects/${projectId}/files/content`, {
      method: 'PUT',
      body: JSON.stringify({ path, baseContent, content }),
    }),

  // 这三个接口没有 owner 参数，不是遗漏：界面只读写自己那棵树。写接口尤其不能有，
  // 否则等于"我可以删掉对方工作区里的文件"。见
  // `docs/decisions/architecture/2026-09-29-write-endpoints-carry-no-owner.md`。

  /** 新建空文件或空目录。目标已存在时后端回 409，绝不覆盖 */
  create: (projectId: string, path: string, directory: boolean) =>
    api<void>(`/api/projects/${projectId}/files`, {
      method: 'POST',
      body: JSON.stringify({ path, directory }),
    }),

  /** 改名或挪位置；两者都只是改路径，所以是同一个接口 */
  move: (projectId: string, from: string, to: string) =>
    api<void>(`/api/projects/${projectId}/files/move`, {
      method: 'POST',
      body: JSON.stringify({ from, to }),
    }),

  /** 删除一个文件或整棵目录 */
  remove: (projectId: string, path: string) =>
    api<void>(`/api/projects/${projectId}/files?path=${encodeURIComponent(path)}`, {
      method: 'DELETE',
    }),
}

// ---------------------------------------------------------------------------
// 工具目录
// ---------------------------------------------------------------------------

/**
 * 一个工具声明的样子。
 *
 * `shape` 是闭集，界面按它选组件，所以新增工具只要形状已存在，前端无需改动。
 * `label` 是工具自己的动作词（读取 / 运行 / 新建），`subjectKey` 指明参数里
 * 哪一项是这次调用的主语。理由见
 * `docs/decisions/architecture/2026-10-06-tool-surface-is-declarative.md`。
 */
export interface ToolView {
  name: string
  shape: 'read' | 'edit' | 'search' | 'execute' | 'plan' | 'web' | 'other'
  /**
   * 动作词。没声明时这一项缺席，不出现为 null：整个 API 按
   * `default-property-inclusion: non_null` 序列化，判空必须写 `=== undefined`。
   */
  label?: string
  /** 参数里哪一项是主语；没有主语时同样缺席 */
  subjectKey?: string
  /** 主语是否为工作区里的路径，决定能否点开。基本类型，永远在场 */
  subjectIsPath: boolean
}

export const tools = {
  /** 全部工具声明的样子。静态数据，取一次即可。 */
  list: () => api<ToolView[]>('/api/tools'),
}

// ---------------------------------------------------------------------------
// 项目聊天室
// ---------------------------------------------------------------------------

/** 一条聊天消息，是人说的话，agent 看不见。 */
export interface ChatMessage {
  id: number
  projectId: string
  authorId: string
  text: string
  /** 引用回复指向的事件序号；没有引用时为 null */
  anchorEventSeq: number | null
  /**
   * 被引用那一步是哪一步的一句话说明（"编辑了 Foo.java"）。
   *
   * 它是那一步的动作（动词 + 对象），不是对事件正文的概括；只需要回答"我指的是
   * 哪一步"。与消息一起存下来，不在读取时去查事件：被引用的事件属于对方的会话流，
   * 聊天室看不到，而且事件会随会话清理消失。与 `anchorEventSeq` 同生共死。
   */
  anchorText: string | null
  createdAt: string
}

export const chat = {
  /** 历史。打开标签页时拉一次，之后新消息走 WebSocket。 */
  history: (projectId: string) =>
    api<ChatMessage[]>(`/api/projects/${projectId}/chat/messages`),
}

export const messages = {
  /**
   * 发一句话，让它跑一轮。请求是阻塞的：直到那一轮结束才返回，可能几十秒。
   *
   * 进度不从响应里看：那一轮的过程同时经 SSE 推来（见 `useSessionStream`），
   * 这个响应只给最后结论。
   *
   * @param clientMessageId 幂等键，每次发送都重新生成；不能由内容决定，否则打断后
   *                        原样重发会被当成重复请求挡回
   */
  send: (sessionId: string, text: string, clientMessageId: string) =>
    api<unknown>(`/api/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ text, clientMessageId }),
    }),
}

export const sessions = {
  /** 项目下的会话，包括队友的：观战要看得见他 */
  forProject: (projectId: string) => api<Session[]>(`/api/projects/${projectId}/sessions`),
  create: (projectId: string, model: NewSession) =>
    api<Session>(`/api/projects/${projectId}/sessions`, {
      method: 'POST',
      body: JSON.stringify(model),
    }),
  /**
   * 换这条会话用的模型，下一轮生效。
   *
   * 正在跑的那一轮已经拿到配置快照，中途改会让"这一轮用了哪个模型"说不清，所以
   * 会话正忙时后端回 409；那是"现在不行"，不是故障。`provider` 与 `modelId`
   * 一起给：后端用它找已配置的密钥，认不出来回 400，避免会话被改成一个下一轮
   * 跑不动的配置。理由见
   * `docs/decisions/architecture/2026-09-28-switching-model-is-an-event.md`。
   */
  switchModel: (sessionId: string, model: { provider: string; modelId: string }) =>
    api<Session>(`/api/sessions/${sessionId}/model`, {
      method: 'PUT',
      body: JSON.stringify(model),
    }),
  /**
   * 打断正在跑的那一轮。
   *
   * 这是信号，不是命令（后端回 202）：取消只在工具边界检查（每次调模型前、每个
   * 工具调用前），所以"发出去了"与"它停了"之间隔着不确定的时间，这一轮也可能
   * 刚好跑完；界面上不要显示成已经停了。没在跑时调用是空操作，不报错。见
   * `docs/decisions/architecture/2026-10-06-cancel-is-a-signal-timeout-is-a-result.md`。
   */
  interrupt: (sessionId: string) =>
    api<void>(`/api/sessions/${sessionId}/interrupt`, { method: 'POST' }),
  /**
   * 丢弃这条会话：对话删除，代码保留（树属于「人 + 项目」，不属于某条会话）。
   * 理由见 `docs/decisions/architecture/2026-09-30-discard-really-deletes.md`。
   */
  discard: (sessionId: string) => api<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),
  /**
   * 回滚：代码和对话一起退到目标 checkpoint 之前。
   *
   * 后端只收一个目标，而不是"代码退到哪 + 对话退到哪"两个参数：两者绑死是写在
   * `CheckpointCreated` 上的约定。只退代码会让模型对着已不存在的现状继续推理，
   * 只退对话会让磁盘上的改动没有解释。
   *
   * 送的是 checkpoint 在事件流里的序号，不是 commit sha：一轮什么都没改时两条
   * checkpoint 会同 sha，按 sha 定位会退过头。序号唯一，从会话流的 fold 里读得到
   * （见 `sessionStream` 的 `fold`：`checkpoint` 条目带 `seq`）。两者绑死的理由见
   * `docs/decisions/architecture/2026-09-29-rewind-the-whole-tree.md`。
   */
  rewind: (sessionId: string, toCheckpointSeq: number) =>
    api<Session>(`/api/sessions/${sessionId}/rewind`, {
      method: 'POST',
      body: JSON.stringify({ toCheckpointSeq }),
    }),
}

/**
 * 答复一次挂起的工具调用。
 *
 * 只有会话所有者能批（后端是 requireDriver）：B 用自己的 key 驱动自己的 agent，
 * A 既不知道它在做什么，也不知道为什么，让 A 同意等于让 A 替 B 的选择负责。
 * 答复之后那一轮会自动续跑，不需要再发一句话：它是为了等人批才停的。
 */
export const approvals = {
  resolve: (sessionId: string, callId: string, approved: boolean) =>
    api<void>(`/api/sessions/${sessionId}/approvals/${encodeURIComponent(callId)}`, {
      method: 'POST',
      // reason 传 null 而不是省略：后端刻意不设默认值，显式给 null，
      // 免得和"字段没传上来"混成一件事。
      body: JSON.stringify({ approved, reason: null }),
    }),
}

// ---------------------------------------------------------------------------
// 同步 / 合并 / 冲突
// ---------------------------------------------------------------------------

interface Verification {
  command: string
  passed: boolean
  exitCode: number | null
  summary: string
}

export interface MergeOutcome {
  /** `MERGED` / `FAST_FORWARD` / `UP_TO_DATE` / `CONFLICT_PENDING` */
  status: string
  mergeCommitSha: string | null
  /** 合并后在主干上跑了一次验证；没认出构建文件时为 null */
  verification: Verification | null
  remainingConflicts: string[]
}

interface SyncOutcome {
  status: string
  fromHead: string
  toHead: string
}

/** 两侧的名字始终指向同一份内容，不随合并方向翻转。 */
interface Conflict {
  path: string
  /** 会话那侧（我改的） */
  sessionSide: string
  /** 主干那侧（对方已合进去的） */
  mainSide: string
}

export interface Conflicts {
  direction: 'INTO_SESSION' | 'INTO_MAIN' | null
  conflicts: Conflict[]
}

/**
 * 一条冲突要取哪一侧。
 *
 * 用 `session` / `main` 而不是 git 的 `ours` / `theirs`：后者会随合并方向翻转，
 * 而"我要我这份"在任何方向下意思相同。后端也只认这两个词。
 */
export type ConflictSide = 'session' | 'main'

export const merge = {
  /** 把这条会话的产出合进主干。 */
  intoMain: (sessionId: string) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/merge`, { method: 'POST' }),

  /** 把主干的最新改动拉进这条会话的工作区（合并的反方向）。 */
  sync: (sessionId: string) =>
    api<SyncOutcome>(`/api/sessions/${sessionId}/sync`, { method: 'POST' }),

  /** 当前等着裁决的冲突。没有时返回空清单，不报错；进页面时探一下是正常用法。 */
  conflicts: (sessionId: string) => api<Conflicts>(`/api/sessions/${sessionId}/conflicts`),

  /** 取某一侧。所有冲突裁完后后端会自行收尾，不需要再点"完成"。 */
  keep: (sessionId: string, path: string, side: ConflictSide) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/conflicts/resolve`, {
      method: 'POST',
      body: JSON.stringify({ path, side }),
    }),

  /** 用人给出的完整内容了结一个文件的冲突。 */
  keepContent: (sessionId: string, path: string, content: string) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/conflicts/resolve`, {
      method: 'POST',
      body: JSON.stringify({ path, content }),
    }),

  /** 放弃这次合并，工作区恢复到合并前。 */
  abort: (sessionId: string) =>
    api<void>(`/api/sessions/${sessionId}/merge/abort`, { method: 'POST' }),
}