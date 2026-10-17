---
name: codeloom-commit
description: Use when writing, rewriting or reviewing a git commit message in the codeloom repo — before committing, when picking the type and scope, when writing the subject and body, or when reformatting an existing history (amend, rebase, force-push). Not code comments (see codeloom-comment-standard)
---

# codeloom 的提交判据

提交信息是给以后翻历史的人、给 `git log`、也给 GitHub 的提交页看的。
这边管它的形状和内容：首行用 Conventional Commits，正文讲**为什么**和**改了什么**，
并且**是纯文本，不是 markdown** —— 三处渲染规则不一样，修饰符在至少一处会露出原文。

## 范围

适用：`git commit` 的提交信息本身 —— 首行与正文；重写既有提交（amend、rebase、强推）时的
消息也按这份。

不适用：代码注释（见 `codeloom-comment-standard`）、给模型看的字（见 `codeloom-prompt-standard`）、
界面文案（见 `codeloom-ui-standard`）。Git 的分支与合并策略也不在这份里。

## 首行

`<type>(<scope>): <主题>`

- type 小写英文，主题用中文；scope 能点名范围就写，代表不了整个提交就省略。
- 主题不超过 50 字符，说"这个提交做什么"（祈使语气），句尾不加句号。
- 主题要说清**改了哪件事**。"注释修正""修个 bug"不算，`docs: 收口重复讲述且互相打架的注释`才算。

| type | 用在哪 |
|---|---|
| feat | 用户或调用方能感知的新能力 |
| fix | 行为与契约、文档或预期不符 |
| docs | 只动注释或文档 |
| refactor | 结构变了、行为没变 |
| perf | 只为性能 |
| test | 只动测试 |
| chore | 构建、依赖、工具配置 |
| build / ci / revert | 同名用途 |

这个仓里常见的 scope：`domain` / `workspace` / `agent` / `realtime` / `app` / `tool` / `sse` / `frontend`。

## 正文

固定骨架，段与段之间各空一行：

1. 一段散文：**为什么**改 —— 出了什么问题、违反了哪条契约、为什么选这条路。
2. `- ` 开头的一组条目：**具体改了什么**，一条一件事。
3. 可选收尾一段：实测结果，或指向 `docs/decisions/` 里的决策记录。

```
fix(sse): 超时由应用自己收尾

emitter 的超时回调原来只关订阅，没管这次请求本身。于是超时之后落到框架那条路上：
由框架给结果设一个 AsyncRequestTimeoutException，再走错误分派去渲染错误体……

- 回调里补一次 complete()，它是"由应用把这次请求正常结束掉"的那一下。
- 写失败那条路不跟着改：send 出错之后不该再调 complete()，那里只关订阅。

实测（同一个浏览器动作 A/B）：改前 2 次 AsyncRequestTimeoutException，改后 0 次。
```

正文**不写跑没跑检查**（"tsc 0 错""75/75 通过"是流水账，提交讲的是改了什么、为什么）。
"这次新增了 75 条测试"是改动的一部分，可以写；"我跑了一遍全过"不写。

**理由和取舍不能省。** 这个项目的提交正文是文档的一部分：为什么没选另一条路、踩到了什么，
都写在这儿；写得清的，指向对应的决策记录。

## 正文是纯文本，不带 markdown

（这条管的是**提交正文**；这份判据自己仍按 markdown 写。）

禁用：`**加粗**`、反引号、`#` 标题、`>` 引用、表格、代码围栏、链接、`{@code}` / `{@link}`。

- 要强调就靠句子本身，不加粗。
- 类名、方法名、配置项**直接写**，不套反引号、不套 `{@code}`。
- 列表只用 `- `，续行缩进两格。

## 一条提交只讲一件事

不相关的改动不塞进同一条。同一主题下的小"顺带"改动可以一起提交，但**正文里要点名**。
主题有主次时，type 取**更重要的那一件**（对使用者影响大的优先于纯结构调整），其余在正文列出。

## 提交与改写

- 中文一律写进文件，用 `git commit -F <文件>` —— 控制台是 GBK，中文走命令行参数会乱。
- 推之前把首行和正文给用户看，等他点头。
- 重写既有提交（改消息、合并）之后核三样 —— 每条 tree、作者与提交者、两个时间戳 —— 改消息不该改内容：

  ```bash
  git log --format='%T %an %ae %at %cn %ce %ct' <旧头> > /tmp/a
  git log --format='%T %an %ae %at %cn %ce %ct' <新头> > /tmp/b
  diff /tmp/a /tmp/b    # 空 = 三样都没动
  ```

  核对完用 `git push --force-with-lease`，并在推后用 `git ls-remote origin <分支>` 独立核一遍远端。

## 判例

**主题说清是哪类修正**：原首行「注释修正」→ `docs: 收口重复讲述且互相打架的注释`。
前者换个提交也能用，后者一眼能看出改的是哪一类问题。

**散文正文改成骨架**：把"另外修掉了 A；回滚之后 B；顺带 C"这种堆在一段里的叙述，
拆成一段说明为什么、再把 A/B/C 各自成条 —— 一条一件事，读的人才能挑着看。

**混合提交取主**：一条提交里既有新功能、又有间距和默认值的收口，type 记 `feat`，
收口那几件在正文条目里点名，不另立首行。

判例多了就另开 `references/cases.md`，这一节只留指针 —— SKILL.md 保持短。
