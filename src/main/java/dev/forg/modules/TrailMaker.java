package dev.forg.modules;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.forg.util.OptionalModSupport;
import dev.forg.utils.ForgPaths;
import dev.forg.util.JeffUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

import dev.forg.forg;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.Drawing;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.Queue;
import java.util.ArrayList;
import java.util.List;

public class TrailMaker extends Module
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> recording = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-recording")
        .description("Enabled = recording, Disabled = not recording.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> rotationScaling = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-scaling")
        .description("Scaling of how fast the yaw changes. 1 = instant, 0 = doesn't change")
        .defaultValue(0.1)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<String> routeName = sgGeneral.add(new StringSetting.Builder()
        .name("route-name")
        .description("Name used when saving or loading this route.")
        .defaultValue("default")
        .build()
    );

    public TrailMaker()
    {
        super(forg.STASH, "trail-maker", "Allows you to plot xaero chunk highlights on the map and then follow them in order.");
    }

    public RegistryKey<World> dimension;
    public final Queue<ChunkPos> points = new LinkedList<>();
    private boolean following = false;

    @Override
    public void onActivate()
    {
        if (!OptionalModSupport.XAERO_PLUS_AVAILABLE) {
            error("TrailMaker requires XaeroPlus drawing support. Install XaeroPlus to use this module.");
            toggle();
            return;
        }

        if (points.isEmpty())
        {
            info("You haven't added any points, toggle the recording setting and add chunk highlights on the xaero map.");
        }
    }

    @Override
    public void onDeactivate()
    {
        following = false;
        recording.set(false);
    }

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();

        WTable buttonTable = list.add(theme.table()).widget();

        WButton startFollowing = buttonTable.add(theme.button(following ? "Stop Following" : "Start Following")).widget();
        startFollowing.action = () -> {
            following = !following;
            recording.set(false);
        };

        WButton clearPoints = buttonTable.add(theme.button("Clear Points")).widget();
        clearPoints.action = () -> {
            points.clear();
            dimension = null;
        };

        WButton reversePoints = buttonTable.add(theme.button("Reverse")).widget();
        reversePoints.action = this::reversePoints;

        WButton savePoints = buttonTable.add(theme.button("Save")).widget();
        savePoints.action = this::saveRoute;

        WButton loadPoints = buttonTable.add(theme.button("Load")).widget();
        loadPoints.action = this::loadRoute;

        return list;
    }

    public boolean isRecording()
    {
        return this.isActive() && recording.get();
    }

    private void reversePoints() {
        List<ChunkPos> reversed = new ArrayList<>(points);
        java.util.Collections.reverse(reversed);
        points.clear();
        points.addAll(reversed);
    }

    private void saveRoute() {
        try {
            Files.createDirectories(routeDir());
            try (Writer writer = Files.newBufferedWriter(routeFile())) {
                RouteSave route = new RouteSave();
                route.dimension = dimension != null ? dimension.getValue().toString() : null;
                route.points = new ArrayList<>(points);
                GSON.toJson(route, writer);
            }
            info("Saved route '" + routeName.get() + "' with " + points.size() + " points.");
        } catch (IOException e) {
            error("Failed to save route: " + e.getMessage());
        }
    }

    private void loadRoute() {
        if (!Files.exists(routeFile())) {
            error("No saved route named '" + routeName.get() + "'.");
            return;
        }

        try (Reader reader = Files.newBufferedReader(routeFile())) {
            RouteSave route = GSON.fromJson(reader, RouteSave.class);
            points.clear();
            if (route != null && route.points != null) points.addAll(route.points);
            dimension = mc.world != null ? mc.world.getRegistryKey() : null;
            info("Loaded route '" + routeName.get() + "' with " + points.size() + " points.");
        } catch (Exception e) {
            error("Failed to load route: " + e.getMessage());
        }
    }

    private Path routeDir() {
        return ForgPaths.dataDir().resolve("trail-routes");
    }

    private Path routeFile() {
        return routeDir().resolve(routeName.get() + ".json");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (mc.player == null) return;
        if (following)
        {
            if (points.isEmpty())
            {
                info("Finished following the points! Disabling.");
                this.toggle();
                return;
            }
            ChunkPos goal = points.peek();
            Vec3d centerBlockPos = goal.getCenterAtY((int) mc.player.getY()).toCenterPos();

            if (dimension.equals(World.NETHER) && !mc.world.getRegistryKey().equals(World.NETHER))
            {
                centerBlockPos = centerBlockPos.multiply(8.0, 1.0, 8.0);
            }
            else if (mc.world.getRegistryKey().equals(World.NETHER) && !dimension.equals(World.NETHER))
            {
                centerBlockPos = centerBlockPos.multiply(1.0 / 8.0, 1.0, 1.0 / 8.0);
            }

            float targetYaw = (float) Rotations.getYaw(centerBlockPos);
            mc.player.setYaw(JeffUtils.smoothRotation(mc.player.getYaw(), targetYaw, rotationScaling.get()));

            if (mc.player.getEntityPos().squaredDistanceTo(centerBlockPos) < 16 * 16)
            {
                ChunkPos point = points.poll();
                ModuleManager.getModule(Drawing.class).drawingCache.removeHighlight(point.x, point.z, dimension);
            }
        }
    }

    private static class RouteSave {
        private String dimension;
        private List<ChunkPos> points;
    }
}
