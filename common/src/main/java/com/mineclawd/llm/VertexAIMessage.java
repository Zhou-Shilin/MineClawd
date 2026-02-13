package com.mineclawd.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public record VertexAIMessage(String role, List<JsonObject> parts) {
    public static VertexAIMessage user(String text) {
        return new VertexAIMessage("user", List.of(textPart(text)));
    }

    public static VertexAIMessage model(String text) {
        return new VertexAIMessage("model", List.of(textPart(text)));
    }

    public static VertexAIMessage modelParts(List<JsonObject> parts) {
        return new VertexAIMessage("model", parts);
    }

    public static VertexAIMessage toolResponse(String name, JsonObject response) {
        return new VertexAIMessage("user", List.of(functionResponsePart(name, response)));
    }

    public JsonArray toJsonParts() {
        JsonArray array = new JsonArray();
        if (parts == null) {
            return array;
        }
        for (JsonObject part : parts) {
            if (part != null) {
                array.add(part);
            }
        }
        return array;
    }

    public static JsonObject textPart(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text == null ? "" : text);
        return part;
    }

    public static JsonObject functionResponsePart(String name, JsonObject response) {
        JsonObject part = new JsonObject();
        JsonObject functionResponse = new JsonObject();
        functionResponse.addProperty("name", name == null ? "" : name);
        functionResponse.add("response", response == null ? new JsonObject() : response);
        part.add("functionResponse", functionResponse);
        return part;
    }
}
