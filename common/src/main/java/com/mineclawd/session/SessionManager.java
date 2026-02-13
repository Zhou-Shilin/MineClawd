package com.mineclawd.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.MineClawd;
import com.mineclawd.llm.OpenAIMessage;
import com.mineclawd.llm.OpenAIToolCall;
import com.mineclawd.llm.VertexAIMessage;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SessionManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern OWNER_SANITIZE = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern TITLE_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TITLE_SLUG_SANITIZE = Pattern.compile("[^a-zA-Z0-9\\s-]");
    private static final String ACTIVE_FILE_NAME = "active.json";
    private static final String DEFAULT_TITLE = "New Session";
    private static final int TITLE_MAX_LENGTH = 100;
    private static final int TITLE_SLUG_MAX_LENGTH = 48;
    private final Path sessionsRoot;

    public SessionManager() {
        this.sessionsRoot = Platform.getGameFolder().resolve("mineclawd").resolve("sessions");
        ensureDirectory(sessionsRoot);
    }

    public synchronized SessionData loadOrCreateActiveSession(String ownerKey) {
        SessionData active = loadActiveSession(ownerKey);
        if (active != null) {
            return active;
        }
        return createNewSession(ownerKey);
    }

    public synchronized SessionData loadActiveSession(String ownerKey) {
        Path ownerDir = ownerDirectory(ownerKey);
        ensureDirectory(ownerDir);
        String activeId = readActiveId(ownerDir);
        if (activeId != null && !activeId.isBlank()) {
            SessionData active = readSession(ownerDir.resolve(activeId + ".json"));
            if (active != null) {
                return active;
            }
        }

        List<SessionData> sessions = readAllSessions(ownerDir);
        if (sessions.isEmpty()) {
            return null;
        }
        sessions.sort(Comparator.comparingLong(SessionData::updatedAtEpochMilli).reversed());
        SessionData latest = sessions.get(0);
        writeActiveId(ownerDir, latest.id());
        return latest;
    }

    public synchronized SessionData createNewSession(String ownerKey) {
        Path ownerDir = ownerDirectory(ownerKey);
        ensureDirectory(ownerDir);

        Set<String> ids = existingIds(ownerDir);
        String id = generateUniqueId(ids);
        long now = Instant.now().toEpochMilli();

        SessionData session = new SessionData(
                id,
                DEFAULT_TITLE,
                buildTitleSlug(DEFAULT_TITLE),
                now,
                now,
                false,
                new ArrayList<>(),
                new ArrayList<>()
        );

        writeSession(ownerDir, session);
        writeActiveId(ownerDir, session.id());
        return session;
    }

    public synchronized List<SessionSummary> listSessions(String ownerKey) {
        Path ownerDir = ownerDirectory(ownerKey);
        if (!Files.isDirectory(ownerDir)) {
            return List.of();
        }
        String activeId = readActiveId(ownerDir);
        List<SessionData> sessions = readAllSessions(ownerDir);
        sessions.sort(Comparator.comparingLong(SessionData::updatedAtEpochMilli).reversed());
        List<SessionSummary> list = new ArrayList<>();
        for (SessionData session : sessions) {
            list.add(new SessionSummary(
                    session.id(),
                    session.title(),
                    session.commandToken(),
                    session.updatedAtEpochMilli(),
                    session.id().equalsIgnoreCase(activeId == null ? "" : activeId)
            ));
        }
        return list;
    }

    public synchronized SessionData resumeSession(String ownerKey, String reference) {
        SessionData session = resolve(ownerKey, reference);
        if (session == null) {
            return null;
        }
        Path ownerDir = ownerDirectory(ownerKey);
        writeActiveId(ownerDir, session.id());
        return session;
    }

    public synchronized SessionData resolve(String ownerKey, String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        Path ownerDir = ownerDirectory(ownerKey);
        if (!Files.isDirectory(ownerDir)) {
            return null;
        }
        String ref = reference.trim().toLowerCase(Locale.ROOT);
        for (SessionData session : readAllSessions(ownerDir)) {
            String id = session.id().toLowerCase(Locale.ROOT);
            String token = session.commandToken().toLowerCase(Locale.ROOT);
            if (ref.equals(id) || ref.equals(token) || ref.startsWith(id + "-")) {
                return session;
            }
        }
        return null;
    }

    public synchronized boolean removeSession(String ownerKey, String reference) {
        Path ownerDir = ownerDirectory(ownerKey);
        if (!Files.isDirectory(ownerDir)) {
            return false;
        }

        SessionData target = resolve(ownerKey, reference);
        if (target == null) {
            return false;
        }

        try {
            Files.deleteIfExists(ownerDir.resolve(target.id() + ".json"));
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to remove session {}: {}", target.id(), exception.getMessage());
            return false;
        }

        String activeId = readActiveId(ownerDir);
        if (target.id().equalsIgnoreCase(activeId == null ? "" : activeId)) {
            List<SessionData> remaining = readAllSessions(ownerDir);
            if (remaining.isEmpty()) {
                clearActiveId(ownerDir);
            } else {
                remaining.sort(Comparator.comparingLong(SessionData::updatedAtEpochMilli).reversed());
                writeActiveId(ownerDir, remaining.get(0).id());
            }
        }
        return true;
    }

    public synchronized void saveSession(String ownerKey, SessionData session) {
        if (session == null) {
            return;
        }
        Path ownerDir = ownerDirectory(ownerKey);
        ensureDirectory(ownerDir);
        writeSession(ownerDir, session);
    }

    public static String normalizeTitle(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return DEFAULT_TITLE;
        }
        text = text.replace('\r', ' ').replace('\n', ' ');
        text = TITLE_WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (text.length() > TITLE_MAX_LENGTH) {
            text = text.substring(0, TITLE_MAX_LENGTH).trim();
        }
        if (text.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return text;
    }

    public static String buildTitleSlug(String value) {
        String normalized = normalizeTitle(value);
        String slug = TITLE_SLUG_SANITIZE.matcher(normalized).replaceAll("");
        slug = TITLE_WHITESPACE.matcher(slug).replaceAll("-");
        while (slug.contains("--")) {
            slug = slug.replace("--", "-");
        }
        slug = slug.replaceAll("^-+", "").replaceAll("-+$", "");
        if (slug.length() > TITLE_SLUG_MAX_LENGTH) {
            slug = slug.substring(0, TITLE_SLUG_MAX_LENGTH).replaceAll("-+$", "");
        }
        if (slug.isBlank()) {
            return "session";
        }
        return slug;
    }

    private Path ownerDirectory(String ownerKey) {
        String safeKey = ownerKey == null || ownerKey.isBlank()
                ? "unknown"
                : OWNER_SANITIZE.matcher(ownerKey.trim()).replaceAll("_");
        if (safeKey.isBlank()) {
            safeKey = "unknown";
        }
        return sessionsRoot.resolve(safeKey);
    }

    private void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create session directory: " + path, exception);
        }
    }

    private Set<String> existingIds(Path ownerDir) {
        Set<String> ids = new HashSet<>();
        for (SessionData session : readAllSessions(ownerDir)) {
            ids.add(session.id().toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    private String generateUniqueId(Set<String> existingIds) {
        for (int i = 0; i < 256; i++) {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toLowerCase(Locale.ROOT);
            if (!existingIds.contains(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to allocate unique 4-character session id.");
    }

    private List<SessionData> readAllSessions(Path ownerDir) {
        List<SessionData> sessions = new ArrayList<>();
        try (var stream = Files.list(ownerDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !ACTIVE_FILE_NAME.equals(path.getFileName().toString()))
                    .forEach(path -> {
                        SessionData session = readSession(path);
                        if (session != null) {
                            sessions.add(session);
                        }
                    });
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to read sessions in {}: {}", ownerDir, exception.getMessage());
        }
        return sessions;
    }

    private SessionData readSession(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            String id = stringValue(root, "id");
            if (id.isBlank()) {
                id = path.getFileName().toString().replace(".json", "");
            }
            String title = normalizeTitle(stringValue(root, "title"));
            String titleSlug = buildTitleSlug(title);
            long createdAt = longValue(root, "createdAt", Instant.now().toEpochMilli());
            long updatedAt = longValue(root, "updatedAt", createdAt);
            boolean titleGenerated = booleanValue(root, "titleGenerated", !DEFAULT_TITLE.equals(title));

            List<OpenAIMessage> openAiHistory = readOpenAiHistory(root);
            List<VertexAIMessage> vertexHistory = readVertexHistory(root);

            return new SessionData(id, title, titleSlug, createdAt, updatedAt, titleGenerated, openAiHistory, vertexHistory);
        } catch (Exception exception) {
            MineClawd.LOGGER.warn("Failed to read session file {}: {}", path, exception.getMessage());
            return null;
        }
    }

    private void writeSession(Path ownerDir, SessionData session) {
        Path path = ownerDir.resolve(session.id() + ".json");
        JsonObject root = new JsonObject();
        root.addProperty("id", session.id());
        root.addProperty("title", normalizeTitle(session.title()));
        root.addProperty("titleSlug", session.titleSlug());
        root.addProperty("createdAt", session.createdAtEpochMilli());
        root.addProperty("updatedAt", session.updatedAtEpochMilli());
        root.addProperty("titleGenerated", session.titleGenerated());
        root.add("openAiHistory", writeOpenAiHistory(session.openAiHistory()));
        root.add("vertexHistory", writeVertexHistory(session.vertexHistory()));
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
            throw new IllegalStateException("Failed to write session file: " + path, exception);
        }
    }

    private String readActiveId(Path ownerDir) {
        Path activePath = ownerDir.resolve(ACTIVE_FILE_NAME);
        if (!Files.isRegularFile(activePath)) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(activePath, StandardCharsets.UTF_8)).getAsJsonObject();
            return stringValue(root, "activeSessionId");
        } catch (Exception exception) {
            MineClawd.LOGGER.warn("Failed to read active session in {}: {}", ownerDir, exception.getMessage());
            return "";
        }
    }

    private void writeActiveId(Path ownerDir, String activeId) {
        JsonObject root = new JsonObject();
        root.addProperty("activeSessionId", activeId == null ? "" : activeId);
        try {
            Files.writeString(
                    ownerDir.resolve(ACTIVE_FILE_NAME),
                    GSON.toJson(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write active session file in " + ownerDir, exception);
        }
    }

    private void clearActiveId(Path ownerDir) {
        try {
            Files.deleteIfExists(ownerDir.resolve(ACTIVE_FILE_NAME));
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to clear active session in {}: {}", ownerDir, exception.getMessage());
        }
    }

    private List<OpenAIMessage> readOpenAiHistory(JsonObject root) {
        List<OpenAIMessage> messages = new ArrayList<>();
        if (!root.has("openAiHistory") || !root.get("openAiHistory").isJsonArray()) {
            return messages;
        }
        JsonArray array = root.getAsJsonArray("openAiHistory");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String role = stringValue(message, "role");
            String content = nullableStringValue(message, "content");
            String toolCallId = nullableStringValue(message, "toolCallId");
            List<OpenAIToolCall> toolCalls = new ArrayList<>();
            if (message.has("toolCalls") && message.get("toolCalls").isJsonArray()) {
                for (JsonElement callElement : message.getAsJsonArray("toolCalls")) {
                    if (!callElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject call = callElement.getAsJsonObject();
                    toolCalls.add(new OpenAIToolCall(
                            stringValue(call, "id"),
                            stringValue(call, "name"),
                            stringValue(call, "arguments")
                    ));
                }
            }
            messages.add(new OpenAIMessage(role, content, toolCalls.isEmpty() ? null : toolCalls, toolCallId));
        }
        return messages;
    }

    private JsonArray writeOpenAiHistory(List<OpenAIMessage> history) {
        JsonArray array = new JsonArray();
        if (history == null) {
            return array;
        }
        for (OpenAIMessage message : history) {
            if (message == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            if (message.content() == null) {
                object.add("content", null);
            } else {
                object.addProperty("content", message.content());
            }
            if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                object.addProperty("toolCallId", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                JsonArray calls = new JsonArray();
                for (OpenAIToolCall call : message.toolCalls()) {
                    if (call == null) {
                        continue;
                    }
                    JsonObject callObject = new JsonObject();
                    callObject.addProperty("id", call.id());
                    callObject.addProperty("name", call.name());
                    callObject.addProperty("arguments", call.arguments());
                    calls.add(callObject);
                }
                object.add("toolCalls", calls);
            }
            array.add(object);
        }
        return array;
    }

    private List<VertexAIMessage> readVertexHistory(JsonObject root) {
        List<VertexAIMessage> messages = new ArrayList<>();
        if (!root.has("vertexHistory") || !root.get("vertexHistory").isJsonArray()) {
            return messages;
        }
        JsonArray array = root.getAsJsonArray("vertexHistory");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject message = element.getAsJsonObject();
            String role = stringValue(message, "role");
            List<JsonObject> parts = new ArrayList<>();
            if (message.has("parts") && message.get("parts").isJsonArray()) {
                for (JsonElement partElement : message.getAsJsonArray("parts")) {
                    if (partElement.isJsonObject()) {
                        parts.add(partElement.getAsJsonObject().deepCopy());
                    }
                }
            }
            messages.add(new VertexAIMessage(role, parts));
        }
        return messages;
    }

    private JsonArray writeVertexHistory(List<VertexAIMessage> history) {
        JsonArray array = new JsonArray();
        if (history == null) {
            return array;
        }
        for (VertexAIMessage message : history) {
            if (message == null) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            object.add("parts", message.toJsonParts());
            array.add(object);
        }
        return array;
    }

    private static String stringValue(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String nullableStringValue(JsonObject object, String key) {
        String value = stringValue(object, key);
        return value.isBlank() ? null : value;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static final class SessionData {
        private final String id;
        private String title;
        private String titleSlug;
        private final long createdAtEpochMilli;
        private long updatedAtEpochMilli;
        private boolean titleGenerated;
        private final List<OpenAIMessage> openAiHistory;
        private final List<VertexAIMessage> vertexHistory;

        public SessionData(
                String id,
                String title,
                String titleSlug,
                long createdAtEpochMilli,
                long updatedAtEpochMilli,
                boolean titleGenerated,
                List<OpenAIMessage> openAiHistory,
                List<VertexAIMessage> vertexHistory
        ) {
            this.id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            this.title = normalizeTitle(title);
            this.titleSlug = titleSlug == null || titleSlug.isBlank() ? buildTitleSlug(this.title) : titleSlug;
            this.createdAtEpochMilli = createdAtEpochMilli;
            this.updatedAtEpochMilli = updatedAtEpochMilli;
            this.titleGenerated = titleGenerated;
            this.openAiHistory = openAiHistory == null ? new ArrayList<>() : openAiHistory;
            this.vertexHistory = vertexHistory == null ? new ArrayList<>() : vertexHistory;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public String titleSlug() {
            return titleSlug;
        }

        public String commandToken() {
            return id + "-" + titleSlug;
        }

        public void setTitle(String title) {
            this.title = normalizeTitle(title);
            this.titleSlug = buildTitleSlug(this.title);
        }

        public long createdAtEpochMilli() {
            return createdAtEpochMilli;
        }

        public long updatedAtEpochMilli() {
            return updatedAtEpochMilli;
        }

        public void touch() {
            this.updatedAtEpochMilli = Instant.now().toEpochMilli();
        }

        public boolean titleGenerated() {
            return titleGenerated;
        }

        public void setTitleGenerated(boolean titleGenerated) {
            this.titleGenerated = titleGenerated;
        }

        public List<OpenAIMessage> openAiHistory() {
            return openAiHistory;
        }

        public List<VertexAIMessage> vertexHistory() {
            return vertexHistory;
        }
    }

    public record SessionSummary(String id, String title, String token, long updatedAtEpochMilli, boolean active) {
    }
}
