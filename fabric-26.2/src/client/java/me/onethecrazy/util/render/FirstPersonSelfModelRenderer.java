package me.onethecrazy.util.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

public final class FirstPersonSelfModelRenderer {
    private static final int FULL_BRIGHT_LIGHT = 0xF000F0;

    private FirstPersonSelfModelRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(FirstPersonSelfModelRenderer::render);
    }

    public static boolean shouldRenderFor(LocalPlayer player) {
        return getRenderVertices(player, 0.0f) != null;
    }

    private static void render(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || context.levelState().cameraRenderState == null || context.levelState().cameraRenderState.pos == null) {
            return;
        }

        float tickDelta = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        @Nullable List<Vertex> vertices = getRenderVertices(player, tickDelta);
        if (vertices == null) {
            return;
        }

        Vec3 playerPos = player.getPosition(tickDelta);
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        int light = "Sit".equals(currentAnimation(player)) || minecraft.level == null
                ? FULL_BRIGHT_LIGHT
                : LightCoordsUtil.getLightCoords(minecraft.level, BlockPos.containing(playerPos));

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(playerPos.x - cameraPos.x, playerPos.y - cameraPos.y, playerPos.z - cameraPos.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(tickDelta, player.yBodyRotO, player.yBodyRot)));

        for (Vertex vertex : vertices) {
            RenderType layer = RenderTypes.entityCutout(vertex.texture);
            context.submitNodeCollector().submitCustomGeometry(poseStack, layer, (entry, buffer) -> writeVertex(entry, buffer, vertex, light));
        }
        poseStack.popPose();
    }

    private static @Nullable List<Vertex> getRenderVertices(LocalPlayer player, float tickDelta) {
        if (!isRenderAllowed(player)) {
            return null;
        }

        CacheSkin cacheSkin = SkinManager.getSelfSkin();
        if (cacheSkin == null) {
            return null;
        }

        @Nullable List<Vertex> vertices = cacheSkin.vertices;
        if (cacheSkin.skinnedModel != null) {
            String animation = currentAnimation(player);
            float seconds = (player.tickCount + tickDelta) / 20f;
            CustomModelPose.HeadLookRotation headLookRotation = CustomModelPose.computeHeadLookRotation(player, tickDelta);
            CustomModelPose.LimbPose limbPose = CustomModelPose.computeLimbPose(player, tickDelta, animation);
            vertices = cacheSkin.skinnedModel.renderWithHiddenHead(animation, seconds, headLookRotation, limbPose);
        }

        return vertices == null || vertices.isEmpty() ? null : vertices;
    }

    private static boolean isRenderAllowed(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        return FBXPlayerModelsClient.options().isEnabled
                && FBXPlayerModelsClient.options().renderSelfModelInFirstPerson
                && minecraft.getCameraEntity() == player
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON
                && !player.isSpectator()
                && !player.isInvisible()
                && player.getPose() != Pose.SLEEPING;
    }

    private static void writeVertex(PoseStack.Pose entry, VertexConsumer buffer, Vertex vertex, int light) {
        Matrix4f matrix = entry.pose();
        buffer.addVertex(matrix, vertex.position.x, vertex.position.y, vertex.position.z)
                .setColor(vertex.color)
                .setUv(vertex.textureUV.u, vertex.textureUV.v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(entry, vertex.normals.x, vertex.normals.y, vertex.normals.z);
    }

    private static boolean isWalking(LocalPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        return velocity.x * velocity.x + velocity.z * velocity.z > 0.0004;
    }

    private static String currentAnimation(LocalPlayer player) {
        if (player.isPassenger()) {
            return "Sit";
        }
        if (player.isShiftKeyDown() || player.isCrouching()) {
            return "Sneak";
        }
        return isWalking(player) ? "Walk" : "Idle";
    }

}
