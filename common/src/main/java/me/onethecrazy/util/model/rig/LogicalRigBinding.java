package me.onethecrazy.util.model.rig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LogicalRigBinding {
    public Map<LogicalBodyPart, List<String>> bindings = new EnumMap<>(LogicalBodyPart.class);

    public LogicalRigBinding() {
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            bindings.put(part, new ArrayList<>());
        }
    }

    public List<String> namesFor(LogicalBodyPart part) {
        return bindings.computeIfAbsent(part, ignored -> new ArrayList<>());
    }

    public void setSingle(LogicalBodyPart part, String name) {
        List<String> names = new ArrayList<>();
        if (name != null && !name.isBlank()) {
            names.add(name);
        }
        bindings.put(part, names);
    }

    public String firstName(LogicalBodyPart part) {
        List<String> names = namesFor(part);
        return names.isEmpty() ? "" : names.get(0);
    }

    public boolean isEmpty() {
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            if (!namesFor(part).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static LogicalRigBinding autoBind(List<String> importedNames) {
        LogicalRigBinding binding = new LogicalRigBinding();
        Map<LogicalBodyPart, String> best = new LinkedHashMap<>();

        for (String name : importedNames) {
            LogicalBodyPart part = suggestPart(name);
            if (part != null) {
                if (part == LogicalBodyPart.HEAD && normalize(name).endsWith("head")
                        && !normalize(best.get(part)).endsWith("head")) {
                    best.put(part, name);
                    continue;
                }
                best.putIfAbsent(part, name);
            }
        }

        for (Map.Entry<LogicalBodyPart, String> entry : best.entrySet()) {
            binding.setSingle(entry.getKey(), entry.getValue());
        }

        return binding;
    }

    public static int resolveBoneIndex(List<String> importedNames, LogicalRigBinding explicit, LogicalBodyPart part) {
        List<String> names = explicit == null ? List.of() : explicit.namesFor(part);
        if (names.isEmpty()) {
            names = autoBind(importedNames).namesFor(part);
        }
        for (String name : names) {
            int exact = importedNames.indexOf(name);
            if (exact >= 0) {
                return exact;
            }
            for (int i = 0; i < importedNames.size(); i++) {
                if (normalize(name).equals(normalize(importedNames.get(i)))) {
                    return i;
                }
            }
        }
        // An explicit missing binding stays missing instead of selecting a different joint.
        return -1;
    }

    public static LogicalBodyPart suggestPart(String rawName) {
        String name = normalize(rawName);

        // Assimp pivot helpers keep the joint's name but are not the authored joint itself.
        if (name.contains("assimpfbx")) {
            return null;
        }

        if (name.contains("head") || name.contains("neck")) {
            return LogicalBodyPart.HEAD;
        }
        if (name.contains("spine") || name.contains("chest") || name.contains("torso") || name.equals("body") || name.contains("body")) {
            return LogicalBodyPart.CHEST;
        }
        if (isRight(name) && name.contains("arm")) {
            return LogicalBodyPart.RIGHT_ARM;
        }
        if (isLeft(name) && name.contains("arm")) {
            return LogicalBodyPart.LEFT_ARM;
        }
        if (isRight(name) && (name.contains("leg") || name.contains("thigh"))) {
            return LogicalBodyPart.RIGHT_LEG;
        }
        if (isLeft(name) && (name.contains("leg") || name.contains("thigh"))) {
            return LogicalBodyPart.LEFT_LEG;
        }

        return null;
    }

    public static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.toLowerCase(Locale.ROOT)
                .replace("model::", "")
                .replace("mixamorig:", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "");
    }

    private static boolean isRight(String name) {
        return name.contains("right") || name.endsWith(".r") || name.endsWith("r");
    }

    private static boolean isLeft(String name) {
        return name.contains("left") || name.endsWith(".l") || name.endsWith("l");
    }
}
