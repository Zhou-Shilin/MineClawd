package com.mineclawd;

import de.themoep.minedown.adventure.MineDown;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.config.MineClawdConfig;
import com.mineclawd.kubejs.KubeJsScriptManager;
import com.mineclawd.kubejs.KubeJsToolExecutor;
import com.mineclawd.kubejs.KubeJsToolExecutor.ToolExecutionResult;
import com.mineclawd.llm.OpenAIClient;
import com.mineclawd.llm.OpenAIMessage;
import com.mineclawd.llm.OpenAIResponse;
import com.mineclawd.llm.OpenAITool;
import com.mineclawd.llm.OpenAIToolCall;
import com.mineclawd.llm.VertexAIFunction;
import com.mineclawd.llm.VertexAIClient;
import com.mineclawd.llm.VertexAIMessage;
import com.mineclawd.llm.VertexAIResponse;
import com.mineclawd.llm.VertexAIToolCall;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.SharedConstants;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MineClawd implements ModInitializer {
    public static final String MOD_ID = "mineclawd";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final OpenAIClient OPENAI_CLIENT = new OpenAIClient();
    private static final VertexAIClient VERTEX_CLIENT = new VertexAIClient();
    private static final ConcurrentHashMap<String, List<OpenAIMessage>> OPENAI_CONVERSATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, List<VertexAIMessage>> VERTEX_CONVERSATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ACTIVE_REQUESTS = new ConcurrentHashMap<>();

    private static final String TOOL_NAME = "kubejs_eval";
    private static final int TOOL_LIMIT_MIN = 1;
    private static final int TOOL_LIMIT_MAX = 20;
    private static final int MAX_REPEAT_TOOL_CALLS = 1;
    private static final int RATE_LIMIT_RETRIES = 2;
    private static final long RATE_LIMIT_BACKOFF_MS = 1500L;
    private static final GsonComponentSerializer ADVENTURE_GSON = GsonComponentSerializer.gson();
    private static final Pattern MINEDOWN_ACTION_COLON_PATTERN = Pattern.compile(
            "\\((run_command|suggest_command|copy_to_clipboard|change_page|open_url|show_text|hover|insert|show_entity|show_item|custom|show_dialog)\\s*:\\s*",
            Pattern.CASE_INSENSITIVE
    );
    private static final String BASE_SYSTEM_PROMPT = String.join("\n",
        "You are MineClawd, an advanced Minecraft in-game agent specialized in KubeJS 1.20.1 scripting.",

        "You can call the tool `kubejs_eval` to execute KubeJS JavaScript on the server via /_exec_kubejs_internal.",
        "For multi-line code, include newline characters; they will be converted to \\n before execution.",
        "Predefined variables: source, server, level, player (may be null). If server is null, use Utils.getServer().",
        
        "*** STRICT KUBEJS SYNTAX RULES ***",
        "1. EVENT SYSTEMS: You MUST use the specific event objects defined in the current environment. DO NOT use the legacy `onEvent` syntax.",
        "   - CORRECT: `ServerEvents.recipes(event => { ... })`",
        "   - CORRECT: `ServerEvents.tags('item', event => { ... })`",
        "   - CORRECT: `StartupEvents.registry('item', event => { ... })`",
        "   - CORRECT: `PlayerEvents.chat(event => { ... })`",
        "   - CORRECT: `LevelEvents.tick(event => { ... })`",
        "   - WRONG: `onEvent('recipes', event => ...)` (Strictly Forbidden)",
        
        "2. RECIPE TYPES (ServerEvents.recipes):",
        "   - Shaped: `event.shaped('minecraft:diamond', ['AAA', ' B ', ' B '], {A: 'minecraft:dirt', B: 'minecraft:stick'})`",
        "   - Shapeless: `event.shapeless('minecraft:stick', ['minecraft:diamond', '#minecraft:logs'])`",
        "   - Smelting: `event.smelting('minecraft:iron_ingot', 'minecraft:raw_iron')`",
        "   - Removing: `event.remove({output: 'minecraft:stick'})` or `event.remove({id: 'minecraft:chest'})`",
        
        "3. GENERAL UTILITIES:",
        "   - Logging: Use `console.info('Message')` or `Utils.server.tell('Message')`.",
        "   - Items: Use `Item.of('minecraft:diamond', 64)` to create ItemStacks.",
        "   - Java Types: Avoid `Java.loadClass` unless necessary. Use built-in wrappers like `Utils`.",

        "These are examples of the most commonly used codes, not a complete list. Other KubeJS features may be used as needed.",
        
        "*** EXECUTION PROTOCOL ***",
        "1. The first assistant response must explain your immediate plan.",
        "2. Before each tool call, send a short progress update to the player.",
        "3. After receiving tool results, verify the output. If there is a 'ReferenceError' or 'TypeError', it means you used invalid syntax. Analyze the error and retry with standard KubeJS patterns.",
        "4. Use Markdown and MineDown syntax for player messages.",
        "5. When the task is complete, explain the result and stop."
    );

    @Override
    public void onInitialize() {
        MineClawdConfig.HANDLER.load();
        MineClawdConfig.HANDLER.save();

        KubeJsScriptManager.ensureScriptInGameDir();
        MineClawdNetworking.register();
        CommandRegistrationCallback.EVENT.register(this::registerCommands);
        LOGGER.info("MineClawd initialized. /mineclawd is ready for the agent loop.");
    }

    private void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            net.minecraft.command.CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("mineclawd")
                .then(CommandManager.literal("config")
                        .executes(context -> openConfig(context.getSource())))
                .then(CommandManager.literal("new")
                        .executes(context -> resetSession(context.getSource())))
                .then(CommandManager.literal("prompt")
                        .then(CommandManager.argument("request", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String request = StringArgumentType.getString(context, "request");
                                    return handleRequest(context.getSource(), request);
                                }))));

        dispatcher.register(CommandManager.literal("mclawd")
                .then(CommandManager.argument("request", StringArgumentType.greedyString())
                        .executes(context -> {
                            String request = StringArgumentType.getString(context, "request");
                            return handleRequest(context.getSource(), request);
                        })));
    }

    private int openConfig(ServerCommandSource source) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: only players can open the config screen."));
            return 0;
        }
        ServerPlayNetworking.send(player, MineClawdNetworking.OPEN_CONFIG, PacketByteBufs.empty());
        return 1;
    }

    private int resetSession(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String key = conversationKey(source);
        if (ACTIVE_REQUESTS.containsKey(key)) {
            source.sendError(Text.literal("MineClawd: cannot reset session while a request is running."));
            return 0;
        }
        OPENAI_CONVERSATIONS.remove(key);
        VERTEX_CONVERSATIONS.remove(key);
        sendAgentMessage(source, "Session history cleared. Starting fresh.");
        return 1;
    }

    private int handleRequest(ServerCommandSource source, String request) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }

        MineClawdConfig config = MineClawdConfig.get();
        MineClawdConfig.LlmProvider provider = config.provider == null
                ? MineClawdConfig.LlmProvider.OPENAI
                : config.provider;
        if (!validateConfig(source, config, provider)) {
            return 0;
        }

        ensureKubeJsScript(source);

        String conversationKey = conversationKey(source);
        String requestId = UUID.randomUUID().toString();
        if (ACTIVE_REQUESTS.putIfAbsent(conversationKey, requestId) != null) {
            source.sendError(Text.literal("MineClawd: another request is still running for this session."));
            return 0;
        }

        AgentRuntime runtime = new AgentRuntime(resolveToolLimit(config), isToolLimitEnabled(config), config.debugMode, requestId, conversationKey);
        debugLog(runtime, "Request from %s: %s", conversationKey, request);
        debugLog(runtime, "Request id: %s", requestId);
        debugLog(runtime, "Tool call limit enabled: %s", runtime.limitToolCallsEnabled());
        if (runtime.limitToolCallsEnabled()) {
            debugLog(runtime, "Tool call limit: %d", runtime.toolLimit());
        }
        sendPromptEcho(source, request);
        try {
            if (provider == MineClawdConfig.LlmProvider.OPENAI) {
                List<OpenAIMessage> history = OPENAI_CONVERSATIONS.computeIfAbsent(conversationKey, key -> {
                    List<OpenAIMessage> messages = new ArrayList<>();
                    messages.add(OpenAIMessage.system(buildSystemPrompt(config)));
                    return messages;
                });
                history.add(OpenAIMessage.user(request));
                runOpenAiAgent(source, conversationKey, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            } else {
                List<VertexAIMessage> history = VERTEX_CONVERSATIONS.computeIfAbsent(conversationKey, key -> new ArrayList<>());
                if (history.isEmpty()) {
                    history.add(VertexAIMessage.user(buildSystemPrompt(config)));
                }
                history.add(VertexAIMessage.user(request));
                runVertexAgent(source, conversationKey, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            }
        } catch (Exception e) {
            finishActiveRequest(runtime);
            source.sendError(Text.literal("MineClawd: failed to start request: " + e.getMessage()));
            return 0;
        }

        return 1;
    }

    private void sendPromptEcho(ServerCommandSource source, String request) {
        if (source == null || request == null || request.isBlank()) {
            return;
        }
        String speaker = source.getName();
        MutableText line = Text.empty()
                .append(Text.literal("<" + speaker + "> "))
                .append(Text.literal("@MineClawd").formatted(Formatting.BLUE))
                .append(Text.literal(" " + request));

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            player.sendMessage(line, false);
            return;
        }
        source.sendFeedback(() -> line, false);
    }

    private boolean validateConfig(ServerCommandSource source, MineClawdConfig config, MineClawdConfig.LlmProvider provider) {
        if (provider == MineClawdConfig.LlmProvider.OPENAI) {
            if (config.apiKey == null || config.apiKey.isBlank()) {
                source.sendError(Text.literal("MineClawd: OpenAI API Key is missing. Edit config/mineclawd.json5."));
                return false;
            }
            if (config.model == null || config.model.isBlank()) {
                source.sendError(Text.literal("MineClawd: OpenAI model is missing. Edit config/mineclawd.json5."));
                return false;
            }
        } else if (provider == MineClawdConfig.LlmProvider.VERTEX_AI) {
            if (config.vertexApiKey == null || config.vertexApiKey.isBlank()) {
                source.sendError(Text.literal("MineClawd: Vertex AI API Key is missing. Edit config/mineclawd.json5."));
                return false;
            }
            if (config.vertexModel == null || config.vertexModel.isBlank()) {
                source.sendError(Text.literal("MineClawd: Vertex AI model is missing. Edit config/mineclawd.json5."));
                return false;
            }
        } else {
            source.sendError(Text.literal("MineClawd: Unknown LLM provider configured."));
            return false;
        }
        return true;
    }

    private boolean isOp(ServerCommandSource source) {
        return source.hasPermissionLevel(2);
    }

    private void ensureKubeJsScript(ServerCommandSource source) {
        if (source.getServer() == null) {
            return;
        }
        boolean created = KubeJsScriptManager.ensureScript(source.getServer());
        if (created) {
            sendAgentMessage(source, "Generated the internal KubeJS script and reloading `server_scripts`.");
            source.getServer().getCommandManager().executeWithPrefix(source, "/kubejs reload server_scripts");
        }
    }

    private void runOpenAiAgent(
            ServerCommandSource source,
            String conversationKey,
            List<OpenAIMessage> history,
            int depth,
            int retryCount,
            ToolLoopState loopState,
            AgentRuntime runtime
    ) {
        if (runtime.limitToolCallsEnabled() && depth >= runtime.toolLimit()) {
            source.sendError(Text.literal("MineClawd: tool loop limit reached."));
            finishActiveRequest(runtime);
            return;
        }

        MineClawdConfig config = MineClawdConfig.get();
        debugLog(runtime, "OpenAI request round=%d retry=%d", depth + 1, retryCount);
        OPENAI_CLIENT.sendMessage(config.endpoint, config.apiKey, config.model, history, List.of(kubeJsToolOpenAi()))
                .whenComplete((response, error) -> {
                    source.getServer().execute(() -> handleOpenAiStep(source, conversationKey, history, depth, retryCount, loopState, runtime, response, error));
                });
    }

    private void handleOpenAiStep(
            ServerCommandSource source,
            String conversationKey,
            List<OpenAIMessage> history,
            int depth,
            int retryCount,
            ToolLoopState loopState,
            AgentRuntime runtime,
            OpenAIResponse response,
            Throwable error
    ) {
        if (error != null) {
            if (isRateLimitError(error) && retryCount < RATE_LIMIT_RETRIES) {
                scheduleRateLimitRetry(source, runtime, retryCount, () ->
                        runOpenAiAgent(source, conversationKey, history, depth, retryCount + 1, loopState, runtime));
                return;
            }
            source.sendError(Text.literal("MineClawd: LLM error: " + error.getMessage()));
            finishActiveRequest(runtime);
            return;
        }
        if (response == null) {
            source.sendError(Text.literal("MineClawd: LLM returned no response."));
            finishActiveRequest(runtime);
            return;
        }

        String text = response.text();
        debugLog(runtime, "OpenAI response text: %s", text == null ? "(null)" : text);
        debugLog(runtime, "OpenAI round=%d tool_calls=%d", depth + 1, response.toolCalls() == null ? 0 : response.toolCalls().size());
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            for (OpenAIToolCall call : response.toolCalls()) {
                debugLog(runtime, "OpenAI tool call: id=%s name=%s args=%s",
                        call == null ? "(null)" : call.id(),
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.arguments());
            }
        }
        if (text != null && !text.isBlank()) {
            sendAgentMessage(source, text);
        }

        List<OpenAIToolCall> toolCalls = response.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            history.add(OpenAIMessage.assistant(text, toolCalls));
            ToolExecutionBatch batch = executeOpenAiToolCalls(source, toolCalls, runtime);
            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                source.sendError(Text.literal("MineClawd: repeated tool call detected. Stopping."));
                finishActiveRequest(runtime);
                return;
            }
            List<OpenAIMessage> toolMessages = batch.messages();
            history.addAll(toolMessages);
            runOpenAiAgent(source, conversationKey, history, depth + 1, 0, nextState, runtime);
            return;
        }

        history.add(OpenAIMessage.assistant(text, null));
        OPENAI_CONVERSATIONS.put(conversationKey, history);
        sendAgentMessage(source, "Task finished.");
        finishActiveRequest(runtime);
    }

    private void runVertexAgent(
            ServerCommandSource source,
            String conversationKey,
            List<VertexAIMessage> history,
            int depth,
            int retryCount,
            ToolLoopState loopState,
            AgentRuntime runtime
    ) {
        if (runtime.limitToolCallsEnabled() && depth >= runtime.toolLimit()) {
            source.sendError(Text.literal("MineClawd: tool loop limit reached."));
            finishActiveRequest(runtime);
            return;
        }

        MineClawdConfig config = MineClawdConfig.get();
        debugLog(runtime, "Vertex request round=%d retry=%d", depth + 1, retryCount);
        VERTEX_CLIENT.sendMessage(config.vertexEndpoint, config.vertexApiKey, config.vertexModel, history, List.of(kubeJsToolVertex()))
                .whenComplete((response, error) -> {
                    source.getServer().execute(() -> handleVertexStep(source, conversationKey, history, depth, retryCount, loopState, runtime, response, error));
                });
    }

    private void handleVertexStep(
            ServerCommandSource source,
            String conversationKey,
            List<VertexAIMessage> history,
            int depth,
            int retryCount,
            ToolLoopState loopState,
            AgentRuntime runtime,
            VertexAIResponse response,
            Throwable error
    ) {
        if (error != null) {
            if (isRateLimitError(error) && retryCount < RATE_LIMIT_RETRIES) {
                scheduleRateLimitRetry(source, runtime, retryCount, () ->
                        runVertexAgent(source, conversationKey, history, depth, retryCount + 1, loopState, runtime));
                return;
            }
            source.sendError(Text.literal("MineClawd: LLM error: " + error.getMessage()));
            finishActiveRequest(runtime);
            return;
        }
        if (response == null) {
            source.sendError(Text.literal("MineClawd: LLM returned no response."));
            finishActiveRequest(runtime);
            return;
        }

        String text = response.text();
        debugLog(runtime, "Vertex response text: %s", text == null ? "(null)" : text);
        debugLog(runtime, "Vertex round=%d tool_calls=%d", depth + 1, response.toolCalls() == null ? 0 : response.toolCalls().size());
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            for (VertexAIToolCall call : response.toolCalls()) {
                debugLog(runtime, "Vertex tool call: name=%s args=%s",
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.args());
            }
        }
        if (text != null && !text.isBlank()) {
            sendAgentMessage(source, text);
        }

        if (response.modelMessage() != null) {
            history.add(response.modelMessage());
        }

        List<VertexAIToolCall> toolCalls = response.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ToolExecutionBatch batch = executeVertexToolCalls(source, toolCalls, runtime);
            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                source.sendError(Text.literal("MineClawd: repeated tool call detected. Stopping."));
                finishActiveRequest(runtime);
                return;
            }
            List<VertexAIMessage> toolMessages = batch.vertexMessages();
            history.addAll(toolMessages);
            runVertexAgent(source, conversationKey, history, depth + 1, 0, nextState, runtime);
            return;
        }

        VERTEX_CONVERSATIONS.put(conversationKey, history);
        sendAgentMessage(source, "Task finished.");
        finishActiveRequest(runtime);
    }

    private ToolExecutionBatch executeOpenAiToolCalls(ServerCommandSource source, List<OpenAIToolCall> toolCalls, AgentRuntime runtime) {
        List<OpenAIMessage> results = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        int callIndex = 0;
        for (OpenAIToolCall call : toolCalls) {
            if (call == null) {
                continue;
            }
            callIndex++;
            String code = extractCode(call.arguments());
            debugLog(runtime, "Executing tool (OpenAI) #%d name=%s code=%s", callIndex, call.name(), code);
            String output = executeToolCall(source, call.name(), code);
            debugLog(runtime, "Tool output #%d: %s", callIndex, output);
            String toolCallId = call.id();
            if (toolCallId == null || toolCallId.isBlank()) {
                toolCallId = "unknown";
            }
            results.add(OpenAIMessage.tool(toolCallId, output));
            outputs.add(output);
            signatures.add(call.name() + ":" + (code == null ? "" : code));
        }
        return ToolExecutionBatch.openAi(results, String.join("\n", signatures), String.join("\n", outputs));
    }

    private ToolExecutionBatch executeVertexToolCalls(ServerCommandSource source, List<VertexAIToolCall> toolCalls, AgentRuntime runtime) {
        List<VertexAIMessage> results = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        List<String> signatures = new ArrayList<>();
        int callIndex = 0;
        for (VertexAIToolCall call : toolCalls) {
            if (call == null) {
                continue;
            }
            callIndex++;
            String code = extractCode(call.args());
            debugLog(runtime, "Executing tool (Vertex) #%d name=%s code=%s", callIndex, call.name(), code);
            String output = executeToolCall(source, call.name(), code);
            debugLog(runtime, "Tool output #%d: %s", callIndex, output);
            JsonObject response = new JsonObject();
            response.addProperty("result", output);
            response.addProperty("is_error", output.startsWith("ERROR:"));
            results.add(VertexAIMessage.toolResponse(call.name(), response));
            outputs.add(output);
            signatures.add(call.name() + ":" + (code == null ? "" : code));
        }
        return ToolExecutionBatch.vertex(results, String.join("\n", signatures), String.join("\n", outputs));
    }

    private String executeToolCall(ServerCommandSource source, String toolName, String code) {
        if (!TOOL_NAME.equals(toolName)) {
            return "ERROR: Unknown tool " + toolName;
        }
        if (code == null || code.isBlank()) {
            return "ERROR: Tool call is missing required `code`.";
        }
        ToolExecutionResult result = KubeJsToolExecutor.execute(source, code);
        if (result.success()) {
            return result.output();
        }
        return "ERROR: " + result.output();
    }

    private String extractCode(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(arguments).getAsJsonObject();
            if (root.has("code")) {
                return root.get("code").getAsString();
            }
        } catch (Exception ignored) {
        }
        return arguments.trim();
    }

    private String extractCode(JsonObject args) {
        if (args == null) {
            return null;
        }
        if (args.has("code") && args.get("code").isJsonPrimitive()) {
            return args.get("code").getAsString();
        }
        return null;
    }

    private OpenAITool kubeJsToolOpenAi() {
        return new OpenAITool(TOOL_NAME, "Execute KubeJS code via /_exec_kubejs_internal.", createToolParameters());
    }

    private VertexAIFunction kubeJsToolVertex() {
        return new VertexAIFunction(TOOL_NAME, "Execute KubeJS code via /_exec_kubejs_internal.", createToolParameters());
    }

    private JsonObject createToolParameters() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description", "KubeJS JavaScript to eval. Use newlines for multi-line code.");
        properties.add("code", code);
        root.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("code");
        root.add("required", required);
        return root;
    }

    private String conversationKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuidAsString();
        }
        return source.getName();
    }

    private String buildSystemPrompt(MineClawdConfig config) {
        String configured = config == null ? "" : config.systemPrompt;
        String basePrompt = configured == null || configured.isBlank()
                ? BASE_SYSTEM_PROMPT
                : configured.trim();
        String env = buildEnvironmentInfo();
        if (env.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n\nEnvironment:\n" + env;
    }

    private String buildEnvironmentInfo() {
        String mc = "";
        try {
            mc = SharedConstants.getGameVersion().getName();
        } catch (Exception ignored) {
        }
        String loader = modVersion("fabricloader");
        String kubejs = modVersion("kubejs");
        String mineclawd = modVersion(MOD_ID);

        StringBuilder sb = new StringBuilder();
        if (!mc.isBlank()) {
            sb.append("Minecraft ").append(mc).append("\n");
        }
        if (!loader.isBlank()) {
            sb.append("Fabric Loader ").append(loader).append("\n");
        }
        if (!kubejs.isBlank()) {
            sb.append("KubeJS ").append(kubejs).append("\n");
        }
        if (!mineclawd.isBlank()) {
            sb.append("MineClawd ").append(mineclawd).append("\n");
        }
        return sb.toString().trim();
    }

    private String modVersion(String id) {
        return FabricLoader.getInstance()
                .getModContainer(id)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("");
    }

    private int resolveToolLimit(MineClawdConfig config) {
        if (!isToolLimitEnabled(config)) {
            return Integer.MAX_VALUE;
        }
        int limit = config == null ? 16 : config.toolCallLimit;
        if (limit < TOOL_LIMIT_MIN) {
            return TOOL_LIMIT_MIN;
        }
        if (limit > TOOL_LIMIT_MAX) {
            return TOOL_LIMIT_MAX;
        }
        return limit;
    }

    private boolean isToolLimitEnabled(MineClawdConfig config) {
        return config != null && config.limitToolCalls;
    }

    private void sendAgentMessage(ServerCommandSource source, String markdown) {
        if (source == null || markdown == null || markdown.isBlank()) {
            return;
        }
        Text body = renderAgentBody(markdown);
        MutableText line = Text.empty().append(agentPrefix()).append(body);
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            player.sendMessage(line, false);
            return;
        }
        source.sendFeedback(() -> line, false);
    }

    private MutableText agentPrefix() {
        return Text.literal("[MineClawd] ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD);
    }

    private Text renderAgentBody(String markdown) {
        String normalized = normalizeMineDownActions(markdown);
        String input = normalized == null ? "" : normalized.trim();
        if (input.isBlank()) {
            return Text.empty();
        }
        try {
            Component parsed = MineDown.parse(input);
            String json = ADVENTURE_GSON.serialize(parsed);
            Text text = Text.Serializer.fromJson(json);
            if (text != null) {
                return text;
            }
        } catch (Exception exception) {
            LOGGER.debug("MineDown parse fallback: {}", exception.getMessage());
        }
        return Text.literal(input);
    }

    private String normalizeMineDownActions(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        return MINEDOWN_ACTION_COLON_PATTERN.matcher(markdown).replaceAll("($1=");
    }

    private void debugLog(AgentRuntime runtime, String format, Object... args) {
        if (runtime == null || !runtime.debug()) {
            return;
        }
        LOGGER.info("[MineClawd Debug] " + String.format(format, args));
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (message.contains("(429)")
                        || lower.contains("resource exhausted")
                        || lower.contains("too many requests")
                        || lower.contains("rate limit")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private void scheduleRateLimitRetry(ServerCommandSource source, AgentRuntime runtime, int retryCount, Runnable action) {
        long delay = RATE_LIMIT_BACKOFF_MS * (retryCount + 1L);
        debugLog(runtime, "Rate limited. Scheduling retry %d in %d ms.", retryCount + 1, delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            if (source.getServer() == null) {
                finishActiveRequest(runtime);
                return;
            }
            source.getServer().execute(action);
        });
    }

    private void finishActiveRequest(AgentRuntime runtime) {
        if (runtime == null) {
            return;
        }
        ACTIVE_REQUESTS.remove(runtime.conversationKey(), runtime.requestId());
        debugLog(runtime, "Request finished.");
    }

    private record ToolExecutionBatch(
            List<OpenAIMessage> messages,
            List<VertexAIMessage> vertexMessages,
            String signature,
            String output
    ) {
        static ToolExecutionBatch openAi(List<OpenAIMessage> messages, String signature, String output) {
            return new ToolExecutionBatch(messages, List.of(), signature, output);
        }

        static ToolExecutionBatch vertex(List<VertexAIMessage> messages, String signature, String output) {
            return new ToolExecutionBatch(List.of(), messages, signature, output);
        }
    }

    private record ToolLoopState(String lastSignature, String lastOutput, int repeatCount) {
        ToolLoopState next(String signature, String output) {
            if (signature == null || output == null) {
                return new ToolLoopState("", "", 0);
            }
            if (signature.equals(lastSignature) && output.equals(lastOutput)) {
                return new ToolLoopState(signature, output, repeatCount + 1);
            }
            return new ToolLoopState(signature, output, 0);
        }
    }

    private record AgentRuntime(int toolLimit, boolean limitToolCallsEnabled, boolean debug, String requestId, String conversationKey) {
    }
}
