package dev.kizuna.inventoryui.client;

/** 摘要样式的稳定 ID、菜单里显示名的翻译键，以及缩放前的 GUI 像素尺寸。 */
public record CompactVariant(String id, String nameKey, int width, int height) {
    public CompactVariant {
        // 空尺寸无法裁剪或接收宿主内的鼠标。
        if (id == null || id.isBlank() || nameKey == null || nameKey.isBlank() || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid compact variant");
        }
    }
}
