package me.onethecrazy.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.FBXPlayerModelsMod;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.LivingEntityRenderExtension;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import me.onethecrazy.util.render.CustomSkinRenderData;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(LivingEntityRenderer.class)
public abstract class RenderMixin implements LivingEntityRenderExtension {
    @Unique private static final int FULL_BRIGHT_LIGHT = 0xF000F0;
    @Unique private static final boolean fbx_player_models$debugHeadLook = Boolean.getBoolean("fbxplayermodels.debugHeadLook");
    @Unique private static boolean fbx_player_models$headLookDebugLogged;

    @Override
    public void fbx_player_models$setPlayerAsNull() {
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void fbx_player_models$extractCustomModel(LivingEntity livingEntity, LivingEntityRenderState livingState, float tickDelta, CallbackInfo ci) {
        if (!(livingEntity instanceof AbstractClientPlayer renderedPlayer) || !(livingState instanceof AvatarRenderState state)) {
            return;
        }

        if (!FBXPlayerModelsClient.options().isEnabled || renderedPlayer != Minecraft.getInstance().player) {
            return;
        }

        @Nullable CacheSkin cacheResult = SkinManager.getSelfSkin();
        if (cacheResult == null) {
            return;
        }

        @Nullable List<Vertex> vertices = cacheResult.vertices;
        String animation = fbx_player_models$currentAnimation(renderedPlayer, tickDelta);
        float seconds = (renderedPlayer.tickCount + tickDelta) / 20f;
        CustomModelPose.LimbPose limbPose = CustomModelPose.LimbPose.NONE;
        if (cacheResult.skinnedModel != null) {
            boolean applyHeadLook = !"Sleep".equals(animation);
            limbPose = CustomModelPose.computeLimbPose(renderedPlayer, tickDelta, animation);
            fbx_player_models$logHeadLookDebug(animation, renderedPlayer, tickDelta, applyHeadLook);
        }

        if (cacheResult.skinnedModel == null && (vertices == null || vertices.isEmpty())) {
            return;
        }
        if (cacheResult.skinnedModel != null && cacheResult.skinnedModel.vertices.isEmpty()) {
            return;
        }

        state.setData(CustomSkinRenderData.KEY, new CustomSkinRenderData(
                vertices == null ? List.of() : vertices,
                cacheResult.skinnedModel,
                animation,
                seconds,
                limbPose,
                CustomModelPose.computeHeadLookRotation(renderedPlayer, tickDelta),
                Mth.rotLerp(tickDelta, renderedPlayer.yBodyRotO, renderedPlayer.yBodyRot),
                state.bodyRot,
                state.yRot,
                state.xRot,
                state.pose,
                state.bedOrientation,
                state.eyeHeight,
                state.lightCoords
        ));
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void fbx_player_models$submitCustomModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState, CallbackInfo ci) {
        CustomSkinRenderData renderData = state.getData(CustomSkinRenderData.KEY);
        if (renderData == null) {
            return;
        }

        List<Vertex> vertices = renderData.vertices();
        if (renderData.skinnedModel() != null) {
            boolean renderStatePoseOverride = fbx_player_models$hasRenderStatePoseOverride(state, renderData);
            CustomModelPose.HeadLookRotation headLookRotation = state.pose == Pose.SLEEPING
                    ? CustomModelPose.HeadLookRotation.NONE
                    : renderStatePoseOverride
                            ? CustomModelPose.computeHeadLookRotation(state.yRot, state.xRot)
                            : renderData.headLookRotation();
            vertices = renderData.skinnedModel().render(
                    renderData.animation(), renderData.animationSeconds(), headLookRotation, renderData.limbPose());
        }
        if (vertices == null || vertices.isEmpty()) {
            return;
        }

        PoseStack customPose = poseStack;
        customPose.pushPose();
        if (renderData.pose() == Pose.SLEEPING && renderData.bedOrientation() != null) {
            float offset = renderData.eyeHeight() - 0.1F + 1.0F;
            customPose.translate((float) -renderData.bedOrientation().getStepX() * offset, 0.0F, (float) -renderData.bedOrientation().getStepZ() * offset);
        }
        float bodyYaw = Math.abs(Mth.wrapDegrees(state.bodyRot - renderData.extractedBodyRot())) > 0.001f
                ? state.bodyRot : renderData.interpolatedBodyYaw();
        fbx_player_models$applyBodyTransform(customPose, renderData, bodyYaw);

        for (Vertex vertex : vertices) {
            RenderType layer = RenderTypes.entityCutout(vertex.texture);
            submitNodeCollector.submitCustomGeometry(customPose, layer, (entry, buffer) -> fbx_player_models$writeVertex(entry, buffer, vertex, renderData.light()));
        }
        customPose.popPose();

        if (state.nameTag != null && !state.isDiscrete) {
            submitNodeCollector.submitNameTag(poseStack, state.nameTagAttachment, 0, state.nameTag, true, FULL_BRIGHT_LIGHT, cameraRenderState);
        }

        ci.cancel();
    }

    @Unique
    private static void fbx_player_models$writeVertex(PoseStack.Pose entry, VertexConsumer buffer, Vertex vertex, int light) {
        Matrix4f matrix = entry.pose();
        buffer.addVertex(matrix, vertex.position.x, vertex.position.y, vertex.position.z)
                .setColor(vertex.color)
                .setUv(vertex.textureUV.u, vertex.textureUV.v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(entry, vertex.normals.x, vertex.normals.y, vertex.normals.z);
    }

    @Unique
    private static void fbx_player_models$applyBodyTransform(PoseStack poseStack, CustomSkinRenderData renderData, float bodyRot) {
        if (renderData.pose() == Pose.SLEEPING) {
            float yaw = renderData.bedOrientation() == null
                    ? bodyRot
                    : fbx_player_models$sleepDirectionToRotation(renderData.bedOrientation());
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(Axis.ZP.rotationDegrees(90f));
            poseStack.mulPose(Axis.YP.rotationDegrees(90f));
            return;
        }

        poseStack.mulPose(Axis.YP.rotationDegrees(-bodyRot));
    }

    @Unique
    private static boolean fbx_player_models$hasRenderStatePoseOverride(LivingEntityRenderState state, CustomSkinRenderData renderData) {
        return Math.abs(Mth.wrapDegrees(state.bodyRot - renderData.extractedBodyRot())) > 0.001f
                || Math.abs(Mth.wrapDegrees(state.yRot - renderData.extractedHeadYaw())) > 0.001f
                || Math.abs(state.xRot - renderData.extractedHeadPitch()) > 0.001f;
    }

    @Unique
    private static float fbx_player_models$sleepDirectionToRotation(Direction direction) {
        return switch (direction) {
            case SOUTH -> 90f;
            case WEST -> 0f;
            case NORTH -> 270f;
            case EAST -> 180f;
            default -> 0f;
        };
    }

    @Unique
    private boolean fbx_player_models$isWalking(AbstractClientPlayer player, float tickDelta) {
        float walkSpeed = player.walkAnimation.speed(tickDelta);
        if (walkSpeed > 0.01f) {
            return true;
        }

        if (fbx_player_models$horizontalMovementSquared(player) > 0.0004) {
            return true;
        }

        Vec3 velocity = player.getDeltaMovement();
        return velocity.x * velocity.x + velocity.z * velocity.z > 0.0004;
    }

    @Unique
    private double fbx_player_models$horizontalMovementSquared(AbstractClientPlayer player) {
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        return dx * dx + dz * dz;
    }

    @Unique
    private String fbx_player_models$currentAnimation(AbstractClientPlayer renderedPlayer, float tickDelta) {
        if (renderedPlayer.getPose() == Pose.SLEEPING) {
            return "Sleep";
        }
        if (renderedPlayer.isPassenger()) {
            return "Sit";
        }
        if (renderedPlayer.isShiftKeyDown() || renderedPlayer.isCrouching()) {
            return "Sneak";
        }
        return fbx_player_models$isWalking(renderedPlayer, tickDelta) ? "Walk" : "Idle";
    }

    @Unique
    private void fbx_player_models$logHeadLookDebug(String animation, AbstractClientPlayer renderedPlayer, float tickDelta, boolean usedPlayerLookRotation) {
        if (!fbx_player_models$debugHeadLook || fbx_player_models$headLookDebugLogged) {
            return;
        }

        float bodyYaw = Mth.rotLerp(tickDelta, renderedPlayer.yBodyRotO, renderedPlayer.yBodyRot);
        float headYaw = Mth.rotLerp(tickDelta, renderedPlayer.yRotO, renderedPlayer.getYRot());
        float relativeHeadYaw = Mth.wrapDegrees(headYaw - bodyYaw);
        float pitch = Mth.lerp(tickDelta, renderedPlayer.xRotO, renderedPlayer.getXRot());

        FBXPlayerModelsMod.LOGGER.info(
                "Head look debug animation={} bodyYaw={} headYaw={} relativeHeadYaw={} pitch={} usedPlayerLookRotation={}",
                animation,
                bodyYaw,
                headYaw,
                relativeHeadYaw,
                pitch,
                usedPlayerLookRotation
        );
        fbx_player_models$headLookDebugLogged = true;
    }

}
