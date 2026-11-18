package dev.kizuna.inventoryui.status;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 单份分类文件的严格字段校验，不读取资源管理器。 */
final class StatusGroupParser {
    private StatusGroupParser() {}

    static File parse(String groupId, String content) {
        // 任一已声明字段不合法都拒绝整份文件，允许省略成员段或定义段之一。
        StatusIds.validate(groupId, "类 id");
        try {
            var root = object(JsonParser.parseString(content), "文件根节点");
            var effectsValue = root.get("effects");
            var groupValue = root.get("group");
            if (effectsValue == null && groupValue == null) {
                throw new IllegalArgumentException("effects 和 group 不能都省略");
            }
            var effects = effects(effectsValue);
            StatusGroup group = null;
            if (groupValue != null) {
                group = group(groupId, object(groupValue, "group"));
            }
            return new File(effects, group);
        } catch (JsonParseException failure) {
            throw new IllegalArgumentException("JSON 解析失败：" + failure.getMessage(), failure);
        }
    }

    private static List<String> effects(JsonElement value) {
        // 成员 ID 只接受字符串，避免 JSON 数字和布尔值被隐式转成身份。
        if (value == null) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("effects 必须是数组");
        }
        var effects = new ArrayList<String>();
        for (var member : value.getAsJsonArray()) {
            String id = text(member, "effects 成员");
            StatusIds.validate(id, "效果 id");
            effects.add(id);
        }
        return List.copyOf(effects);
    }

    private static StatusGroup group(String id, JsonObject definition) {
        // 定义必须完整，缺少字段不能让显示顺序被默认成零。
        return new StatusGroup(
                id,
                text(definition.get("name"), "group.name"),
                text(definition.get("icon"), "group.icon"),
                integer(definition.get("merge_order"), "group.merge_order"),
                integer(definition.get("display_order"), "group.display_order"));
    }

    private static JsonObject object(JsonElement value, String field) {
        // null、数组和标量都不能充当结构化段落。
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(field + " 必须是对象");
        }
        return value.getAsJsonObject();
    }

    private static String text(JsonElement value, String field) {
        // 不使用 Gson 的标量强制转换，资源文件写错类型必须留下诊断。
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
        return value.getAsString();
    }

    private static int integer(JsonElement value, String field) {
        // 只接受 int 范围内的整数，不截断小数，也不把数字字符串当成顺序。
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(field + " 必须是 int 范围内的整数", failure);
        }
    }

    record File(List<String> effects, StatusGroup group) {}
}
