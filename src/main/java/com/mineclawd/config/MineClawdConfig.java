package com.mineclawd.config;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

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

    public static final ConfigClassHandler<MineClawdConfig> HANDLER = ConfigClassHandler.createBuilder(MineClawdConfig.class)
            .id(new Identifier("mineclawd", "config"))
            .serializer(config -> GsonConfigSerializerBuilder.create(config)
                    .setPath(FabricLoader.getInstance().getConfigDir().resolve("mineclawd.json5"))
                    .setJson5(true)
                    .build())
            .build();

    @SerialEntry(comment = "LLM provider selection.")
    public LlmProvider provider = LlmProvider.OPENAI;

    @SerialEntry(comment = "OpenAI-compatible API base URL (no trailing slash).")
    public String endpoint = "https://api.openai.com/v1";

    @SerialEntry(comment = "OpenAI API key.")
    public String apiKey = "";

    @SerialEntry(comment = "OpenAI model name (e.g., gpt-4o-mini).")
    public String model = "gpt-4o-mini";

    @SerialEntry(comment = "OpenAI summarize model used to generate session titles.")
    public String summarizeModel = "gpt-4o-mini";

    @SerialEntry(comment = "Vertex AI express mode API base URL (no trailing slash).")
    public String vertexEndpoint = "https://aiplatform.googleapis.com/v1";

    @SerialEntry(comment = "Vertex AI API key (express mode).")
    public String vertexApiKey = "";

    @SerialEntry(comment = "Vertex AI model name or full path (e.g., publishers/google/models/gemini-3-pro-preview).")
    public String vertexModel = "publishers/google/models/gemini-3-pro-preview";

    @SerialEntry(comment = "Vertex AI summarize model used to generate session titles.")
    public String vertexSummarizeModel = "publishers/google/models/gemini-3-pro-preview";

    @SerialEntry(comment = "Enable debug logging for LLM responses and tool calls.")
    public boolean debugMode = false;

    @SerialEntry(comment = "Whether to limit maximum tool call rounds per request.")
    public boolean limitToolCalls = false;

    @SerialEntry(comment = "Maximum tool call rounds per request (1-20).")
    public int toolCallLimit = 16;

    @SerialEntry(comment = "Advanced: custom system prompt. Leave blank to use MineClawd default prompt.")
    public String systemPrompt = "";

    public static MineClawdConfig get() {
        return HANDLER.instance();
    }
}
