/**
 * 唯一的 HTTP 入口。
 *
 * <h2>为什么所有请求都要过这里</h2>
 * 后端把每一种失败都变成了**同一种形状**（RFC 7807 的 `ProblemDetail`）：
 * 校验失败、409、403 全都是 `{title, detail, status}`。要是每个调用点各自
 * `fetch` + 各自解析，那份"怎么读错误"的知识就会散在几十个地方，而其中一半会写错。
 *
 * <h2>凭据是 cookie，所以什么都不用做</h2>
 * 后端用的是**会话 cookie**，浏览器会自动带上（同源请求的默认行为）。
 * 这里显式写 `same-origin` 是把这条约定写下来 —— 谁哪天改成 `include`，
 * 等于在说"我要跨源发凭据"，那是一个要同时改后端 SameSite 的决定，不该悄悄发生。
 */

/** 后端返回的 ProblemDetail。`detail` 是给人看的那句话，`title` 是它的分类。 */
export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
  /** 合并冲突时后端会额外带上这些（见 ApiExceptionHandler） */
  conflictingPaths?: string[]
  commitsBehind?: number
}

/** 一次失败的请求。`status` 用来分支处理，`detail` 用来给人看。 */
export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetail

  constructor(status: number, problem: ProblemDetail) {
    // 优先用 detail：那是后端**特意写给调用方看的**那句话
    //（见 ApiExceptionHandler 的类注释里为什么要把 reason 带出去）。
    // 没有 detail 时退到 title，再没有就报状态码 —— 不能给一个空消息，
    // 那样界面上会出现一个没有原因的"出错了"
    super(problem.detail || problem.title || `请求失败（HTTP ${status}）`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/**
 * 发一个请求。
 *
 * @param path 后端的路径，比如 `/api/projects`。**带前导斜杠** —— dev 代理按前缀匹配
 * @returns 解析好的 JSON；204 或空响应体时返回 `undefined as T`
 * @throws ApiError 任何非 2xx
 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    // **一个读实时数据的应用不该让浏览器缓存任何一次请求。**
    //
    // 后端那几个读接口没有发 `Cache-Control`，于是浏览器按启发式规则自己决定存不存 ——
    // 症状是很具体的一种：**刷新页面（F5）能看到最新的，普通地切来切去看到的是旧的**
    // （F5 会带 `max-age=0` 强制去问服务端，普通请求不会）。
    // 树、文件内容、主干同步之后的样子，全都栽在这上面。
    //
    // `no-store` 而不是 `no-cache`：后者只是"每次先问一下服务端"，仍然会存；
    // 这里根本不想让任何一份响应留在缓存里 —— 工作区随时在被 agent 改
    cache: 'no-store',
    headers: {
      // 只有带 body 的请求才加 Content-Type：给 GET 加一个会让某些代理
      // 把它当成"有实体的请求"，而那个区别只会在排障时才显形
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
 * <p>**读不出来也要给一个能看的错误**：502 之类的响应根本不是 JSON
 *（网关自己生成的 HTML），而那时候在解析上再炸一次，界面上就只剩
 * "Unexpected token < in JSON" —— 那对用户和排障都没有任何帮助。
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
// 刻意**不**做成一个大对象（`api.projects.list()` 那种）：那会在类型上多一层
// 需要跟着后端改的镜像，而这里的每个函数本来就只是一次 `api()` 调用。
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
  /** 哪一家。**删除、拉模型、建会话都用它** */
  provider: string
  /** 显示名；用户没起过名字时后端回落到预设名 */
  name: string
  /** 密钥掩码（头八尾四，中间固定五个星），用来认"填的是哪一把"；读不出来时为 null */
  hint: string | null
  configuredAt: string
}

/**
 * 我们认识的一家 —— 界面上的"预设供应商"卡片。
 *
 * <p>**没有请求地址**：发请求由服务端做，地址留在服务端。这里只有给人看和点的东西。
 */
export interface ProviderPreset {
  id: string
  displayName: string
  /** 官网，**只用来给人点过去** */
  officialUrl: string
}

export const apiKeys = {
  list: () => api<ConfiguredKey[]>('/api/auth/api-key'),

  /** 认识的那几家（预设卡片）。和上面那个是两件事：这个是产品说明，那个是"我配了什么"。 */
  providers: () => api<ProviderPreset[]>('/api/providers'),

  /**
   * 存或换一把。
   *
   * <p>{@code name} 可以留空 —— 界面上就用预设名。**后端会把空名字写成 NULL**，
   * 而不是空串：那样"没起过名"和"起了个空名"才是同一件事。
   *
   * <p>不收地址：地址由服务端按这一家取（见后端 {@code Providers}）。
   */
  configure: (provider: string, name: string, apiKey: string) =>
    api<void>('/api/auth/api-key', {
      method: 'PUT',
      body: JSON.stringify({ provider, name, apiKey }),
    }),

  remove: (provider: string) =>
    api<void>(`/api/auth/api-key?provider=${encodeURIComponent(provider)}`, {
      method: 'DELETE',
    }),

  /**
   * 这一家现在有哪些模型 —— **后端替我们去问服务商本人**。
   *
   * <p>拉不到时它会以 502 回来，而且**消息里说得清是哪一种失败**（密钥不对 / 这一家
   * 没有 /models / 连不上）—— 直接显示那句话，别自己再编一套文案。
   */
  models: (provider: string) =>
    api<{ models: string[] }>(
      `/api/auth/api-key/models?provider=${encodeURIComponent(provider)}`,
    ),
}

// ---------------------------------------------------------------------------
// 项目
// ---------------------------------------------------------------------------

export interface ProjectMember {
  id: string
  username: string
  displayName: string
}

export interface Project {
  id: string
  name: string
  /** 房主。**它决定退出时该说哪句话**：房主退出会把项目交给对方 */
  ownerId: string
  members: ProjectMember[]
  /**
   * 项目目录叫什么 —— 左边那棵树的第一行。
   *
   * <p>**从服务端来**，不是这里写死的：目录名是服务端定的，
   * 客户端自己写一个 `untitled` 就是第二条真相，改名那天会对不上。
   */
  rootName: string
}

export const projects = {
  mine: () => api<Project[]>('/api/projects'),
  one: (projectId: string) => api<Project>(`/api/projects/${projectId}`),
  create: (name: string) =>
    api<Project>('/api/projects', { method: 'POST', body: JSON.stringify({ name }) }),
  /**
   * 退出项目。**每个人都能调，包括房主** —— 没有"删除项目"这个动作。
   *
   * <p>{@code me} 在路径里：这个动作只能对自己做。
   */
  leave: (projectId: string) =>
    api<void>(`/api/projects/${projectId}/members/me`, { method: 'DELETE' }),
}

/**
 * 下载这个项目（主干，zip）的地址。
 *
 * <p>**它不经过 `api()`**：下载走的是浏览器自己那条通路（响应带 `Content-Disposition`），
 * 给一个地址就够 —— 而且它是**顶层导航**（正好是 `SameSite=Lax` 允许带上 cookie 的那一类），
 * 所以不必先取一次 blob 再存盘，也不必给请求塞任何头。
 */
export function projectArchiveUrl(projectId: string): string {
  return `/api/projects/${projectId}/archive`
}

// ---------------------------------------------------------------------------
// 邀请链接
// ---------------------------------------------------------------------------

/**
 * 一张发出去的邀请。**服务端返回的是 token，不是完整链接** —— 见下。
 *
 * <p>没有"还能不能用"和"为什么不能"：**这个类型里出现的都是此刻还能用的**
 *（作废的既不在清单里、也不会被这样返回）。要那一栏的是另一条路 ——
 * 拿着链接点开的人看到的是 {@link InvitationPreview}，那里有。
 */
export interface Invitation {
  token: string
  createdAt: string
  expiresAt: string
}

/** 点开别人链接时看到的东西。 */
export interface InvitationPreview {
  projectId: string
  projectName: string
  /** 邀请人的用户名；对方被删掉时为 null */
  invitedBy: string | null
  expiresAt: string
  usable: boolean
  reason: string | null
  /**
   * 拿着这张链接的人**自己**处在哪一态 —— 界面靠它决定给什么动作。
   *
   * <p>登录是可选的（没登录就是 GUEST），所以匿名访客永远拿到 GUEST，
   * 看得到的还是原来那些：它只说"你自己在不在里面"，不说别人。
   */
  viewer: 'GUEST' | 'MEMBER' | 'INVITER'
}

/**
 * 完整链接由**客户端**拼。
 *
 * <p>服务端不知道自己在外面叫什么 —— 反向代理、换域名、本地开发是 localhost:5173……
 * 猜错就是把一张指向错误地址的链接发出去。而"我在哪个 origin 上"是浏览器本来就知道的事。
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

  /** 预览**不需要登录**：还没注册的人也该看得见自己在被邀请去哪。 */
  preview: (token: string) => api<InvitationPreview>(`/api/invitations/${token}`),
  /** 接受**要登录** —— 成员身份得挂在某个人身上。 */
  accept: (token: string) =>
    api<Project>(`/api/invitations/${token}/accept`, { method: 'POST' }),
}

