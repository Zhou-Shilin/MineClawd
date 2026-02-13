package com.mineclawd.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class VertexAIClient {
    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_ENDPOINT = "https://aiplatform.googleapis.com/v1";

    private final HttpClient httpClient;

    public VertexAIClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public CompletableFuture<VertexAIResponse> sendMessage(
            String endpoint,
            String apiKey,
            String model,
            List<VertexAIMessage> history,
            List<VertexAIFunction> tools
    ) {
        String baseUrl = normalizeEndpoint(endpoint);
        String modelPath = normalizeModel(model);
        if (modelPath.isBlank()) {
            CompletableFuture<VertexAIResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("Vertex AI model is missing."));
            return failed;
        }

        String encodedKey = URLEncoder.encode(apiKey == null ? "" : apiKey, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/" + modelPath + ":generateContent?key=" + encodedKey);

        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        if (history != null) {
            for (VertexAIMessage message : history) {
                if (message == null) {
                    continue;
                }
                JsonObject content = new JsonObject();
                if (message.role() != null && !message.role().isBlank()) {
                    content.addProperty("role", message.role());
                }
                content.add("parts", message.toJsonParts());
                contents.add(content);
            }
        }
        body.add("contents", contents);
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            JsonObject tool = new JsonObject();
            JsonArray declarations = new JsonArray();
            for (VertexAIFunction function : tools) {
                if (function == null) {
                    continue;
                }
                JsonObject functionJson = new JsonObject();
                functionJson.addProperty("name", function.name());
                if (function.description() != null && !function.description().isBlank()) {
                    functionJson.addProperty("description", function.description());
                }
                if (function.parameters() != null) {
                    functionJson.add("parameters", function.parameters());
                }
                declarations.add(functionJson);
            }
            tool.add("functionDeclarations", declarations);
            toolsArray.add(tool);
            body.add("tools", toolsArray);

            JsonObject toolConfig = new JsonObject();
            JsonObject functionCallingConfig = new JsonObject();
            functionCallingConfig.addProperty("mode", "AUTO");
            toolConfig.add("functionCallingConfig", functionCallingConfig);
            body.add("toolConfig", toolConfig);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        String errorMessage = extractErrorMessage(response.body());
                        throw new RuntimeException("Vertex AI API error (" + status + "): " + errorMessage);
                    }
                    return parseResponse(response.body());
                });
    }

    private static String normalizeEndpoint(String endpoint) {
        String trimmed = endpoint == null ? "" : endpoint.trim();
        if (trimmed.isEmpty()) {
            trimmed = DEFAULT_ENDPOINT;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeModel(String model) {
        String trimmed = model == null ? "" : model.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.contains("/")) {
            trimmed = "publishers/google/models/" + trimmed;
        }
        return trimmed;
    }

    private static VertexAIResponse parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        return extractResponse(root);
    }

    private static VertexAIResponse extractResponse(JsonObject root) {
        if (root == null) {
            return new VertexAIResponse("", List.of(), VertexAIMessage.modelParts(List.of()));
        }
        JsonObject content = extractContent(root);
        List<JsonObject> modelParts = new ArrayList<>();
        List<VertexAIToolCall> toolCalls = new ArrayList<>();
        List<String> texts = new ArrayList<>();

        if (content != null && content.has("parts") && content.get("parts").isJsonArray()) {
            JsonArray parts = content.getAsJsonArray("parts");
            for (JsonElement partElement : parts) {
                if (!partElement.isJsonObject()) {
                    continue;
                }
                JsonObject part = partElement.getAsJsonObject();
                modelParts.add(part);
                if (part.has("text")) {
                    texts.add(part.get("text").getAsString());
                }
                if (part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                    JsonObject functionCall = part.getAsJsonObject("functionCall");
                    String name = functionCall.has("name") ? functionCall.get("name").getAsString() : "";
                    JsonObject args = functionCall.has("args") && functionCall.get("args").isJsonObject()
                            ? functionCall.getAsJsonObject("args")
                            : new JsonObject();
                    toolCalls.add(new VertexAIToolCall(name, args));
                }
            }
        }

        String text = String.join("\n", texts).trim();
        VertexAIMessage modelMessage = VertexAIMessage.modelParts(modelParts);
        return new VertexAIResponse(text, toolCalls, modelMessage);
    }

    private static JsonObject extractContent(JsonObject root) {
        JsonArray candidates = root.has("candidates") && root.get("candidates").isJsonArray()
                ? root.getAsJsonArray("candidates")
                : new JsonArray();

        for (JsonElement candidateElement : candidates) {
            if (!candidateElement.isJsonObject()) {
                continue;
            }
            JsonObject candidate = candidateElement.getAsJsonObject();
            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                return candidate.getAsJsonObject("content");
            }
        }
        return null;
    }

    private static String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (root.has("error") && root.get("error").isJsonObject()) {
                JsonObject error = root.getAsJsonObject("error");
                if (error.has("message")) {
                    return error.get("message").getAsString();
                }
            }
        } catch (Exception ignored) {
        }
        return body == null || body.isBlank() ? "Unknown error" : body;
    }
}
