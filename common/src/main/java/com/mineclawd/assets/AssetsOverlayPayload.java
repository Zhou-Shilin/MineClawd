package com.mineclawd.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AssetsOverlayPayload {
    private final boolean openUi;
    private final String activeSessionId;
    private final String activePersona;
    private final List<String> personas;
    private final List<AssetItem> assets;

    public AssetsOverlayPayload(
            boolean openUi,
            String activeSessionId,
            String activePersona,
            List<String> personas,
            List<AssetItem> assets
    ) {
        this.openUi = openUi;
        this.activeSessionId = activeSessionId == null ? "" : activeSessionId.trim();
        this.activePersona = activePersona == null ? "" : activePersona.trim();
        this.personas = personas == null ? List.of() : List.copyOf(personas);
        this.assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public boolean openUi() {
        return openUi;
    }

    public String activeSessionId() {
        return activeSessionId;
    }

    public String activePersona() {
        return activePersona;
    }

    public List<String> personas() {
        return Collections.unmodifiableList(personas);
    }

    public List<AssetItem> assets() {
        return Collections.unmodifiableList(assets);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("openUi", openUi);
        root.addProperty("activeSessionId", activeSessionId);
        root.addProperty("activePersona", activePersona);

        JsonArray personasArray = new JsonArray();
        for (String persona : personas) {
            if (persona != null && !persona.isBlank()) {
                personasArray.add(persona);
            }
        }
        root.add("personas", personasArray);

        JsonArray assetsArray = new JsonArray();
        for (AssetItem item : assets) {
            if (item == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            JsonObject asset = new JsonObject();
            asset.addProperty("id", item.id());
            asset.addProperty("category", item.category());
            asset.addProperty("name", item.name());
            asset.addProperty("summary", item.summary());
            asset.addProperty("scriptPath", item.scriptPath());
            asset.addProperty("details", item.details());
            asset.addProperty("contentId", item.contentId());
            asset.addProperty("specialItemId", item.specialItemId());
            asset.addProperty("specialItemNbt", item.specialItemNbt());
            asset.addProperty("command", item.command());
            asset.addProperty("entityUuid", item.entityUuid());
            asset.addProperty("entityDimension", item.entityDimension());
            if (item.entityX() != null) {
                asset.addProperty("entityX", item.entityX());
            }
            if (item.entityY() != null) {
                asset.addProperty("entityY", item.entityY());
            }
            if (item.entityZ() != null) {
                asset.addProperty("entityZ", item.entityZ());
            }
            asset.addProperty("sessionId", item.sessionId());
            asset.addProperty("updatedAtEpochMillis", item.updatedAtEpochMillis());
            assetsArray.add(asset);
        }
        root.add("assets", assetsArray);
        return root.toString();
    }

    public static AssetsOverlayPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean openUi = readBoolean(root, "openUi");
            String activeSessionId = readString(root, "activeSessionId");
            String activePersona = readString(root, "activePersona");

            List<String> personas = new ArrayList<>();
            if (root.has("personas") && root.get("personas").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("personas")) {
                    if (element == null || element.isJsonNull()) {
                        continue;
                    }
                    String value = element.getAsString();
                    if (!value.isBlank()) {
                        personas.add(value);
                    }
                }
            }

            List<AssetItem> assets = new ArrayList<>();
            if (root.has("assets") && root.get("assets").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("assets")) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = element.getAsJsonObject();
                    String id = readString(item, "id");
                    if (id.isBlank()) {
                        continue;
                    }
                    assets.add(new AssetItem(
                            id,
                            readString(item, "category"),
                            readString(item, "name"),
                            readString(item, "summary"),
                            readString(item, "scriptPath"),
                            readString(item, "details"),
                            readString(item, "contentId"),
                            readString(item, "specialItemId"),
                            readString(item, "specialItemNbt"),
                            readString(item, "command"),
                            readString(item, "entityUuid"),
                            readString(item, "entityDimension"),
                            readNullableDouble(item, "entityX"),
                            readNullableDouble(item, "entityY"),
                            readNullableDouble(item, "entityZ"),
                            readString(item, "sessionId"),
                            readLong(item, "updatedAtEpochMillis", 0L)
                    ));
                }
            }

            return new AssetsOverlayPayload(openUi, activeSessionId, activePersona, personas, assets);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean readBoolean(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long readLong(JsonObject object, String key, long fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Double readNullableDouble(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    public record AssetItem(
            String id,
            String category,
            String name,
            String summary,
            String scriptPath,
            String details,
            String contentId,
            String specialItemId,
            String specialItemNbt,
            String command,
            String entityUuid,
            String entityDimension,
            Double entityX,
            Double entityY,
            Double entityZ,
            String sessionId,
            long updatedAtEpochMillis
    ) {
        public AssetItem {
            id = id == null ? "" : id.trim();
            category = category == null ? "" : category.trim();
            name = name == null ? "" : name.trim();
            summary = summary == null ? "" : summary.trim();
            scriptPath = scriptPath == null ? "" : scriptPath.trim();
            details = details == null ? "" : details.trim();
            contentId = contentId == null ? "" : contentId.trim();
            specialItemId = specialItemId == null ? "" : specialItemId.trim();
            specialItemNbt = specialItemNbt == null ? "" : specialItemNbt.trim();
            command = command == null ? "" : command.trim();
            entityUuid = entityUuid == null ? "" : entityUuid.trim();
            entityDimension = entityDimension == null ? "" : entityDimension.trim();
            sessionId = sessionId == null ? "" : sessionId.trim();
        }
    }
}