// ---------------------------------------------------------------------------
// 会话
// ---------------------------------------------------------------------------

export interface SessionModel {
  /** 哪一家。**没有地址** —— 那是服务端的事 */
  provider: string
  modelId: string
  systemPrompt: string | null
}

export interface Session {
  id: string
  projectId: string
  ownerId: string
  /** 分支名和 HEAD 是**工作区**的属性 —— 同一个人在这个项目里的几条会话看到的是同一对值 */
  branch: string
  headCommit: string | null
  state: string
  turnIndex: number
  model: SessionModel
  /**
   * 用户开口的**第一句话** —— 历史列表拿它区分"这是哪一条"。
   *
   * <p>为什么不是"谁在说"：那一栏里列的是同一个人的会话，说话人区分不了它们，
   * 而开头那句正是人自己记得住的（见后端 {@code SessionView} 的类注释）。还没人说过话时是 null。
   */
  firstMessage: string | null
}

/**
 * 建一条会话要带的东西。
 *
 * <p>**没有系统提示词这一项** —— 它不是用户配的东西。这个产品里"agent 该怎么干活"
 * 是平台定的，用户选的是端点和模型。那个字段一度存在过（后端有列、这里有个可选属性），
 * 但从来没有人填过它，留着只会让"用户能改系统提示词"这件事看起来像个功能。
 */
