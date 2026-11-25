package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.*;

import dev.kizuna.inventoryui.client.component.ItemDrawing;
import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import dev.kizuna.inventoryui.window.UiWindowManager;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowKey;
import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import dev.kizuna.inventoryui.window.WindowMotion;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 新版界面在一个宿主屏里的窗口绘制与输入：展开窗用琉璃外框，收小的窗口就地画成可拖动的小窗。
 * 继承只为让宿主拿到同一个类型；父类的状态不用，公开方法全部在这里重写。
 */
public final class ModernWindowLayer extends WindowLayer {
    private static final int DRAG_ICON_SIZE = 32;
    private static final int DRAG_POINTER_GAP = 8;
    private static final int OUTSIDE = Integer.MIN_VALUE / 2;
    private final Screen host;
    private final ClientRuntime runtime;
    private final CompactWindows compact;
    private final UiWindowManager windows;
    private final Map<WindowKey, WindowMotion> motions = new LinkedHashMap<>();
    private final Set<Integer> consumedKeys = new HashSet<>();
    private WindowState compactDrag;
    private CompactPlacement dragOrigin;
    private int grabX;
    private int grabY;
    private final int dragFloor;
    private Rect viewport;
    private WindowKey focused;
    private WindowKey pressed;
    private WindowKey resizing;
    private long frameTime = System.nanoTime();
    private boolean detached;

    ModernWindowLayer(ClientRuntime runtime, Screen host, Rect viewport) {
        super(runtime, host, viewport);
        this.host = host;
        this.runtime = runtime;
        this.compact = runtime.modern().compact;
        this.windows = runtime.workspace().windows();
        dragFloor = host instanceof ModernWorkspaceScreen ? TOOLBAR_HEIGHT : 0;
        viewport(viewport);
        // 重开宿主时沿用最高可见窗口的焦点，窗口 scope 随连接保留。
        for (var state : windows.snapshot()) {
            if (!state.minimized()) {
                focused = state.key();
            }
        }
    }

    @Override
    public void viewport(Rect next) {
        // 父类构造时会先调到这里，那时本类的字段还没有赋值；本类构造函数随后再调一次。
        if (windows == null) {
            return;
        }
        // 位置变动也要立即应用；动画始终使用局部坐标，不把宿主偏移写入偏好。
        if (!detached && !next.equals(viewport)) {
            viewport = next;
            motions.clear();
            windows.resizeViewport(next.width(), next.height(), dragFloor);
        }
    }

    private Rect compactFrame() {
        int top = Math.min(dragFloor, viewport.height() - 1);
        return new Rect(0, top, viewport.width(), viewport.height() - top);
    }

    Optional<Rect> compactBounds(String feature) {
        return compact.bounds(feature, compactFrame()).map(this::onScreen);
    }

    void beginFrame() {
        // 宿主查询避让、窗口绘制与本帧输入都取同一时刻。
        frameTime = System.nanoTime();
        motions.keySet().removeIf(key -> !windows.contains(key));
    }

    private Rect displayed(WindowState state) {
        // 窗口、捕获与动画均来自当前模型，首帧绘制前也可命中。
        var key = state.key();
        var bounds = state.bounds();
        if (state.minimized()) {
            motions.remove(key);
            return compact.bounds(state, compactFrame());
        }
        var target = bounds;
        var motion = motions.computeIfAbsent(key, ignored -> new WindowMotion(bounds));
        boolean animate = runtime.motion() && !key.equals(resizing) && !key.equals(windows.capturedKey());
        motion.target(target, frameTime, animate);
        return motion.sample(frameTime);
    }

    private Rect onScreen(Rect local) {
        return new Rect(local.x() + viewport.x(), local.y() + viewport.y(), local.width(), local.height());
    }

    private WindowState hit(double x, double y) {
        // 逆绘制序选最上面的完整矩形，右、下边不含。
        if (!viewport.contains(x, y)) {
            return null;
        }
        for (var state : windows.snapshot().reversed()) {
            if (displayed(state).contains(x - viewport.x(), y - viewport.y())) {
                return state;
            }
        }
        return null;
    }

