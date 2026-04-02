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
    ElytraFlyPlusPlus efly = null;

    @Inject(at = @At("RETURN"), method = "isPressed", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir)
    {
        // setting it beforehand caused a crash because meteor wasnt loaded yet
        efly = efly == null ? Modules.get().get(ElytraFlyPlusPlus.class) : efly;
        KeyBinding self = (KeyBinding) (Object) this;
        if (efly != null && efly.isActive() && efly.enabled() && self == MinecraftClient.getInstance().options.forwardKey)
        {
            cir.setReturnValue(true);
        }
    }
}
