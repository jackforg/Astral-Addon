package dev.forg.utils.seed;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public record LootSearchResult(
    SeedStructureSpec spec,
    ChunkPos startChunk,
    BlockPos locatePos,
    BlockPos markerPos,
    double distanceSq
) implements Comparable<LootSearchResult> {
    @Override
    public int compareTo(LootSearchResult other) {
        return Double.compare(distanceSq, other.distanceSq);
    }
}
