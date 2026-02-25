package com.mineclawd.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.kubejs.KubeJsToolExecutor.ToolExecutionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class SearchToolExecutor {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT = "MineClawd/1.0";
    private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";
    private static final int MIN_RESULTS = 1;
    private static final int MAX_RESULTS = 10;
    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_QUERY_CHARS = 400;
    private static final int MAX_SNIPPET_CHARS = 420;
    private static final int MAX_ERROR_BODY_CHARS = 400;
    private static final int MAX_OUTPUT_CHARS = 14_000;

    private SearchToolExecutor() {
    }

    public static ToolExecutionResult searchWeb(String tavilyApiKey, String query, Integer maxResults) {
        if (blank(tavilyApiKey)) {
            return new ToolExecutionResult(false, "Search tool is disabled because Tavily API key is not configured.");
        }
        String normalizedQuery = safe(query).trim();
        if (blank(normalizedQuery)) {
            return new ToolExecutionResult(false, "Missing required `query`.");
        }
        if (normalizedQuery.length() > MAX_QUERY_CHARS) {
            normalizedQuery = normalizedQuery.substring(0, MAX_QUERY_CHARS).trim();
        }

        int limit = clampResults(maxResults);
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("api_key", tavilyApiKey.trim());
        requestBody.addProperty("query", normalizedQuery);
        requestBody.addProperty("search_depth", "advanced");
        requestBody.addProperty("max_results", limit);
        requestBody.addProperty("include_answer", true);
        requestBody.addProperty("include_images", false);
        requestBody.addProperty("include_raw_content", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(TAVILY_SEARCH_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return new ToolExecutionResult(false, "Search request failed: " + safe(exception.getMessage()));
        }
        if (response == null) {
            return new ToolExecutionResult(false, "Search request failed: empty HTTP response.");
        }

        String body = safe(response.body());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = body.length() > MAX_ERROR_BODY_CHARS
                    ? body.substring(0, MAX_ERROR_BODY_CHARS).trim() + "..."
                    : body;
            return new ToolExecutionResult(false, "Search API error (HTTP " + status + "): " + safe(detail));
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception exception) {
            return new ToolExecutionResult(false, "Search API returned invalid JSON.");
        }

        String answer = safe(string(root, "answer")).trim();
        JsonArray results = root.has("results") && root.get("results").isJsonArray()
                ? root.getAsJsonArray("results")
                : new JsonArray();

        StringBuilder out = new StringBuilder();
        out.append("Web search results for `").append(normalizedQuery).append("`:");
        if (!answer.isBlank()) {
            out.append("\nSummary: ").append(answer);
        }
        if (results.isEmpty()) {
            out.append("\nNo result entries returned.");
            return new ToolExecutionResult(true, trimToMax(out.toString().trim(), MAX_OUTPUT_CHARS));
        }

        int count = 0;
        for (JsonElement element : results) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String title = safe(string(entry, "title")).trim();
            String url = safe(string(entry, "url")).trim();
            String snippet = safe(string(entry, "content")).trim();
            if (snippet.isBlank()) {
                snippet = safe(string(entry, "snippet")).trim();
            }
            if (snippet.length() > MAX_SNIPPET_CHARS) {
                snippet = snippet.substring(0, MAX_SNIPPET_CHARS).trim() + "...";
            }

            count++;
            out.append("\n\n").append(count).append(". ");
            out.append(title.isBlank() ? "(untitled)" : title);
            if (!url.isBlank()) {
                out.append("\nURL: ").append(url);
            }
            if (!snippet.isBlank()) {
                out.append("\nSnippet: ").append(snippet);
            }
            if (entry.has("score") && entry.get("score").isJsonPrimitive()) {
                try {
                    double score = entry.get("score").getAsDouble();
                    out.append("\nScore: ").append(String.format(java.util.Locale.ROOT, "%.3f", score));
                } catch (Exception ignored) {
                }
            }
            if (count >= limit) {
                break;
            }
        }

        if (count == 0) {
            out.append("\nNo result entries returned.");
        }
        return new ToolExecutionResult(true, trimToMax(out.toString().trim(), MAX_OUTPUT_CHARS));
    }

    private static int clampResults(Integer requested) {
        if (requested == null) {
            return DEFAULT_RESULTS;
        }
        if (requested < MIN_RESULTS) {
            return MIN_RESULTS;
        }
        if (requested > MAX_RESULTS) {
            return MAX_RESULTS;
        }
        return requested;
    }

    private static String string(JsonObject object, String key) {
        if (object == null || key == null || key.isBlank() || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String trimToMax(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, Math.max(0, maxChars));
        }
        return text.substring(0, maxChars - 3) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
