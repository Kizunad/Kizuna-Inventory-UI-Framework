package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.Rect;

/** 游戏画面上一个小窗的只读描述：所属功能、当前样式和屏幕矩形。 */
public record CompactView(String feature, String variant, Rect bounds) { }
