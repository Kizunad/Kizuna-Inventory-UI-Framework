package dev.kizuna.inventoryui.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.kizuna.inventoryui.workspace.LocalBackgroundLibrary;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** 本地背景纹理有界缓存；刷新、替换和关闭时释放显存。仅客户端线程调用。 */
final class LocalBackgrounds implements AutoCloseable {
    private static final int THUMBNAIL_SIZE = 256;
    private final LocalBackgroundLibrary library;
    private final Map<String, Texture> thumbnails = new LinkedHashMap<>();
    private List<String> entries = List.of();
    private Texture selected;
    private String error = "";

    LocalBackgrounds(Path directory) {
        library = new LocalBackgroundLibrary(directory);
    }

    Path directory() {
        return library.directory();
    }

    List<String> entries() {
        return entries;
    }

    String error() {
        return error;
    }

    void refresh() {
        // 显式刷新释放成功和失败缩略图缓存；主背景保留，选择失败不会让画面突然消失。
        thumbnails.values().forEach(LocalBackgrounds::release);
        thumbnails.clear();
        try {
            entries = library.entries();
            error = "";
        } catch (IOException failure) {
            error = "无法读取背景目录：" + failure.getMessage();
        }
    }

    boolean select(String id) {
        // 新图完全解码并上传后才替换旧图，失败保留当前图像与选择状态。
        try {
            var replacement = upload(library.decode(id), false);
            release(selected);
            selected = replacement;
            error = "";
            return true;
        } catch (IOException | RuntimeException failure) {
            error = "背景无法加载，保留当前图像：" + failure.getMessage();
            return false;
        }
    }

    void render(GuiGraphics context, int width, int height) {
        draw(context, selected, 0, 0, width, height);
    }

    void thumbnail(GuiGraphics context, String id, int x, int y, int width, int height) {
        // 只加载可见条目；空值也缓存，避免损坏图片每帧重复解析。
        if (!thumbnails.containsKey(id) && entries.contains(id)) {
            try {
                thumbnails.put(id, upload(library.decode(id), true));
            } catch (IOException | RuntimeException failure) {
                thumbnails.put(id, null);
            }
        }
        draw(context, thumbnails.get(id), x, y, width, height);
    }

    private static Texture upload(BufferedImage source, boolean thumbnail) {
        // 缩略图限制长边；只将最终像素上传到 GPU，不为每个列表项保留全尺寸纹理。
        double scale =
                thumbnail
                        ? Math.min(
                                1,
                                (double) THUMBNAIL_SIZE
                                        / Math.max(source.getWidth(), source.getHeight()))
                        : 1;
        int width = Math.max(1, (int) (source.getWidth() * scale));
        int height = Math.max(1, (int) (source.getHeight() * scale));
        var image = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb =
                            source.getRGB(
                                    Math.min(source.getWidth() - 1, (int) (x / scale)),
                                    Math.min(source.getHeight() - 1, (int) (y / scale)));
                    // ImageIO 为 ARGB，NativeImage 为 ABGR；用颜色工具转换，避免位掩码散落。
                    image.setPixelRGBA(
                            x,
                            y,
                            net.minecraft.util.FastColor.ABGR32.color(
                                    net.minecraft.util.FastColor.ARGB32.alpha(argb),
                                    net.minecraft.util.FastColor.ARGB32.blue(argb),
                                    net.minecraft.util.FastColor.ARGB32.green(argb),
                                    net.minecraft.util.FastColor.ARGB32.red(argb)));
                }
            }
        } catch (RuntimeException | Error failure) {
            image.close();
            throw failure;
        }
        // 从此处起像素所有权交给纹理对象，失败路径只由纹理关闭一次。
        var texture = new DynamicTexture(image);
        try {
            var id =
                    Minecraft.getInstance()
                            .getTextureManager()
                            .register("kiui-background", texture);
            return new Texture(id, width, height);
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    private static void draw(
            GuiGraphics context, Texture texture, int x, int y, int width, int height) {
        // COVER 直接裁切源区域，不依赖外层 scissor，缩略图也可嵌入滚动列表。
        if (texture == null) {
            return;
        }
        double scale =
                Math.max((double) width / texture.width(), (double) height / texture.height());
        int sw = Math.max(1, (int) Math.round(width / scale));
        int sh = Math.max(1, (int) Math.round(height / scale));
        context.blit(
                texture.id(),
                x,
                y,
                width,
                height,
                (texture.width() - sw) / 2f,
                (texture.height() - sh) / 2f,
                sw,
                sh,
                texture.width(),
                texture.height());
    }

    private static void release(Texture texture) {
        // 空值代表加载失败，无纹理需要释放。
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture.id());
        }
    }

    @Override
    public void close() {
        thumbnails.values().forEach(LocalBackgrounds::release);
        thumbnails.clear();
        release(selected);
        selected = null;
    }

    private record Texture(ResourceLocation id, int width, int height) {}
}
