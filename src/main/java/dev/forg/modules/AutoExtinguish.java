package dev.forg.modules;

import dev.forg.forg;
import dev.forg.util.WorldUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

public class AutoExtinguish extends Module {
    public enum NetherMode {
        Off,
        WhenBurning,
        Always
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNether = settings.createGroup("Nether");
    private final SettingGroup sgWater = settings.createGroup("Water");

    private final Setting<Boolean> clearFireBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-fire-blocks")
        .description("Break nearby fire blocks before trying other extinguish methods.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait between extinguish attempts.")
        .defaultValue(4)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<NetherMode> netherMode = sgNether.add(new EnumSetting.Builder<NetherMode>()
        .name("nether-mode")
        .description("How to clear persistent netherrack or soul-soil fire in the Nether.")
        .defaultValue(NetherMode.WhenBurning)
        .build()
    );

    private final Setting<Integer> netherRange = sgNether.add(new IntSetting.Builder()
        .name("nether-range")
        .description("How far away persistent Nether fire can be extinguished.")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
        .visible(() -> netherMode.get() != NetherMode.Off)
        .build()
    );

    private final Setting<Boolean> useWaterBucket = sgWater.add(new BoolSetting.Builder()
        .name("use-water-bucket")
        .description("Place water at your feet when you're burning and water can be used.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pickupWater = sgWater.add(new BoolSetting.Builder()
        .name("pickup-water")
        .description("Pick up water placed by AutoExtinguish once you're safe again.")
        .defaultValue(true)
        .visible(useWaterBucket::get)
        .build()
    );

    private final Setting<Integer> pickupDelay = sgWater.add(new IntSetting.Builder()
        .name("pickup-delay")
        .description("Ticks to wait before trying to pick up placed water.")
        .defaultValue(8)
        .min(0)
        .sliderMax(40)
        .visible(() -> useWaterBucket.get() && pickupWater.get())
        .build()
    );

    private final Setting<Boolean> rotate = sgWater.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate toward the extinguish target before using buckets.")
        .defaultValue(true)
        .build()
    );

    private int cooldownTicks;
    private int pickupDelayTicks;
    private BlockPos pendingWaterPos;

    public AutoExtinguish() {
        super(forg.UTILITY, "auto-extinguish", "Puts you out by clearing nearby fire and placing water at your feet.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (cooldownTicks > 0) cooldownTicks--;

        handlePendingPickup();

        if (cooldownTicks == 0 && shouldClearNetherFire() && breakNearbyFire(true)) return;

        if (!mc.player.isOnFire()) return;
        if (cooldownTicks > 0) return;

        if (clearFireBlocks.get() && breakNearbyFire(false)) return;
        if (useWaterBucket.get()) placeWaterAtFeet();
    }

    private void resetState() {
        cooldownTicks = 0;
        pickupDelayTicks = 0;
        pendingWaterPos = null;
    }

    private void handlePendingPickup() {
        if (!pickupWater.get() || pendingWaterPos == null) return;
        if (mc.player.isOnFire()) return;

        if (pickupDelayTicks > 0) {
            pickupDelayTicks--;
            return;
        }

        if (!isWaterSource(pendingWaterPos)) {
            pendingWaterPos = null;
            return;
        }

        FindItemResult bucket = InvUtils.findInHotbar(Items.BUCKET);
        if (!bucket.found()) return;

        if (WorldUtils.interact(pendingWaterPos, bucket, rotate.get())) {
            mc.player.swingHand(Hand.MAIN_HAND);
            cooldownTicks = actionDelay.get();
        }

        pendingWaterPos = null;
    }

    private boolean shouldClearNetherFire() {
        if (mc.world == null || mc.world.getRegistryKey() != World.NETHER) return false;
        if (netherMode.get() == NetherMode.Off) return false;
        return netherMode.get() == NetherMode.Always || mc.player.isOnFire();
    }

    private boolean breakNearbyFire(boolean persistentNetherOnly) {
        for (BlockPos pos : getNearbyFirePositions(persistentNetherOnly)) {
            if (mc.interactionManager.attackBlock(pos, net.minecraft.util.math.Direction.UP)) {
                mc.player.swingHand(Hand.MAIN_HAND);
                cooldownTicks = actionDelay.get();
                return true;
            }
        }

        return false;
    }

    private void placeWaterAtFeet() {
        if (mc.world.getRegistryKey() == World.NETHER) return;

        FindItemResult waterBucket = InvUtils.findInHotbar(Items.WATER_BUCKET);
        if (!waterBucket.found()) return;

        BlockPos supportPos = findWaterSupportPos();
        if (supportPos == null) return;

        if (WorldUtils.interact(supportPos, waterBucket, rotate.get())) {
            cooldownTicks = actionDelay.get();

            if (pickupWater.get()) {
                pendingWaterPos = supportPos.up().toImmutable();
                pickupDelayTicks = pickupDelay.get();
            } else {
                pendingWaterPos = null;
            }
        }
    }

    private BlockPos findWaterSupportPos() {
        BlockPos origin = mc.player.getBlockPos().down();

        for (BlockPos supportPos : BlockPos.iterate(origin.add(-1, 0, -1), origin.add(1, 0, 1))) {
            BlockPos targetPos = supportPos.up();
            BlockState supportState = mc.world.getBlockState(supportPos);
            BlockState targetState = mc.world.getBlockState(targetPos);

            if (supportState.getCollisionShape(mc.world, supportPos).isEmpty()) continue;
            if (!targetState.isReplaceable() && !isFire(targetState)) continue;
            if (mc.player.squaredDistanceTo(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5) > 6.25) continue;

            return supportPos.toImmutable();
        }

        return null;
    }

    private List<BlockPos> getNearbyFirePositions(boolean persistentNetherOnly) {
        BlockPos base = mc.player.getBlockPos();
        List<BlockPos> positions = new ArrayList<>();
        int radius = persistentNetherOnly ? netherRange.get() : 1;

        for (BlockPos pos : BlockPos.iterate(base.add(-radius, -1, -radius), base.add(radius, 1, radius))) {
            if (!isFire(mc.world.getBlockState(pos))) continue;
            if (persistentNetherOnly && !isPersistentNetherFire(pos)) continue;
            positions.add(pos.toImmutable());
        }

        positions.sort((a, b) -> Double.compare(
            mc.player.squaredDistanceTo(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5),
            mc.player.squaredDistanceTo(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)
        ));

        return positions;
    }

    private boolean isWaterSource(BlockPos pos) {
        return mc.world.getFluidState(pos).isIn(FluidTags.WATER) && mc.world.getFluidState(pos).isStill();
    }

    private boolean isFire(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE);
    }

    private boolean isPersistentNetherFire(BlockPos pos) {
        if (mc.world.getRegistryKey() != World.NETHER) return false;

        BlockState below = mc.world.getBlockState(pos.down());
        return below.isOf(Blocks.NETHERRACK) || below.isOf(Blocks.SOUL_SOIL);
    }
}
