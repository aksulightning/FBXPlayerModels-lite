package com.aksulightning.fbxplayermodels.model;

import org.joml.Matrix4f;

public final class FbxCoordinateSpace {
    public static final String ROOT_NAME = "__fbx_model_space";

    private FbxCoordinateSpace() {}

    public static Matrix4f fromUpAxis(int axis, int sign) {
        if (sign != 1 && sign != -1) {
            return new Matrix4f();
        }
        float halfPi = (float) (Math.PI / 2.0);
        // Convert file-world up to model-space +Y without changing joint-local axes.
        return switch (axis) {
            case 0 -> new Matrix4f().rotationZ(sign * halfPi);
            case 1 -> sign == 1 ? new Matrix4f() : new Matrix4f().rotationX((float) Math.PI);
            case 2 -> new Matrix4f().rotationX(-sign * halfPi);
            default -> new Matrix4f();
        };
    }
}
