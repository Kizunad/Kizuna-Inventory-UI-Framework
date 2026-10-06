package dev.kizuna.inventoryui.client.svg;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 已注册 SVG 的惰性网格缓存，资源重载后同时清除成功与失败结果。 */
public final class SvgRenderer {
    private final Map<String, FrameworkCatalog.SvgAsset> assets;
    private final Map<String, Optional<SvgMesh>> cache = new LinkedHashMap<>();
    private final RestrictedSvgParser parser = new RestrictedSvgParser();
    private final SvgTessellator tessellator = new SvgTessellator();
    private final MinecraftGuiMeshEmitter emitter = new MinecraftGuiMeshEmitter();

    public SvgRenderer(FrameworkCatalog catalog) {
        assets = catalog.svgAssets();
    }

    public boolean draw(
            DrawContext context, String assetId, int x, int y, int width, int height, int tint) {
        // 缺失注册是接入错误；已注册但坏资源只跳过绘制，日志每次重载最多报告一次。
        if (!assets.containsKey(assetId)) {
            throw new IllegalArgumentException("unregistered SVG asset: " + assetId);
        }
        if (width <= 0 || height <= 0) {
            return false;
        }
        var mesh = cache.computeIfAbsent(assetId, this::load);
        if (mesh.isEmpty()) {
            return false;
        }
        var value = mesh.get();
        emitter.emit(context, value, x, y, width / value.width(), height / value.height(), tint);
        return true;
    }

    private Optional<SvgMesh> load(String id) {
        // 流只在首次绘制时打开，不在每帧解析 XML，缓存容量由注册目录界定。
        try (var input =
                MinecraftClient.getInstance()
                        .getResourceManager()
                        .open(new Identifier(assets.get(id).resourceId()))) {
            return Optional.of(tessellator.tessellate(parser.parse(input)));
        } catch (IOException | RuntimeException failure) {
            System.getLogger(SvgRenderer.class.getName())
                    .log(System.Logger.Level.WARNING, "SVG 资源加载失败：" + id, failure);
            return Optional.empty();
        }
    }

    public void reload() {
        cache.clear();
    }
}
