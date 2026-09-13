package me.onethecrazy.util.model.animation;

import net.minecraft.entity.player.PlayerEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class CustomModelPose {
    private static final float MAX_HEAD_YAW_DEGREES = 85f;
    private static final float MAX_HEAD_PITCH_DEGREES = 90f;

    private CustomModelPose() {
    }

    public static HeadLookRotation computeHeadLookRotation(PlayerEntity player, float tickDelta) {
        float bodyYaw = lerpAngle(tickDelta, player.lastBodyYaw, player.bodyYaw);
        float headYaw = lerpAngle(tickDelta, player.prevYaw, player.yaw);
        float pitch = lerp(tickDelta, player.prevPitch, player.pitch);
        return createMinecraftHeadLookRotation(wrapDegrees(headYaw - bodyYaw), pitch);
    }

    public static HeadLookRotation computeHeadLookRotation(float relativeHeadYaw, float pitchDegrees) {
        return createMinecraftHeadLookRotation(relativeHeadYaw, pitchDegrees);
    }

    public static HeadLookRotation createMinecraftHeadLookRotation(float relativeHeadYaw, float pitchDegrees) {
        float yaw = clamp(relativeHeadYaw, -MAX_HEAD_YAW_DEGREES, MAX_HEAD_YAW_DEGREES);
        float pitch = clamp(pitchDegrees, -MAX_HEAD_PITCH_DEGREES, MAX_HEAD_PITCH_DEGREES);
        return new HeadLookRotation((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch));
    }

    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    public static float lerpAngle(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    public static float wrapDegrees(float value) {
        value %= 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record HeadLookRotation(float yawRadians, float pitchRadians) {
        public static final HeadLookRotation NONE = new HeadLookRotation(0f, 0f);

        public Matrix4f toMatrix() {
            return new Matrix4f().rotateY(yawRadians).rotateX(pitchRadians);
        }

        public Matrix4f globalPivotDelta(Vector3f pivot) {
            return new Matrix4f()
                    .translation(pivot)
                    .rotateY(yawRadians)
                    .rotateX(pitchRadians)
                    .translate(-pivot.x, -pivot.y, -pivot.z);
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
