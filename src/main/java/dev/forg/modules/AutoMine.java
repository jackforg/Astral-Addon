package dev.forg.modules;

import dev.forg.forg;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.PacketMine;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class AutoMine extends Module {
    public enum TargetMode {
        Burrow,
        Surround,
        BurrowThenSurround
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("Which enemy block positions AutoMine prefers.")
        .defaultValue(TargetMode.BurrowThenSurround)
        .build()
    );

    private final Setting<SortPriority> sortPriority = sgGeneral.add(new EnumSetting.Builder<SortPriority>()
        .name("target-priority")
        .description("How enemy players are prioritized.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("How far away an enemy can be before AutoMine ignores them.")
        .defaultValue(5.5)
        .min(0)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switches to the fastest hotbar tool before mining.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireSuitableTool = sgGeneral.add(new BoolSetting.Builder()
        .name("require-suitable-tool")
        .description("Only mines when a suitable mining tool is available.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> usePacketMine = sgGeneral.add(new BoolSetting.Builder()
        .name("use-packet-mine")
        .description("Hands targets to Meteor's built-in PacketMine when it is enabled, instead of duplicating packet mining logic here.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the block server-side when using normal breaking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> blastResistantOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("blast-resistant-only")
        .description("Only targets burrow and surround blocks with high blast resistance, like obsidian or ender chests.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the block AutoMine is currently targeting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the target shape is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the target block sides.")
        .defaultValue(new SettingColor(255, 80, 40, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the target block outline.")
        .defaultValue(new SettingColor(255, 80, 40, 255))
        .build()
    );

    private BlockPos targetPos;
    private boolean packetMineNoticeShown;

    public AutoMine() {
        super(forg.MINING, "auto-mine", "Automatically mines burrow or surround blocks around nearby enemies.");
    }

    @Override
    public void onActivate() {
        targetPos = null;
        packetMineNoticeShown = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        PlayerEntity target = TargetUtils.getPlayerTarget(targetRange.get(), sortPriority.get());
        if (target == null) {
            targetPos = null;
            return;
        }

        BlockPos nextTarget = findTargetBlock(target);
        if (nextTarget == null) {
            targetPos = null;
            return;
        }

        targetPos = nextTarget.toImmutable();
        BlockState state = mc.world.getBlockState(targetPos);

        if (!prepareTool(state)) return;

        if (usePacketMine.get()) {
            PacketMine packetMine = Modules.get().get(PacketMine.class);
            if (packetMine != null && packetMine.isActive()) {
                if (!packetMine.isMiningBlock(targetPos)) {
                    MeteorClient.EVENT_BUS.post(StartBreakingBlockEvent.get(targetPos, BlockUtils.getDirection(targetPos)));
                }
                return;
            }

            if (!packetMineNoticeShown) {
                warning("AutoMine is set to use Meteor's PacketMine, but PacketMine is not enabled. Falling back to normal breaking.");
                packetMineNoticeShown = true;
            }
        }

        Runnable breaker = () -> BlockUtils.breakBlock(targetPos, true);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), 50, breaker);
        else breaker.run();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || targetPos == null) return;
        event.renderer.box(targetPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

    private boolean prepareTool(BlockState state) {
        if (!autoSwitch.get() && !requireSuitableTool.get()) return true;

        FindItemResult tool = InvUtils.findFastestTool(state);
        if (!tool.found()) return !requireSuitableTool.get();

        if (autoSwitch.get() && mc.player.getInventory().getSelectedSlot() != tool.slot()) {
            InvUtils.swap(tool.slot(), false);
        }

        return true;
    }

    private BlockPos findTargetBlock(PlayerEntity player) {
        BlockPos burrow = player.getBlockPos();

        return switch (targetMode.get()) {
            case Burrow -> isValidTarget(burrow) ? burrow : null;
            case Surround -> findBestSurround(player);
            case BurrowThenSurround -> {
                if (isValidTarget(burrow)) yield burrow;
                yield findBestSurround(player);
            }
        };
    }

    private BlockPos findBestSurround(PlayerEntity player) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos pos = player.getBlockPos().offset(direction);
            if (!isValidTarget(pos)) continue;

            double distance = PlayerUtils.squaredDistanceTo(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }

        return best;
    }

    private boolean isValidTarget(BlockPos pos) {
        if (pos == null || !PlayerUtils.isWithinReach(pos)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return false;
        if (!BlockUtils.canBreak(pos, state)) return false;

        return !blastResistantOnly.get() || state.getBlock().getBlastResistance() >= 600;
    }
}
