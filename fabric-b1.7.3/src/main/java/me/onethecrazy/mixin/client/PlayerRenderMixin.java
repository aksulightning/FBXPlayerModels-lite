package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import me.onethecrazy.util.render.BetaPlayerPoseAdapter;
import me.onethecrazy.util.render.LegacyModelRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.player.PlayerEntity;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerRenderMixin {
    @Inject(
            method = "render(Lnet/minecraft/entity/player/PlayerEntity;DDDFF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void fbx_player_models$renderLocalModel(
            PlayerEntity renderedPlayer,
            double x,
            double y,
            double z,
            float yaw,
            float tickDelta,
            CallbackInfo ci
    ) {
        if (!FBXPlayerModelsClient.options().isEnabled
                || renderedPlayer != FBXPlayerModelsClient.minecraft().player) {
            return;
        }

        CacheSkin skin = SkinManager.getSelfSkin();
        if (skin == null) {
            return;
        }
        List<Vertex> vertices = BetaPlayerPoseAdapter.vertices(skin, renderedPlayer, tickDelta, false);
        if (vertices == null || vertices.isEmpty()) {
            return;
        }

        LegacyModelRenderer.render(vertices, () -> {
            // Beta stores/passes the player's render Y at eye height. Vanilla's
            // PlayerEntityRenderer subtracts standingEyeHeight before delegating
            // to LivingEntityRenderer; this HEAD injection replaces that method,
            // so it must preserve the same feet-origin conversion everywhere
            // (world, inventory and other previews).
            GL11.glTranslated(x, y - renderedPlayer.standingEyeHeight, z);
            if (renderedPlayer.isSleeping()) {
                GL11.glRotatef(renderedPlayer.getSleepingRotation(), 0f, 1f, 0f);
                GL11.glRotatef(90f, 0f, 0f, 1f);
                GL11.glRotatef(90f, 0f, 1f, 0f);
            } else {
                GL11.glRotatef(-BetaPlayerPoseAdapter.interpolatedBodyYaw(renderedPlayer, tickDelta), 0f, 1f, 0f);
            }
        });
        ci.cancel();
    }
}
