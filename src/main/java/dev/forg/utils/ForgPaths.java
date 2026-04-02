package dev.forg.utils;

import meteordevelopment.meteorclient.MeteorClient;

import java.io.File;
import java.nio.file.Path;

public final class ForgPaths {
    private ForgPaths() {}

    public static Path dataDir() {
        // All Astral file output should live here.
        return MeteorClient.FOLDER.toPath().resolve("astral");
    }

    public static File ensureDataDir() {
        File dir = dataDir().toFile();
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
