package com.mineclawd.question;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class QuestionResponsePayload {
    public enum Type {
        OPTION,
        OTHER,
        SKIP
    }

    private final String questionId;
    private final Type type;
    private final int optionIndex;
    private final String value;

    public QuestionResponsePayload(String questionId, Type type, int optionIndex, String value) {
        this.questionId = questionId == null ? "" : questionId.trim();
        this.type = type == null ? Type.SKIP : type;
        this.optionIndex = optionIndex;
        this.value = value == null ? "" : value;
    }

    public String questionId() {
        return questionId;
    }

    public Type type() {
        return type;
    }

    public int optionIndex() {
        return optionIndex;
    }

    public String value() {
        return value;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("questionId", questionId);
        root.addProperty("type", type.name().toLowerCase());
        root.addProperty("optionIndex", optionIndex);
        root.addProperty("value", value);
        return root.toString();
    }

    public static QuestionResponsePayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String questionId = readString(root, "questionId");
            Type type = parseType(readString(root, "type"));
            int optionIndex = root.has("optionIndex") ? root.get("optionIndex").getAsInt() : -1;
            String value = readString(root, "value");
            if (questionId.isBlank()) {
                return null;
            }
            return new QuestionResponsePayload(questionId, type, optionIndex, value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Type parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return Type.SKIP;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "option" -> Type.OPTION;
            case "other" -> Type.OTHER;
            default -> Type.SKIP;
        };
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
}

