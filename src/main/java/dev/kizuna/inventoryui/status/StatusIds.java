package dev.kizuna.inventoryui.status;

import java.util.regex.Pattern;

/** 与资源位置相同的字符约束，但不依赖游戏类或隐式命名空间。 */
final class StatusIds {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/_.-]+");

    private StatusIds() {}

    static void validate(String id, String field) {
        // 身份必须显式包含命名空间；坏声明不能被悄悄补成另一个资源位置。
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(field + " 必须是命名空间:路径：" + id);
        }
    }
}
