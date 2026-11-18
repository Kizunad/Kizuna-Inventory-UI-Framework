package dev.kizuna.inventoryui.client.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusLayoutTest {
    private static final int SMALL_WIDTH = 427;
    private static final int SMALL_HEIGHT = 240;

    @Test
    void smallWindowHoldsTwoColumnsOfSix() {
        // 854×480 的窗口按两倍缩放是 427×240：每列六格，共十二格。
        List<Area> cells = StatusLayout.hudCells(SMALL_WIDTH, SMALL_HEIGHT);
        assertEquals(12, cells.size());
        assertEquals(new Area(400, 1, 24, 33), cells.get(0));
        assertEquals(new Area(400, 35, 24, 33), cells.get(1));
        assertEquals(new Area(370, 1, 24, 33), cells.get(6));
    }

    @Test
    void tallerWindowsHoldMoreRows() {
        // 1080p 在三倍、两倍缩放下分别是九行和十五行。
        assertEquals(18, StatusLayout.hudCells(640, 360).size());
        assertEquals(30, StatusLayout.hudCells(960, 540).size());
    }

    @Test
    void cellsStayAboveTheHotbarReserve() {
        // 最后一格连同时间一行不能伸进底边预留。
        for (Area cell : StatusLayout.hudCells(SMALL_WIDTH, SMALL_HEIGHT)) {
            assertTrue(cell.bottom() <= SMALL_HEIGHT - StatusLayout.BOTTOM_RESERVE_PIXELS);
        }
    }

    @Test
    void anOpenScreenUsesTheSameCellsAsTheHud() {
        // 界面没挡着右边时，打开界面图标不挪位置，也不比游戏画面里多。
        List<Area> hud = StatusLayout.hudCells(SMALL_WIDTH, SMALL_HEIGHT);
        assertEquals(hud, StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of()));
    }

    @Test
    void aBlockedCellMovesDownToJustBelowTheObstacle() {
        // 右上角有个按钮：第一列从按钮下面两像素处重新排起，第二列不受影响。
        Area button = new Area(406, 5, 13, 13);
        List<Area> cells = StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of(button));
        assertEquals(new Area(400, 20, 24, 33), cells.get(0));
        assertEquals(new Area(400, 54, 24, 33), cells.get(1));
        assertEquals(new Area(400, 156, 24, 33), cells.get(4));
        assertEquals(new Area(370, 1, 24, 33), cells.get(5));
    }

    @Test
    void noCellComesWithinTheClearanceOfAnyBlockedArea() {
        // 界面面板占着屏幕中间偏右：留下的格位离它至少两个像素。
        Area panel = new Area(250, 60, 130, 90);
        for (Area cell : StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of(panel))) {
            boolean apart =
                    cell.right() + StatusLayout.CLEARANCE_PIXELS <= panel.left()
                            || cell.left() >= panel.right() + StatusLayout.CLEARANCE_PIXELS
                            || cell.bottom() + StatusLayout.CLEARANCE_PIXELS <= panel.top()
                            || cell.top() >= panel.bottom() + StatusLayout.CLEARANCE_PIXELS;
            assertTrue(apart, "格位压到了界面：" + cell);
        }
    }

    @Test
    void whenTheRightEdgeIsTakenCellsFallIntoTheCornerBelow() {
        // 右半边上面一大块被界面占着：四列各在它下面排一格，一共四格，都在那一块的下方。
        Area scene = new Area(217, 18, 202, 152);
        List<Area> cells = StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of(scene));
        assertEquals(4, cells.size());
        assertEquals(new Area(400, 172, 24, 33), cells.get(0));
        assertEquals(new Area(310, 172, 24, 33), cells.get(3));
    }

    @Test
    void anOpenScreenNeverHoldsMoreThanTheHud() {
        // 界面能多用两列找空地方，但总数封顶在游戏画面放得下的十二个。
        Area sliver = new Area(400, 100, 24, 10);
        List<Area> cells = StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of(sliver));
        assertEquals(12, cells.size());
    }

    @Test
    void aPanelCoveringTheWholeEdgeLeavesNoCells() {
        // 界面把右边四列整条都占了，就一格也不排。
        Area panel = new Area(300, 0, 127, SMALL_HEIGHT);
        assertTrue(StatusLayout.screenCells(SMALL_WIDTH, SMALL_HEIGHT, List.of(panel)).isEmpty());
    }

    @Test
    void areaContainsItsTopLeftButNotItsBottomRight() {
        Area area = new Area(10, 20, 24, 33);
        assertTrue(area.contains(10, 20));
        assertTrue(area.contains(33, 52));
        assertFalse(area.contains(34, 52));
        assertFalse(area.contains(33, 53));
    }
}
