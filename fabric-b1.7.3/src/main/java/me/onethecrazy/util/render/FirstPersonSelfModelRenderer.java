package me.onethecrazy.util.render;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.util.List;

public final class FirstPersonSelfModelRenderer {
    private FirstPersonSelfModelRenderer() {
    }

    public static boolean shouldRenderFor(ClientPlayerEntity player) {
        if (player == null
                || player != FBXPlayerModelsClient.minecraft().player
                || !FBXPlayerModelsClient.options().isEnabled
                || !FBXPlayerModelsClient.options().renderSelfModelInFirstPerson
                || FBXPlayerModelsClient.minecraft().camera != player
                || FBXPlayerModelsClient.minecraft().options.thirdPerson
                || player.isSleeping()) {
            return false;
        }

        CacheSkin skin = SkinManager.getSelfSkin();
        return skin != null && (skin.skinnedModel != null || skin.vertices != null && !skin.vertices.isEmpty());
    }

    public static void render(Vec3d cameraPosition, float tickDelta) {
        ClientPlayerEntity player = FBXPlayerModelsClient.minecraft().player;
        if (!shouldRenderFor(player)) {
            return;
        }

        CacheSkin skin = SkinManager.getSelfSkin();
        List<Vertex> vertices = BetaPlayerPoseAdapter.vertices(skin, player, tickDelta, true);
        if (vertices == null || vertices.isEmpty()) {
            return;
        }

        double playerX = player.prevX + (player.x - player.prevX) * tickDelta;
        double playerY = player.prevY + (player.y - player.prevY) * tickDelta - player.standingEyeHeight;
        double playerZ = player.prevZ + (player.z - player.prevZ) * tickDelta;
        float yaw = BetaPlayerPoseAdapter.interpolatedViewYaw(player, tickDelta);

        LegacyModelRenderer.render(vertices, () -> {
            GL11.glTranslated(playerX - cameraPosition.x, playerY - cameraPosition.y, playerZ - cameraPosition.z);
            GL11.glRotatef(-yaw, 0f, 1f, 0f);
        });
    }
}
