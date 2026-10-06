package dev.kizuna.inventoryui.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 背景从资源管理器读取，缺失或损坏时保留主题底色；F3+T 后重新解析。 */
final class WorkspaceBackground {
    private static final Map<String, Size> SIZES = new ConcurrentHashMap<>();
    private static final Logger LOG = Logger.getLogger(WorkspaceBackground.class.getName());

    static void clearCache() {
        // 资源包重载后尺寸及失败记录都可能过期，下一帧重新从资源管理器解析。
        SIZES.clear();
    }

    static void render(
            DrawContext context, FrameworkCatalog.Background background, int width, int height) {
        // 没有选择背景或上次加载失败时保留已经画好的主题底色。
        if (background == null) {
            return;
        }
        Size size = SIZES.computeIfAbsent(background.resourceId(), WorkspaceBackground::readSize);
        if (size.width == 0) {
            return;
        }
        Identifier texture = new Identifier(background.resourceId());
        RenderSystem.enableBlend();
        context.enableScissor(0, 0, width, height);
        try {
            if (background.fit() == FrameworkCatalog.Fit.TILE) {
                for (int y = 0; y < height; y += size.height) {
                    for (int x = 0; x < width; x += size.width) {
                        context.drawTexture(
                                texture,
                                x,
                                y,
                                0,
                                0,
                                size.width,
                                size.height,
                                size.width,
                                size.height);
                    }
                }
            } else {
                // COVER 填满并裁切，CONTAIN 完整显示并留边；两者都保持图片比例。
                double sx = (double) width / size.width;
                double sy = (double) height / size.height;
                double scale =
                        background.fit() == FrameworkCatalog.Fit.COVER
                                ? Math.max(sx, sy)
                                : Math.min(sx, sy);
                int w = Math.max(1, (int) Math.ceil(size.width * scale));
                int h = Math.max(1, (int) Math.ceil(size.height * scale));
                context.drawTexture(
                        texture,
                        (width - w) / 2,
                        (height - h) / 2,
                        w,
                        h,
                        0,
                        0,
                        size.width,
                        size.height,
                        size.width,
                        size.height);
            }
        } finally {
            context.disableScissor();
        }
    }

    static void thumbnail(
            DrawContext context,
            FrameworkCatalog.Background background,
            int x,
            int y,
            int width,
            int height) {
        // 模块资源与本地背景使用相同 COVER 缩略图取景，保持列表视觉一致。
        if (background == null) {
            return;
        }
        var size = SIZES.computeIfAbsent(background.resourceId(), WorkspaceBackground::readSize);
        if (size.width == 0) {
            return;
        }
        double scale = Math.max((double) width / size.width, (double) height / size.height);
        int sw = Math.max(1, (int) Math.round(width / scale));
        int sh = Math.max(1, (int) Math.round(height / scale));
        context.drawTexture(
                new Identifier(background.resourceId()),
                x,
                y,
                width,
                height,
                (size.width - sw) / 2f,
                (size.height - sh) / 2f,
                sw,
                sh,
                size.width,
                size.height);
    }

    private static Size readSize(String id) {
        // 只缓存尺寸，读取用的流和图片在此释放；零尺寸哨兵避免失败资源每帧重复报错。
        try (var stream =
                        MinecraftClient.getInstance()
                                .getResourceManager()
                                .open(new Identifier(id));
                var image = NativeImage.read(stream)) {
            return new Size(image.getWidth(), image.getHeight());
        } catch (IOException | RuntimeException failure) {
            LOG.warning("无法加载背景 " + id + ": " + failure.getMessage());
            return new Size(0, 0);
        }
    }

    private record Size(int width, int height) {}
}
