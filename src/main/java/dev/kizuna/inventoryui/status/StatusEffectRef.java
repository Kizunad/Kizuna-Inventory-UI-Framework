package dev.kizuna.inventoryui.status;

import java.util.Objects;

/** 正在生效的效果身份与兜底属性，不持有效果实例、剩余时间或绘制状态。 */
public record StatusEffectRef(
        // 效果种类的稳定资源 ID，同一次排列中不得重复。
        String id,
        // 没有有效分类声明时使用的好坏属性。
        StatusCategory category) {
    public StatusEffectRef {
        // 在纯数据边界检查身份，后续计算不需要依赖游戏注册表。
        StatusIds.validate(id, "效果 id");
        Objects.requireNonNull(category, "category must not be null");
    }
}
