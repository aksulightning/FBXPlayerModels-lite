package me.onethecrazy.util.objects;

import me.onethecrazy.util.parsing.DynamicTextureLoader;

public class Vertex {
    public Float3 position;
    public Float3 normals;
    public Float2 textureUV;
    public int color;
    public int texture;

    public Vertex(Float3 position, Float3 normals, Float2 textureUV) {
        this(position, normals, textureUV, DynamicTextureLoader.whiteTexture(), 0xFFFFFFFF);
    }

    public Vertex(Float3 position, Float3 normals, Float2 textureUV, int texture) {
        this(position, normals, textureUV, texture, 0xFFFFFFFF);
    }

    public Vertex(Float3 position, Float3 normals, Float2 textureUV, int texture, int color) {
        this.position = position;
        this.normals = normals;
        this.textureUV = textureUV;
        this.texture = texture;
        this.color = color;
    }
}
