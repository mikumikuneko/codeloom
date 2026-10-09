---
name: codeloom-prompt-standard
description: Use when writing or changing text the model reads — a tool's name, description or parameter schema, a platform-injected instruction, or a tool-result notice (spill path, truncation, approval answer, synthesized result). Not comments (see codeloom-comment-standard) and not user-facing copy
---

# codeloom 的提示词判据

这边管的是**提示词** —— 工具定义、平台注入的指令与通知，也就是**模型看得见的那些字**。
它和注释**是两回事**：注释讲给下一个人听，而这里的**每个字都会进上下文、并且直接改变模型的行为**。
所以判据的落点也不同：这边只问两件事 —— **模型能不能做对**、**这些字值不值那个 token**。

## 范围

适用：工具的 `name()` / `description()` / `parametersJsonSchema()`、平台注入的指令与通知
（落盘提示、截断说明、审批答复、回执、合成结果、清单头），以及任何会变成 `ChatMessage` 内容的字符串。

不适用：代码注释（见 `codeloom-comment-standard`）、**给人看的**界面文字和报错文案（见 `codeloom-ui-standard`）。

skill 怎么设计、多步工作流怎么拆、上下文怎么加载，也不在这份里。

## 六条原则

写新工具、新通知之前对着看。每条后面是**我们已经在哪儿这么做**：照它写，别走样。

| 原则 | 我们落在哪 |
|---|---|
| **先给最小上下文**：先说用途、能干哪些事、有什么限制，细节等真需要时再给 | 工具的 `description()` 只有两三句；长输出不直接灌进上下文，见下两条 |
| **发现要显式**：任何"以后还能取到"的东西，都得有清楚的说明和**一条可靠的取回路径** | 落盘提示给的是一条**相对工作区路径**，模型能直接拿去 `read_file` |
| **关键约束前置**：权限、破坏性后果、必需的校验，出现在那件事**之前** | 要审批的调用在执行前就挂着；`edit_file` / `write_file` 的"先读过"要求在拒绝的那一刻当场说 |
| **输出要有界**：给结论加检索用的标识或路径，别把整份日志往里倒 | `MAX_OUTPUT_CHARS`、每轮的工具输出总量上限，超了落盘 |
| **局部性**：给结果配一点**多半会用到**的相邻上下文，而且**明说省掉了什么**、怎么取回 | 头尾各留一半，加一句"...（输出过长已截断，如需更多内容请缩小范围重试）" |
| **算总账**：省上下文只在它没换来更多次搜索、重读、犯错时才算省 | `Tool#selfBounded()`：读文件那类工具"落盘 → 再用它读回来"是**循环指令**，所以它整个不进总量限制 |

## 写工具定义（五条）

- **显而易见的约束别写。** 模型从调用结果就能学到的（"文件不存在会读失败"）写进 `description` 只是白占前缀。
- **写行为，不写实现。** 说它做什么、返回什么；内部机制、被拦下时走哪条分支、输出里有哪些标记，都不写。
- **参数的规矩写在参数上。** 默认值、范围、配对、什么时候该传，放**那个参数**的 description / schema 里 ——
  一条会随配置变化的说明，通常说明它本来就该是个参数。
- **一件事只说一遍。** 不在系统提示词里重述工具定义、不在参数描述里重述工具描述、不在一个工具里说另一个工具的规矩。
- **改了要量。** 前后比首轮输入 token —— 我们现成有真值（`TurnTokensUsed` 的 `inputTokens`），别拿估算当证据。
