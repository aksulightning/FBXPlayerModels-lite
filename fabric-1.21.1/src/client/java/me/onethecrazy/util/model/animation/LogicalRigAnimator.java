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
        List<String> names = bones.stream().map(SkinnedModel.Bone::name).toList();
        Map<Integer, SkinnedModel.BoneTrack> walk = new HashMap<>();
        Map<Integer, SkinnedModel.BoneTrack> sneak = new HashMap<>();
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            int i = LogicalRigBinding.resolveBoneIndex(names, savedBinding, part);
            if (i < 0) {
                continue;
            }
            switch (part) {
                case RIGHT_ARM -> walk.put(i, rotationTrack(0.7f, 16f, -16f, 16f));
                case LEFT_ARM -> walk.put(i, rotationTrack(0.7f, -16f, 16f, -16f));
                case RIGHT_LEG -> walk.put(i, rotationTrack(0.7f, -18f, 18f, -18f));
                case LEFT_LEG -> walk.put(i, rotationTrack(0.7f, 18f, -18f, 18f));
                case CHEST -> sneak.put(i, staticRotationTrack(12f, 0f, 0f));
            }
        }

        return Map.of(
                "Idle", SkinnedModel.Animation.logicalRigDriven(1f, Map.of()),
                "Walk", SkinnedModel.Animation.logicalRigDriven(0.7f, walk),
                "Sneak", SkinnedModel.Animation.logicalRigDriven(1f, sneak),
                "Sit", SkinnedModel.Animation.logicalRigDriven(1f, Map.of()),
                "Sleep", SkinnedModel.Animation.logicalRigDriven(1f, Map.of())
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

}
