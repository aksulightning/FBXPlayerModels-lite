package me.onethecrazy.util.parsing;

import me.onethecrazy.FBXPlayerModelsClient;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DynamicTextureLoader {
    private static final Map<String, Integer> TEXTURES = new HashMap<>();
    private static int whiteTexture = -1;

    private DynamicTextureLoader() {
    }

    public static int whiteTexture() {
        if (whiteTexture >= 0) {
            return whiteTexture;
        }

        BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        whiteTexture = textureManager().load(white);
        return whiteTexture;
    }

    public static int load(Path imagePath) throws Exception {
        return load(imagePath, null);
    }

    public static int load(Path imagePath, String stableName) throws Exception {
        try (InputStream input = Files.newInputStream(imagePath)) {
            return readDecodeAndRegister(input, stableName);
        }
    }

    public static int load(byte[] imageBytes) throws Exception {
        return load(imageBytes, null);
    }

    public static int load(byte[] imageBytes, String stableName) throws Exception {
        try (InputStream input = new ByteArrayInputStream(imageBytes)) {
            return readDecodeAndRegister(input, stableName);
        }
    }

    private static int readDecodeAndRegister(InputStream input, String stableName) throws Exception {
        String key = identifierPath(stableName);
        Integer cached = TEXTURES.get(key);
        if (cached != null) {
            return cached;
        }

        byte[] raw = trimToImageHeader(readAll(input));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
        if (image == null) {
            throw new IOException("Unsupported embedded or external texture image");
        }

        int texture = textureManager().load(image);
        TEXTURES.put(key, texture);
        return texture;
    }

    private static net.minecraft.client.texture.TextureManager textureManager() {
        Minecraft minecraft = FBXPlayerModelsClient.minecraft();
        if (minecraft == null || minecraft.textureManager == null) {
            throw new IllegalStateException("Minecraft texture manager is not initialized");
        }
        return minecraft.textureManager;
    }

    private static byte[] trimToImageHeader(byte[] raw) {
        int offset = imageHeaderOffset(raw);
        if (offset <= 0) {
            return raw;
        }

        byte[] trimmed = new byte[raw.length - offset];
        System.arraycopy(raw, offset, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static int imageHeaderOffset(byte[] raw) {
        for (int i = 0; i < raw.length - 4; i++) {
            if (i + 8 <= raw.length
                    && (raw[i] & 0xFF) == 0x89
                    && raw[i + 1] == 0x50
                    && raw[i + 2] == 0x4E
                    && raw[i + 3] == 0x47
                    && raw[i + 4] == 0x0D
                    && raw[i + 5] == 0x0A
                    && raw[i + 6] == 0x1A
                    && raw[i + 7] == 0x0A) {
                return i;
            }
            if ((raw[i] & 0xFF) == 0xFF
                    && (raw[i + 1] & 0xFF) == 0xD8
                    && (raw[i + 2] & 0xFF) == 0xFF) {
                return i;
            }
            if (i + 6 <= raw.length
                    && raw[i] == 0x47
                    && raw[i + 1] == 0x49
                    && raw[i + 2] == 0x46
                    && raw[i + 3] == 0x38) {
                return i;
            }
        }
        return 0;
    }

    private static String identifierPath(String stableName) {
        if (stableName == null || stableName.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = stableName.toLowerCase(Locale.ROOT).replace('\\', '/');
        normalized = normalized.replaceAll("[^a-z0-9_./-]", "_");
        normalized = normalized.replaceAll("/+", "/");
        normalized = normalized.replaceAll("^/+", "");
        return normalized.isBlank() ? UUID.randomUUID().toString() : normalized;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        input.transferTo(output);
        return output.toByteArray();
    }
}