    public boolean blocksPointer(double x, double y) {
        // 模态弹层在框外也消费点击，不能让底下的槽位显示悬停。
        return !detached && (runtime.contextMenu().isOpen() || hit(x, y) != null);
    }

    public List<Area> blockedAreas() {
        if (detached) {
            return List.of();
        }
        var areas = new ArrayList<Area>();
        for (var state : windows.snapshot()) {
            var local = displayed(state);
            if (local != null) {
                var bounds = onScreen(local);
                areas.add(new Area(bounds.x(), bounds.y(), bounds.width(), bounds.height()));
            }
        }
        if (runtime.contextMenu().isOpen()) {
            areas.add(runtime.contextMenu().area(host.width, host.height));
        }
        return List.copyOf(areas);
    }

    public void extract(GuiGraphicsExtractor g, int x, int y, float delta) {
        extractWindows(g, x, y, delta);
        extractOverlay(g, x, y);
    }

    void extractWindows(GuiGraphicsExtractor g, int x, int y, float delta) {
        if (detached) {
            return;
        }
        var hovered = hit(x, y);
        boolean allowHover = !runtime.contextMenu().isOpen() && runtime.drag().current() == null;
        for (var state : windows.snapshot()) {
            var bounds = displayed(state);
            if (bounds != null) {
                boolean hover = allowHover && state == hovered;
                if (state.minimized()) {
                    var screen = onScreen(bounds);
                    g.nextStratum();
                    compact.draw(g, state, screen, delta);
                    if (hover || state == compactDrag) {
                        // 透明小窗只在指着或拖着时显出范围。
                        compact.outline(g, screen);
                    }
                    if (hover) {
                        ModernChrome.controls(g, runtime, state, screen, x, y);
                    }
                    continue;
                }
                runtime.renderWindow(g, state, onScreen(bounds), true /* controls */,
                        hover ? x : OUTSIDE, hover ? y : OUTSIDE, delta);
            }
        }
    }

    void extractOverlay(GuiGraphicsExtractor g, int x, int y) {
        if (detached) {
            return;
        }
        runtime.contextMenu().render(g, host.width, host.height, x, y, runtime::color);
        var payload = runtime.drag().current();
        if (payload != null) {
            // 拖影使用屏幕坐标，可以离开来源窗口。
            ItemDrawing.draw(g, payload.item(), payload.count(), x + DRAG_POINTER_GAP, y + DRAG_POINTER_GAP,
                    DRAG_ICON_SIZE, DRAG_ICON_SIZE, runtime::color);
        }
    }

