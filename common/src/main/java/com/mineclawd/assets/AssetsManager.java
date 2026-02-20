package com.mineclawd.assets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.MineClawd;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class AssetsManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern OWNER_SANITIZE = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern ID_SANITIZE = Pattern.compile("[^a-z0-9_-]");
    private static final int NAME_MAX_LENGTH = 100;
    private static final int SUMMARY_MAX_LENGTH = 260;
    private static final int SCRIPT_PATH_MAX_LENGTH = 260;
    private static final int DETAILS_MAX_LENGTH = 2_000;
    private static final int CATEGORY_LABEL_LENGTH = 32;
    private static final String FILE_EXT = ".json";
    private static final String ARRAY_KEY = "assets";

    private final Path assetsRoot;

    public AssetsManager() {
        this.assetsRoot = Platform.getGameFolder().resolve("mineclawd").resolve("assets");
        ensureDirectory(assetsRoot);
    }

    public synchronized List<AssetRecord> list(String ownerKey) {
        List<AssetRecord> records = readOwnerRecords(ownerKey);
        records.sort(Comparator.comparingLong(AssetRecord::updatedAtEpochMilli).reversed());
        return List.copyOf(records);
    }

    public synchronized AssetRecord resolve(String ownerKey, String reference) {
        String ref = normalizeReference(reference);
        if (ref.isBlank()) {
            return null;
        }
        for (AssetRecord record : list(ownerKey)) {
            if (record == null) {
                continue;
            }
            if (record.id().equalsIgnoreCase(ref)) {
                return record;
            }
            if (!record.name().isBlank() && record.name().equalsIgnoreCase(reference == null ? "" : reference.trim())) {
                return record;
            }
        }
        return null;
    }

    public synchronized UpsertResult upsert(String ownerKey, AssetDraft draft) {
        if (draft == null) {
            return UpsertResult.error("Asset draft is missing.");
        }
        AssetCategory category = AssetCategory.fromInput(draft.category());
        if (category == null) {
            return UpsertResult.error("`category` is required. Use: entities, items_blocks_fluids, special_items, commands, game_mechanics.");
        }

        String normalizedName = normalizeText(draft.name(), NAME_MAX_LENGTH);
        if (normalizedName.isBlank()) {
            return UpsertResult.error("`name` is required.");
        }

        Path ownerPath = ownerPath(ownerKey);
        List<AssetRecord> records = readRecordsFromPath(ownerPath);

        String requestedId = normalizeId(draft.id());
        int existingIndex = findRecordIndex(records, requestedId);
        AssetRecord existing = existingIndex >= 0 ? records.get(existingIndex) : null;
        String id = existing != null
                ? existing.id()
                : (requestedId.isBlank() ? allocateId(records, category, normalizedName) : requestedId);

        long now = Instant.now().toEpochMilli();
        long createdAt = existing == null ? now : existing.createdAtEpochMilli();
        AssetRecord updated = new AssetRecord(
                id,
                category,
                normalizedName,
                normalizeText(draft.summary(), SUMMARY_MAX_LENGTH),
                normalizeText(draft.scriptPath(), SCRIPT_PATH_MAX_LENGTH),
                normalizeText(draft.details(), DETAILS_MAX_LENGTH),
                normalizeIdentifierLike(draft.contentId()),
                normalizeIdentifierLike(draft.specialItemId()),
                normalizeNbtLike(draft.specialItemNbt()),
                normalizeText(draft.command(), DETAILS_MAX_LENGTH),
                normalizeUuidString(draft.entityUuid()),
                normalizeIdentifierLike(draft.entityDimension()),
                draft.entityX(),
                draft.entityY(),
                draft.entityZ(),
                normalizeText(draft.sessionId(), CATEGORY_LABEL_LENGTH),
                createdAt,
                now
        );

        if (existingIndex >= 0) {
            records.set(existingIndex, updated);
        } else {
            records.add(updated);
        }
        writeRecordsToPath(ownerPath, records);
        return UpsertResult.success(updated, existingIndex >= 0);
    }

    public synchronized boolean remove(String ownerKey, String reference) {
        String ref = normalizeReference(reference);
        if (ref.isBlank()) {
            return false;
        }
        Path ownerPath = ownerPath(ownerKey);
        List<AssetRecord> records = readRecordsFromPath(ownerPath);
        int index = findRecordIndex(records, ref);
        if (index < 0) {
            return false;
        }
        records.remove(index);
        writeRecordsToPath(ownerPath, records);
        return true;
    }

    private int findRecordIndex(List<AssetRecord> records, String idOrRef) {
        if (records == null || records.isEmpty() || idOrRef == null || idOrRef.isBlank()) {
            return -1;
        }
        String ref = idOrRef.trim();
        for (int i = 0; i < records.size(); i++) {
            AssetRecord record = records.get(i);
            if (record == null) {
                continue;
            }
            if (record.id().equalsIgnoreCase(ref)) {
                return i;
            }
            if (!record.name().isBlank() && record.name().equalsIgnoreCase(ref)) {
                return i;
            }
        }
        return -1;
    }

    private List<AssetRecord> readOwnerRecords(String ownerKey) {
        return readRecordsFromPath(ownerPath(ownerKey));
    }

    private List<AssetRecord> readRecordsFromPath(Path path) {
        List<AssetRecord> records = new ArrayList<>();
        if (path == null || !Files.isRegularFile(path)) {
            return records;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has(ARRAY_KEY) || !root.get(ARRAY_KEY).isJsonArray()) {
                return records;
            }
            JsonArray array = root.getAsJsonArray(ARRAY_KEY);
            for (JsonElement element : array) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                AssetRecord record = readRecord(element.getAsJsonObject());
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception exception) {
            MineClawd.LOGGER.warn("Failed to read assets file {}: {}", path, exception.getMessage());
        }
        return records;
    }

    private void writeRecordsToPath(Path path, List<AssetRecord> records) {
        ensureDirectory(path == null ? null : path.getParent());
        JsonArray array = new JsonArray();
        if (records != null) {
            for (AssetRecord record : records) {
                if (record == null) {
                    continue;
                }
                array.add(writeRecord(record));
            }
        }
        JsonObject root = new JsonObject();
        root.add(ARRAY_KEY, array);
        try {
            Files.writeString(
                    path,
                    GSON.toJson(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write assets file: " + path, exception);
        }
    }

    private AssetRecord readRecord(JsonObject object) {
        if (object == null) {
            return null;
        }
        String id = normalizeId(readString(object, "id"));
        if (id.isBlank()) {
            return null;
        }
        AssetCategory category = AssetCategory.fromInput(readString(object, "category"));
        if (category == null) {
            return null;
        }
        String name = normalizeText(readString(object, "name"), NAME_MAX_LENGTH);
        if (name.isBlank()) {
            name = id;
        }
        return new AssetRecord(
                id,
                category,
                name,
                normalizeText(readString(object, "summary"), SUMMARY_MAX_LENGTH),
                normalizeText(readString(object, "scriptPath"), SCRIPT_PATH_MAX_LENGTH),
                normalizeText(readString(object, "details"), DETAILS_MAX_LENGTH),
                normalizeIdentifierLike(readString(object, "contentId")),
                normalizeIdentifierLike(readString(object, "specialItemId")),
                normalizeNbtLike(readString(object, "specialItemNbt")),
                normalizeText(readString(object, "command"), DETAILS_MAX_LENGTH),
                normalizeUuidString(readString(object, "entityUuid")),
                normalizeIdentifierLike(readString(object, "entityDimension")),
                readNullableDouble(object, "entityX"),
                readNullableDouble(object, "entityY"),
                readNullableDouble(object, "entityZ"),
                normalizeText(readString(object, "sessionId"), CATEGORY_LABEL_LENGTH),
                readLong(object, "createdAt", Instant.now().toEpochMilli()),
                readLong(object, "updatedAt", Instant.now().toEpochMilli())
        );
    }

    private JsonObject writeRecord(AssetRecord record) {
        JsonObject object = new JsonObject();
        object.addProperty("id", record.id());
        object.addProperty("category", record.category().id());
        object.addProperty("name", record.name());
        object.addProperty("summary", record.summary());
        object.addProperty("scriptPath", record.scriptPath());
        object.addProperty("details", record.details());
        object.addProperty("contentId", record.contentId());
        object.addProperty("specialItemId", record.specialItemId());
        object.addProperty("specialItemNbt", record.specialItemNbt());
        object.addProperty("command", record.command());
        object.addProperty("entityUuid", record.entityUuid());
        object.addProperty("entityDimension", record.entityDimension());
        if (record.entityX() != null) {
            object.addProperty("entityX", record.entityX());
        }
        if (record.entityY() != null) {
            object.addProperty("entityY", record.entityY());
        }
        if (record.entityZ() != null) {
            object.addProperty("entityZ", record.entityZ());
        }
        object.addProperty("sessionId", record.sessionId());
        object.addProperty("createdAt", record.createdAtEpochMilli());
        object.addProperty("updatedAt", record.updatedAtEpochMilli());
        return object;
    }

    private Path ownerPath(String ownerKey) {
        String normalized = normalizeOwner(ownerKey);
        return assetsRoot.resolve(normalized + FILE_EXT);
    }

    private String normalizeOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            return "unknown";
        }
        String safe = OWNER_SANITIZE.matcher(ownerKey.trim()).replaceAll("_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String normalizeReference(String reference) {
        if (reference == null) {
            return "";
        }
        return reference.trim();
    }

    private String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String raw = id.trim().toLowerCase(Locale.ROOT);
        raw = ID_SANITIZE.matcher(raw).replaceAll("-");
        raw = raw.replaceAll("-{2,}", "-");
        raw = raw.replaceAll("^-+", "").replaceAll("-+$", "");
        if (raw.length() > CATEGORY_LABEL_LENGTH) {
            raw = raw.substring(0, CATEGORY_LABEL_LENGTH).replaceAll("-+$", "");
        }
        return raw;
    }

    private String allocateId(List<AssetRecord> records, AssetCategory category, String name) {
        String base = normalizeId(category.id() + "-" + name.toLowerCase(Locale.ROOT).replace(' ', '-'));
        if (base.isBlank()) {
            base = category.id();
        }
        String candidate = base;
        int suffix = 2;
        while (containsId(records, candidate)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean containsId(List<AssetRecord> records, String id) {
        if (records == null || id == null || id.isBlank()) {
            return false;
        }
        for (AssetRecord record : records) {
            if (record != null && record.id().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', ' ').trim();
        if (maxLength > 0 && normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).trim();
        }
        return normalized;
    }

    private String normalizeIdentifierLike(String value) {
        String normalized = normalizeText(value, CATEGORY_LABEL_LENGTH * 2);
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeNbtLike(String value) {
        return normalizeText(value, DETAILS_MAX_LENGTH);
    }

    private String normalizeUuidString(String value) {
        String normalized = normalizeText(value, 64);
        if (normalized.isBlank()) {
            return "";
        }
        try {
            return java.util.UUID.fromString(normalized).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return Objects.toString(object.get(key).getAsString(), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private Double readNullableDouble(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    private long readLong(JsonObject object, String key, long fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void ensureDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create assets directory: " + directory, exception);
        }
    }

    public enum AssetCategory {
        ENTITIES("entities", "Entities"),
        ITEMS_BLOCKS_FLUIDS("items_blocks_fluids", "Items/Blocks/Fluids"),
        SPECIAL_ITEMS("special_items", "Special Items"),
        COMMANDS("commands", "Commands"),
        GAME_MECHANICS("game_mechanics", "Game Mechanics");

        private final String id;
        private final String displayName;

        AssetCategory(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }

        public static AssetCategory fromInput(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            return switch (normalized) {
                case "entity", "entities", "special_entity", "special_entities" -> ENTITIES;
                case "items_blocks_fluids", "item_block_fluid", "item_block_fluids",
                        "items", "item", "blocks", "block", "fluids", "fluid", "dynamic_content" -> ITEMS_BLOCKS_FLUIDS;
                case "special_items", "special_item", "nbt_items", "nbt_item" -> SPECIAL_ITEMS;
                case "commands", "command" -> COMMANDS;
                case "game_mechanics", "game_mechanic", "mechanics", "mechanic", "gameplay" -> GAME_MECHANICS;
                default -> null;
            };
        }
    }

    public record AssetRecord(
            String id,
            AssetCategory category,
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
            long createdAtEpochMilli,
            long updatedAtEpochMilli
    ) {
        public AssetRecord {
            id = id == null ? "" : id.trim();
            category = category == null ? AssetCategory.GAME_MECHANICS : category;
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

    public record AssetDraft(
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
            String sessionId
    ) {
    }

    public record UpsertResult(boolean success, boolean updated, String message, AssetRecord record) {
        public static UpsertResult success(AssetRecord record, boolean updated) {
            String verb = updated ? "Updated" : "Created";
            String message = verb + " asset record `" + (record == null ? "" : record.id()) + "`.";
            return new UpsertResult(true, updated, message, record);
        }

        public static UpsertResult error(String message) {
            String text = message == null || message.isBlank() ? "Asset upsert failed." : message;
            return new UpsertResult(false, false, text, null);
        }
    }
}
