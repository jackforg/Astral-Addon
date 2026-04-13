package dev.forg.modules;

import dev.forg.forg;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SoundLocator extends meteordevelopment.meteorclient.systems.modules.Module {
    private enum SoundType {
        Explosion,
        EnderPearl,
        Totem,
        RespawnAnchor,
        Portal
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum range for sound alerts.")
        .defaultValue(128)
        .min(8)
        .sliderMax(512)
        .build()
    );

    private final Setting<Boolean> explosions = sgGeneral.add(new BoolSetting.Builder()
        .name("explosions")
        .description("Track explosion sounds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pearls = sgGeneral.add(new BoolSetting.Builder()
        .name("ender-pearls")
        .description("Track ender pearl throw sounds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> totems = sgGeneral.add(new BoolSetting.Builder()
        .name("totems")
        .description("Track totem pop sounds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> anchors = sgGeneral.add(new BoolSetting.Builder()
        .name("respawn-anchors")
        .description("Track respawn anchor sounds.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> portals = sgGeneral.add(new BoolSetting.Builder()
        .name("portal-sounds")
        .description("Track portal use sounds.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> dedupeRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("dedupe-radius")
        .description("Ignore repeat hits of the same sound type inside this radius.")
        .defaultValue(8)
        .min(0)
        .sliderMax(64)
        .build()
    );

    private final Setting<Integer> keepSeconds = sgRender.add(new IntSetting.Builder()
        .name("keep-seconds")
        .description("How long to render sound hits.")
        .defaultValue(12)
        .min(1)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Render tracers to recent sound hits.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> box = sgRender.add(new BoolSetting.Builder()
        .name("box")
        .description("Render boxes at recent sound hits.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How sound hit boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(box::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 255, 85, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 255, 85, 35))
        .visible(box::get)
        .build()
    );

    private final List<SoundHit> hits = new ArrayList<>();

    public SoundLocator() {
        super(forg.WORLD, "sound-locator", "Turns important sound packets into location alerts.");
    }

    @Override
    public void onDeactivate() {
        hits.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;

        SoundSnapshot snapshot = null;
        if (event.packet instanceof PlaySoundS2CPacket packet) snapshot = SoundSnapshot.from(packet);
        else if (event.packet instanceof PlaySoundFromEntityS2CPacket packet) snapshot = SoundSnapshot.from(packet, mc.world.getEntityById(packet.getEntityId()));

        if (snapshot == null || snapshot.pos == null) return;
        if (mc.player.squaredDistanceTo(snapshot.pos) > range.get() * range.get()) return;
        if (snapshot.category == SoundCategory.MASTER) return;

        SoundType type = classify(snapshot.soundId);
        if (type == null) return;
        if (isDuplicate(type, snapshot.pos)) return;

        hits.add(new SoundHit(type, snapshot.soundId, snapshot.pos, System.currentTimeMillis() + keepSeconds.get() * 1000L));
        announce(type, snapshot.soundId, snapshot.pos);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        Iterator<SoundHit> iterator = hits.iterator();
        while (iterator.hasNext()) {
            SoundHit hit = iterator.next();
            if (hit.expiresAt < now) {
                iterator.remove();
                continue;
            }

            if (tracer.get()) {
                Vec3d eyePos = mc.player.getEyePos();
                event.renderer.line(eyePos.x, eyePos.y, eyePos.z, hit.pos.x, hit.pos.y, hit.pos.z, lineColor.get());
            }

            if (box.get()) {
                event.renderer.box(new Box(hit.pos.x - 0.5, hit.pos.y - 0.5, hit.pos.z - 0.5, hit.pos.x + 0.5, hit.pos.y + 0.5, hit.pos.z + 0.5), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private SoundType classify(String soundId) {
        if (soundId == null) return null;

        if (explosions.get() && soundId.contains("explode")) return SoundType.Explosion;
        if (pearls.get() && soundId.contains("ender_pearl.throw")) return SoundType.EnderPearl;
        if (totems.get() && soundId.contains("totem.use")) return SoundType.Totem;
        if (anchors.get() && soundId.contains("respawn_anchor")) return SoundType.RespawnAnchor;
        if (portals.get() && (soundId.contains("portal.travel") || soundId.contains("portal.trigger"))) return SoundType.Portal;
        return null;
    }

    private boolean isDuplicate(SoundType type, Vec3d pos) {
        long now = System.currentTimeMillis();
        hits.removeIf(hit -> hit.expiresAt < now);

        for (SoundHit hit : hits) {
            if (hit.type != type) continue;
            if (hit.pos.distanceTo(pos) <= dedupeRadius.get()) return true;
        }

        return false;
    }

    private void announce(SoundType type, String soundId, Vec3d pos) {
        MutableText text = Text.literal(type.name()).formatted(Formatting.YELLOW)
            .append(Text.literal(" sound at ").formatted(Formatting.GRAY))
            .append(ChatUtils.formatCoords(pos))
            .append(Text.literal(" (" + soundId + ").").formatted(Formatting.DARK_GRAY));
        ChatUtils.sendMsg(text);
        DiscordNotifs.pushModuleEvent("SoundLocator", type.name() + " sound at " + String.format("%.1f, %.1f, %.1f", pos.x, pos.y, pos.z));
    }

    private record SoundHit(SoundType type, String soundId, Vec3d pos, long expiresAt) {}

    private record SoundSnapshot(String soundId, SoundCategory category, Vec3d pos) {
        private static SoundSnapshot from(PlaySoundS2CPacket packet) {
            return new SoundSnapshot(packet.getSound().value().id().toString(), packet.getCategory(), new Vec3d(packet.getX(), packet.getY(), packet.getZ()));
        }

        private static SoundSnapshot from(PlaySoundFromEntityS2CPacket packet, Entity entity) {
            if (entity == null) return null;
            return new SoundSnapshot(packet.getSound().value().id().toString(), packet.getCategory(), entity.getEntityPos());
        }
    }
}