    boolean clickOverlay(MouseButtonEvent e) {
        if (detached) {
            return false;
        }
        if (runtime.contextMenu().isOpen()) {
            runtime.contextMenu().area(host.width, host.height);
            return runtime.contextMenu().click(e.x(), e.y(), e.button());
        }
        if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && runtime.drag().current() != null) {
            runtime.drag().cancel();
            return true;
        }
        return false;
    }

    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (detached || clickOverlay(e)) {
            return !detached;
        }
        var state = hit(e.x(), e.y());
        if (state == null) {
            cancelInput();
            focused = null;
            return false;
        }
        focus(state.key());
        var local = displayed(state);
        if (state.minimized()) {
            clickCompact(state, onScreen(local), e);
            return true;
        }
        if (!local.equals(state.bounds())) {
            // 抓住动画当帧的矩形，并从此处继续拖动。
            windows.settleAt(state.key(), local);
            motions.put(state.key(), new WindowMotion(state.bounds()));
        }
        var bounds = onScreen(local);
        if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && openWindowMenu(state, e)) {
            return true;
        }
        if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (e.y() < bounds.y() + WINDOW_HEADER_HEIGHT) {
                clickHeader(state, bounds, e);
                return true;
            }
            if (e.x() >= bounds.x() + bounds.width() - RESIZE_GRIP_SIZE
                    && e.y() >= bounds.y() + bounds.height() - RESIZE_GRIP_SIZE) {
                cancelInput();
                resizing = focused;
                return true;
            }
        }
        pressed = focused;
        var view = runtime.view(pressed);
        if (view != null) {
            view.layout(bounds);
            view.adapter.mouseClicked(view.local(e), doubleClick);
        }
        return true;
    }

    private boolean openWindowMenu(WindowState state, MouseButtonEvent e) {
        // 窗口类型登记了菜单才接管右键；菜单跟着窗口走，窗口关掉或收小就失效。
        var menu = runtime.modern().windowMenus.get(state.key().windowType());
        if (menu == null) {
            return false;
        }
        var actions = menu.apply(state);
        if (actions.isEmpty()) {
            return false;
        }
        cancelInput();
        runtime.contextMenu().open((int) e.x(), (int) e.y(), actions, () -> !state.closed() && !state.minimized());
        return true;
    }

    private void clickCompact(WindowState state, Rect bounds, MouseButtonEvent e) {
        // 浮起的控件先命中，余下的整个透明矩形都可以拖动。
        if (e.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            compact.menu(state, (int) e.x(), (int) e.y());
        } else if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            var action = ModernChrome.hit(runtime, state, bounds, e.x(), e.y());
            if (action == null) {
                compactDrag = state;
                dragOrigin = compact.placement(state);
                grabX = (int) e.x() - bounds.x();
                grabY = (int) e.y() - bounds.y();
            } else {
                perform(state, action);
            }
        }
    }

    private void clickHeader(WindowState state, Rect bounds, MouseButtonEvent e) {
        var action = ModernChrome.hit(runtime, state, bounds, e.x(), e.y());
        if (action == null) {
            windows.beginDrag(state.key(), e.x() - viewport.x(), e.y() - viewport.y());
            return;
        }
        perform(state, action);
    }

    private void perform(WindowState state, ModernChrome.Action action) {
        cancelInput();
        switch (action) {
            case CLOSE -> closeWindow(state.key());
            case MINIMIZE -> {
                windows.minimize(state.key());
                focused = null;
            }
            case PIN -> runtime.pinWindow(state.key(), !state.pinned());
            case RESTORE -> {
                var target = compact.providers.get(state.key().windowType()).expandedFeature();
                if (target.isPresent()) {
                    runtime.open(target.get(), null);
                    return;
                }
                windows.restore(state.key());
                runtime.ensureView(state);
                focus(state.key());
            }
        }
    }

    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (detached) {
            return false;
        }
        double localX = e.x() - viewport.x();
        double localY = e.y() - viewport.y();
        if (compactDrag != null) {
            var frame = compactFrame();
            var size = compact.bounds(compactDrag, frame);
            var outer = new Rect((int) localX - grabX, (int) localY - grabY, size.width(), size.height());
            compact.move(compactDrag, outer, frame);
            return true;
        }
        if (resizing != null) {
            for (var state : windows.snapshot()) {
                if (state.key().equals(resizing)) {
                    windows.resize(resizing, Integer.toString(Math.max(1, (int) localX - state.bounds().x())),
                            Integer.toString(Math.max(1, (int) localY - state.bounds().y())));
                    break;
                }
            }
            return true;
        }
        if (windows.dragTo(localX, Math.max(dragFloor, localY))) {
            return true;
        }
        var view = runtime.view(pressed);
        if (view != null) {
            view.adapter.mouseDragged(view.local(e), dx, dy);
        }
        return pressed != null;
    }

    public boolean mouseReleased(MouseButtonEvent e) {
        if (detached) {
            return false;
        }
        if (compactDrag != null || resizing != null || windows.endDrag()) {
            compactDrag = null;
            dragOrigin = null;
            resizing = null;
            runtime.savePreferences();
            return true;
        }
        var source = pressed;
        pressed = null;
        if (runtime.drag().current() != null && (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                || e.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
            // 只有释放点最上面的窗口能提交物品；空地或不兼容的内容只取消预览。
            try {
                var target = hit(e.x(), e.y());
                if (target != null && !target.minimized()) {
                    var view = runtime.view(target.key());
                    if (view != null) {
                        view.layout(onScreen(displayed(target)));
                        view.dropItem(e.x(), e.y());
                    }
                }
            } finally {
                runtime.drag().cancel();
            }
            return true;
        }
        var view = runtime.view(source);
        if (view != null) {
            view.adapter.mouseReleased(view.local(e));
        }
        return source != null;
    }

    boolean scrollOverlay(double amount) {
        if (detached) {
            return false;
        }
        if (runtime.contextMenu().isOpen()) {
            runtime.contextMenu().scroll(amount);
            return true;
        }
        var payload = runtime.drag().current();
        if (payload != null) {
            runtime.drag().count(payload.count() + (int) amount);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (detached || scrollOverlay(dy)) {
            return !detached;
        }
        var state = hit(x, y);
        if (state == null) {
            return false;
        }
        if (state.minimized()) {
            return true;
        }
        var view = runtime.view(state.key());
        if (view != null) {
            view.layout(onScreen(displayed(state)));
            view.adapter.mouseScrolled(x - view.adapter.x(), y - view.adapter.y(), dx, dy);
        }
        return true;
    }

    boolean keyOverlay(KeyEvent e) {
        if (detached) {
            return false;
        }
        if (compactDrag != null && e.key() == GLFW.GLFW_KEY_ESCAPE) {
            // 取消拖动回到按下时的位置，原本自动排开的仍旧自动排开。
            compact.place(compactDrag, dragOrigin);
            cancelInput();
            return true;
        }
        if (runtime.contextMenu().key(e.key())) {
            return true;
        }
        if (runtime.drag().current() != null) {
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                runtime.drag().cancel();
                return true;
            }
            if (e.key() == GLFW.GLFW_KEY_R) {
                runtime.drag().rotate();
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(KeyEvent e) {
        if (detached || keyOverlay(e)) {
            return !detached;
        }
        // 普通宿主先收回窗口手势，再处理自己的关屏快捷键。
        if (e.key() == GLFW.GLFW_KEY_ESCAPE && (resizing != null || windows.capturedKey() != null)) {
            cancelInput();
            return true;
        }
        return keyInWindow(e);
    }

    boolean keyInWindow(KeyEvent e) {
        if (detached) {
            return false;
        }
        if (e.key() == GLFW.GLFW_KEY_TAB && (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            // 将最底部的可见窗移到顶层，重复按键即可遍历。
            for (var state : windows.snapshot()) {
                if (!state.minimized()) {
                    focus(state.key());
                    break;
                }
            }
            return true;
        }
        var view = focusedView();
        if (view != null && view.keyPressed(e)) {
            consumedKeys.add(e.key());
            return true;
        }
        return false;
    }

    private OwoWindowView focusedView() {
        // 小窗只接管理手势，不把输入交给藏在后面的展开内容。
        if (windows.snapshot().stream().anyMatch(s -> s.key().equals(focused) && s.minimized())) {
            return null;
        }
        return runtime.view(focused);
    }

    public boolean keyReleased(KeyEvent e) {
        if (detached) {
            return false;
        }
        // 扩展已有的释放回调负责清除长按；归本层的按键不再向宿主分发。
        runtime.keyReleased(e);
        return consumedKeys.remove(e.key());
    }

    public boolean charTyped(CharacterEvent e) {
        if (detached || runtime.contextMenu().isOpen()) {
            return !detached;
        }
        var view = focusedView();
        return view != null && view.adapter.charTyped(e);
    }

    public void focus(WindowKey key) {
        if (detached || !windows.focus(key)) {
            return;
        }
        if (!key.equals(focused)) {
            cancelInput();
            focused = key;
        }
    }

    void closeWindow(WindowKey key) {
        cancelInput();
        runtime.savePreferences();
        windows.close(key);
        if (key.equals(focused)) {
            focused = null;
        }
    }

    public void cancelInput() {
        if (!detached) {
            compactDrag = null;
            dragOrigin = null;
            pressed = null;
            resizing = null;
            consumedKeys.clear();
            runtime.cancelInput();
        }
    }

    public void detach() {
        // removed 可晚于下一次 attach；旧层不能清掉新屏的捕获或关闭实例。
        if (!detached) {
            cancelInput();
            detached = true;
            motions.clear();
            runtime.unbind(this);
        }
    }
}
