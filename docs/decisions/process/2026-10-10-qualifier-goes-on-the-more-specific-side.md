# 决策：两件事撞名时，限定词加在更具体的那一侧

## 是什么问题

全仓有**两个** `ChatMessage`，指两件毫无关系的事：

- `agent/llm/ChatMessage` —— **发给模型的那条消息**（role / content / tool_calls / tool_call_id）。
- `domain/chat/ChatMessage` —— **项目聊天室里的一条消息**（作者、时间、可以引用会话流里的某一步）。

两个都是 record、都在各自的包里、都写得不算错 —— 但读代码的人搜一次 `ChatMessage`，会拿到两堆无关的东西；而"我要找的是哪个"只能靠包名去猜。这不是错，是**名字里的信息不够**。

## 定了什么

**两件事撞名时，限定词加在更"专有"的那一侧；通用那一侧保留短名。**

落到这次：`agent/llm` 里那三件套改名，`domain/chat/ChatMessage` 不动。

- `ChatMessage` → `LlmMessage`、`ChatRequest` → `LlmRequest`、`ChatRole` → `LlmRole`。
- 判据有两条：**那一包的邻居已经叫 `LlmClient` / `LlmResult` / `LlmClientProvider`** —— 它本来就该住在那族里；而**产品自己的词是"聊天"**（`ChatPanel`、`ChatController`、`ProjectChat`、`domain/chat` 包），聊天室那条没有改名的问题，是旁边那个借用了 OpenAI 的 "chat completion" 挤了进来。

## 还考虑过什么，为什么没选

- **改聊天室那一侧**（`RoomMessage` 之类）：两边的引用面差不多大（模型侧 17 个文件、聊天室侧 15 个 + 前端 2 个），所以**代价不是判据**。没选是因为它把产品自己的词让给了一个外来词 —— 而两个参考实现里，**限定词都加在模型那一侧**：Claude Code 直接用官方 SDK 的 `MessageParam`，自己内部那个叫 `Message`，靠**后缀**分家（`SDKMessage`、`normalizeMessagesForAPI`）；deepseek-harness 自己定义 `Message`，靠**包名**（`@deepseek-ai/dsh-llm`）限定，wire 层另有 `WireMessage`。
- **只改 `ChatMessage`，另两个留着**：代价一样（那三个的引用点落在同一批文件里），而半改一族正是这个项目一直在清的那种"两套词指同一件事" —— 改完 `LlmRequest` 里躺着 `List<LlmMessage>` 才顺。
- **都不改，只在两个类上各写一句"我不是另一个"**：那句话是**给撞车打补丁**，不是消除它；而且下一个读到的人还是得先发现有两个。参考实现里也**没有**哪一家是靠这种注释混过去的（只有 DSH 那份仓库级命名契约里那句「**A distinct suffix prevents an import and declaration collision.**」——它选择的是加后缀）。
- **给模型侧加 `Model*` 前缀**：`Model` 在这个项目里已经占住了别的意思（`ModelConfig`、`ModelCapabilities`），而"哪一家"的说法是 `Llm*`（见 `LlmClient`、`LlmResult`、`LlmRetryScheduled`）。

## 代价与换来的

- 代价：17 个文件改名（都在这三件套的引用面里，**没有一处同时用着聊天室那个**，所以没有夹缝）。前端不受影响 —— 它那个 `ChatMessage` 是聊天室的，没动。
- 换来：**全仓再没有两个同名的类**（`find … | uniq -d` 现在为空），而且那三个名字回到了自己那一包已有的 `Llm*` 一族。
- 一条顺手记下的事实：`LlmMessage` 自称**中间表示（IR）**、"刻意 provider 无关" —— 而 `ChatMessage` 是 OpenAI 协议那边的词。改名之后，名字和它自己的注释终于是同一个立场。

## 什么情况下该重新考虑

- 如果哪天聊天室那侧也要加限定词（比如项目里出现第二种"消息"），照这条判据办：加在**更具体**的那一侧。
- 这条判据只管**撞名**。不撞名的时候不要为了"整齐"去加限定词 —— 参考实现那份契约里还有一句：「**Do not remove an intentional vendor qualifier to avoid repetition.**」，反过来也一样：没撞上就别加。
