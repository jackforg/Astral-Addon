package dev.forg.mixin;

import dev.forg.modules.ForgGlow;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(Entity.class)
public class ForgGlowMixin {
    private ForgGlow astral$getModule() {
        var modules = Modules.get();
        if (modules == null) return null;
        return modules.get(ForgGlow.class);
    }

    @Inject(method = "isGlowing", at = @At("HEAD"), cancellable = true)
    private void isGlowing(CallbackInfoReturnable<Boolean> cir) {
        if (mc.player == null) return;
        ForgGlow module = astral$getModule();
        if (module == null || !module.isActive()) return;
        Entity entity = (Entity) (Object) this;
        if ((module.selfGlow.get() && entity == mc.player) || module.shouldGlow(entity.getUuid())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void getTeamColorValue(CallbackInfoReturnable<Integer> cir) {
        if (mc.player == null) return;
        ForgGlow module = astral$getModule();
        if (module == null || !module.isActive()) return;
        Entity entity = (Entity) (Object) this;
        if ((module.selfGlow.get() && entity == mc.player) || module.shouldGlow(entity.getUuid())) {
            var c = module.color.get();
            cir.setReturnValue((c.r << 16) | (c.g << 8) | c.b);
        }
    }
}
