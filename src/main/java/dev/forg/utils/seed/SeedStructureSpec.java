package dev.forg.utils.seed;

import meteordevelopment.meteorclient.utils.world.Dimension;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.structure.StructureKeys;
import net.minecraft.structure.StructureSetKeys;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.structure.StructureSet;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public enum SeedStructureSpec {
    ANCIENT_CITY(
        "Ancient City",
        "AC",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.ANCIENT_CITIES,
        Set.of(StructureKeys.ANCIENT_CITY),
        List.of("data/minecraft/loot_table/chests/ancient_city.json"),
        Set.of(),
        MarkerMode.FIXED,
        -51,
        "diamond"
    ),
    DESERT_PYRAMID(
        "Desert Pyramid",
        "DP",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.DESERT_PYRAMIDS,
        Set.of(StructureKeys.DESERT_PYRAMID),
        List.of("data/minecraft/loot_table/chests/desert_pyramid.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "triangle"
    ),
    MINESHAFT(
        "Mineshaft",
        "MS",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.MINESHAFTS,
        Set.of(StructureKeys.MINESHAFT, StructureKeys.MINESHAFT_MESA),
        List.of("data/minecraft/loot_table/chests/abandoned_mineshaft.json"),
        Set.of(),
        MarkerMode.FIXED,
        32,
        "diamond"
    ),
    WOODLAND_MANSION(
        "Woodland Mansion",
        "WM",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.WOODLAND_MANSIONS,
        Set.of(StructureKeys.MANSION),
        List.of("data/minecraft/loot_table/chests/woodland_mansion.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "square"
    ),
    RUINED_PORTAL(
        "Ruined Portal",
        "RP",
        EnumSet.of(Dimension.Overworld, Dimension.Nether),
        StructureSetKeys.RUINED_PORTALS,
        Set.of(
            StructureKeys.RUINED_PORTAL,
            StructureKeys.RUINED_PORTAL_DESERT,
            StructureKeys.RUINED_PORTAL_JUNGLE,
            StructureKeys.RUINED_PORTAL_SWAMP,
            StructureKeys.RUINED_PORTAL_MOUNTAIN,
            StructureKeys.RUINED_PORTAL_OCEAN,
            StructureKeys.RUINED_PORTAL_NETHER
        ),
        List.of("data/minecraft/loot_table/chests/ruined_portal.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "triangle"
    ),
    BASTION_REMNANT(
        "Bastion Remnant",
        "BR",
        EnumSet.of(Dimension.Nether),
        StructureSetKeys.NETHER_COMPLEXES,
        Set.of(StructureKeys.BASTION_REMNANT),
        List.of("data/minecraft/loot_table/chests/bastion_treasure.json"),
        Set.of("minecraft:netherite_upgrade_smithing_template"),
        MarkerMode.FIXED,
        33,
        "star"
    ),
    FORTRESS(
        "Fortress",
        "FT",
        EnumSet.of(Dimension.Nether),
        StructureSetKeys.NETHER_COMPLEXES,
        Set.of(StructureKeys.FORTRESS),
        List.of("data/minecraft/loot_table/chests/nether_bridge.json"),
        Set.of(),
        MarkerMode.FIXED,
        64,
        "star"
    ),
    END_CITY(
        "End City",
        "EC",
        EnumSet.of(Dimension.End),
        StructureSetKeys.END_CITIES,
        Set.of(StructureKeys.END_CITY),
        List.of("data/minecraft/loot_table/chests/end_city_treasure.json"),
        Set.of("minecraft:elytra", "minecraft:dragon_head"),
        MarkerMode.SURFACE,
        0,
        "diamond"
    ),
    TRIAL_CHAMBERS(
        "Trial Chambers",
        "TC",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.TRIAL_CHAMBERS,
        Set.of(StructureKeys.TRIAL_CHAMBERS),
        List.of(
            "data/minecraft/loot_table/chests/trial_chambers/reward_unique.json",
            "data/minecraft/loot_table/chests/trial_chambers/reward_ominous_unique.json"
        ),
        Set.of("minecraft:heavy_core"),
        MarkerMode.FIXED,
        -20,
        "circle"
    ),
    JUNGLE_TEMPLE(
        "Jungle Temple",
        "JT",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.JUNGLE_TEMPLES,
        Set.of(StructureKeys.JUNGLE_PYRAMID),
        List.of("data/minecraft/loot_table/chests/jungle_temple.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "triangle"
    ),
    IGLOO(
        "Igloo",
        "IG",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.IGLOOS,
        Set.of(StructureKeys.IGLOO),
        List.of("data/minecraft/loot_table/chests/igloo_chest.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "triangle"
    ),
    PILLAGER_OUTPOST(
        "Pillager Outpost",
        "PO",
        EnumSet.of(Dimension.Overworld),
        StructureSetKeys.PILLAGER_OUTPOSTS,
        Set.of(StructureKeys.PILLAGER_OUTPOST),
        List.of("data/minecraft/loot_table/chests/pillager_outpost.json"),
        Set.of(),
        MarkerMode.SURFACE,
        0,
        "square"
    );

    private final String displayName;
    private final String shortName;
    private final EnumSet<Dimension> dimensions;
    private final RegistryKey<StructureSet> structureSetKey;
    private final Set<RegistryKey<Structure>> acceptedStructures;
    private final List<String> lootTables;
    private final Set<String> specialItems;
    private final MarkerMode markerMode;
    private final int markerY;
    private final String waypointIcon;

    SeedStructureSpec(
        String displayName,
        String shortName,
        EnumSet<Dimension> dimensions,
        RegistryKey<StructureSet> structureSetKey,
        Set<RegistryKey<Structure>> acceptedStructures,
        List<String> lootTables,
        Set<String> specialItems,
        MarkerMode markerMode,
        int markerY,
        String waypointIcon
    ) {
        this.displayName = displayName;
        this.shortName = shortName;
        this.dimensions = dimensions;
        this.structureSetKey = structureSetKey;
        this.acceptedStructures = acceptedStructures;
        this.lootTables = lootTables;
        this.specialItems = specialItems;
        this.markerMode = markerMode;
        this.markerY = markerY;
        this.waypointIcon = waypointIcon;
    }

    public String displayName() {
        return displayName;
    }

    public String shortName() {
        return shortName;
    }

    public boolean supportsDimension(Dimension dimension) {
        return dimensions.contains(dimension);
    }

    public RegistryKey<StructureSet> structureSetKey() {
        return structureSetKey;
    }

    public Set<RegistryKey<Structure>> acceptedStructures() {
        return acceptedStructures;
    }

    public String waypointIcon() {
        return waypointIcon;
    }

    public boolean supportsItem(String itemId) {
        if (specialItems.contains(itemId)) return true;

        for (String lootTable : lootTables) {
            if (LootTableIndex.tableContainsItem(lootTable, itemId)) {
                return true;
            }
        }

        return false;
    }

    public BlockPos createMarkerPos(SeedWorldContext context, BlockPos locatePos) {
        int y = markerMode == MarkerMode.SURFACE ? context.surfaceY(locatePos) : markerY;
        return new BlockPos(locatePos.getX(), y, locatePos.getZ());
    }

    private enum MarkerMode {
        FIXED,
        SURFACE
    }
}
