package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.seed.BaritoneBridge;
import dev.forg.utils.seed.SeedResolver;
import dev.forg.utils.seed.SlimeChunkMath;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.waypoints.Waypoint;
import meteordevelopment.meteorclient.systems.waypoints.Waypoints;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public class SlimeChunks extends Module {
    private static final String WAYPOINT_PREFIX = "[SlimeChunks]";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgTargets = settings.createGroup("Targets");

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("Optional per-module seed override. Leave blank to use Astral's default survival seed, or the true singleplayer seed.")
        .defaultValue("7557068879127401510")
        .build()
    );

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("How many chunks around you to render.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 16)
        .build()
    );

    private final Setting<Integer> nearestSearchRadius = sgGeneral.add(new IntSetting.Builder()
        .name("nearest-search-radius")
        .description("How far out to search for the nearest slime chunk.")
        .defaultValue(64)
        .min(4)
        .sliderRange(8, 256)
        .build()
    );

    private final Setting<Boolean> nearestOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("nearest-only")
        .description("Only renders the nearest slime chunk instead of the whole local grid.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> reloadOnWorldChange = sgGeneral.add(new BoolSetting.Builder()
        .name("reload-on-world-change")
        .description("Refreshes the cached seed state when you change world or dimension.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Prints a summary when the nearest slime chunk changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color for regular slime chunks.")
        .defaultValue(new SettingColor(40, 210, 110, 50))
        .build()
    );

    private final Setting<SettingColor> nearestColor = sgRender.add(new ColorSetting.Builder()
        .name("nearest-color")
        .description("Color for the nearest slime chunk.")
        .defaultValue(new SettingColor(120, 255, 160, 130))
        .build()
    );

    private final Setting<Boolean> createNearestWaypoint = sgTargets.add(new BoolSetting.Builder()
        .name("nearest-waypoint")
        .description("Keeps a Meteor waypoint on the nearest slime chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pathToNearest = sgTargets.add(new BoolSetting.Builder()
        .name("baritone-to-nearest")
        .description("Hands the nearest slime chunk to Baritone whenever it updates.")
        .defaultValue(false)
        .build()
    );

    private long worldSeed;
    private String lastWorldKey = "";
    private long lastPlayerChunkKey = Long.MIN_VALUE;
    private ChunkPos nearestChunk;

    public SlimeChunks() {
        super(forg.WORLD, "slime-chunks", "Predicts slime chunks from a known seed, with optional waypoints and Baritone handoff.");
    }

    @Override
    public void onActivate() {
        reload(true);
    }

    @Override
    public void onDeactivate() {
        clearWaypoints();
        if (pathToNearest.get()) {
            BaritoneBridge.cancel();
        }
        nearestChunk = null;
        lastWorldKey = "";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        String worldKey = currentWorldKey();
        if (reloadOnWorldChange.get() && !worldKey.equals(lastWorldKey)) {
            reload(false);
            return;
        }

        long playerChunkKey = mc.player.getChunkPos().toLong();
        if (playerChunkKey != lastPlayerChunkKey) {
            lastPlayerChunkKey = playerChunkKey;
            refreshNearest(false);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || lastWorldKey.isEmpty()) return;

        int bottomY = mc.world.getBottomY();
        int topY = mc.world.getBottomY() + mc.world.getHeight();

        if (nearestOnly.get()) {
            if (nearestChunk != null) renderChunk(nearestChunk, bottomY, topY, nearestColor.get(), event);
            return;
        }

        ChunkPos center = mc.player.getChunkPos();
        int range = chunkRange.get();
        for (int chunkX = center.x - range; chunkX <= center.x + range; chunkX++) {
            for (int chunkZ = center.z - range; chunkZ <= center.z + range; chunkZ++) {
                if (!SlimeChunkMath.isSlimeChunk(worldSeed, chunkX, chunkZ)) continue;

                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                SettingColor renderColor = nearestChunk != null && nearestChunk.equals(chunkPos) ? nearestColor.get() : color.get();
                renderChunk(chunkPos, bottomY, topY, renderColor, event);
            }
        }
    }

    private void renderChunk(ChunkPos chunkPos, int bottomY, int topY, SettingColor renderColor, Render3DEvent event) {
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();
        event.renderer.boxLines(startX, bottomY, startZ, startX + 16, topY, startZ + 16, renderColor, 0);
    }

    private void reload(boolean notify) {
        if (mc.world == null || mc.player == null) {
            error("Join a world before using Slime Chunks.");
            toggle();
            return;
        }

        if (PlayerUtils.getDimension() != Dimension.Overworld) {
            error("Slime Chunks only works in the Overworld.");
            toggle();
            return;
        }

        Long resolvedSeed = SeedResolver.resolve(mc, seed.get());
        if (resolvedSeed == null) {
            error("Set a valid world seed before using Slime Chunks.");
            toggle();
            return;
        }

        worldSeed = resolvedSeed;
        lastWorldKey = currentWorldKey();
        lastPlayerChunkKey = Long.MIN_VALUE;
        refreshNearest(notify);
    }

    private void refreshNearest(boolean notify) {
        if (mc.player == null) return;

        ChunkPos previousNearest = nearestChunk;
        nearestChunk = findNearestSlimeChunk(mc.player.getChunkPos(), nearestSearchRadius.get());
        syncTargets();

        if (notify && chatFeedback.get() && nearestChunk != null) {
            info("Nearest slime chunk: " + nearestChunk.x + ", " + nearestChunk.z);
        } else if (!notify && chatFeedback.get() && nearestChunk != null && !nearestChunk.equals(previousNearest)) {
            info("Nearest slime chunk moved to " + nearestChunk.x + ", " + nearestChunk.z + ".");
        }
    }

    private ChunkPos findNearestSlimeChunk(ChunkPos origin, int maxRadius) {
        if (SlimeChunkMath.isSlimeChunk(worldSeed, origin.x, origin.z)) return origin;

        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int x = origin.x - radius; x <= origin.x + radius; x++) {
                if (SlimeChunkMath.isSlimeChunk(worldSeed, x, origin.z - radius)) return new ChunkPos(x, origin.z - radius);
                if (SlimeChunkMath.isSlimeChunk(worldSeed, x, origin.z + radius)) return new ChunkPos(x, origin.z + radius);
            }

            for (int z = origin.z - radius + 1; z <= origin.z + radius - 1; z++) {
                if (SlimeChunkMath.isSlimeChunk(worldSeed, origin.x - radius, z)) return new ChunkPos(origin.x - radius, z);
                if (SlimeChunkMath.isSlimeChunk(worldSeed, origin.x + radius, z)) return new ChunkPos(origin.x + radius, z);
            }
        }

        return null;
    }

    private void syncTargets() {
        clearWaypoints();
        if (nearestChunk == null) return;

        if (createNearestWaypoint.get()) {
            BlockPos marker = toMarkerPos(nearestChunk);
            Waypoint waypoint = new Waypoint.Builder()
                .name(WAYPOINT_PREFIX + " nearest")
                .icon("circle")
                .pos(marker)
                .dimension(Dimension.Overworld)
                .build();
            Waypoints.get().add(waypoint);
        }

        if (pathToNearest.get()) {
            BaritoneBridge.pathToXZ(nearestChunk.getCenterX(), nearestChunk.getCenterZ());
        }
    }

    private void clearWaypoints() {
        List<Waypoint> toRemove = new ArrayList<>();
        for (Waypoint waypoint : Waypoints.get()) {
            if (waypoint.name.get().startsWith(WAYPOINT_PREFIX)) {
                toRemove.add(waypoint);
            }
        }
        Waypoints.get().removeAll(toRemove);
    }

    private BlockPos toMarkerPos(ChunkPos chunkPos) {
        int y = mc.player != null ? mc.player.getBlockY() : 64;
        return new BlockPos(chunkPos.getCenterX(), y, chunkPos.getCenterZ());
    }

    private String currentWorldKey() {
        if (mc.world == null) return "";
        return mc.world.getRegistryKey().getValue().toString();
    }

    public List<ChunkPos> getVisibleSlimeChunks(ChunkPos center, int range) {
        List<ChunkPos> chunks = new ArrayList<>();
        if (lastWorldKey.isEmpty()) return chunks;

        for (int chunkX = center.x - range; chunkX <= center.x + range; chunkX++) {
            for (int chunkZ = center.z - range; chunkZ <= center.z + range; chunkZ++) {
                if (SlimeChunkMath.isSlimeChunk(worldSeed, chunkX, chunkZ)) {
                    chunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        return chunks;
    }

    public ChunkPos getNearestChunk() {
        return nearestChunk;
    }
}
