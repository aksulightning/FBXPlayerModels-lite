package me.onethecrazy.util.model.animation;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class CustomModelPose {
    private static final float MAX_HEAD_YAW_DEGREES = 85f;
    private static final float MAX_HEAD_PITCH_DEGREES = 90f;

    public static HeadLookRotation computeHeadLookRotation(PlayerEntity player, float tickDelta) {
        float bodyYaw = MathHelper.lerpAngleDegrees(
                tickDelta,
                player.prevBodyYaw,
                player.bodyYaw
        );

        float headYaw = MathHelper.lerpAngleDegrees(
                tickDelta,
                player.prevYaw,
                player.getYaw()
        );

        float relativeHeadYaw = MathHelper.wrapDegrees(headYaw - bodyYaw);

        float pitch = MathHelper.lerp(
                tickDelta,
                player.prevPitch,
                player.getPitch()
        );

        return createMinecraftHeadLookRotation(relativeHeadYaw, pitch);
    }

    public static HeadLookRotation computeHeadLookRotation(float relativeHeadYaw, float pitchDegrees) {
        return createMinecraftHeadLookRotation(relativeHeadYaw, pitchDegrees);
    }

    public static HeadLookRotation createMinecraftHeadLookRotation(float relativeHeadYaw, float pitchDegrees) {
        float yaw = MathHelper.clamp(MathHelper.wrapDegrees(relativeHeadYaw), -MAX_HEAD_YAW_DEGREES, MAX_HEAD_YAW_DEGREES);
        float pitch = MathHelper.clamp(pitchDegrees, -MAX_HEAD_PITCH_DEGREES, MAX_HEAD_PITCH_DEGREES);
        // Custom model skinning is already body-yaw aligned by the renderer, so local head yaw uses the inverse sign.
        return new HeadLookRotation(
                (float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch)
        );
    }

    public static LimbPose computeLimbPose(PlayerEntity player, float tickDelta, String animation) {
        if ("Sleep".equals(animation)) {
            return LimbPose.NONE;
        }
        LimbPose pose = LimbPose.NONE;
        if ("Sit".equals(animation)) {
            pose = new LimbPose(
                    new BodyPartRotation(-0.62831855f, 0f, 0f),
                    new BodyPartRotation(-0.62831855f, 0f, 0f),
                    new BodyPartRotation(-1.5707964f, 0.31415927f, 0.07853982f),
                    new BodyPartRotation(-1.5707964f, -0.31415927f, -0.07853982f));
        } else if ("Walk".equals(animation) || "Sneak".equals(animation)) {
            float progress = player.limbAnimator.getPos(tickDelta);
            float amplitude = player.limbAnimator.getSpeed(tickDelta);
            float sneakPitch = "Sneak".equals(animation) ? 0.4f : 0f;
            pose = new LimbPose(
                    new BodyPartRotation(MathHelper.cos(progress * 0.6662f + MathHelper.PI) * amplitude + sneakPitch, 0f, 0f),
                    new BodyPartRotation(MathHelper.cos(progress * 0.6662f) * amplitude + sneakPitch, 0f, 0f),
                    new BodyPartRotation(MathHelper.cos(progress * 0.6662f) * 1.4f * amplitude, 0f, 0f),
                    new BodyPartRotation(MathHelper.cos(progress * 0.6662f + MathHelper.PI) * 1.4f * amplitude, 0f, 0f));
        }
        boolean mainRight = player.getMainArm() == Arm.RIGHT;
        boolean mainHeld = !player.getMainHandStack().isEmpty();
        boolean offHeld = !player.getOffHandStack().isEmpty();
        if (mainRight ? mainHeld : offHeld) {
            BodyPartRotation r = pose.rightArm();
            pose = pose.withRightArm(new BodyPartRotation(r.pitchRadians() * 0.5f - 0.31415927f, r.yawRadians(), r.rollRadians()));
        }
        if (mainRight ? offHeld : mainHeld) {
            BodyPartRotation r = pose.leftArm();
            pose = pose.withLeftArm(new BodyPartRotation(r.pitchRadians() * 0.5f - 0.31415927f, r.yawRadians(), r.rollRadians()));
        }

        MinecraftClient client = MinecraftClient.getInstance();
        boolean breaking = player == client.player && client.interactionManager != null && client.interactionManager.isBreakingBlock();
        float swing = player.getHandSwingProgress(tickDelta);
        if (!breaking && swing <= 0f) {
            return pose;
        }
        Hand hand = breaking && !player.handSwinging ? Hand.MAIN_HAND : player.preferredHand;
        boolean rightArm = (hand == Hand.MAIN_HAND) == mainRight;
        BodyPartRotation action;
        if (breaking) {
            float phase = ((player.age + tickDelta) % 8f) / 8f;
            float chop = MathHelper.sin(phase * MathHelper.TAU);
            action = new BodyPartRotation(-1.15f - 0.55f * chop, 0.18f * chop, 0.12f * chop);
        } else {
            float ease = MathHelper.sin(MathHelper.sqrt(swing) * MathHelper.PI);
            action = new BodyPartRotation(-0.45f - 0.85f * ease, 0f, 0.18f * ease);
        }
        return rightArm ? pose.withRightArm(pose.rightArm().add(action)) : pose.withLeftArm(pose.leftArm().add(action));
    }

    public record HeadLookRotation(float yawRadians, float pitchRadians) {
        public static final HeadLookRotation NONE = new HeadLookRotation(0f, 0f);

        public Matrix4f toMatrix() {
            return new Matrix4f()
                    .rotateY(yawRadians)
                    .rotateX(pitchRadians);
        }

    }

    public record BodyPartRotation(float pitchRadians, float yawRadians, float rollRadians) {
        public static final BodyPartRotation NONE = new BodyPartRotation(0f, 0f, 0f);

        public boolean isNone() {
            return pitchRadians == 0f && yawRadians == 0f && rollRadians == 0f;
        }

        public BodyPartRotation add(BodyPartRotation other) {
            return new BodyPartRotation(
                    pitchRadians + other.pitchRadians,
                    yawRadians + other.yawRadians,
                    rollRadians + other.rollRadians
            );
        }

        public Matrix4f globalPivotDelta(Vector3f pivot) {
            return new Matrix4f()
                    .translation(pivot)
                    .rotateY(yawRadians)
                    .rotateX(pitchRadians)
                    .rotateZ(rollRadians)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
        }
    }

    public record LimbPose(
            BodyPartRotation rightArm,
            BodyPartRotation leftArm,
            BodyPartRotation rightLeg,
            BodyPartRotation leftLeg
    ) {
        public static final LimbPose NONE = new LimbPose(
                BodyPartRotation.NONE,
                BodyPartRotation.NONE,
                BodyPartRotation.NONE,
                BodyPartRotation.NONE
        );

        public boolean isNone() {
            return rightArm.isNone() && leftArm.isNone() && rightLeg.isNone() && leftLeg.isNone();
        }

        public LimbPose withRightArm(BodyPartRotation rotation) {
            return new LimbPose(rotation, leftArm, rightLeg, leftLeg);
        }

        public LimbPose withLeftArm(BodyPartRotation rotation) {
            return new LimbPose(rightArm, rotation, rightLeg, leftLeg);
        }

        public LimbPose withArmAction(LimbPose actionPose) {
            return new LimbPose(
                    rightArm.add(actionPose.rightArm),
                    leftArm.add(actionPose.leftArm),
                    rightLeg,
                    leftLeg
            );
        }
    }
}
