package dev.kizuna.inventoryui.status;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

final class StatusTestData {
    private StatusTestData() {}

    static StatusEffectRef effect(String path) {
        return new StatusEffectRef("example:" + path, StatusCategory.NEUTRAL);
    }

    static String file(int mergeOrder, int displayOrder, List<String> effects) {
        // 样例文件使用完整定义，让各用例只改变关注的顺序和成员。
        var members = new JsonArray();
        effects.forEach(members::add);
        var group = new JsonObject();
        group.addProperty("name", "status_group.example.group");
        group.addProperty("icon", "example:status_group/group");
        group.addProperty("merge_order", mergeOrder);
        group.addProperty("display_order", displayOrder);
        var root = new JsonObject();
        root.add("effects", members);
        root.add("group", group);
        return root.toString();
    }
}
