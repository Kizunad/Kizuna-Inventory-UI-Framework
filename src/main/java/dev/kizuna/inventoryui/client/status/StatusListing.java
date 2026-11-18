package dev.kizuna.inventoryui.client.status;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * 展开列表的内容；既放进提示框，也直接画在游戏画面上。
 *
 * @param rows 从上到下的各行
 */
public record StatusListing(List<Row> rows) implements TooltipComponent {
    public StatusListing {
        rows = List.copyOf(rows);
    }

    /**
     * 列表里的一行。
     *
     * @param icon 行首的小图标；标题行没有
     * @param label 类名或效果名
     * @param time 剩余时间；标题行为空文字
     */
    public record Row(
            Optional<Identifier> icon,
            Component label,
            Component time) {}
}
