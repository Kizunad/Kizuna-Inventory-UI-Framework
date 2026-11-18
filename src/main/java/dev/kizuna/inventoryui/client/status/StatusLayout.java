package dev.kizuna.inventoryui.client.status;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** 状态图标的格位：右上角起先向下、再向左；会压到界面的格位跳过。只做整数几何，不依赖客户端类。 */
public final class StatusLayout {
    /** 原版状态图标底框的边长。 */
    public static final int FRAME_PIXELS = 24;
    /** 图标在底框里的内缩，和原版一致。 */
    public static final int ICON_INSET_PIXELS = 3;
    /** 原版状态图标贴图的边长。 */
    public static final int ICON_PIXELS = 18;
    /** 底框和下面那行时间之间的空隙。 */
    public static final int TIME_GAP_PIXELS = 1;
    /** 一行原版字的高度。 */
    public static final int TIME_LINE_PIXELS = 8;
    /** 一个格位的高度：底框加时间。 */
    public static final int CELL_HEIGHT_PIXELS = FRAME_PIXELS + TIME_GAP_PIXELS + TIME_LINE_PIXELS;
    /** 相邻两行格位的间距。 */
    public static final int ROW_STEP_PIXELS = CELL_HEIGHT_PIXELS + 1;
    /** 相邻两列的间距；要容得下「00:00」这样最宽的时间。 */
    public static final int COLUMN_STEP_PIXELS = 30;
    /** 游戏画面里最多排几列。 */
    public static final int HUD_COLUMNS = 2;
    /** 界面打开时，右边两列被界面挡住的话最多往左用到第几列。 */
    public static final int SCREEN_COLUMNS = 4;
    /** 第一行离屏幕上沿的距离，和原版一致。 */
    public static final int TOP_MARGIN_PIXELS = 1;
    /** 底边留给快捷栏的高度。 */
    public static final int BOTTOM_RESERVE_PIXELS = 24;
    /** 格位和界面占位之间至少留的空隙。 */
    public static final int CLEARANCE_PIXELS = 2;

    private StatusLayout() {}

    /**
     * 屏幕上的一块矩形，单位是界面像素。
     *
     * @param left 左边界
     * @param top 上边界
     * @param width 宽度
     * @param height 高度
     */
    public record Area(
            int left,
            int top,
            int width,
            int height) {
        public int right() {
            return left + width;
        }

        public int bottom() {
            return top + height;
        }

        public boolean contains(int x, int y) {
            // 左上含边、右下不含，相邻格位不会同时命中。
            return x >= left && x < right() && y >= top && y < bottom();
        }

        boolean crowds(Area other) {
            // 两块矩形之间不足规定空隙就算挤到了。
            return left < other.right() + CLEARANCE_PIXELS
                    && right() > other.left() - CLEARANCE_PIXELS
                    && top < other.bottom() + CLEARANCE_PIXELS
                    && bottom() > other.top() - CLEARANCE_PIXELS;
        }
    }

    /** 游戏画面里的格位，按填充顺序排列。 */
    public static List<Area> hudCells(int guiWidth, int guiHeight) {
        return cells(guiWidth, guiHeight, HUD_COLUMNS, List.of());
    }

    /**
     * 界面打开时的格位，按填充顺序排列；{@code blocked} 是界面占着、不能压的矩形。
     * 可以多用几列去找空地方，但总数不超过游戏画面里放得下的数量。
     */
    public static List<Area> screenCells(int guiWidth, int guiHeight, List<Area> blocked) {
        List<Area> open = cells(guiWidth, guiHeight, SCREEN_COLUMNS, blocked);
        int limit = hudCells(guiWidth, guiHeight).size();
        return open.subList(0, Math.min(limit, open.size()));
    }

    private static List<Area> cells(int guiWidth, int guiHeight, int columns, List<Area> blocked) {
        // 每列底框在列宽里居中；格位连同时间一行都不能越过底边预留。
        List<Area> cells = new ArrayList<>();
        int inset = (COLUMN_STEP_PIXELS - FRAME_PIXELS) / 2;
        int lowest = guiHeight - BOTTOM_RESERVE_PIXELS;
        for (int column = 0; column < columns; column++) {
            int left = guiWidth - COLUMN_STEP_PIXELS * (column + 1) + inset;
            int top = TOP_MARGIN_PIXELS;
            while (left >= 0 && top + CELL_HEIGHT_PIXELS <= lowest) {
                Area cell = new Area(left, top, FRAME_PIXELS, CELL_HEIGHT_PIXELS);
                OptionalInt clear = clearTop(cell, blocked);
                if (clear.isPresent()) {
                    // 被挡住就顺着往下挪到挡路的东西下面，而不是跳到固定的下一行。
                    top = clear.getAsInt();
                } else {
                    cells.add(cell);
                    top += ROW_STEP_PIXELS;
                }
            }
        }
        return List.copyOf(cells);
    }

    private static OptionalInt clearTop(Area cell, List<Area> blocked) {
        // 挤到这个格位的矩形里最靠下的那个决定往下挪多少；没有挤到的返回空。
        return blocked.stream()
                .filter(cell::crowds)
                .mapToInt(area -> area.bottom() + CLEARANCE_PIXELS)
                .max();
    }
}
