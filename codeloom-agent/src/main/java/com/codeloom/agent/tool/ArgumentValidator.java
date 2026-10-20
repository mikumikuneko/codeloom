package com.codeloom.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按工具声明的 JSON Schema 校验模型给的参数。模型参数是不可信输入，直接用
 * {@code asText("")} / {@code asInt(默认值)} 会静默兜底，产生模型无从自修的错误。
 *
 * <p>校验失败返回一句给模型看的说明，不抛异常。只实现 {@code type}、{@code properties}、
 * {@code required}、{@code items}、{@code enum}、{@code additionalProperties}（仅 false）；
 * 白名单外的关键字和未知 type 在编译时拒绝，避免声明了却不执行的约束。
 *
 * <p>schema 在 {@code ToolRegistry} 构造时编译一次，写错的 schema 在启动时失败。
 */
public final class ArgumentValidator {


    /**
     * 认识的关键字：前六项是执行的约束，后四项只是注释。它们必须同表，
     * 否则会出现“在白名单里却没人执行”的约束。
     */
    private static final Set<String> KNOWN_KEYWORDS = Set.of(
            "type", "properties", "required", "items", "enum", "additionalProperties",
            "description", "title", "default", "examples");

    /** 编好的 schema；递归以支持嵌套参数。 */
    private sealed interface Spec {
    }

    /**
     * 标量叶子。
     *
     * @param type    标量类型名
     * @param allowed 非空时取值必须属于其中（{@code enum}）
     */
    private record Leaf(String type, Set<String> allowed) implements Spec {
    }

    /** 数组：每项满足 {@code element}；未声明 {@code items} 时 element 为 null，不查元素。 */
    private record ListOf(Spec element) implements Spec {
    }

    /**
     * 对象：校验声明的属性、必填项，以及 closed 时是否有多余属性。
     * closed 来自 {@code "additionalProperties": false}；否则多余参数会被静默忽略。
     */
    private record Fields(Map<String, Spec> properties, List<String> required, boolean closed)
            implements Spec {
    }

    private final Fields root;

    private ArgumentValidator(Fields root) {
        this.root = root;
    }

    /**
     * 编译一份 schema。
     *
     * @param schemaJson schema 文本
     * @return 编译器
     * @throws IllegalArgumentException schema 非法、根不是对象、属性缺 type，或含未知关键字；
     *                                  这些是代码错误，不是运行时输入问题
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
     * schema 是否声明了该属性。供 {@code ToolRegistry} 在启动时核对工具声明的主语键。
     *
     * @param property 属性名
     * @return 声明了该属性时为 true
     */
    boolean declares(String property) {
        return root.properties().containsKey(property);
    }

    /**
     * @param arguments 模型给的参数（已解析的 JSON）
     * @return 空 = 通过；有值 = 给模型看的可自修说明
     */
    public Optional<String> validate(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return Optional.of("参数必须是一个 JSON 对象，收到的是 " + describe(arguments) + "。");
        }
        // 根上的 where 为空串，报错措辞与嵌套位置不同（见 missingRequired）。
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

    /** 白名单外的关键字一律拒绝，避免声明了却不执行的约束。 */
    private static void rejectUnknownKeywords(JsonNode spec, String where) {
        for (Iterator<String> it = spec.fieldNames(); it.hasNext(); ) {
            String keyword = it.next();
            if (!KNOWN_KEYWORDS.contains(keyword)) {
                throw new IllegalArgumentException(where + " 里有我们不认识的关键字 `" + keyword + "`。"
                        + "我们只执行 type / properties / required / items / enum / additionalProperties。"
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
        return new Fields(properties, required, additionalPropertiesIsClosed(spec, where));
    }

    /** 读 {@code additionalProperties}，只接受布尔值；schema 形式未实现，收下不执行比不收更糟。 */
    private static boolean additionalPropertiesIsClosed(JsonNode spec, String where) {
        JsonNode value = spec.path("additionalProperties");
        if (value.isMissingNode()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(where + " 的 additionalProperties 只支持 true / false，"
                    + "收到的是：" + value + "。");
        }
        return !value.asBoolean();
    }

    /** 读 {@code enum}，只支持字符串取值；数字枚举的相等边界尚无测试，因此不实现。 */
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
     * @param spec  编译后的 schema
     * @param value 待校验值
     * @param where 出错位置；根为空串，嵌套时由外层拼好
     * @return 校验失败说明；通过时为空
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
            // 必须列出合法值，否则模型只能再猜一轮。
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
            // 必须带上项号，只说“某项不对”等于没说。
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
                // 显式 null 与未传对工具没有区别，合并报错。
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
        if (fields.closed()) {
            for (Iterator<String> it = value.fieldNames(); it.hasNext(); ) {
                String name = it.next();
                if (!fields.properties().containsKey(name)) {
                    return Optional.of(unknownParameter(where, name, fields.properties().keySet()));
                }
            }
        }
        return Optional.empty();
    }

    /** 报出多余参数时同时列出可用参数，让模型一次改对。 */
    private static String unknownParameter(String where, String name, Set<String> allowed) {
        String list = allowed.isEmpty() ? "什么参数都不接受" : "只接受：" + String.join("、", allowed);
        return where.isEmpty()
                ? "多了不认识的参数 `" + name + "`。这个工具" + list + "。"
                : where + "里多了不认识的字段 `" + name + "`；它" + list + "。";
    }

    /** 缺少必填项的说明；根上指调用参数，嵌套里指某一项内部字段。 */
    private static String missingRequired(String where, String name) {
        return where.isEmpty()
                ? "缺少必填参数 `" + name + "`。"
                : where + "里缺少必填的 `" + name + "`。";
    }

    /** 类型错误的说明同时包含期望类型和实际类型。 */
    private static String typeProblem(String where, String expected, JsonNode value) {
        return where + " 的类型应为 " + expected + "，实际是 " + describe(value) + "。";
    }

    private static boolean matches(String expected, JsonNode value) {
        return switch (expected) {
            case "string" -> value.isTextual();
            // 1.5 不应通过 integer 校验。
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    /** 把 JSON 节点转成给模型看的类型名；只用于报错。 */
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
            return ToolJson.MAPPER.readTree(schemaJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具的 parametersJsonSchema 不是合法 JSON：" + schemaJson, e);
        }
    }
}
