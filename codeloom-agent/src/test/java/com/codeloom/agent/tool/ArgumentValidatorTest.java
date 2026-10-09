package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArgumentValidatorTest {

    /** 一份把本类支持的全部类型都覆盖到的 schema。 */
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path":    {"type": "string", "description": "随便写点什么"},
                "count":   {"type": "integer"},
                "ratio":   {"type": "number"},
                "force":   {"type": "boolean"},
                "command": {"type": "array", "items": {"type": "string"}}
              },
              "required": ["path"]
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ArgumentValidator validator() {
        return ArgumentValidator.of(SCHEMA);
    }

    private static JsonNode args(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("编译 schema")
    class Compile {

        @Test
        @DisplayName("合法 schema 编译得过")
        void acceptsWellFormedSchema() {
            assertThat(validator()).isNotNull();
        }

        @Test
        @DisplayName("schema 不是合法 JSON —— 这是写代码时的错，抛异常而不是返回失败")
        void rejectsMalformedJson() {
            assertThatThrownBy(() -> ArgumentValidator.of("{ 这不是 JSON "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是合法 JSON");
        }

        @Test
        @DisplayName("schema 不是对象")
        void rejectsNonObjectSchema() {
            assertThatThrownBy(() -> ArgumentValidator.of("[1,2,3]"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("必须是 JSON 对象");
        }

        @Test
        @DisplayName("属性漏写 type 要当场炸 —— 否则就是静默不校验，那正是这个类要消灭的东西")
        void rejectsPropertyWithoutType() {
            assertThatThrownBy(() -> ArgumentValidator.of("""
                    {"type":"object","properties":{"path":{"description":"忘了写 type"}}}"""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("`path`")
                    .hasMessageContaining("没写 type");
        }

        /**
         * 规矩是"认识的关键字要执行，不认识的要在**启动时**拒绝"。
         *
         * <p>{@code todo_write} 的 schema 里曾写着 {@code enum}，而这一层**从头到尾
         * 没读过它** —— 等于向模型声明了一条平台根本不执行的约束。那比不声明更糟：
         * 模型以为自己受着一条保护。
         */
        @Test
        @DisplayName("不认识的关键字当场炸 —— 接受了却不执行，比不声明更糟")
        void rejectsKeywordsWeCannotEnforce() {
            assertThatThrownBy(() -> ArgumentValidator.of("""
                    {"type":"object","properties":{"path":{"type":"string","pattern":"^a.*$"}}}"""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pattern")
                    .hasMessageContaining("没人执行");
        }

        @Test
        @DisplayName("type 拼错也当场炸 —— 从前它被静默放过，那个参数就永远没人查")
        void rejectsUnknownTypeName() {
            assertThatThrownBy(() -> ArgumentValidator.of("""
                    {"type":"object","properties":{"path":{"type":"strng"}}}"""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strng")
                    .hasMessageContaining("不认识");
        }

        @Test
        @DisplayName("enum 只收字符串的取值 —— 数字枚举的相等边界没人写过测试，那就不如不收")
        void rejectsNonStringEnum() {
            assertThatThrownBy(() -> ArgumentValidator.of("""
                    {"type":"object","properties":{"n":{"type":"integer","enum":[1,2]}}}"""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("enum");
        }

        @Test
        @DisplayName("注释性的关键字放行 —— 它们不约束任何东西，只是给人看的")
        void ignoredKeywordsAreFine() {
            assertThat(ArgumentValidator.of("""
                    {"type":"object","properties":{"path":{"type":"string",
                     "description":"读哪个文件","default":"a.txt"}}}""")).isNotNull();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("校验参数")
    class Validate {

        @Test
        @DisplayName("合法参数通过")
        void acceptsValidArguments() {
            assertThat(validator().validate(args("""
                    {"path":"src/Foo.java","count":3,"force":true}"""))).isEmpty();
        }

        @Test
        @DisplayName("缺必填参数 —— 错误里要点名是哪一个，模型才知道补什么")
        void reportsMissingRequired() {
            Optional<String> problem = validator().validate(args("{}"));

            assertThat(problem).isPresent();
            assertThat(problem.get()).contains("缺少必填参数").contains("`path`");
        }

        @Test
        @DisplayName("必填参数传了 null，和没传一样处理")
        void treatsExplicitNullAsMissing() {
            assertThat(validator().validate(args("""
                    {"path":null}""")).get()).contains("缺少必填参数");
        }

        @Test
        @DisplayName("可选参数没给就跳过，不报错")
        void ignoresAbsentOptional() {
            assertThat(validator().validate(args("""
                    {"path":"a.txt"}"""))).isEmpty();
        }

        @Test
        @DisplayName("类型不对 —— 错误里要同时说清【期望什么】和【实际是什么】")
        void reportsWrongType() {
            Optional<String> problem = validator().validate(args("""
                    {"path":123}"""));

            assertThat(problem).isPresent();
            assertThat(problem.get()).contains("`path`").contains("string").contains("number");
        }

        @Test
        @DisplayName("整数位收整数，小数不收")
        void integerRejectsFraction() {
            assertThat(validator().validate(args("""
                    {"path":"a","count":2}"""))).isEmpty();
            assertThat(validator().validate(args("""
                    {"path":"a","count":2.5}""")).get()).contains("`count`").contains("integer");
        }

        @Test
        @DisplayName("number 位小数和整数都收")
        void numberAcceptsBoth() {
            assertThat(validator().validate(args("""
                    {"path":"a","ratio":1.5}"""))).isEmpty();
            assertThat(validator().validate(args("""
                    {"path":"a","ratio":2}"""))).isEmpty();
        }

        @Test
        @DisplayName("数组元素类型不对时，要说清是【第几项】—— 只说「类型不对」等于没说")
        void reportsArrayItemPosition() {
            Optional<String> problem = validator().validate(args("""
                    {"path":"a","command":["mvn","-q",42]}"""));

            assertThat(problem).isPresent();
            assertThat(problem.get()).contains("`command`").contains("第 3 项").contains("string");
        }

        @Test
        @DisplayName("整个数组类型就不对")
        void reportsArrayItself() {
            assertThat(validator().validate(args("""
                    {"path":"a","command":"mvn -q test"}""")).get())
                    .contains("`command`").contains("array").contains("string");
        }

        @Test
        @DisplayName("多传了没声明的参数不拦 —— 模型偶尔附送一个解释性字段，为它报错只会平添一轮")
        void ignoresUndeclaredArguments() {
            assertThat(validator().validate(args("""
                    {"path":"a","_comment":"顺手写一句" }"""))).isEmpty();
        }

        @Test
        @DisplayName("参数整个不是 JSON 对象")
        void rejectsNonObjectArguments() {
            assertThat(validator().validate(args("\"我不是对象\"")).orElseThrow())
                    .contains("必须是一个 JSON 对象");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("与 ToolRegistry 集成")
    class WithRegistry {

        @Test
        @DisplayName("standard() 能构造出来 —— 这意味着每个工具的 schema 都编译得过")
        void standardToolSetCompiles() {
            assertThat(ToolRegistry.standard().names()).isNotEmpty();
        }

        @Test
        @DisplayName("校验走的是【那个工具自己的】schema")
        void validatesAgainstThatToolsSchema() {
            ToolRegistry registry = ToolRegistry.standard();

            // run_command 的 command 是必填的字符串
            assertThat(registry.validateArguments("run_command", args("{}")).orElseThrow())
                    .contains("`command`");
            assertThat(registry.validateArguments("run_command", args("""
                    {"command":"mvn -q test"}"""))).isEmpty();
        }

        @Test
        @DisplayName("认不出的工具名不在这里报 —— 那是另一个问题，由调用方自己说")
        void unknownToolIsNotThisLayersBusiness() {
            assertThat(ToolRegistry.standard().validateArguments("不存在的工具", args("{}")))
                    .isEmpty();
        }

        /**
         * 这一条和下面那条一起，守的是**同一份约束只写一遍**：{@code todo_write} 的
         * {@code state} enum 只在 schema 里写一次，工具不再手抄一遍，校验器照着 schema 执行。
         */
        @Test
        @DisplayName("【数组里的对象】某一项缺字段时，点出是第几项、缺哪个")
        void nestedObjectFieldsAreChecked() {
            ToolRegistry registry = ToolRegistry.standard();

            String problem = registry.validateArguments("todo_write", args("""
                    {"todos":[{"content":"跑测试","state":"pending"},
                              {"state":"pending"}]}""")).orElseThrow();

            assertThat(problem)
                    .contains("todos")
                    .contains("第 2 项")
                    .contains("content");
        }

        @Test
        @DisplayName("【enum】收下了就得执行：state 只能是 schema 里声明的那三个")
        void enumInTheSchemaIsActuallyEnforced() {
            ToolRegistry registry = ToolRegistry.standard();

            // 合法值一个都不能误伤
            assertThat(registry.validateArguments("todo_write", args("""
                    {"todos":[{"content":"跑测试","state":"in_progress"}]}"""))).isEmpty();

            // 不在声明里的值要**挡下来**，而且说清合法值是哪几个（声明了就得执行）
            String problem = registry.validateArguments("todo_write", args("""
                    {"todos":[{"content":"跑测试","state":"doing"}]}""")).orElseThrow();

            assertThat(problem)
                    .contains("第 1 项")
                    .contains("doing")
                    .contains("pending")
                    .contains("in_progress")
                    .contains("completed");
        }

        @Test
        @DisplayName("todos 不是数组：按 schema 报类型不对，不用工具自己再写一句")
        void wrongTypeAtTheTopIsReportedByTheSchema() {
            String problem = ToolRegistry.standard()
                    .validateArguments("todo_write", args("""
                            {"todos":"跑测试"}""")).orElseThrow();

            assertThat(problem).contains("`todos`").contains("array").contains("string");
        }
    }
}
