package dev.forg.modules;

import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import dev.forg.util.OptionalModSupport;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.util.math.Vec3d;
import xaero.common.minimap.waypoints.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import net.minecraft.block.entity.*;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.forg.util.JeffUtils.sendWebhook;

public class BetterStashFinder extends Module
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public List<Chunk> chunks = new ArrayList<>();

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("The minimum amount of storage blocks in a chunk to record the chunk.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Boolean> shulkerInstantHit = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-instant-hit")
        .description("If a single shulker counts as a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> crafterInstantHit = sgGeneral.add(new BoolSetting.Builder()
        .name("crafter-instant-hit")
        .description("If a single auto crafter counts as a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableOnTeleport = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-teleport-or-death")
        .description("If on, will disable this module when respawning or teleporting to try to prevent coord leaks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreTrialChambers = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-trial-chambers")
        .description("Attempts to ignore trial chambers, but may cause false negatives if someone made their base to look like a trial chamber.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("The minimum distance you must be from spawn to record a certain chunk.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> onlyOldchunks = sgGeneral.add(new BoolSetting.Builder()
        .name("only-old-chunks")
        .description("Checks that the chunks it scans have already been loaded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveToWaypoints = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-waypoints")
        .description("Creates xaeros minimap waypoints for stash finds.")
        .defaultValue(false)
        .onChanged(this::waypointSettingChanged)
        .visible(() -> OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends Minecraft notifications when new stashes are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumAlertPriority = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-alert-priority")
        .description("Only sends chat, toast, webhook, module-event, and waypoint alerts for leads at or above this priority score. Leads below this are still saved locally.")
        .defaultValue(0)
        .min(0)
        .sliderMax(100)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("The mode to use for notifications.")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    private final Setting<Boolean> sendWebhook = sgGeneral.add(new BoolSetting.Builder()
        .name("send-webhook")
        .description("Sends a webhook when a stash is found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-link")
        .description("A discord webhook link. Looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .visible(sendWebhook::get)
        .build()
    );

    public final Setting<Boolean> advancedLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-logging")
        .description("Will log more information, including the amount of each container found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> useStashScore = sgGeneral.add(new BoolSetting.Builder()
        .name("use-stash-score")
        .description("Weights important blocks like shulkers, crafters, and hoppers higher than plain storage.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> minimumScore = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-score")
        .description("Minimum score required before a chunk is recorded when stash scoring is enabled.")
        .defaultValue(12)
        .min(1)
        .sliderMax(100)
        .visible(useStashScore::get)
        .build()
    );

    public final Setting<Boolean> scoreWaypoints = sgGeneral.add(new BoolSetting.Builder()
        .name("score-waypoints")
        .description("Prefixes Xaero waypoints with the stash score.")
        .defaultValue(true)
        .visible(() -> OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE && saveToWaypoints.get() && useStashScore.get())
        .build()
    );

    public final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("ping-for-stash-finder")
        .description("Pings you for stash finder and base finder messages")
        .defaultValue(false)
        .visible(sendWebhook::get)
        .build()
    );

    public final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("discord-ID")
        .description("Your discord ID")
        .defaultValue("")
        .visible(() -> sendWebhook.get() && ping.get())
        .build()
    );

    private final Setting<Boolean> detectEnderStorageClusters = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-ender-storage-clusters")
        .description("Flags chunks where an ender chest is unusually close to regular storage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> enderStorageRadius = sgGeneral.add(new IntSetting.Builder()
        .name("ender-storage-radius")
        .description("Maximum distance between an ender chest and nearby storage for a suspicious cluster.")
        .defaultValue(6)
        .range(1, 16)
        .sliderRange(1, 16)
        .visible(detectEnderStorageClusters::get)
        .build()
    );

    private final Setting<Integer> minimumEnderStoragePairs = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-ender-storage-pairs")
        .description("Minimum number of ender-chest/storage links before the chunk is recorded as suspicious.")
        .defaultValue(1)
        .range(1, 16)
        .sliderRange(1, 8)
        .visible(detectEnderStorageClusters::get)
        .build()
    );

    private final Setting<Boolean> detectDungeonAnomalies = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-dungeon-anomalies")
        .description("Looks for player-placed blocks and storage around mob spawners.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> dungeonScanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("dungeon-scan-radius")
        .description("How far around spawners to inspect for suspicious blocks.")
        .defaultValue(6)
        .range(2, 12)
        .sliderRange(2, 12)
        .visible(detectDungeonAnomalies::get)
        .build()
    );

    private final Setting<Integer> dungeonAnomalyThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("dungeon-anomaly-threshold")
        .description("Minimum anomaly score near a spawner before the chunk is recorded.")
        .defaultValue(6)
        .range(1, 50)
        .sliderRange(1, 25)
        .visible(detectDungeonAnomalies::get)
        .build()
    );

    private final Setting<Boolean> detectMineshaftAnomalies = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-mineshaft-anomalies")
        .description("Looks for odd player blocks in chunks that resemble abandoned mineshafts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> mineshaftMarkerThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("mineshaft-marker-threshold")
        .description("Minimum amount of rail/web/fence/plank markers before a chunk is treated as a mineshaft candidate.")
        .defaultValue(8)
        .range(1, 64)
        .sliderRange(1, 32)
        .visible(detectMineshaftAnomalies::get)
        .build()
    );

    private final Setting<Integer> mineshaftAnomalyThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("mineshaft-anomaly-threshold")
        .description("Minimum anomaly score in mineshaft-like chunks before the chunk is recorded.")
        .defaultValue(8)
        .range(1, 80)
        .sliderRange(1, 40)
        .visible(detectMineshaftAnomalies::get)
        .build()
    );

    private final Setting<Integer> anomalyMinY = sgGeneral.add(new IntSetting.Builder()
        .name("anomaly-min-y")
        .description("Lowest Y level scanned for structure anomaly detection.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .visible(() -> detectDungeonAnomalies.get() || detectMineshaftAnomalies.get())
        .build()
    );

    private final Setting<Integer> anomalyMaxY = sgGeneral.add(new IntSetting.Builder()
        .name("anomaly-max-y")
        .description("Highest Y level scanned for structure anomaly detection.")
        .defaultValue(80)
        .range(-64, 320)
        .sliderRange(-64, 160)
        .visible(() -> detectDungeonAnomalies.get() || detectMineshaftAnomalies.get())
        .build()
    );

    private final Setting<Boolean> detectHumanTouch = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-human-touch")
        .description("Scores chunks that contain underground workstations, lights, doors, ladders, and similar man-made touches.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> humanTouchThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("human-touch-threshold")
        .description("Minimum human-touch score before the chunk gets treated as suspicious.")
        .defaultValue(6)
        .range(1, 64)
        .sliderRange(1, 24)
        .visible(detectHumanTouch::get)
        .build()
    );

    private final Setting<Boolean> detectBuriedUtility = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-buried-utility")
        .description("Looks for underground utility clusters like crafting tables, anvils, furnaces, and weird water/lava support.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> buriedUtilityThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("buried-utility-threshold")
        .description("Minimum buried-utility score before the chunk is recorded.")
        .defaultValue(5)
        .range(1, 64)
        .sliderRange(1, 24)
        .visible(detectBuriedUtility::get)
        .build()
    );

    private final Setting<Boolean> detectIllegalBedrock = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-illegal-bedrock")
        .description("Flags overworld or mid-nether chunks containing bedrock outside natural bedrock layers.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> illegalBedrockThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("illegal-bedrock-threshold")
        .description("Minimum suspicious bedrock count before the chunk is treated as a lead.")
        .defaultValue(1)
        .range(1, 64)
        .sliderRange(1, 16)
        .visible(detectIllegalBedrock::get)
        .build()
    );

    private final Setting<Boolean> detectHighwaySidePockets = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-highway-side-pockets")
        .description("Boosts suspicious chunks that sit just off axis or diagonal highways, where side caches often hide.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> highwayPocketMinOffset = sgGeneral.add(new IntSetting.Builder()
        .name("highway-pocket-min-offset")
        .description("Minimum offset from a highway line before a lead is considered a side pocket.")
        .defaultValue(16)
        .range(0, 512)
        .sliderRange(0, 128)
        .visible(detectHighwaySidePockets::get)
        .build()
    );

    private final Setting<Integer> highwayPocketMaxOffset = sgGeneral.add(new IntSetting.Builder()
        .name("highway-pocket-max-offset")
        .description("Maximum offset from a highway line for the side-pocket boost.")
        .defaultValue(192)
        .range(8, 1024)
        .sliderRange(32, 384)
        .visible(detectHighwaySidePockets::get)
        .build()
    );

    private final Setting<Integer> highwayPocketSpawnBypass = sgGeneral.add(new IntSetting.Builder()
        .name("highway-pocket-spawn-bypass")
        .description("Ignore highway side-pocket scoring inside this distance from spawn.")
        .defaultValue(2000)
        .range(0, 200000)
        .sliderRange(0, 20000)
        .visible(detectHighwaySidePockets::get)
        .build()
    );

    private final Setting<Boolean> suppressLowValueNoise = sgGeneral.add(new BoolSetting.Builder()
        .name("suppress-low-value-noise")
        .description("Suppresses weak dungeon/mineshaft hits unless there is real player influence nearby.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> correlateOtherModules = sgGeneral.add(new BoolSetting.Builder()
        .name("correlate-other-modules")
        .description("Cross-checks leads with BannerFinder, CoordLogger, and MinecartDetector logs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> correlationRadiusBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("correlation-radius")
        .description("How far away external module signals can be while still boosting a stash lead.")
        .defaultValue(192)
        .range(16, 2048)
        .sliderRange(32, 512)
        .visible(correlateOtherModules::get)
        .build()
    );

    private final Setting<Boolean> correlateBannerFinder = sgGeneral.add(new BoolSetting.Builder()
        .name("correlate-banner-finder")
        .description("Lets banner finds boost stash leads.")
        .defaultValue(true)
        .visible(correlateOtherModules::get)
        .build()
    );

    private final Setting<Boolean> correlateCoordLogger = sgGeneral.add(new BoolSetting.Builder()
        .name("correlate-coord-logger")
        .description("Lets CoordLogger signals boost stash leads.")
        .defaultValue(true)
        .visible(correlateOtherModules::get)
        .build()
    );

    private final Setting<Boolean> correlateMinecartDetector = sgGeneral.add(new BoolSetting.Builder()
        .name("correlate-minecart-detector")
        .description("Lets MinecartDetector signals boost stash leads.")
        .defaultValue(true)
        .visible(correlateOtherModules::get)
        .build()
    );

    private final Setting<Integer> correlationRefreshSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("correlation-refresh-seconds")
        .description("How often to rescan external module logs for cross-correlation.")
        .defaultValue(30)
        .range(5, 600)
        .sliderRange(5, 120)
        .visible(correlateOtherModules::get)
        .build()
    );

    private final Setting<Boolean> clusterNearbyChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("cluster-nearby-chunks")
        .description("Groups nearby stash leads into one site so multi-chunk bases stand out properly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> clusterRadiusChunks = sgGeneral.add(new IntSetting.Builder()
        .name("cluster-radius-chunks")
        .description("Chunks within this radius are grouped into the same stash site.")
        .defaultValue(2)
        .range(1, 8)
        .sliderRange(1, 5)
        .visible(clusterNearbyChunks::get)
        .build()
    );

    private final Setting<Boolean> showSiteHeatmap = sgGeneral.add(new BoolSetting.Builder()
        .name("show-site-heatmap")
        .description("Shows grouped site summaries above the raw chunk table in the module UI.")
        .defaultValue(true)
        .visible(clusterNearbyChunks::get)
        .build()
    );

    private final Setting<Boolean> autoWaypointNotes = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-waypoint-notes")
        .description("Adds age/context notes to stash-finder waypoint names automatically.")
        .defaultValue(true)
        .visible(() -> OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE && saveToWaypoints.get())
        .build()
    );

    public BetterStashFinder()
    {
        super(forg.STASH, "better-stash-finder", "Meteors StashFinder but with more features.");
    }

    private Vec3d lastPosition = null;
    private final Map<String, LeadHistory> leadHistory = new HashMap<>();
    private final List<LeadSite> leadSites = new ArrayList<>();
    private final List<SignalPoint> correlationSignals = new ArrayList<>();
    private boolean xaeroPlusRegistered;
    private boolean warnedAboutMissingXaeroPlus;
    private boolean warnedAboutMissingWaypoints;
    private long lastCorrelationRefreshMs = 0L;

    @Override
    public void onActivate() {
        lastPosition = null;
        if (!ensureXaeroPlusAvailable()) {
            toggle();
            return;
        }

        XaeroPlus.EVENT_BUS.register(this);
        xaeroPlusRegistered = true;
        load();
        loadLeadHistory();
        refreshCorrelationSignals(true);
        rebuildLeadSites();
    }

    @Override
    public void onDeactivate() {
        if (xaeroPlusRegistered) {
            XaeroPlus.EVENT_BUS.unregister(this);
            xaeroPlusRegistered = false;
        }

        saveLeadHistory();
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event) {
        if (event.seenChunk()) return;
        // Check the distance.
        double chunkXAbs = Math.abs(event.chunk().getPos().x * 16);
        double chunkZAbs = Math.abs(event.chunk().getPos().z * 16);
        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get()) return;

        Chunk chunk = new Chunk(event.chunk().getPos());

        RegistryKey<World> currentDimension = mc.world.getRegistryKey();

        ChunkPos chunkPos = chunk.chunkPos;
        PaletteNewChunks paletteNewChunks = ModuleManager.getModule(PaletteNewChunks.class);
        boolean is119NewChunk = paletteNewChunks
            .isNewChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        // Check that the chunk is in old chunks
        if (onlyOldchunks.get() && (is119NewChunk && !is112OldChunk)) return;

        List<BlockPos> storagePositions = new ArrayList<>();
        List<BlockPos> enderChestPositions = new ArrayList<>();

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (!storageBlocks.get().contains(blockEntity.getType())) continue;

            Block blockUnder = mc.world.getBlockState(blockEntity.getPos().down()).getBlock();
            if (ignoreTrialChambers.get() && blockUnder.equals(Blocks.WAXED_OXIDIZED_CUT_COPPER) ||
                blockUnder.equals(Blocks.TUFF_BRICKS) || blockUnder.equals(Blocks.WAXED_COPPER_BLOCK) ||
                blockUnder.equals(Blocks.WAXED_OXIDIZED_COPPER))
            {
                continue;
            }

            if (blockEntity instanceof ChestBlockEntity) {
                chunk.chests++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof BarrelBlockEntity) {
                chunk.barrels++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof ShulkerBoxBlockEntity) {
                chunk.shulkers++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof EnderChestBlockEntity) {
                chunk.enderChests++;
                enderChestPositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof AbstractFurnaceBlockEntity) {
                chunk.furnaces++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof DispenserBlockEntity) {
                chunk.dispensersDroppers++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof HopperBlockEntity) {
                chunk.hoppers++;
                storagePositions.add(blockEntity.getPos());
            }
            else if (blockEntity instanceof CrafterBlockEntity) {
                chunk.crafters++;
                storagePositions.add(blockEntity.getPos());
            }
        }

        scanSuspiciousSignals(event.chunk(), chunk, storagePositions, enderChestPositions);
        if (correlateOtherModules.get()) refreshCorrelationSignals(false);
        applyCrossModuleCorrelation(chunk);
        chunk.highwaySidePocketScore = detectHighwaySidePockets.get() ? scoreHighwaySidePocket(chunk) : 0;
        chunk.ageGuess = guessAge(is119NewChunk, is112OldChunk, chunk);
        chunk.contextTags = classifyContexts(chunk);
        applyLeadHistoryPreview(chunk);

        boolean passesCountCheck = chunk.getTotal() >= minimumStorageCount.get();
        boolean passesInstantHit = (shulkerInstantHit.get() && chunk.shulkers > 0) || (crafterInstantHit.get() && chunk.crafters > 0);
        boolean passesScoreCheck = !useStashScore.get() || chunk.getScore() >= minimumScore.get();
        boolean passesHeuristicCheck = chunk.hasSuspiciousLead(this);
        boolean suppressedLowValue = suppressLowValueNoise.get() && shouldSuppressLowValueLead(chunk);
        boolean passesAlertThreshold = chunk.getPriorityScore() >= minimumAlertPriority.get();

        if ((((passesCountCheck || passesInstantHit) && passesScoreCheck) || passesHeuristicCheck) && !suppressedLowValue) {
            Chunk prevChunk = null;
            int i = chunks.indexOf(chunk);

            if (i < 0) chunks.add(chunk);
            else prevChunk = chunks.set(i, chunk);

            updateLeadHistory(chunk);
            rebuildLeadSites();
            saveJson();
            saveCsv();
            saveLeadHistory();

            if (!chunk.equals(prevChunk) || !chunk.countsEqual(prevChunk)) {
                if (sendNotifications.get() && passesAlertThreshold)
                {
                    switch (notificationMode.get())
                    {
                        case Chat -> info("Found stash lead at (highlight)%s(default), (highlight)%s(default) (%s).", chunk.x, chunk.z, chunk.getLeadSummary());
                        case Toast -> mc.getToastManager().add(new MeteorToast.Builder(title).icon(Items.CHEST).text("Found Stash Lead!").build());
                        case Both -> {
                            info("Found stash lead at (highlight)%s(default), (highlight)%s(default) (%s).", chunk.x, chunk.z, chunk.getLeadSummary());
                            mc.getToastManager().add(new MeteorToast.Builder(title).icon(Items.CHEST).text("Found Stash Lead!").build());
                        }
                    }
                }

                if (passesAlertThreshold) {
                    DiscordNotifs.pushModuleEvent("BetterStashFinder", "Lead score " + chunk.getScore() + " at " + chunk.x + ", " + chunk.z + " [" + chunk.getLeadSummary() + "]");
                }

                if (passesAlertThreshold && sendWebhook.get() && !webhookLink.get().isEmpty())
                {
                    if (advancedLogging.get())
                    {
                        String chunkType = "";
                        if (is119NewChunk && !is112OldChunk) chunkType = "new";
                        else if (is119NewChunk && is112OldChunk) chunkType = "unfollowed 1.12";
                        else if (!is119NewChunk && !is112OldChunk) chunkType = "1.19";
                        else if (!is119NewChunk && is112OldChunk) chunkType = "followed 1.12";
                        String json = "{\"embeds\": [{" +
                            "\"title\": \"Stash Found!\"," +
                            "\"color\": 2154012," +
                            "\"description\": \"Coordinates: || X: " + chunk.x + " Z: " + chunk.z + "|| in " + chunkType + " chunks | Score: " + chunk.getScore() + " | Lead: " + chunk.getLeadSummary() + "\"," +
                            "\"fields\": [" +
                                "{" +
                                    "\"name\": \"Chests\"," +
                                    "\"value\": " + chunk.chests + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Barrels\"," +
                                    "\"value\": " + chunk.barrels + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Shulkers\"," +
                                    "\"value\": " + chunk.shulkers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Ender Chests\"," +
                                    "\"value\": " + chunk.enderChests + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Hoppers\"," +
                                    "\"value\": " + chunk.hoppers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Dispensers/Droppers\"," +
                                    "\"value\": " + chunk.dispensersDroppers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Furnaces\"," +
                                    "\"value\": " + chunk.furnaces + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Crafters\"," +
                                    "\"value\": " + chunk.crafters + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Ender/Storage Pairs\"," +
                                    "\"value\": " + chunk.enderStoragePairs + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Dungeon Score\"," +
                                    "\"value\": " + chunk.dungeonAnomalyScore + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Mineshaft Score\"," +
                                    "\"value\": " + chunk.mineshaftAnomalyScore + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Human Touch\"," +
                                    "\"value\": " + chunk.humanTouchScore + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Buried Utility\"," +
                                    "\"value\": " + chunk.buriedUtilityScore + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Correlation\"," +
                                    "\"value\": " + chunk.correlationScore + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Age Guess\"," +
                                    "\"value\": \"" + chunk.ageGuess + "\"," +
                                    "\"inline\": true" +
                                "}" +
                            "]" +
                        "}]}";

                        new Thread(() -> sendWebhook(webhookLink.get(), json, ping.get() ? discordId.get() : null)).start();
                    }
                    else
                    {
                        String message = "Found stash lead at " + chunk.x + ", " + chunk.z + " (score " + chunk.getScore() + ", " + chunk.getLeadSummary() + ").";
                        new Thread(() -> sendWebhook(webhookLink.get(), title, message, ping.get() ? discordId.get() : null, mc.player.getGameProfile().name())).start();
                    }
                }

                if (passesAlertThreshold && saveToWaypoints.get() && OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE)
                {
                    WaypointSet waypointSet = getWaypointSet();
                    if (waypointSet == null) return;
                    addToWaypoints(waypointSet, chunk);
                    refreshWaypointDisplay();
                }
            }
        }
    }

    private void scanSuspiciousSignals(WorldChunk worldChunk, Chunk chunk, List<BlockPos> storagePositions, List<BlockPos> enderChestPositions) {
        if (detectEnderStorageClusters.get()) {
            chunk.enderStoragePairs = countEnderStoragePairs(storagePositions, enderChestPositions, enderStorageRadius.get());
            chunk.kitStationScore = scoreKitStation(chunk, storagePositions, enderChestPositions);
        }

        int worldBottomY = mc.world.getBottomY();
        int worldTopY = mc.world.getBottomY() + mc.world.getHeight() - 1;
        int minY = Math.max(worldBottomY, anomalyMinY.get());
        int maxY = Math.min(worldTopY, anomalyMaxY.get());
        if (minY > maxY) return;

        List<BlockPos> spawnerPositions = new ArrayList<>();
        int mineshaftMarkers = 0;
        int suspiciousMineshaftBlocks = 0;
        int humanTouchScore = 0;
        int buriedUtilityScore = 0;
        int abnormalBlockScore = 0;
        int illegalBedrockScore = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int x = worldChunk.getPos().getStartX(); x <= worldChunk.getPos().getEndX(); x++) {
            for (int z = worldChunk.getPos().getStartZ(); z <= worldChunk.getPos().getEndZ(); z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutable.set(x, y, z);
                    Block block = worldChunk.getBlockState(mutable).getBlock();

                    if (block == Blocks.SPAWNER) spawnerPositions.add(mutable.toImmutable());
                    if (isMineshaftMarkerBlock(block)) mineshaftMarkers++;
                    if (isSuspiciousMineshaftBlock(block)) suspiciousMineshaftBlocks++;
                    if (detectHumanTouch.get() && isHumanTouchBlock(block)) humanTouchScore += humanTouchWeight(block, y);
                    if (detectBuriedUtility.get() && isBuriedUtilityBlock(block)) buriedUtilityScore += buriedUtilityWeight(block, y);
                    if (isSuspiciousDungeonBlock(block) || isAbnormalUndergroundBlock(block)) abnormalBlockScore += abnormalBlockWeight(block, y);
                    if (detectIllegalBedrock.get() && isIllegalBedrock(block, y)) illegalBedrockScore++;
                }
            }
        }

        chunk.spawners = spawnerPositions.size();
        chunk.mineshaftMarkerBlocks = mineshaftMarkers;
        chunk.suspiciousMineshaftBlocks = suspiciousMineshaftBlocks;
        chunk.humanTouchScore = humanTouchScore;
        chunk.buriedUtilityScore = buriedUtilityScore;
        chunk.abnormalBlockScore = abnormalBlockScore;
        chunk.illegalBedrockScore = illegalBedrockScore;

        if (detectDungeonAnomalies.get() && !spawnerPositions.isEmpty()) {
            chunk.dungeonAnomalyScore = scoreDungeonAnomaly(spawnerPositions, storagePositions, enderChestPositions) + (humanTouchScore / 2) + (buriedUtilityScore / 2);
        }

        if (detectMineshaftAnomalies.get() && mineshaftMarkers >= mineshaftMarkerThreshold.get()) {
            chunk.mineshaftAnomalyScore = suspiciousMineshaftBlocks + (chunk.enderStoragePairs * 4) + Math.min(storagePositions.size(), 6) + (humanTouchScore / 2);
        }
    }

    private boolean isIllegalBedrock(Block block, int y) {
        if (block != Blocks.BEDROCK || mc.world == null) return false;

        String dimension = mc.world.getRegistryKey().getValue().getPath();
        int bottom = mc.world.getBottomY();
        int top = bottom + mc.world.getHeight() - 1;

        return switch (dimension) {
            case "the_nether" -> y > bottom + 4 && y < top - 4;
            case "overworld" -> y > bottom + 4;
            default -> false;
        };
    }

    private int countEnderStoragePairs(List<BlockPos> storagePositions, List<BlockPos> enderChestPositions, int radius) {
        if (storagePositions.isEmpty() || enderChestPositions.isEmpty()) return 0;

        int radiusSq = radius * radius;
        int pairs = 0;

        for (BlockPos enderChestPos : enderChestPositions) {
            for (BlockPos storagePos : storagePositions) {
                if (enderChestPos.getSquaredDistance(storagePos) <= radiusSq) pairs++;
            }
        }

        return pairs;
    }

    private int scoreKitStation(Chunk chunk, List<BlockPos> storagePositions, List<BlockPos> enderChestPositions) {
        if (enderChestPositions.isEmpty()) return 0;

        int utilityCount = chunk.hoppers + chunk.dispensersDroppers + chunk.furnaces + chunk.crafters;
        if (utilityCount == 0 && storagePositions.isEmpty()) return 0;

        int score = 0;
        int radiusSq = enderStorageRadius.get() * enderStorageRadius.get();
        for (BlockPos enderChestPos : enderChestPositions) {
            for (BlockPos storagePos : storagePositions) {
                if (enderChestPos.getSquaredDistance(storagePos) <= radiusSq) score += 2;
            }
        }

        score += Math.min(utilityCount * 2, 12);
        return score;
    }

    private int scoreDungeonAnomaly(List<BlockPos> spawnerPositions, List<BlockPos> storagePositions, List<BlockPos> enderChestPositions) {
        int score = 0;
        int radius = dungeonScanRadius.get();
        int radiusSq = radius * radius;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (BlockPos spawnerPos : spawnerPositions) {
            for (int x = spawnerPos.getX() - radius; x <= spawnerPos.getX() + radius; x++) {
                for (int y = spawnerPos.getY() - radius; y <= spawnerPos.getY() + radius; y++) {
                    for (int z = spawnerPos.getZ() - radius; z <= spawnerPos.getZ() + radius; z++) {
                        mutable.set(x, y, z);
                        if (spawnerPos.getSquaredDistance(mutable) > radiusSq) continue;

                        Block block = mc.world.getBlockState(mutable).getBlock();
                        if (isSuspiciousDungeonBlock(block)) score += 2;
                    }
                }
            }

            for (BlockPos storagePos : storagePositions) {
                if (spawnerPos.getSquaredDistance(storagePos) <= radiusSq) score += 3;
            }

            for (BlockPos enderChestPos : enderChestPositions) {
                if (spawnerPos.getSquaredDistance(enderChestPos) <= radiusSq) score += 5;
            }
        }

        return score;
    }

    private boolean isMineshaftMarkerBlock(Block block) {
        return block == Blocks.RAIL
            || block == Blocks.POWERED_RAIL
            || block == Blocks.DETECTOR_RAIL
            || block == Blocks.ACTIVATOR_RAIL
            || block == Blocks.COBWEB
            || block == Blocks.OAK_FENCE
            || block == Blocks.OAK_PLANKS
            || block == Blocks.DARK_OAK_FENCE
            || block == Blocks.DARK_OAK_PLANKS;
    }

    private boolean isSuspiciousDungeonBlock(Block block) {
        return block == Blocks.OBSIDIAN
            || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.NETHERRACK
            || block == Blocks.MAGMA_BLOCK
            || block == Blocks.RESPAWN_ANCHOR;
    }

    private boolean isSuspiciousMineshaftBlock(Block block) {
        return block == Blocks.COBBLESTONE
            || block == Blocks.OBSIDIAN
            || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.NETHERRACK
            || block == Blocks.MAGMA_BLOCK
            || block == Blocks.RESPAWN_ANCHOR
            || block == Blocks.ENDER_CHEST;
    }

    private boolean isHumanTouchBlock(Block block) {
        return block == Blocks.TORCH
            || block == Blocks.WALL_TORCH
            || block == Blocks.SOUL_TORCH
            || block == Blocks.SOUL_WALL_TORCH
            || block == Blocks.LANTERN
            || block == Blocks.CRAFTING_TABLE
            || block == Blocks.CARTOGRAPHY_TABLE
            || block == Blocks.SMITHING_TABLE
            || block == Blocks.FLETCHING_TABLE
            || block == Blocks.LOOM
            || block == Blocks.STONECUTTER
            || block == Blocks.GRINDSTONE
            || block == Blocks.ANVIL
            || block == Blocks.CHIPPED_ANVIL
            || block == Blocks.DAMAGED_ANVIL
            || block == Blocks.ENCHANTING_TABLE
            || block == Blocks.LADDER
            || block == Blocks.OAK_DOOR
            || block == Blocks.IRON_DOOR
            || block == Blocks.OAK_TRAPDOOR
            || block == Blocks.IRON_TRAPDOOR
            || block == Blocks.OAK_SIGN
            || block == Blocks.OAK_WALL_SIGN
            || block == Blocks.STONE_BUTTON
            || block == Blocks.OAK_BUTTON
            || block == Blocks.LEVER;
    }

    private boolean isBuriedUtilityBlock(Block block) {
        return block == Blocks.CRAFTING_TABLE
            || block == Blocks.ENCHANTING_TABLE
            || block == Blocks.ANVIL
            || block == Blocks.CHIPPED_ANVIL
            || block == Blocks.DAMAGED_ANVIL
            || block == Blocks.ENDER_CHEST
            || block == Blocks.CHEST
            || block == Blocks.BARREL
            || block == Blocks.OBSIDIAN
            || block == Blocks.CRYING_OBSIDIAN
            || block == Blocks.RESPAWN_ANCHOR
            || block == Blocks.NETHERRACK;
    }

    private boolean isAbnormalUndergroundBlock(Block block) {
        return block == Blocks.END_ROD
            || block == Blocks.BREWING_STAND
            || block == Blocks.BEACON
            || block == Blocks.WHITE_WOOL
            || block == Blocks.REDSTONE_BLOCK
            || block == Blocks.TARGET
            || block == Blocks.SCULK_SENSOR
            || block == Blocks.DAYLIGHT_DETECTOR;
    }

    private int humanTouchWeight(Block block, int y) {
        int weight = 1;
        if (block == Blocks.CRAFTING_TABLE || block == Blocks.ENCHANTING_TABLE || block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL) weight = 3;
        else if (block == Blocks.LANTERN || block == Blocks.TORCH || block == Blocks.WALL_TORCH || block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH) weight = 2;
        return y < 60 ? weight : Math.max(1, weight - 1);
    }

    private int buriedUtilityWeight(Block block, int y) {
        int weight = 1;
        if (block == Blocks.ENDER_CHEST || block == Blocks.ENCHANTING_TABLE || block == Blocks.RESPAWN_ANCHOR) weight = 4;
        else if (block == Blocks.CRAFTING_TABLE || block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL) weight = 3;
        else if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.NETHERRACK) weight = 2;
        return y < 60 ? weight : 0;
    }

    private int abnormalBlockWeight(Block block, int y) {
        int weight = isSuspiciousDungeonBlock(block) ? 2 : 1;
        return y < 60 ? weight : 0;
    }

    private void refreshCorrelationSignals(boolean force) {
        if (!correlateOtherModules.get()) {
            correlationSignals.clear();
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastCorrelationRefreshMs < correlationRefreshSeconds.get() * 1000L) return;

        correlationSignals.clear();
        if (correlateBannerFinder.get()) loadSignalCsv(new File(ForgPaths.ensureDataDir(), "banner_finds.csv"), "banner", 0, 2);
        if (correlateCoordLogger.get()) loadSignalCsv(new File(ForgPaths.ensureDataDir(), "CoordLogger.csv"), "coord", 4, 6);
        if (correlateMinecartDetector.get()) loadMinecartSignals(new File(ForgPaths.ensureDataDir(), "MinecartDetector.log"));
        lastCorrelationRefreshMs = now;
    }

    private void loadSignalCsv(File file, String source, int xIndex, int zIndex) {
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length <= Math.max(xIndex, zIndex)) continue;
                int x = Integer.parseInt(values[xIndex].replace("\"", "").trim());
                int z = Integer.parseInt(values[zIndex].replace("\"", "").trim());
                correlationSignals.add(new SignalPoint(source, x, z));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadMinecartSignals(File file) {
        if (!file.exists()) return;

        Pattern chunkPattern = Pattern.compile("chunk\\s+(-?\\d+),\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
        Pattern coordPattern = Pattern.compile("X:?\\s*(-?\\d+).*Z:?\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher chunkMatcher = chunkPattern.matcher(line);
                if (chunkMatcher.find()) {
                    int chunkX = Integer.parseInt(chunkMatcher.group(1));
                    int chunkZ = Integer.parseInt(chunkMatcher.group(2));
                    correlationSignals.add(new SignalPoint("minecart", chunkX * 16 + 8, chunkZ * 16 + 8));
                    continue;
                }

                Matcher coordMatcher = coordPattern.matcher(line);
                if (coordMatcher.find()) {
                    int x = Integer.parseInt(coordMatcher.group(1));
                    int z = Integer.parseInt(coordMatcher.group(2));
                    correlationSignals.add(new SignalPoint("minecart", x, z));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void applyCrossModuleCorrelation(Chunk chunk) {
        if (!correlateOtherModules.get() || correlationSignals.isEmpty()) return;

        chunk.bannerCorrelation = 0;
        chunk.coordCorrelation = 0;
        chunk.minecartCorrelation = 0;

        int radiusSq = correlationRadiusBlocks.get() * correlationRadiusBlocks.get();
        for (SignalPoint signal : correlationSignals) {
            int dx = chunk.x - signal.x;
            int dz = chunk.z - signal.z;
            if (dx * dx + dz * dz > radiusSq) continue;

            switch (signal.source) {
                case "banner" -> chunk.bannerCorrelation++;
                case "coord" -> chunk.coordCorrelation++;
                case "minecart" -> chunk.minecartCorrelation++;
            }
        }

        chunk.correlationScore = (chunk.bannerCorrelation * 5) + (chunk.coordCorrelation * 3) + (chunk.minecartCorrelation * 4);
    }

    private int scoreHighwaySidePocket(Chunk chunk) {
        double horizontal = Math.hypot(chunk.x, chunk.z);
        if (horizontal < highwayPocketSpawnBypass.get()) return 0;

        double axisDistance = Math.min(Math.abs(chunk.x), Math.abs(chunk.z));
        double diagonalDistance = Math.min(Math.abs(chunk.x - chunk.z), Math.abs(chunk.x + chunk.z)) / Math.sqrt(2.0);
        double nearestHighway = Math.min(axisDistance, diagonalDistance);

        if (nearestHighway < highwayPocketMinOffset.get() || nearestHighway > highwayPocketMaxOffset.get()) return 0;
        return 4 + Math.min(chunk.getTotal(), 6);
    }

    private String guessAge(boolean is119NewChunk, boolean is112OldChunk, Chunk chunk) {
        if (is119NewChunk && !is112OldChunk) return "fresh";
        if (!is119NewChunk && is112OldChunk) {
            if (chunk.humanTouchScore > 10 || chunk.buriedUtilityScore > 8) return "legacy";
            return "old";
        }
        if (!is119NewChunk) return "settled";
        if (chunk.correlationScore > 0 || chunk.enderStoragePairs > 0) return "mixed";
        return "recent";
    }

    private List<String> classifyContexts(Chunk chunk) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();

        if (chunk.kitStationScore > 0) tags.add("kit-station");
        if (chunk.enderStoragePairs > 0) tags.add("ender-storage");
        if (chunk.dungeonAnomalyScore >= dungeonAnomalyThreshold.get() && chunk.spawners > 0) tags.add("dungeon-conversion");
        if (chunk.mineshaftAnomalyScore >= mineshaftAnomalyThreshold.get() && chunk.mineshaftMarkerBlocks >= mineshaftMarkerThreshold.get()) tags.add("mineshaft-stash");
        if (chunk.buriedUtilityScore >= buriedUtilityThreshold.get()) tags.add("buried-utility");
        if (chunk.illegalBedrockScore >= illegalBedrockThreshold.get()) tags.add("illegal-bedrock");
        if (chunk.humanTouchScore >= humanTouchThreshold.get()) tags.add("human-touch");
        if (chunk.highwaySidePocketScore > 0) tags.add("highway-pocket");
        if (chunk.correlationScore > 0) tags.add("trail-correlated");
        if (tags.isEmpty() && chunk.getTotal() > 0) tags.add("storage-cluster");

        return new ArrayList<>(tags);
    }

    private void applyLeadHistoryPreview(Chunk chunk) {
        LeadHistory history = leadHistory.get(chunk.getKey());
        if (history == null) return;

        chunk.sightings = history.sightings;
        chunk.revisitPriority = Math.min(20, history.sightings * 2) + Math.max(0, chunk.getCoreScore() - history.bestScore);
    }

    private void updateLeadHistory(Chunk chunk) {
        LeadHistory history = leadHistory.computeIfAbsent(chunk.getKey(), key -> new LeadHistory());
        int previousBest = history.bestScore;
        history.sightings++;
        history.bestScore = Math.max(history.bestScore, chunk.getCoreScore());
        history.lastScore = chunk.getCoreScore();
        history.lastSeenMs = System.currentTimeMillis();
        if (history.firstSeenMs == 0L) history.firstSeenMs = history.lastSeenMs;
        history.ageGuess = chunk.ageGuess;
        history.lastLeadSummary = chunk.getLeadSummary();

        chunk.sightings = history.sightings;
        chunk.revisitPriority = Math.min(20, history.sightings * 2) + Math.max(0, chunk.getCoreScore() - previousBest);
    }

    private boolean shouldSuppressLowValueLead(Chunk chunk) {
        boolean weakStructureNoise = chunk.spawners > 0 && chunk.dungeonAnomalyScore < dungeonAnomalyThreshold.get() && chunk.humanTouchScore < humanTouchThreshold.get() && chunk.correlationScore == 0;
        boolean weakMineshaftNoise = chunk.mineshaftMarkerBlocks >= mineshaftMarkerThreshold.get() && chunk.mineshaftAnomalyScore < mineshaftAnomalyThreshold.get() && chunk.humanTouchScore < humanTouchThreshold.get() && chunk.correlationScore == 0;
        boolean noRealLead = chunk.enderStoragePairs == 0
            && chunk.buriedUtilityScore < buriedUtilityThreshold.get()
            && chunk.illegalBedrockScore < illegalBedrockThreshold.get()
            && chunk.kitStationScore == 0
            && chunk.getTotal() < minimumStorageCount.get();
        return (weakStructureNoise || weakMineshaftNoise) && noRealLead;
    }

    private void rebuildLeadSites() {
        leadSites.clear();
        for (Chunk chunk : chunks) {
            chunk.siteChunkCount = 1;
            chunk.siteScore = chunk.getCoreScore();
        }

        if (!clusterNearbyChunks.get() || chunks.isEmpty()) return;

        Set<Chunk> remaining = new LinkedHashSet<>(chunks);
        while (!remaining.isEmpty()) {
            Chunk seed = remaining.iterator().next();
            remaining.remove(seed);

            List<Chunk> members = new ArrayList<>();
            ArrayDeque<Chunk> queue = new ArrayDeque<>();
            queue.add(seed);

            while (!queue.isEmpty()) {
                Chunk current = queue.removeFirst();
                if (members.contains(current)) continue;
                members.add(current);

                Iterator<Chunk> iterator = remaining.iterator();
                while (iterator.hasNext()) {
                    Chunk candidate = iterator.next();
                    if (current.chunkDistanceTo(candidate) <= clusterRadiusChunks.get()) {
                        queue.add(candidate);
                        iterator.remove();
                    }
                }
            }

            LeadSite site = LeadSite.fromMembers(members);
            leadSites.add(site);
            for (Chunk member : members) {
                member.siteChunkCount = site.chunkCount;
                member.siteScore = site.totalScore;
            }
        }

        leadSites.sort(Comparator.comparingInt(LeadSite::priorityScore).reversed());
    }

    private void loadLeadHistory() {
        leadHistory.clear();
        File file = getLeadHistoryFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Map<String, LeadHistory> loaded = GSON.fromJson(reader, new TypeToken<Map<String, LeadHistory>>() {}.getType());
            if (loaded != null) leadHistory.putAll(loaded);
        } catch (Exception ignored) {
        }
    }

    private void saveLeadHistory() {
        try {
            File file = getLeadHistoryFile();
            file.getParentFile().mkdirs();
            try (Writer writer = new FileWriter(file)) {
                GSON.toJson(leadHistory, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private File getLeadHistoryFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "better-stash-finder"), Utils.getFileWorldName()), "lead-history.json");
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        // Sort
        rebuildLeadSites();
        chunks.sort(Comparator.comparingInt(Chunk::getPriorityScore).reversed().thenComparingInt((Chunk value) -> -value.getTotal()));

        WVerticalList list = theme.verticalList();

        // Clear
        WButton clear = list.add(theme.button("Clear")).widget();

        if (showSiteHeatmap.get() && !leadSites.isEmpty()) {
            list.add(theme.label("Lead Sites"));
            WTable siteTable = new WTable();
            list.add(siteTable);
            fillSiteTable(theme, siteTable);
        }

        WTable table = new WTable();
        if (!chunks.isEmpty()) list.add(table);

        clear.action = () -> {
            removeAllStashWaypoints(chunks);
            chunks.clear();
            leadSites.clear();
            table.clear();
            saveJson();
            saveCsv();
            saveLeadHistory();
        };

        // Chunks
        fillTable(theme, table);

        return list;
    }

    private void fillTable(GuiTheme theme, WTable table) {
        for (Chunk chunk : chunks) {
            table.add(theme.label("Pos: " + chunk.x + ", " + chunk.z));
            table.add(theme.label("Priority: " + chunk.getPriorityScore() + " | " + chunk.getLeadSummary()));

            WButton open = table.add(theme.button("Open")).widget();
            open.action = () -> mc.setScreen(new ChunkScreen(theme, chunk));

            WButton gotoBtn = table.add(theme.button("Goto")).widget();
            gotoBtn.action = () -> PathManagers.get().moveTo(new BlockPos(chunk.x, 0, chunk.z), true);

            WMinus delete = table.add(theme.minus()).widget();
            delete.action = () -> {
                if (chunks.remove(chunk)) {
                    rebuildLeadSites();
                    table.clear();
                    fillTable(theme, table);

                    saveJson();
                    saveCsv();
                    saveLeadHistory();
                    Waypoint waypoint = getWaypointByCoordinate(chunk.x, chunk.z);
                    if (waypoint != null)
                    {
                        WaypointSet waypointSet = getWaypointSet();
                        if (waypointSet != null)
                        {
                            waypointSet.remove(waypoint);
                            refreshWaypointDisplay();
                        }
                    }
                }
            };

            table.row();
        }
    }

    private void load() {
        boolean loaded = false;

        // Try to load json
        File file = getJsonFile();
        if (file.exists()) {
            try {
                FileReader reader = new FileReader(file);
                chunks = GSON.fromJson(reader, new TypeToken<List<Chunk>>() {}.getType());
                reader.close();

                for (Chunk chunk : chunks) chunk.calculatePos();

                loaded = true;
            } catch (Exception ignored) {
                if (chunks == null) chunks = new ArrayList<>();
            }
        }

        // Try to load csv
        file = getCsvFile();
        if (!loaded && file.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                reader.readLine();

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(",");
                    boolean legacyCsv = values.length <= 31;
                    Chunk chunk = new Chunk(new ChunkPos(Integer.parseInt(values[0]), Integer.parseInt(values[1])));

                    chunk.chests = Integer.parseInt(values[2]);
                    chunk.barrels = Integer.parseInt(values[3]);
                    chunk.shulkers = Integer.parseInt(values[4]);
                    chunk.enderChests = Integer.parseInt(values[5]);
                    chunk.furnaces = Integer.parseInt(values[6]);
                    chunk.dispensersDroppers = Integer.parseInt(values[7]);
                    chunk.hoppers = Integer.parseInt(values[8]);
                    if (values.length > 9) chunk.crafters = Integer.parseInt(values[9]);
                    if (values.length > 10) chunk.enderStoragePairs = Integer.parseInt(values[10]);
                    if (values.length > 11) chunk.spawners = Integer.parseInt(values[11]);
                    if (values.length > 12) chunk.dungeonAnomalyScore = Integer.parseInt(values[12]);
                    if (values.length > 13) chunk.mineshaftMarkerBlocks = Integer.parseInt(values[13]);
                    if (values.length > 14) chunk.suspiciousMineshaftBlocks = Integer.parseInt(values[14]);
                    if (values.length > 15) chunk.mineshaftAnomalyScore = Integer.parseInt(values[15]);
                    if (legacyCsv) {
                        if (values.length > 16) chunk.humanTouchScore = Integer.parseInt(values[16]);
                        if (values.length > 17) chunk.buriedUtilityScore = Integer.parseInt(values[17]);
                        if (values.length > 18) chunk.abnormalBlockScore = Integer.parseInt(values[18]);
                        if (values.length > 19) chunk.kitStationScore = Integer.parseInt(values[19]);
                        if (values.length > 20) chunk.bannerCorrelation = Integer.parseInt(values[20]);
                        if (values.length > 21) chunk.coordCorrelation = Integer.parseInt(values[21]);
                        if (values.length > 22) chunk.minecartCorrelation = Integer.parseInt(values[22]);
                        if (values.length > 23) chunk.correlationScore = Integer.parseInt(values[23]);
                        if (values.length > 24) chunk.highwaySidePocketScore = Integer.parseInt(values[24]);
                        if (values.length > 25) chunk.ageGuess = values[25];
                        if (values.length > 26) chunk.sightings = Integer.parseInt(values[26]);
                        if (values.length > 27) chunk.revisitPriority = Integer.parseInt(values[27]);
                        if (values.length > 28) chunk.siteChunkCount = Integer.parseInt(values[28]);
                        if (values.length > 29) chunk.siteScore = Integer.parseInt(values[29]);
                        if (values.length > 30) chunk.contextTags = parseContextTags(values[30]);
                    } else {
                        if (values.length > 16) chunk.illegalBedrockScore = Integer.parseInt(values[16]);
                        if (values.length > 17) chunk.humanTouchScore = Integer.parseInt(values[17]);
                        if (values.length > 18) chunk.buriedUtilityScore = Integer.parseInt(values[18]);
                        if (values.length > 19) chunk.abnormalBlockScore = Integer.parseInt(values[19]);
                        if (values.length > 20) chunk.kitStationScore = Integer.parseInt(values[20]);
                        if (values.length > 21) chunk.bannerCorrelation = Integer.parseInt(values[21]);
                        if (values.length > 22) chunk.coordCorrelation = Integer.parseInt(values[22]);
                        if (values.length > 23) chunk.minecartCorrelation = Integer.parseInt(values[23]);
                        if (values.length > 24) chunk.correlationScore = Integer.parseInt(values[24]);
                        if (values.length > 25) chunk.highwaySidePocketScore = Integer.parseInt(values[25]);
                        if (values.length > 26) chunk.ageGuess = values[26];
                        if (values.length > 27) chunk.sightings = Integer.parseInt(values[27]);
                        if (values.length > 28) chunk.revisitPriority = Integer.parseInt(values[28]);
                        if (values.length > 29) chunk.siteChunkCount = Integer.parseInt(values[29]);
                        if (values.length > 30) chunk.siteScore = Integer.parseInt(values[30]);
                        if (values.length > 31) chunk.contextTags = parseContextTags(values[31]);
                    }

                    chunks.add(chunk);
                }

                reader.close();
            } catch (Exception ignored) {
                if (chunks == null) chunks = new ArrayList<>();
            }
        }
        rebuildLeadSites();
    }

    private void saveCsv() {
        try {
            File file = getCsvFile();
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);

            writer.write("X,Z,Chests,Barrels,Shulkers,EnderChests,Furnaces,DispensersDroppers,Hoppers,Crafters,EnderStoragePairs,Spawners,DungeonAnomalyScore,MineshaftMarkers,SuspiciousMineshaftBlocks,MineshaftAnomalyScore,IllegalBedrockScore,HumanTouchScore,BuriedUtilityScore,AbnormalBlockScore,KitStationScore,BannerCorrelation,CoordCorrelation,MinecartCorrelation,CorrelationScore,HighwaySidePocketScore,AgeGuess,Sightings,RevisitPriority,SiteChunkCount,SiteScore,ContextTags\n");
            for (Chunk chunk : chunks) chunk.write(writer);

            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveJson() {
        try {
            File file = getJsonFile();
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);
            GSON.toJson(chunks, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getJsonFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "better-stash-finder"), Utils.getFileWorldName()), "stashes.json");
    }

    private File getCsvFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "better-stash-finder"), Utils.getFileWorldName()), "stashes.csv");
    }

    @Override
    public String getInfoString() {
        return leadSites.isEmpty() ? String.valueOf(chunks.size()) : leadSites.size() + "s/" + chunks.size() + "c";
    }

    private Waypoint getWaypointByCoordinate(int x, int z)
    {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) return null;

        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return null;
        for (Waypoint waypoint : waypointSet.getWaypoints())
        {
            if (waypoint.getX() == x && waypoint.getZ() == z)
            {
                return waypoint;
            }
        }
        return null;
    }

    private void removeAllStashWaypoints(List<Chunk> chunks)
    {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) return;

        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return;
        for (Chunk chunk : chunks)
        {
            Waypoint waypoint = getWaypointByCoordinate(chunk.x, chunk.z);
            if (waypoint != null)
            {
                waypointSet.remove(waypoint);
            }
        }
        refreshWaypointDisplay();
    }

    private WaypointSet getWaypointSet()
    {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) return null;

        MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (minimapSession == null) return null;
        MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
        if (currentWorld == null) return null;
        return currentWorld.getCurrentWaypointSet();
    }

    private void addToWaypoints(WaypointSet waypointSet, Chunk chunk)
    {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) return;

        int x = chunk.x;
        int z = chunk.z;

        // dont add waypoint that already exists
        if (getWaypointByCoordinate(x, z) != null) return;

        String waypointName = getWaypointName(chunk);
        if (scoreWaypoints.get()) waypointName = "[" + chunk.getPriorityScore() + "] " + waypointName;

        // set color based on total storage blocks
        int color = 0;
        if (chunk.getScore() < 20) color = 10;
        else if (chunk.getScore() < 40) color = 14;
        else if (chunk.getScore() < 80) color = 12;
        else color = 4;

        Waypoint waypoint = new Waypoint(
            x,
            70,
            z,
            waypointName,
            "S",
            color,
            0,
            false);

        waypointSet.add(waypoint);
    }

    private String getWaypointName(Chunk chunk) {
        String waypointName = "";
        if (chunk.chests > 0) waypointName += "C:" + chunk.chests;
        if (chunk.barrels > 0) waypointName += "B:" + chunk.barrels;
        if (chunk.shulkers > 0) waypointName += "S:" + chunk.shulkers;
        if (chunk.enderChests > 0) waypointName += "E:" + chunk.enderChests;
        if (chunk.hoppers > 0) waypointName += "H:" + chunk.hoppers;
        if (chunk.dispensersDroppers > 0) waypointName += "D:" + chunk.dispensersDroppers;
        if (chunk.furnaces > 0) waypointName += "F:" + chunk.furnaces;
        if (chunk.crafters > 0) waypointName += "A:" + chunk.crafters;
        if (chunk.enderStoragePairs > 0) waypointName += "P:" + chunk.enderStoragePairs;
        if (chunk.dungeonAnomalyScore > 0) waypointName += "DG:" + chunk.dungeonAnomalyScore;
        if (chunk.mineshaftAnomalyScore > 0) waypointName += "MS:" + chunk.mineshaftAnomalyScore;
        if (chunk.illegalBedrockScore > 0) waypointName += "IB:" + chunk.illegalBedrockScore;
        if (autoWaypointNotes.get() && chunk.autoNoteSuffix() != null && !chunk.autoNoteSuffix().isEmpty()) waypointName += " " + chunk.autoNoteSuffix();
        return waypointName;
    }

    private static List<String> parseContextTags(String raw) {
        if (raw == null) return new ArrayList<>();
        raw = raw.replace("\"", "");
        if (raw.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split("\\|")));
    }

    private void waypointSettingChanged(boolean enabled)
    {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) {
            if (enabled && !warnedAboutMissingWaypoints) {
                warning("Xaero waypoint support is not installed. `save-to-waypoints` will stay inactive until Xaero mods are present.");
                warnedAboutMissingWaypoints = true;
            }
            return;
        }

        if (!enabled) {
            removeAllStashWaypoints(chunks);
        } else {
            WaypointSet waypointSet = getWaypointSet();
            if (waypointSet == null) return;
            for (Chunk chunk : chunks) {
                addToWaypoints(waypointSet, chunk);
            }
            refreshWaypointDisplay();
        }
    }

    private boolean ensureXaeroPlusAvailable() {
        if (OptionalModSupport.XAERO_PLUS_AVAILABLE) return true;

        if (!warnedAboutMissingXaeroPlus) {
            error("Better Stash Finder requires XaeroPlus chunk data. Install XaeroPlus to use this module.");
            warnedAboutMissingXaeroPlus = true;
        }

        return false;
    }

    private void refreshWaypointDisplay() {
        if (!OptionalModSupport.XAERO_WAYPOINTS_AVAILABLE) return;
        if (SupportMods.xaeroMinimap == null) return;
        SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }


    public enum Mode {
        Chat,
        Toast,
        Both
    }

    public static class Chunk {
        private static final StringBuilder sb = new StringBuilder();

        public ChunkPos chunkPos;
        public transient int x, z;
        public int chests, barrels, shulkers, enderChests, furnaces, dispensersDroppers, hoppers, crafters;
        public int enderStoragePairs, spawners, dungeonAnomalyScore, mineshaftMarkerBlocks, suspiciousMineshaftBlocks, mineshaftAnomalyScore, illegalBedrockScore;
        public int humanTouchScore, buriedUtilityScore, abnormalBlockScore, kitStationScore;
        public int bannerCorrelation, coordCorrelation, minecartCorrelation, correlationScore, highwaySidePocketScore;
        public int sightings, revisitPriority, siteChunkCount = 1, siteScore;
        public String ageGuess = "unknown";
        public List<String> contextTags = new ArrayList<>();

        public Chunk(ChunkPos chunkPos) {
            this.chunkPos = chunkPos;

            calculatePos();
        }

        public void calculatePos() {
            x = chunkPos.x * 16 + 8;
            z = chunkPos.z * 16 + 8;
        }

        public int getTotal() {
            return chests + barrels + shulkers + enderChests + furnaces + dispensersDroppers + hoppers + crafters;
        }

        public int getCoreScore() {
            return chests
                + barrels
                + (shulkers * 8)
                + (enderChests * 4)
                + furnaces
                + (dispensersDroppers * 2)
                + (hoppers * 3)
                + (crafters * 5)
                + (enderStoragePairs * 8)
                + (dungeonAnomalyScore * 3)
                + (mineshaftAnomalyScore * 2)
                + (humanTouchScore * 2)
                + (buriedUtilityScore * 3)
                + (kitStationScore * 4)
                + (illegalBedrockScore * 6)
                + abnormalBlockScore;
        }

        public int getScore() {
            return getCoreScore() + correlationScore + highwaySidePocketScore + revisitPriority + Math.max(0, siteChunkCount - 1) * 2;
        }

        public int getPriorityScore() {
            return getScore() + Math.min(20, siteScore / 8);
        }

        public String getLeadSummary() {
            List<String> leads = new ArrayList<>();

            if (enderStoragePairs > 0) leads.add("ender/storage=" + enderStoragePairs);
            if (dungeonAnomalyScore > 0) leads.add("dungeon=" + dungeonAnomalyScore);
            if (mineshaftAnomalyScore > 0) leads.add("mineshaft=" + mineshaftAnomalyScore);
            if (kitStationScore > 0) leads.add("kit=" + kitStationScore);
            if (humanTouchScore > 0) leads.add("touch=" + humanTouchScore);
            if (buriedUtilityScore > 0) leads.add("utility=" + buriedUtilityScore);
            if (illegalBedrockScore > 0) leads.add("bedrock=" + illegalBedrockScore);
            if (correlationScore > 0) leads.add("trail=" + correlationScore);
            if (highwaySidePocketScore > 0) leads.add("highway=" + highwaySidePocketScore);
            if (!"unknown".equals(ageGuess)) leads.add("age=" + ageGuess);
            if (leads.isEmpty()) leads.add("storage=" + getTotal());

            return String.join(" | ", leads);
        }

        public boolean hasSuspiciousLead(BetterStashFinder module) {
            return (module.detectEnderStorageClusters.get() && enderStoragePairs >= module.minimumEnderStoragePairs.get())
                || (module.detectDungeonAnomalies.get() && dungeonAnomalyScore >= module.dungeonAnomalyThreshold.get())
                || (module.detectMineshaftAnomalies.get() && mineshaftAnomalyScore >= module.mineshaftAnomalyThreshold.get())
                || (module.detectHumanTouch.get() && humanTouchScore >= module.humanTouchThreshold.get())
                || (module.detectBuriedUtility.get() && buriedUtilityScore >= module.buriedUtilityThreshold.get())
                || (module.detectIllegalBedrock.get() && illegalBedrockScore >= module.illegalBedrockThreshold.get())
                || kitStationScore > 0
                || correlationScore > 0
                || highwaySidePocketScore > 0;
        }

        public void write(Writer writer) throws IOException {
            sb.setLength(0);
            sb.append(x).append(',').append(z).append(',');
            sb.append(chests).append(',').append(barrels).append(',').append(shulkers).append(',').append(enderChests).append(',').append(furnaces).append(',').append(dispensersDroppers).append(',').append(hoppers).append(',').append(crafters).append(',');
            sb.append(enderStoragePairs).append(',').append(spawners).append(',').append(dungeonAnomalyScore).append(',').append(mineshaftMarkerBlocks).append(',').append(suspiciousMineshaftBlocks).append(',').append(mineshaftAnomalyScore).append(',').append(illegalBedrockScore).append(',');
            sb.append(humanTouchScore).append(',').append(buriedUtilityScore).append(',').append(abnormalBlockScore).append(',').append(kitStationScore).append(',');
            sb.append(bannerCorrelation).append(',').append(coordCorrelation).append(',').append(minecartCorrelation).append(',').append(correlationScore).append(',').append(highwaySidePocketScore).append(',');
            sb.append(ageGuess).append(',').append(sightings).append(',').append(revisitPriority).append(',').append(siteChunkCount).append(',').append(siteScore).append(',');
            sb.append('"').append(String.join("|", contextTags)).append('"').append('\n');
            writer.write(sb.toString());
        }

        public boolean countsEqual(Chunk c) {
            if (c == null) return false;
            return chests == c.chests && barrels == c.barrels && shulkers == c.shulkers && enderChests == c.enderChests
                && furnaces == c.furnaces && dispensersDroppers == c.dispensersDroppers && hoppers == c.hoppers && crafters == c.crafters
                && enderStoragePairs == c.enderStoragePairs && spawners == c.spawners && dungeonAnomalyScore == c.dungeonAnomalyScore
                && mineshaftMarkerBlocks == c.mineshaftMarkerBlocks && suspiciousMineshaftBlocks == c.suspiciousMineshaftBlocks
                && mineshaftAnomalyScore == c.mineshaftAnomalyScore && illegalBedrockScore == c.illegalBedrockScore && humanTouchScore == c.humanTouchScore
                && buriedUtilityScore == c.buriedUtilityScore && abnormalBlockScore == c.abnormalBlockScore
                && kitStationScore == c.kitStationScore && bannerCorrelation == c.bannerCorrelation
                && coordCorrelation == c.coordCorrelation && minecartCorrelation == c.minecartCorrelation
                && correlationScore == c.correlationScore && highwaySidePocketScore == c.highwaySidePocketScore
                && Objects.equals(ageGuess, c.ageGuess) && siteChunkCount == c.siteChunkCount
                && siteScore == c.siteScore && Objects.equals(contextTags, c.contextTags);
        }

        public String getKey() {
            return chunkPos.x + "," + chunkPos.z;
        }

        public int chunkDistanceTo(Chunk other) {
            return Math.max(Math.abs(chunkPos.x - other.chunkPos.x), Math.abs(chunkPos.z - other.chunkPos.z));
        }

        public String autoNoteSuffix() {
            if (contextTags == null || contextTags.isEmpty()) return "";

            StringBuilder suffix = new StringBuilder();
            suffix.append('{').append(ageGuess);
            int maxTags = Math.min(2, contextTags.size());
            for (int i = 0; i < maxTags; i++) {
                suffix.append('/').append(contextTags.get(i));
            }
            suffix.append('}');
            return suffix.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chunk chunk = (Chunk) o;
            return Objects.equals(chunkPos, chunk.chunkPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkPos);
        }
    }

    private static class ChunkScreen extends WindowScreen {
        private final Chunk chunk;

        public ChunkScreen(GuiTheme theme, Chunk chunk) {
            super(theme, "Chunk at " + chunk.x + ", " + chunk.z);

            this.chunk = chunk;
        }

        @Override
        public void initWidgets() {
            WTable t = add(theme.table()).expandX().widget();

            // Total
            t.add(theme.label("Score:"));
            t.add(theme.label(chunk.getScore() + ""));
            t.row();

            t.add(theme.label("Total:"));
            t.add(theme.label(chunk.getTotal() + ""));
            t.row();

            t.add(theme.label("Priority:"));
            t.add(theme.label(chunk.getPriorityScore() + ""));
            t.row();

            t.add(theme.label("Age Guess:"));
            t.add(theme.label(chunk.ageGuess));
            t.row();

            t.add(theme.label("Contexts:"));
            t.add(theme.label(chunk.contextTags == null || chunk.contextTags.isEmpty() ? "none" : String.join(", ", chunk.contextTags)));
            t.row();

            t.add(theme.horizontalSeparator()).expandX();
            t.row();

            // Separate
            t.add(theme.label("Chests:"));
            t.add(theme.label(chunk.chests + ""));
            t.row();

            t.add(theme.label("Barrels:"));
            t.add(theme.label(chunk.barrels + ""));
            t.row();

            t.add(theme.label("Shulkers:"));
            t.add(theme.label(chunk.shulkers + ""));
            t.row();

            t.add(theme.label("Ender Chests:"));
            t.add(theme.label(chunk.enderChests + ""));
            t.row();

            t.add(theme.label("Furnaces:"));
            t.add(theme.label(chunk.furnaces + ""));
            t.row();

            t.add(theme.label("Dispensers and droppers:"));
            t.add(theme.label(chunk.dispensersDroppers + ""));
            t.row();

            t.add(theme.label("Hoppers:"));
            t.add(theme.label(chunk.hoppers + ""));
            t.row();

            t.add(theme.label("Crafters:"));
            t.add(theme.label(chunk.crafters + ""));
            t.row();

            t.add(theme.label("Ender/Storage Pairs:"));
            t.add(theme.label(chunk.enderStoragePairs + ""));
            t.row();

            t.add(theme.label("Spawners:"));
            t.add(theme.label(chunk.spawners + ""));
            t.row();

            t.add(theme.label("Dungeon Anomaly:"));
            t.add(theme.label(chunk.dungeonAnomalyScore + ""));
            t.row();

            t.add(theme.label("Mineshaft Markers:"));
            t.add(theme.label(chunk.mineshaftMarkerBlocks + ""));
            t.row();

            t.add(theme.label("Mineshaft Anomaly:"));
            t.add(theme.label(chunk.mineshaftAnomalyScore + ""));
            t.row();

            t.add(theme.label("Illegal Bedrock:"));
            t.add(theme.label(chunk.illegalBedrockScore + ""));
            t.row();

            t.add(theme.label("Human Touch:"));
            t.add(theme.label(chunk.humanTouchScore + ""));
            t.row();

            t.add(theme.label("Buried Utility:"));
            t.add(theme.label(chunk.buriedUtilityScore + ""));
            t.row();

            t.add(theme.label("Kit Station:"));
            t.add(theme.label(chunk.kitStationScore + ""));
            t.row();

            t.add(theme.label("Correlation:"));
            t.add(theme.label(chunk.correlationScore + " (B:" + chunk.bannerCorrelation + " C:" + chunk.coordCorrelation + " M:" + chunk.minecartCorrelation + ")"));
            t.row();

            t.add(theme.label("Highway Pocket:"));
            t.add(theme.label(chunk.highwaySidePocketScore + ""));
            t.row();

            t.add(theme.label("Sightings:"));
            t.add(theme.label(chunk.sightings + " | revisit " + chunk.revisitPriority));
            t.row();

            t.add(theme.label("Site Cluster:"));
            t.add(theme.label(chunk.siteChunkCount + " chunks | site score " + chunk.siteScore));
        }
    }

    private static class LeadHistory {
        public int sightings;
        public int bestScore;
        public int lastScore;
        public long firstSeenMs;
        public long lastSeenMs;
        public String ageGuess = "unknown";
        public String lastLeadSummary = "";
    }

    private static class SignalPoint {
        private final String source;
        private final int x;
        private final int z;

        private SignalPoint(String source, int x, int z) {
            this.source = source;
            this.x = x;
            this.z = z;
        }
    }

    private static class LeadSite {
        private final int centerX;
        private final int centerZ;
        private final int totalScore;
        private final int chunkCount;
        private final String summary;

        private LeadSite(int centerX, int centerZ, int totalScore, int chunkCount, String summary) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.totalScore = totalScore;
            this.chunkCount = chunkCount;
            this.summary = summary;
        }

        private int priorityScore() {
            return totalScore + (chunkCount * 6);
        }

        private static LeadSite fromMembers(List<Chunk> members) {
            int totalX = 0;
            int totalZ = 0;
            int totalScore = 0;
            LinkedHashSet<String> tags = new LinkedHashSet<>();

            for (Chunk member : members) {
                totalX += member.x;
                totalZ += member.z;
                totalScore += member.getScore();
                if (member.contextTags != null) tags.addAll(member.contextTags);
            }

            String summary = tags.isEmpty() ? "storage cluster" : String.join(", ", tags.stream().limit(3).toList());
            return new LeadSite(totalX / members.size(), totalZ / members.size(), totalScore, members.size(), summary);
        }
    }

    private void fillSiteTable(GuiTheme theme, WTable table) {
        for (LeadSite site : leadSites) {
            table.add(theme.label("Site: " + site.centerX + ", " + site.centerZ));
            table.add(theme.label("Chunks: " + site.chunkCount + " | Score: " + site.totalScore + " | " + site.summary));

            WButton gotoBtn = table.add(theme.button("Goto")).widget();
            gotoBtn.action = () -> PathManagers.get().moveTo(new BlockPos(site.centerX, 0, site.centerZ), true);

            table.row();
        }
    }

    @meteordevelopment.orbit.EventHandler(priority = EventPriority.HIGH)
    private void onOpenScreenEvent(OpenScreenEvent event) {
        if (!(event.screen instanceof DeathScreen)) return;
        if (!disableOnTeleport.get()) return;

        this.toggle();
    }

    @meteordevelopment.orbit.EventHandler(priority = EventPriority.HIGH)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (lastPosition != null)
        {
            if (disableOnTeleport.get() && mc.player.squaredDistanceTo(lastPosition) > 16 * 16) this.toggle();
        }
        lastPosition = mc.player.getEntityPos();
    }
}
