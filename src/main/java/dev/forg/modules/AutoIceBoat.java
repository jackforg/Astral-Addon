package dev.forg.modules;

import dev.forg.forg;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.HorseEntity;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.StriderEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

public class AutoIceBoat extends Module {

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgRotation  = settings.createGroup("Rotation Lock");
    private final SettingGroup sgAutoWalk  = settings.createGroup("Auto Walk");
    private final SettingGroup sgAutoTrack = settings.createGroup("Auto Track");

    // General
    private final Setting<Boolean> smartMode = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-mode")
        .description("Automatically locks rotation when you mount a rideable entity.")
        .defaultValue(true).build());

    private final Setting<Keybind> turnLeftKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("turn-left-key")
        .description("Instantly snap locked heading 90 degrees to the left.")
        .defaultValue(Keybind.none()).build());

    private final Setting<Keybind> turnRightKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("turn-right-key")
        .description("Instantly snap locked heading 90 degrees to the right.")
        .defaultValue(Keybind.none()).build());

    // Rotation lock
    private final Setting<Boolean> enableRotationLock = sgRotation.add(new BoolSetting.Builder()
        .name("enable-rotation-lock")
        .description("Locks player rotation to the snapped heading.")
        .defaultValue(true).build());

    private final Setting<Boolean> lockOnActivation = sgRotation.add(new BoolSetting.Builder()
        .name("lock-on-activation")
        .description("Snap and lock to current facing when the module activates.")
        .defaultValue(true).build());

    private final Setting<Boolean> vehicleRotation = sgRotation.add(new BoolSetting.Builder()
        .name("vehicle-rotation")
        .description("Apply rotation lock to the vehicle entity as well as the player.")
        .defaultValue(true).build());

    // Auto walk
    private final Setting<Boolean> enableAutoWalk = sgAutoWalk.add(new BoolSetting.Builder()
        .name("enable-auto-walk")
        .description("Automatically holds the forward key.")
        .defaultValue(true).build());

    private final Setting<Boolean> walkInVehicles = sgAutoWalk.add(new BoolSetting.Builder()
        .name("walk-in-vehicles")
        .description("Continue auto-walking while mounted.")
        .defaultValue(true).build());

    // Auto track (Phase 2)
    private final Setting<Boolean> enableAutoTrack = sgAutoTrack.add(new BoolSetting.Builder()
        .name("auto-track")
        .description("Scan ahead and automatically turn when the ice road turns. Works best on axis-aligned roads.")
        .defaultValue(false).build());

    private final Setting<Integer> scanDistance = sgAutoTrack.add(new IntSetting.Builder()
        .name("scan-distance")
        .description("How many blocks ahead to scan for the next turn.")
        .defaultValue(64).min(10).max(200).sliderMin(10).sliderMax(128)
        .visible(() -> enableAutoTrack.get()).build());

    private final Setting<Integer> turnLeadBlocks = sgAutoTrack.add(new IntSetting.Builder()
        .name("turn-lead")
        .description("Blocks before the corner to initiate the turn. Increase for higher speeds.")
        .defaultValue(8).min(1).max(60).sliderMin(1).sliderMax(40)
        .visible(() -> enableAutoTrack.get()).build());

    private final Setting<Integer> scanInterval = sgAutoTrack.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Re-scan every N ticks. Lower = more responsive, higher = cheaper on the CPU.")
        .defaultValue(5).min(1).max(20).sliderMin(1).sliderMax(10)
        .visible(() -> enableAutoTrack.get()).build());

    private final Setting<Boolean> steerBoat = sgAutoTrack.add(new BoolSetting.Builder()
        .name("steer-boat")
        .description("Actively presses left/right while in a boat so auto-track turns the boat instead of only rotating the camera.")
        .defaultValue(true)
        .visible(() -> enableAutoTrack.get()).build());

    private final Setting<Double> steeringDeadzone = sgAutoTrack.add(new DoubleSetting.Builder()
        .name("steering-deadzone")
        .description("How close the boat yaw must be to the locked heading before steering keys are released.")
        .defaultValue(4)
        .min(0.5)
        .max(20)
        .sliderMin(1)
        .sliderMax(12)
        .visible(() -> enableAutoTrack.get() && steerBoat.get()).build());

    // Phase 3: physics-aware lead distance
    // Formula: stopping_distance = v / (1 - c)
    //   Packed/regular ice c=0.98 → 50v blocks to stop
    //   Blue ice           c=0.989 → ~91v blocks to stop
    // lead = lead-fraction * stopping_distance, floored at turn-lead
    private final Setting<Boolean> usePhysicsLead = sgAutoTrack.add(new BoolSetting.Builder()
        .name("physics-lead")
        .description("Derive turn lead from current speed and ice type instead of a fixed block count. turn-lead becomes the minimum.")
        .defaultValue(true)
        .visible(() -> enableAutoTrack.get()).build());

    private final Setting<Double> leadFraction = sgAutoTrack.add(new DoubleSetting.Builder()
        .name("lead-fraction")
        .description("Fraction of stopping distance used as turn lead (0.1 = tight, 0.3 = generous). Start low and increase if overshooting.")
        .defaultValue(0.1).min(0.02).max(0.75).sliderMin(0.02).sliderMax(0.4)
        .visible(() -> enableAutoTrack.get() && usePhysicsLead.get()).build());

    // State
    private float   lockedYaw   = 0f;
    private float   lockedPitch = 0f;
    private boolean isLocked    = false;

    private boolean turnLeftWasDown  = false;
    private boolean turnRightWasDown = false;

    private enum TurnDir { LEFT, RIGHT, NONE }
    private TurnDir nextTurn         = TurnDir.NONE;
    private int     nextTurnDistance = Integer.MAX_VALUE;
    private int     scanTimer        = 0;
    private int     turnCooldown     = 0;

    private static final int    DIRS        = 24;
    private static final float  DEG_PER_DIR = 360f / DIRS;
    private static final String[] DIR_NAMES = {
        "S","SSW","SW","WSW","W","WNW","NW","NNW",
        "N","NNE","NE","ENE","E","ESE","SE","SSE",
        "S2","SSW2","SW2","WSW2","W2","WNW2","NW2","NNW2"
    };

    public AutoIceBoat() {
        super(forg.MOVEMENT, "auto-ice-boat",
              "Rotation lock and auto-walk for ice highways, with keybind 90-degree turns and optional track-following.");
    }

    @Override
    public void onActivate() {
        if (lockOnActivation.get() && enableRotationLock.get()) lockCurrentRotation();
        scanTimer = 0; turnCooldown = 0; nextTurn = TurnDir.NONE;
    }

    @Override
    public void onDeactivate() {
        isLocked = false;
        releaseMovementKeys();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        Entity  vehicle = mc.player.getVehicle();
        boolean mounted = vehicle != null;

        if (smartMode.get() && !isLocked && mounted && isRideable(vehicle))
            lockCurrentRotation();

        // Phase 1: keybind 90-degree turns
        boolean leftDown  = isLocked && turnLeftKey.get().isPressed();
        boolean rightDown = isLocked && turnRightKey.get().isPressed();
        if (leftDown  && !turnLeftWasDown)  { snapHeading(-90f); turnCooldown = 10; nextTurn = TurnDir.NONE; }
        if (rightDown && !turnRightWasDown) { snapHeading(+90f); turnCooldown = 10; nextTurn = TurnDir.NONE; }
        turnLeftWasDown  = leftDown;
        turnRightWasDown = rightDown;

        // Phase 2: auto track
        if (enableAutoTrack.get() && isLocked) {
            if (turnCooldown > 0) {
                turnCooldown--;
            } else {
                if (nextTurn != TurnDir.NONE && nextTurnDistance > 0) {
                    // Decrement by actual speed (blocks/tick) not just 1 — at blue ice
                    // max speed (~3.6 blocks/tick) decrementing by 1 would badly lag behind
                    double vx = mc.player.getVelocity().x;
                    double vz = mc.player.getVelocity().z;
                    int moved = Math.max(1, (int) Math.ceil(Math.sqrt(vx * vx + vz * vz)));
                    nextTurnDistance = Math.max(0, nextTurnDistance - moved);
                }
                if (scanTimer <= 0) { scanTrack(); scanTimer = scanInterval.get(); }
                else scanTimer--;

                if (nextTurn != TurnDir.NONE && nextTurnDistance <= computeLead()) {
                    if (nextTurn == TurnDir.LEFT) snapHeading(-90f);
                    else                          snapHeading(+90f);
                    nextTurn         = TurnDir.NONE;
                    nextTurnDistance = Integer.MAX_VALUE;
                    turnCooldown     = 20;
                    scanTimer        = 10;
                }
            }
        }

        // Rotation lock
        if (enableRotationLock.get() && isLocked) {
            if (mounted && vehicleRotation.get() && isRideable(vehicle)) applyVehicleRotation(vehicle);
            else if (!mounted) Rotations.rotate(lockedYaw, lockedPitch);
        }

        // Auto walk
        if (enableAutoWalk.get())
            mc.options.forwardKey.setPressed(!mounted || walkInVehicles.get());

        if (mounted && vehicle instanceof BoatEntity boat && enableAutoTrack.get() && isLocked && steerBoat.get()) {
            updateBoatSteering(boat);
        } else {
            releaseSteeringKeys();
        }
    }

    private void snapHeading(float delta) {
        lockedYaw = snapToDir(lockedYaw + delta);
        isLocked  = true;
        info("Heading -> {} ({} deg)", dirName(lockedYaw), String.format("%.1f", lockedYaw));
    }

    private void lockCurrentRotation() {
        if (mc.player == null) return;
        lockedYaw   = snapToDir(mc.player.getYaw());
        lockedPitch = mc.player.getPitch();
        isLocked    = true;
        info("Locked to {} ({} deg)", dirName(lockedYaw), String.format("%.1f", lockedYaw));
    }

    /**
     * Dynamic lead distance.
     * - Physics off: returns the static turn-lead setting.
     * - Physics on:  stopping_distance = speed / (1 - friction)
     *                lead = max(turn-lead, lead-fraction * stopping_distance)
     *
     * At packed ice terminal velocity (2.0 m/tick, c=0.98):
     *   stopping_dist = 100 blocks → lead-fraction 0.1 → 10 blocks
     * At blue ice terminal velocity (3.636 m/tick, c=0.989):
     *   stopping_dist = 330 blocks → lead-fraction 0.1 → 33 blocks
     */
    private int computeLead() {
        if (!usePhysicsLead.get() || mc.player == null) return turnLeadBlocks.get();
        double vx = mc.player.getVelocity().x;
        double vz = mc.player.getVelocity().z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        float  c     = getIceFriction();
        double stopDist = speed / (1.0 - c);
        int    dynamic  = (int)(stopDist * leadFraction.get());
        return Math.max(turnLeadBlocks.get(), dynamic);
    }

    /**
     * Returns the Minecraft friction coefficient of the ice block under the player.
     * Values sourced from the wiki: packed/regular ice = 0.98, blue ice = 0.989.
     */
    private float getIceFriction() {
        if (mc.world == null) return 0.98f;
        BlockPos pos = new BlockPos(
            (int) Math.floor(mc.player.getX()),
            iceY(),
            (int) Math.floor(mc.player.getZ()));
        Block b = mc.world.getBlockState(pos).getBlock();
        if (b == Blocks.BLUE_ICE) return 0.989f;
        return 0.98f; // ICE, PACKED_ICE, FROSTED_ICE all share 0.98
    }

    private void scanTrack() {
        if (mc.world == null || !isLocked) return;

        double rad = Math.toRadians(lockedYaw);
        int fdx = (int) Math.round(-Math.sin(rad));
        int fdz = (int) Math.round( Math.cos(rad));
        int ldx =  fdz, ldz = -fdx;   // left  = CCW
        int rdx = -fdz, rdz =  fdx;   // right = CW

        int ox = (int) Math.floor(mc.player.getX());
        int oy = iceY();
        int oz = (int) Math.floor(mc.player.getZ());

        nextTurn         = TurnDir.NONE;
        nextTurnDistance = Integer.MAX_VALUE;

        for (int i = 1; i <= scanDistance.get(); i++) {
            int cx = ox + fdx * i;
            int cz = oz + fdz * i;
            if (!isIce(cx, oy, cz)) {
                if      (isIce(cx + ldx,     oy, cz + ldz)     || isIce(cx + ldx * 2, oy, cz + ldz * 2)) nextTurn = TurnDir.LEFT;
                else if (isIce(cx + rdx,     oy, cz + rdz)     || isIce(cx + rdx * 2, oy, cz + rdz * 2)) nextTurn = TurnDir.RIGHT;
                nextTurnDistance = i;
                break;
            }
        }
    }

    private int iceY() {
        int base = (int) Math.floor(mc.player.getY());
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos p = new BlockPos((int) Math.floor(mc.player.getX()), base - dy, (int) Math.floor(mc.player.getZ()));
            Block b = mc.world.getBlockState(p).getBlock();
            if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE)
                return base - dy;
        }
        return base - 1;
    }

    private boolean isIce(int x, int y, int z) {
        Block b = mc.world.getBlockState(new BlockPos(x, y, z)).getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE;
    }

    private void applyVehicleRotation(Entity vehicle) {
        float y = lockedYaw;
        if      (vehicle instanceof BoatEntity    boat)  {
            // Boats need real left/right input to steer. Hard-setting yaw here makes
            // the camera look correct while the boat keeps drifting forward.
            if (!(enableAutoTrack.get() && steerBoat.get())) {
                boat.setYaw(y);
                boat.lastYaw = y;
            }
        }
        else if (vehicle instanceof MinecartEntity mc2)   { mc2.setYaw(y); }
        else if (vehicle instanceof HorseEntity   horse) { horse.setYaw(y); horse.setBodyYaw(y); }
        else if (vehicle instanceof PigEntity     pig)   { pig.setYaw(y);   pig.setBodyYaw(y); }
        else if (vehicle instanceof StriderEntity s)     { s.setYaw(y);     s.setBodyYaw(y); }
        else if (vehicle instanceof LlamaEntity   l)     { l.setYaw(y);     l.setBodyYaw(y); }
        Rotations.rotate(lockedYaw, lockedPitch);
    }

    private void updateBoatSteering(BoatEntity boat) {
        float yawDiff = MathHelper.wrapDegrees(lockedYaw - boat.getYaw());
        boolean steerLeft = yawDiff < -steeringDeadzone.get();
        boolean steerRight = yawDiff > steeringDeadzone.get();

        mc.options.leftKey.setPressed(steerLeft);
        mc.options.rightKey.setPressed(steerRight);
    }

    private void releaseSteeringKeys() {
        if (mc.options == null) return;
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
    }

    private void releaseMovementKeys() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        releaseSteeringKeys();
    }

    private boolean isRideable(Entity e) {
        return e instanceof BoatEntity || e instanceof MinecartEntity
            || e instanceof HorseEntity || e instanceof PigEntity
            || e instanceof StriderEntity || e instanceof LlamaEntity
            || e.hasControllingPassenger();
    }

    private float snapToDir(float yaw) {
        yaw = MathHelper.wrapDegrees(yaw);
        if (yaw < 0) yaw += 360f;
        int idx = Math.round(yaw / DEG_PER_DIR) % DIRS;
        float snapped = idx * DEG_PER_DIR;
        if (snapped > 180f) snapped -= 360f;
        return snapped;
    }

    private String dirName(float yaw) {
        float n = yaw < 0 ? yaw + 360f : yaw;
        return DIR_NAMES[Math.round(n / DEG_PER_DIR) % DIRS];
    }

    public boolean isRotationLocked() { return isLocked && enableRotationLock.get(); }
    public float   getLockedYaw()     { return lockedYaw; }
    public String  getNextTurnInfo()  {
        return nextTurn == TurnDir.NONE ? "none" : nextTurn + " in " + nextTurnDistance + " blocks";
    }
}
