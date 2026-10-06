package dev.kizuna.inventoryui.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个状态流的完整、不可变库存快照，由宿主从权威数据转换。
 *
 * <p>Container 描述容器网格，Item 描述物品本身，Placement 描述物品在容器中的位置。 构造完成即保证身份唯一、容器引用有效且占格没有越界或重叠；窗口和 HUD
 * 可以共享同一快照。
 */
public record InventorySnapshot(
        // 状态流身份；不同连接或库存来源的数据不能仅凭 revision 混用。
        String streamId,
        // 当前完整快照的版本；是否比上一版更新由 InventoryState 检查。
        long revision,
        // 可用的容器定义，不要求固定背包数量或尺寸。
        List<Container> containers,
        // 所有容器内的物品及占格位置，空格无需单独存储。
        List<Placement> placements) {
    public InventorySnapshot {
        // 先检查快照自身的元数据，再复制列表，避免调用方事后修改已发布的内容。
        streamId = required(streamId, "streamId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        containers = List.copyOf(Objects.requireNonNull(containers, "containers must not be null"));
        placements = List.copyOf(Objects.requireNonNull(placements, "placements must not be null"));

        // 按物品矩形检查占格，不按远端容器尺寸分配二维数组，稀疏大容器不会耗尽内存。
        Map<String, Container> byId = new LinkedHashMap<>();
        Map<String, ArrayList<Placement>> occupied = new LinkedHashMap<>();
        for (Container container : containers) {
            if (byId.putIfAbsent(container.id(), container) != null) {
                throw new IllegalArgumentException("duplicate container: " + container.id());
            }
            occupied.put(container.id(), new ArrayList<>());
        }

        // 实例身份在整份快照内唯一，同一物品不能同时出现在两个容器或位置。
        Map<String, Boolean> instances = new LinkedHashMap<>();
        for (Placement placement : placements) {
            if (instances.putIfAbsent(placement.item().instanceId(), true) != null) {
                throw new IllegalArgumentException(
                        "duplicate item instance: " + placement.item().instanceId());
            }
            Container container = byId.get(placement.containerId());
            if (container == null) {
                throw new IllegalArgumentException("missing container: " + placement.containerId());
            }

            // 物品尺寸以未旋转方向保存；只有计算实际占格时才交换宽高。
            int width = placement.rotated() ? placement.item().height() : placement.item().width();
            int height = placement.rotated() ? placement.item().width() : placement.item().height();

            // 提升到 long 后再相加，防止异常的大尺寸溢出后绕过边界检查。
            if ((long) placement.column() + width > container.columns()
                    || (long) placement.row() + height > container.rows()) {
                throw new IllegalArgumentException(
                        "item outside container: " + placement.item().instanceId());
            }

            // 边界已验证，两个占格矩形在横纵两个方向同时相交才表示冲突。
            var cells = occupied.get(container.id());
            for (var other : cells) {
                int otherWidth = other.rotated() ? other.item().height() : other.item().width();
                int otherHeight = other.rotated() ? other.item().width() : other.item().height();
                if (placement.column() < other.column() + otherWidth
                        && other.column() < placement.column() + width
                        && placement.row() < other.row() + otherHeight
                        && other.row() < placement.row() + height) {
                    throw new IllegalArgumentException(
                            "overlapping item: " + placement.item().instanceId());
                }
            }
            cells.add(placement);
        }
    }

    /** 容器的身份与网格尺寸；不持有物品列表，物品位置统一由 Placement 表达。 */
    public record Container(
            // 稳定容器身份，供 Placement 和移动请求引用。
            String id,
            // 展示标题，不参与容器身份匹配。
            String title,
            // 纵向格子数。
            int rows,
            // 横向格子数。
            int columns) {
        public Container {
            // 容器至少有一行一列；空库存用没有 Placement 表示，而不是零尺寸网格。
            id = required(id, "container id");
            title = required(title, "container title");
            if (rows <= 0 || columns <= 0) {
                throw new IllegalArgumentException("container dimensions must be positive");
            }
        }
    }

    /** 物品显示数据；实例 ID 标识具体对象，种类 ID 允许多个实例共用。 */
    public record Item(
            // 移动和其他操作引用的具体物品实例。
            String instanceId,
            // 物品种类或模板身份，不可代替 instanceId 发起操作。
            String itemId,
            // 显示名称。
            String name,
            // 图标纹理资源 ID，由客户端资源管理器解析。
            String iconId,
            // 未旋转时的横向占格数，不是像素尺寸。
            int width,
            // 未旋转时的纵向占格数。
            int height,
            // 当前实例代表的堆叠数量。
            int count) {
        public Item {
            // 快照只保存实际存在的物品；空格不使用数量为零的占位物品。
            instanceId = required(instanceId, "instanceId");
            itemId = required(itemId, "itemId");
            name = required(name, "name");
            iconId = required(iconId, "iconId");
            if (width <= 0 || height <= 0 || count <= 0) {
                throw new IllegalArgumentException("item dimensions and count must be positive");
            }
        }
    }

    /** 物品在某个容器中的占位，坐标从零开始，指向占位矩形的左上角。 */
    public record Placement(
            // 所属容器，必须存在于同一快照的 containers 中。
            String containerId,
            // 左上角所在行，沿纵向向下增长。
            int row,
            // 左上角所在列，沿横向向右增长。
            int column,
            // 为 true 时交换物品宽高来计算占格，不改写 Item 的原始尺寸。
            boolean rotated,
            // 此位置承载的物品实例及显示数据。
            Item item) {
        public Placement {
            // 这里只检查局部字段；容器是否存在、是否越界和重叠由完整快照统一检查。
            containerId = required(containerId, "containerId");
            Objects.requireNonNull(item, "item must not be null");
            if (row < 0 || column < 0) {
                throw new IllegalArgumentException("placement coordinates must be non-negative");
            }
        }
    }

    private static String required(String value, String name) {
        // 仅拒绝缺失和全空白字段，保留宿主提供的原值，不隐式改写身份或显示名称。
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
