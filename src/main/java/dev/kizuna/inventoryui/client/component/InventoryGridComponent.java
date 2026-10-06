package dev.kizuna.inventoryui.client.component;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import dev.kizuna.inventoryui.client.ItemContextMenu;
import dev.kizuna.inventoryui.contract.UiStateSource;
import dev.kizuna.inventoryui.inventory.InventoryDragSession;
import dev.kizuna.inventoryui.inventory.InventoryGrid;
import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.theme.BuiltinThemes;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 占格网格；共享 drag 时支持跨窗口拖放，所有操作仅产生意图。 */
public final class InventoryGridComponent extends BaseComponent implements ItemDropTarget {
    private static final int MIN_CELL_SIZE = 12;
    private static final int CELL_GAP = 1;
    private static final int ITEM_INSET = 2;
    private static final int CHECKERBOARD_PERIOD = 2;
    private static final long DOUBLE_CLICK_NANOS = 250_000_000L;
    private final UiStateSource<InventorySnapshot> source;
    private final String containerId;
    private final int cellSize;
    private final Consumer<InventoryGrid.MoveIntent> moves;
    private final ToIntFunction<String> colors;
    private final InventoryDragSession drag;
    private ItemPresentation presentation = ItemPresentation.DEFAULT;
    private ItemContextMenu menu;
    private String lastClick;
    private long lastClickNanos;
    private int columns;
    private int rows;
    private Predicate<InventoryDragSession.GridDrop> acceptsExternal = intent -> false;
    private Consumer<InventoryDragSession.GridDrop> externalDrop = intent -> {};

    public InventoryGridComponent externalDrops(
            Predicate<InventoryDragSession.GridDrop> accepts,
            Consumer<InventoryDragSession.GridDrop> submit) {
        // 外部装备或独立状态流必须显式适配，默认不能误当成同一库存的普通移动。
        acceptsExternal = Objects.requireNonNull(accepts);
        externalDrop = Objects.requireNonNull(submit);
        return this;
    }

    private InventoryDragSession.GridDrop externalIntent(int row, int column) {
        var snapshot = source.snapshot();
        return new InventoryDragSession.GridDrop(
                drag.current(), snapshot.streamId(), snapshot.revision(), containerId, row, column);
    }

    private boolean canDrop(int row, int column) {
        // 同状态流走原子网格移动，其余来源先验证占格再询问模块是否接受此类转移。
        if (drag.belongsTo(source)) {
            return drag.canMove(source, containerId, row, column);
        }
        var payload = drag.current();
        return payload != null
                && InventoryGrid.canPlaceExternal(
                        source.snapshot(),
                        payload.item(),
                        containerId,
                        row,
                        column,
                        payload.rotated())
                && acceptsExternal.test(externalIntent(row, column));
    }

    public InventoryGridComponent(
            UiStateSource<InventorySnapshot> source,
            String containerId,
            int cellSize,
            Consumer<InventoryGrid.MoveIntent> moves) {
        this(source, containerId, cellSize, moves, BuiltinThemes::defaultColor);
    }

    public InventoryGridComponent(
            UiStateSource<InventorySnapshot> source,
            String containerId,
            int cellSize,
            Consumer<InventoryGrid.MoveIntent> moves,
            ToIntFunction<String> colors) {
        this(source, containerId, cellSize, moves, colors, new InventoryDragSession());
    }

    public InventoryGridComponent(
            UiStateSource<InventorySnapshot> source,
            String containerId,
            int cellSize,
            Consumer<InventoryGrid.MoveIntent> moves,
            ToIntFunction<String> colors,
            InventoryDragSession drag) {
        // 共享会话由工作台持有，独立使用时仍可使用兼容构造方法。
        this.source = Objects.requireNonNull(source);
        this.containerId = Objects.requireNonNull(containerId);
        this.moves = Objects.requireNonNull(moves);
        this.colors = Objects.requireNonNull(colors);
        this.drag = Objects.requireNonNull(drag);
        if (cellSize < MIN_CELL_SIZE) {
            throw new IllegalArgumentException("cell size must be at least " + MIN_CELL_SIZE);
        }
        this.cellSize = cellSize;
        var container = container(source.snapshot());
        if (container == null) {
            throw new IllegalArgumentException("missing container: " + containerId);
        }
        resizeGrid(container);
    }