export interface NewSession {
  provider: string
  modelId: string
}

// ---------------------------------------------------------------------------
// 工作区里的文件
// ---------------------------------------------------------------------------

export interface FileEntry {
  name: string
  /**
   * 相对**项目根**的路径，`/` 分隔。
   *
   * <p>项目根是那个叫 {@link Project.rootName} 的目录（默认 `untitled`），
   * 而它下面一层才是 git 工作区根 —— 两者不是一回事。这里和 agent 用的是同一套路径，
   * 所以会话流里的路径点一下就能在中栏打开，不需要翻译。
   */
  path: string
  directory: boolean
  /** 目录、或者读不到大小时为 null */
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
   * 列**一层**目录。
   *
   * @param path  空 = 工作区根目录。**不递归** —— 前端点开一层拉一层
   * @param owner 看谁的树；不传就是自己的（观战时才传）
   * @param trunk 看**主干**。和 owner 互斥：一个是"谁"，一个是"那条共享的线"
   */
  list: (projectId: string, path = '', owner?: string, trunk = false) =>
    api<FileEntry[]>(
      `/api/projects/${projectId}/files?path=${encodeURIComponent(path)}` +
        (owner ? `&owner=${encodeURIComponent(owner)}` : '') +
        (trunk ? '&trunk=true' : ''),
    ),

