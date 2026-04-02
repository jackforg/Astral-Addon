package dev.forg.mixin;

import dev.forg.modules.ElytraFlyPlusPlus;
import net.minecraft.client.MinecraftClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {

    @Unique
    private ElytraFlyPlusPlus efly = null;

    @Unique
    private ElytraFlyPlusPlus astral$getEfly() {
        if (efly != null) return efly;

        Modules modules = Modules.get();
        if (modules == null) return null;

        efly = modules.get(ElytraFlyPlusPlus.class);
        return efly;
    }

    @Inject(at = @At("RETURN"), method = "isPressed", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir)
    {
        ElytraFlyPlusPlus efly = astral$getEfly();
        KeyBinding self = (KeyBinding) (Object) this;
        if (efly != null && efly.isActive() && efly.enabled() && self == MinecraftClient.getInstance().options.forwardKey)
        {
            cir.setReturnValue(true);
        }
    }
}
