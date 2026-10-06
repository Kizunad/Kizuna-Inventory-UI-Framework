package dev.kizuna.inventoryui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

class BuiltinThemesTest {
    private static final int TRANSPARENT_COLOR = 0x00000000;
    private static final int CUSTOM_TEXT_COLOR = 0xFF765432;

    @Test
    void partialThemeKeepsOverridesIncludingTransparentColorsAndInheritsMissingTokens() {
        // 子模块可只覆盖少数令牌，透明值必须被视为有意覆盖而不是“缺省颜色”。
        var theme =
                new FrameworkCatalog.Theme(
                        "example:custom",
                        Map.of(
                                ThemeTokens.INVENTORY_CELL_EVEN, TRANSPARENT_COLOR,
                                ThemeTokens.INVENTORY_ITEM_TEXT, CUSTOM_TEXT_COLOR));

        assertEquals(
                TRANSPARENT_COLOR, BuiltinThemes.color(theme, ThemeTokens.INVENTORY_CELL_EVEN));
        assertEquals(
                CUSTOM_TEXT_COLOR, BuiltinThemes.color(theme, ThemeTokens.INVENTORY_ITEM_TEXT));
        assertEquals(
                BuiltinThemes.DARK.colors().get(ThemeTokens.INVENTORY_CELL_ODD).intValue(),
                BuiltinThemes.color(theme, ThemeTokens.INVENTORY_CELL_ODD),
                "局部覆盖主题仍须为未声明的网格颜色提供默认值");
    }
}
