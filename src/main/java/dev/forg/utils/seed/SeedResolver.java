package dev.forg.utils.seed;

import net.minecraft.client.MinecraftClient;

public final class SeedResolver {
    public static final String DEFAULT_MULTIPLAYER_SEED = "7557068879127401510";

    private SeedResolver() {
    }

    public static Long resolve(MinecraftClient mc, String seedText) {
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return mc.getServer().getOverworld().getSeed();
        }

        String trimmed = normalize(seedText);
        if (trimmed.isEmpty()) {
            trimmed = DEFAULT_MULTIPLAYER_SEED;
        }

        if (trimmed.isEmpty()) return null;

        try {
            return Long.parseLong(trimmed);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
