package dev.kizuna.inventoryui.client.status;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.CommonColors;

/** 把展开列表画成一行一个：小图标、名字靠左，剩余时间靠右。 */
final class StatusListingView implements ClientTooltipComponent {
    /** 相邻两行的间距。 */
    static final int ROW_PIXELS = 11;
    /** 行首小图标的边长，是原版状态图标的一半。 */
    private static final int MINI_ICON_PIXELS = StatusLayout.ICON_PIXELS / 2;
    /** 小图标和名字之间的空隙。 */
    private static final int ICON_GAP_PIXELS = 3;
    /** 名字和时间之间至少留的空隙。 */
    private static final int TIME_GAP_PIXELS = 8;
    private final StatusListing listing;

    StatusListingView(StatusListing listing) {
        this.listing = listing;
    }

    @Override
    public int getHeight(Font font) {
        return listing.rows().size() * ROW_PIXELS;
    }

    @Override
    public int getWidth(Font font) {
        // 每行各算各的宽度，取最宽的一行。
        int widest = 0;
        for (StatusListing.Row row : listing.rows()) {
            int time = font.width(row.time());
            int tail = time == 0 ? 0 : TIME_GAP_PIXELS + time;
            widest = Math.max(widest, indent(row) + font.width(row.label()) + tail);
        }
        return widest;
    }

    @Override
    public void extractImage(
            Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
        // 小图标和这一行的字顶端对齐。
        int top = y;
        for (StatusListing.Row row : listing.rows()) {
            if (row.icon().isPresent()) {
                graphics.blitSprite(
                        RenderPipelines.GUI_TEXTURED,
                        row.icon().get(),
                        x,
                        top,
                        MINI_ICON_PIXELS,
                        MINI_ICON_PIXELS);
            }
            top += ROW_PIXELS;
        }
    }

    @Override
    public void extractText(GuiGraphicsExtractor graphics, Font font, int x, int y) {
        // 时间统一贴右边，各行对齐成一列。
        int right = x + getWidth(font);
        int top = y;
        for (StatusListing.Row row : listing.rows()) {
            graphics.text(font, row.label(), x + indent(row), top, CommonColors.WHITE);
            graphics.text(font, row.time(), right - font.width(row.time()), top, CommonColors.WHITE);
            top += ROW_PIXELS;
        }
    }

    private static int indent(StatusListing.Row row) {
        // 标题行没有图标，顶格写。
        return row.icon().isPresent() ? MINI_ICON_PIXELS + ICON_GAP_PIXELS : 0;
    }
}
