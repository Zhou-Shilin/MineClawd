package com.mineclawd.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.MineClawdClientNetworking;
import com.mineclawd.client.AgentResponseOverlay;
import com.mineclawd.player.PlayerSettingsManager.RequestBroadcastTarget;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumDropdownControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import com.mineclawd.config.ui.MaskedStringController;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MineClawdConfigScreen {
    private static RequestBroadcastTarget cachedBroadcastTarget = RequestBroadcastTarget.SELF;
    private static boolean broadcastTargetSyncedFromServer = false;
    private static MineClawdConfig cachedServerConfig = null;
    private static boolean serverConfigSyncedFromServer = false;

    private MineClawdConfigScreen() {
    }

    public static Screen create(Screen parent) {
        return createInternal(parent, cachedBroadcastTarget.commandValue());
    }

    public static Screen create(Screen parent, String initialBroadcastTarget) {
        syncBroadcastTargetFromServer(initialBroadcastTarget);
        return createInternal(parent, initialBroadcastTarget);
    }

    public static void syncBroadcastTargetFromServer(String value) {
        cachedBroadcastTarget = parseBroadcastTarget(value);
        broadcastTargetSyncedFromServer = true;
    }

    public static void clearBroadcastTargetServerSync() {
        broadcastTargetSyncedFromServer = false;
    }

    public static void syncServerConfigFromServer(String payload) {
        MineClawdConfig parsed = parseServerConfigPayload(payload);
        if (parsed == null) {
            cachedServerConfig = null;
            serverConfigSyncedFromServer = false;
            return;
        }
        cachedServerConfig = parsed;
        serverConfigSyncedFromServer = true;
    }

    public static void clearServerConfigSync() {
        cachedServerConfig = null;
        serverConfigSyncedFromServer = false;
    }

    private static Screen createInternal(Screen parent, String initialBroadcastTarget) {
        MineClawdConfig.HANDLER.load();
        MineClawdConfig config = MineClawdConfig.get();
        MineClawdConfig defaults = MineClawdConfig.HANDLER.defaults();
        boolean serverBackedConfig = serverConfigSyncedFromServer && cachedServerConfig != null;
        MineClawdConfig serverConfig = serverBackedConfig
                ? copyServerConfig(cachedServerConfig)
                : copyServerConfig(config);
        MineClawdConfig initialServerConfig = copyServerConfig(serverConfig);
        AtomicBoolean revealOpenAiKey = new AtomicBoolean(false);
        AtomicBoolean revealVertexKey = new AtomicBoolean(false);
        AtomicBoolean revealTavilyKey = new AtomicBoolean(false);
        AtomicReference<RequestBroadcastTarget> broadcastTarget = new AtomicReference<>(
                parseBroadcastTarget(initialBroadcastTarget)
        );

        Option<MineClawdConfig.LlmProvider> providerOption = Option.<MineClawdConfig.LlmProvider>createBuilder()
                .name(Text.literal("Provider"))
                .description(OptionDescription.of(Text.literal("Select which LLM provider to use.")))
                .binding(defaults.provider, () -> serverConfig.provider, value -> serverConfig.provider = value)
                .controller(option -> EnumDropdownControllerBuilder.create(option)
                        .formatValue(value -> Text.literal(value.displayName())))
                .build();

        Option<String> openAiEndpointOption = Option.<String>createBuilder()
                .name(Text.literal("Endpoint"))
                .description(OptionDescription.of(Text.literal("OpenAI-compatible base URL (e.g., https://api.openai.com/v1).")))
                .binding(defaults.endpoint, () -> serverConfig.endpoint, value -> serverConfig.endpoint = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> openAiKeyOption = Option.<String>createBuilder()
                .name(Text.literal("API Key"))
                .description(OptionDescription.of(Text.literal("Your OpenAI API key.")))
                .binding(defaults.apiKey, () -> serverConfig.apiKey, value -> serverConfig.apiKey = value)
                .customController(option -> new MaskedStringController(option, revealOpenAiKey::get))
                .build();

        Option<Boolean> showOpenAiKeyOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Show API Key"))
                .description(OptionDescription.of(Text.literal("Temporarily reveal the OpenAI API key in this screen.")))
                .binding(false, revealOpenAiKey::get, revealOpenAiKey::set)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<String> tavilyKeyOption = Option.<String>createBuilder()
                .name(Text.literal("Tavily API Key"))
                .description(OptionDescription.of(Text.literal("Optional. Enables the `search` tool when configured.")))
                .binding(defaults.tavilyApiKey, () -> serverConfig.tavilyApiKey, value -> serverConfig.tavilyApiKey = value)
                .customController(option -> new MaskedStringController(option, revealTavilyKey::get))
                .build();

        Option<Boolean> showTavilyKeyOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Show API Key"))
                .description(OptionDescription.of(Text.literal("Temporarily reveal the Tavily API key in this screen.")))
                .binding(false, revealTavilyKey::get, revealTavilyKey::set)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<String> openAiModelOption = Option.<String>createBuilder()
                .name(Text.literal("Model"))
                .description(OptionDescription.of(Text.literal("OpenAI model name to use for chat.")))
                .binding(defaults.model, () -> serverConfig.model, value -> serverConfig.model = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> openAiSummarizeModelOption = Option.<String>createBuilder()
                .name(Text.literal("Summarize Model"))
                .description(OptionDescription.of(Text.literal("OpenAI model used to summarize first-round chat into a session title.")))
                .binding(defaults.summarizeModel, () -> serverConfig.summarizeModel, value -> serverConfig.summarizeModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexEndpointOption = Option.<String>createBuilder()
                .name(Text.literal("Endpoint"))
                .description(OptionDescription.of(Text.literal("Vertex AI base URL (express mode).")))
                .binding(defaults.vertexEndpoint, () -> serverConfig.vertexEndpoint, value -> serverConfig.vertexEndpoint = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexKeyOption = Option.<String>createBuilder()
                .name(Text.literal("API Key"))
                .description(OptionDescription.of(Text.literal("Vertex AI API key (express mode).")))
                .binding(defaults.vertexApiKey, () -> serverConfig.vertexApiKey, value -> serverConfig.vertexApiKey = value)
                .customController(option -> new MaskedStringController(option, revealVertexKey::get))
                .build();

        Option<Boolean> showVertexKeyOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Show API Key"))
                .description(OptionDescription.of(Text.literal("Temporarily reveal the Vertex AI API key in this screen.")))
                .binding(false, revealVertexKey::get, revealVertexKey::set)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<String> vertexModelOption = Option.<String>createBuilder()
                .name(Text.literal("Model"))
                .description(OptionDescription.of(Text.literal("Vertex AI model name or full path (publishers/google/models/...).")))
                .binding(defaults.vertexModel, () -> serverConfig.vertexModel, value -> serverConfig.vertexModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> vertexSummarizeModelOption = Option.<String>createBuilder()
                .name(Text.literal("Summarize Model"))
                .description(OptionDescription.of(Text.literal("Vertex AI model used to summarize first-round chat into a session title.")))
                .binding(defaults.vertexSummarizeModel, () -> serverConfig.vertexSummarizeModel, value -> serverConfig.vertexSummarizeModel = value)
                .controller(StringControllerBuilder::create)
                .build();

        Option<Boolean> debugModeOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Debug Mode"))
                .description(OptionDescription.of(Text.literal("Log detailed LLM responses and tool calls to latest.log.")))
                .binding(defaults.debugMode, () -> serverConfig.debugMode, value -> serverConfig.debugMode = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<Boolean> limitToolCallsOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Limit Tool Calls"))
                .description(OptionDescription.of(Text.literal("Enable a maximum tool call round limit per request.")))
                .binding(defaults.limitToolCalls, () -> serverConfig.limitToolCalls, value -> serverConfig.limitToolCalls = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<Integer> toolCallLimitOption = Option.<Integer>createBuilder()
                .name(Text.literal("Tool Call Limit"))
                .description(OptionDescription.of(Text.literal("Maximum tool call rounds per request (1-20).")))
                .binding(defaults.toolCallLimit, () -> serverConfig.toolCallLimit, value -> serverConfig.toolCallLimit = value)
                .controller(option -> IntegerSliderControllerBuilder.create(option).range(1, 20).step(1))
                .build();

        Option<MineClawdConfig.DynamicRegistryMode> dynamicRegistryModeOption = Option.<MineClawdConfig.DynamicRegistryMode>createBuilder()
                .name(Text.literal("Dynamic Registry Mode"))
                .description(OptionDescription.of(Text.literal("AUTO: enabled in single-player runtime and disabled on dedicated servers. ENABLED on dedicated servers requires clients to install MineClawd.")))
                .binding(defaults.dynamicRegistryMode, () -> serverConfig.dynamicRegistryMode, value -> serverConfig.dynamicRegistryMode = value)
                .controller(option -> EnumDropdownControllerBuilder.create(option)
                        .formatValue(value -> Text.literal(value.displayName())))
                .build();

        Option<Boolean> enableGuiOption = Option.<Boolean>createBuilder()
                .name(Text.literal("Enable GUI"))
                .description(OptionDescription.of(Text.literal("Client-side MineClawd GUI overlays/screens. OFF behaves like a non-modded client for GUI features, but keeps dynamic placeholder item/block/fluid support.")))
                .binding(defaults.enableGui, () -> config.enableGui, value -> config.enableGui = value)
                .controller(BooleanControllerBuilder::create)
                .build();

        Option<RequestBroadcastTarget> broadcastTargetOption = Option.<RequestBroadcastTarget>createBuilder()
                .name(Text.literal("Broadcast Requests To"))
                .description(OptionDescription.of(Text.literal("Who sees your '<Player> @MineClawd ...' line and task start/finish status. Only available while connected to a MineClawd server.")))
                .binding(
                        RequestBroadcastTarget.SELF,
                        broadcastTarget::get,
                        value -> broadcastTarget.set(value == null ? RequestBroadcastTarget.SELF : value)
                )
                .controller(option -> EnumDropdownControllerBuilder.create(option)
                        .formatValue(value -> Text.literal(value.displayName())))
                .build();

        Runnable updateAvailability = () -> {
            boolean openAiSelected = providerOption.pendingValue() == MineClawdConfig.LlmProvider.OPENAI;
            openAiEndpointOption.setAvailable(openAiSelected);
            openAiKeyOption.setAvailable(openAiSelected);
            showOpenAiKeyOption.setAvailable(openAiSelected);
            openAiModelOption.setAvailable(openAiSelected);
            openAiSummarizeModelOption.setAvailable(openAiSelected);

            boolean vertexSelected = providerOption.pendingValue() == MineClawdConfig.LlmProvider.VERTEX_AI;
            vertexEndpointOption.setAvailable(vertexSelected);
            vertexKeyOption.setAvailable(vertexSelected);
            showVertexKeyOption.setAvailable(vertexSelected);
            vertexModelOption.setAvailable(vertexSelected);
            vertexSummarizeModelOption.setAvailable(vertexSelected);

            toolCallLimitOption.setAvailable(limitToolCallsOption.pendingValue());
            broadcastTargetOption.setAvailable(broadcastTargetSyncedFromServer);
        };

        providerOption.addEventListener((option, event) -> updateAvailability.run());
        limitToolCallsOption.addEventListener((option, event) -> updateAvailability.run());
        updateAvailability.run();

        return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("MineClawd"))
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("LLM"))
                        .option(providerOption)
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("OpenAI"))
                                .option(openAiEndpointOption)
                                .option(openAiKeyOption)
                                .option(showOpenAiKeyOption)
                                .option(openAiModelOption)
                                .option(openAiSummarizeModelOption)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Search"))
                                .option(tavilyKeyOption)
                                .option(showTavilyKeyOption)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Google Vertex AI"))
                                .option(vertexEndpointOption)
                                .option(vertexKeyOption)
                                .option(showVertexKeyOption)
                                .option(vertexModelOption)
                                .option(vertexSummarizeModelOption)
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(Text.literal("Misc"))
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Agent"))
                                .option(enableGuiOption)
                                .option(debugModeOption)
                                .option(limitToolCallsOption)
                                .option(toolCallLimitOption)
                                .option(dynamicRegistryModeOption)
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(Text.literal("Chat"))
                                .option(broadcastTargetOption)
                                .build())
                        .build())
                .save(() -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (serverBackedConfig) {
                        sendServerConfigUpdates(client, initialServerConfig, serverConfig);
                        cachedServerConfig = copyServerConfig(serverConfig);
                    } else {
                        applyServerConfigFields(config, serverConfig);
                    }
                    config.enableGui = Boolean.TRUE.equals(enableGuiOption.pendingValue());
                    MineClawdConfig.HANDLER.save();
                    MineClawdClientNetworking.sendClientGuiPreferenceSync();
                    RequestBroadcastTarget selected = broadcastTarget.get() == null
                            ? RequestBroadcastTarget.SELF
                            : broadcastTarget.get();
                    cachedBroadcastTarget = selected;
                    AgentResponseOverlay.onClientGuiPreferenceChanged(client, config.enableGui);
                    if (broadcastTargetSyncedFromServer) {
                        sendChatCommand(client, "mineclawd config broadcast-requests-to " + selected.commandValue());
                    }
                })
                .build()
                .generateScreen(parent);
    }

    private static MineClawdConfig copyServerConfig(MineClawdConfig source) {
        MineClawdConfig copy = new MineClawdConfig();
        if (source == null) {
            return copy;
        }
        applyServerConfigFields(copy, source);
        return copy;
    }

    private static void applyServerConfigFields(MineClawdConfig target, MineClawdConfig source) {
        if (target == null || source == null) {
            return;
        }
        target.provider = source.provider == null ? MineClawdConfig.LlmProvider.OPENAI : source.provider;
        target.endpoint = safe(source.endpoint);
        target.apiKey = safe(source.apiKey);
        target.tavilyApiKey = safe(source.tavilyApiKey);
        target.model = safe(source.model);
        target.summarizeModel = safe(source.summarizeModel);
        target.vertexEndpoint = safe(source.vertexEndpoint);
        target.vertexApiKey = safe(source.vertexApiKey);
        target.vertexModel = safe(source.vertexModel);
        target.vertexSummarizeModel = safe(source.vertexSummarizeModel);
        target.debugMode = source.debugMode;
        target.limitToolCalls = source.limitToolCalls;
        target.toolCallLimit = Math.max(1, Math.min(20, source.toolCallLimit));
        target.systemPrompt = safe(source.systemPrompt);
        target.dynamicRegistryMode = source.dynamicRegistryMode == null
                ? MineClawdConfig.DynamicRegistryMode.AUTO
                : source.dynamicRegistryMode;
    }

    private static MineClawdConfig parseServerConfigPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            MineClawdConfig parsed = new MineClawdConfig();
            parsed.provider = parseProviderValue(readString(root, "provider", "openai"));
            parsed.endpoint = readString(root, "endpoint", parsed.endpoint);
            parsed.apiKey = readString(root, "apiKey", parsed.apiKey);
            parsed.tavilyApiKey = readString(root, "tavilyApiKey", parsed.tavilyApiKey);
            parsed.model = readString(root, "model", parsed.model);
            parsed.summarizeModel = readString(root, "summarizeModel", parsed.summarizeModel);
            parsed.vertexEndpoint = readString(root, "vertexEndpoint", parsed.vertexEndpoint);
            parsed.vertexApiKey = readString(root, "vertexApiKey", parsed.vertexApiKey);
            parsed.vertexModel = readString(root, "vertexModel", parsed.vertexModel);
            parsed.vertexSummarizeModel = readString(root, "vertexSummarizeModel", parsed.vertexSummarizeModel);
            parsed.debugMode = readBoolean(root, "debugMode", parsed.debugMode);
            parsed.limitToolCalls = readBoolean(root, "limitToolCalls", parsed.limitToolCalls);
            parsed.toolCallLimit = Math.max(1, Math.min(20, readInt(root, "toolCallLimit", parsed.toolCallLimit)));
            parsed.systemPrompt = readString(root, "systemPrompt", parsed.systemPrompt);
            parsed.dynamicRegistryMode = parseDynamicRegistryModeValue(
                    readString(root, "dynamicRegistryMode", parsed.dynamicRegistryMode.name().toLowerCase(Locale.ROOT))
            );
            return parsed;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readString(JsonObject root, String key, String fallback) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return safe(root.get(key).getAsString());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject root, String key, int fallback) {
        if (root == null || key == null || !root.has(key) || !root.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return root.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static MineClawdConfig.LlmProvider parseProviderValue(String value) {
        String normalized = safe(value).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("vertex-ai".equals(normalized) || "vertexai".equals(normalized) || "vertex".equals(normalized)) {
            return MineClawdConfig.LlmProvider.VERTEX_AI;
        }
        return MineClawdConfig.LlmProvider.OPENAI;
    }

    private static MineClawdConfig.DynamicRegistryMode parseDynamicRegistryModeValue(String value) {
        String normalized = safe(value).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "enabled" -> MineClawdConfig.DynamicRegistryMode.ENABLED;
            case "disabled" -> MineClawdConfig.DynamicRegistryMode.DISABLED;
            default -> MineClawdConfig.DynamicRegistryMode.AUTO;
        };
    }

    private static void sendServerConfigUpdates(MinecraftClient client, MineClawdConfig previous, MineClawdConfig current) {
        if (client == null || previous == null || current == null) {
            return;
        }
        if (previous.provider != current.provider) {
            sendServerConfigCommand(client, "provider", providerToCommandValue(current.provider));
        }
        if (!safe(previous.endpoint).equals(safe(current.endpoint))) {
            sendServerConfigCommand(client, "endpoint", safe(current.endpoint));
        }
        if (!safe(previous.apiKey).equals(safe(current.apiKey))) {
            sendServerConfigCommand(client, "api-key", safe(current.apiKey));
        }
        if (!safe(previous.tavilyApiKey).equals(safe(current.tavilyApiKey))) {
            sendServerConfigCommand(client, "tavily-api-key", safe(current.tavilyApiKey));
        }
        if (!safe(previous.model).equals(safe(current.model))) {
            sendServerConfigCommand(client, "model", safe(current.model));
        }
        if (!safe(previous.summarizeModel).equals(safe(current.summarizeModel))) {
            sendServerConfigCommand(client, "summarize-model", safe(current.summarizeModel));
        }
        if (!safe(previous.vertexEndpoint).equals(safe(current.vertexEndpoint))) {
            sendServerConfigCommand(client, "vertex-endpoint", safe(current.vertexEndpoint));
        }
        if (!safe(previous.vertexApiKey).equals(safe(current.vertexApiKey))) {
            sendServerConfigCommand(client, "vertex-api-key", safe(current.vertexApiKey));
        }
        if (!safe(previous.vertexModel).equals(safe(current.vertexModel))) {
            sendServerConfigCommand(client, "vertex-model", safe(current.vertexModel));
        }
        if (!safe(previous.vertexSummarizeModel).equals(safe(current.vertexSummarizeModel))) {
            sendServerConfigCommand(client, "vertex-summarize-model", safe(current.vertexSummarizeModel));
        }
        if (previous.debugMode != current.debugMode) {
            sendServerConfigCommand(client, "debug-mode", Boolean.toString(current.debugMode));
        }
        if (previous.limitToolCalls != current.limitToolCalls) {
            sendServerConfigCommand(client, "limit-tool-calls", Boolean.toString(current.limitToolCalls));
        }
        if (previous.toolCallLimit != current.toolCallLimit) {
            sendServerConfigCommand(client, "tool-call-limit", Integer.toString(Math.max(1, Math.min(20, current.toolCallLimit))));
        }
        if (!safe(previous.systemPrompt).equals(safe(current.systemPrompt))) {
            sendServerConfigCommand(client, "system-prompt", current.systemPrompt == null || current.systemPrompt.isBlank()
                    ? "default"
                    : safe(current.systemPrompt));
        }
        if (previous.dynamicRegistryMode != current.dynamicRegistryMode) {
            sendServerConfigCommand(client, "dynamic-registry-mode", dynamicRegistryModeToCommandValue(current.dynamicRegistryMode));
        }
    }

    private static String providerToCommandValue(MineClawdConfig.LlmProvider provider) {
        return provider == MineClawdConfig.LlmProvider.VERTEX_AI ? "vertex-ai" : "openai";
    }

    private static String dynamicRegistryModeToCommandValue(MineClawdConfig.DynamicRegistryMode mode) {
        if (mode == null) {
            return "auto";
        }
        return switch (mode) {
            case ENABLED -> "enabled";
            case DISABLED -> "disabled";
            default -> "auto";
        };
    }

    private static void sendServerConfigCommand(MinecraftClient client, String key, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        String normalizedValue = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        if (normalizedValue.isBlank()) {
            normalizedValue = "\"\"";
        }
        sendChatCommand(client, "mineclawd config " + key + " " + normalizedValue);
    }

    private static void sendChatCommand(MinecraftClient client, String command) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }
        if (command == null || command.isBlank()) {
            return;
        }
        client.player.networkHandler.sendChatCommand(command.trim());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static RequestBroadcastTarget parseBroadcastTarget(String value) {
        if (value == null || value.isBlank()) {
            return cachedBroadcastTarget;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        RequestBroadcastTarget parsed = RequestBroadcastTarget.fromUserInput(normalized);
        return parsed == null ? cachedBroadcastTarget : parsed;
    }
}
