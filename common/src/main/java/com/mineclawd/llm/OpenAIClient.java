package com.mineclawd.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

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
        return sendMessage(endpoint, apiKey, model, history, tools, null);
    }

    public CompletableFuture<OpenAIResponse> sendMessage(
            String endpoint,
            String apiKey,
            String model,
            List<OpenAIMessage> history,
            @Nullable List<OpenAITool> tools,
            @Nullable Consumer<String> streamDeltaConsumer
    ) {
        String baseUrl = normalizeEndpoint(endpoint);
        URI uri = URI.create(baseUrl + "/chat/completions");

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        if (streamDeltaConsumer != null) {
            body.addProperty("stream", true);
        }

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

        if (streamDeltaConsumer == null) {
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

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if (status < 200 || status >= 300) {
                        try (InputStream stream = response.body()) {
                            String bodyText = readBodyAsString(stream);
                            String errorMessage = extractErrorMessage(bodyText);
                            CompletableFuture<OpenAIResponse> failed = new CompletableFuture<>();
                            failed.completeExceptionally(new RuntimeException("OpenAI API error (" + status + "): " + errorMessage));
                            return failed;
                        } catch (IOException exception) {
                            CompletableFuture<OpenAIResponse> failed = new CompletableFuture<>();
                            failed.completeExceptionally(exception);
                            return failed;
                        }
                    }
                    return CompletableFuture.supplyAsync(() -> {
                        try (InputStream stream = response.body()) {
                            return parseStreamingResponse(stream, streamDeltaConsumer);
                        } catch (IOException exception) {
                            throw new CompletionException(exception);
                        }
                    });
                });
    }

    private static OpenAIResponse parseStreamingResponse(InputStream stream, Consumer<String> streamDeltaConsumer) throws IOException {
        if (stream == null) {
            return new OpenAIResponse("", List.of());
        }
        StringBuilder fullText = new StringBuilder();
        Map<Integer, StreamingToolCallBuilder> toolCallBuilders = new TreeMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder dataBlock = new StringBuilder();
            boolean done = false;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    done = consumeStreamDataBlock(dataBlock, fullText, toolCallBuilders, streamDeltaConsumer) || done;
                    dataBlock.setLength(0);
                    if (done) {
                        break;
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (!dataBlock.isEmpty()) {
                        dataBlock.append('\n');
                    }
                    dataBlock.append(data);
                }
            }
            if (!done && !dataBlock.isEmpty()) {
                consumeStreamDataBlock(dataBlock, fullText, toolCallBuilders, streamDeltaConsumer);
            }
        }

        List<OpenAIToolCall> toolCalls = new ArrayList<>();
        for (StreamingToolCallBuilder builder : toolCallBuilders.values()) {
            if (builder == null) {
                continue;
            }
            toolCalls.add(builder.build());
        }
        return new OpenAIResponse(fullText.toString(), toolCalls);
    }

    private static boolean consumeStreamDataBlock(
            StringBuilder dataBlock,
            StringBuilder textBuilder,
            Map<Integer, StreamingToolCallBuilder> toolCallBuilders,
            Consumer<String> streamDeltaConsumer
    ) {
        if (dataBlock == null || dataBlock.isEmpty()) {
            return false;
        }
        String data = dataBlock.toString().trim();
        if (data.isEmpty()) {
            return false;
        }
        if ("[DONE]".equals(data)) {
            return true;
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception ignored) {
            return false;
        }

        JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                ? root.getAsJsonArray("choices")
                : new JsonArray();
        for (JsonElement choiceElement : choices) {
            if (!choiceElement.isJsonObject()) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            JsonObject delta = choice.has("delta") && choice.get("delta").isJsonObject()
                    ? choice.getAsJsonObject("delta")
                    : new JsonObject();

            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                String contentDelta = extractDeltaText(delta.get("content"));
                if (!contentDelta.isEmpty()) {
                    textBuilder.append(contentDelta);
                    streamDeltaConsumer.accept(contentDelta);
                }
            }

            if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                JsonArray calls = delta.getAsJsonArray("tool_calls");
                for (int i = 0; i < calls.size(); i++) {
                    JsonElement callElement = calls.get(i);
                    if (!callElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject callJson = callElement.getAsJsonObject();
                    int index = callJson.has("index") && callJson.get("index").isJsonPrimitive()
                            ? callJson.get("index").getAsInt()
                            : i;
                    StreamingToolCallBuilder builder = toolCallBuilders.computeIfAbsent(index, ignored -> new StreamingToolCallBuilder());
                    if (callJson.has("id") && callJson.get("id").isJsonPrimitive()) {
                        builder.id = callJson.get("id").getAsString();
                    }
                    JsonObject function = callJson.has("function") && callJson.get("function").isJsonObject()
                            ? callJson.getAsJsonObject("function")
                            : new JsonObject();
                    if (function.has("name") && function.get("name").isJsonPrimitive()) {
                        builder.name = function.get("name").getAsString();
                    }
                    if (function.has("arguments") && function.get("arguments").isJsonPrimitive()) {
                        builder.arguments.append(function.get("arguments").getAsString());
                    }
                }
            }
        }
        return false;
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
            return extractDeltaText(content);
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

    private static String extractDeltaText(JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        JsonArray parts = content.getAsJsonArray();
        for (JsonElement partElement : parts) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonObject part = partElement.getAsJsonObject();
            if (part.has("type") && part.get("type").isJsonPrimitive()
                    && !"text".equalsIgnoreCase(part.get("type").getAsString())) {
                continue;
            }
            if (part.has("text") && part.get("text").isJsonPrimitive()) {
                sb.append(part.get("text").getAsString());
            }
        }
        return sb.toString();
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

    private static String readBodyAsString(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        byte[] bytes = stream.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class StreamingToolCallBuilder {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        private OpenAIToolCall build() {
            return new OpenAIToolCall(id, name, arguments.toString());
        }
    }
}
