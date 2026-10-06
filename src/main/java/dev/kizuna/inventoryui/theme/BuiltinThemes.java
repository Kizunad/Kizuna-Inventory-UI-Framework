package dev.kizuna.inventoryui.theme;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import static java.util.Map.entry;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import java.util.Map;
import java.util.Objects;

/** 内置调色板；颜色字面量仅在主题定义中出现，绘制代码只读取语义令牌。 */
public final class BuiltinThemes {
    /** 未知扩展令牌的最终回退色，保持可见而不抛出渲染异常。 */
    private static final int UNKNOWN_TOKEN_COLOR = 0xFFFFFFFF;

    public static final FrameworkCatalog.Theme DARK =
            new FrameworkCatalog.Theme(
                    "kiui:dark",
                    Map.ofEntries(
                            entry(BACKGROUND, 0xFF171E20),
                            entry(PANEL, 0xFF292C2D),
                            entry(HEADER, 0xFF484D48),
                            entry(TEXT, 0xFFE2EAE5),
                            entry(ACCENT, 0xFFDBBF7B),
                            entry(MUTED_TEXT, 0xFFA6AFA8),
                            entry(PROGRESS_TRACK, 0xFF2A2A2A),
                            entry(PROGRESS_FILL, 0xFF5588BB),
                            entry(PREVIEW_BACKGROUND, 0xFF17191B),
                            entry(INVENTORY_CELL_EVEN, 0xFF1E1E1E),
                            entry(INVENTORY_CELL_ODD, 0xFF232323),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFF3A3A3A),
                            entry(INVENTORY_HOVER_BORDER, 0xFF888888),
                            entry(INVENTORY_ITEM_TEXT, 0xFFFFFFFF),
                            entry(INVENTORY_DROP_VALID, 0x3300CC44),
                            entry(INVENTORY_DROP_INVALID, 0x33CC2222),
                            entry(HUD_BACKGROUND, 0xCC111819),
                            entry(HUD_TEXT, 0xFFE1E8DC),
                            entry(PANEL_BOTTOM, 0xFF17191B),
                            entry(HEADER_BOTTOM, 0xFF292E2C),
                            entry(BORDER, 0xFF626762),
                            entry(EDGE_LIGHT, 0xFF9C9D8C),
                            entry(EDGE_SIDE, 0xFF494E49),
                            entry(EDGE_DARK, 0xFF090B0C),
                            entry(HEADER_RULE, 0xFF0D1110),
                            entry(HEADER_ACCENT, 0xFFB4A77C),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFF303D37),
                            entry(CONTROL_HOVER, 0xFF485A51),
                            entry(CONTROL_CLOSE_HOVER, 0xFF824548),
                            entry(TOOLBAR, 0xFF171E20),
                            entry(TOOLBAR_BORDER, 0xFF4D5B55),
                            entry(MENU, 0xFF212A2A),
                            entry(MENU_BORDER, 0xFF819187),
                            entry(INPUT_ERROR, 0xFFEE8F8F),
                            entry(BACKGROUND_OVERLAY, 0x35080C0D)));

    public static final FrameworkCatalog.Theme LIGHT =
            new FrameworkCatalog.Theme(
                    "kiui:light",
                    Map.ofEntries(
                            entry(BACKGROUND, 0xEEE5E9EF),
                            entry(PANEL, 0xFFF5F7FA),
                            entry(HEADER, 0xFFBECDE0),
                            entry(TEXT, 0xFF192535),
                            entry(ACCENT, 0xFF325F96),
                            entry(MUTED_TEXT, 0xFF657085),
                            entry(PROGRESS_TRACK, 0xFFCAD3E1),
                            entry(PROGRESS_FILL, 0xFF4F83AC),
                            entry(PREVIEW_BACKGROUND, 0xFFE1E8F1),
                            entry(INVENTORY_CELL_EVEN, 0xFFDCE4EF),
                            entry(INVENTORY_CELL_ODD, 0xFFCDD8E6),
                            entry(INVENTORY_ITEM_BACKGROUND, 0x00000000),
                            entry(INVENTORY_BORDER, 0xFFA6AFA8),
                            entry(INVENTORY_HOVER_BORDER, 0xFF65736B),
                            entry(INVENTORY_ITEM_TEXT, 0xFF192535),
                            entry(INVENTORY_DROP_VALID, 0x3300CC44),
                            entry(INVENTORY_DROP_INVALID, 0x33CC2222),
                            entry(HUD_BACKGROUND, 0xCCF5F7FA),
                            entry(HUD_TEXT, 0xFF192535),
                            entry(PANEL_BOTTOM, 0xFFE1E8E2),
                            entry(HEADER_BOTTOM, 0xFFC3CFC5),
                            entry(BORDER, 0xFF819187),
                            entry(EDGE_LIGHT, 0xFFFFFFFF),
                            entry(EDGE_SIDE, 0xFFA6AFA8),
                            entry(EDGE_DARK, 0xFF65736B),
                            entry(HEADER_RULE, 0xFF819187),
                            entry(HEADER_ACCENT, 0xFF90763F),
                            entry(SHADOW, 0x08000000),
                            entry(CONTROL, 0xFFD4DED6),
                            entry(CONTROL_HOVER, 0xFFB3C8B9),
                            entry(CONTROL_CLOSE_HOVER, 0xFFE5B6B7),
                            entry(TOOLBAR, 0xFFE1E8E2),
                            entry(TOOLBAR_BORDER, 0xFF819187),
                            entry(MENU, 0xFFF5F7FA),
                            entry(MENU_BORDER, 0xFF819187),
                            entry(INPUT_ERROR, 0xFFAA3038),
                            entry(BACKGROUND_OVERLAY, 0x10FFFFFF)));

    private BuiltinThemes() {}

    public static int color(FrameworkCatalog.Theme theme, String token) {
        // 局部主题只需覆盖关心的令牌，缺项逐级回退；透明色零值也是有效覆盖。
        Objects.requireNonNull(theme, "theme must not be null");
        Objects.requireNonNull(token, "token must not be null");
        return theme.colors().getOrDefault(token, defaultColor(token));
    }

    public static int defaultColor(String token) {
        // 独立于客户端运行时，未绑定主题提供器的组件也能使用完整默认调色板。
        Objects.requireNonNull(token, "token must not be null");
        return DARK.colors().getOrDefault(token, UNKNOWN_TOKEN_COLOR);
    }
}
