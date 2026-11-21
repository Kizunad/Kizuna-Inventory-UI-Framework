package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.workspace.UiVersion;
import dev.kizuna.inventoryui.workspace.WorkspaceController;
import dev.kizuna.inventoryui.workspace.WorkspacePreferences.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * 一版界面的全部表现。每一版有自己的目录、窗口模型和窗口工厂，并决定窗口怎么开、怎么锁到游戏画面、
 * 外框怎么画、宿主层和整屏界面用哪个类，以及布局存在偏好的哪几项里。运行时只和这个类型打交道。
 */
abstract class UiEdition {
    final ClientRuntime runtime;
    final FrameworkCatalog catalog;
    final WorkspaceController workspace;
    final Map<String, ClientBindings.WindowFactory> factories = new LinkedHashMap<>();
    final Map<String, Consumer<WindowState>> featureOpened = new LinkedHashMap<>();
    String theme;
    boolean themeChanged;

    UiEdition(ClientRuntime runtime, FrameworkCatalog catalog, WorkspaceController workspace, String theme) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.workspace = workspace;
        this.theme = theme;
    }

    abstract UiVersion version();

    abstract WindowLayer layer(Screen host, Rect viewport);

    abstract Screen workspaceScreen();

    /** 这块屏幕是不是这一版自己的整屏界面；切换版本时据此决定要不要换成另一版的整屏界面。 */
    abstract boolean ownsScreen(Screen screen);

    /** 标题栏高度，窗口内容从这条线以下开始。 */
    abstract int headerHeight();

    /** 没有宿主时开窗，屏幕底部要给这一版留出的高度。 */
    abstract int reservedBottom();

    /** 读入这一版的布局和主题；偏好重新加载时会再调一次。 */
    abstract void load(Snapshot saved);

    /** 这一版存下的展开窗矩形，按窗口类型。 */
    abstract Map<String, Rect> savedWindows(Snapshot saved);

    /** 把这一版的布局写回快照，另一版的字段原样带过去。 */
    abstract Snapshot store(Snapshot saved, Map<String, Rect> windows);

    /** 窗口模型刚打开或聚焦一个功能；返回 false 表示它随即被关掉，不再建视图。 */
    abstract boolean opened(String feature, WindowState state);

    /** 新窗口的组件树刚建好。 */
    abstract void viewCreated(WindowState state);

    abstract boolean pin(WindowKey key, boolean pinned);

    /** 参数和 ClientRuntime.renderWindow 相同，宿主层只调那一个入口。 */
    abstract void renderWindow(
            GuiGraphicsExtractor context,
            WindowState state,
            Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY,
            float delta);

    /** 游戏画面里属于窗口的部分：旧版是锁住的整窗，新版是锁住的小窗。 */
    abstract void renderHudWindows(GuiGraphicsExtractor context, int width, int height, float delta);

    /** 游戏画面每帧开头解算一次这一版占的位置，状态图标据此让开。 */
    List<Rect> beginHudFrame(int width, int height) {
        return List.of();
    }

    void resetLayout() { }

    /** 宿主给出了可用区，或者刚连上服务器。 */
    void viewport(Rect viewport) { }

    void connected() { }

    /** 断线，或者玩家切到了另一版：这一版的窗口随后全部关闭，锁和位置保留。 */
    void released() { }

    void tick(long now) { }
}
