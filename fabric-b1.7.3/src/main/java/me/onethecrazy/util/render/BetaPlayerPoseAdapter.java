package me.onethecrazy.util.render;

import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.List;

public final class BetaPlayerPoseAdapter {
    private BetaPlayerPoseAdapter() {
    }

    public static List<Vertex> vertices(CacheSkin skin, PlayerEntity player, float tickDelta, boolean hideHead) {
        List<Vertex> vertices = skin.vertices;
        if (skin.skinnedModel == null) {
            return vertices;
        }

        String animation = currentAnimation(player);
        float seconds = (player.age + tickDelta) / 20f;
        boolean sleeping = "Sleep".equals(animation);
        CustomModelPose.HeadLookRotation headLook = sleeping
                ? CustomModelPose.HeadLookRotation.NONE
                : CustomModelPose.computeHeadLookRotation(player, tickDelta);
        CustomModelPose.LimbPose limbs = limbPose(player, tickDelta, animation);
        if (hideHead) {
            return skin.skinnedModel.renderWithHiddenHead(animation, seconds, headLook, limbs);
        }
        // Player gaze is independent of gait/posture. The normal model render
        // applies look only to Idle, so use its existing overlay while awake;
        // otherwise Walk/Sneak/Sit silently freeze the head at the bind pose.
        return sleeping
                ? skin.skinnedModel.render(animation, seconds, headLook, limbs)
                : skin.skinnedModel.renderWithForcedHeadLook(animation, seconds, headLook, limbs);
    }

    public static String currentAnimation(PlayerEntity player) {
        if (player.isSleeping()) {
            return "Sleep";
        }
        if (player.hasVehicle()) {
            return "Sit";
        }
        if (player.isSneaking()) {
            return "Sneak";
        }
        return isWalking(player) ? "Walk" : "Idle";
    }

    public static float interpolatedBodyYaw(PlayerEntity player, float tickDelta) {
        return CustomModelPose.lerpAngle(tickDelta, player.lastBodyYaw, player.bodyYaw);
    }

    public static float interpolatedViewYaw(PlayerEntity player, float tickDelta) {
        return CustomModelPose.lerpAngle(tickDelta, player.prevYaw, player.yaw);
    }

    private static boolean isWalking(PlayerEntity player) {
        if (player.walkAnimationSpeed > 0.01f) {
            return true;
        }
        double dx = player.x - player.prevX;
        double dz = player.z - player.prevZ;
        return dx * dx + dz * dz > 0.0004
                || player.velocityX * player.velocityX + player.velocityZ * player.velocityZ > 0.0004;
    }

    private static CustomModelPose.LimbPose limbPose(PlayerEntity player, float tickDelta, String animation) {
        if ("Sit".equals(animation)) {
            return sittingPose();
        }

        CustomModelPose.LimbPose action = handActionPose(player, tickDelta);
        if (!"Walk".equals(animation) && !"Sneak".equals(animation)) {
            return action;
        }

        boolean sneaking = "Sneak".equals(animation);
        float progress = player.walkAnimationProgress;
        float amplitude = CustomModelPose.lerp(
                tickDelta,
                player.lastWalkAnimationSpeed,
                player.walkAnimationSpeed
        );
        if (amplitude <= 0.01f) {
            progress = (player.age + tickDelta) * 0.9f;
            amplitude = sneaking ? 0.45f : 1f;
        }

        float armAmplitude = amplitude;
        float legAmplitude = 1.4f * amplitude;
        float sneakArmPitch = sneaking ? 0.4f : 0f;
        CustomModelPose.LimbPose walk = new CustomModelPose.LimbPose(
                rotation(MathHelper.cos(progress * 0.6662f + (float) Math.PI) * armAmplitude + sneakArmPitch),
                rotation(MathHelper.cos(progress * 0.6662f) * armAmplitude + sneakArmPitch),
                new CustomModelPose.BodyPartRotation(MathHelper.cos(progress * 0.6662f) * legAmplitude, 0.005f, 0.005f),
                new CustomModelPose.BodyPartRotation(MathHelper.cos(progress * 0.6662f + (float) Math.PI) * legAmplitude, -0.005f, -0.005f)
        );
        return walk.withArmAction(action);
    }

    private static CustomModelPose.BodyPartRotation rotation(float pitch) {
        return new CustomModelPose.BodyPartRotation(pitch, 0f, 0f);
    }

    private static CustomModelPose.LimbPose sittingPose() {
        return new CustomModelPose.LimbPose(
                rotation(-0.62831855f),
                rotation(-0.62831855f),
                rotation(-1.5707964f),
                rotation(-1.5707964f)
        );
    }

    private static CustomModelPose.LimbPose handActionPose(PlayerEntity player, float tickDelta) {
        CustomModelPose.BodyPartRotation held = player.getHeldItem() == null
                ? CustomModelPose.BodyPartRotation.NONE
                : rotation(-0.2f);
        float swing = player.getHandSwingProgress(tickDelta);
        if (swing <= 0f) {
            return held.isNone()
                    ? CustomModelPose.LimbPose.NONE
                    : CustomModelPose.LimbPose.NONE.withRightArm(held);
        }

        float ease = MathHelper.sin(MathHelper.sqrt(swing) * (float) Math.PI);
        CustomModelPose.BodyPartRotation action = new CustomModelPose.BodyPartRotation(
                -0.45f - 0.85f * ease,
                0f,
                0.18f * ease
        );
        return CustomModelPose.LimbPose.NONE.withRightArm(held.add(action));
    }
}
