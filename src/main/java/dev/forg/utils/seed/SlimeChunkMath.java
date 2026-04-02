package dev.forg.utils.seed;

import net.minecraft.util.math.random.ChunkRandom;

public final class SlimeChunkMath {
    private static final long SLIME_SALT = 987234911L;

    private SlimeChunkMath() {
    }

    public static boolean isSlimeChunk(long seed, int chunkX, int chunkZ) {
        return ChunkRandom.getSlimeRandom(chunkX, chunkZ, seed, SLIME_SALT).nextInt(10) == 0;
    }
}
