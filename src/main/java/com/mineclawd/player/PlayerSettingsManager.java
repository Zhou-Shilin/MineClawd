package com.mineclawd.player;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.MineClawd;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class PlayerSettingsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern KEY_SANITIZE = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final String SETTINGS_FILE_NAME = "player-settings.json";

    private final Path filePath;
    private boolean loaded;
    private final Map<String, RequestBroadcastTarget> requestBroadcastTargets = new HashMap<>();

    public PlayerSettingsManager() {
        Path root = FabricLoader.getInstance().getGameDir().resolve("mineclawd");
        this.filePath = root.resolve(SETTINGS_FILE_NAME);
    }

    public synchronized RequestBroadcastTarget getRequestBroadcastTarget(String playerKey) {
        ensureLoaded();
        String key = normalizePlayerKey(playerKey);
        if (key.isBlank()) {
            return RequestBroadcastTarget.SELF;
        }
        return requestBroadcastTargets.getOrDefault(key, RequestBroadcastTarget.SELF);
    }

    public synchronized void setRequestBroadcastTarget(String playerKey, RequestBroadcastTarget target) {
        ensureLoaded();
        String key = normalizePlayerKey(playerKey);
        if (key.isBlank() || target == null || target == RequestBroadcastTarget.SELF) {
            requestBroadcastTargets.remove(key);
        } else {
            requestBroadcastTargets.put(key, target);
        }
        save();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        requestBroadcastTargets.clear();
        ensureDirectory();
        if (!Files.isRegularFile(filePath)) {
            return;
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("requestBroadcastTargets") || !root.get("requestBroadcastTargets").isJsonObject()) {
                return;
            }
            JsonObject targets = root.getAsJsonObject("requestBroadcastTargets");
            for (String key : targets.keySet()) {
                String normalizedKey = normalizePlayerKey(key);
                if (normalizedKey.isBlank()) {
                    continue;
                }
                String value = targets.get(key).isJsonNull() ? "" : targets.get(key).getAsString();
                RequestBroadcastTarget target = RequestBroadcastTarget.fromUserInput(value);
                if (target != null && target != RequestBroadcastTarget.SELF) {
                    requestBroadcastTargets.put(normalizedKey, target);
                }
            }
        } catch (Exception exception) {
            MineClawd.LOGGER.warn("Failed to read player settings {}: {}", filePath, exception.getMessage());
        }
    }

    private void save() {
        ensureDirectory();
        JsonObject root = new JsonObject();
        JsonObject targets = new JsonObject();
        for (Map.Entry<String, RequestBroadcastTarget> entry : requestBroadcastTargets.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            targets.addProperty(entry.getKey(), entry.getValue().commandValue());
        }
        root.add("requestBroadcastTargets", targets);
        try {
            Files.writeString(
                    filePath,
                    GSON.toJson(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to write player settings {}: {}", filePath, exception.getMessage());
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create settings directory: " + filePath.getParent(), exception);
        }
    }

    private String normalizePlayerKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return KEY_SANITIZE.matcher(key.trim()).replaceAll("_");
    }

    public enum RequestBroadcastTarget {
        SELF("self", "Only Me"),
        ALL("all", "Everyone"),
        OPS("ops", "All OPs");

        private final String commandValue;
        private final String displayName;

        RequestBroadcastTarget(String commandValue, String displayName) {
            this.commandValue = commandValue;
            this.displayName = displayName;
        }

        public String commandValue() {
            return commandValue;
        }

        public String displayName() {
            return displayName;
        }

        public static RequestBroadcastTarget fromUserInput(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT)
                    .replace('_', '-')
                    .replace(' ', '-');
            return switch (normalized) {
                case "self", "me", "only-me", "only-self", "private" -> SELF;
                case "all", "everyone", "everybody", "public" -> ALL;
                case "ops", "op", "all-ops", "operators" -> OPS;
                default -> null;
            };
        }
    }
}