  /** @param trunk 读**主干**上的它；和 owner 互斥 */
  read: (projectId: string, path: string, owner?: string, trunk = false) =>
    api<FileContent>(
      `/api/projects/${projectId}/files/content?path=${encodeURIComponent(path)}` +
        (owner ? `&owner=${encodeURIComponent(owner)}` : '') +
        (trunk ? '&trunk=true' : ''),
    ),

  // 下面三个**没有 owner 参数**，这不是漏了：读可以读别人的（观战要看得见对方的代码），
  // 写只能写自己的。带上 owner 就等于"我可以删掉对方工作区里的文件"

  /** 新建一个空文件，或者一个空目录。目标已存在时后端回 409，绝不覆盖 */
  create: (projectId: string, path: string, directory: boolean) =>
    api<void>(`/api/projects/${projectId}/files`, {
      method: 'POST',
      body: JSON.stringify({ path, directory }),
    }),

  /** 改名，或者挪个位置 —— 改的都是路径，所以是同一个接口 */
  move: (projectId: string, from: string, to: string) =>
    api<void>(`/api/projects/${projectId}/files/move`, {
      method: 'POST',
      body: JSON.stringify({ from, to }),
    }),

  /** 删掉一个文件或一整棵目录 */
  remove: (projectId: string, path: string) =>
    api<void>(`/api/projects/${projectId}/files?path=${encodeURIComponent(path)}`, {
      method: 'DELETE',
    }),
}

// ---------------------------------------------------------------------------
// 工具目录
// ---------------------------------------------------------------------------

/**
 * 一个工具**声明**的样子。
 *
 * `shape` 是**闭集**，界面按它选组件 —— 所以新增一个工具只要形状是已有的，
 * 这里一行都不用改。`label` 是工具自己的动作词（读取 / 运行 / 新建），
 * `subjectKey` 说"参数里哪一项是这次调用的主语"。
 */
export interface ToolView {
  name: string
  shape: 'read' | 'edit' | 'search' | 'execute' | 'plan' | 'other'
  /**
   * 动作词。**没声明时它不在响应里** —— 整个 API 都按
   * `default-property-inclusion: non_null` 走，所以"没有"在线上就是"这一项缺席"。
   * 判空要写成 `=== undefined`，写成 `=== null` 的判据在真实响应上永远不成立。
   */
  label?: string
  /** 参数里哪一项是主语。**没有主语时同样缺席** */
  subjectKey?: string
  /** 主语是不是一个工作区里的路径 —— 决定"能不能点开"。基本类型，永远在场 */
  subjectIsPath: boolean
}

export const tools = {
  /** 全部工具声明的样子。**静态的**，取一次就够（见后端 ToolsController）。 */
  list: () => api<ToolView[]>('/api/tools'),
}

// ---------------------------------------------------------------------------
// 项目聊天室
// ---------------------------------------------------------------------------

/** 一条聊天消息。**它是人说的话**，agent 看不见（见后端 ChatWebSocketHandler 的类注释）。 */
export interface ChatMessage {
  id: number
  projectId: string
  authorId: string
  text: string
  /** 引用回复：指向某条事件；没引用时为 null */
  anchorEventSeq: number | null
  /**
   * 被引用那一步**是哪一步**的一句话说明（"编辑了 Foo.java"）。
   *
   * 它是那一步的**动作**（动词 + 对象），**不是**把那条事件的正文概括成一句话 ——
   * 要回答的只有"我指的是哪一步"。后端的 `ChatMessage.anchorText` 那里写着同样的话。
   *
   * <p>**跟消息一起存下来的**，不是每次去查那条事件：被引用的事件属于**对方的**会话流，
   * 聊天室这边看不到；而且事件会随会话清理而消失，而引用该留住当时指的是什么。
   * 和 `anchorEventSeq` 同生共死
   */
  anchorText: string | null
  createdAt: string
}

export const chat = {
  /** 历史。**打开标签页时拉一次**，之后新消息走 WebSocket 那条通道。 */
  history: (projectId: string) =>
    api<ChatMessage[]>(`/api/projects/${projectId}/chat/messages`),
}

