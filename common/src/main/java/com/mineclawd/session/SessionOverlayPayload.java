package com.mineclawd.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionOverlayPayload {
    private final boolean openUi;
    private final String activeSessionId;
    private final String activePersona;
    private final List<String> personas;
    private final List<SessionItem> sessions;
    private final List<HistoryItem> history;

    public SessionOverlayPayload(
            boolean openUi,
            String activeSessionId,
            String activePersona,
            List<String> personas,
            List<SessionItem> sessions,
            List<HistoryItem> history
    ) {
        this.openUi = openUi;
        this.activeSessionId = activeSessionId == null ? "" : activeSessionId.trim();
        this.activePersona = activePersona == null ? "" : activePersona.trim();
        this.personas = personas == null ? List.of() : List.copyOf(personas);
        this.sessions = sessions == null ? List.of() : List.copyOf(sessions);
        this.history = history == null ? List.of() : List.copyOf(history);
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

    public List<SessionItem> sessions() {
        return Collections.unmodifiableList(sessions);
    }

    public List<HistoryItem> history() {
        return Collections.unmodifiableList(history);
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

        JsonArray sessionsArray = new JsonArray();
        for (SessionItem item : sessions) {
            if (item == null) {
                continue;
            }
            JsonObject sessionObject = new JsonObject();
            sessionObject.addProperty("id", item.id());
            sessionObject.addProperty("title", item.title());
            sessionObject.addProperty("token", item.token());
            sessionObject.addProperty("updatedAtEpochMillis", item.updatedAtEpochMillis());
            sessionObject.addProperty("active", item.active());
            sessionsArray.add(sessionObject);
        }
        root.add("sessions", sessionsArray);

        JsonArray historyArray = new JsonArray();
        for (HistoryItem item : history) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            JsonObject historyObject = new JsonObject();
            historyObject.addProperty("assistant", item.assistant());
            historyObject.addProperty("content", item.content());
            historyArray.add(historyObject);
        }
        root.add("history", historyArray);

        return root.toString();
    }

    public static SessionOverlayPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            boolean openUi = root.has("openUi")
                    && root.get("openUi").isJsonPrimitive()
                    && root.get("openUi").getAsBoolean();
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

            List<SessionItem> sessions = new ArrayList<>();
            if (root.has("sessions") && root.get("sessions").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("sessions")) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    String id = readString(object, "id");
                    if (id.isBlank()) {
                        continue;
                    }
                    sessions.add(new SessionItem(
                            id,
                            readString(object, "title"),
                            readString(object, "token"),
                            readLong(object, "updatedAtEpochMillis", 0L),
                            readBoolean(object, "active")
                    ));
                }
            }

            List<HistoryItem> history = new ArrayList<>();
            if (root.has("history") && root.get("history").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("history")) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    String content = readString(object, "content");
                    if (content.isBlank()) {
                        continue;
                    }
                    history.add(new HistoryItem(readBoolean(object, "assistant"), content));
                }
            }

            return new SessionOverlayPayload(openUi, activeSessionId, activePersona, personas, sessions, history);
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

    public record SessionItem(
            String id,
            String title,
            String token,
            long updatedAtEpochMillis,
            boolean active
    ) {
    }

    public record HistoryItem(boolean assistant, String content) {
    }
}
