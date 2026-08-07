package com.mineclawd.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MineClawdConfig {
    public enum LlmProvider {
        OPENAI("OpenAI"),
        VERTEX_AI("Google Vertex AI");

        private final String displayName;

        LlmProvider(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public enum DynamicRegistryMode {
        AUTO("Auto"),
        ENABLED("Enabled"),
        DISABLED("Disabled");

        private final String displayName;

        DynamicRegistryMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    public static final ConfigHandler HANDLER = new ConfigHandler(
            Platform.getConfigFolder().resolve("mineclawd.json5")
    );

    public LlmProvider provider = LlmProvider.OPENAI;
    public String endpoint = "https://api.openai.com/v1";
    public String apiKey = "";
    public String tavilyApiKey = "";
    public String model = "26.1.2";
    public String summarizeModel = "26.1.2";
    public String vertexEndpoint = "https://aiplatform.googleapis.com/v1";
    public String vertexApiKey = "";
    public String vertexModel = "gemini-3.1-pro-preview";
    public String vertexSummarizeModel = "gemini-3-flash-preview";
    public boolean debugMode = false;
    public boolean limitToolCalls = false;
    public int toolCallLimit = 16;
    public String systemPrompt = "";
    public DynamicRegistryMode dynamicRegistryMode = DynamicRegistryMode.AUTO;
    public boolean enableGui = true;

    public static MineClawdConfig get() {
        return HANDLER.instance();
    }

    public static final class ConfigHandler {
        private static final Gson GSON = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();

        private final Path path;
        private final MineClawdConfig defaults = new MineClawdConfig();
        private MineClawdConfig instance = new MineClawdConfig();

        private ConfigHandler(Path path) {
            this.path = path;
        }

        public synchronized void load() {
            MineClawdConfig loaded = null;
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    loaded = GSON.fromJson(reader, MineClawdConfig.class);
                } catch (IOException ignored) {
                }
            }
            instance = mergeWithDefaults(loaded);
        }

        public synchronized void save() {
            instance.toolCallLimit = Math.max(1, Math.min(20, instance.toolCallLimit));
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    GSON.toJson(instance, writer);
                }
            } catch (IOException ignored) {
            }
        }

        public synchronized MineClawdConfig defaults() {
            return mergeWithDefaults(defaults);
        }

        public synchronized MineClawdConfig instance() {
            return instance;
        }

        private static MineClawdConfig mergeWithDefaults(MineClawdConfig loaded) {
            MineClawdConfig merged = new MineClawdConfig();
            if (loaded == null) {
                return merged;
            }
            if (loaded.provider != null) merged.provider = loaded.provider;
            if (loaded.endpoint != null) merged.endpoint = loaded.endpoint;
            if (loaded.apiKey != null) merged.apiKey = loaded.apiKey;
            if (loaded.tavilyApiKey != null) merged.tavilyApiKey = loaded.tavilyApiKey;
            if (loaded.model != null) merged.model = loaded.model;
            if (loaded.summarizeModel != null) merged.summarizeModel = loaded.summarizeModel;
            if (loaded.vertexEndpoint != null) merged.vertexEndpoint = loaded.vertexEndpoint;
            if (loaded.vertexApiKey != null) merged.vertexApiKey = loaded.vertexApiKey;
            if (loaded.vertexModel != null) merged.vertexModel = loaded.vertexModel;
            if (loaded.vertexSummarizeModel != null) merged.vertexSummarizeModel = loaded.vertexSummarizeModel;
            merged.debugMode = loaded.debugMode;
            merged.limitToolCalls = loaded.limitToolCalls;
            merged.toolCallLimit = Math.max(1, Math.min(20, loaded.toolCallLimit));
            if (loaded.systemPrompt != null) merged.systemPrompt = loaded.systemPrompt;
            if (loaded.dynamicRegistryMode != null) merged.dynamicRegistryMode = loaded.dynamicRegistryMode;
            merged.enableGui = loaded.enableGui;
            return merged;
        }
    }
}
