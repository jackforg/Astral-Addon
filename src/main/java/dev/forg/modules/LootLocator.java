package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.seed.BaritoneBridge;
import dev.forg.utils.seed.LootSearchResult;
import dev.forg.utils.seed.SeedResolver;
import dev.forg.utils.seed.SeedStructureSpec;
import dev.forg.utils.seed.SeedWorldContext;
import dev.forg.utils.seed.StructureLocator;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
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
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class LootLocator extends Module {
    private static final String WAYPOINT_PREFIX = "[LootLocator]";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgTargets = settings.createGroup("Targets");

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("World seed used for structure searches on multiplayer. Leave blank to use the real singleplayer seed.")
        .defaultValue("")
        .build()
    );

    private final Setting<TargetPreset> targetPreset = sgGeneral.add(new EnumSetting.Builder<TargetPreset>()
        .name("target")
        .description("Quick presets for high-value structure loot targets.")
        .defaultValue(TargetPreset.ENCHANTED_GOLDEN_APPLE)
        .build()
    );

    private final Setting<String> customItemId = sgGeneral.add(new StringSetting.Builder()
        .name("custom-item-id")
        .description("Custom item id to search for inside supported structure loot tables.")
        .defaultValue("minecraft:enchanted_golden_apple")
        .visible(() -> targetPreset.get() == TargetPreset.CUSTOM)
        .build()
    );

    private final Setting<Integer> searchDistance = sgGeneral.add(new IntSetting.Builder()
        .name("search-distance")
        .description("Maximum search distance in blocks.")
        .defaultValue(4096)
        .min(256)
        .sliderRange(512, 16384)
        .build()
    );

    private final Setting<Integer> resultsPerStructure = sgGeneral.add(new IntSetting.Builder()
        .name("results-per-structure")
        .description("How many hits to keep per structure type.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> maxResults = sgGeneral.add(new IntSetting.Builder()
        .name("max-results")
        .description("Maximum number of results to keep after sorting by distance.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 32)
        .build()
    );

    private final Setting<Integer> refreshDistance = sgGeneral.add(new IntSetting.Builder()
        .name("refresh-distance")
        .description("How far you need to move before the search recenters.")
        .defaultValue(256)
        .min(32)
        .sliderRange(64, 2048)
        .build()
    );

    private final Setting<Boolean> reloadOnWorldChange = sgGeneral.add(new BoolSetting.Builder()
        .name("reload-on-world-change")
        .description("Re-runs the structure search when you switch world or dimension.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Prints a summary when the result list changes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderMarkers = sgRender.add(new BoolSetting.Builder()
        .name("render-markers")
        .description("Renders marker boxes at located structure targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderTracers = sgRender.add(new BoolSetting.Builder()
        .name("render-tracers")
        .description("Draws tracers to located structure targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Color for normal loot targets.")
        .defaultValue(new SettingColor(255, 205, 80, 180))
        .build()
    );

    private final Setting<SettingColor> nearestColor = sgRender.add(new ColorSetting.Builder()
        .name("nearest-color")
        .description("Color for the nearest loot target.")
        .defaultValue(new SettingColor(255, 245, 120, 255))
        .build()
    );

    private final Setting<WaypointMode> waypointMode = sgTargets.add(new EnumSetting.Builder<WaypointMode>()
        .name("waypoints")
        .description("How many results to export as Meteor waypoints.")
        .defaultValue(WaypointMode.NEAREST)
        .build()
    );

    private final Setting<Boolean> pathToNearest = sgTargets.add(new BoolSetting.Builder()
        .name("baritone-to-nearest")
        .description("Hands the nearest result to Baritone after each refresh.")
        .defaultValue(false)
        .build()
    );

    private long worldSeed;
    private String lastWorldKey = "";
    private String lastTargetItem = "";
    private BlockPos lastSearchOrigin = BlockPos.ORIGIN;
    private String lastSummarySignature = "";
    private List<LootSearchResult> results = new ArrayList<>();

    public LootLocator() {
        super(forg.WORLD, "loot-locator", "Locates seed-valid structures whose loot tables can roll a target item.");
    }

    @Override
    public void onActivate() {
        refreshSearch(true);
    }

    @Override
    public void onDeactivate() {
        clearWaypoints();
        if (pathToNearest.get()) {
            BaritoneBridge.cancel();
        }
        results = new ArrayList<>();
        lastWorldKey = "";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        String worldKey = currentWorldKey();
        if (reloadOnWorldChange.get() && !worldKey.equals(lastWorldKey)) {
            refreshSearch(false);
            return;
        }

        String currentTarget = resolveTargetItemId();
        if (!currentTarget.equals(lastTargetItem) || distanceSqXZ(lastSearchOrigin, mc.player.getBlockPos()) >= (double) refreshDistance.get() * refreshDistance.get()) {
            refreshSearch(false);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || results.isEmpty()) return;

        Vec3d eyePos = mc.player.getEyePos();
        for (int i = 0; i < results.size(); i++) {
            LootSearchResult result = results.get(i);
            SettingColor renderColor = i == 0 ? nearestColor.get() : markerColor.get();
            BlockPos marker = result.markerPos();

            if (renderMarkers.get()) {
                event.renderer.boxLines(
                    marker.getX() - 1,
                    marker.getY(),
                    marker.getZ() - 1,
                    marker.getX() + 1,
                    marker.getY() + 2,
                    marker.getZ() + 1,
                    renderColor,
                    0
                );
            }

            if (renderTracers.get()) {
                event.renderer.line(
                    eyePos.x,
                    eyePos.y,
                    eyePos.z,
                    marker.getX() + 0.5D,
                    marker.getY() + 0.5D,
                    marker.getZ() + 0.5D,
                    renderColor
                );
            }
        }
    }

    private void refreshSearch(boolean forceNotify) {
        if (mc.player == null || mc.world == null) {
            error("Join a world before using Loot Locator.");
            toggle();
            return;
        }

        Long resolvedSeed = SeedResolver.resolve(mc, seed.get());
        if (resolvedSeed == null) {
            error("Set a valid world seed before using Loot Locator.");
            toggle();
            return;
        }

        String itemId = resolveTargetItemId();
        if (!isValidItemTarget(itemId)) {
            error("Invalid item id: " + itemId);
            toggle();
            return;
        }

        Dimension dimension = PlayerUtils.getDimension();
        List<SeedStructureSpec> specs = Arrays.stream(SeedStructureSpec.values())
            .filter(spec -> spec.supportsDimension(dimension) && spec.supportsItem(itemId))
            .toList();

        if (specs.isEmpty()) {
            clearWaypoints();
            results = new ArrayList<>();
            lastTargetItem = itemId;
            lastWorldKey = currentWorldKey();
            lastSearchOrigin = mc.player.getBlockPos();
            if (chatFeedback.get()) {
                error("No supported " + dimension.name().toLowerCase(Locale.ROOT) + " structures can roll " + itemId + ".");
            }
            return;
        }

        SeedWorldContext context = SeedWorldContext.create(mc.world, dimension, resolvedSeed, mc.player.getBlockPos());
        List<LootSearchResult> newResults = StructureLocator.locateLootTargets(
            context,
            specs,
            mc.player.getBlockPos(),
            searchDistance.get(),
            resultsPerStructure.get(),
            maxResults.get()
        );

        worldSeed = resolvedSeed;
        lastTargetItem = itemId;
        lastWorldKey = currentWorldKey();
        lastSearchOrigin = mc.player.getBlockPos();
        results = newResults;

        syncTargets();

        String signature = buildSummarySignature(newResults);
        if (chatFeedback.get() && (forceNotify || !signature.equals(lastSummarySignature))) {
            if (newResults.isEmpty()) {
                warning("No " + itemId + " structures found within " + searchDistance.get() + " blocks.");
            } else {
                info("Found " + newResults.size() + " " + itemId + " targets: " + summarizeTypes(newResults));
            }
        }
        lastSummarySignature = signature;
    }

    private void syncTargets() {
        clearWaypoints();
        if (results.isEmpty()) return;

        int limit = switch (waypointMode.get()) {
            case OFF -> 0;
            case NEAREST -> 1;
            case ALL -> results.size();
        };

        for (int i = 0; i < limit; i++) {
            LootSearchResult result = results.get(i);
            Waypoint waypoint = new Waypoint.Builder()
                .name(WAYPOINT_PREFIX + " " + result.spec().shortName() + " " + (i + 1))
                .icon(result.spec().waypointIcon())
                .pos(result.markerPos())
                .dimension(PlayerUtils.getDimension())
                .build();
            Waypoints.get().add(waypoint);
        }

        if (pathToNearest.get() && !results.isEmpty()) {
            BlockPos marker = results.get(0).markerPos();
            BaritoneBridge.pathToXZ(marker.getX(), marker.getZ());
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

    private String resolveTargetItemId() {
        if (targetPreset.get() == TargetPreset.CUSTOM) {
            return customItemId.get().trim();
        }

        return targetPreset.get().itemId;
    }

    private boolean isValidItemTarget(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) return false;
        return Registries.ITEM.containsId(id);
    }

    private String summarizeTypes(List<LootSearchResult> found) {
        return found.stream()
            .map(result -> result.spec().displayName())
            .distinct()
            .collect(Collectors.joining(", "));
    }

    private String buildSummarySignature(List<LootSearchResult> found) {
        if (found.isEmpty()) return "empty";

        LootSearchResult nearest = found.get(0);
        return nearest.spec().name() + ":" + nearest.startChunk().toLong() + ":" + found.size();
    }

    private double distanceSqXZ(BlockPos a, BlockPos b) {
        double dx = (b.getX() + 0.5D) - (a.getX() + 0.5D);
        double dz = (b.getZ() + 0.5D) - (a.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private String currentWorldKey() {
        if (mc.world == null) return "";
        return mc.world.getRegistryKey().getValue().toString();
    }

    public List<LootSearchResult> getResultsSnapshot() {
        return List.copyOf(results);
    }

    public LootSearchResult getNearestResult() {
        return results.isEmpty() ? null : results.get(0);
    }

    public String getTargetItemId() {
        return lastTargetItem.isEmpty() ? resolveTargetItemId() : lastTargetItem;
    }

    private enum WaypointMode {
        OFF,
        NEAREST,
        ALL
    }

    private enum TargetPreset {
        ENCHANTED_GOLDEN_APPLE("minecraft:enchanted_golden_apple"),
        ELYTRA("minecraft:elytra"),
        NETHERITE_UPGRADE("minecraft:netherite_upgrade_smithing_template"),
        HEAVY_CORE("minecraft:heavy_core"),
        SILENCE_TRIM("minecraft:silence_armor_trim_smithing_template"),
        CUSTOM("");

        private final String itemId;

        TargetPreset(String itemId) {
            this.itemId = itemId;
        }
    }
}
