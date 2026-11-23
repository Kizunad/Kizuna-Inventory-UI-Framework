package dev.kizuna.inventoryui.client;

import java.util.Objects;

/** 小窗没被玩家拖过时的排位声明：贴哪一侧、从那一侧数第几列、列内按顺序号从上往下。 */
public record CompactSlot(Edge edge, int column, int order) {
    /** 自动排开的小窗贴着可用区的哪一侧；右侧的列右对齐，放不下时往左另起一列。 */
    public enum Edge { LEFT, RIGHT }

    /** 没有声明排位的小窗接在左侧首列末尾。 */
    public static final CompactSlot UNORDERED = new CompactSlot(0, Integer.MAX_VALUE);

    public CompactSlot {
        Objects.requireNonNull(edge);
        if (column < 0 || order < 0) {
            throw new IllegalArgumentException("invalid compact slot");
        }
    }

    /** 贴左侧的排位。 */
    public CompactSlot(int column, int order) {
        this(Edge.LEFT, column, order);
    }
}
