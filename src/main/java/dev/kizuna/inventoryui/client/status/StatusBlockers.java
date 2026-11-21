package dev.kizuna.inventoryui.client.status;

import dev.kizuna.inventoryui.client.mixin.ContainerScreenAccess;
import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/** 界面上不能被状态图标压住的地方。默认认面板、物品格和按钮；画在别处的浮动面板由各模组自己登记。 */
public final class StatusBlockers {
    /** 原版物品格的边长。 */
    private static final int SLOT_PIXELS = 16;
    /** 物品格外面那圈面板边框的估计宽度；浮动面板没有登记时靠它把整块面板让出来。 */
    private static final int SLOT_PANEL_MARGIN_PIXELS = 8;
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private static final List<HudSource> HUD_SOURCES = new CopyOnWriteArrayList<>();

    public interface HudSource {
        List<Area> blocked(int guiWidth, int guiHeight);
    }

    public static void registerHud(HudSource source) {
        HUD_SOURCES.add(Objects.requireNonNull(source));
    }

    static List<Area> hud(int width, int height) {
        return HUD_SOURCES.stream().flatMap(source -> source.blocked(width, height).stream()).toList();
    }

    private StatusBlockers() {}

    /** 一个界面里额外要让开的矩形；不认识的界面返回空列表。 */
    @FunctionalInterface
    public interface Source {
        List<Area> blocked(Screen screen);
    }

    /** 模组在客户端初始化时登记自己界面上的浮动面板。 */
    public static void register(Source source) {
        SOURCES.add(Objects.requireNonNull(source));
    }

    static List<Area> of(AbstractContainerScreen<?> screen) {
        // 默认规则覆盖原版和多数模组界面；登记的来源只需补充默认规则看不见的部分。
        List<Area> blocked = new ArrayList<>();
        ContainerScreenAccess panel = (ContainerScreenAccess) screen;
        int left = panel.inventoryUi$leftPos();
        int top = panel.inventoryUi$topPos();
        blocked.add(new Area(left, top, panel.inventoryUi$imageWidth(), panel.inventoryUi$imageHeight()));
        for (Slot slot : screen.getMenu().slots) {
            if (slot.isActive()) {
                blocked.add(slotArea(left + slot.x, top + slot.y));
            }
        }
        for (GuiEventListener child : screen.children()) {
            ScreenRectangle bounds = child.getRectangle();
            if (bounds.width() > 0 && bounds.height() > 0) {
                blocked.add(new Area(bounds.left(), bounds.top(), bounds.width(), bounds.height()));
            }
        }
        for (Source source : SOURCES) {
            blocked.addAll(source.blocked(screen));
        }
        return List.copyOf(blocked);
    }

    private static Area slotArea(int left, int top) {
        // 物品格连同它外面的面板边框一起让开。
        int side = SLOT_PIXELS + SLOT_PANEL_MARGIN_PIXELS * 2;
        return new Area(left - SLOT_PANEL_MARGIN_PIXELS, top - SLOT_PANEL_MARGIN_PIXELS, side, side);
    }
}
