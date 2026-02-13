package com.mineclawd.question;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QuestionPromptPayload {
    private final String questionId;
    private final String question;
    private final List<String> options;
    private final long expiresAtEpochMillis;

    public QuestionPromptPayload(String questionId, String question, List<String> options, long expiresAtEpochMillis) {
        this.questionId = questionId == null ? "" : questionId.trim();
        this.question = question == null ? "" : question;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    public String questionId() {
        return questionId;
    }

    public String question() {
        return question;
    }

    public List<String> options() {
        return Collections.unmodifiableList(options);
    }

    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("questionId", questionId);
        root.addProperty("question", question);
        JsonArray optionsArray = new JsonArray();
        for (String option : options) {
            if (option != null && !option.isBlank()) {
                optionsArray.add(option);
            }
        }
        root.add("options", optionsArray);
        root.addProperty("expiresAtEpochMillis", expiresAtEpochMillis);
        return root.toString();
    }

    public static QuestionPromptPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String questionId = readString(root, "questionId");
            String question = readString(root, "question");
            long expiresAt = root.has("expiresAtEpochMillis") ? root.get("expiresAtEpochMillis").getAsLong() : 0L;

            List<String> options = new ArrayList<>();
            if (root.has("options") && root.get("options").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("options")) {
                    if (!element.isJsonNull()) {
                        String option = element.getAsString();
                        if (!option.isBlank()) {
                            options.add(option);
                        }
                    }
                }
            }

            if (questionId.isBlank() || question.isBlank()) {
                return null;
            }
            return new QuestionPromptPayload(questionId, question, options, expiresAt);
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
}

