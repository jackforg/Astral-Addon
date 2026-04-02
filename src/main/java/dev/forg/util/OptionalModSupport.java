package dev.forg.util;

public final class OptionalModSupport {
    public static final boolean BARITONE_AVAILABLE = isClassAvailable("baritone.api.BaritoneAPI")
        && isClassAvailable("baritone.api.pathing.goals.GoalXZ");

    public static final boolean XAERO_WAYPOINTS_AVAILABLE = isClassAvailable("xaero.common.minimap.waypoints.Waypoint")
        && isClassAvailable("xaero.hud.minimap.BuiltInHudModules")
        && isClassAvailable("xaero.map.mods.SupportMods");

    public static final boolean XAERO_PLUS_AVAILABLE = isClassAvailable("xaeroplus.XaeroPlus")
        && isClassAvailable("xaeroplus.event.ChunkDataEvent")
        && isClassAvailable("xaeroplus.module.ModuleManager");

    private OptionalModSupport() {
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className, false, OptionalModSupport.class.getClassLoader());
            return true;
        }
        catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
