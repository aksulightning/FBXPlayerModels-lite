package me.onethecrazy.screens.editor;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.FileUtil;
import me.onethecrazy.util.model.rig.LogicalBodyPart;
import me.onethecrazy.util.model.rig.LogicalRigBinding;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.SkinnedModel;
import me.onethecrazy.util.parsing.FBXParser;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Paged Beta GUI for the same binding, clip, and first-person state as the modern editor. */
public final class ModelBindingEditorScreen extends Screen {
    private static final int AUTO_BIND = 30;
    private static final int ANIMATIONS = 40;
    private static final int FIRST_PERSON = 60;
    private static final int RESET_CAMERA = 76;
    private static final int PREVIOUS_PAGE = 90;
    private static final int NEXT_PAGE = 91;
    private static final int DONE = 92;
    private static final float CAMERA_STEP = 0.05f;
    private static final float CAMERA_LIMIT = 1.5f;

    private final Screen parent;
    private List<String> boneNames = List.of();
    private List<String> clipNames = List.of();
    private int page;

    public ModelBindingEditorScreen(Screen parent) {
        this.parent = parent;
    }

    @Override
    public void init() {
        buttons.clear();
        boneNames = currentBones();
        clipNames = currentClips();

        if (page == 0) {
            initBindingPage();
        } else if (page == 1) {
            initAnimationPage();
        } else {
            initFirstPersonPage();
        }

        int navigationWidth = Math.min(300, width - 12);
        int navigationX = width / 2 - navigationWidth / 2;
        int third = (navigationWidth - 8) / 3;
        ButtonWidget previous = new ButtonWidget(PREVIOUS_PAGE, navigationX, height - 26, third, 20, "Previous");
        previous.active = page > 0;
        ButtonWidget next = new ButtonWidget(NEXT_PAGE, navigationX + third + 4, height - 26, third, 20, "Next");
        next.active = page < 2;
        buttons.add(previous);
        buttons.add(next);
        buttons.add(new ButtonWidget(DONE, navigationX + (third + 4) * 2, height - 26, third, 20, "Done"));
    }

