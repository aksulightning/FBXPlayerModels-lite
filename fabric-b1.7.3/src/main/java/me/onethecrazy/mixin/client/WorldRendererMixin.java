package me.onethecrazy.mixin.client;

import me.onethecrazy.util.render.FirstPersonSelfModelRenderer;
import net.minecraft.client.render.Culler;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Inject(method = "renderEntities", at = @At("TAIL"))
    private void fbx_player_models$renderFirstPersonSelfModel(
            Vec3d cameraPosition,
            Culler culler,
            float tickDelta,
            CallbackInfo ci
    ) {
        FirstPersonSelfModelRenderer.render(cameraPosition, tickDelta);
    }
}
