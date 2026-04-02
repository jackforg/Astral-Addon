package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldEventS2CPacket;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CoordLogger extends Module {
    private enum OutputMode {
        CHAT,
        FILE,
        BOTH
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTeleports = settings.createGroup("Teleports");
    private final SettingGroup sgWorldEvents = settings.createGroup("World Events");

    private final Setting<Double> minDistance = sgGeneral.add(new DoubleSetting.Builder()
            .name("minimum-distance")
            .description("Minimum distance to log event.")
            .min(5)
            .max(100)
            .sliderMin(5)
            .sliderMax(100)
            .defaultValue(10)
            .build()
    );

    private final Setting<OutputMode> outputMode = sgGeneral.add(new EnumSetting.Builder<OutputMode>()
        .name("output-mode")
        .description("Where logged events should go.")
        .defaultValue(OutputMode.BOTH)
        .build()
    );

    private final Setting<Integer> dedupeSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("dedupe-seconds")
        .description("Suppress repeat events of the same type near the same position for this many seconds.")
        .defaultValue(30)
        .min(0)
        .sliderMax(300)
        .build()
    );

    private final Setting<Double> dedupeRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("dedupe-radius")
        .description("Events within this radius are treated as duplicates for logging purposes.")
        .defaultValue(32)
        .min(0)
        .max(512)
        .sliderMin(0)
        .sliderMax(128)
        .build()
    );

    private final Setting<Boolean> players = sgTeleports.add(new BoolSetting.Builder()
            .name("players")
            .description("Logs player teleports.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> wolves = sgTeleports.add(new BoolSetting.Builder()
            .name("wolves")
            .description("Logs wolf teleports.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> enderDragons = sgWorldEvents.add(new BoolSetting.Builder()
            .name("ender-dragons")
            .description("Logs killed ender dragons.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> endPortals = sgWorldEvents.add(new BoolSetting.Builder()
            .name("end-portals")
            .description("Logs opened end portals.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> withers = sgWorldEvents.add(new BoolSetting.Builder()
            .name("withers")
            .description("Logs wither spawns.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Boolean> otherEvents = sgWorldEvents.add(new BoolSetting.Builder()
            .name("other-global-events")
            .description("Logs other global events.")
            .defaultValue(false)
            .build()
    );

    public CoordLogger() {
        super(forg.STASH, "coord-logger", "Logs coordinates of various events. Might not work on Spigot/Paper servers.");
    }

    private final Map<String, LoggedEvent> recentEvents = new HashMap<>();
    private File logFile;

    @Override
    public void onActivate() {
        recentEvents.clear();
        logFile = new File(ForgPaths.ensureDataDir(), "CoordLogger.csv");
        if (!logFile.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write("timestamp,dimension,type,subject,x,y,z\n");
            } catch (IOException e) {
                error("Failed to initialize coord log file: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDeactivate() {
        recentEvents.clear();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof EntityPositionS2CPacket packet) {
            try {
                Entity entity = mc.world.getEntityById(packet.entityId());

                if (entity.getType().equals(EntityType.PLAYER) && players.get()) {
                    Vec3d packetPosition = packet.change().position();
                    Vec3d playerPosition = entity.getEntityPos();

                    if (playerPosition.distanceTo(packetPosition) >= minDistance.get()) {
                        emitEvent("player_teleport", entity.getNameForScoreboard(), packetPosition, "Player '" + entity.getNameForScoreboard() + "' has teleported to ");
                    }
                } else if (entity.getType().equals(EntityType.WOLF) && wolves.get()) {
                    Vec3d packetPosition = packet.change().position();
                    Vec3d wolfPosition = entity.getEntityPos();

                    UUID ownerUuid = ((TameableEntity) entity).getOwnerReference().getUuid();

                    if (ownerUuid != null && wolfPosition.distanceTo(packetPosition) >= minDistance.get()) {
                        emitEvent("wolf_teleport", ownerUuid.toString(), packetPosition, "Wolf has teleported to ");
                    }
                }
            } catch (NullPointerException ignored) {}

        } else if (event.packet instanceof WorldEventS2CPacket worldEventPacket) {
            if (worldEventPacket.isGlobal()) {
                if (PlayerUtils.distanceTo(worldEventPacket.getPos()) <= minDistance.get()) return;

                switch (worldEventPacket.getEventId()) {
                    case 1023:
                        if (withers.get()) emitEvent("wither_spawn", "wither", worldEventPacket.getPos(), "Wither spawned at ");
                        break;
                    case 1038:
                        if (endPortals.get()) emitEvent("end_portal", "end_portal", worldEventPacket.getPos(), "End portal opened at ");
                        break;
                    case 1028:
                        if (enderDragons.get()) emitEvent("dragon_kill", "ender_dragon", worldEventPacket.getPos(), "Ender dragon killed at ");
                        break;
                    default:
                        if (otherEvents.get()) emitEvent("world_event_" + worldEventPacket.getEventId(), "event_" + worldEventPacket.getEventId(), worldEventPacket.getPos(), "Unknown global event at ");
                }
            }
        }
    }

    private void emitEvent(String type, String subject, BlockPos coords, String messagePrefix) {
        emitEvent(type, subject, new Vec3d(coords.getX(), coords.getY(), coords.getZ()), messagePrefix);
    }

    private void emitEvent(String type, String subject, Vec3d coords, String messagePrefix) {
        if (isDuplicate(type, coords)) return;

        String webhookMessage = messagePrefix + String.format("(%.1f, %.1f, %.1f)", coords.x, coords.y, coords.z);
        if (outputMode.get() == OutputMode.CHAT || outputMode.get() == OutputMode.BOTH) {
            info(formatMessage(messagePrefix, coords));
        }

        if (outputMode.get() == OutputMode.FILE || outputMode.get() == OutputMode.BOTH) {
            appendToFile(type, subject, coords);
        }

        DiscordNotifs.pushModuleEvent("CoordLogger", webhookMessage);
    }

    private boolean isDuplicate(String type, Vec3d coords) {
        long now = System.currentTimeMillis();
        recentEvents.entrySet().removeIf(entry -> now - entry.getValue().timestamp > dedupeSeconds.get() * 1000L);

        LoggedEvent existing = recentEvents.get(type);
        if (existing != null && existing.pos.distanceTo(coords) <= dedupeRadius.get()) {
            existing.timestamp = now;
            existing.pos = coords;
            return true;
        }

        recentEvents.put(type, new LoggedEvent(coords, now));
        return false;
    }

    private void appendToFile(String type, String subject, Vec3d coords) {
        if (logFile == null) return;

        String dimension = mc.world != null ? mc.world.getRegistryKey().getValue().toString() : "unknown";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(String.format("%s,%s,%s,%s,%.1f,%.1f,%.1f%n",
                timestamp,
                csvEscape(dimension),
                csvEscape(type),
                csvEscape(subject),
                coords.x,
                coords.y,
                coords.z
            ));
        } catch (IOException e) {
            error("Failed to write coord log: " + e.getMessage());
        }
    }

    private String csvEscape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public MutableText formatMessage(String message, Vec3d coords) {
        MutableText text = Text.literal(message);
        text.append(ChatUtils.formatCoords(coords));
        if (mc.world != null) {
            text.append(Text.literal(" [" + mc.world.getRegistryKey().getValue().getPath() + "]").formatted(Formatting.DARK_GRAY));
        }
        text.append(Formatting.GRAY + ".");
        return text;
    }

    public MutableText formatMessage(String message, BlockPos coords) {
        return formatMessage(message, new Vec3d(coords.getX(), coords.getY(), coords.getZ()));
    }

    private static class LoggedEvent {
        private Vec3d pos;
        private long timestamp;

        private LoggedEvent(Vec3d pos, long timestamp) {
            this.pos = pos;
            this.timestamp = timestamp;
        }
    }
}
