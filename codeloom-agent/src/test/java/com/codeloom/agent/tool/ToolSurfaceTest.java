package com.codeloom.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具声明的"样子"。
 *
 * <p>它守的是两件事：**声明和参数 schema 对得上**（这两处一个是代码、一个是 JSON 文本，
 * 编译器管不到），以及**每个工具都真的声明了**（漏声明不会报错，只会让界面显示得糙，
 * 所以它必须由测试来喊）。
 */
class ToolSurfaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("标准工具集：每个工具的形状和主语都是说好的那个")
    void standardSetDeclaresWhatWeExpect() {
        Map<String, ToolSurface> surfaces = ToolRegistry.standard().surfaces();

        assertThat(surfaces.keySet()).containsExactlyInAnyOrder(
                "read_file", "write_file", "edit_file", "grep", "glob", "run_command", "todo_write");

        assertThat(surfaces.get("read_file"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.READ, "读取", "path"));
        assertThat(surfaces.get("write_file"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.EDIT, "新建", "path"));
        assertThat(surfaces.get("edit_file"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.EDIT, "修改", "path"));
        assertThat(surfaces.get("grep"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.SEARCH, "搜索", "pattern"));
        assertThat(surfaces.get("glob"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.SEARCH, "查找", "pattern"));
        assertThat(surfaces.get("run_command"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.EXECUTE, "运行", "command"));
        assertThat(surfaces.get("todo_write"))
                .isEqualTo(ToolSurface.of(ToolSurface.Shape.PLAN, "计划", null));
    }

    @Test
    @DisplayName("没有工具还停留在默认的 PLAIN 上 —— 那样界面只会显示工具名")
    void nothingIsLeftUndeclared() {
        for (Map.Entry<String, ToolSurface> entry : ToolRegistry.standard().surfaces().entrySet()) {
            assertThat(entry.getValue().shape())
                    .as("工具 %s 没有声明形状", entry.getKey())
                    .isNotEqualTo(ToolSurface.Shape.OTHER);
            assertThat(entry.getValue().label())
                    .as("工具 %s 没有声明动作词", entry.getKey())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("声明的主语不在参数 schema 里 → 启动时就炸")
    void subjectMustExistInTheSchema() {
        // 参数里只有 path，声明却说主语是 file_path
        Tool liar = new ProbeTool("file_path");

        assertThatThrownBy(() -> new ToolRegistry(List.of(liar)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file_path");
    }

    @Test
    @DisplayName("对得上的照常注册 —— 上一条拦的是不一致，不是「声明了主语」这件事本身")
    void consistentDeclarationIsAccepted() {
        assertThat(new ToolRegistry(List.of(new ProbeTool("path"))).surfaces())
                .containsEntry("probe", ToolSurface.of(ToolSurface.Shape.READ, "读", "path"));
    }

    /** 一个只为这两条测试存在的工具：它的参数永远是 {@code path}，主语键由构造参数给。 */
    private record ProbeTool(String subjectKey) implements Tool {

        @Override
        public String name() {
            return "probe";
        }

        @Override
        public String description() {
            return "测试用";
        }

        @Override
        public String parametersJsonSchema() {
            return """
                    {
                      "type": "object",
                      "properties": { "path": { "type": "string" } },
                      "required": ["path"]
                    }
                    """;
        }

        @Override
        public ToolSurface surface() {
            return ToolSurface.of(ToolSurface.Shape.READ, "读", subjectKey);
        }

        @Override
        public ToolOutcome execute(ToolContext context, JsonNode arguments) {
            return ToolOutcome.ok("");
        }
    }

    @Test
    @DisplayName("取主语：参数里没有那一项、它不是字符串、或者它是空白，都算没有")
    void subjectInIsBoringlySafe() throws Exception {
        ToolSurface run = ToolRegistry.standard().surfaces().get("run_command");

        assertThat(run.subjectIn(args("{\"command\":\"mvn -q test\"}")))
                .contains("mvn -q test");
        assertThat(run.subjectIn(args("{}"))).isEmpty();
        assertThat(run.subjectIn(args("{\"command\":123}"))).isEmpty();
        assertThat(run.subjectIn(args("{\"command\":\"   \"}"))).isEmpty();
        assertThat(run.subjectIn(null)).isEmpty();
    }

    @Test
    @DisplayName("主语是不是路径：由形状推出来，读和改是，搜索和执行不是")
    void subjectIsPathFollowsTheShape() {
        assertThat(ToolSurface.of(ToolSurface.Shape.READ, "读", "path").subjectIsPath()).isTrue();
        assertThat(ToolSurface.of(ToolSurface.Shape.EDIT, "改", "path").subjectIsPath()).isTrue();
        assertThat(ToolSurface.of(ToolSurface.Shape.SEARCH, "搜", "pattern").subjectIsPath()).isFalse();
        assertThat(ToolSurface.of(ToolSurface.Shape.EXECUTE, "跑", "command").subjectIsPath()).isFalse();
        // 没声明主语时，形状再像路径也不算 —— "哪一个参数"都不知道，谈它是不是路径没有意义
        assertThat(ToolSurface.of(ToolSurface.Shape.READ, "读", null).subjectIsPath()).isFalse();
    }

    private static JsonNode args(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