    private void initBindingPage() {
        int buttonWidth = Math.min(156, width / 2 - 18);
        int x = width / 2 + 4;
        int y = 47;
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            buttons.add(new ButtonWidget(10 + part.ordinal(), x, y, buttonWidth, 20, bindingText(part)));
            y += 24;
        }
        buttons.add(new ButtonWidget(AUTO_BIND, width / 2 - 76, y + 4, 152, 20, "Auto Bind"));
    }

    private void initAnimationPage() {
        int buttonWidth = Math.min(180, width - 24);
        int x = width / 2 - buttonWidth / 2;
        buttons.add(new ButtonWidget(ANIMATIONS, x, 47, buttonWidth, 20, animationText()));
        int y = 82;
        for (int i = 0; i < 4; i++) {
            buttons.add(new ButtonWidget(50 + i, x, y, buttonWidth, 20, clipText(clipState(i))));
            y += 28;
        }
    }

    private void initFirstPersonPage() {
        int mainWidth = Math.min(220, width - 24);
        int x = width / 2 - mainWidth / 2;
        buttons.add(new ButtonWidget(FIRST_PERSON, x, 47, mainWidth, 20, firstPersonText()));
        int rowY = 82;
        for (int axis = 0; axis < 3; axis++) {
            buttons.add(new ButtonWidget(70 + axis * 2, x, rowY, 30, 20, "-"));
            buttons.add(new ButtonWidget(71 + axis * 2, x + mainWidth - 30, rowY, 30, 20, "+"));
            rowY += 28;
        }
        buttons.add(new ButtonWidget(RESET_CAMERA, x, rowY + 2, mainWidth, 20, "Reset Camera Offsets"));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (!button.active) {
            return;
        }
        if (button.id >= 10 && button.id < 10 + LogicalBodyPart.values().length) {
            LogicalBodyPart part = LogicalBodyPart.values()[button.id - 10];
            cycleBinding(part);
            button.text = bindingText(part);
        } else if (button.id == AUTO_BIND) {
            FBXPlayerModelsClient.options().selectedSkin.logicalRigBinding = LogicalRigBinding.autoBind(boneNames);
            saveAndReload();
            init();
        } else if (button.id == ANIMATIONS) {
            boolean enabled = FBXPlayerModelsClient.options().selectedSkin.animationsEnabled();
            FBXPlayerModelsClient.options().selectedSkin.setAnimationsEnabled(!enabled);
            saveAndReload();
            button.text = animationText();
        } else if (button.id >= 50 && button.id <= 53) {
            String state = clipState(button.id - 50);
            cycleClip(state);
            button.text = clipText(state);
        } else if (button.id == FIRST_PERSON) {
            FBXPlayerModelsClient.options().renderSelfModelInFirstPerson =
                    !FBXPlayerModelsClient.options().renderSelfModelInFirstPerson;
            FileUtil.writeSave(FBXPlayerModelsClient.options());
            button.text = firstPersonText();
        } else if (button.id >= 70 && button.id <= 75) {
            changeCameraOffset((button.id - 70) / 2, (button.id & 1) == 0 ? -CAMERA_STEP : CAMERA_STEP);
        } else if (button.id == RESET_CAMERA) {
            FBXPlayerModelsClient.options().firstPersonCameraOffsetX = 0f;
            FBXPlayerModelsClient.options().firstPersonCameraOffsetY = 0f;
            FBXPlayerModelsClient.options().firstPersonCameraOffsetZ = 0f;
            FileUtil.writeSave(FBXPlayerModelsClient.options());
        } else if (button.id == PREVIOUS_PAGE) {
            page--;
            init();
        } else if (button.id == NEXT_PAGE) {
            page++;
            init();
        } else if (button.id == DONE) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        renderBackground();
        drawCenteredTextWithShadow(textRenderer, "Model Settings - " + pageName(), width / 2, 12, 0xFFFFFF);
        if (page == 0) {
            renderBindingLabels();
        } else if (page == 1) {
            renderAnimationLabels();
        } else {
            renderFirstPersonLabels();
        }
        renderStatus();
        super.render(mouseX, mouseY, tickDelta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void renderBindingLabels() {
        drawCenteredTextWithShadow(textRenderer, "Detected bones: " + boneNames.size(), width / 2, 27, 0xCCCCCC);
        int y = 53;
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            drawTextWithShadow(textRenderer, part.displayName, Math.max(8, width / 2 - 160), y, 0xFFFFFF);
            y += 24;
        }
        if (boneNames.isEmpty()) {
            drawCenteredTextWithShadow(textRenderer, "No bindable bones detected.", width / 2, y + 31, 0xFFAAAA);
        } else {
            String names = String.join(", ", boneNames.subList(0, Math.min(5, boneNames.size())));
            if (boneNames.size() > 5) {
                names += ", ...";
            }
            drawCenteredTextWithShadow(textRenderer, trim(names, width - 20), width / 2, y + 31, 0xAAAAAA);
        }
    }

    private void renderAnimationLabels() {
        int y = 88;
        for (int i = 0; i < 4; i++) {
            String state = clipState(i);
            drawTextWithShadow(textRenderer, state + " clip", Math.max(8, width / 2 - 145), y, 0xFFFFFF);
            y += 28;
        }
        drawCenteredTextWithShadow(textRenderer, "Imported clips: " + clipNames.size(), width / 2, y + 7, 0xCCCCCC);
        drawCenteredTextWithShadow(textRenderer, "Unmapped states use the logical-rig animation.", width / 2, y + 19, 0xAAAAAA);
    }

    private void renderFirstPersonLabels() {
        int y = 88;
        drawCenteredTextWithShadow(textRenderer, cameraText("X", FBXPlayerModelsClient.options().firstPersonCameraOffsetX), width / 2, y, 0xFFFFFF);
        drawCenteredTextWithShadow(textRenderer, cameraText("Y", FBXPlayerModelsClient.options().firstPersonCameraOffsetY), width / 2, y + 28, 0xFFFFFF);
        drawCenteredTextWithShadow(textRenderer, cameraText("Z", FBXPlayerModelsClient.options().firstPersonCameraOffsetZ), width / 2, y + 56, 0xFFFFFF);
    }

    private void renderStatus() {
        CacheSkin cache = currentCache();
        String status = cache == null ? "Rig: none" : cache.debugStatus();
        drawCenteredTextWithShadow(textRenderer, trim(status, width - 20), width / 2, height - 49, 0xCCCCCC);
        List<String> warnings = FBXPlayerModelsClient.options().selectedSkin.warnings();
        if (!warnings.isEmpty()) {
            drawCenteredTextWithShadow(textRenderer, trim(warnings.get(0), width - 20), width / 2, height - 61, 0xFFFF88);
        } else if (page == 0 && !FBXParser.lastMaterialDiagnostics().isEmpty()) {
            drawCenteredTextWithShadow(textRenderer,
                    trim(FBXParser.lastMaterialDiagnostics().get(0), width - 20), width / 2, height - 61, 0xAAAAAA);
        }
    }

    private void cycleBinding(LogicalBodyPart part) {
        LogicalRigBinding binding = FBXPlayerModelsClient.options().selectedSkin.binding();
        if (boneNames.isEmpty()) {
            binding.setSingle(part, "");
        } else {
            String current = binding.firstName(part);
            int next = current.isBlank() ? 0 : boneNames.indexOf(current) + 1;
            binding.setSingle(part, next < 0 || next >= boneNames.size() ? "" : boneNames.get(next));
        }
        saveAndReload();
    }

    private void cycleClip(String state) {
        if (clipNames.isEmpty()) {
            FBXPlayerModelsClient.options().selectedSkin.clipMappings().remove(state);
        } else {
            String current = FBXPlayerModelsClient.options().selectedSkin.clipMappings().getOrDefault(state, "");
            int next = current.isBlank() ? 0 : clipNames.indexOf(current) + 1;
            if (next < 0 || next >= clipNames.size()) {
                FBXPlayerModelsClient.options().selectedSkin.clipMappings().remove(state);
            } else {
                FBXPlayerModelsClient.options().selectedSkin.clipMappings().put(state, clipNames.get(next));
            }
        }
        saveAndReload();
    }

    private void changeCameraOffset(int axis, float delta) {
        if (axis == 0) {
            FBXPlayerModelsClient.options().firstPersonCameraOffsetX = clampOffset(FBXPlayerModelsClient.options().firstPersonCameraOffsetX + delta);
        } else if (axis == 1) {
            FBXPlayerModelsClient.options().firstPersonCameraOffsetY = clampOffset(FBXPlayerModelsClient.options().firstPersonCameraOffsetY + delta);
        } else {
            FBXPlayerModelsClient.options().firstPersonCameraOffsetZ = clampOffset(FBXPlayerModelsClient.options().firstPersonCameraOffsetZ + delta);
        }
        FileUtil.writeSave(FBXPlayerModelsClient.options());
    }

    private void saveAndReload() {
        SkinManager.saveCurrentBinding();
        boneNames = currentBones();
        clipNames = currentClips();
    }

    private String bindingText(LogicalBodyPart part) {
        String name = FBXPlayerModelsClient.options().selectedSkin.binding().firstName(part);
        return trim(name.isBlank() ? "Unbound" : name, Math.min(144, width / 2 - 30));
    }

    private String clipText(String state) {
        String name = FBXPlayerModelsClient.options().selectedSkin.clipMappings().getOrDefault(state, "");
        return trim(name.isBlank() ? "Procedural/default" : name, Math.min(168, width - 36));
    }

    private String animationText() {
        return "Animations: " + (FBXPlayerModelsClient.options().selectedSkin.animationsEnabled() ? "ON" : "OFF");
    }

    private String firstPersonText() {
        return "First Person Model: " + (FBXPlayerModelsClient.options().renderSelfModelInFirstPerson ? "ON" : "OFF");
    }

    private static String clipState(int index) {
        return switch (index) {
            case 0 -> "Walk";
            case 1 -> "Sneak";
            case 2 -> "Sit";
            default -> "Sleep";
        };
    }

    private String pageName() {
        return page == 0 ? "Bindings" : page == 1 ? "Animations" : "First Person";
    }

    private static float clampOffset(float value) {
        return Math.round(Math.max(-CAMERA_LIMIT, Math.min(CAMERA_LIMIT, value)) * 100f) / 100f;
    }

    private static String cameraText(String axis, float value) {
        return String.format(Locale.ROOT, "Camera %s: %.2f", axis, value);
    }

    private List<String> currentBones() {
        CacheSkin cache = currentCache();
        if (cache == null || cache.skinnedModel == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (SkinnedModel.Bone bone : cache.skinnedModel.bones) {
            names.add(bone.name());
        }
        return names;
    }

    private List<String> currentClips() {
        CacheSkin cache = currentCache();
        if (cache == null || cache.skinnedModel == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var entry : cache.skinnedModel.animations.entrySet()) {
            if (!entry.getValue().logicalRigDriven()) {
                names.add(entry.getKey());
            }
        }
        names.remove("Idle");
        names.remove("Walk");
        names.remove("Sneak");
        return names;
    }

    private CacheSkin currentCache() {
        return SkinManager.getSelfSkin();
    }

    private String trim(String value, int maxWidth) {
        if (textRenderer == null || textRenderer.getWidth(value) <= maxWidth) {
            return value;
        }
        int end = value.length();
        while (end > 0 && textRenderer.getWidth(value.substring(0, end) + "...") > maxWidth) {
            end--;
        }
        return value.substring(0, end) + "...";
    }
}
