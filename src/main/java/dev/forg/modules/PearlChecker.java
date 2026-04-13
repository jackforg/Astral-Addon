package dev.forg.modules;

import dev.forg.forg;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PearlChecker extends meteordevelopment.meteorclient.systems.modules.Module {
    private enum LandingMode {
        Off,
        All,
        HostileOnly
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range for pearl alerts.")
        .defaultValue(96)
        .min(8)
        .sliderMax(256)
        .build()
    );

    private final Setting<Boolean> ignoreOwnPearls = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-own-pearls")
        .description("Ignore your own pearls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hostileOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hostile-only")
        .description("Only alert for pearls not owned by friends or yourself.")
        .defaultValue(false)
        .build()
    );

    private final Setting<LandingMode> landingMode = sgGeneral.add(new EnumSetting.Builder<LandingMode>()
        .name("landing-mode")
        .description("Whether to announce where tracked pearls land.")
        .defaultValue(LandingMode.All)
        .build()
    );

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Render tracers to active pearls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Render a box around active pearls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How active pearl boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(box::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(170, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(170, 255, 255, 45))
        .visible(box::get)
        .build()
    );

    private final Map<UUID, TrackedPearl> trackedPearls = new HashMap<>();

    public PearlChecker() {
        super(forg.WORLD, "pearl-checker", "Tracks nearby ender pearls and where they land.");
    }

    @Override
    public void onDeactivate() {
        trackedPearls.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        Set<UUID> activeIds = new HashSet<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) continue;

            UUID uuid = pearl.getUuid();
            activeIds.add(uuid);

            PlayerEntity owner = pearl.getOwner() instanceof PlayerEntity player ? player : null;
            if (shouldIgnore(owner)) continue;
            if (mc.player.squaredDistanceTo(pearl.getEntityPos()) > range.get() * range.get()) continue;

            TrackedPearl tracked = trackedPearls.computeIfAbsent(uuid, ignored -> {
                TrackedPearl created = new TrackedPearl();
                created.ownerName = owner == null ? "unknown" : owner.getGameProfile().name();
                created.hostile = owner == null || !owner.getUuid().equals(mc.player.getUuid());
                created.lastPos = pearl.getEntityPos();
                announcePearl(created.ownerName, pearl.getEntityPos());
                return created;
            });

            tracked.lastPos = pearl.getEntityPos();
            tracked.hostile = owner == null || !owner.getUuid().equals(mc.player.getUuid());
        }

        trackedPearls.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) return false;

            maybeAnnounceLanding(entry.getValue());
            return true;
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl)) continue;

            PlayerEntity owner = pearl.getOwner() instanceof PlayerEntity player ? player : null;
            if (shouldIgnore(owner)) continue;
            if (mc.player.squaredDistanceTo(pearl.getEntityPos()) > range.get() * range.get()) continue;

            if (tracer.get()) {
                Vec3d eyePos = mc.player.getEyePos();
                Vec3d pearlPos = pearl.getEntityPos();
                event.renderer.line(eyePos.x, eyePos.y, eyePos.z, pearlPos.x, pearlPos.y, pearlPos.z, lineColor.get());
            }

            if (box.get()) {
                event.renderer.box(pearl.getBoundingBox(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private boolean shouldIgnore(PlayerEntity owner) {
        if (owner != null && ignoreOwnPearls.get() && owner.getUuid().equals(mc.player.getUuid())) return true;
        return hostileOnly.get() && owner != null && owner.getUuid().equals(mc.player.getUuid());
    }

    private void announcePearl(String ownerName, Vec3d pos) {
        MutableText text = Text.literal("Pearl from ").formatted(Formatting.AQUA)
            .append(Text.literal(ownerName).formatted(Formatting.YELLOW))
            .append(Text.literal(" at "))
            .append(ChatUtils.formatCoords(pos))
            .append(Text.literal("."));
        ChatUtils.sendMsg(text);
        DiscordNotifs.pushModuleEvent("PearlChecker", ownerName + " threw a pearl at " + formatPos(pos));
    }

    private void maybeAnnounceLanding(TrackedPearl tracked) {
        if (landingMode.get() == LandingMode.Off) return;
        if (landingMode.get() == LandingMode.HostileOnly && !tracked.hostile) return;
        if (tracked.lastPos == null) return;

        MutableText text = Text.literal("Pearl landed for ").formatted(Formatting.AQUA)
            .append(Text.literal(tracked.ownerName).formatted(Formatting.YELLOW))
            .append(Text.literal(" near "))
            .append(ChatUtils.formatCoords(tracked.lastPos))
            .append(Text.literal("."));
        ChatUtils.sendMsg(text);
        DiscordNotifs.pushModuleEvent("PearlChecker", tracked.ownerName + " pearl landed near " + formatPos(tracked.lastPos));
    }

    private String formatPos(Vec3d pos) {
        return String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z);
    }

    private static class TrackedPearl {
        private String ownerName;
        private boolean hostile;
        private Vec3d lastPos;
    }
}
