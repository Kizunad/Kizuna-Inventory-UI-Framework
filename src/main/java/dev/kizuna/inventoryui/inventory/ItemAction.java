package dev.kizuna.inventoryui.inventory;

import java.util.Objects;

/** 子模块提供的上下文操作；框架只展示并执行回调，不解释操作 ID。 */
public record ItemAction(
        String id, String label, boolean enabled, String disabledReason, Runnable execute) {
    public ItemAction {
        // 禁用原因参与提示，操作身份独立于本地化文案；实际权限仍须在执行端验证。
        if (Objects.requireNonNull(id).isBlank() || Objects.requireNonNull(label).isBlank()) {
            throw new IllegalArgumentException("action id and label must not be blank");
        }
        disabledReason = Objects.requireNonNullElse(disabledReason, "");
        Objects.requireNonNull(execute);
    }
}
