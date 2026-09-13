package me.onethecrazy.screens;

import me.onethecrazy.FBXPlayerModels;
import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.screens.editor.ModelBindingEditorScreen;
import me.onethecrazy.screens.rendering.SkinPreviewRenderer;
import me.onethecrazy.util.FileUtil;
import me.onethecrazy.util.objects.CacheSkin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.lwjgl.input.Mouse;

public final class ConfigScreen extends Screen {
    private static final int SELECT = 1;
    private static final int RESET = 2;
    private static final int TOGGLE = 3;
    private static final int SETTINGS = 4;
    private static final int DONE = 5;
    private static final float ROTATION_SENSITIVITY = 0.6f;

    private final Screen parent;
    private SkinPreviewRenderer preview;
    private ButtonWidget selectButton;
    private ButtonWidget resetButton;
    private ButtonWidget toggleButton;
    private boolean rotating;
    private int lastMouseX;
    private int lastMouseY;

    public ConfigScreen(Screen parent) {
        this.parent = parent;
    }

    public ConfigScreen() {
        this(null);
    }

    @Override
    public void init() {
        buttons.clear();
        int previewSize = previewSize();
        int previewX = width / 2 - previewSize / 2;
        int previewY = 25;
        preview = new SkinPreviewRenderer(previewX, previewY, previewSize, previewSize * 0.42f);

        int controlsY = previewY + previewSize + 8;
        int contentWidth = Math.min(260, width - 20);
        int contentX = width / 2 - contentWidth / 2;
        int half = (contentWidth - 4) / 2;
        selectButton = new ButtonWidget(SELECT, contentX, controlsY, contentWidth, 20, selectText());
        resetButton = new ButtonWidget(RESET, contentX, controlsY + 24, half, 20, "Reset Skin");
        toggleButton = new ButtonWidget(TOGGLE, contentX + half + 4, controlsY + 24, half, 20, toggleText());
        buttons.add(selectButton);
        buttons.add(resetButton);
        buttons.add(toggleButton);
        buttons.add(new ButtonWidget(SETTINGS, contentX, controlsY + 48, contentWidth, 20, "Settings"));
        buttons.add(new ButtonWidget(DONE, contentX, height - 26, contentWidth, 20, "Done"));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (!button.active) {
            return;
        }
        if (button.id == SELECT) {
            SkinManager.pickClientSkin();
        } else if (button.id == RESET) {
            SkinManager.resetSelfSkin();
        } else if (button.id == TOGGLE) {
            FBXPlayerModelsClient.options().isEnabled = !FBXPlayerModelsClient.options().isEnabled;
            FileUtil.writeSave(FBXPlayerModelsClient.options());
        } else if (button.id == SETTINGS) {
            minecraft.setScreen(new ModelBindingEditorScreen(this));
        } else if (button.id == DONE) {
            minecraft.setScreen(parent);
        }
        updateButtonText();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && insidePreview(mouseX, mouseY)) {
            rotating = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (button == 0) {
            rotating = false;
        }
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        renderBackground();
        updateButtonText();
        if (rotating && Mouse.isButtonDown(0)) {
            preview.addRotation((mouseX - lastMouseX) * ROTATION_SENSITIVITY,
                    -(mouseY - lastMouseY) * ROTATION_SENSITIVITY);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        } else if (!Mouse.isButtonDown(0)) {
            rotating = false;
        }

        preview.renderPreview(tickDelta, width, height);
        drawPreviewBorder();
        drawCenteredTextWithShadow(textRenderer, FBXPlayerModels.DISPLAY_NAME, width / 2, 8, 0xFFFFFF);

        CacheSkin cacheSkin = SkinManager.getSelfSkin();
        if (cacheSkin != null) {
            String status = trim(cacheSkin.debugStatus(), Math.min(260, width - 20));
            drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 38, 0xCCCCCC);
        }
        super.render(mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void updateButtonText() {
        if (selectButton == null) {
            return;
        }
        selectButton.text = selectText();
        resetButton.text = "Reset Skin";
        toggleButton.text = toggleText();
    }

    private String selectText() {
        String name = FBXPlayerModelsClient.options().selectedSkin.name;
        return name == null || name.isBlank() ? "Choose an FBX model..." : trim(name, Math.min(248, width - 32));
    }

    private String toggleText() {
        return FBXPlayerModelsClient.options().isEnabled ? "Enabled" : "Disabled";
    }

    private int previewSize() {
        int reserved = 126;
        return Math.max(64, Math.min(150, Math.min(width - 20, height - reserved)));
    }

    private boolean insidePreview(int mouseX, int mouseY) {
        int size = previewSize();
        int x = width / 2 - size / 2;
        return mouseX >= x && mouseX <= x + size && mouseY >= 25 && mouseY <= 25 + size;
    }

    private void drawPreviewBorder() {
        int size = previewSize();
        int x = width / 2 - size / 2;
        int y = 25;
        drawHorizontalLine(x, x + size, y, 0xFFFFFFFF);
        drawHorizontalLine(x, x + size, y + size, 0xFFFFFFFF);
        drawVerticalLine(x, y, y + size, 0xFFFFFFFF);
        drawVerticalLine(x + size, y, y + size, 0xFFFFFFFF);
    }

    private String trim(String value, int maxWidth) {
        if (value == null || textRenderer.getWidth(value) <= maxWidth) {
            return value == null ? "" : value;
        }
        String suffix = "...";
        int end = value.length();
        while (end > 0 && textRenderer.getWidth(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }
}
