package dev.forg.utils.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LootTableIndex {
    private static final Map<String, String> TABLE_CACHE = new ConcurrentHashMap<>();

    private LootTableIndex() {
    }

    public static boolean tableContainsItem(String resourcePath, String itemId) {
        return load(resourcePath).contains('"' + itemId + '"');
    }

    private static String load(String resourcePath) {
        return TABLE_CACHE.computeIfAbsent(resourcePath, LootTableIndex::readResource);
    }

    private static String readResource(String resourcePath) {
        try (InputStream stream = LootTableIndex.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ignored) {
            return "";
        }
    }
}
