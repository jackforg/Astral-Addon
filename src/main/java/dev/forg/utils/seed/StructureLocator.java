package dev.forg.utils.seed;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructureSet;
import net.minecraft.structure.StructureSet.WeightedEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StructureLocator {
    private StructureLocator() {
    }

    public static List<LootSearchResult> locateLootTargets(
        SeedWorldContext context,
        Collection<SeedStructureSpec> specs,
        BlockPos origin,
        int maxDistanceBlocks,
        int maxResultsPerStructure,
        int maxResults
    ) {
        List<LootSearchResult> results = new ArrayList<>();

        for (SeedStructureSpec spec : specs) {
            results.addAll(locateForSpec(context, spec, origin, maxDistanceBlocks, maxResultsPerStructure));
        }

        results.sort(Comparator.naturalOrder());
        if (results.size() > maxResults) {
            return new ArrayList<>(results.subList(0, maxResults));
        }

        return results;
    }

    private static List<LootSearchResult> locateForSpec(
        SeedWorldContext context,
        SeedStructureSpec spec,
        BlockPos origin,
        int maxDistanceBlocks,
        int maxResults
    ) {
        RegistryEntry.Reference<StructureSet> setEntry = context.getStructureSet(spec.structureSetKey());
        StructurePlacement placement = setEntry.value().placement();

        if (placement instanceof RandomSpreadStructurePlacement randomSpread) {
            return locateRandomSpread(context, spec, setEntry, randomSpread, origin, maxDistanceBlocks, maxResults);
        }

        if (placement instanceof ConcentricRingsStructurePlacement concentricRings) {
            return locateConcentric(context, spec, setEntry, concentricRings, origin, maxDistanceBlocks, maxResults);
        }

        return List.of();
    }

    private static List<LootSearchResult> locateRandomSpread(
        SeedWorldContext context,
        SeedStructureSpec spec,
        RegistryEntry.Reference<StructureSet> setEntry,
        RandomSpreadStructurePlacement placement,
        BlockPos origin,
        int maxDistanceBlocks,
        int maxResults
    ) {
        List<LootSearchResult> results = new ArrayList<>();
        Set<Long> seenChunks = new HashSet<>();

        int spacing = placement.getSpacing();
        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;
        int originRegionX = Math.floorDiv(originChunkX, spacing);
        int originRegionZ = Math.floorDiv(originChunkZ, spacing);
        int maxRegionRadius = Math.max(1, MathHelper.ceil((double) maxDistanceBlocks / (spacing * 16.0D)) + 1);

        for (int radius = 0; radius <= maxRegionRadius && results.size() < maxResults; radius++) {
            for (int regionX = originRegionX - radius; regionX <= originRegionX + radius && results.size() < maxResults; regionX++) {
                addCandidate(context, spec, setEntry, placement, regionX, originRegionZ - radius, origin, maxDistanceBlocks, results, seenChunks);
                if (radius != 0) {
                    addCandidate(context, spec, setEntry, placement, regionX, originRegionZ + radius, origin, maxDistanceBlocks, results, seenChunks);
                }
            }

            for (int regionZ = originRegionZ - radius + 1; regionZ <= originRegionZ + radius - 1 && results.size() < maxResults; regionZ++) {
                addCandidate(context, spec, setEntry, placement, originRegionX - radius, regionZ, origin, maxDistanceBlocks, results, seenChunks);
                if (radius != 0) {
                    addCandidate(context, spec, setEntry, placement, originRegionX + radius, regionZ, origin, maxDistanceBlocks, results, seenChunks);
                }
            }
        }

        results.sort(Comparator.naturalOrder());
        return results;
    }

    private static void addCandidate(
        SeedWorldContext context,
        SeedStructureSpec spec,
        RegistryEntry.Reference<StructureSet> setEntry,
        RandomSpreadStructurePlacement placement,
        int regionX,
        int regionZ,
        BlockPos origin,
        int maxDistanceBlocks,
        List<LootSearchResult> results,
        Set<Long> seenChunks
    ) {
        ChunkPos startChunk = placement.getStartChunk(context.seed(), regionX, regionZ);
        long key = startChunk.toLong();
        if (!seenChunks.add(key)) return;
        if (!context.placementCalculator().canGenerate(setEntry, startChunk.x, startChunk.z, 0)) return;

        addResultIfMatching(context, spec, setEntry, placement, startChunk, origin, maxDistanceBlocks, results);
    }

    private static List<LootSearchResult> locateConcentric(
        SeedWorldContext context,
        SeedStructureSpec spec,
        RegistryEntry.Reference<StructureSet> setEntry,
        ConcentricRingsStructurePlacement placement,
        BlockPos origin,
        int maxDistanceBlocks,
        int maxResults
    ) {
        List<LootSearchResult> results = new ArrayList<>();
        List<ChunkPos> placements = context.placementCalculator().getPlacementPositions(placement);
        if (placements == null) return results;

        for (ChunkPos startChunk : placements) {
            if (results.size() >= maxResults) break;
            addResultIfMatching(context, spec, setEntry, placement, startChunk, origin, maxDistanceBlocks, results);
        }

        results.sort(Comparator.naturalOrder());
        return results;
    }

    private static void addResultIfMatching(
        SeedWorldContext context,
        SeedStructureSpec spec,
        RegistryEntry.Reference<StructureSet> setEntry,
        StructurePlacement placement,
        ChunkPos startChunk,
        BlockPos origin,
        int maxDistanceBlocks,
        List<LootSearchResult> results
    ) {
        RegistryKey<Structure> actualStructure = resolveStructureForStart(context, spec, setEntry, startChunk, placement);
        if (actualStructure == null || !spec.acceptedStructures().contains(actualStructure)) return;

        BlockPos locatePos = placement.getLocatePos(startChunk);
        BlockPos markerPos = spec.createMarkerPos(context, locatePos);
        double distanceSq = distanceSqXZ(origin, markerPos);
        if (distanceSq > (double) maxDistanceBlocks * maxDistanceBlocks) return;

        results.add(new LootSearchResult(spec, startChunk, locatePos, markerPos, distanceSq));
    }

    private static RegistryKey<Structure> resolveStructureForStart(
        SeedWorldContext context,
        SeedStructureSpec spec,
        RegistryEntry.Reference<StructureSet> setEntry,
        ChunkPos startChunk,
        StructurePlacement placement
    ) {
        List<WeightedEntry> entries = new ArrayList<>(setEntry.value().structures());
        if (entries.isEmpty()) return null;

        boolean allAccepted = true;
        RegistryKey<Structure> fallback = null;
        for (WeightedEntry entry : entries) {
            RegistryKey<Structure> key = entry.structure().getKey().orElse(null);
            if (key == null) continue;
            if (fallback == null) fallback = key;
            if (!spec.acceptedStructures().contains(key)) {
                allAccepted = false;
            }
        }

        if (entries.size() == 1 || allAccepted) return fallback;

        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setCarverSeed(context.placementCalculator().getStructureSeed(), startChunk.x, startChunk.z);
        BlockPos locatePos = placement.getLocatePos(startChunk);
        int totalWeight = entries.stream().mapToInt(WeightedEntry::weight).sum();

        while (!entries.isEmpty() && totalWeight > 0) {
            int roll = random.nextInt(totalWeight);
            int selectedIndex = 0;

            for (; selectedIndex < entries.size(); selectedIndex++) {
                roll -= entries.get(selectedIndex).weight();
                if (roll < 0) break;
            }

            WeightedEntry selected = entries.get(Math.min(selectedIndex, entries.size() - 1));
            RegistryKey<Structure> key = selected.structure().getKey().orElse(null);
            if (key != null && isBiomeValid(context, selected.structure(), locatePos)) {
                return key;
            }

            entries.remove(selected);
            totalWeight -= selected.weight();
        }

        return null;
    }

    private static boolean isBiomeValid(SeedWorldContext context, RegistryEntry<Structure> structure, BlockPos locatePos) {
        RegistryEntry<?> biome = context.biomeAt(locatePos);
        return structure.value().getValidBiomes().stream().anyMatch(validBiome -> validBiome.equals(biome));
    }

    private static double distanceSqXZ(BlockPos a, BlockPos b) {
        double dx = (b.getX() + 0.5D) - (a.getX() + 0.5D);
        double dz = (b.getZ() + 0.5D) - (a.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }
}
