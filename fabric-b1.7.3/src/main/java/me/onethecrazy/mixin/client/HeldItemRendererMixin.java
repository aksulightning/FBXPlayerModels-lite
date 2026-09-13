package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.util.render.FirstPersonSelfModelRenderer;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void fbx_player_models$hideVanillaFirstPersonHand(float tickDelta, CallbackInfo ci) {
        if (FirstPersonSelfModelRenderer.shouldRenderFor(FBXPlayerModelsClient.minecraft().player)) {
            ci.cancel();
        }
    }
}
