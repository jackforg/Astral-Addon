package dev.forg.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ForgGlow extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_PUBLIC_LIST_URL = "http://138.68.235.32:8787/glow_list.json";
    private static final String DEFAULT_SHARE_URL = "http://138.68.235.32:8787/share";

    private final Path glowFile = ForgPaths.dataDir().resolve("glow_list.json");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRegistry = settings.createGroup("Public Registry");
    private final SettingGroup sgSharing = settings.createGroup("Presence Sharing");

    public final Setting<SettingColor> color = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Color of the glow outline.")
        .defaultValue(new SettingColor(85, 255, 85, 255))
        .build()
    );

    public final Setting<Boolean> selfGlow = sgGeneral.add(new BoolSetting.Builder()
        .name("self-glow")
        .description("Also apply the glow to yourself.")
        .defaultValue(true)
        .build()
    );

    public final Setting<String> remoteUrl = sgRegistry.add(new StringSetting.Builder()
        .name("list-url")
        .description("JSON URL Astral reads for shared glow users.")
        .defaultValue(DEFAULT_PUBLIC_LIST_URL)
        .build()
    );

    public final Setting<Boolean> sharePresence = sgSharing.add(new BoolSetting.Builder()
        .name("share-presence")
        .description("Share your UUID and current username so other Astral users can glow you automatically. Off by default.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> shareUrl = sgSharing.add(new StringSetting.Builder()
        .name("share-url")
        .description("POST endpoint used for opt-in glow sharing. Astral sends only your UUID and current username.")
        .defaultValue(DEFAULT_SHARE_URL)
        .visible(sharePresence::get)
        .build()
    );

    public final Setting<Boolean> verboseSharing = sgSharing.add(new BoolSetting.Builder()
        .name("verbose-sharing-messages")
        .description("Show messages for list refreshes and share attempts.")
        .defaultValue(true)
        .build()
    );

    private final Map<UUID, String> glowList = new LinkedHashMap<>();
    private final Set<UUID> remoteList = ConcurrentHashMap.newKeySet();

    private String lastWorldKey = "";
    private boolean presenceDisclosureShown = false;
    private boolean missingShareUrlWarned = false;
    private volatile String lastFetchStatus = "No list fetch yet.";
    private volatile String lastShareStatus = "No share attempt yet.";

    public ForgGlow() {
        super(forg.UTILITY, "astral-glow", "Highlights tracked players with a colored glow outline.");
        load();
    }

    @Override
    public void onActivate() {
        lastWorldKey = currentWorldKey();
        presenceDisclosureShown = false;
        missingShareUrlWarned = false;
        lastFetchStatus = "Waiting for first list fetch.";
        lastShareStatus = "Waiting for first share attempt.";

        if (!lastWorldKey.isEmpty()) {
            fetchRemoteList("module activated");
            sharePresenceIfNeeded();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        String worldKey = currentWorldKey();
        boolean worldChanged = !worldKey.equals(lastWorldKey);

        if (worldChanged) {
            lastWorldKey = worldKey;
            if (!worldKey.isEmpty()) {
                fetchRemoteList("world changed");
                sharePresenceIfNeeded();
            }
        }
    }

    public boolean shouldGlow(UUID uuid) {
        return glowList.containsKey(uuid) || remoteList.contains(uuid);
    }

    public boolean isPresenceSharingEnabled() {
        return sharePresence.get();
    }

    public String getShareUrl() {
        return shareUrl.get().trim();
    }

    public String getRemoteUrl() {
        return remoteUrl.get().trim();
    }

    public int getRemoteListSize() {
        return remoteList.size();
    }

    public String getSharedFieldSummary() {
        return "Astral sends only your UUID and current username when sharing is enabled.";
    }

    public String getLastFetchStatus() {
        return lastFetchStatus;
    }

    public String getLastShareStatus() {
        return lastShareStatus;
    }

    public void refreshSharedRegistryNow() {
        fetchRemoteList("manual refresh");
        sharePresenceIfNeeded();
    }

    private void fetchRemoteList(String reason) {
        String url = getRemoteUrl();
        if (url.isEmpty()) {
            lastFetchStatus = "List URL is not set.";
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "astral-addon/" + forg.VERSION);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    lastFetchStatus = "List fetch failed with HTTP " + code + ".";
                    forg.LOG.warn("[astral-glow] Failed to fetch remote glow list (HTTP {}).", code);
                    return;
                }

                try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    Set<UUID> fetched = parseRemoteEntries(GSON.fromJson(reader, JsonElement.class));
                    remoteList.clear();
                    remoteList.addAll(fetched);

                    if (verboseSharing.get()) {
                        sendClientInfo("Loaded " + fetched.size() + " shared users from " + url + " (" + reason + ").");
                    }

                    lastFetchStatus = "Loaded " + fetched.size() + " shared users at " + Instant.now() + ".";
                    forg.LOG.info("[astral-glow] Loaded {} UUIDs from remote list.", remoteList.size());
                }
            } catch (Exception e) {
                lastFetchStatus = "List fetch failed: " + e.getMessage();
                forg.LOG.warn("[astral-glow] Failed to fetch remote glow list: {}", e.getMessage());
            }
        });
    }

    private Set<UUID> parseRemoteEntries(JsonElement element) {
        Set<UUID> parsed = ConcurrentHashMap.newKeySet();
        if (element == null || element.isJsonNull()) return parsed;

        if (element.isJsonArray()) {
            parseRemoteArray(parsed, element.getAsJsonArray());
            return parsed;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("users") && object.get("users").isJsonArray()) {
                parseRemoteArray(parsed, object.getAsJsonArray("users"));
            }
        }

        return parsed;
    }

    private void parseRemoteArray(Set<UUID> parsed, JsonArray array) {
        for (JsonElement entry : array) {
            if (entry == null || entry.isJsonNull()) continue;

            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                addParsedUuid(parsed, entry.getAsString());
                continue;
            }

            if (!entry.isJsonObject()) continue;

            JsonObject obj = entry.getAsJsonObject();
            if (obj.has("uuid")) addParsedUuid(parsed, obj.get("uuid").getAsString());
        }
    }

    private void addParsedUuid(Set<UUID> parsed, String value) {
        try {
            parsed.add(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void sharePresenceIfNeeded() {
        if (!sharePresence.get()) return;
        if (mc.getSession() == null || mc.getSession().getUuidOrNull() == null) return;

        String url = getShareUrl();
        if (url.isEmpty()) {
            if (!missingShareUrlWarned) {
                warning("Presence sharing is enabled, but share-url is empty.");
                missingShareUrlWarned = true;
            }
            lastShareStatus = "Sharing is enabled, but share-url is empty.";
            return;
        }

        String username = mc.getSession().getUsername();
        UUID uuid = mc.getSession().getUuidOrNull();
        PresencePayload payload = new PresencePayload(uuid.toString(), username);

        if (!presenceDisclosureShown && verboseSharing.get()) {
            info("Presence sharing is enabled. Astral will send your UUID and current username to " + url + ".");
            info("Astral does not send chat, coordinates, inventory, server IP, tokens, or credentials.");
            presenceDisclosureShown = true;
        }

        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "astral-addon/" + forg.VERSION);

                try (Writer writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                    GSON.toJson(payload, writer);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    lastShareStatus = "Share failed with HTTP " + code + ".";
                    sendClientWarning("Could not share your presence. Endpoint returned HTTP " + code + ".");
                    return;
                }

                String responseSummary = parseShareResponse(conn);
                lastShareStatus = "Shared UUID and username at " + Instant.now() + "." + responseSummary;

                if (verboseSharing.get()) {
                    sendClientInfo("Shared your username and UUID with " + url + "." + responseSummary);
                }
            } catch (Exception e) {
                lastShareStatus = "Share failed: " + e.getMessage();
                sendClientWarning("Failed to share your presence: " + e.getMessage());
            }
        });
    }

    private String parseShareResponse(HttpURLConnection conn) {
        try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement element = GSON.fromJson(reader, JsonElement.class);
            if (element == null || !element.isJsonObject()) return "";

            JsonObject object = element.getAsJsonObject();
            StringBuilder summary = new StringBuilder();

            if (object.has("publicListUrl") && object.get("publicListUrl").isJsonPrimitive()) {
                summary.append(" List: ").append(object.get("publicListUrl").getAsString()).append(".");
            }

            if (object.has("githubMirrored") && object.get("githubMirrored").isJsonPrimitive()) {
                summary.append(" GitHub mirrored: ").append(object.get("githubMirrored").getAsBoolean()).append(".");
            }

            if (object.has("message") && object.get("message").isJsonPrimitive()) {
                summary.append(" ").append(object.get("message").getAsString());
            }

            return summary.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    // Local list management used by GlowCommand.
    public boolean add(UUID uuid, String name) {
        boolean added = !glowList.containsKey(uuid);
        glowList.put(uuid, name);
        save();
        return added;
    }

    public boolean remove(UUID uuid) {
        boolean removed = glowList.remove(uuid) != null;
        if (removed) save();
        return removed;
    }

    public void clear() {
        glowList.clear();
        save();
    }

    public Map<UUID, String> getGlowList() {
        return Collections.unmodifiableMap(glowList);
    }

    private void load() {
        if (!Files.exists(glowFile)) return;
        try (Reader reader = Files.newBufferedReader(glowFile, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> raw = GSON.fromJson(reader, type);
            if (raw != null) {
                glowList.clear();
                raw.forEach((k, v) -> {
                    try {
                        glowList.put(UUID.fromString(k), v);
                    } catch (IllegalArgumentException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            error("Failed to load glow list: " + e.getMessage());
        }
    }

    private void save() {
        try {
            ForgPaths.ensureDataDir();
            Map<String, String> raw = new LinkedHashMap<>();
            glowList.forEach((k, v) -> raw.put(k.toString(), v));
            try (Writer writer = Files.newBufferedWriter(glowFile, StandardCharsets.UTF_8)) {
                GSON.toJson(raw, writer);
            }
        } catch (IOException e) {
            error("Failed to save glow list: " + e.getMessage());
        }
    }

    private String currentWorldKey() {
        if (mc.world == null || mc.player == null) return "";
        return Utils.getWorldName();
    }

    private void sendClientInfo(String message) {
        mc.execute(() -> info(message));
    }

    private void sendClientWarning(String message) {
        mc.execute(() -> warning(message));
    }

    private record PresencePayload(String uuid, String username) {
    }
}
