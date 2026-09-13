package me.onethecrazy.util.model.animation;

import me.onethecrazy.util.model.rig.LogicalBodyPart;
import me.onethecrazy.util.model.rig.LogicalRigBinding;
import me.onethecrazy.util.objects.SkinnedModel;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LogicalRigAnimator {
    public static Map<String, SkinnedModel.Animation> proceduralAnimations(List<SkinnedModel.Bone> bones, LogicalRigBinding savedBinding) {
        LogicalRigBinding binding = savedBinding == null || savedBinding.isEmpty()
                ? LogicalRigBinding.autoBind(bones.stream().map(SkinnedModel.Bone::name).toList())
                : savedBinding;

        Map<Integer, SkinnedModel.BoneTrack> walk = new HashMap<>();
        Map<Integer, SkinnedModel.BoneTrack> sneak = new HashMap<>();
        Map<Integer, SkinnedModel.BoneTrack> sit = new HashMap<>();
        Map<Integer, SkinnedModel.BoneTrack> sleep = new HashMap<>();

        for (int i = 0; i < bones.size(); i++) {
            LogicalBodyPart part = boundPartForBone(bones.get(i).name(), binding);
            if (part == null) {
                continue;
            }

            switch (part) {
                case RIGHT_ARM -> walk.put(i, rotationTrack(0.7f, 16f, -16f, 16f));
                case LEFT_ARM -> walk.put(i, rotationTrack(0.7f, -16f, 16f, -16f));
                case RIGHT_LEG -> walk.put(i, rotationTrack(0.7f, -18f, 18f, -18f));
                case LEFT_LEG -> walk.put(i, rotationTrack(0.7f, 18f, -18f, 18f));
                case CHEST -> sneak.put(i, staticRotationTrack(12f, 0f, 0f));
            }

            if (part == LogicalBodyPart.RIGHT_ARM || part == LogicalBodyPart.LEFT_ARM) {
                sneak.put(i, rotationTrack(1f, part == LogicalBodyPart.RIGHT_ARM ? 8f : -8f, part == LogicalBodyPart.RIGHT_ARM ? 8f : -8f, part == LogicalBodyPart.RIGHT_ARM ? 8f : -8f));
            } else if (part == LogicalBodyPart.HEAD) {
                sneak.put(i, staticRotationTrack(5f, 0f, 0f));
            } else if (part == LogicalBodyPart.RIGHT_LEG || part == LogicalBodyPart.LEFT_LEG) {
                sneak.put(i, staticRotationTrack(part == LogicalBodyPart.RIGHT_LEG ? -6f : 6f, 0f, 0f));
            } else if (part == LogicalBodyPart.CHEST) {
                sit.put(i, staticRotationTrack(0f, 0f, 0f));
            }
        }

        return Map.of(
                "Walk", SkinnedModel.Animation.logicalRigDriven(0.7f, walk),
                "Sneak", SkinnedModel.Animation.logicalRigDriven(1f, sneak),
                "Sit", SkinnedModel.Animation.logicalRigDriven(1f, sit),
                "Sleep", SkinnedModel.Animation.logicalRigDriven(1f, sleep)
        );
    }

    private static SkinnedModel.BoneTrack rotationTrack(float duration, float a, float b, float c) {
        return new SkinnedModel.BoneTrack(
                List.of(),
                List.of(
                        new SkinnedModel.KeyVec3(0f, new Vector3f(a, 0f, 0f)),
                        new SkinnedModel.KeyVec3(duration / 2f, new Vector3f(b, 0f, 0f)),
                        new SkinnedModel.KeyVec3(duration, new Vector3f(c, 0f, 0f))
                ),
                List.of()
        );
    }

    private static SkinnedModel.BoneTrack staticRotationTrack(float x, float y, float z) {
        return new SkinnedModel.BoneTrack(
                List.of(),
                List.of(new SkinnedModel.KeyVec3(0f, new Vector3f(x, y, z))),
                List.of()
        );
    }

    private static LogicalBodyPart boundPartForBone(String boneName, LogicalRigBinding binding) {
        String normalizedBone = LogicalRigBinding.normalize(boneName);
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            for (String boundName : binding.namesFor(part)) {
                if (normalizedBone.equals(LogicalRigBinding.normalize(boundName))) {
                    return part;
                }
            }
        }
        return null;
    }
}