export const messages = {
  /**
   * 发一句话，让它跑一轮。**这个请求是阻塞的** —— 它一直等到那一轮跑完才返回，
   * 可能几十秒。
   *
   * <p>所以进度**不从这个响应里看**：那一轮的过程同时从 SSE 那条通道推过来
   *（见 `useSessionStream`）。这个响应只负责最后给个结论。
   *
   * @param clientMessageId 挡住"同一个请求被处理两次"。**同一句话重发必须用同一个值** ——
   *                        所以它由内容的哈希决定，而不是每次点击新生成一个
   */
  send: (sessionId: string, text: string, clientMessageId: string) =>
    api<unknown>(`/api/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ text, clientMessageId }),
    }),
}

export const sessions = {
  /** 项目下的会话。**包括队友的** —— 观战要看得见他 */
  forProject: (projectId: string) => api<Session[]>(`/api/projects/${projectId}/sessions`),
  one: (sessionId: string) => api<Session>(`/api/sessions/${sessionId}`),
  create: (projectId: string, model: NewSession) =>
    api<Session>(`/api/projects/${projectId}/sessions`, {
      method: 'POST',
      body: JSON.stringify(model),
    }),
  /**
   * 换这条会话用的模型。
   *
   * <p>**下一轮生效**：正在跑的那一轮已经拿到配置快照了，中途改靶会让"这一轮用的是哪个模型"
   * 说不清。所以会话正忙时后端回 409，那不是故障，是"现在不行"—— 调用方把那句话说出来即可。
   *
   * <p>哪一家和模型名一起给：换供应商就是换 {@code provider}。后端会拿它去认
   * **你配过的那把密钥**，认不出来就回 400 —— 否则会话会被改成一个下一轮根本跑不动的配置，
   * 而那时候错误离用户的操作已经很远了。
   */
  switchModel: (sessionId: string, model: { provider: string; modelId: string }) =>
    api<Session>(`/api/sessions/${sessionId}/model`, {
      method: 'PUT',
      body: JSON.stringify(model),
    }),
  /**
   * 打断正在跑的那一轮。
   *
   * <p>**它是个信号，不是一条命令**（后端回 202 而不是 200）：取消只在工具边界上
   * 被检查（每次调模型前、每个工具调用前），所以"发出去了"和"它停了"之间隔着
   * 一段不确定的时间 —— 而且这一轮也可能刚好跑完。界面上别把它当成"已经停了"。
   *
   * <p>没在跑的时候调它是**空操作**，不报错。
   */
  interrupt: (sessionId: string) =>
    api<void>(`/api/sessions/${sessionId}/interrupt`, { method: 'POST' }),
  /** 丢弃这条会话：对话没了，**代码留着**（树属于「人 + 项目」，不属这条会话）。 */
  discard: (sessionId: string) => api<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),
  /**
   * 每一轮改了哪些文件、能退回哪儿 —— **这些不再走接口**：它们是事件流的一部分，
   * 从 `sessionStream` 的 fold 里读（见 `turnChanges` 和后端那条 `WorkspaceChanges`）。
   *
   */

  /**
   * 回滚：**代码和对话一起**退到那个点之前。
   *
   * <p>后端刻意只收一个目标而不是"代码退到哪 + 对话退到哪"两个参数 ——
   * 两者绑死是写在 {@code CheckpointCreated} 上的约定：只退代码会让模型对着一个
   * 已经不存在的现状继续推理，只退对话会让磁盘上的改动找不到任何解释。
   *
   * <p>送的是**那条 checkpoint 在事件流里的序号**，不是它的 commit sha：一轮什么都没改
   * 时两条 checkpoint 会同 sha，拿它定不了位（后端从前就是按 sha 猜的，猜的方向恰好是
   * 退过头）。序号是界面上唯一的，从会话流那份 fold 里读得到（见 `sessionStream` 的 `turnChanges`）。
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
 * <p>后端那条路一直是完整的：白名单外的命令会让整轮**停下来**，
 * 会话状态进 AWAITING_APPROVAL，落一条 ToolApprovalRequested。
 * 缺的只是这里 —— 界面上写着"等你批准"，却没有任何地方能批。
 *
 * <p>**只有会话所有者能批**（后端是 requireDriver）：B 用自己的 key 驱动自己的 agent，
 * A 既不知道它在干什么、也不知道为什么，让 A 点这个"同意"等于让 A 替 B 的选择负责。
 *
 * <p>答复之后那一轮**会自动续跑**，不需要再发一句话 —— 它是为了等人批才停的，
 * 不是用户想停。
 */
export const approvals = {
  resolve: (sessionId: string, callId: string, approved: boolean) =>
    api<void>(`/api/sessions/${sessionId}/approvals/${encodeURIComponent(callId)}`, {
      method: 'POST',
      // reason 传 null 而不是省略：后端**刻意不默认任何一边**（见那个控制器），
      // 显式给一个 null 免得和"字段没传上来"混成一件事
      body: JSON.stringify({ approved, reason: null }),
    }),
}

// ---------------------------------------------------------------------------
// 同步 / 合并 / 冲突
// ---------------------------------------------------------------------------

export interface Verification {
  command: string
  passed: boolean
  exitCode: number | null
  summary: string
}

export interface MergeOutcome {
  /** `MERGED` / `FAST_FORWARD` / `CONFLICT_PENDING` */
  status: string
  mergeCommitSha: string | null
  /** 合并之后在主干上跑了一次验证；没认出构建文件时为 null */
  verification: Verification | null
  remainingConflicts: string[]
}

export interface SyncOutcome {
  status: string
  fromHead: string
  toHead: string
}

/** 两侧的**名字**永远指向同一份东西，不随合并方向翻转（见后端的 ConflictView）。 */
export interface Conflict {
  path: string
  /** 会话那侧（我改的） */
  sessionSide: string
  /** 主干那侧（对方已经合进去的） */
  mainSide: string
}

export interface Conflicts {
  direction: 'INTO_SESSION' | 'INTO_MAIN' | null
  conflicts: Conflict[]
}

/**
 * 一条冲突要取哪一侧。
 *
 * <p>用 `session` / `main` 而不是 git 的 `ours` / `theirs` —— 后者会随合并方向**反过来**，
 * 而"我要我这份"这句话在任何方向下都是同一个意思。后端也只认这两个词。
 */
export type ConflictSide = 'session' | 'main'

export const merge = {
  /** 把这条会话的产出合进主干。 */
  intoMain: (sessionId: string) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/merge`, { method: 'POST' }),

  /** 把主干的最新改动拉进这条会话的工作区（合并的反方向）。 */
  sync: (sessionId: string) =>
    api<SyncOutcome>(`/api/sessions/${sessionId}/sync`, { method: 'POST' }),

  /** 当前等着裁决的冲突。没有就是空信封，**不报错** —— 进页面时探一下是正常用法。 */
  conflicts: (sessionId: string) => api<Conflicts>(`/api/sessions/${sessionId}/conflicts`),

  /** 取某一侧。所有冲突都裁完之后后端会自己收尾，不用再点一次"完成"。 */
  keep: (sessionId: string, path: string, side: ConflictSide) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/conflicts/resolve`, {
      method: 'POST',
      body: JSON.stringify({ path, side }),
    }),

  /** 用**人给出的完整内容**了结一个文件的冲突。 */
  keepContent: (sessionId: string, path: string, content: string) =>
    api<MergeOutcome>(`/api/sessions/${sessionId}/conflicts/resolve`, {
      method: 'POST',
      body: JSON.stringify({ path, content }),
    }),

  /** 放弃这次合并，工作区回到合并前。 */
  abort: (sessionId: string) =>
    api<void>(`/api/sessions/${sessionId}/merge/abort`, { method: 'POST' }),
}