package dev.kizuna.inventoryui.client.component;

import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.Size;
import io.wispforest.owo.ui.core.Sizing;
import java.util.Objects;
import java.util.function.BiConsumer;

/** 包装同一棵 XML 组件树，在内容区逻辑尺寸变化时通知模块重新排布。 */
public final class ResponsiveLayout extends FlowLayout {
    private final BiConsumer<Integer, Integer> arrange;
    private int arrangedWidth = -1;
    private int arrangedHeight = -1;

    public ResponsiveLayout(Component content, BiConsumer<Integer, Integer> arrange) {
        // 模块保留组件、选择和滚动状态；框架只提供尺寸通知，不猜测业务断点。
        super(Sizing.fill(100), Sizing.fill(100), Algorithm.VERTICAL);
        this.arrange = Objects.requireNonNull(arrange, "arrange must not be null");
        child(Objects.requireNonNull(content, "content must not be null"));
    }

    @Override
    public void layout(Size space) {
        // 先更新尺寸标记再调整子树，批处理避免嵌套重排时重复发出同一尺寸通知。
        if (width != arrangedWidth || height != arrangedHeight) {
            arrangedWidth = width;
            arrangedHeight = height;
            runAndDeferEvents(() -> arrange.accept(width, height));
        }
        super.layout(space);
    }
}
