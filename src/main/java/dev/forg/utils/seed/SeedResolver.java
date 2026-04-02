package dev.forg.utils.seed;

import dev.forg.modules.SeedMinimap;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;

public final class SeedResolver {
    private SeedResolver() {
    }

    public static Long resolve(MinecraftClient mc, String seedText) {
        if (mc.isInSingleplayer() && mc.getServer() != null) {
            return mc.getServer().getOverworld().getSeed();
        }

        String trimmed = normalize(seedText);
        if (trimmed.isEmpty()) {
            SeedMinimap seedMinimap = Modules.get().get(SeedMinimap.class);
            if (seedMinimap != null) {
                trimmed = normalize(seedMinimap.getSharedSeed());
            }
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
