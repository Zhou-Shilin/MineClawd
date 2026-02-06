package com.mineclawd.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OpenAIClient {
    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";

    private final HttpClient httpClient;

    public OpenAIClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public CompletableFuture<OpenAIResponse> sendMessage(
            String endpoint,
            String apiKey,
            String model,
            List<OpenAIMessage> history,
            @Nullable List<OpenAITool> tools
    ) {
        String baseUrl = normalizeEndpoint(endpoint);
        URI uri = URI.create(baseUrl + "/chat/completions");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();
        if (history != null) {
            for (OpenAIMessage message : history) {
                if (message == null) {
                    continue;
                }
                JsonObject msg = new JsonObject();
                msg.addProperty("role", message.role());
                if (message.content() != null) {
                    msg.addProperty("content", message.content());
                } else {
                    msg.add("content", JsonNull.INSTANCE);
                }
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    JsonArray toolCalls = new JsonArray();
                    for (OpenAIToolCall call : message.toolCalls()) {
                        if (call == null) {
                            continue;
                        }
                        JsonObject callObj = new JsonObject();
                        if (call.id() != null && !call.id().isBlank()) {
                            callObj.addProperty("id", call.id());
                        }
                        callObj.addProperty("type", "function");
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        function.addProperty("arguments", call.arguments() == null ? "" : call.arguments());
                        callObj.add("function", function);
                        toolCalls.add(callObj);
                    }
                    msg.add("tool_calls", toolCalls);
                }
                if (message.toolCallId() != null && !message.toolCallId().isBlank()) {
                    msg.addProperty("tool_call_id", message.toolCallId());
                }
                messages.add(msg);
            }
        }
        body.add("messages", messages);

        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (OpenAITool tool : tools) {
                if (tool == null) {
                    continue;
                }
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                if (tool.description() != null && !tool.description().isBlank()) {
                    function.addProperty("description", tool.description());
                }
                if (tool.parameters() != null) {
                    function.add("parameters", tool.parameters());
                }
                toolObj.add("function", function);
                toolsArray.add(toolObj);
            }
            body.add("tools", toolsArray);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        String errorMessage = extractErrorMessage(response.body());
                        throw new RuntimeException("OpenAI API error (" + status + "): " + errorMessage);
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

    private static OpenAIResponse parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject message = extractMessage(root);
        String text = extractOutputText(message);
        List<OpenAIToolCall> toolCalls = extractToolCalls(message);
        return new OpenAIResponse(text, toolCalls);
    }

    private static JsonObject extractMessage(JsonObject root) {
        if (root == null) {
            return new JsonObject();
        }

        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                ? root.getAsJsonArray("choices")
                : new JsonArray();

        for (JsonElement choiceElement : choices) {
            if (!choiceElement.isJsonObject()) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            if (choice.has("message") && choice.get("message").isJsonObject()) {
                return choice.getAsJsonObject("message");
            }
        }

        return new JsonObject();
    }

    private static String extractOutputText(JsonObject message) {
        if (message == null) {
            return "";
        }
        if (message.has("content") && !message.get("content").isJsonNull()) {
            JsonElement content = message.get("content");
            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }
        }
        return "";
    }

    private static List<OpenAIToolCall> extractToolCalls(JsonObject message) {
        List<OpenAIToolCall> toolCalls = new ArrayList<>();
        if (message == null) {
            return toolCalls;
        }
        if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
            return toolCalls;
        }
        JsonArray calls = message.getAsJsonArray("tool_calls");
        for (JsonElement callElement : calls) {
            if (!callElement.isJsonObject()) {
                continue;
            }
            JsonObject call = callElement.getAsJsonObject();
            JsonObject function = call.has("function") && call.get("function").isJsonObject()
                    ? call.getAsJsonObject("function")
                    : new JsonObject();
            String id = call.has("id") ? call.get("id").getAsString() : "";
            String name = function.has("name") ? function.get("name").getAsString() : "";
            String arguments = function.has("arguments") ? function.get("arguments").getAsString() : "";
            toolCalls.add(new OpenAIToolCall(id, name, arguments));
        }
        return toolCalls;
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
