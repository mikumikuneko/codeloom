# 决策：事件流用 SSE、聊天用 WebSocket，认证用 cookie

## 是什么问题

两个人要**看**同一场会话的实时过程（事件一条条推过来），还要能**互相说话**。这两件事的形状不一样：一个是严格单向、天然有序、断了要补齐的流；另一个是双向、频率低、需要"谁在线"的通道。另外：浏览器怎么带上身份？

## 定了什么

**两条通道各用各的**：事件流走 SSE（`SessionStreamController` 的 `/stream`，帧里带 `id`，浏览器断线重连时会自动带上 `Last-Event-ID`，服务端据此补历史），聊天走 WebSocket（`ChatWebSocketHandler`）。发送消息不走那条 SSE —— 它只下行。

**认证用会话 cookie**（Spring Session + Redis），不用 `Authorization: Bearer`。CSRF 靠 cookie 的 `SameSite=Lax`，不开 token 校验；`SecurityConfig` 里写明了**什么时候必须回来改**（前端和 API 分到不同站点时 `SameSite` 就挡不住了）。

## 还考虑过什么，为什么没选

- **全部用 WebSocket**（事件流也走它）：事件流是单向、有序、要补历史的，SSE 的语义正好吻合，而且浏览器侧的 `EventSource` 免费提供重连与游标；自己用 WebSocket 实现一遍这两件事，是在重造一个标准件。
- **JWT / Bearer token**：`EventSource` 不能设置自定义请求头，浏览器的 WebSocket 握手也不能 —— 那就只剩"把 token 放进 URL"，而 URL 会进日志、进浏览器历史。cookie 是唯一能同时被这两条通道自动带上的凭据。
- **CSRF token**：Spring Security 6 默认的 XOR 掩码让"前端读 cookie 再回传"这条常规做法失效；那份复杂度在这个架构下不值得 —— 所以选了 `SameSite=Lax`，并把它的适用边界写在代码里。

## 代价与换来的

- SSE 只能单向，发消息要另开一个请求（也顺带让"发消息"走的是普通 HTTP 语义：有响应、有错误码）。
- cookie 方案把"前后端跨站部署"这条路暂时堵上了 —— 所以那条"什么时候必须回来改"不是免责声明，是提醒。
- 聊天室不做"每条消息再查一次成员"（成员被移出这个功能目前不存在）；不是成员时是"连上立刻被关"，不是"连不上"。
- 换来的是：两条通道各自用最贴合的形状，认证只有一套，浏览器自动带凭据、自动重连。
- **理由订正过一次，记准的这一条**：cookie 而不是 token 的**决定性**理由是 **WebSocket 的握手带不上自定义请求头** —— 单看 SSE 的话，用 `fetch` + `ReadableStream` 是能自己塞头的。另一半理由是 `EventSource` 免费给重连和游标（`Last-Event-ID`），而断线补齐正好靠它。
- 认证还有一层前置：会话放 **Spring Session + Redis**（不粘在某一台实例的内存里），否则多实例下随时 401 —— 这一层和"cookie 里不带 token"是一套的。
