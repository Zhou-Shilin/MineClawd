package com.mineclawd.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.kubejs.KubeJsToolExecutor.ToolExecutionResult;
import com.mojang.brigadier.tree.CommandNode;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.server.command.ServerCommandSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModToolsExecutor {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);
    private static final String USER_AGENT = "MineClawd/1.0";
    private static final FlexmarkHtmlConverter HTML_TO_MD = FlexmarkHtmlConverter.builder().build();

    private static final int MAX_COMMAND_RESULTS = 500;
    private static final int MAX_SOURCE_CHARS = 120_000;
    private static final int MAX_OUTPUT_CHARS = 14_000;
    private static final int CHUNK_TARGET_CHARS = 1_200;
    private static final int CHUNK_TOP_COUNT = 3;

    private static final Pattern MODRINTH_LINK_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?modrinth\\.com/(?:mod|plugin|datapack|resourcepack|shader|project)/([A-Za-z0-9._-]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9]+");

    private ModToolsExecutor() {
    }

    public static ToolExecutionResult listCommands(ServerCommandSource source, String modIdFilter) {
        if (source == null || source.getServer() == null) {
            return new ToolExecutionResult(false, "No server available.");
        }

        List<String> commands = new ArrayList<>();
        for (CommandNode<ServerCommandSource> node : source.getServer().getCommandManager().getDispatcher().getRoot().getChildren()) {
            if (node == null || blank(node.getName())) {
                continue;
            }
            commands.add(node.getName());
        }

        commands = commands.stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (!blank(modIdFilter)) {
            Set<String> aliases = buildModAliases(modIdFilter);
            commands = commands.stream()
                    .filter(name -> matchesCommandFilter(name, aliases))
                    .toList();
        }

        if (commands.isEmpty()) {
            if (blank(modIdFilter)) {
                return new ToolExecutionResult(true, "No commands available.");
            }
            return new ToolExecutionResult(
                    true,
                    "No commands matched mod_id `" + safe(modIdFilter).trim() + "` (filtering is best-effort by command name/prefix)."
            );
        }

        StringBuilder out = new StringBuilder();
        if (blank(modIdFilter)) {
            out.append("Available root commands (").append(commands.size()).append("):\n");
        } else {
            out.append("Commands matching mod_id `")
                    .append(safe(modIdFilter).trim())
                    .append("` (best-effort filter, ")
                    .append(commands.size())
                    .append("):\n");
        }

        int count = 0;
        for (String command : commands) {
            count++;
            if (count > MAX_COMMAND_RESULTS) {
                out.append("... truncated at ").append(MAX_COMMAND_RESULTS).append(" commands.\n");
                break;
            }
            out.append("- /").append(command).append("\n");
        }

        return new ToolExecutionResult(true, trimToMax(out.toString().trim(), MAX_OUTPUT_CHARS));
    }

    public static ToolExecutionResult fetchModrinth(String modId) {
        String normalizedModId = safe(modId).trim().toLowerCase(Locale.ROOT);
        if (blank(normalizedModId)) {
            return new ToolExecutionResult(false, "Missing required mod_id.");
        }

        Mod installedMod = Platform.getOptionalMod(normalizedModId).orElse(null);
        Map<String, String> contactLinks = readFabricContactLinks(installedMod);
        ModrinthProject project = resolveModrinthProject(normalizedModId, installedMod, contactLinks);
        if (project == null) {
            return new ToolExecutionResult(
                    false,
                    "Mod `" + normalizedModId + "` is not on Modrinth. Use `list_commands` or installed mod ids and retry."
            );
        }

        if (!blank(project.projectUrl())) {
            ToolExecutionResult fetched = fetchUrl(project.projectUrl());
            if (fetched.success()) {
                StringBuilder out = new StringBuilder();
                out.append("mod_id: ").append(normalizedModId).append("\n");
                if (!blank(project.slug())) {
                    out.append("modrinth_slug: ").append(project.slug()).append("\n");
                }
                if (!blank(project.title())) {
                    out.append("modrinth_title: ").append(project.title()).append("\n");
                }
                out.append("\n").append(fetched.output());
                return new ToolExecutionResult(true, trimToMax(out.toString().trim(), MAX_OUTPUT_CHARS));
            }
        }

        String fallback = formatModrinthApiContent(project);
        if (blank(fallback)) {
            return new ToolExecutionResult(false, "No readable Modrinth content available for `" + normalizedModId + "`.");
        }
        return new ToolExecutionResult(true, trimToMax(fallback, MAX_OUTPUT_CHARS));
    }

    public static ToolExecutionResult fetchUrl(String url) {
        String normalizedUrl = safe(url).trim();
        if (blank(normalizedUrl)) {
            return new ToolExecutionResult(false, "Missing required url.");
        }
        if (!looksHttpUrl(normalizedUrl)) {
            return new ToolExecutionResult(false, "Only http:// and https:// URLs are supported.");
        }

        HttpResult response = httpGet(
                normalizedUrl,
                Map.of("Accept", "text/html,application/xhtml+xml,text/markdown,text/plain,application/json;q=0.9,*/*;q=0.8")
        );
        if (!response.success()) {
            if (response.statusCode() > 0) {
                return new ToolExecutionResult(false, "Failed to fetch URL (HTTP " + response.statusCode() + "): " + normalizedUrl);
            }
            if (!blank(response.error())) {
                return new ToolExecutionResult(false, "Failed to fetch URL: " + response.error());
            }
            return new ToolExecutionResult(false, "Failed to fetch URL: " + normalizedUrl);
        }
        if (blank(response.body())) {
            return new ToolExecutionResult(false, "Fetched URL but response body was empty.");
        }

        String contentType = safe(response.contentType()).toLowerCase(Locale.ROOT);
        String mode = "text";
        String title = "";
        String content;

        if (looksLikeHtmlPayload(contentType, response.body())) {
            content = convertHtmlToMarkdown(response.body());
            title = extractHtmlTitle(response.body());
            mode = "html->markdown";
        } else {
            content = normalizeTextBlock(response.body());
            if (contentType.contains("json")) {
                mode = "json";
            } else if (contentType.contains("markdown")) {
                mode = "markdown";
            } else if (!blank(contentType)) {
                mode = contentType;
            }
        }

        if (blank(content)) {
            return new ToolExecutionResult(false, "Fetched URL but no readable text content could be extracted.");
        }
        String formatted = formatDocument(new Doc(response.finalUrl(), title, content, mode), "overview");
        return new ToolExecutionResult(true, trimToMax(formatted.trim(), MAX_OUTPUT_CHARS));
    }

    private static String formatModrinthApiContent(ModrinthProject project) {
        if (project == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        String title = blank(project.title()) ? project.slug() : project.title();
        if (!blank(title)) {
            out.append("# ").append(title.trim()).append("\n\n");
        }
        if (!blank(project.projectUrl())) {
            out.append("source: ").append(project.projectUrl()).append("\n\n");
        }
        if (!blank(project.description())) {
            out.append(project.description().trim()).append("\n\n");
        }
        if (!blank(project.body())) {
            out.append(project.body().trim());
        }
        String normalized = normalizeTextBlock(out.toString());
        if (blank(normalized)) {
            return "";
        }
        return formatDocument(new Doc(project.projectUrl(), title, normalized, "modrinth-api"), "overview");
    }

    private static String formatDocument(Doc document, String query) {
        if (document == null || blank(document.content())) {
            return "No document content available.";
        }

        String normalized = normalizeTextBlock(document.content());
        if (blank(normalized)) {
            return "No document content available.";
        }
        if (normalized.length() > MAX_SOURCE_CHARS) {
            normalized = normalized.substring(0, MAX_SOURCE_CHARS);
        }

        StringBuilder out = new StringBuilder();
        out.append("source: ").append(blank(document.sourceUrl()) ? "unknown" : document.sourceUrl()).append("\n");
        if (!blank(document.title())) {
            out.append("title: ").append(document.title()).append("\n");
        }
        out.append("mode: ").append(blank(document.mode()) ? "document" : document.mode()).append("\n");

        if (normalized.length() <= 3_500) {
            out.append("content:\n").append(normalized);
            return out.toString().trim();
        }

        List<String> chunks = buildChunks(normalized, CHUNK_TARGET_CHARS);
        if (chunks.isEmpty()) {
            out.append("content:\n").append(trimToMax(normalized, MAX_OUTPUT_CHARS));
            return out.toString().trim();
        }

        Set<String> tokens = tokenize(query);
        List<ChunkScore> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int score = scoreText(tokens, chunk);
            if (chunk.length() > 800) {
                score += 4;
            }
            scored.add(new ChunkScore(i, score, chunk));
        }

        scored.sort((a, b) -> {
            int byScore = Integer.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(a.index(), b.index());
        });

        int take = Math.min(CHUNK_TOP_COUNT, scored.size());
        List<ChunkScore> selected = new ArrayList<>(scored.subList(0, take));
        selected.sort(Comparator.comparingInt(ChunkScore::index));

        out.append("content: top ").append(selected.size()).append(" chunks for query `").append(safe(query)).append("`\n");
        for (ChunkScore chunk : selected) {
            out.append("\n[chunk ").append(chunk.index() + 1).append("]\n");
            out.append(chunk.content()).append("\n");
        }
        return out.toString().trim();
    }

    private static List<String> buildChunks(String text, int targetChars) {
        if (blank(text)) {
            return List.of();
        }
        String normalized = normalizeTextBlock(text);
        if (blank(normalized)) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = normalized.split("\\n\\n+");
        StringBuilder current = new StringBuilder();

        for (String rawParagraph : paragraphs) {
            String paragraph = normalizeTextBlock(rawParagraph);
            if (blank(paragraph)) {
                continue;
            }

            if (paragraph.length() > targetChars * 2) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                for (int start = 0; start < paragraph.length(); start += targetChars) {
                    int end = Math.min(paragraph.length(), start + targetChars);
                    chunks.add(paragraph.substring(start, end).trim());
                }
                continue;
            }

            int projected = current.length() + paragraph.length() + 2;
            if (projected > targetChars && current.length() > 0) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(paragraph);
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static ModrinthProject resolveModrinthProject(String modId, Mod installedMod, Map<String, String> contactLinks) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (!blank(modId)) {
            candidates.add(modId);
        }

        if (installedMod != null) {
            addModrinthCandidate(candidates, installedMod.getHomepage().orElse(""));
            addModrinthCandidate(candidates, installedMod.getSources().orElse(""));
            addModrinthCandidate(candidates, installedMod.getIssueTracker().orElse(""));
        }

        if (contactLinks != null) {
            for (String link : contactLinks.values()) {
                addModrinthCandidate(candidates, link);
            }
        }

        for (String candidate : candidates) {
            Optional<ModrinthProject> project = fetchModrinthProject(candidate);
            if (project.isPresent()) {
                return project.get();
            }
        }

        String query = installedMod != null && !blank(installedMod.getName())
                ? installedMod.getName()
                : modId;
        return searchModrinthProject(query, modId).orElse(null);
    }

    private static Optional<ModrinthProject> fetchModrinthProject(String idOrSlug) {
        if (blank(idOrSlug)) {
            return Optional.empty();
        }
        String url = "https://api.modrinth.com/v2/project/" + encodePathSegment(idOrSlug);
        HttpResult response = httpGet(url, Map.of("Accept", "application/json"));
        if (response.statusCode() == 404 || !response.success()) {
            return Optional.empty();
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String slug = stringValue(root, "slug");
            String title = stringValue(root, "title");
            String description = stringValue(root, "description");
            String body = stringValue(root, "body");
            String projectType = stringValue(root, "project_type");
            String webType = blank(projectType) ? "mod" : projectType;
            String projectUrl = blank(slug) ? "" : "https://modrinth.com/" + webType + "/" + slug;
            return Optional.of(new ModrinthProject(slug, title, description, body, projectUrl));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<ModrinthProject> searchModrinthProject(String query, String modId) {
        if (blank(query)) {
            return Optional.empty();
        }
        String url = "https://api.modrinth.com/v2/search?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=10";
        HttpResult response = httpGet(url, Map.of("Accept", "application/json"));
        if (!response.success()) {
            return Optional.empty();
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("hits") || !root.get("hits").isJsonArray()) {
                return Optional.empty();
            }
            JsonArray hits = root.getAsJsonArray("hits");
            JsonObject best = null;
            int bestScore = Integer.MIN_VALUE;
            Set<String> modIdTokens = tokenize(modId);

            for (JsonElement hitElement : hits) {
                if (!hitElement.isJsonObject()) {
                    continue;
                }
                JsonObject hit = hitElement.getAsJsonObject();
                String projectType = stringValue(hit, "project_type");
                String slug = stringValue(hit, "slug");
                String projectId = stringValue(hit, "project_id");
                String title = stringValue(hit, "title");
                String description = stringValue(hit, "description");

                int score = scoreText(modIdTokens, slug + " " + title + " " + description);
                if ("mod".equalsIgnoreCase(projectType)) {
                    score += 40;
                }
                if (!blank(modId) && slug.equalsIgnoreCase(modId)) {
                    score += 320;
                }
                if (!blank(modId) && projectId.equalsIgnoreCase(modId)) {
                    score += 260;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = hit;
                }
            }

            if (best == null) {
                return Optional.empty();
            }
            String slug = stringValue(best, "slug");
            String projectId = stringValue(best, "project_id");
            if (!blank(slug)) {
                Optional<ModrinthProject> bySlug = fetchModrinthProject(slug);
                if (bySlug.isPresent()) {
                    return bySlug;
                }
            }
            if (!blank(projectId)) {
                return fetchModrinthProject(projectId);
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static Map<String, String> readFabricContactLinks(Mod installedMod) {
        if (installedMod == null) {
            return Map.of();
        }
        Optional<Path> path = installedMod.findResource("fabric.mod.json");
        if (path.isEmpty()) {
            return Map.of();
        }

        try {
            String json = Files.readString(path.get(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            LinkedHashMap<String, String> links = new LinkedHashMap<>();

            if (root.has("contact") && root.get("contact").isJsonObject()) {
                JsonObject contact = root.getAsJsonObject("contact");
                for (Map.Entry<String, JsonElement> entry : contact.entrySet()) {
                    if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String value = entry.getValue().getAsString();
                    if (looksHttpUrl(value)) {
                        links.put(entry.getKey().toLowerCase(Locale.ROOT), value.trim());
                    }
                }
            }

            if (root.has("links") && root.get("links").isJsonObject()) {
                JsonObject extra = root.getAsJsonObject("links");
                for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
                    if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    String value = entry.getValue().getAsString();
                    if (looksHttpUrl(value)) {
                        links.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), value.trim());
                    }
                }
            }
            return Map.copyOf(links);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static void addModrinthCandidate(Set<String> candidates, String link) {
        String extracted = extractModrinthSlug(link);
        if (!blank(extracted)) {
            candidates.add(extracted);
        }
    }

    private static String extractModrinthSlug(String text) {
        if (blank(text)) {
            return "";
        }
        Matcher matcher = MODRINTH_LINK_PATTERN.matcher(text);
        if (matcher.find()) {
            return safe(matcher.group(1)).trim();
        }
        return "";
    }

    private static Set<String> buildModAliases(String modId) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String normalized = normalizeForMatch(modId);
        if (!blank(normalized)) {
            aliases.add(normalized);
        }

        Platform.getOptionalMod(modId == null ? "" : modId.trim()).ifPresent(mod -> {
            aliases.add(normalizeForMatch(mod.getModId()));
            for (String token : tokenize(mod.getName())) {
                if (token.length() >= 3) {
                    aliases.add(token);
                }
            }
        });
        aliases.removeIf(ModToolsExecutor::blank);
        return aliases;
    }

    private static boolean matchesCommandFilter(String command, Set<String> aliases) {
        if (blank(command) || aliases == null || aliases.isEmpty()) {
            return false;
        }
        String normalizedCommand = normalizeForMatch(command);
        String lowerRaw = command.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (blank(alias)) {
                continue;
            }
            if (normalizedCommand.equals(alias)
                    || normalizedCommand.startsWith(alias + " ")
                    || lowerRaw.startsWith(alias + ":")
                    || lowerRaw.startsWith(alias + "_")
                    || lowerRaw.startsWith(alias + "-")
                    || lowerRaw.contains(":" + alias)) {
                return true;
            }
            if (alias.length() >= 5 && normalizedCommand.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private static HttpResult httpGet(String url, Map<String, String> extraHeaders) {
        if (blank(url)) {
            return new HttpResult(0, "", "", "", "empty url");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        builder.header(entry.getKey(), entry.getValue());
                    }
                }
            }
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            URI finalUri = response.uri();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return new HttpResult(
                    response.statusCode(),
                    response.body() == null ? "" : response.body(),
                    finalUri == null ? url : finalUri.toString(),
                    contentType,
                    ""
            );
        } catch (Exception exception) {
            return new HttpResult(0, "", url, "", exception.getClass().getSimpleName() + ": " + safe(exception.getMessage()));
        }
    }

    private static String convertHtmlToMarkdown(String html) {
        if (blank(html)) {
            return "";
        }
        try {
            String markdown = HTML_TO_MD.convert(html);
            String normalized = normalizeTextBlock(markdown);
            if (normalized.length() > MAX_SOURCE_CHARS) {
                return normalized.substring(0, MAX_SOURCE_CHARS);
            }
            return normalized;
        } catch (Exception ignored) {
            String text = html.replaceAll("(?is)<(script|style|noscript|svg|canvas)[^>]*>.*?</\\1>", " ");
            text = text.replaceAll("(?i)<br\\s*/?>", "\n");
            text = text.replaceAll("(?i)</(p|div|li|section|article|h[1-6]|tr|pre|code)>", "\n");
            text = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
            return normalizeTextBlock(text);
        }
    }

    private static String extractHtmlTitle(String html) {
        if (blank(html)) {
            return "";
        }
        Matcher matcher = HTML_TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return normalizeTextBlock(HTML_TAG_PATTERN.matcher(safe(matcher.group(1))).replaceAll(" "));
    }

    private static boolean looksLikeHtmlPayload(String contentType, String body) {
        if (!blank(contentType)) {
            String lowerType = contentType.toLowerCase(Locale.ROOT);
            if (lowerType.contains("text/html") || lowerType.contains("application/xhtml+xml")) {
                return true;
            }
        }
        if (blank(body)) {
            return false;
        }
        String probe = body.stripLeading().toLowerCase(Locale.ROOT);
        return probe.startsWith("<!doctype html")
                || probe.startsWith("<html")
                || probe.contains("<body")
                || probe.contains("<head")
                || probe.contains("<article");
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int scoreText(Set<String> tokens, String text) {
        if (tokens == null || tokens.isEmpty() || blank(text)) {
            return 0;
        }
        String normalized = normalizeForMatch(text);
        if (blank(normalized)) {
            return 0;
        }
        int score = 0;
        int matched = 0;
        for (String token : tokens) {
            if (blank(token)) {
                continue;
            }
            if (normalized.equals(token)) {
                score += 60;
                matched++;
                continue;
            }
            if (normalized.contains(token)) {
                score += 20 + token.length();
                matched++;
                if (normalized.startsWith(token)) {
                    score += 10;
                }
            }
        }
        if (matched == tokens.size() && matched > 0) {
            score += 25;
        }
        return score;
    }

    private static Set<String> tokenize(String text) {
        String normalized = normalizeForMatch(text);
        if (blank(normalized)) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalizeForMatch(String text) {
        if (blank(text)) {
            return "";
        }
        String normalized = NON_ALNUM_PATTERN.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        return normalized;
    }

    private static String normalizeTextBlock(String text) {
        if (blank(text)) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        normalized = normalized.replaceAll("[ \\t\\f]+", " ");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private static boolean looksHttpUrl(String value) {
        if (blank(value)) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String stringValue(JsonObject object, String key) {
        if (object == null || blank(key) || !object.has(key) || object.get(key).isJsonNull()) {
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
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars).trim() + "\n... truncated ...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record HttpResult(int statusCode, String body, String finalUrl, String contentType, String error) {
        private boolean success() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private record Doc(String sourceUrl, String title, String content, String mode) {
    }

    private record ModrinthProject(
            String slug,
            String title,
            String description,
            String body,
            String projectUrl
    ) {
    }

    private record ChunkScore(int index, int score, String content) {
    }
}
