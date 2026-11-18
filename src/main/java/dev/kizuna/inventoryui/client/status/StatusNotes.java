package dev.kizuna.inventoryui.client.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;

/** 鼠标悬停在单个状态图标上时，名字和时间下面接着显示的说明。 */
public final class StatusNotes {
    /** 效果名的语言键加上这个后缀就是它的一句说明；没写就不显示。 */
    public static final String NOTE_SUFFIX = ".note";
    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private StatusNotes() {}

    /** 按效果实例给出说明行；与该效果无关时返回空列表。 */
    @FunctionalInterface
    public interface Source {
        List<Component> lines(MobEffectInstance effect);
    }

    /** 模组在客户端初始化时登记会随数值变化、写不成固定语言键的说明。 */
    public static void register(Source source) {
        SOURCES.add(Objects.requireNonNull(source));
    }

    static List<Component> of(MobEffectInstance effect) {
        // 固定说明在前，登记的来源按登记顺序接在后面。
        List<Component> lines = new ArrayList<>();
        String noteKey = effect.getDescriptionId() + NOTE_SUFFIX;
        if (Language.getInstance().has(noteKey)) {
            lines.add(Component.translatable(noteKey).withStyle(ChatFormatting.GRAY));
        }
        for (Source source : SOURCES) {
            lines.addAll(source.lines(effect));
        }
        return List.copyOf(lines);
    }
}
