package dev.kizuna.inventoryui.workspace;

import java.util.Locale;

/** 新版界面的图标风格；每种风格在各模块的资源里各有一套同名贴图。 */
public enum IconStyle {
    ITEM,
    GLASS_LINE,
    INK;

    /** 偏好里没有这一项、或写了不认识的名字时用的风格。 */
    public static final IconStyle DEFAULT = GLASS_LINE;

    private static final String NAME_KEY = "kizuna_inventory_ui.icons.";

    /** 偏好文件、资源目录和命令里用的名字。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 外观菜单里显示名的翻译键。 */
    public String nameKey() {
        return NAME_KEY + id();
    }

    public static IconStyle of(String id) {
        for (var style : values()) {
            if (style.id().equals(id)) {
                return style;
            }
        }
        return DEFAULT;
    }
}
