package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.inventoryui.client.CompactLayout.Item;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import dev.kizuna.inventoryui.workspace.CompactPlacement;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactLayoutTest {
    private static final Rect FRAME = new Rect(0, 0, 427, 216);
    private static final Rect CROSSHAIR = new Rect(205, 112, 16, 16);
    private static final int MARGIN = CompactLayout.MARGIN;
    private static final int GAP = CompactLayout.GAP;

    private static Item<String> flowing(String key, int column, int order, int width, int height) {
        var variant = new CompactVariant("value", "fixture.compact.value", width, height);
        return new Item<>(key, CompactPlacement.initial("value"), new CompactSlot(column, order), variant);
    }

    @Test
    void declaredColumnsStackDownwardAndSitSideBySide() {
        var items = List.of(flowing("second", 0, 1, 75, 30), flowing("aside", 1, 0, 80, 60),
                flowing("first", 0, 0, 118, 30));
        var layout = CompactLayout.arrange(FRAME, items, List.of());
        assertEquals(new Rect(MARGIN, MARGIN, 118, 30), layout.get("first"));
        assertEquals(new Rect(MARGIN, MARGIN + 30 + GAP, 75, 30), layout.get("second"));
        assertEquals(new Rect(MARGIN + 118 + GAP, MARGIN, 80, 60), layout.get("aside"));
    }

    @Test
    void flowStopsJustShortOfAnObstacleWithoutExtraSpacing() {
        // 障碍自己带余量：小窗下沿可以贴到障碍上沿，再往下一个像素就得另起一列。
        var obstacle = new Rect(0, MARGIN + 40, 200, 30);
        var touching = CompactLayout.arrange(FRAME, List.of(flowing("fits", 0, 0, 60, 40)), List.of(obstacle));
        assertEquals(new Rect(MARGIN, MARGIN, 60, 40), touching.get("fits"));
        var crossing = CompactLayout.arrange(FRAME, List.of(flowing("tall", 0, 0, 60, 41)), List.of(obstacle));
        assertEquals(new Rect(MARGIN, MARGIN + 40 + 30 + GAP, 60, 41), crossing.get("tall"));
    }

    @Test
    void fullColumnContinuesInANewColumnToTheRight() {
        var items = List.of(flowing("a", 0, 0, 60, 100), flowing("b", 0, 1, 60, 100), flowing("c", 0, 2, 40, 100));
        var layout = CompactLayout.arrange(FRAME, items, List.of());
        assertEquals(new Rect(MARGIN, MARGIN + 100 + GAP, 60, 100), layout.get("b"));
        assertEquals(new Rect(MARGIN + 60 + GAP, MARGIN, 40, 100), layout.get("c"));
    }

    @Test
    void flowSkipsPastWindowsThePlayerPlaced() {
        var placed = CompactPlacement.initial("value").anchor(new Rect(MARGIN, MARGIN, 50, 20), FRAME);
        var variant = new CompactVariant("value", "fixture.compact.value", 50, 20);
        var dragged = new Item<>("placed", placed, CompactSlot.UNORDERED, variant);
        var items = List.of(dragged, flowing("auto", 0, 0, 50, 20));
        var layout = CompactLayout.arrange(FRAME, items, List.of());
        assertEquals(new Rect(MARGIN, MARGIN, 50, 20), layout.get("placed"));
        assertEquals(new Rect(MARGIN, MARGIN + 20 + GAP, 50, 20), layout.get("auto"));
    }

    @Test
    void placedWindowMovesOffTheCrosshairByTheShortestShift() {
        // 压住准星左缘 3 像素：往左贴边只挪 3 像素，比上下左右其余三处都近。
        var dropped = new Rect(CROSSHAIR.x() - 50 + 3, CROSSHAIR.y(), 50, 16);
        var placed = CompactPlacement.initial("value").anchor(dropped, FRAME);
        var variant = new CompactVariant("value", "fixture.compact.value", 50, 16);
        var layout = CompactLayout.arrange(FRAME, List.of(new Item<>("placed", placed, CompactSlot.UNORDERED, variant)),
                List.of(CROSSHAIR));
        assertEquals(new Rect(CROSSHAIR.x() - 50, CROSSHAIR.y(), 50, 16), layout.get("placed"));
    }

    @Test
    void frameTooSmallForEverythingStillPlacesEveryItemInside() {
        var tiny = new Rect(0, 0, 60, 40);
        var items = List.of(flowing("a", 0, 0, 80, 30), flowing("b", 0, 1, 80, 30), flowing("c", 1, 0, 80, 30));
        var layout = CompactLayout.arrange(tiny, items, List.of(new Rect(22, 12, 16, 16)));
        assertEquals(items.size(), layout.size());
        for (var rect : layout.values()) {
            assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.x() + rect.width() <= tiny.width()
                    && rect.y() + rect.height() <= tiny.height(), rect.toString());
        }
    }
}
