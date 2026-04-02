package dev.forg.modules;

import dev.forg.forg;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AutoLogPlus extends Module {
    private enum MaceThreatMode {
        HOLDING,
        ABOVE_YOU,
        DIVING
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgResources = settings.createGroup("Resources");
    private final SettingGroup sgBehavior = settings.createGroup("Behavior");

    private final Setting<Boolean> logOnY = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-y")
        .description("Logs out if you are below a certain Y level.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yLevel = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-level")
        .defaultValue(256)
        .min(-128)
        .sliderRange(-128, 320)
        .visible(logOnY::get)
        .build()
    );

    private final Setting<Boolean> logArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("log-armor")
        .description("Logs out if your armor goes below a certain durability amount.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-elytra")
        .description("Ignores the elytra when checking armor durability.")
        .defaultValue(false)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Double> armorPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("armor-percent")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Boolean> logPortal = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-portal")
        .description("Logs out if you are in a portal for too long.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> portalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("portal-ticks")
        .description("The amount of ticks in a portal before you get kicked. Vanilla portal travel takes 80 ticks.")
        .defaultValue(30)
        .min(1)
        .sliderMax(70)
        .visible(logPortal::get)
        .build()
    );

    private final Setting<Boolean> logPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("log-position")
        .description("Logs out if you are within x horizontal blocks of this position.")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> position = sgGeneral.add(new BlockPosSetting.Builder()
        .name("position")
        .description("The position to log out at. Y position is ignored.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("The horizontal distance from the position to log out at.")
        .defaultValue(100)
        .sliderRange(0, 1000)
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Boolean> serverNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("server-not-responding")
        .description("Logs out if the server is not responding.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> serverNotRespondingSecs = sgGeneral.add(new DoubleSetting.Builder()
        .name("seconds-not-responding")
        .description("The amount of seconds the server is not responding before you log out.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Boolean> reconnectAfterNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("reconnect-after-not-responding")
        .description("Reconnects after the server is not responding.")
        .defaultValue(false)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Double> secondsToReconnect = sgGeneral.add(new DoubleSetting.Builder()
        .name("reconnect-seconds")
        .description("The amount of seconds to wait before reconnecting. This temporarily overwrites Meteor's AutoReconnect delay.")
        .defaultValue(60)
        .min(10)
        .sliderMax(300)
        .visible(() -> reconnectAfterNotResponding.get() && serverNotResponding.get())
        .build()
    );

    private final Setting<Boolean> logOnHealth = sgCombat.add(new BoolSetting.Builder()
        .name("log-on-health")
        .description("Logs out when your health plus absorption falls below the threshold.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> healthThreshold = sgCombat.add(new DoubleSetting.Builder()
        .name("health-threshold")
        .description("Health plus absorption threshold for logging out.")
        .defaultValue(10)
        .min(0)
        .max(40)
        .sliderMin(0)
        .sliderMax(30)
        .visible(logOnHealth::get)
        .build()
    );

    private final Setting<Boolean> predictIncomingDamage = sgCombat.add(new BoolSetting.Builder()
        .name("predict-incoming-damage")
        .description("Logs out if nearby crystal, player, bed, or fall damage would push you below the health threshold.")
        .defaultValue(true)
        .visible(logOnHealth::get)
        .build()
    );

    private final Setting<Boolean> logOnTotemPops = sgCombat.add(new BoolSetting.Builder()
        .name("log-on-totem-pops")
        .description("Logs out after popping a configurable number of totems.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> totemPops = sgCombat.add(new IntSetting.Builder()
        .name("totem-pops")
        .description("How many totem pops are allowed before logging out.")
        .defaultValue(1)
        .min(1)
        .sliderMax(10)
        .visible(logOnTotemPops::get)
        .build()
    );

    private final Setting<Boolean> logOnEndCrystal = sgCombat.add(new BoolSetting.Builder()
        .name("log-on-end-crystal")
        .description("Logs out when an end crystal is nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> crystalRange = sgCombat.add(new DoubleSetting.Builder()
        .name("crystal-range")
        .description("Maximum crystal distance before logging out.")
        .defaultValue(6)
        .min(1)
        .max(32)
        .sliderMin(1)
        .sliderMax(16)
        .visible(logOnEndCrystal::get)
        .build()
    );

    private final Setting<Boolean> logOnEnderPearl = sgCombat.add(new BoolSetting.Builder()
        .name("log-on-ender-pearl")
        .description("Logs out when an ender pearl flies close enough to be a likely engage.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> pearlRange = sgCombat.add(new DoubleSetting.Builder()
        .name("pearl-range")
        .description("Maximum pearl distance before logging out.")
        .defaultValue(12)
        .min(1)
        .max(64)
        .sliderMin(1)
        .sliderMax(24)
        .visible(logOnEnderPearl::get)
        .build()
    );

    private final Setting<Boolean> logOnMaceThreat = sgCombat.add(new BoolSetting.Builder()
        .name("log-on-mace-threat")
        .description("Logs out when a nearby player matches a mace-attack profile.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MaceThreatMode> maceThreatMode = sgCombat.add(new EnumSetting.Builder<MaceThreatMode>()
        .name("mace-threat-mode")
        .description("How strict the mace detector should be.")
        .defaultValue(MaceThreatMode.DIVING)
        .visible(logOnMaceThreat::get)
        .build()
    );

    private final Setting<Double> maceRange = sgCombat.add(new DoubleSetting.Builder()
        .name("mace-range")
        .description("Horizontal range for mace threat detection.")
        .defaultValue(12)
        .min(1)
        .max(64)
        .sliderMin(1)
        .sliderMax(24)
        .visible(logOnMaceThreat::get)
        .build()
    );

    private final Setting<Double> maceVerticalRange = sgCombat.add(new DoubleSetting.Builder()
        .name("mace-vertical-range")
        .description("How far above you a mace player can be before it counts as a threat.")
        .defaultValue(16)
        .min(1)
        .max(64)
        .sliderMin(1)
        .sliderMax(24)
        .visible(logOnMaceThreat::get)
        .build()
    );

    private final Setting<Double> maceMinDownwardSpeed = sgCombat.add(new DoubleSetting.Builder()
        .name("mace-min-downward-speed")
        .description("Required downward speed for DIVING mode.")
        .defaultValue(0.5)
        .min(0)
        .max(4)
        .sliderMin(0)
        .sliderMax(2)
        .visible(() -> logOnMaceThreat.get() && maceThreatMode.get() == MaceThreatMode.DIVING)
        .build()
    );

    private final Setting<Boolean> logOnPlayer = sgPlayers.add(new BoolSetting.Builder()
        .name("log-on-player")
        .description("Logs out when enough non-ignored players are nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgPlayers.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignores Meteor friends for player-based logout triggers.")
        .defaultValue(true)
        .visible(() -> logOnPlayer.get() || logOnMaceThreat.get())
        .build()
    );

    private final Setting<Double> playerRange = sgPlayers.add(new DoubleSetting.Builder()
        .name("player-range")
        .description("How close a player has to be before they count as a threat.")
        .defaultValue(96)
        .min(1)
        .max(512)
        .sliderMin(8)
        .sliderMax(256)
        .visible(logOnPlayer::get)
        .build()
    );

    private final Setting<Integer> playerCount = sgPlayers.add(new IntSetting.Builder()
        .name("player-count")
        .description("The number of nearby players required before logging out.")
        .defaultValue(1)
        .min(1)
        .sliderMax(8)
        .visible(logOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> playerLineOfSight = sgPlayers.add(new BoolSetting.Builder()
        .name("player-line-of-sight")
        .description("Only counts players you can directly see.")
        .defaultValue(false)
        .visible(logOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> logLowTotems = sgResources.add(new BoolSetting.Builder()
        .name("log-low-totems")
        .description("Logs out when your total totem count falls below the threshold.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minimumTotems = sgResources.add(new IntSetting.Builder()
        .name("minimum-totems")
        .description("Minimum totems to keep in inventory and offhand.")
        .defaultValue(1)
        .min(0)
        .sliderMax(16)
        .visible(logLowTotems::get)
        .build()
    );

    private final Setting<Boolean> logLowRockets = sgResources.add(new BoolSetting.Builder()
        .name("log-low-rockets")
        .description("Logs out when your firework supply drops below the threshold.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> minimumRockets = sgResources.add(new IntSetting.Builder()
        .name("minimum-rockets")
        .description("Minimum fireworks to keep in inventory.")
        .defaultValue(16)
        .min(0)
        .sliderMax(256)
        .visible(logLowRockets::get)
        .build()
    );

    private final Setting<Integer> confirmTicks = sgBehavior.add(new IntSetting.Builder()
        .name("confirm-ticks")
        .description("How many consecutive ticks a trigger must stay true before logging out. Higher is safer under lag.")
        .defaultValue(2)
        .min(1)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> pauseWhileLagging = sgBehavior.add(new BoolSetting.Builder()
        .name("pause-while-lagging")
        .description("Temporarily pauses non-network triggers while the server is lagging to reduce false positives.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> lagGraceSeconds = sgBehavior.add(new DoubleSetting.Builder()
        .name("lag-grace-seconds")
        .description("Time since the last server tick that counts as lag for trigger pausing.")
        .defaultValue(1.5)
        .min(0.25)
        .max(10)
        .sliderMin(0.25)
        .sliderMax(5)
        .visible(pauseWhileLagging::get)
        .build()
    );

    private final Setting<Boolean> toggleAutoReconnect = sgBehavior.add(new BoolSetting.Builder()
        .name("toggle-auto-reconnect")
        .description("Disables Meteor AutoReconnect on normal danger logouts.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOff = sgBehavior.add(new BoolSetting.Builder()
        .name("toggle-off")
        .description("Turns AutoLogPlus off after a successful logout trigger.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> illegalDisconnect = sgBehavior.add(new BoolSetting.Builder()
        .name("illegal-disconnect")
        .description("Disconnects from the server using the slot method.")
        .defaultValue(false)
        .build()
    );

    private final Map<String, Integer> triggerCounts = new HashMap<>();
    private final Set<String> activeTriggers = new HashSet<>();

    private int currPortalTicks;
    private int pops;
    private double oldDelay;
    private boolean autoReconnectEnabled;
    private boolean waitingForReconnection;

    public AutoLogPlus() {
        super(forg.UTILITY, "auto-log-plus", "Customizable logout triggers for 2b2t travel, PvP, and AFK automation.");
    }

    @Override
    public void onActivate() {
        currPortalTicks = 0;
        pops = 0;
        triggerCounts.clear();
        activeTriggers.clear();

        if (waitingForReconnection) {
            waitingForReconnection = false;
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            @SuppressWarnings("unchecked")
            Setting<Double> delay = (Setting<Double>) autoReconnect.settings.get("delay");
            delay.set(oldDelay);
            if (!autoReconnectEnabled && autoReconnect.isActive()) {
                autoReconnect.toggle();
            }
        }
    }

    @Override
    public void onDeactivate() {
        triggerCounts.clear();
        activeTriggers.clear();
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != EntityStatuses.USE_TOTEM_OF_UNDYING) return;

        Entity entity = packet.getEntity(mc.world);
        if (entity == null || entity != mc.player) return;

        pops++;
        if (logOnTotemPops.get() && pops >= totemPops.get()) {
            logOut("Popped " + pops + " totems.", true);
            postLogout();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.player.getAbilities().allowFlying) return;

        activeTriggers.clear();

        if (handleServerNotResponding()) return;

        if (pauseWhileLagging.get() && TickRate.INSTANCE.getTimeSinceLastTick() > lagGraceSeconds.get()) {
            currPortalTicks = 0;
            finishTriggerTick();
            return;
        }

        if (handlePortalTrigger()) return;
        if (handlePositionTrigger()) return;
        if (handleYTrigger()) return;
        if (handleArmorTrigger()) return;
        if (handleHealthTrigger()) return;
        if (handlePlayerTrigger()) return;
        if (handleCrystalTrigger()) return;
        if (handlePearlTrigger()) return;
        if (handleMaceTrigger()) return;
        if (handleTotemCountTrigger()) return;
        if (handleRocketTrigger()) return;

        finishTriggerTick();
    }

    private boolean handleServerNotResponding() {
        if (!serverNotResponding.get() || waitingForReconnection) return false;

        if (TickRate.INSTANCE.getTimeSinceLastTick() <= serverNotRespondingSecs.get()) return false;

        if (reconnectAfterNotResponding.get()) {
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            autoReconnectEnabled = autoReconnect.isActive();
            @SuppressWarnings("unchecked")
            Setting<Double> delay = (Setting<Double>) autoReconnect.settings.get("delay");
            oldDelay = delay.get();
            delay.set(secondsToReconnect.get());
            if (!autoReconnectEnabled) autoReconnect.toggle();
            waitingForReconnection = true;
        }

        logOut("Server was not responding for " + serverNotRespondingSecs.get() + " seconds.", !reconnectAfterNotResponding.get());
        postLogout();
        return true;
    }

    private boolean handlePortalTrigger() {
        if (!logPortal.get() || mc.player.portalManager == null) return false;

        if (mc.player.portalManager.isInPortal()) {
            currPortalTicks++;
            if (currPortalTicks > portalTicks.get()) {
                return tryTrigger("portal", "Player was in a portal for " + currPortalTicks + " ticks.", true);
            }
        } else {
            currPortalTicks = 0;
        }

        return false;
    }

    private boolean handlePositionTrigger() {
        if (!logPosition.get()) return false;

        double distanceToTarget = mc.player.getEntityPos().multiply(1, 0, 1).distanceTo(position.get().toCenterPos().multiply(1, 0, 1));
        if (distanceToTarget < distance.get()) {
            return tryTrigger("position", "Player was within " + String.format("%.1f", distanceToTarget) + " blocks of the target position.", true);
        }

        return false;
    }

    private boolean handleYTrigger() {
        if (!logOnY.get()) return false;

        if (mc.player.getY() < yLevel.get()) {
            return tryTrigger("y", "Player was at Y=" + String.format("%.1f", mc.player.getY()) + ", below your limit of Y=" + yLevel.get() + ".", true);
        }

        return false;
    }

    private boolean handleArmorTrigger() {
        if (!logArmor.get()) return false;

        for (int i = 0; i < 4; i++) {
            ItemStack armorPiece = mc.player.getInventory().getStack(SlotUtils.ARMOR_START + i);
            if (ignoreElytra.get() && armorPiece.getItem() == Items.ELYTRA) continue;
            if (!armorPiece.isDamageable()) continue;

            int max = armorPiece.getMaxDamage();
            int current = armorPiece.getDamage();
            double percentUndamaged = 100 - ((double) current / max) * 100;
            if (percentUndamaged < armorPercent.get()) {
                return tryTrigger("armor", "Armor durability dropped below " + armorPercent.get() + "%.", true);
            }
        }

        return false;
    }

    private boolean handleHealthTrigger() {
        if (!logOnHealth.get()) return false;

        double effectiveHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (effectiveHealth <= healthThreshold.get()) {
            return tryTrigger("health", "Effective health fell to " + String.format("%.1f", effectiveHealth) + ".", true);
        }

        if (predictIncomingDamage.get()) {
            double projectedHealth = effectiveHealth - PlayerUtils.possibleHealthReductions();
            if (projectedHealth <= healthThreshold.get()) {
                return tryTrigger("predicted-health", "Incoming damage prediction would drop you to " + String.format("%.1f", projectedHealth) + ".", true);
            }
        }

        return false;
    }

    private boolean handlePlayerTrigger() {
        if (!logOnPlayer.get()) return false;

        int playersNearby = 0;
        String nearestName = null;
        double nearestDistance = Double.MAX_VALUE;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isTrackedPlayer(player, playerRange.get(), playerLineOfSight.get())) continue;

            playersNearby++;
            double distanceToPlayer = mc.player.distanceTo(player);
            if (distanceToPlayer < nearestDistance) {
                nearestDistance = distanceToPlayer;
                nearestName = player.getGameProfile().name();
            }
        }

        if (playersNearby >= playerCount.get()) {
            String reason = playersNearby == 1 && nearestName != null
                ? "Player '" + nearestName + "' entered your configured range."
                : playersNearby + " players entered your configured range.";
            return tryTrigger("players", reason, true);
        }

        return false;
    }

    private boolean handleCrystalTrigger() {
        if (!logOnEndCrystal.get()) return false;

        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.END_CRYSTAL) continue;

            double distanceToCrystal = mc.player.distanceTo(entity);
            if (distanceToCrystal <= crystalRange.get() && distanceToCrystal < bestDistance) {
                bestDistance = distanceToCrystal;
            }
        }

        if (bestDistance != Double.MAX_VALUE) {
            return tryTrigger("crystal", "End crystal detected within " + String.format("%.1f", bestDistance) + " blocks.", true);
        }

        return false;
    }

    private boolean handlePearlTrigger() {
        if (!logOnEnderPearl.get()) return false;

        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getType() != EntityType.ENDER_PEARL) continue;

            double distanceToPearl = mc.player.distanceTo(entity);
            if (distanceToPearl <= pearlRange.get() && distanceToPearl < bestDistance) {
                bestDistance = distanceToPearl;
            }
        }

        if (bestDistance != Double.MAX_VALUE) {
            return tryTrigger("pearl", "Ender pearl detected within " + String.format("%.1f", bestDistance) + " blocks.", true);
        }

        return false;
    }

    private boolean handleMaceTrigger() {
        if (!logOnMaceThreat.get()) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isTrackedPlayer(player, maceRange.get(), false)) continue;
            if (!isHoldingMace(player)) continue;

            double horizontalDistance = horizontalDistanceTo(player);
            double verticalDiff = player.getY() - mc.player.getY();

            boolean threat = switch (maceThreatMode.get()) {
                case HOLDING -> true;
                case ABOVE_YOU -> verticalDiff >= 1 && verticalDiff <= maceVerticalRange.get();
                case DIVING -> verticalDiff >= 1
                    && verticalDiff <= maceVerticalRange.get()
                    && player.getVelocity().y <= -maceMinDownwardSpeed.get();
            };

            if (threat) {
                String reason = "Mace threat detected from " + player.getGameProfile().name()
                    + " (" + String.format("%.1f", horizontalDistance) + " blocks away).";
                return tryTrigger("mace", reason, true);
            }
        }

        return false;
    }

    private boolean handleTotemCountTrigger() {
        if (!logLowTotems.get()) return false;

        int totalTotems = countItem(Items.TOTEM_OF_UNDYING);
        if (totalTotems <= minimumTotems.get()) {
            return tryTrigger("totems", "Totems dropped to " + totalTotems + ".", true);
        }

        return false;
    }

    private boolean handleRocketTrigger() {
        if (!logLowRockets.get()) return false;

        int totalRockets = countItem(Items.FIREWORK_ROCKET);
        if (totalRockets <= minimumRockets.get()) {
            return tryTrigger("rockets", "Fireworks dropped to " + totalRockets + ".", true);
        }

        return false;
    }

    private boolean isTrackedPlayer(PlayerEntity player, double maxRange, boolean requireLineOfSight) {
        if (player == null || player == mc.player || player.isRemoved() || player.isDead()) return false;
        if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
        if (mc.player.squaredDistanceTo(player) > maxRange * maxRange) return false;
        return !requireLineOfSight || PlayerUtils.canSeeEntity(player);
    }

    private boolean isHoldingMace(PlayerEntity player) {
        return player.getMainHandStack().isOf(Items.MACE) || player.getOffHandStack().isOf(Items.MACE);
    }

    private double horizontalDistanceTo(Entity entity) {
        double dx = entity.getX() - mc.player.getX();
        double dz = entity.getZ() - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private int countItem(Item item) {
        return InvUtils.find(item).count();
    }

    private boolean tryTrigger(String key, String reason, boolean turnOffReconnect) {
        activeTriggers.add(key);

        int count = triggerCounts.getOrDefault(key, 0) + 1;
        if (count < confirmTicks.get()) {
            triggerCounts.put(key, count);
            return false;
        }

        logOut(reason, turnOffReconnect);
        postLogout();
        return true;
    }

    private void finishTriggerTick() {
        triggerCounts.keySet().removeIf(key -> !activeTriggers.contains(key));
        activeTriggers.clear();
    }

    private void postLogout() {
        triggerCounts.clear();
        activeTriggers.clear();

        if (!waitingForReconnection && toggleOff.get() && isActive()) {
            toggle();
        }
    }

    private void logOut(String reason, boolean turnOffReconnect) {
        if (mc.player == null) return;

        if (turnOffReconnect && toggleAutoReconnect.get()) {
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            if (autoReconnect.isActive()) autoReconnect.toggle();
        }

        if (illegalDisconnect.get()) {
            mc.player.networkHandler.sendChatMessage(String.valueOf((char) 0));
        } else {
            mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[AutoLogPlus] " + reason)));
        }
    }
}
