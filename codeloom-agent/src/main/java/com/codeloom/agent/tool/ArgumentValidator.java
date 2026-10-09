package com.codeloom.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按工具自己声明的 JSON Schema 校验模型给的参数。
 *
 * <h2>为什么必须有这一层</h2>
 * 模型给的参数是**不可信输入**，而各工具原本用
 * {@code arguments.path("x").asText("")} / {@code .asInt(默认值)} 取值 ——
 * 这两种写法遇到类型不对或漏传时会**静默兜底**：{@code {"path": 123}} 会变成
 * {@code "123"}，漏传会变成空串。结果是工具报出「文件不存在: 」这种莫名其妙的话，
 * 模型无从下手，白白烧掉一轮去猜自己哪里错了。
 *
 * <p>这里把「你给错了」直接说成一句人话喂回去。它**不是异常**，而是一次
 * {@link ToolOutcome#failed} —— 和别的工具失败一样，模型读一眼就能自己改。
 *
 * <h2>只认我们用到的那一小撮，但**认了就得执行**</h2>
 * 支持 {@code type}、{@code properties}、{@code required}、{@code items}、{@code enum}。
 * 另外四个（{@code description} / {@code title} / {@code default} / {@code examples}）
 * 是**注释**，不是约束 —— 它们在白名单里，但读了也不用执行。
 *
 * <p><b>白名单之外的关键字一律在编译时拒绝</b>（启动时炸，见下）。
 * 这条规矩是从 deepseek-harness 那儿学来的 —— 它的原则是：**不认识的关键字要拒绝，
 * 而不是接受了却不执行**。我们曾经在这上面栽过：{@code todo_write} 的 schema 声明了
 * {@code enum} 约束，而这一层从头到尾没读过它 —— 于是一条约束**声明给了模型、
 * 却没人执行**，只能靠工具自己再手写一遍，两份迟早漂移。
 *
 * <h2>什么时候构造</h2>
 * 在 {@code ToolRegistry} 构造时把每个工具的 schema 编译一次，之后每次调用只做校验。
 * 于是「schema 写坏了」会在**启动时**炸，而不是等模型第一次调用它才 400。
 * 认不出的 type 名也走这条路：静默放过它和把 {@code string} 拼错成 {@code strng}
 * 在代码上是同一个形状 —— 代价都是那个参数永远没人查。宁可启动时炸一声。
 */
public final class ArgumentValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 我们认识的关键字。
     *
     * <p>分两类，但它们必须待在**同一张表**里：前半截是要执行的约束，
     * 后四个只是注释。"我们认识的"和"我们会执行的"一旦分成两张表，
     * 就会出现"在白名单里、却没有代码去读它"这种状态 —— 那正是这个类要消灭的东西。
     */
    private static final Set<String> KNOWN_KEYWORDS = Set.of(
            "type", "properties", "required", "items", "enum",
            "description", "title", "default", "examples");

    /** 一份编好的 schema。递归，因为参数可以嵌套（清单就是对象数组）。 */
    private sealed interface Spec {
    }

    /**
     * 叶子：一个标量类型，外加可选的取值范围。
     *
     * @param allowed 非空时，取值必须落在这里面（{@code enum}）
     */
    private record Leaf(String type, Set<String> allowed) implements Spec {
    }

    /** 数组：每一项都要满足 {@code element}；没声明 {@code items} 就是 null，不查元素。 */
    private record ListOf(Spec element) implements Spec {
    }

    /** 对象：只查它自己声明的那些属性，以及它们当中哪些是必填的。 */
    private record Fields(Map<String, Spec> properties, List<String> required) implements Spec {
    }

    private final Fields root;

    private ArgumentValidator(Fields root) {
        this.root = root;
    }

    /**
     * 编译一份 schema。
     *
     * @throws IllegalArgumentException schema 不是合法 JSON、不是对象、有属性没写 type、
     *                                  或者出现了我们不认识的关键字。
     *                                  **这些都是写代码时的错，不是运行时输入的问题**，
     *                                  所以抛异常而不是返回失败
     */
    public static ArgumentValidator of(String schemaJson) {
        JsonNode schema = parse(schemaJson);
        if (!schema.isObject()) {
            throw new IllegalArgumentException("工具的 parametersJsonSchema 必须是 JSON 对象：" + schemaJson);
        }
        if (!(compile(schema, "参数 schema") instanceof Fields fields)) {
            throw new IllegalArgumentException("工具的 parametersJsonSchema 根节点必须是 object：" + schemaJson);
        }
        return new ArgumentValidator(fields);
    }

    /**
     * 这份 schema 里有没有声明这个属性。
     *
     * <p>给 {@code ToolRegistry} 做启动时的交叉检查用：工具的 {@code surface()} 会声明
     * "我的主语是参数里的 {@code path}"，而那句话和 schema 是两个地方写的（一处是代码、
     * 一处是 JSON 文本），编译器管不到。对不上时症状很隐蔽 —— 界面那一条只显示工具名，
     * 审批路径取不到命令。在这里对一遍，是唯一能在启动时发现它的地方。
     */
    boolean declares(String property) {
        return root.properties().containsKey(property);
    }

    /**
     * @param arguments 模型给的参数（已经从调用里解析出来的 JSON）
     * @return 空 = 通过；有值 = 一句给模型看的、可自修的说明
     */
    public Optional<String> validate(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return Optional.of("参数必须是一个 JSON 对象，收到的是 " + describe(arguments) + "。");
        }
        // 根上的 where 是空串 —— 报错的措辞在根上和嵌套里不一样，见 missingRequired
        return validateFields(root, arguments, "");
    }

    // ------------------------------------------------------------------
    // 编译
    // ------------------------------------------------------------------

    private static Spec compile(JsonNode spec, String where) {
        if (!spec.isObject()) {
            throw new IllegalArgumentException(where + " 必须是一个 JSON 对象，收到的是：" + spec);
        }
        rejectUnknownKeywords(spec, where);

        String type = spec.path("type").asText("");
        if (type.isEmpty()) {
            throw new IllegalArgumentException(where + " 没写 type。"
                    + "要么补上，要么它根本不该出现在 properties 里 ——"
                    + "不写的话这一层就无从校验，而那正是它存在的理由。schema 片段：" + spec);
        }

        return switch (type) {
            case "object" -> compileFields(spec, where);
            case "array" -> new ListOf(spec.has("items") ? compile(spec.path("items"), where + " 的 items") : null);
            case "string", "integer", "number", "boolean" -> new Leaf(type, enumOf(spec, type, where));
            default -> throw new IllegalArgumentException(where + " 的 type 是 `" + type + "`，我们不认识。"
                    + "支持 object / array / string / integer / number / boolean ——"
                    + "认不出来而放过去的话，这个参数就永远没人查，而「把一个类型名拼错」"
                    + "和「写了一个我们真没实现的类型」在代码上是同一个形状。"
                    + "schema 片段：" + spec);
        };
    }

    /**
     * 白名单之外的关键字一律拒绝。
     *
     * <p>教训写在类注释里：{@code enum} 曾经就是被这么静默忽略的 ——
     * schema 对模型声明了一条约束，而平台从不执行它。
     */
    private static void rejectUnknownKeywords(JsonNode spec, String where) {
        for (Iterator<String> it = spec.fieldNames(); it.hasNext(); ) {
            String keyword = it.next();
            if (!KNOWN_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException(where + " 里有我们不认识的关键字 `" + keyword + "`。"
                        + "我们只执行 type / properties / required / items / enum。"
                        + "放过去等于**声明了一条没人执行的约束** —— 对模型来说那比不声明更糟，"
                        + "它会以为自己受着一条其实不存在的保护。schema 片段：" + spec);
            }
        }
    }

    private static Fields compileFields(JsonNode spec, String where) {
        Map<String, Spec> properties = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : spec.path("properties").properties()) {
            properties.put(entry.getKey(), compile(entry.getValue(), where + " 的属性 `" + entry.getKey() + "`"));
        }
        List<String> required = new ArrayList<>();
        for (JsonNode name : spec.path("required")) {
            required.add(name.asText());
        }
        return new Fields(properties, required);
    }

    /**
     * 读 {@code enum}。
     *
     * <p>只支持**字符串**的取值。不是偷懒：我们真用得到的那一处（清单里每一条的状态）
     * 就是字符串，而数字枚举要处理 {@code 2} 与 {@code 2.0} 这类相等的边界 ——
     * 现在没有人为那个写测试，那就不如不收。
     */
    private static Set<String> enumOf(JsonNode spec, String type, String where) {
        JsonNode values = spec.path("enum");
        if (values.isMissingNode()) {
            return Set.of();
        }
        if (!"string".equals(type)) {
            throw new IllegalArgumentException(where + " 上用了 enum，但它的 type 是 `" + type
                    + "`。我们只支持字符串的 enum。schema 片段：" + spec);
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException(where + " 的 enum 里出现了非字符串的取值：" + value);
            }
            allowed.add(value.asText());
        }
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException(where + " 的 enum 是空的 —— 那等于一个都不许传，"
                    + "而写它的人多半是想要「不限制」。schema 片段：" + spec);
        }
        return Set.copyOf(allowed);
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * @param where 出错时用的"在哪儿"，由外层拼好。**根上空串**，嵌套里是
     *              "参数 `todos` 的第 3 项"这种 —— 模型得先知道改哪里，才知道改什么
     */
    private static Optional<String> validate(Spec spec, JsonNode value, String where) {
        return switch (spec) {
            case Leaf leaf -> validateLeaf(leaf, value, where);
            case ListOf list -> validateList(list, value, where);
            case Fields fields -> validateFields(fields, value, where);
        };
    }

    private static Optional<String> validateLeaf(Leaf leaf, JsonNode value, String where) {
        if (!matches(leaf.type(), value)) {
            return Optional.of(typeProblem(where, leaf.type(), value));
        }
        if (!leaf.allowed().isEmpty() && !leaf.allowed().contains(value.asText())) {
            // 说清**合法值是哪几个**：只报"值不对"的话，模型只能再猜一轮
            return Optional.of(where + " 只接受 " + String.join(" / ", leaf.allowed())
                    + "，收到的是「" + value.asText() + "」。");
        }
        return Optional.empty();
    }

    private static Optional<String> validateList(ListOf list, JsonNode value, String where) {
        if (!value.isArray()) {
            return Optional.of(typeProblem(where, "array", value));
        }
        for (int i = 0; i < value.size(); i++) {
            if (list.element() == null) {
                continue;
            }
            // 带上**第几项**：数组有十几项时，只说"里面某项不对"等于没说
            Optional<String> problem = validate(list.element(), value.get(i),
                    where + " 的第 " + (i + 1) + " 项");
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateFields(Fields fields, JsonNode value, String where) {
        if (!value.isObject()) {
            return Optional.of(typeProblem(where, "object", value));
        }
        for (String name : fields.required()) {
            if (!value.hasNonNull(name)) {
                // 传了 null 也走这里：对工具来说「显式 null」和「没传」没区别，
                // 分开报只会让模型多猜一轮
                return Optional.of(missingRequired(where, name));
            }
        }
        for (Map.Entry<String, Spec> entry : fields.properties().entrySet()) {
            JsonNode fieldValue = value.get(entry.getKey());
            if (fieldValue == null || fieldValue.isNull()) {
                continue;
            }
            String fieldWhere = where.isEmpty()
                    ? "参数 `" + entry.getKey() + "`"
                    : where + "的 `" + entry.getKey() + "`";
            Optional<String> problem = validate(entry.getValue(), fieldValue, fieldWhere);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /**
     * 少了必填项的说法，**根上和嵌套里不一样**。
     *
     * <p>根上是"你少给了一个参数"，嵌套里是"这个东西少了一个字段"——
     * 对模型来说这是两件事，而它要改的地方也不一样（一个是调用本身，一个是那一项的内容）。
     */
    private static String missingRequired(String where, String name) {
        return where.isEmpty()
                ? "缺少必填参数 `" + name + "`。"
                : where + "里缺少必填的 `" + name + "`。";
    }

    /** 类型不对时把**期望什么**和**实际是什么**一起说清 —— 只说一半模型还得猜。 */
    private static String typeProblem(String where, String expected, JsonNode value) {
        return where + " 的类型应为 " + expected + "，实际是 " + describe(value) + "。";
    }

    private static boolean matches(String expected, JsonNode value) {
        return switch (expected) {
            case "string" -> value.isTextual();
            // 用 isIntegralNumber 而不是 isNumber：1.5 不该通过 integer 的校验
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    /** 把 JSON 节点说成给模型看的类型名。只在报错时用。 */
    private static String describe(JsonNode value) {
        if (value == null || value.isNull()) {
            return "空";
        }
        return switch (value.getNodeType()) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
            case NULL -> "空";
            default -> value.getNodeType().name().toLowerCase();
        };
    }

    private static JsonNode parse(String schemaJson) {
        try {
            return MAPPER.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具的 parametersJsonSchema 不是合法 JSON：" + schemaJson, e);
        }
    }
}
