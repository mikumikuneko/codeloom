package com.codeloom.agent.tool;

import com.codeloom.domain.event.TodoListUpdated;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 写任务清单 —— **整份替换**。
 *
 * <h2>它和别的工具不一样：产出的是**一条事实**，不是一个文件</h2>
 * 它不碰工作区，所以 {@code mutated} 是 false；但它带回去一条 {@link TodoListUpdated}，
 * 由调用方落成事件（见 {@link ToolOutcome#todoUpdate()}）。工具自己不写事件流 ——
 * 那是知道会话是谁的那一层的事。
 *
 * <p>写成事件而不是"回一句工具结果"，是为了让它**活过上下文压缩**：工具结果会被清掉，
 * 而清单每轮都会重新出现在模型眼前（见 {@code ContextAssembler}）。
 *
 * <h2>参数为什么要"整份"</h2>
 * 让模型算 diff（"把第 2 条标完成"）比让它重写整份容易错，而整份重写天然幂等 ——
 * 重试一次还是那份清单，不会变成删两遍。Claude Code 和 deepseek-harness 都是这个形状。
 */
public final class TodoWriteTool implements Tool {

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return """
                在你打算干一件多步的活之前，先把步骤列成一份清单；之后每做完一步就更新它。\
                清单会被平台记住，每一轮都重新摆在你面前 —— 所以**上下文被压缩之后它还在**，\
                你不会忘了自己做到第几步。

                该用的时候：
                - 一件事要动三个以上的地方，或者要分好几步（改代码 → 跑测试 → 修报错）：先列出来再动手
                - 用户一次交给你好几件事：照着列成清单
                - 开始做某一步之前，把它标成 in_progress；**同时只标一个**
                - 做完一步立刻标 completed，然后照清单往下走

                不该用的时候：
                - 只有一件事、一句话就能答完的：直接做
                - 纯问答、查资料、解释代码：那些不需要清单

                全部做完时传一份空清单，把它清掉。

                验证不用写进清单 —— 平台会在你收尾时自己跑一遍验证，你不用为它单独排一步。""";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "todos": {
                      "type": "array",
                      "description": "完整的清单，会覆盖之前那份。传空数组 = 清空。",
                      "items": {
                        "type": "object",
                        "properties": {
                          "content": {
                            "type": "string",
                            "description": "这一步是什么，一句话，用祈使句（比如「改 OrderService 的并发处理」），不要写成「分析了一下…」"
                          },
                          "state": {
                            "type": "string",
                            "enum": ["pending", "in_progress", "completed"],
                            "description": "pending = 还没开始；in_progress = 正在做（同时只有一个）；completed = 做完了"
                          }
                        },
                        "required": ["content", "state"]
                      }
                    }
                  },
                  "required": ["todos"]
                }
                """;
    }

    @Override
    public ToolSurface surface() {
        // 没有主语：它的参数是一整个清单，不是"某一样东西"
        return ToolSurface.of(ToolSurface.Shape.PLAN, "计划", null);
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        // **这里不再自己查一遍参数的形状。**
        //
        // `todos` 是不是数组、每一项要有哪些字段、`state` 只能取哪几个值 ——
        // 三样都写在 {@link #parametersJsonSchema()} 里了，而 {@link ArgumentValidator}
        // 会在调到这里之前按那份 schema 查完，报出来的话术也是同一套
        //（"第 3 项的 `state` 只接受 pending / in_progress / completed…"）。
        //
        // 手写一遍就是同一份约束的第二份副本，而副本会漂移 —— 漂移的症状最难查：
        // **schema 告诉模型的值，工具其实不收**。
        JsonNode todos = arguments.path("todos");
        if (!todos.isArray()) {
            // 走到这里说明**上面那层没拦住**（或者有人绕过注册表直接调这个工具）。
            // 所以这句话不是写给模型看的 —— 模型给错参数是校验那一层的事，
            // 这是调用方的问题，得在开发时炸出来，不能悄悄当成"空清单"
            throw new IllegalStateException("todo_write 的前提不成立：todos 不是数组。"
                    + "这一条由 ArgumentValidator 按 schema 保证 —— 到不了这里才对。");
        }

        List<TodoListUpdated.Item> items = new ArrayList<>();
        for (int index = 0; index < todos.size(); index++) {
            JsonNode node = todos.get(index);
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                // **这一条留在工具里**，因为 schema 的词汇表表达不了它
                //（要表达"不能是空白"得上 minLength 或者 pattern，而我们不收那两个）。
                // 它是这个工具自己的语义，不是"参数长什么样"
                return ToolOutcome.failed("第 " + (index + 1) + " 条的 content 是空的。"
                        + "每一步写一句话，说清要做什么。");
            }
            // state 的取值由 schema 的 enum 保证（那一层查过才轮到这儿），
            // 这里只负责把它翻成领域里的三态
            items.add(new TodoListUpdated.Item(content, stateOf(node.path("state").asText(""))));
        }

        TodoListUpdated updated = new TodoListUpdated(items);
        // 回给模型的是**一句确认 + 数**，不是整份清单：清单马上就摆在它眼前了
        // （见 ContextAssembler 的注入），在这儿再抄一遍是白烧 token
        return ToolOutcome.produced(updated, confirmation(items));
    }

    private static TodoListUpdated.State stateOf(String raw) {
        return switch (raw) {
            case "pending" -> TodoListUpdated.State.PENDING;
            case "in_progress" -> TodoListUpdated.State.IN_PROGRESS;
            case "completed" -> TodoListUpdated.State.COMPLETED;
            // 取值那一边由参数 schema 的 enum 保证（ArgumentValidator 查过才轮到这儿），
            // 所以这个分支只可能在**两边对不上**时走到：schema 里加了第四个值，
            // 而这里没有对应的领域状态。那是写代码时的错，不该被静默吞掉 ——
            // ToolTest 里有一条测试专门盯着这两边别漂移
            default -> throw new IllegalArgumentException("不认识的状态：" + raw);
        };
    }

    private static String confirmation(List<TodoListUpdated.Item> items) {
        if (items.isEmpty()) {
            return "清单已清空。";
        }
        long pending = items.stream().filter(i -> i.state() == TodoListUpdated.State.PENDING).count();
        long doing = items.stream().filter(i -> i.state() == TodoListUpdated.State.IN_PROGRESS).count();
        long done = items.stream().filter(i -> i.state() == TodoListUpdated.State.COMPLETED).count();
        return "清单已更新：" + doing + " 进行中、" + pending + " 待处理、" + done + " 已完成。"
                + "继续照它往下做，每做完一步就更新一次。";
    }
}
