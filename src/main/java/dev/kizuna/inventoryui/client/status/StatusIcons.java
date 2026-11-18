package dev.kizuna.inventoryui.client.status;

import com.google.common.collect.Ordering;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kizuna.inventoryui.client.status.StatusLayout.Area;
import dev.kizuna.inventoryui.status.StatusArrangement;
import dev.kizuna.inventoryui.status.StatusArrangement.Merged;
import dev.kizuna.inventoryui.status.StatusArrangement.Overflow;
import dev.kizuna.inventoryui.status.StatusArrangement.Single;
import dev.kizuna.inventoryui.status.StatusArrangement.Slot;
import dev.kizuna.inventoryui.status.StatusCategory;
import dev.kizuna.inventoryui.status.StatusEffectRef;
import dev.kizuna.inventoryui.status.StatusGroup;
import dev.kizuna.inventoryui.status.StatusGroups;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.CommonColors;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import org.lwjgl.glfw.GLFW;

/** 状态效果图标：平时一个效果一格、带剩余时间；放不下才按类合并；悬停或按住键展开。 */
public final class StatusIcons {
    private static final Identifier FRAME = Identifier.withDefaultNamespace("hud/effect_background");
    private static final Identifier FRAME_AMBIENT =
            Identifier.withDefaultNamespace("hud/effect_background_ambient");
    private static final String PEEK_KEY = "key.kizuna_inventory_ui.status_peek";
    private static final String HOURS_KEY = "status.kizuna_inventory_ui.hours";
    private static final String MORE_KEY = "status.kizuna_inventory_ui.more";
    private static final String LEVEL_KEY_PREFIX = "enchantment.level.";
    /** 原版只给 2 到 10 级准备了罗马数字，对应增幅 1 到 9。 */
    private static final int LEVELLED_AMPLIFIER_MIN = 1;
    private static final int LEVELLED_AMPLIFIER_MAX = 9;
    private static final int SECONDS_PER_HOUR = 3600;
    private static final int TICKS_PER_SECOND = 20;
    /** 原版从最后十秒开始让图标变淡并闪动。 */
    private static final int BLINK_SECONDS = 10;
    private static final int BLINK_TICKS = BLINK_SECONDS * TICKS_PER_SECOND;
    /** 剩余不足这么多刻时底色继续淡下去。 */
    private static final float FADE_TICKS = 50.0F;
    private static final float BLINK_BASE_ALPHA = 0.5F;
    private static final float BLINK_PULSE_ALPHA = 0.25F;
    /** 闪动半个周期的刻数。 */
    private static final float BLINK_HALF_PERIOD_TICKS = 5.0F;
    private static final float OPAQUE = 1.0F;
    /** 原版格式化时长时的缩放；这里不缩放。 */
    private static final float FULL_DURATION_SCALE = 1.0F;
    /** 悬停说明超过这个宽度就折行。 */
    private static final int NOTE_WRAP_PIXELS = 170;
    /** 展开列表和图标列之间的空隙，含提示框自己的边。 */
    private static final int PEEK_GAP_PIXELS = 8;
    /** 展开列表离屏幕上沿的距离，含提示框自己的边。 */
    private static final int PEEK_TOP_PIXELS = 6;
    /** 合并图标右下角数字离底框边的距离。 */
    private static final int BADGE_INSET_PIXELS = 2;
    private static KeyMapping peekKey;
    private static Layout lastLayout = new Layout(List.of(), List.of(), List.of());

    private StatusIcons() {}

    /**
     * 最近一帧排出来的结果；只在渲染线程读写，给界面测试和调试核对用。
     *
     * @param cells 用掉的格位，和 {@code slots} 一一对应
     * @param slots 每个格位里放的内容
     * @param blocked 这一帧让开的界面矩形；游戏画面里为空
     */
    public record Layout(
            List<Area> cells,
            List<Slot> slots,
            List<Area> blocked) {}

    public static Layout lastLayout() {
        return lastLayout;
    }

