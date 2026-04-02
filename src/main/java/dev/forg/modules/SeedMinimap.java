package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.seed.LootSearchResult;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.List;

public class SeedMinimap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVisual = settings.createGroup("Visual");

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

    public SeedMinimap() {
        super(forg.WORLD, "seed-minimap", "Draws a player-centered seed map with slime chunks and loot targets.");
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

            LootLocator lootLocator = Modules.get().get(LootLocator.class);
            if (lootLocator != null && lootLocator.isActive()) {
                text.render(lootLocator.getTargetItemId(), bounds.x, bounds.y + bounds.size + 2, new Color(255, 220, 150));
            }

            text.end();
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

    private record Bounds(double x, double y, double size) {
    }

    private enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}
