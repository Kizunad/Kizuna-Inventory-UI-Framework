package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.workspace.IconStyle;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;

/**
 * 按「图标风格 + 图标 ID」找贴图：图标 ID 的命名空间是出图的模块，路径是图标名。
 * 某种风格缺图时报出风格、图标和贴图路径，仍返回这种风格的路径，不换成别的风格。
 */
public final class StyledIcons {
    private static final String DIRECTORY = "textures/gui/icons/";
    private static final String EXTENSION = ".png";
    private final Predicate<Identifier> exists;
    private final Consumer<String> report;
    private final Set<Identifier> present = new HashSet<>();
    private final Set<Identifier> missing = new HashSet<>();

    public StyledIcons(Predicate<Identifier> exists, Consumer<String> report) {
        this.exists = exists;
        this.report = report;
    }

    /** 这种风格下这个图标的贴图路径；不检查贴图在不在。 */
    public static Identifier path(IconStyle style, Identifier icon) {
        String file = DIRECTORY + style.id() + "/" + icon.getPath() + EXTENSION;
        return Identifier.fromNamespaceAndPath(icon.getNamespace(), file);
    }

    /** 画图标前取贴图；同一张缺图在一次资源加载里只报一次。 */
    public Identifier texture(IconStyle style, Identifier icon) {
        var texture = path(style, icon);
        if (present.contains(texture) || missing.contains(texture)) {
            return texture;
        }
        if (exists.test(texture)) {
            present.add(texture);
            return texture;
        }
        missing.add(texture);
        report.accept(message(style, icon, texture));
        return texture;
    }

    /** 已经报过的缺图；外观菜单和用例用它核对。 */
    public Set<Identifier> missing() {
        return Set.copyOf(missing);
    }

    /** 资源重新加载后重新检查每张贴图。 */
    public void reload() {
        present.clear();
        missing.clear();
    }

    static String message(IconStyle style, Identifier icon, Identifier texture) {
        return "图标风格 " + style.id() + " 缺少图标 " + icon + "，应有贴图 " + texture;
    }
}
