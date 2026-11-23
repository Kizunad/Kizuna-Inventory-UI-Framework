package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 小窗排位的几何解算，工作台和游戏画面共用。
 * 没拖过的按声明的一侧、列和顺序排开，拖过的只夹取并让开障碍。
 */
final class CompactLayout {
    static final int MARGIN = 4;
    static final int GAP = 8;

    record Item<K>(K key, CompactPlacement placement, CompactSlot slot, CompactVariant variant) { }

    private CompactLayout() { }

    static <K> Map<K, Rect> arrange(Rect frame, List<Item<K>> items, List<Rect> obstacles) {
        // 玩家放过的位置先定下，自动排开的小窗再绕开它们，也互相绕开。
        var result = new LinkedHashMap<K, Rect>();
        var taken = new ArrayList<Rect>();
        for (var item : items) {
            if (item.placement().position() == CompactPlacement.Position.PLACED) {
                var placed = clear(resolve(item, frame), frame, obstacles);
                result.put(item.key(), placed);
                taken.add(placed);
            }
        }
        var flows = new EnumMap<CompactSlot.Edge, Flow>(CompactSlot.Edge.class);
        var ordered = items.stream()
                .filter(item -> item.placement().position() == CompactPlacement.Position.FLOW)
                .sorted(Comparator.comparing((Item<K> item) -> item.slot().edge())
                        .thenComparingInt(item -> item.slot().column())
                        .thenComparingInt(item -> item.slot().order()))
                .toList();
        for (var item : ordered) {
            var size = resolve(item, frame);
            var flow = flows.computeIfAbsent(
                    item.slot().edge(), edge -> new Flow(frame, obstacles, taken, edge));
            result.put(item.key(), flow.next(item.slot().column(), size.width(), size.height()));
        }
        return result;
    }

    private static Rect resolve(Item<?> item, Rect frame) {
        return item.placement().resolve(frame, item.variant().width(), item.variant().height());
    }

    private static Rect clear(Rect rect, Rect frame, List<Rect> obstacles) {
        // 压住障碍时贴到某个障碍的一条边外，取位移最短的一处；四周都放不下就留在原地。
        if (free(rect, obstacles)) {
            return rect;
        }
        int width = rect.width();
        int height = rect.height();
        var candidates = new ArrayList<Rect>();
        for (var obstacle : obstacles) {
            candidates.add(new Rect(obstacle.x() - width, rect.y(), width, height));
            candidates.add(new Rect(obstacle.x() + obstacle.width(), rect.y(), width, height));
            candidates.add(new Rect(rect.x(), obstacle.y() - height, width, height));
            candidates.add(new Rect(rect.x(), obstacle.y() + obstacle.height(), width, height));
        }
        return candidates.stream()
                .filter(candidate -> inside(candidate, frame))
                .filter(candidate -> free(candidate, obstacles))
                .min(Comparator.comparingInt(candidate -> distance(candidate, rect)))
                .orElse(rect);
    }

    private static boolean free(Rect rect, List<Rect> obstacles) {
        return obstacles.stream().noneMatch(obstacle -> overlaps(rect, obstacle, 0));
    }

    private static int distance(Rect a, Rect b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    private static boolean inside(Rect rect, Rect frame) {
        return rect.x() >= frame.x() && rect.y() >= frame.y()
                && rect.x() + rect.width() <= frame.x() + frame.width()
                && rect.y() + rect.height() <= frame.y() + frame.height();
    }

    private static boolean overlaps(Rect a, Rect b, int gap) {
        return a.x() < b.x() + b.width() + gap && b.x() < a.x() + a.width() + gap
                && a.y() < b.y() + b.height() + gap && b.y() < a.y() + a.height() + gap;
    }

    /** 贴着一侧一列一列排：列内从上往下，到底放不下就朝另一侧另起一列。两侧各一个，共用同一份已占位置。 */
    private static final class Flow {
        private final Rect frame;
        private final List<Rect> obstacles;
        private final List<Rect> windows;
        private final CompactSlot.Edge edge;
        private int declaredColumn = -1;
        private int edgeX;
        private int top;
        private int columnWidth;

        Flow(Rect frame, List<Rect> obstacles, List<Rect> windows, CompactSlot.Edge edge) {
            this.frame = frame;
            this.obstacles = obstacles;
            this.windows = windows;
            this.edge = edge;
            this.edgeX = frame.x() + MARGIN;
            if (edge == CompactSlot.Edge.RIGHT) {
                this.edgeX = frame.x() + frame.width() - MARGIN;
            }
            this.top = frame.y() + MARGIN;
        }

        Rect next(int column, int width, int height) {
            if (column != declaredColumn && columnWidth > 0) {
                startColumn();
            }
            declaredColumn = column;
            var free = descend(width, height);
            if (free.isEmpty() && columnWidth > 0) {
                startColumn();
                free = descend(width, height);
            }
            // 整个可用区都排满时退回夹取后的位置，允许重叠。
            var placed = free.orElseGet(() -> clamp(new Rect(left(width), top, width, height)));
            columnWidth = Math.max(columnWidth, width);
            top = placed.y() + height + GAP;
            windows.add(placed);
            return placed;
        }

        private int left(int width) {
            // 右侧的列以右边线为准，窄的小窗也贴着右边。
            if (edge == CompactSlot.Edge.RIGHT) {
                return edgeX - width;
            }
            return edgeX;
        }

        private void startColumn() {
            int step = columnWidth + GAP;
            if (edge == CompactSlot.Edge.RIGHT) {
                step = -step;
            }
            edgeX += step;
            top = frame.y() + MARGIN;
            columnWidth = 0;
        }

        private Optional<Rect> descend(int width, int height) {
            // 每次越过一个挡路的矩形，纵坐标只增不减。
            int y = top;
            while (y + height <= frame.y() + frame.height()) {
                var candidate = clamp(new Rect(left(width), y, width, height));
                var blocker = blocking(candidate);
                if (blocker.isEmpty()) {
                    return Optional.of(candidate);
                }
                y = blocker.get().y() + blocker.get().height() + GAP;
            }
            return Optional.empty();
        }

        private Optional<Rect> blocking(Rect candidate) {
            // 和别的小窗之间留出间距；障碍的矩形自己已经带了余量，只要不相交。
            var window = windows.stream().filter(other -> overlaps(candidate, other, GAP)).findFirst();
            if (window.isPresent()) {
                return window;
            }
            return obstacles.stream().filter(other -> overlaps(candidate, other, 0)).findFirst();
        }

        private Rect clamp(Rect rect) {
            int maxX = frame.x() + Math.max(0, frame.width() - rect.width());
            int maxY = frame.y() + Math.max(0, frame.height() - rect.height());
            return new Rect(Math.clamp(rect.x(), frame.x(), maxX), Math.clamp(rect.y(), frame.y(), maxY),
                    rect.width(), rect.height());
        }
    }
}