    /** 客户端初始化时调用一次：替换原版图标、接管容器界面、登记展开键。 */
    public static void register(KeyMapping.Category category) {
        peekKey =
                KeyMappingHelper.registerKeyMapping(
                        new KeyMapping(
                                PEEK_KEY,
                                InputConstants.Type.KEYSYM,
                                GLFW.GLFW_KEY_LEFT_ALT,
                                category));
        HudElementRegistry.replaceElement(
                VanillaHudElements.MOB_EFFECTS, vanilla -> StatusIcons::extractHud);
        ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> {
                    // 每次界面初始化后逐屏登记；框架在重新初始化时会清掉旧的逐屏监听。
                    // 挂在界面前景这一步：原版在整屏画完后才统一出提示框，再晚登记的提示不会显示。
                    if (screen instanceof AbstractContainerScreen<?>) {
                        ScreenEvents.afterForeground(screen).register(StatusIcons::extractScreen);
                    }
                });
        ClientTooltipComponentCallback.EVENT.register(
                data -> data instanceof StatusListing listing ? new StatusListingView(listing) : null);
    }

    /** 这一帧要画的东西：每个格位放什么，以及按 id 找回效果实例。 */
    private record Frame(
            List<Placed> placed,
            Map<String, MobEffectInstance> effects) {
        MobEffectInstance effect(StatusEffectRef ref) {
            return effects.get(ref.id());
        }
    }

    private record Placed(
            Area area,
            Slot slot) {}

    private static void extractHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        // 容器界面打开时改由界面那一层画，这里不重复；其他界面和原版一样画在界面底下。
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gui.screen() instanceof AbstractContainerScreen<?>) {
            return;
        }
        List<Area> cells = StatusLayout.hudCells(graphics.guiWidth(), graphics.guiHeight());
        Frame frame = frame(client, cells, List.of());
        paint(graphics, client.font, frame);
        if (peekKey.isDown()) {
            peek(graphics, client.font, frame);
        }
    }

    private static void extractScreen(
            Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        // 界面占着的地方先让开，再排图标；鼠标在哪一格上就出哪一格的提示。
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        List<Area> blocked = StatusBlockers.of((AbstractContainerScreen<?>) screen);
        Frame frame = frame(client, StatusLayout.screenCells(screen.width, screen.height, blocked), blocked);
        paint(graphics, client.font, frame);
        for (Placed placed : frame.placed()) {
            if (placed.area().contains(mouseX, mouseY)) {
                tooltip(graphics, client.font, frame, placed.slot(), mouseX, mouseY);
            }
        }
    }

    private static Frame frame(Minecraft client, List<Area> cells, List<Area> blocked) {
        // 只收要显示图标且登记过的效果；先按原版顺序排好，类内的先后由它决定。
        Map<String, MobEffectInstance> effects = new HashMap<>();
        List<StatusEffectRef> refs = new ArrayList<>();
        for (MobEffectInstance effect : Ordering.natural().sortedCopy(client.player.getActiveEffects())) {
            var key = effect.getEffect().unwrapKey();
            if (effect.showIcon() && key.isPresent()) {
                String id = key.get().identifier().toString();
                effects.put(id, effect);
                refs.add(new StatusEffectRef(id, category(effect)));
            }
        }
        List<Slot> slots = StatusArrangement.arrange(refs, cells.size(), StatusGroups.current());
        List<Placed> placed = new ArrayList<>();
        for (int index = 0; index < slots.size(); index++) {
            placed.add(new Placed(cells.get(index), slots.get(index)));
        }
        lastLayout = new Layout(cells.subList(0, slots.size()), slots, blocked);
        return new Frame(List.copyOf(placed), effects);
    }

    private static StatusCategory category(MobEffectInstance effect) {
        // 没有声明分类的效果靠原版自带的好坏归进兜底类。
        return switch (effect.getEffect().value().getCategory()) {
            case BENEFICIAL -> StatusCategory.BENEFICIAL;
            case HARMFUL -> StatusCategory.HARMFUL;
            case NEUTRAL -> StatusCategory.NEUTRAL;
        };
    }

    private static void paint(GuiGraphicsExtractor graphics, Font font, Frame frame) {
        for (Placed placed : frame.placed()) {
            Area area = placed.area();
            switch (placed.slot()) {
                case Single single -> paintSingle(graphics, font, area, frame.effect(single.effect()));
                case Merged merged -> paintMerged(graphics, font, area, merged);
                case Overflow overflow -> paintOverflow(graphics, font, area, overflow);
            }
        }
    }

    private static void paintSingle(
            GuiGraphicsExtractor graphics, Font font, Area area, MobEffectInstance effect) {
        // 底框、图标、闪动都照原版；多出来的只有图标下面那行剩余时间。
        Identifier frame = effect.isAmbient() ? FRAME_AMBIENT : FRAME;
        frame(graphics, area, frame);
        float alpha = effect.isAmbient() ? OPAQUE : blinkAlpha(effect);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                Hud.getMobEffectSprite(effect.getEffect()),
                area.left() + StatusLayout.ICON_INSET_PIXELS,
                area.top() + StatusLayout.ICON_INSET_PIXELS,
                StatusLayout.ICON_PIXELS,
                StatusLayout.ICON_PIXELS,
                ARGB.white(alpha));
        Optional<Component> time = shortTime(effect);
        if (time.isPresent()) {
            int centre = area.left() + StatusLayout.FRAME_PIXELS / 2;
            int top = area.top() + StatusLayout.FRAME_PIXELS + StatusLayout.TIME_GAP_PIXELS;
            graphics.centeredText(font, time.get(), centre, top, CommonColors.WHITE);
        }
    }

    private static void paintMerged(
            GuiGraphicsExtractor graphics, Font font, Area area, Merged merged) {
        // 合并的格子不写时间、不闪，右下角写里面有几个。
        frame(graphics, area, FRAME);
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                Identifier.parse(merged.group().icon()),
                area.left() + StatusLayout.ICON_INSET_PIXELS,
                area.top() + StatusLayout.ICON_INSET_PIXELS,
                StatusLayout.ICON_PIXELS,
                StatusLayout.ICON_PIXELS);
        String count = Integer.toString(merged.effects().size());
        int right = area.left() + StatusLayout.FRAME_PIXELS - BADGE_INSET_PIXELS;
        int top = area.top() + StatusLayout.FRAME_PIXELS - BADGE_INSET_PIXELS - StatusLayout.TIME_LINE_PIXELS;
        graphics.text(font, count, right - font.width(count), top, CommonColors.WHITE);
    }

    private static void paintOverflow(
            GuiGraphicsExtractor graphics, Font font, Area area, Overflow overflow) {
        // 连合并都放不下时的最后一格：只写还有几个。
        frame(graphics, area, FRAME);
        String count = "+" + effectCount(overflow.slots());
        int centre = area.left() + StatusLayout.FRAME_PIXELS / 2;
        int top = area.top() + (StatusLayout.FRAME_PIXELS - StatusLayout.TIME_LINE_PIXELS) / 2;
        graphics.centeredText(font, count, centre, top, CommonColors.WHITE);
    }

    private static void frame(GuiGraphicsExtractor graphics, Area area, Identifier sprite) {
        graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                sprite,
                area.left(),
                area.top(),
                StatusLayout.FRAME_PIXELS,
                StatusLayout.FRAME_PIXELS);
    }

    private static float blinkAlpha(MobEffectInstance effect) {
        // 和原版同一条曲线：最后十秒整体变淡，再叠一层越来越明显的闪动。
        if (!effect.endsWithin(BLINK_TICKS)) {
            return OPAQUE;
        }
        int remaining = effect.getDuration();
        int elapsedSeconds = BLINK_SECONDS - remaining / TICKS_PER_SECOND;
        float base = Mth.clamp(remaining / FADE_TICKS * BLINK_BASE_ALPHA, 0.0F, BLINK_BASE_ALPHA);
        float pulse =
                Mth.clamp(
                        (float) elapsedSeconds / BLINK_SECONDS * BLINK_PULSE_ALPHA,
                        0.0F,
                        BLINK_PULSE_ALPHA);
        float wave = Mth.cos(remaining * Mth.PI / BLINK_HALF_PERIOD_TICKS);
        return Mth.clamp(base + wave * pulse, 0.0F, OPAQUE);
    }

    private static Optional<Component> shortTime(MobEffectInstance effect) {
        // 图标下面只有一格宽：没有期限的不写，满一小时的只写小时数。
        if (effect.isInfiniteDuration()) {
            return Optional.empty();
        }
        float tickrate = tickrate();
        int hours = (int) (effect.getDuration() / tickrate) / SECONDS_PER_HOUR;
        if (hours > 0) {
            return Optional.of(Component.translatable(HOURS_KEY, hours));
        }
        return Optional.of(Component.literal(StringUtil.formatTickDuration(effect.getDuration(), tickrate)));
    }

    private static float tickrate() {
        return Minecraft.getInstance().level.tickRateManager().tickrate();
    }

    private static void tooltip(
            GuiGraphicsExtractor graphics, Font font, Frame frame, Slot slot, int mouseX, int mouseY) {
        switch (slot) {
            case Single single -> {
                // 单个效果：名字、时间，下面接说明；说明太长就折行。
                MobEffectInstance effect = frame.effect(single.effect());
                List<FormattedCharSequence> lines = new ArrayList<>();
                lines.add(name(effect).getVisualOrderText());
                lines.add(longTime(effect).getVisualOrderText());
                for (Component note : StatusNotes.of(effect)) {
                    lines.addAll(font.split(note, NOTE_WRAP_PIXELS));
                }
                graphics.setTooltipForNextFrame(font, lines, mouseX, mouseY);
            }
            case Merged merged -> {
                // 合并的类：类名做标题，成员一行一个。
                List<StatusListing.Row> rows = new ArrayList<>();
                addMembers(rows, frame, merged);
                listingTooltip(graphics, font, heading(merged.group()), rows, mouseX, mouseY);
            }
            case Overflow overflow -> {
                // 放不下的那些：照它们本来的样子列出来，合并过的带上类名。
                List<StatusListing.Row> rows = new ArrayList<>();
                addSlots(rows, frame, overflow.slots());
                Component title = Component.translatable(MORE_KEY, effectCount(overflow.slots()));
                listingTooltip(graphics, font, title.copy().withStyle(ChatFormatting.YELLOW), rows, mouseX, mouseY);
            }
        }
    }

    private static void listingTooltip(
            GuiGraphicsExtractor graphics,
            Font font,
            Component title,
            List<StatusListing.Row> rows,
            int mouseX,
            int mouseY) {
        graphics.setTooltipForNextFrame(
                font, List.of(title), Optional.of(new StatusListing(rows)), mouseX, mouseY);
    }

    private static void peek(GuiGraphicsExtractor graphics, Font font, Frame frame) {
        // 游戏画面里没有鼠标：按住键时把合并的格子一次全展开，画在图标列的左边。
        List<StatusListing.Row> rows = new ArrayList<>();
        int leftmost = graphics.guiWidth();
        for (Placed placed : frame.placed()) {
            leftmost = Math.min(leftmost, placed.area().left());
            if (!(placed.slot() instanceof Single)) {
                addSlots(rows, frame, List.of(placed.slot()));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        StatusListingView view = new StatusListingView(new StatusListing(rows));
        int width = view.getWidth(font);
        int height = view.getHeight(font);
        int left = leftmost - PEEK_GAP_PIXELS - width;
        TooltipRenderUtil.extractTooltipBackground(graphics, left, PEEK_TOP_PIXELS, width, height, null);
        view.extractImage(font, left, PEEK_TOP_PIXELS, width, height, graphics);
        view.extractText(graphics, font, left, PEEK_TOP_PIXELS);
    }

    private static void addSlots(List<StatusListing.Row> rows, Frame frame, List<Slot> slots) {
        for (Slot slot : slots) {
            switch (slot) {
                case Single single -> rows.add(memberRow(frame.effect(single.effect())));
                case Merged merged -> {
                    rows.add(new StatusListing.Row(Optional.empty(), heading(merged.group()), Component.empty()));
                    addMembers(rows, frame, merged);
                }
                case Overflow overflow -> {
                    Component title = Component.translatable(MORE_KEY, effectCount(overflow.slots()));
                    rows.add(
                            new StatusListing.Row(
                                    Optional.empty(),
                                    title.copy().withStyle(ChatFormatting.YELLOW),
                                    Component.empty()));
                    addSlots(rows, frame, overflow.slots());
                }
            }
        }
    }

    private static void addMembers(List<StatusListing.Row> rows, Frame frame, Merged merged) {
        for (StatusEffectRef ref : merged.effects()) {
            rows.add(memberRow(frame.effect(ref)));
        }
    }

    private static StatusListing.Row memberRow(MobEffectInstance effect) {
        return new StatusListing.Row(
                Optional.of(Hud.getMobEffectSprite(effect.getEffect())), name(effect), longTime(effect));
    }

    private static Component heading(StatusGroup group) {
        return Component.translatable(group.nameKey()).withStyle(ChatFormatting.YELLOW);
    }

    private static Component name(MobEffectInstance effect) {
        // 和原版背包列表同一个写法：二到十级在名字后面加罗马数字。
        MutableComponent name = effect.getEffect().value().getDisplayName().copy();
        int amplifier = effect.getAmplifier();
        if (amplifier >= LEVELLED_AMPLIFIER_MIN && amplifier <= LEVELLED_AMPLIFIER_MAX) {
            name.append(CommonComponents.SPACE)
                    .append(Component.translatable(LEVEL_KEY_PREFIX + (amplifier + 1)));
        }
        return name;
    }

    private static Component longTime(MobEffectInstance effect) {
        // 列表里地方够，用原版的完整写法，没有期限的写无限符号。
        return MobEffectUtil.formatDuration(effect, FULL_DURATION_SCALE, tickrate())
                .copy()
                .withStyle(ChatFormatting.GRAY);
    }

    private static int effectCount(List<Slot> slots) {
        // 合并的格子按成员数算，嵌套的照样往里数。
        int count = 0;
        for (Slot slot : slots) {
            count +=
                    switch (slot) {
                        case Single single -> 1;
                        case Merged merged -> merged.effects().size();
                        case Overflow overflow -> effectCount(overflow.slots());
                    };
        }
        return count;
    }
}
