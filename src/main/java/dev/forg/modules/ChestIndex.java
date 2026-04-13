package dev.forg.modules;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.forg.forg;
import dev.forg.utils.ForgPaths;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class ChestIndex extends Module {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_LIST_TYPE = new TypeToken<List<IndexedContainer>>() {}.getType();
    private static final List<Item> DEFAULT_TRACKED_ITEMS = List.of(
        Items.ENCHANTED_GOLDEN_APPLE,
        Items.SHULKER_BOX,
        Items.TOTEM_OF_UNDYING,
        Items.END_CRYSTAL,
        Items.ENDER_CHEST,
        Items.OBSIDIAN,
        Items.FIREWORK_ROCKET,
        Items.ELYTRA
    );

    private final Path indexFile = ForgPaths.dataDir().resolve("chest_index.json");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTracked = settings.createGroup("Tracked Items");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> saveEmpty = sgGeneral.add(new BoolSetting.Builder()
        .name("save-empty")
        .description("Keep empty containers in the index.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> chatOnTracked = sgTracked.add(new BoolSetting.Builder()
        .name("chat-on-tracked")
        .description("Notify when an indexed container contains one of your tracked items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Item>> trackedItems = sgTracked.add(new ItemListSetting.Builder()
        .name("tracked-items")
        .description("Tracked items that make a saved container worth surfacing in chat and render.")
        .defaultValue(DEFAULT_TRACKED_ITEMS)
        .build()
    );

    private final Setting<Boolean> renderMatches = sgRender.add(new BoolSetting.Builder()
        .name("render-matches")
        .description("Render indexed containers that contain tracked items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> renderRange = sgRender.add(new DoubleSetting.Builder()
        .name("render-range")
        .description("How far away tracked container matches can render.")
        .defaultValue(96)
        .min(8)
        .sliderMax(256)
        .visible(renderMatches::get)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new meteordevelopment.meteorclient.settings.EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How tracked container matches are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(renderMatches::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 170, 32, 40))
        .visible(renderMatches::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 170, 32, 255))
        .visible(renderMatches::get)
        .build()
    );

    private final Map<String, IndexedContainer> indexedContainers = new LinkedHashMap<>();
    private final Set<String> announcedMatchKeys = new LinkedHashSet<>();

    private BlockPos pendingContainerPos;
    private long pendingContainerAt;
    private String pendingContainerTitle;
    private boolean usePressedLastTick;

    public ChestIndex() {
        super(forg.WORLD, "chest-index", "Indexes opened containers and highlights tracked-item storage.");
    }

    @Override
    public void onActivate() {
        indexedContainers.clear();
        announcedMatchKeys.clear();
        pendingContainerPos = null;
        pendingContainerAt = 0;
        pendingContainerTitle = null;
        usePressedLastTick = false;
        loadIndex();
    }

    @Override
    public void onDeactivate() {
        pendingContainerPos = null;
        pendingContainerAt = 0;
        pendingContainerTitle = null;
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        boolean usePressed = mc.options.useKey.isPressed();
        if (usePressed && !usePressedLastTick && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (isTrackableContainer(mc.world.getBlockState(pos).getBlock())) {
                pendingContainerPos = pos.toImmutable();
                pendingContainerAt = System.currentTimeMillis();
                pendingContainerTitle = null;
            }
        }
        usePressedLastTick = usePressed;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.screen instanceof HandledScreen<?> handledScreen)) return;
        if (pendingContainerPos == null) return;
        if (System.currentTimeMillis() - pendingContainerAt > 1500) return;

        BlockState state = mc.world.getBlockState(pendingContainerPos);
        if (!isTrackableContainer(state.getBlock())) return;
        pendingContainerTitle = handledScreen.getTitle().getString();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || pendingContainerPos == null || pendingContainerTitle == null) return;
        if (!(mc.currentScreen instanceof HandledScreen<?>)) return;
        if (System.currentTimeMillis() - pendingContainerAt > 1500) {
            pendingContainerPos = null;
            pendingContainerTitle = null;
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        int slotCount = getContainerSlotCount(handler);
        if (slotCount <= 0) return;

        BlockState state = mc.world.getBlockState(pendingContainerPos);
        if (!isTrackableContainer(state.getBlock())) {
            pendingContainerPos = null;
            pendingContainerTitle = null;
            return;
        }

        IndexedContainer container = buildContainerSnapshot(pendingContainerPos, state.getBlock(), pendingContainerTitle, handler, slotCount);
        pendingContainerPos = null;
        pendingContainerTitle = null;

        if (!saveEmpty.get() && container.totalItems <= 0) return;

        indexedContainers.put(container.key(), container);
        saveIndex();
        maybeAnnounceTracked(container);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderMatches.get() || mc.player == null || mc.world == null) return;

        for (IndexedContainer container : indexedContainers.values()) {
            if (!Objects.equals(container.dimension, mc.world.getRegistryKey().getValue().toString())) continue;
            if (!containsTrackedItems(container)) continue;

            double distanceSq = mc.player.squaredDistanceTo(container.x + 0.5, container.y + 0.5, container.z + 0.5);
            if (distanceSq > renderRange.get() * renderRange.get()) continue;

            event.renderer.box(new Box(container.x, container.y, container.z, container.x + 1, container.y + 1, container.z + 1), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        return indexedContainers.isEmpty() ? null : String.valueOf(indexedContainers.size());
    }

    private IndexedContainer buildContainerSnapshot(BlockPos pos, Block block, String title, ScreenHandler handler, int slotCount) {
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        int totalItems = 0;

        for (int i = 0; i < slotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            String itemId = Registries.ITEM.getId(stack.getItem()).toString();
            itemCounts.merge(itemId, stack.getCount(), Integer::sum);
            totalItems += stack.getCount();
        }

        return new IndexedContainer(
            mc.world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            Registries.BLOCK.getId(block).toString(),
            title == null ? block.getName().getString() : title,
            Instant.now().toString(),
            totalItems,
            itemCounts
        );
    }

    private void maybeAnnounceTracked(IndexedContainer container) {
        if (!chatOnTracked.get()) return;
        if (!containsTrackedItems(container)) return;

        String key = container.key() + "|" + String.join(",", matchingTrackedItems(container));
        if (!announcedMatchKeys.add(key)) return;

        MutableText text = Text.literal("ChestIndex found ")
            .formatted(Formatting.GOLD)
            .append(Text.literal(container.title).formatted(Formatting.YELLOW))
            .append(Text.literal(" with "))
            .append(Text.literal(String.join(", ", matchingTrackedItems(container))).formatted(Formatting.AQUA))
            .append(Text.literal(" at "))
            .append(ChatUtils.formatCoords(new net.minecraft.util.math.Vec3d(container.x, container.y, container.z)));

        ChatUtils.sendMsg(text);
        DiscordNotifs.pushModuleEvent("ChestIndex", container.title + " contains " + String.join(", ", matchingTrackedItems(container)) + " at " + container.x + ", " + container.y + ", " + container.z);
    }

    private boolean containsTrackedItems(IndexedContainer container) {
        Set<String> trackedIds = trackedItemIds();
        return container.items.keySet().stream().anyMatch(trackedIds::contains);
    }

    private List<String> matchingTrackedItems(IndexedContainer container) {
        Set<String> trackedIds = trackedItemIds();
        return container.items.keySet().stream()
            .filter(trackedIds::contains)
            .map(id -> Registries.ITEM.get(net.minecraft.util.Identifier.of(id)).getName().getString())
            .sorted()
            .collect(Collectors.toList());
    }

    private Set<String> trackedItemIds() {
        return trackedItems.get().stream()
            .map(item -> Registries.ITEM.getId(item).toString())
            .collect(Collectors.toSet());
    }

    private int getContainerSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) return generic.getRows() * 9;
        if (handler instanceof ShulkerBoxScreenHandler) return 27;
        if (handler instanceof HopperScreenHandler) return 5;
        return 0;
    }

    private boolean isTrackableContainer(Block block) {
        return block == Blocks.CHEST
            || block == Blocks.TRAPPED_CHEST
            || block == Blocks.BARREL
            || block == Blocks.SHULKER_BOX
            || block == Blocks.WHITE_SHULKER_BOX
            || block == Blocks.ORANGE_SHULKER_BOX
            || block == Blocks.MAGENTA_SHULKER_BOX
            || block == Blocks.LIGHT_BLUE_SHULKER_BOX
            || block == Blocks.YELLOW_SHULKER_BOX
            || block == Blocks.LIME_SHULKER_BOX
            || block == Blocks.PINK_SHULKER_BOX
            || block == Blocks.GRAY_SHULKER_BOX
            || block == Blocks.LIGHT_GRAY_SHULKER_BOX
            || block == Blocks.CYAN_SHULKER_BOX
            || block == Blocks.PURPLE_SHULKER_BOX
            || block == Blocks.BLUE_SHULKER_BOX
            || block == Blocks.BROWN_SHULKER_BOX
            || block == Blocks.GREEN_SHULKER_BOX
            || block == Blocks.RED_SHULKER_BOX
            || block == Blocks.BLACK_SHULKER_BOX
            || block == Blocks.ENDER_CHEST
            || block == Blocks.HOPPER;
    }

    private void loadIndex() {
        if (!Files.exists(indexFile)) return;

        try (Reader reader = Files.newBufferedReader(indexFile)) {
            List<IndexedContainer> entries = GSON.fromJson(reader, ENTRY_LIST_TYPE);
            if (entries == null) return;
            for (IndexedContainer entry : entries) {
                indexedContainers.put(entry.key(), entry);
            }
        } catch (IOException e) {
            error("Failed to load chest index: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            ForgPaths.ensureDataDir();
            try (Writer writer = Files.newBufferedWriter(indexFile)) {
                GSON.toJson(new ArrayList<>(indexedContainers.values()), ENTRY_LIST_TYPE, writer);
            }
        } catch (IOException e) {
            error("Failed to save chest index: " + e.getMessage());
        }
    }

    private static class IndexedContainer {
        private final String dimension;
        private final int x;
        private final int y;
        private final int z;
        private final String blockId;
        private final String title;
        private final String lastSeenAt;
        private final int totalItems;
        private final Map<String, Integer> items;

        private IndexedContainer(String dimension, int x, int y, int z, String blockId, String title, String lastSeenAt, int totalItems, Map<String, Integer> items) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
            this.title = title;
            this.lastSeenAt = lastSeenAt;
            this.totalItems = totalItems;
            this.items = items;
        }

        private String key() {
            return dimension + "|" + x + "|" + y + "|" + z;
        }
    }
}
