package me.onethecrazy.util.objects;

public class SkinnedVertex {
    public Vertex vertex;
    public int[] boneIds;
    public float[] weights;

    public SkinnedVertex(Vertex vertex, int[] boneIds, float[] weights) {
        this.vertex = vertex;
        this.boneIds = boneIds;
        this.weights = weights;
    }
}