    public InventoryGridComponent presentation(
            ItemPresentation presentation, ItemContextMenu menu) {
        this.presentation = Objects.requireNonNull(presentation);
        this.menu = Objects.requireNonNull(menu);
        return this;
    }

    private InventorySnapshot.Container container(InventorySnapshot snapshot) {
        return snapshot.containers().stream()
                .filter(value -> value.id().equals(containerId))
                .findFirst()
                .orElse(null);
    }

    private void resizeGrid(InventorySnapshot.Container container) {
        // 容量变化同步布局，不能继续画出旧组件命中区域之外的格子。
        columns = container.columns();
        rows = container.rows();
        sizing(
                Sizing.fixed(Math.multiplyExact(columns, cellSize)),
                Sizing.fixed(Math.multiplyExact(rows, cellSize)));
    }

    @Override
    public void update(float delta, int mouseX, int mouseY) {
        // 先通知父布局尺寸变化，再绘制，确保动态容量的绘制与命中一致。
        var container = container(source.snapshot());
        if (container != null && (columns != container.columns() || rows != container.rows())) {
            resizeGrid(container);
        }
        super.update(delta, mouseX, mouseY);
    }

    @Override
    public void draw(
            OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 一帧固定一份快照；拖动期间仍显示权威原位置，拒绝或取消无需回滚库存。
        var snapshot = source.snapshot();
        var container = container(snapshot);
        if (container == null) {
            drag.cancel(this);
            tooltip(List.<Text>of());
            return;
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int dx = x + column * cellSize;
                int dy = y + row * cellSize;
                context.fill(
                        dx,
                        dy,
                        dx + cellSize - CELL_GAP,
                        dy + cellSize - CELL_GAP,
                        colors.applyAsInt(
                                (row + column) % CHECKERBOARD_PERIOD == 0
                                        ? INVENTORY_CELL_EVEN
                                        : INVENTORY_CELL_ODD));
                boolean hover =
                        mouseX >= dx
                                && mouseX < dx + cellSize
                                && mouseY >= dy
                                && mouseY < dy + cellSize;
                context.drawRectOutline(
                        dx,
                        dy,
                        cellSize,
                        cellSize,
                        colors.applyAsInt(hover ? INVENTORY_HOVER_BORDER : INVENTORY_BORDER));
            }
        }
        for (var placement : snapshot.placements()) {
            if (!placement.containerId().equals(containerId)) {
                continue;
            }
            int w =
                    (placement.rotated() ? placement.item().height() : placement.item().width())
                            * cellSize;
            int h =
                    (placement.rotated() ? placement.item().width() : placement.item().height())
                            * cellSize;
            int dx = x + placement.column() * cellSize;
            int dy = y + placement.row() * cellSize;
            context.fill(
                    dx + ITEM_INSET,
                    dy + ITEM_INSET,
                    dx + w - ITEM_INSET - CELL_GAP,
                    dy + h - ITEM_INSET - CELL_GAP,
                    colors.applyAsInt(INVENTORY_ITEM_BACKGROUND));
            context.drawRectOutline(
                    dx + CELL_GAP,
                    dy + CELL_GAP,
                    w - 2 * CELL_GAP,
                    h - 2 * CELL_GAP,
                    colors.applyAsInt(presentation.borderToken(placement.item())));
            presentation.draw(
                    context,
                    placement.item(),
                    placement.item().count(),
                    placement.rotated(),
                    dx,
                    dy,
                    w,
                    h,
                    colors);
        }
        var payload = drag.current();
        if (payload != null && isInBoundingBox(mouseX, mouseY)) {
            int row = Math.floorDiv(mouseY - y, cellSize) - payload.grabRow();
            int column = Math.floorDiv(mouseX - x, cellSize) - payload.grabColumn();
            boolean valid = canDrop(row, column);
            int w =
                    (payload.rotated() ? payload.item().height() : payload.item().width())
                            * cellSize;
            int h =
                    (payload.rotated() ? payload.item().width() : payload.item().height())
                            * cellSize;
            context.fill(
                    x + column * cellSize,
                    y + row * cellSize,
                    x + column * cellSize + w,
                    y + row * cellSize + h,
                    colors.applyAsInt(valid ? INVENTORY_DROP_VALID : INVENTORY_DROP_INVALID));
        }
        var hovered =
                itemAt(
                        snapshot,
                        Math.floorDiv(mouseX - x, cellSize),
                        Math.floorDiv(mouseY - y, cellSize));
        tooltip(
                payload != null || hovered == null
                        ? List.of()
                        : presentation.tooltip(hovered.item()));
    }

    private InventorySnapshot.Placement itemAt(InventorySnapshot snapshot, int column, int row) {
        // 任意占格均可抓取同一实例，旋转后的尺寸同时用于命中和预览。
        return snapshot.placements().stream()
                .filter(
                        value ->
                                value.containerId().equals(containerId)
                                        && column >= value.column()
                                        && row >= value.row()
                                        && column
                                                < value.column()
                                                        + (value.rotated()
                                                                ? value.item().height()
                                                                : value.item().width())
                                        && row
                                                < value.row()
                                                        + (value.rotated()
                                                                ? value.item().width()
                                                                : value.item().height()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean onMouseDown(double mouseX, double mouseY, int button) {
        // 右键菜单和快速操作先于抓取；中键选择半堆，拖动中滚轮可精确调整数量。
        int column = (int) Math.floor(mouseX / cellSize);
        int row = (int) Math.floor(mouseY / cellSize);
        var placement = itemAt(source.snapshot(), column, row);
        if (placement == null) {
            return false;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            drag.cancel();
            if (menu != null) {
                var snapshot = source.snapshot();
                menu.open(
                        x + (int) mouseX,
                        y + (int) mouseY,
                        presentation.actions(placement.item()),
                        () -> source.snapshot() == snapshot);
            }
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return false;
        }
        if (Screen.hasShiftDown() && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            presentation.quickMove(placement.item());
            return true;
        }
        long now = System.nanoTime();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && placement.item().instanceId().equals(lastClick)
                && now - lastClickNanos < DOUBLE_CLICK_NANOS) {
            lastClick = null;
            drag.cancel();
            presentation.inspect(placement.item());
            return true;
        }
        lastClick = placement.item().instanceId();
        lastClickNanos = now;
        int count =
                button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                        ? Math.max(1, placement.item().count() / 2)
                        : placement.item().count();
        drag.begin(
                this,
                source,
                placement.item().instanceId(),
                count,
                row - placement.row(),
                column - placement.column());
        return true;
    }

    @Override
    public boolean dropItem(double screenX, double screenY) {
        // 主屏按最上层窗口分派目标，所以不会穿透遮挡窗口投递到底层网格。
        var payload = drag.current();
        if (payload == null) {
            return false;
        }
        int row = (int) Math.floor((screenY - y) / cellSize) - payload.grabRow();
        int column = (int) Math.floor((screenX - x) / cellSize) - payload.grabColumn();
        if (drag.belongsTo(source)) {
            return drag.move(source, containerId, row, column, moves);
        }
        if (!canDrop(row, column)) {
            drag.cancel();
            return false;
        }
        var intent = externalIntent(row, column);
        drag.consume(value -> externalDrop.accept(intent));
        return true;
    }

    @Override
    public boolean onMouseDrag(double mouseX, double mouseY, double dx, double dy, int button) {
        // 一旦发生拖动，这次按下不再参与双击详情识别。
        lastClick = null;
        return drag.current() != null;
    }

    @Override
    public boolean onMouseUp(double mouseX, double mouseY, int button) {
        // 独立使用时仍支持本地释放；工作台共享会话会先在真正的目标组件上消费。
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return false;
        }
        boolean handled = drag.current() != null;
        if (handled) {
            if (mouseX >= 0 && mouseY >= 0 && mouseX < width && mouseY < height) {
                dropItem(x + mouseX, y + mouseY);
            } else {
                drag.cancel();
            }
        }
        return handled;
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        // 无工作台宿主时也能旋转和取消；公共快捷键不触发子模块操作。
        if (drag.current() == null) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            drag.rotate();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            drag.cancel();
            return true;
        }
        return false;
    }

    @Override
    public boolean canFocus(Component.FocusSource source) {
        return true;
    }

    @Override
    public void onFocusLost() {
        drag.cancel(this);
        super.onFocusLost();
    }

    public void cancelDrag() {
        drag.cancel(this);
    }
}
