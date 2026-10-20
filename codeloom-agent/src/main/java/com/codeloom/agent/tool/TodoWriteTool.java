package com.codeloom.agent.tool;

import com.codeloom.domain.event.TodoListUpdated;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 写任务清单，整份替换。
 *
 * <p>它不碰工作区，只返回一条 {@link TodoListUpdated} 领域事实，由调用方落成事件；
 * 写成事件而不是工具结果，是为了让清单在上下文压缩后仍然存在并每轮重新投影。
 * 整份替换让模型无需计算 diff，也天然幂等。
 */
public final class TodoWriteTool implements Tool {

    @Override
    public String name() {
        return ToolNames.TODO_WRITE;
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
        // 没有主语：参数是整份清单，不是某一样东西。
        return ToolSurface.of(ToolSurface.Shape.PLAN, "计划", null);
    }

    @Override
    public ToolOutcome execute(ToolContext context, JsonNode arguments) {
        // 参数形状由 schema 与 ArgumentValidator 统一校验，这里不重复实现。
        JsonNode todos = arguments.path("todos");
        if (!todos.isArray()) {
            // 说明校验层被绕过，属于调用方错误，应当显式失败。
            throw new IllegalStateException("todo_write 的前提不成立：todos 不是数组。"
                    + "这一条由 ArgumentValidator 按 schema 保证 —— 到不了这里才对。");
        }

        List<TodoListUpdated.Item> items = new ArrayList<>();
        for (int index = 0; index < todos.size(); index++) {
            JsonNode node = todos.get(index);
            String content = node.path("content").asText("");
            if (content.isBlank()) {
                // schema 词汇表无法表达“非空白”，这是工具自身的语义约束。
                return ToolOutcome.failed("第 " + (index + 1) + " 条的 content 是空的。"
                        + "每一步写一句话，说清要做什么。");
            }
            // state 取值由 schema enum 保证，这里只做领域状态映射。
            items.add(new TodoListUpdated.Item(content, stateOf(node.path("state").asText(""))));
        }

        TodoListUpdated updated = new TodoListUpdated(items);
        // 只回确认和计数；清单会立即由投影注入，不再抄一遍。
        return ToolOutcome.produced(updated, confirmation(items));
    }

    private static TodoListUpdated.State stateOf(String raw) {
        return switch (raw) {
            case "pending" -> TodoListUpdated.State.PENDING;
            case "in_progress" -> TodoListUpdated.State.IN_PROGRESS;
            case "completed" -> TodoListUpdated.State.COMPLETED;
            // schema 与领域状态不一致属于代码错误，不能静默吞掉。
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
