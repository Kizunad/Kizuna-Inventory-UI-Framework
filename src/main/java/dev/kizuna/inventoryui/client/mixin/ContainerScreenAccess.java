package dev.kizuna.inventoryui.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读容器界面面板的位置和尺寸；状态图标要让开它。 */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccess {
    @Accessor("leftPos")
    int inventoryUi$leftPos();

    @Accessor("topPos")
    int inventoryUi$topPos();

    @Accessor("imageWidth")
    int inventoryUi$imageWidth();

    @Accessor("imageHeight")
    int inventoryUi$imageHeight();
}
