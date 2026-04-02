package dev.forg.utils.seed;

import net.minecraft.client.MinecraftClient;

public final class SeedResolver {
    private SeedResolver() {
    }

    public static Long resolve(MinecraftClient mc, String seedText) {
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return mc.getServer().getOverworld().getSeed();
        }

        String trimmed = seedText == null ? "" : seedText.trim();
        if (trimmed.isEmpty()) return null;

        try {
            return Long.parseLong(trimmed);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }
}
