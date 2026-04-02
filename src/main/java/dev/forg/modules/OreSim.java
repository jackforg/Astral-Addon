package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.oresim.Ore;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OreSim extends Module {
    public enum AirCheck {
        ON_LOAD,
        RECHECK,
        OFF
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOres = settings.createGroup("Ores");

    private final Setting<String> seed = sgGeneral.add(new StringSetting.Builder()
        .name("seed")
        .description("World seed used for ore simulation on multiplayer. Leave blank to use the real singleplayer seed when available.")
        .defaultValue("")
        .onChanged(value -> {
            if (isActive()) reload();
        })
        .build()
    );

    private final Setting<Integer> horizontalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Taxi-cap distance of chunks being shown.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<AirCheck> airCheck = sgGeneral.add(new EnumSetting.Builder<AirCheck>()
        .name("air-check-mode")
        .description("Checks whether predicted ore positions are still hidden.")
        .defaultValue(AirCheck.RECHECK)
        .build()
    );

    private final Setting<Boolean> reloadOnWorldChange = sgGeneral.add(new BoolSetting.Builder()
        .name("reload-on-world-change")
        .description("Clears and recalculates ore positions when you change world or dimension.")
        .defaultValue(true)
        .build()
    );

    private final Map<Long, Map<Ore, Set<Vec3d>>> chunkRenderers = new ConcurrentHashMap<>();
    private Map<RegistryKey<Biome>, List<Ore>> oreConfig;
    private long worldSeed;
    private String lastWorldKey = "";

    public OreSim() {
        super(forg.WORLD, "ore-sim", "Predicts ore vein positions from a known seed.");
        Ore.oreSettings.forEach(sgOres::add);
    }

    @Override
    public void onActivate() {
        reload();
    }

    @Override
    public void onDeactivate() {
        chunkRenderers.clear();
        oreConfig = null;
        lastWorldKey = "";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        String worldKey = currentWorldKey();
        if (reloadOnWorldChange.get() && !worldKey.equals(lastWorldKey)) {
            reload();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || oreConfig == null) return;

        int chunkX = mc.player.getChunkPos().x;
        int chunkZ = mc.player.getChunkPos().z;
        int range = horizontalRadius.get();

        for (int radius = 0; radius <= range; radius++) {
            for (int x = -radius + chunkX; x <= radius + chunkX; x++) {
                renderChunk(x, chunkZ + radius - range, event);
            }

            for (int x = -radius + 1 + chunkX; x < radius + chunkX; x++) {
                renderChunk(x, chunkZ - radius + range + 1, event);
            }
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (airCheck.get() != AirCheck.RECHECK || event.newState.isOpaque()) return;

        long chunkKey = ChunkPos.toLong(event.pos);
        Map<Ore, Set<Vec3d>> ores = chunkRenderers.get(chunkKey);
        if (ores == null) return;

        Vec3d pos = Vec3d.of(event.pos);
        for (Set<Vec3d> orePositions : ores.values()) {
            orePositions.remove(pos);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (oreConfig == null) return;
        doMathOnChunk(event.chunk());
    }

    private void reload() {
        Long resolvedSeed = resolveSeed();
        if (resolvedSeed == null) {
            error("Set a valid world seed before using OreSim.");
            toggle();
            return;
        }

        worldSeed = resolvedSeed;
        oreConfig = Ore.getRegistry(PlayerUtils.getDimension());
        chunkRenderers.clear();
        lastWorldKey = currentWorldKey();

        if (mc.world != null) {
            loadVisibleChunks();
        }
    }

    private Long resolveSeed() {
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return mc.getServer().getOverworld().getSeed();
        }

        String value = seed.get().trim();
        if (value.isEmpty()) return null;

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void loadVisibleChunks() {
        for (Chunk chunk : Utils.chunks(false)) {
            doMathOnChunk(chunk);
        }
    }

    private void renderChunk(int x, int z, Render3DEvent event) {
        long chunkKey = ChunkPos.toLong(x, z);
        Map<Ore, Set<Vec3d>> chunk = chunkRenderers.get(chunkKey);
        if (chunk == null) return;

        for (Map.Entry<Ore, Set<Vec3d>> oreEntry : chunk.entrySet()) {
            if (!oreEntry.getKey().active.get()) continue;

            for (Vec3d pos : oreEntry.getValue()) {
                event.renderer.boxLines(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, oreEntry.getKey().color, 0);
            }
        }
    }

    private void doMathOnChunk(Chunk chunk) {
        ChunkPos chunkPos = chunk.getPos();
        long chunkKey = chunkPos.toLong();
        ClientWorld world = mc.world;

        if (chunkRenderers.containsKey(chunkKey) || world == null || oreConfig == null) return;

        Set<RegistryKey<Biome>> biomes = new HashSet<>();
        ChunkPos.stream(chunkPos, 1).forEach(pos -> {
            Chunk nearbyChunk = world.getChunk(pos.x, pos.z, ChunkStatus.BIOMES, false);
            if (nearbyChunk == null) return;

            for (ChunkSection section : nearbyChunk.getSectionArray()) {
                section.getBiomeContainer().forEachValue(entry -> entry.getKey().ifPresent(biomes::add));
            }
        });

        Set<Ore> oreSet = biomes.stream()
            .flatMap(biome -> getDefaultOres(biome).stream())
            .collect(Collectors.toSet());

        int chunkX = chunkPos.x << 4;
        int chunkZ = chunkPos.z << 4;
        ChunkRandom random = new ChunkRandom(ChunkRandom.RandomProvider.XOROSHIRO.create(0));
        long populationSeed = random.setPopulationSeed(worldSeed, chunkX, chunkZ);
        HashMap<Ore, Set<Vec3d>> renderedOres = new HashMap<>();

        for (Ore ore : oreSet) {
            HashSet<Vec3d> ores = new HashSet<>();

            random.setDecoratorSeed(populationSeed, ore.index, ore.step);
            int repeat = ore.count.get(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1F && random.nextFloat() >= 1 / ore.rarity) continue;

                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.get(random, ore.heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                RegistryKey<Biome> biome = chunk.getBiomeForNoiseGen(x, y, z).getKey().orElse(null);
                if (biome == null || !getDefaultOres(biome).contains(ore)) continue;

                if (ore.scattered) ores.addAll(generateHidden(world, random, origin, ore.size));
                else ores.addAll(generateNormal(world, random, origin, ore.size, ore.discardOnAirChance));
            }

            if (!ores.isEmpty()) renderedOres.put(ore, ores);
        }

        chunkRenderers.put(chunkKey, renderedOres);
    }

    private List<Ore> getDefaultOres(RegistryKey<Biome> biomeKey) {
        if (oreConfig.containsKey(biomeKey)) return oreConfig.get(biomeKey);
        return oreConfig.values().stream().findAny().orElseGet(ArrayList::new);
    }

    private ArrayList<Vec3d> generateNormal(ClientWorld world, ChunkRandom random, BlockPos origin, int veinSize, float discardOnAir) {
        float angle = random.nextFloat() * 3.1415927F;
        float spread = veinSize / 8.0F;
        int padding = MathHelper.ceil(((veinSize / 16.0F) * 2.0F + 1.0F) / 2.0F);
        double startX = origin.getX() + Math.sin(angle) * spread;
        double endX = origin.getX() - Math.sin(angle) * spread;
        double startZ = origin.getZ() + Math.cos(angle) * spread;
        double endZ = origin.getZ() - Math.cos(angle) * spread;
        double startY = origin.getY() + random.nextInt(3) - 2;
        double endY = origin.getY() + random.nextInt(3) - 2;
        int minX = origin.getX() - MathHelper.ceil(spread) - padding;
        int minY = origin.getY() - 2 - padding;
        int minZ = origin.getZ() - MathHelper.ceil(spread) - padding;
        int size = 2 * (MathHelper.ceil(spread) + padding);
        int vertical = 2 * (2 + padding);

        for (int x = minX; x <= minX + size; x++) {
            for (int z = minZ; z <= minZ + size; z++) {
                if (minY <= world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)) {
                    return generateVeinPart(world, random, veinSize, startX, endX, startZ, endZ, startY, endY, minX, minY, minZ, size, vertical, discardOnAir);
                }
            }
        }

        return new ArrayList<>();
    }

    private ArrayList<Vec3d> generateVeinPart(ClientWorld world, ChunkRandom random, int veinSize, double startX, double endX, double startZ, double endZ, double startY, double endY, int x, int y, int z, int size, int height, float discardOnAir) {
        BitSet bitSet = new BitSet(size * height * size);
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        double[] points = new double[veinSize * 4];
        ArrayList<Vec3d> positions = new ArrayList<>();

        for (int i = 0; i < veinSize; i++) {
            float progress = i / (float) veinSize;
            double px = MathHelper.lerp(progress, startX, endX);
            double py = MathHelper.lerp(progress, startY, endY);
            double pz = MathHelper.lerp(progress, startZ, endZ);
            double sizeNoise = random.nextDouble() * veinSize / 16.0D;
            double radius = ((MathHelper.sin(3.1415927F * progress) + 1.0F) * sizeNoise + 1.0D) / 2.0D;

            points[i * 4] = px;
            points[i * 4 + 1] = py;
            points[i * 4 + 2] = pz;
            points[i * 4 + 3] = radius;
        }

        for (int i = 0; i < veinSize - 1; i++) {
            if (points[i * 4 + 3] <= 0.0D) continue;

            for (int j = i + 1; j < veinSize; j++) {
                if (points[j * 4 + 3] <= 0.0D) continue;

                double dx = points[i * 4] - points[j * 4];
                double dy = points[i * 4 + 1] - points[j * 4 + 1];
                double dz = points[i * 4 + 2] - points[j * 4 + 2];
                double dr = points[i * 4 + 3] - points[j * 4 + 3];

                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0D) points[j * 4 + 3] = -1.0D;
                    else points[i * 4 + 3] = -1.0D;
                }
            }
        }

        for (int i = 0; i < veinSize; i++) {
            double radius = points[i * 4 + 3];
            if (radius < 0.0D) continue;

            double px = points[i * 4];
            double py = points[i * 4 + 1];
            double pz = points[i * 4 + 2];
            int minX = Math.max(MathHelper.floor(px - radius), x);
            int minY = Math.max(MathHelper.floor(py - radius), y);
            int minZ = Math.max(MathHelper.floor(pz - radius), z);
            int maxX = Math.max(MathHelper.floor(px + radius), minX);
            int maxY = Math.max(MathHelper.floor(py + radius), minY);
            int maxZ = Math.max(MathHelper.floor(pz + radius), minZ);

            for (int currentX = minX; currentX <= maxX; currentX++) {
                double scaledX = (currentX + 0.5D - px) / radius;
                if (scaledX * scaledX >= 1.0D) continue;

                for (int currentY = minY; currentY <= maxY; currentY++) {
                    double scaledY = (currentY + 0.5D - py) / radius;
                    if (scaledX * scaledX + scaledY * scaledY >= 1.0D) continue;

                    for (int currentZ = minZ; currentZ <= maxZ; currentZ++) {
                        double scaledZ = (currentZ + 0.5D - pz) / radius;
                        if (scaledX * scaledX + scaledY * scaledY + scaledZ * scaledZ >= 1.0D) continue;

                        int bitIndex = currentX - x + (currentY - y) * size + (currentZ - z) * size * height;
                        if (bitSet.get(bitIndex)) continue;

                        bitSet.set(bitIndex);
                        mutable.set(currentX, currentY, currentZ);

                        if (currentY >= -64 && currentY < 320 && (airCheck.get() == AirCheck.OFF || world.getBlockState(mutable).isOpaque())) {
                            if (shouldPlace(world, mutable, discardOnAir, random)) {
                                positions.add(new Vec3d(currentX, currentY, currentZ));
                            }
                        }
                    }
                }
            }
        }

        return positions;
    }

    private boolean shouldPlace(ClientWorld world, BlockPos orePos, float discardOnAir, ChunkRandom random) {
        if (discardOnAir == 0F || (discardOnAir != 1F && random.nextFloat() >= discardOnAir)) return true;

        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(orePos.add(direction.getVector())).isOpaque() && discardOnAir != 1F) {
                return false;
            }
        }

        return true;
    }

    private ArrayList<Vec3d> generateHidden(ClientWorld world, ChunkRandom random, BlockPos origin, int size) {
        ArrayList<Vec3d> positions = new ArrayList<>();
        int count = random.nextInt(size + 1);

        for (int i = 0; i < count; i++) {
            int spread = Math.min(i, 7);
            int x = randomCoord(random, spread) + origin.getX();
            int y = randomCoord(random, spread) + origin.getY();
            int z = randomCoord(random, spread) + origin.getZ();
            BlockPos pos = new BlockPos(x, y, z);

            if ((airCheck.get() == AirCheck.OFF || world.getBlockState(pos).isOpaque()) && shouldPlace(world, pos, 1F, random)) {
                positions.add(new Vec3d(x, y, z));
            }
        }

        return positions;
    }

    private int randomCoord(ChunkRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * size);
    }

    private String currentWorldKey() {
        if (mc.world == null || mc.player == null) return "";
        return Utils.getWorldName() + "|" + mc.world.getRegistryKey().getValue();
    }
}
