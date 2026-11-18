package dev.kizuna.inventoryui.status;

/** 类的身份与展示元数据；两个顺序都是无单位的优先级，数值越小越靠前。 */
public record StatusGroup(
        // 来自分类文件位置的稳定身份。
        String id,
        // 类名称的翻译键，不是已翻译的显示文字。
        String nameKey,
        // GUI 图集中的 sprite ID，不是纹理文件路径。
        String icon,
        // 超过容量时尝试合并本类的优先级。
        int mergeOrder,
        // 合并前后共用的显示优先级。
        int displayOrder) {
    private static final int HARMFUL_MERGE_ORDER = 70;
    private static final int HARMFUL_DISPLAY_ORDER = 40;
    private static final int NEUTRAL_MERGE_ORDER = 65;
    private static final int NEUTRAL_DISPLAY_ORDER = 85;
    private static final int BENEFICIAL_MERGE_ORDER = 60;
    private static final int BENEFICIAL_DISPLAY_ORDER = 90;

    public static final StatusGroup HARMFUL =
            new StatusGroup(
                    "kizuna_inventory_ui:harmful",
                    "status_group.kizuna_inventory_ui.harmful",
                    "kizuna_inventory_ui:status_group/harmful",
                    HARMFUL_MERGE_ORDER,
                    HARMFUL_DISPLAY_ORDER);
    public static final StatusGroup NEUTRAL =
            new StatusGroup(
                    "kizuna_inventory_ui:neutral",
                    "status_group.kizuna_inventory_ui.neutral",
                    "kizuna_inventory_ui:status_group/neutral",
                    NEUTRAL_MERGE_ORDER,
                    NEUTRAL_DISPLAY_ORDER);
    public static final StatusGroup BENEFICIAL =
            new StatusGroup(
                    "kizuna_inventory_ui:beneficial",
                    "status_group.kizuna_inventory_ui.beneficial",
                    "kizuna_inventory_ui:status_group/beneficial",
                    BENEFICIAL_MERGE_ORDER,
                    BENEFICIAL_DISPLAY_ORDER);

    public StatusGroup {
        // 文件位置与图集身份均严格校验，翻译键只要求非空。
        StatusIds.validate(id, "类 id");
        StatusIds.validate(icon, "类图标 id");
        if (nameKey == null || nameKey.isBlank()) {
            throw new IllegalArgumentException("类名称翻译键不能为空");
        }
    }

    static StatusGroup fallback(StatusCategory category) {
        // 兜底仅依赖效果属性，避免引入任何接入方的玩法知识。
        return switch (category) {
            case BENEFICIAL -> BENEFICIAL;
            case HARMFUL -> HARMFUL;
            case NEUTRAL -> NEUTRAL;
        };
    }
}
