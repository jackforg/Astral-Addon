package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class OldChunkNotifier extends meteordevelopment.meteorclient.systems.modules.Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> scanDistance = sgGeneral.add(new IntSetting.Builder()
        .name("scan-distance")
        .description("How many chunks around you to scan for old-chunk fluid borders.")
        .defaultValue(8)
        .min(1)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> maxChunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-chunks-per-tick")
        .description("Maximum unscanned chunks to inspect each tick.")
        .defaultValue(6)
        .min(1)
        .sliderMax(32)
        .build()
    );

    private final Setting<Integer> minimumFluidHits = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-fluid-hits")
        .description("Minimum flowing border-fluid hits before a chunk is treated as old.")
        .defaultValue(2)
        .min(1)
        .sliderMax(12)
        .build()
    );

    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Write detected chunks to a csv log in meteor-client/astral.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Render detected old chunks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new meteordevelopment.meteorclient.settings.EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How detected chunks are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(85, 170, 255, 25))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(85, 170, 255, 255))
        .visible(render::get)
        .build()
    );

    private final Set<Long> scanned = new HashSet<>();
    private final List<DetectedChunk> detectedChunks = new ArrayList<>();
    private File logFile;

    public OldChunkNotifier() {
        super(forg.WORLD, "old-chunk-notifier", "Notifies you when loaded chunks look old based on flowing border fluids.");
    }

    @Override
    public void onActivate() {
        scanned.clear();
        detectedChunks.clear();
        logFile = new File(ForgPaths.ensureDataDir(), "old_chunks.csv");
        if (logToFile.get() && !logFile.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                writer.write("timestamp,dimension,chunk_x,chunk_z,fluid_hits\n");
            } catch (IOException e) {
                error("Failed to initialize old chunk log: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDeactivate() {
        scanned.clear();
        detectedChunks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int processed = 0;

        for (int dx = -scanDistance.get(); dx <= scanDistance.get() && processed < maxChunksPerTick.get(); dx++) {
            for (int dz = -scanDistance.get(); dz <= scanDistance.get() && processed < maxChunksPerTick.get(); dz++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                long key = chunkPos.toLong();
                if (!scanned.add(key)) continue;

                WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
                if (chunk == null || chunk.isEmpty()) continue;

                int hits = countBorderFluidHits(chunk);
                if (hits >= minimumFluidHits.get()) {
                    DetectedChunk detected = new DetectedChunk(mc.world.getRegistryKey(), chunkPos, hits);
                    detectedChunks.add(detected);
                    announce(detected);
                    appendLog(detected);
                }

                processed++;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || mc.world == null) return;

        for (DetectedChunk chunk : detectedChunks) {
            if (chunk.dimension != mc.world.getRegistryKey()) continue;

            int startX = chunk.pos.getStartX();
            int startZ = chunk.pos.getStartZ();
            event.renderer.box(new Box(startX, mc.world.getBottomY(), startZ, startX + 16, mc.world.getBottomY() + 1, startZ + 16), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private int countBorderFluidHits(WorldChunk chunk) {
        int hits = 0;
        int maxY = mc.world.getRegistryKey() == World.NETHER ? 126 : 80;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (localX != 0 && localX != 15 && localZ != 0 && localZ != 15) continue;

                for (int y = mc.world.getBottomY(); y <= maxY; y++) {
                    FluidState fluid = chunk.getBlockState(new BlockPos(chunk.getPos().getStartX() + localX, y, chunk.getPos().getStartZ() + localZ)).getFluidState();
                    if (fluid.isEmpty() || fluid.isStill()) continue;
                    hits++;
                }
            }
        }

        return hits;
    }

    private void announce(DetectedChunk chunk) {
        MutableText text = Text.literal("Old chunk likely at ").formatted(Formatting.AQUA)
            .append(ChatUtils.formatCoords(new net.minecraft.util.math.Vec3d(chunk.pos.getStartX() + 8, 64, chunk.pos.getStartZ() + 8)))
            .append(Text.literal(" [" + chunk.hits + " border fluid hits].").formatted(Formatting.GRAY));
        ChatUtils.sendMsg(text);
        DiscordNotifs.pushModuleEvent("OldChunkNotifier", "Likely old chunk at " + chunk.pos.x + ", " + chunk.pos.z + " (" + chunk.hits + " hits)");
    }

    private void appendLog(DetectedChunk chunk) {
        if (!logToFile.get() || logFile == null) return;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(String.format("%s,%s,%d,%d,%d%n",
                timestamp,
                chunk.dimension.getValue(),
                chunk.pos.x,
                chunk.pos.z,
                chunk.hits
            ));
        } catch (IOException e) {
            error("Failed to write old chunk log: " + e.getMessage());
        }
    }

    private record DetectedChunk(RegistryKey<World> dimension, ChunkPos pos, int hits) {}
}
