package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** 两处共用同一只读摘要；draw 收到的局部坐标已经缩放并裁剪。 */
public interface CompactProvider {
    List<CompactVariant> variants();

    /** 嵌入另一功能的摘要可以把展开操作定位到所属页，并保留自己的锁和位置。 */
    default java.util.Optional<String> expandedFeature() {
        return java.util.Optional.empty();
    }

    void draw(GuiGraphicsExtractor g, WindowState state, String variant, float delta);
}
