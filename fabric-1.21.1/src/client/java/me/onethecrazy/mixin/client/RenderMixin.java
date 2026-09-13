package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.FBXPlayerModelsMod;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.LivingEntityRenderExtension;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(LivingEntityRenderer.class)
public abstract class RenderMixin <T extends LivingEntity> implements LivingEntityRenderExtension {
    @Unique private static final boolean fbx_player_models$debugHeadLook = Boolean.getBoolean("fbxplayermodels.debugHeadLook");
    @Unique private static boolean fbx_player_models$headLookDebugLogged;
    @Unique private AbstractClientPlayerEntity player;

    @Inject(method="render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at=@At("HEAD"), cancellable = true)
    private void onPlayerRender(T livingEntity, float yaw, float tickDelta, MatrixStack matrixStack, VertexConsumerProvider vertexConsumerProvider, int light, CallbackInfo ci){
        // We only want to hook the player rendering
        if(livingEntity instanceof AbstractClientPlayerEntity renderedPlayer){
            if (!FBXPlayerModelsClient.options().isEnabled || renderedPlayer != MinecraftClient.getInstance().player)
                return;

            player = renderedPlayer;
            @Nullable CacheSkin cacheResult = SkinManager.getSelfSkin();

            // We don't have the skin data yet
            if(cacheResult == null)
                return;

            @Nullable List<Vertex> vertices = cacheResult.vertices;
            if(cacheResult.skinnedModel != null){
                String animation = fbx_player_models$currentAnimation(renderedPlayer, tickDelta);
                float seconds = (renderedPlayer.age + tickDelta) / 20f;
                boolean applyHeadLook = !"Sleep".equals(animation);
                CustomModelPose.HeadLookRotation headLookRotation = applyHeadLook && player != null
                        ? CustomModelPose.computeHeadLookRotation(player, tickDelta)
                        : CustomModelPose.HeadLookRotation.NONE;
                CustomModelPose.LimbPose limbPose = CustomModelPose.computeLimbPose(renderedPlayer, tickDelta, animation);
                fbx_player_models$logHeadLookDebug(animation, renderedPlayer, tickDelta, applyHeadLook && player != null);
                vertices = cacheResult.skinnedModel.render(animation, seconds, headLookRotation, limbPose);
            }

            // User didn't select a skin
            if(vertices == null || vertices.isEmpty())
                return;

            matrixStack.push();

            // --- Stolen from net.minecraft.client.render.entity.LivingEntityRenderer#render ---
            if (renderedPlayer.isInPose(EntityPose.SLEEPING)) {
                Direction direction = renderedPlayer.getSleepingDirection();
                if (direction != null) {
                    float f = renderedPlayer.getStandingEyeHeight() - 0.1F + 1.0F;
                    matrixStack.translate((float)(-direction.getOffsetX()) * f, 0.0F, (float)(-direction.getOffsetZ()) * f);
                }
            }

            // Render Nametag
            renderNameTagIfShouldRender(renderedPlayer, renderedPlayer.getDisplayName(), matrixStack, vertexConsumerProvider, light);

            // Apply body yaw only; custom HEAD look is applied around its own bind pivot during skinning.
            fbx_player_models$applyBodyTransform(renderedPlayer, matrixStack, tickDelta);

            // Get Matrices
            MatrixStack.Entry entry = matrixStack.peek();
            Matrix4f matrix = entry.getPositionMatrix();

            for(Vertex v : vertices){
                RenderLayer layer = RenderLayer.getEntityCutoutNoCull(v.texture);
                VertexConsumer buffer = vertexConsumerProvider.getBuffer(layer);

                buffer.vertex(matrix, v.position.x, v.position.y, v.position.z).color(v.color).texture(v.textureUV.u, v.textureUV.v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, v.normals.x, v.normals.y, v.normals.z);
            }

            matrixStack.pop();

            ci.cancel();
        }
    }

    @Unique
    // Used to reset the rendered Player, since the flow is as following:
    // Update State (set player for UUID-getting) -> Render immediately -> Same process for other entity
    // To now reset the player we use this method.
    // This is used when rendering the Player Skin Preview.
    public void fbx_player_models$setPlayerAsNull(){
        player = null;
    }

    @Unique
    private boolean fbx_player_models$isWalking(AbstractClientPlayerEntity renderedPlayer, float tickDelta) {
        float limbSpeed = renderedPlayer.limbAnimator.getSpeed(tickDelta);
        if (limbSpeed > 0.01f) {
            return true;
        }

        if (fbx_player_models$horizontalMovementSquared(renderedPlayer) > 0.0004) {
            return true;
        }

        Vec3d velocity = renderedPlayer.getVelocity();
        return velocity.x * velocity.x + velocity.z * velocity.z > 0.0004;
    }

    @Unique
    private double fbx_player_models$horizontalMovementSquared(AbstractClientPlayerEntity renderedPlayer) {
        double dx = renderedPlayer.getX() - renderedPlayer.prevX;
        double dz = renderedPlayer.getZ() - renderedPlayer.prevZ;
        return dx * dx + dz * dz;
    }

    @Unique
    private void fbx_player_models$applyBodyTransform(AbstractClientPlayerEntity renderedPlayer, MatrixStack matrixStack, float tickDelta) {
        if (renderedPlayer.isInPose(EntityPose.SLEEPING)) {
            Direction direction = renderedPlayer.getSleepingDirection();
            float yaw = direction == null
                    ? MathHelper.lerpAngleDegrees(tickDelta, renderedPlayer.prevBodyYaw, renderedPlayer.bodyYaw)
                    : fbx_player_models$sleepDirectionToRotation(direction);
            matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
            matrixStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f));
            matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90f));
            return;
        }

        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, renderedPlayer.prevBodyYaw, renderedPlayer.bodyYaw);
        matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-bodyYaw));
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
    private String fbx_player_models$currentAnimation(AbstractClientPlayerEntity renderedPlayer, float tickDelta) {
        if (renderedPlayer.isInPose(EntityPose.SLEEPING)) {
            return "Sleep";
        }
        if (renderedPlayer.hasVehicle()) {
            return "Sit";
        }
        if (renderedPlayer.isSneaking() || renderedPlayer.isInSneakingPose()) {
            return "Sneak";
        }
        return fbx_player_models$isWalking(renderedPlayer, tickDelta) ? "Walk" : "Idle";
    }

    @Unique
    private void fbx_player_models$logHeadLookDebug(String animation, AbstractClientPlayerEntity renderedPlayer, float tickDelta, boolean usedPlayerLookRotation) {
        if (!fbx_player_models$debugHeadLook || fbx_player_models$headLookDebugLogged) {
            return;
        }

        float bodyYaw = MathHelper.lerpAngleDegrees(tickDelta, renderedPlayer.prevBodyYaw, renderedPlayer.bodyYaw);
        float headYaw = MathHelper.lerpAngleDegrees(tickDelta, renderedPlayer.prevYaw, renderedPlayer.getYaw());
        float relativeHeadYaw = MathHelper.wrapDegrees(headYaw - bodyYaw);
        float pitch = MathHelper.lerp(tickDelta, renderedPlayer.prevPitch, renderedPlayer.getPitch());

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

    @Unique
    private void renderNameTagIfShouldRender(AbstractClientPlayerEntity renderedPlayer, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light){
        if (renderedPlayer == MinecraftClient.getInstance().player) {
            return;
        }

        if (!renderedPlayer.shouldRenderName()) {
            return;
        }

        boolean notSneaking = !renderedPlayer.isSneaky();
        int yOffset = "deadmau5".equals(text.getString()) ? -10 : 0;
        matrices.push();
        matrices.translate(0.0F, renderedPlayer.getHeight() + 0.5F, 0.0F);
        matrices.multiply(MinecraftClient.getInstance().getEntityRenderDispatcher().getRotation());
        matrices.scale(0.025F, -0.025F, 0.025F);
        Matrix4f matrix4f = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
        float x = (float)(-textRenderer.getWidth((StringVisitable)text)) / 2.0F;
        int backgroundColor = (int)(MinecraftClient.getInstance().options.getTextBackgroundOpacity(0.25F) * 255.0F) << 24;
        textRenderer.draw(text, x, (float)yOffset, -2130706433, false, matrix4f, vertexConsumers, notSneaking ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL, backgroundColor, light);
        if (notSneaking) {
            textRenderer.draw(text, x, (float)yOffset, -1, false, matrix4f, vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.pack(15, 15));
        }

        matrices.pop();
    }
}
