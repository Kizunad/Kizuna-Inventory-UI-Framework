package dev.kizuna.inventoryui.client;

/** 小窗没被玩家拖过时的排位声明：列号自左起，列内按顺序号从上往下。 */
public record CompactSlot(int column, int order) {
    /** 没有声明排位的小窗接在首列末尾。 */
    public static final CompactSlot UNORDERED = new CompactSlot(0, Integer.MAX_VALUE);

    public CompactSlot {
        if (column < 0 || order < 0) {
            throw new IllegalArgumentException("invalid compact slot");
        }
    }
}
