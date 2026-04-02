package dev.forg.mixin;

import dev.forg.modules.ElytraFlyPlusPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity
{
    @Shadow
    private int jumpingCooldown;

    public LivingEntityMixin(EntityType<?> type, World world) {
        super(type, world);
    }

    @Shadow
    public abstract Brain<?> getBrain();

    @Unique
    private ElytraFlyPlusPlus efly;

    @Unique
    private ElytraFlyPlusPlus astral$getEfly() {
        if (efly != null) return efly;

        Modules modules = Modules.get();
        if (modules == null) return null;

        efly = modules.get(ElytraFlyPlusPlus.class);
        return efly;
    }

    @Inject(at = @At("HEAD"), method = "tickMovement()V")
    private void tickMovement(CallbackInfo ci)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled())
        {
            this.jumpingCooldown = 0;
        }
    }

    @Inject(at = @At("HEAD"), method = "isGliding", cancellable = true)
    private void isGliding(CallbackInfoReturnable<Boolean> cir)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled())
        {
            cir.setReturnValue(true);
        }
    }
}
