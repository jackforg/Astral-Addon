package dev.forg.utils.seed;

import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.structure.StructureSet;

public final class SeedWorldContext {
    private final long seed;
    private final Dimension dimension;
    private final BlockPos origin;
    private final RegistryWrapper.WrapperLookup registryLookup;
    private final RegistryWrapper.Impl<StructureSet> structureSets;
    private final DimensionOptions dimensionOptions;
    private final ChunkGenerator chunkGenerator;
    private final HeightLimitView heightLimitView;
    private final NoiseConfig noiseConfig;
    private final StructurePlacementCalculator placementCalculator;

    private SeedWorldContext(
        long seed,
        Dimension dimension,
        BlockPos origin,
        RegistryWrapper.WrapperLookup registryLookup,
        RegistryWrapper.Impl<StructureSet> structureSets,
        DimensionOptions dimensionOptions,
        ChunkGenerator chunkGenerator,
        HeightLimitView heightLimitView,
        NoiseConfig noiseConfig,
        StructurePlacementCalculator placementCalculator
    ) {
        this.seed = seed;
        this.dimension = dimension;
        this.origin = origin;
        this.registryLookup = registryLookup;
        this.structureSets = structureSets;
        this.dimensionOptions = dimensionOptions;
        this.chunkGenerator = chunkGenerator;
        this.heightLimitView = heightLimitView;
        this.noiseConfig = noiseConfig;
        this.placementCalculator = placementCalculator;
    }

    public static SeedWorldContext create(ClientWorld world, Dimension dimension, long seed, BlockPos origin) {
        RegistryWrapper.WrapperLookup registryLookup = BuiltinRegistries.createWrapperLookup();
        RegistryWrapper.Impl<StructureSet> structureSets = registryLookup.getOrThrow(RegistryKeys.STRUCTURE_SET);
        RegistryWrapper.Impl<WorldPreset> worldPresets = registryLookup.getOrThrow(RegistryKeys.WORLD_PRESET);
        var dimensions = worldPresets.getOrThrow(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder().dimensions();

        DimensionOptions dimensionOptions = switch (dimension) {
            case Overworld -> dimensions.get(DimensionOptions.OVERWORLD);
            case Nether -> dimensions.get(DimensionOptions.NETHER);
            case End -> dimensions.get(DimensionOptions.END);
        };

        ChunkGenerator chunkGenerator = dimensionOptions.chunkGenerator();
        NoiseConfig noiseConfig = createNoiseConfig(registryLookup, chunkGenerator, seed);
        HeightLimitView heightLimitView = createHeightLimitView(world, chunkGenerator);
        StructurePlacementCalculator placementCalculator = StructurePlacementCalculator.create(
            noiseConfig,
            seed,
            chunkGenerator.getBiomeSource(),
            structureSets
        );

        return new SeedWorldContext(
            seed,
            dimension,
            origin,
            registryLookup,
            structureSets,
            dimensionOptions,
            chunkGenerator,
            heightLimitView,
            noiseConfig,
            placementCalculator
        );
    }

    private static NoiseConfig createNoiseConfig(RegistryWrapper.WrapperLookup registryLookup, ChunkGenerator chunkGenerator, long seed) {
        if (!(chunkGenerator instanceof NoiseChunkGenerator noiseChunkGenerator)) {
            throw new IllegalStateException("Seed features require a NoiseChunkGenerator.");
        }

        return NoiseConfig.create(
            noiseChunkGenerator.getSettings().value(),
            registryLookup.getOrThrow(RegistryKeys.NOISE_PARAMETERS),
            seed
        );
    }

    private static HeightLimitView createHeightLimitView(ClientWorld world, ChunkGenerator chunkGenerator) {
        if (chunkGenerator instanceof NoiseChunkGenerator noiseChunkGenerator) {
            GenerationShapeConfig shape = noiseChunkGenerator.getSettings().value().generationShapeConfig();
            return HeightLimitView.create(shape.minimumY(), shape.height());
        }

        return HeightLimitView.create(world.getBottomY(), world.getHeight());
    }

    public long seed() {
        return seed;
    }

    public Dimension dimension() {
        return dimension;
    }

    public BlockPos origin() {
        return origin;
    }

    public RegistryWrapper.WrapperLookup registryLookup() {
        return registryLookup;
    }

    public RegistryEntry.Reference<StructureSet> getStructureSet(RegistryKey<StructureSet> key) {
        return structureSets.getOrThrow(key);
    }

    public ChunkGenerator chunkGenerator() {
        return chunkGenerator;
    }

    public HeightLimitView heightLimitView() {
        return heightLimitView;
    }

    public NoiseConfig noiseConfig() {
        return noiseConfig;
    }

    public StructurePlacementCalculator placementCalculator() {
        return placementCalculator;
    }

    public RegistryEntry<Biome> biomeAt(BlockPos pos) {
        return chunkGenerator.getBiomeSource().getBiome(
            BiomeCoords.fromBlock(pos.getX()),
            BiomeCoords.fromBlock(pos.getY()),
            BiomeCoords.fromBlock(pos.getZ()),
            noiseConfig.getMultiNoiseSampler()
        );
    }

    public int surfaceY(BlockPos pos) {
        return chunkGenerator.getHeight(
            pos.getX(),
            pos.getZ(),
            Heightmap.Type.WORLD_SURFACE_WG,
            heightLimitView,
            noiseConfig
        );
    }
}
