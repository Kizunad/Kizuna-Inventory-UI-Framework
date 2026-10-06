package dev.kizuna.inventoryui.client.component;

/** 工作台按最上层组件命中分发释放；坐标为屏幕逻辑像素，不是组件局部坐标。 */
public interface ItemDropTarget {
    boolean dropItem(double screenX, double screenY);
}
