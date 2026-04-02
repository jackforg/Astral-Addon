package dev.forg.mixin;

import dev.forg.modules.ElytraFlyPlusPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(Entity.class)
public class EntityMixin
{
    @Shadow
    protected UUID uuid;

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

    @Inject(at = @At("HEAD"), method = "getPose()Lnet/minecraft/entity/EntityPose;", cancellable = true)
    private void getPose(CallbackInfoReturnable<EntityPose> cir)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        if (efly != null && efly.enabled() && this.uuid == mc.player.getUuid())
        {
            cir.setReturnValue(EntityPose.STANDING);
        }
    }

    @Inject(at = @At("HEAD"), method = "isSprinting()Z", cancellable = true)
    private void isSprinting(CallbackInfoReturnable<Boolean> cir)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        if (efly != null && efly.enabled() && this.uuid == mc.player.getUuid())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(at = @At("HEAD"), method = "pushAwayFrom", cancellable = true)
    private void pushAwayFrom(Entity entity, CallbackInfo ci)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        if (mc.player != null && this.uuid == mc.player.getUuid() && efly != null && efly.enabled() && !entity.getUuid().equals(this.uuid))
        {
            ci.cancel();
        }
    }
}
