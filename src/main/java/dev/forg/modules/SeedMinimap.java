package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.seed.LootSearchResult;
import dev.forg.utils.seed.SeedResolver;
import dev.forg.utils.seed.SeedWorldContext;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.GenericSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dimension;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class SeedMinimap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgBiomes = settings.createGroup("Biomes");
    private final SettingGroup sgVisual = settings.createGroup("Visual");

    private final Setting<String> sharedSeed = sgGeneral.add(new StringSetting.Builder()
        .name("shared-seed")
        .description("Shared world seed used by Astral seed modules on multiplayer. Slime Chunks and Loot Locator fall back to this when their own seed setting is blank.")
        .defaultValue("7557068879127401510")
        .build()
    );

    private final Setting<MapDimension> mapDimension = sgGeneral.add(new EnumSetting.Builder<MapDimension>()
        .name("dimension")
        .description("Which dimension Seed Minimap should render. Current follows the dimension you are standing in.")
        .defaultValue(MapDimension.CURRENT)
        .build()
    );

    private final Setting<Integer> size = sgGeneral.add(new IntSetting.Builder()
        .name("size")
        .description("Size of the minimap in pixels.")
        .defaultValue(160)
        .min(80)
        .sliderRange(100, 280)
        .build()
    );

    private final Setting<Integer> blockRadius = sgGeneral.add(new IntSetting.Builder()
        .name("block-radius")
        .description("How many blocks from the player the minimap covers.")
        .defaultValue(256)
        .min(64)
        .sliderRange(64, 1024)
        .build()
    );

    private final Setting<Anchor> anchor = sgGeneral.add(new EnumSetting.Builder<Anchor>()
        .name("anchor")
        .description("Which screen corner to pin the minimap to.")
        .defaultValue(Anchor.TOP_RIGHT)
        .build()
    );

    private final Setting<Integer> offsetX = sgGeneral.add(new IntSetting.Builder()
        .name("offset-x")
        .description("Horizontal screen offset from the chosen anchor.")
        .defaultValue(14)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Integer> offsetY = sgGeneral.add(new IntSetting.Builder()
        .name("offset-y")
        .description("Vertical screen offset from the chosen anchor.")
        .defaultValue(14)
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<ChunkbaseAction> chunkbase = sgGeneral.add(new GenericSetting.Builder<ChunkbaseAction>()
        .name("chunkbase")
        .description("Open or copy a live Chunkbase seed-map link using the shared seed, selected dimension, and your position.")
        .defaultValue(new ChunkbaseAction())
        .build()
    );

    private final Setting<Boolean> showBiomes = sgBiomes.add(new BoolSetting.Builder()
        .name("show-biomes")
        .description("Renders a lightweight biome color layer from the shared seed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BiomeTargetSelection> biomeTargets = sgBiomes.add(new GenericSetting.Builder<BiomeTargetSelection>()
        .name("biome-targets")
        .description("Select which biome ids should be highlighted on the minimap.")
        .defaultValue(new BiomeTargetSelection())
        .build()
    );

    private final Setting<Integer> biomeSampleStep = sgBiomes.add(new IntSetting.Builder()
        .name("biome-sample-step")
        .description("Distance between sampled biome points in blocks.")
        .defaultValue(32)
        .min(8)
        .sliderRange(8, 96)
        .build()
    );

    private final Setting<Boolean> showGrid = sgVisual.add(new BoolSetting.Builder()
        .name("show-grid")
        .description("Shows chunk-aligned guide lines.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTitle = sgVisual.add(new BoolSetting.Builder()
        .name("show-title")
        .description("Shows a text header above the minimap.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSlimeChunks = sgVisual.add(new BoolSetting.Builder()
        .name("show-slime-chunks")
        .description("Renders slime chunks if the Slime Chunks module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLootTargets = sgVisual.add(new BoolSetting.Builder()
        .name("show-loot-targets")
        .description("Renders loot targets if Loot Locator is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgVisual.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background color for the minimap.")
        .defaultValue(new SettingColor(10, 10, 14, 160))
        .build()
    );

    private final Setting<SettingColor> borderColor = sgVisual.add(new ColorSetting.Builder()
        .name("border-color")
        .description("Border color for the minimap.")
        .defaultValue(new SettingColor(255, 255, 255, 120))
        .build()
    );

    private final Setting<SettingColor> gridColor = sgVisual.add(new ColorSetting.Builder()
        .name("grid-color")
        .description("Chunk grid color.")
        .defaultValue(new SettingColor(255, 255, 255, 40))
        .build()
    );

    private final Setting<SettingColor> playerColor = sgVisual.add(new ColorSetting.Builder()
        .name("player-color")
        .description("Marker color for the player.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> biomeColor = sgVisual.add(new ColorSetting.Builder()
        .name("biome-color")
        .description("Color for matching biome samples.")
        .defaultValue(new SettingColor(100, 175, 255, 115))
        .build()
    );

    private final Setting<SettingColor> nearestBiomeColor = sgVisual.add(new ColorSetting.Builder()
        .name("nearest-biome-color")
        .description("Color for the nearest matching biome sample.")
        .defaultValue(new SettingColor(160, 220, 255, 220))
        .build()
    );

    private final Setting<SettingColor> slimeColor = sgVisual.add(new ColorSetting.Builder()
        .name("slime-color")
        .description("Color for predicted slime chunks.")
        .defaultValue(new SettingColor(60, 210, 110, 110))
        .build()
    );

    private final Setting<SettingColor> nearestSlimeColor = sgVisual.add(new ColorSetting.Builder()
        .name("nearest-slime-color")
        .description("Color for the nearest slime chunk.")
        .defaultValue(new SettingColor(120, 255, 160, 200))
        .build()
    );

    private final Setting<SettingColor> lootColor = sgVisual.add(new ColorSetting.Builder()
        .name("loot-color")
        .description("Color for normal loot results.")
        .defaultValue(new SettingColor(255, 200, 80, 230))
        .build()
    );

    private final Setting<SettingColor> nearestLootColor = sgVisual.add(new ColorSetting.Builder()
        .name("nearest-loot-color")
        .description("Color for the nearest loot result.")
        .defaultValue(new SettingColor(255, 245, 120, 255))
        .build()
    );

    private List<BiomeSample> biomeSamples = List.of();
    private BiomeSample nearestBiomeSample;
    private String currentBiomeId = "";
    private String lastBiomeSignature = "";
    private String lastBiomeWorldKey = "";
    private BlockPos lastBiomeOrigin = BlockPos.ORIGIN;

    public SeedMinimap() {
        super(forg.WORLD, "seed-minimap", "Draws a player-centered seed map with slime chunks and loot targets.");
    }

    public String getSharedSeed() {
        return sharedSeed.get().trim();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        refreshBiomeCacheIfNeeded();
    }

    @EventHandler
    private void onRender(Render2DEvent event) {
        if (mc.player == null) return;

        Bounds bounds = resolveBounds(event);
        double centerX = bounds.x + bounds.size / 2.0D;
        double centerY = bounds.y + bounds.size / 2.0D;
        double blocksPerPixel = (blockRadius.get() * 2.0D) / bounds.size;

        Renderer2D.COLOR.begin();
        Renderer2D.COLOR.quad(bounds.x, bounds.y, bounds.size, bounds.size, backgroundColor.get());
        Renderer2D.COLOR.boxLines(bounds.x, bounds.y, bounds.size, bounds.size, borderColor.get());
        Renderer2D.COLOR.line(centerX, bounds.y, centerX, bounds.y + bounds.size, borderColor.get());
        Renderer2D.COLOR.line(bounds.x, centerY, bounds.x + bounds.size, centerY, borderColor.get());

        if (showGrid.get()) {
            drawGrid(bounds, centerX, centerY, blocksPerPixel);
        }

        if (showBiomes.get()) {
            drawBiomes(bounds, centerX, centerY, blocksPerPixel);
        }

        if (showSlimeChunks.get()) {
            drawSlimeChunks(bounds, centerX, centerY, blocksPerPixel);
        }

        if (showLootTargets.get()) {
            drawLootTargets(bounds, centerX, centerY, blocksPerPixel);
        }

        Renderer2D.COLOR.quad(centerX - 2, centerY - 2, 4, 4, playerColor.get());
        Renderer2D.COLOR.render();

        if (showTitle.get()) {
            TextRenderer text = TextRenderer.get();
            text.begin(1.0D, false, false);
            text.render("Seed Minimap", bounds.x, bounds.y - text.getHeight() - 2, new Color(255, 255, 255));

            double footerY = bounds.y + bounds.size + 2;
            text.render("Dimension: " + resolveDimensionLabel(), bounds.x, footerY, new Color(210, 210, 235));
            footerY += text.getHeight() + 1;

            if (showBiomes.get() && !currentBiomeId.isEmpty()) {
                text.render("Biome: " + prettifyBiomeId(currentBiomeId), bounds.x, footerY, new Color(170, 220, 255));
                footerY += text.getHeight() + 1;
            }

            if (showBiomes.get() && nearestBiomeSample != null) {
                text.render("Target: " + prettifyBiomeId(nearestBiomeSample.biomeId()), bounds.x, footerY, new Color(150, 255, 210));
                footerY += text.getHeight() + 1;
            }

            LootLocator lootLocator = Modules.get().get(LootLocator.class);
            if (lootLocator != null && lootLocator.isActive()) {
                text.render(lootLocator.getTargetItemId(), bounds.x, footerY, new Color(255, 220, 150));
            }

            text.end();
        }
    }

    private void refreshBiomeCacheIfNeeded() {
        if (mc.player == null || mc.world == null) {
            clearBiomeCache();
            return;
        }

        if (!showBiomes.get()) {
            clearBiomeCache();
            return;
        }

        Long resolvedSeed = SeedResolver.resolve(mc, "");
        if (resolvedSeed == null) {
            clearBiomeCache();
            return;
        }

        List<RegistryKey<Biome>> targets = parseBiomeTargets();
        BlockPos playerPos = mc.player.getBlockPos();
        String worldKey = currentWorldKey();
        String signature = buildBiomeSignature(resolvedSeed, worldKey, targets);
        double refreshDistanceSq = (double) biomeSampleStep.get() * biomeSampleStep.get();

        if (!signature.equals(lastBiomeSignature)
            || !worldKey.equals(lastBiomeWorldKey)
            || distanceSqXZ(lastBiomeOrigin, playerPos) >= refreshDistanceSq) {
            refreshBiomeCache(resolvedSeed, targets, playerPos, worldKey, signature);
        }
    }

    private void refreshBiomeCache(long resolvedSeed, List<RegistryKey<Biome>> targets, BlockPos playerPos, String worldKey, String signature) {
        try {
            SeedWorldContext context = SeedWorldContext.createBiomeContext(mc.world, resolveDimension(), resolvedSeed, playerPos);
            List<BiomeSample> samples = new ArrayList<>();
            BiomeSample nearest = null;
            int radius = blockRadius.get();
            int step = biomeSampleStep.get();
            int sampleY = resolveSampleY();
            currentBiomeId = biomeId(context.biomeAt(playerPos));

            for (int x = playerPos.getX() - radius; x <= playerPos.getX() + radius; x += step) {
                for (int z = playerPos.getZ() - radius; z <= playerPos.getZ() + radius; z += step) {
                    BlockPos samplePos = new BlockPos(x, sampleY, z);
                    RegistryEntry<Biome> biomeEntry = context.biomeAt(samplePos);
                    String biomeId = biomeId(biomeEntry);
                    if (biomeId.isEmpty()) continue;

                    double distanceSq = distanceSqXZ(playerPos, samplePos);
                    RegistryKey<Biome> matchedBiome = matchBiome(biomeEntry, targets);
                    BiomeSample sample = new BiomeSample(samplePos, biomeId, getBiomeMapColor(biomeId), matchedBiome != null, distanceSq);
                    samples.add(sample);

                    if (matchedBiome != null && (nearest == null || distanceSq < nearest.distanceSq())) {
                        nearest = sample;
                    }
                }
            }

            biomeSamples = samples;
            nearestBiomeSample = nearest;
            lastBiomeOrigin = playerPos;
            lastBiomeWorldKey = worldKey;
            lastBiomeSignature = signature;
        } catch (RuntimeException ignored) {
            biomeSamples = List.of();
            nearestBiomeSample = null;
            currentBiomeId = "";
            lastBiomeOrigin = playerPos;
            lastBiomeWorldKey = worldKey;
            lastBiomeSignature = signature;
        }
    }

    private void drawGrid(Bounds bounds, double centerX, double centerY, double blocksPerPixel) {
        int playerX = mc.player.getBlockX();
        int playerZ = mc.player.getBlockZ();
        int radius = blockRadius.get();

        int minBlockX = playerX - radius;
        int maxBlockX = playerX + radius;
        int minBlockZ = playerZ - radius;
        int maxBlockZ = playerZ + radius;

        int firstGridX = floorToChunk(minBlockX);
        for (int blockX = firstGridX; blockX <= maxBlockX; blockX += 16) {
            double screenX = centerX + (blockX - playerX) / blocksPerPixel;
            Renderer2D.COLOR.line(screenX, bounds.y, screenX, bounds.y + bounds.size, gridColor.get());
        }

        int firstGridZ = floorToChunk(minBlockZ);
        for (int blockZ = firstGridZ; blockZ <= maxBlockZ; blockZ += 16) {
            double screenY = centerY + (blockZ - playerZ) / blocksPerPixel;
            Renderer2D.COLOR.line(bounds.x, screenY, bounds.x + bounds.size, screenY, gridColor.get());
        }
    }

    private void drawBiomes(Bounds bounds, double centerX, double centerY, double blocksPerPixel) {
        if (mc.player == null || biomeSamples.isEmpty()) return;

        double size = Math.max(4.0D, biomeSampleStep.get() / blocksPerPixel);
        for (BiomeSample sample : biomeSamples) {
            double screenX = centerX + (sample.pos().getX() - mc.player.getX()) / blocksPerPixel;
            double screenY = centerY + (sample.pos().getZ() - mc.player.getZ()) / blocksPerPixel;

            if (screenX + size < bounds.x || screenX - size > bounds.x + bounds.size || screenY + size < bounds.y || screenY - size > bounds.y + bounds.size) continue;
            Renderer2D.COLOR.quad(screenX - size / 2.0D, screenY - size / 2.0D, size, size, sample.color());

            if (sample.target()) {
                double markerSize = Math.max(4.0D, size * 0.45D);
                SettingColor highlight = nearestBiomeSample != null && nearestBiomeSample.equals(sample) ? nearestBiomeColor.get() : biomeColor.get();
                Renderer2D.COLOR.quad(screenX - markerSize / 2.0D, screenY - markerSize / 2.0D, markerSize, markerSize, highlight);
            }
        }
    }

    private void drawSlimeChunks(Bounds bounds, double centerX, double centerY, double blocksPerPixel) {
        SlimeChunks slimeChunks = Modules.get().get(SlimeChunks.class);
        if (slimeChunks == null || !slimeChunks.isActive()) return;

        int chunkRange = Math.max(1, (int) Math.ceil(blockRadius.get() / 16.0D) + 1);
        ChunkPos nearest = slimeChunks.getNearestChunk();
        List<ChunkPos> visible = slimeChunks.getVisibleSlimeChunks(mc.player.getChunkPos(), chunkRange);

        for (ChunkPos chunk : visible) {
            double left = centerX + (chunk.getStartX() - mc.player.getX()) / blocksPerPixel;
            double top = centerY + (chunk.getStartZ() - mc.player.getZ()) / blocksPerPixel;
            double width = 16.0D / blocksPerPixel;
            SettingColor color = nearest != null && nearest.equals(chunk) ? nearestSlimeColor.get() : slimeColor.get();

            if (left > bounds.x + bounds.size || top > bounds.y + bounds.size || left + width < bounds.x || top + width < bounds.y) continue;
            Renderer2D.COLOR.quad(left, top, width, width, color);
        }
    }

    private void drawLootTargets(Bounds bounds, double centerX, double centerY, double blocksPerPixel) {
        LootLocator lootLocator = Modules.get().get(LootLocator.class);
        if (lootLocator == null || !lootLocator.isActive()) return;

        LootSearchResult nearest = lootLocator.getNearestResult();
        List<LootSearchResult> results = lootLocator.getResultsSnapshot();
        for (LootSearchResult result : results) {
            BlockPos marker = result.markerPos();
            double screenX = centerX + (marker.getX() - mc.player.getX()) / blocksPerPixel;
            double screenY = centerY + (marker.getZ() - mc.player.getZ()) / blocksPerPixel;
            SettingColor color = nearest != null && nearest.equals(result) ? nearestLootColor.get() : lootColor.get();

            if (screenX < bounds.x || screenX > bounds.x + bounds.size || screenY < bounds.y || screenY > bounds.y + bounds.size) continue;
            Renderer2D.COLOR.quad(screenX - 2, screenY - 2, 4, 4, color);
        }
    }

    private Bounds resolveBounds(Render2DEvent event) {
        int mapSize = size.get();

        return switch (anchor.get()) {
            case TOP_LEFT -> new Bounds(offsetX.get(), offsetY.get(), mapSize);
            case TOP_RIGHT -> new Bounds(event.screenWidth - mapSize - offsetX.get(), offsetY.get(), mapSize);
            case BOTTOM_LEFT -> new Bounds(offsetX.get(), event.screenHeight - mapSize - offsetY.get(), mapSize);
            case BOTTOM_RIGHT -> new Bounds(event.screenWidth - mapSize - offsetX.get(), event.screenHeight - mapSize - offsetY.get(), mapSize);
        };
    }

    private int floorToChunk(int block) {
        return Math.floorDiv(block, 16) * 16;
    }

    private List<RegistryKey<Biome>> parseBiomeTargets() {
        Set<RegistryKey<Biome>> targets = new LinkedHashSet<>();
        for (String token : biomeTargets.get().selectedBiomeIds()) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;

            Identifier id = Identifier.tryParse(trimmed);
            if (id == null) continue;

            targets.add(RegistryKey.of(RegistryKeys.BIOME, id));
        }

        return new ArrayList<>(targets);
    }

    private RegistryKey<Biome> matchBiome(RegistryEntry<Biome> biomeEntry, List<RegistryKey<Biome>> targets) {
        for (RegistryKey<Biome> target : targets) {
            if (biomeEntry.matchesKey(target)) return target;
        }

        return null;
    }

    private String biomeId(RegistryEntry<Biome> biomeEntry) {
        return biomeEntry.getKey()
            .map(key -> key.getValue().toString())
            .orElse("");
    }

    private int resolveSampleY() {
        if (mc.world == null) return 64;
        return Math.max(mc.world.getBottomY(), Math.min(64, mc.world.getBottomY() + mc.world.getHeight() - 1));
    }

    private Dimension resolveDimension() {
        return switch (mapDimension.get()) {
            case CURRENT -> PlayerUtils.getDimension();
            case OVERWORLD -> Dimension.Overworld;
            case NETHER -> Dimension.Nether;
            case END -> Dimension.End;
        };
    }

    private String resolveDimensionLabel() {
        return switch (resolveDimension()) {
            case Nether -> "Nether";
            case End -> "End";
            default -> "Overworld";
        };
    }

    private String buildBiomeSignature(long resolvedSeed, String worldKey, List<RegistryKey<Biome>> targets) {
        String ids = targets.stream()
            .map(key -> key.getValue().toString())
            .sorted()
            .collect(Collectors.joining(","));

        return resolvedSeed + "|" + worldKey + "|" + resolveDimension() + "|" + blockRadius.get() + "|" + biomeSampleStep.get() + "|" + ids;
    }

    private double distanceSqXZ(BlockPos a, BlockPos b) {
        double dx = (b.getX() + 0.5D) - (a.getX() + 0.5D);
        double dz = (b.getZ() + 0.5D) - (a.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private void clearBiomeCache() {
        biomeSamples = List.of();
        nearestBiomeSample = null;
        currentBiomeId = "";
        lastBiomeSignature = "";
        lastBiomeWorldKey = "";
        lastBiomeOrigin = BlockPos.ORIGIN;
    }

    private String currentWorldKey() {
        if (mc.world == null) return "";
        return mc.world.getRegistryKey().getValue().toString();
    }

    private String prettifyBiomeId(String biomeId) {
        String path = biomeId;
        int separator = biomeId.indexOf(':');
        if (separator >= 0 && separator + 1 < biomeId.length()) {
            path = biomeId.substring(separator + 1);
        }

        return path.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private Color getBiomeMapColor(String biomeId) {
        String path = biomeId.toLowerCase(Locale.ROOT);

        if (path.contains("mushroom")) return new Color(148, 78, 161, 135);
        if (path.contains("cherry")) return new Color(245, 182, 203, 135);
        if (path.contains("badlands")) return new Color(201, 115, 57, 135);
        if (path.contains("desert")) return new Color(232, 214, 138, 135);
        if (path.contains("savanna")) return new Color(190, 178, 84, 135);
        if (path.contains("jungle") || path.contains("bamboo")) return new Color(61, 145, 66, 135);
        if (path.contains("mangrove")) return new Color(51, 112, 78, 135);
        if (path.contains("swamp")) return new Color(70, 104, 78, 135);
        if (path.contains("dark_forest") || path.contains("pale_garden")) return new Color(56, 92, 63, 135);
        if (path.contains("forest")) return new Color(78, 132, 82, 135);
        if (path.contains("taiga") || path.contains("old_growth")) return new Color(74, 114, 91, 135);
        if (path.contains("plains")) return new Color(118, 176, 88, 135);
        if (path.contains("meadow")) return new Color(133, 191, 119, 135);
        if (path.contains("beach")) return new Color(215, 212, 164, 135);
        if (path.contains("stony") || path.contains("windswept") || path.contains("gravelly")) return new Color(136, 142, 148, 135);
        if (path.contains("snow") || path.contains("frozen") || path.contains("ice_spikes") || path.contains("grove") || path.contains("peaks") || path.contains("slopes")) return new Color(220, 232, 242, 140);
        if (path.contains("deep_dark")) return new Color(45, 68, 78, 145);
        if (path.contains("lush_caves")) return new Color(65, 164, 103, 135);
        if (path.contains("dripstone")) return new Color(144, 116, 89, 135);
        if (path.contains("river")) return new Color(71, 119, 208, 135);
        if (path.contains("warm_ocean")) return new Color(58, 166, 203, 135);
        if (path.contains("lukewarm_ocean")) return new Color(53, 146, 191, 135);
        if (path.contains("cold_ocean")) return new Color(63, 112, 191, 135);
        if (path.contains("deep_ocean") || path.contains("ocean")) return new Color(43, 87, 173, 135);
        if (path.contains("nether_wastes")) return new Color(135, 49, 49, 145);
        if (path.contains("warped")) return new Color(46, 132, 122, 145);
        if (path.contains("crimson")) return new Color(155, 43, 68, 145);
        if (path.contains("soul_sand")) return new Color(99, 80, 69, 145);
        if (path.contains("basalt")) return new Color(88, 88, 92, 145);
        if (path.contains("end")) return new Color(208, 203, 142, 135);

        return new Color(110, 140, 110, 135);
    }

    private String buildChunkbaseUrl() {
        Long resolvedSeed = SeedResolver.resolve(mc, "");
        long seed = resolvedSeed != null ? resolvedSeed : 0L;
        int x = mc.player != null ? mc.player.getBlockX() : 0;
        int z = mc.player != null ? mc.player.getBlockZ() : 0;
        String dimension = switch (resolveDimension()) {
            case Nether -> "nether";
            case End -> "end";
            default -> "overworld";
        };

        return "https://www.chunkbase.com/apps/seed-map#seed=" + seed
            + "&platform=java_1_21_9"
            + "&dimension=" + dimension
            + "&x=" + x
            + "&z=" + z
            + "&zoom=0.75";
    }

    private record Bounds(double x, double y, double size) {
    }

    private record BiomeSample(BlockPos pos, String biomeId, Color color, boolean target, double distanceSq) {
    }

    private final class ChunkbaseAction implements meteordevelopment.meteorclient.settings.IGeneric<ChunkbaseAction> {
        @Override
        public ChunkbaseAction set(ChunkbaseAction value) {
            return this;
        }

        @Override
        public ChunkbaseAction copy() {
            return new ChunkbaseAction();
        }

        @Override
        public NbtCompound toTag() {
            return new NbtCompound();
        }

        @Override
        public ChunkbaseAction fromTag(NbtCompound tag) {
            return this;
        }

        @Override
        public WindowScreen createScreen(GuiTheme theme, GenericSetting<ChunkbaseAction> setting) {
            return new ChunkbaseScreen(theme);
        }
    }

    private final class BiomeTargetSelection implements meteordevelopment.meteorclient.settings.IGeneric<BiomeTargetSelection> {
        private final List<String> selectedBiomeIds = new ArrayList<>();

        public List<String> selectedBiomeIds() {
            return List.copyOf(selectedBiomeIds);
        }

        @Override
        public BiomeTargetSelection set(BiomeTargetSelection value) {
            selectedBiomeIds.clear();
            selectedBiomeIds.addAll(value.selectedBiomeIds);
            return this;
        }

        @Override
        public BiomeTargetSelection copy() {
            BiomeTargetSelection copy = new BiomeTargetSelection();
            copy.selectedBiomeIds.addAll(selectedBiomeIds);
            return copy;
        }

        @Override
        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putString("biomes", String.join(",", selectedBiomeIds));
            return tag;
        }

        @Override
        public BiomeTargetSelection fromTag(NbtCompound tag) {
            selectedBiomeIds.clear();
            String stored = tag.getString("biomes", "");
            if (!stored.isEmpty()) {
                for (String token : stored.split(",")) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty() && !selectedBiomeIds.contains(trimmed)) {
                        selectedBiomeIds.add(trimmed);
                    }
                }
            }

            return this;
        }

        @Override
        public WindowScreen createScreen(GuiTheme theme, GenericSetting<BiomeTargetSelection> setting) {
            return new BiomeTargetScreen(theme, setting, this);
        }
    }

    private final class BiomeTargetScreen extends WindowScreen {
        private final GenericSetting<BiomeTargetSelection> setting;
        private final BiomeTargetSelection workingCopy;
        private String filter = "";

        private BiomeTargetScreen(GuiTheme theme, GenericSetting<BiomeTargetSelection> setting, BiomeTargetSelection selection) {
            super(theme, "Biome Targets");
            this.setting = setting;
            this.workingCopy = selection.copy();
        }

        @Override
        public void initWidgets() {
            add(theme.label("Select which biomes Seed Minimap should highlight.", 420)).expandX();
            add(theme.label("Selected: " + workingCopy.selectedBiomeIds().size())).expandX();

            var filterRow = add(theme.horizontalList()).expandX().widget();
            filterRow.add(theme.label("Filter:"));
            WTextBox filterBox = filterRow.add(theme.textBox(filter, "Search biome ids")).expandX().widget();
            filterBox.action = () -> {
                filter = filterBox.get().trim().toLowerCase(Locale.ROOT);
                reload();
            };

            add(theme.label("Tip: use the search box or scroll the full biome list.", 420)).expandX();

            List<String> biomeIds = availableBiomeIds();
            for (String biomeId : biomeIds) {
                if (!filter.isEmpty() && !biomeId.toLowerCase(Locale.ROOT).contains(filter)) continue;

                var row = add(theme.horizontalList()).expandX().widget();
                row.add(theme.label(prettifyBiomeId(biomeId) + " (" + biomeId + ")", 380)).expandX();

                var checkbox = row.add(theme.checkbox(workingCopy.selectedBiomeIds.contains(biomeId))).widget();
                checkbox.action = () -> {
                    if (checkbox.checked) {
                        if (!workingCopy.selectedBiomeIds.contains(biomeId)) workingCopy.selectedBiomeIds.add(biomeId);
                    } else {
                        workingCopy.selectedBiomeIds.remove(biomeId);
                    }

                    workingCopy.selectedBiomeIds.sort(String::compareTo);
                    setting.set(workingCopy.copy());
                    setting.onChanged();
                };
            }

            var buttons = add(theme.horizontalList()).expandX().widget();

            WButton clear = buttons.add(theme.button("Clear")).expandX().widget();
            clear.action = () -> {
                workingCopy.selectedBiomeIds.clear();
                setting.set(workingCopy.copy());
                setting.onChanged();
                reload();
            };

            WButton current = buttons.add(theme.button("Add Current")).expandX().widget();
            current.action = () -> {
                if (!currentBiomeId.isEmpty() && !workingCopy.selectedBiomeIds.contains(currentBiomeId)) {
                    workingCopy.selectedBiomeIds.add(currentBiomeId);
                    workingCopy.selectedBiomeIds.sort(String::compareTo);
                    setting.set(workingCopy.copy());
                    setting.onChanged();
                }
                reload();
            };

            WButton close = buttons.add(theme.button("Close")).expandX().widget();
            close.action = this::close;
        }

        private List<String> availableBiomeIds() {
            Set<String> biomeIds = new LinkedHashSet<>(workingCopy.selectedBiomeIds);
            if (mc.world != null) {
                mc.world.getRegistryManager().getOrThrow(RegistryKeys.BIOME).streamEntries()
                    .map(entry -> entry.getKey().map(key -> key.getValue().toString()).orElse(""))
                    .filter(id -> !id.isEmpty())
                    .forEach(biomeIds::add);
            }

            return biomeIds.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        }
    }

    private final class ChunkbaseScreen extends WindowScreen {
        private ChunkbaseScreen(GuiTheme theme) {
            super(theme, "Chunkbase");
        }

        @Override
        public void initWidgets() {
            String url = buildChunkbaseUrl();

            add(theme.label("Open the full Chunkbase seed map in your browser using Astral's shared seed and your current position.", 420)).expandX();
            add(theme.label("Selected dimension: " + resolveDimensionLabel())).expandX();
            add(theme.label("Version preset: Java 1.21.9 - 1.21.11")).expandX();
            add(theme.label("URL:", true)).expandX();
            add(theme.label(url, 420)).expandX();

            var buttons = add(theme.horizontalList()).expandX().widget();

            WButton open = buttons.add(theme.button("Open Chunkbase")).expandX().widget();
            open.action = () -> Util.getOperatingSystem().open(url);

            WButton copy = buttons.add(theme.button("Copy URL")).expandX().widget();
            copy.action = () -> {
                mc.keyboard.setClipboard(url);
                info("Copied Chunkbase URL to clipboard.");
            };

            WButton close = buttons.add(theme.button("Close")).expandX().widget();
            close.action = this::close;
        }
    }

    private enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    private enum MapDimension {
        CURRENT,
        OVERWORLD,
        NETHER,
        END
    }
}
