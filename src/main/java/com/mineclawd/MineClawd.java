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
import com.mineclawd.persona.PersonaManager;
import com.mineclawd.persona.PersonaManager.Persona;
import com.mineclawd.session.SessionManager;
import com.mineclawd.session.SessionManager.SessionData;
import com.mineclawd.session.SessionManager.SessionSummary;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.SharedConstants;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final SessionManager SESSION_MANAGER = new SessionManager();
    private static final PersonaManager PERSONA_MANAGER = new PersonaManager();
    private static final ConcurrentHashMap<String, String> ACTIVE_REQUESTS = new ConcurrentHashMap<>();

    private static final String TOOL_APPLY_INSTANT_SERVER_SCRIPT = "apply-instant-server-script";
    private static final String TOOL_EXECUTE_COMMAND = "execute-command";
    private static final String TOOL_LIST_SERVER_SCRIPTS = "list-server-scripts";
    private static final String TOOL_READ_SERVER_SCRIPT = "read-server-script";
    private static final String TOOL_WRITE_SERVER_SCRIPT = "write-server-script";
    private static final String TOOL_DELETE_SERVER_SCRIPT = "delete-server-script";
    private static final String TOOL_RELOAD_GAME = "reload-game";
    private static final String LEGACY_TOOL_KUBEJS_EVAL = "kubejs_eval";
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
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String SUMMARY_SYSTEM_PROMPT = String.join("\n",
            "You generate titles for Minecraft agent sessions.",
            "Return exactly one concise sentence as plain text.",
            "Example: register-diamon-command",
            "Keep it under 80 characters."
    );
    private static final String BASE_SYSTEM_PROMPT = String.join("\n",
        "You are MineClawd, an advanced Minecraft in-game agent specialized in KubeJS 1.20.1 scripting.",

        "Tool overview:",
        "- `execute-command`: Execute a normal Minecraft command and get command output.",
        "  Prefer this when vanilla commands can solve the task directly (for example: `gamerule`, `time`, `weather`, `tp`, `effect`, `give`, `clear`, `kill`, `summon`, `setblock`, `fill`, `say`, simple checks).",
        "  If command output is enough, do not use KubeJS.",
        "- `apply-instant-server-script`: Execute immediate KubeJS JavaScript on the running server via /_exec_kubejs_internal.",
        "  Use this for instant actions such as checking or editing player inventory, changing nearby blocks, querying entities, and all the one-off server operations. (This is the most commonly used tool.)",
        "Below are tools for managing persistent KubeJS scripts under `kubejs/server_scripts/mineclawd/`. Changes to these scripts persist across reloads and can be used for ongoing behaviors like custom commands, event listeners, and world tick logic.",
        "- `list-server-scripts`: List files under `kubejs/server_scripts/mineclawd/`.",
        "- `read-server-script`: Read a file under `kubejs/server_scripts/mineclawd/`.",
        "- `write-server-script`: Create or overwrite a file under `kubejs/server_scripts/mineclawd/`.",
        "- `delete-server-script`: Delete a file under `kubejs/server_scripts/mineclawd/`.",
        "- `reload-game`: Run `/reload` to apply persistent script changes and return any detected KubeJS loading errors.",

        "Persistent-script workflow:",
        "1. Use file tools to edit scripts under `kubejs/server_scripts/mineclawd/`.",
        "2. Run `reload-game` after changes.",
        "3. If reload reports errors, fix scripts and reload again until clean.",

        "Use persistent scripts for tasks like: registering commands, modifying recipes, listening to player behavior, modifying entity drops, and world tick logic.",
        "Do NOT claim you can register new items, blocks, fluids, or other startup content in this session.",
        "Those require `startup_scripts` plus a full game restart to take effect. If asked, refuse and explain this limit clearly.",

        "For multi-line code in `apply-instant-server-script`, include normal newline characters; they will be converted to \\n before execution.",
        "When implementing persistent custom commands or handlers, include robust error handling: validate args, guard nulls, and use try/catch with clear error context.",
        "Reload can catch load-time syntax errors, but not all runtime command-path errors. After registering or changing commands, run smoke tests via `execute-command` and fix failures immediately.",
        "Predefined variables: source, server, level, player (may be null). If server is null, use Utils.getServer().",
        "Instant server scripts don't need reloading.",
        
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
        
        "Project identity is always MineClawd. Persona files may define another character name or voice. Follow the persona style, but keep project/tool identity and command names unchanged.",

        "*** EXECUTION PROTOCOL ***",
        "1. The first assistant response must explain your immediate plan.",
        "2. Before each tool call, send a short progress update to the player.",
        "3. After receiving tool results, verify the output. If there is an error, analyze it, fix it, and retry.",
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
                .then(CommandManager.literal("sessions")
                        .executes(context -> listSessions(context.getSource()))
                        .then(CommandManager.literal("new")
                                .executes(context -> createNewSession(context.getSource())))
                        .then(CommandManager.literal("list")
                                .executes(context -> listSessions(context.getSource())))
                        .then(CommandManager.literal("resume")
                                .then(CommandManager.argument("session", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestSessionReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String sessionRef = StringArgumentType.getString(context, "session");
                                            return resumeSession(context.getSource(), sessionRef);
                                        })))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("session", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestSessionReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String sessionRef = StringArgumentType.getString(context, "session");
                                            return removeSession(context.getSource(), sessionRef);
                                        }))))
                .then(CommandManager.literal("new")
                        .executes(context -> createNewSession(context.getSource())))
                .then(CommandManager.literal("persona")
                        .executes(context -> showActivePersona(context.getSource()))
                        .then(CommandManager.argument("soul", StringArgumentType.word())
                                .suggests((context, builder) -> suggestSoulName(context.getSource(), builder))
                                .executes(context -> {
                                    String soul = StringArgumentType.getString(context, "soul");
                                    return switchPersona(context.getSource(), soul);
                                })))
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

    private int createNewSession(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: cannot create a new session while a request is running."));
            return 0;
        }

        SessionData session;
        try {
            session = SESSION_MANAGER.createNewSession(ownerKey);
        } catch (Exception exception) {
            source.sendError(Text.literal("MineClawd: failed to create session: " + exception.getMessage()));
            return 0;
        }
        sendAgentMessage(source, "Started new session `" + session.commandToken() + "`.");
        return 1;
    }

    private int listSessions(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        List<SessionSummary> sessions = SESSION_MANAGER.listSessions(sessionOwnerKey(source));
        if (sessions.isEmpty()) {
            sendAgentMessage(source, "No sessions yet. Use `/mineclawd sessions new` to create one.");
            return 1;
        }

        sendAgentMessage(source, "Sessions (`" + sessions.size() + "` total):");
        for (SessionSummary session : sessions) {
            String active = session.active() ? "**ACTIVE** " : "";
            String updatedAt = SESSION_TIME_FORMAT.format(Instant.ofEpochMilli(session.updatedAtEpochMilli()));
            sendAgentMessage(
                    source,
                    active + "`" + session.id() + "` - " + session.title() + " _(updated " + updatedAt + ")_"
                            + " _token: `" + session.token() + "`_"
            );
        }
        return 1;
    }

    private CompletableFuture<Suggestions> suggestSessionReference(ServerCommandSource source, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        List<SessionSummary> sessions = SESSION_MANAGER.listSessions(sessionOwnerKey(source));
        if (sessions.isEmpty()) {
            return Suggestions.empty();
        }
        List<String> candidates = new ArrayList<>(sessions.size() * 2);
        for (SessionSummary session : sessions) {
            candidates.add(session.id());
            candidates.add(session.token());
        }
        return CommandSource.suggestMatching(candidates, builder);
    }

    private int resumeSession(ServerCommandSource source, String sessionRef) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: cannot switch sessions while a request is running."));
            return 0;
        }

        SessionData session = SESSION_MANAGER.resumeSession(ownerKey, sessionRef);
        if (session == null) {
            source.sendError(Text.literal("MineClawd: session not found. Use /mineclawd sessions list."));
            return 0;
        }
        sendAgentMessage(source, "Resumed session `" + session.commandToken() + "`.");
        return 1;
    }

    private int removeSession(ServerCommandSource source, String sessionRef) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: cannot remove sessions while a request is running."));
            return 0;
        }

        SessionData target = SESSION_MANAGER.resolve(ownerKey, sessionRef);
        if (target == null) {
            source.sendError(Text.literal("MineClawd: session not found. Use /mineclawd sessions list."));
            return 0;
        }
        if (!SESSION_MANAGER.removeSession(ownerKey, sessionRef)) {
            source.sendError(Text.literal("MineClawd: failed to remove session."));
            return 0;
        }

        SessionData active = SESSION_MANAGER.loadActiveSession(ownerKey);
        if (active == null) {
            sendAgentMessage(source, "Removed session `" + target.commandToken() + "`. No active session remains.");
        } else {
            sendAgentMessage(source, "Removed session `" + target.commandToken() + "`. Active session is now `" + active.commandToken() + "`.");
        }
        return 1;
    }

    private int showActivePersona(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        Persona persona = PERSONA_MANAGER.loadActivePersona(ownerKey);
        List<String> souls = PERSONA_MANAGER.listSoulNames();
        sendAgentMessage(source, "Active persona: `" + persona.name() + "`");
        if (!souls.isEmpty()) {
            sendAgentMessage(source, "Available personas: `" + String.join("`, `", souls) + "`");
        }
        return 1;
    }

    private CompletableFuture<Suggestions> suggestSoulName(ServerCommandSource source, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        return CommandSource.suggestMatching(PERSONA_MANAGER.listSoulNames(), builder);
    }

    private int switchPersona(ServerCommandSource source, String soulReference) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: cannot switch persona while a request is running."));
            return 0;
        }
        String resolved = PERSONA_MANAGER.resolveSoulName(soulReference);
        if (resolved == null) {
            source.sendError(Text.literal("MineClawd: persona not found. Use /mineclawd persona to list available personas."));
            return 0;
        }
        if (!PERSONA_MANAGER.setActiveSoul(ownerKey, resolved)) {
            source.sendError(Text.literal("MineClawd: failed to switch persona."));
            return 0;
        }
        sendAgentMessage(source, "Switched persona to `" + resolved + "`.");
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

        String ownerKey = sessionOwnerKey(source);
        String requestId = UUID.randomUUID().toString();
        if (ACTIVE_REQUESTS.putIfAbsent(ownerKey, requestId) != null) {
            source.sendError(Text.literal("MineClawd: another request is still running for this session."));
            return 0;
        }

        SessionData session;
        try {
            session = SESSION_MANAGER.loadOrCreateActiveSession(ownerKey);
        } catch (Exception exception) {
            ACTIVE_REQUESTS.remove(ownerKey, requestId);
            source.sendError(Text.literal("MineClawd: failed to load session: " + exception.getMessage()));
            return 0;
        }

        AgentRuntime runtime = new AgentRuntime(
                resolveToolLimit(config),
                isToolLimitEnabled(config),
                config.debugMode,
                requestId,
                ownerKey,
                session.id(),
                request
        );
        debugLog(runtime, "Request from %s session=%s: %s", ownerKey, session.id(), request);
        debugLog(runtime, "Request id: %s", requestId);
        debugLog(runtime, "Tool call limit enabled: %s", runtime.limitToolCallsEnabled());
        if (runtime.limitToolCallsEnabled()) {
            debugLog(runtime, "Tool call limit: %d", runtime.toolLimit());
        }
        sendPromptEcho(source, request);
        try {
            String systemPrompt = buildSystemPrompt(config, ownerKey);
            if (provider == MineClawdConfig.LlmProvider.OPENAI) {
                List<OpenAIMessage> history = session.openAiHistory();
                ensureOpenAiHistory(history, systemPrompt);
                history.add(OpenAIMessage.user(request));
                session.touch();
                SESSION_MANAGER.saveSession(ownerKey, session);
                runOpenAiAgent(source, session, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            } else {
                List<VertexAIMessage> history = session.vertexHistory();
                ensureVertexHistory(history, systemPrompt);
                history.add(VertexAIMessage.user(request));
                session.touch();
                SESSION_MANAGER.saveSession(ownerKey, session);
                runVertexAgent(source, session, history, 0, 0, new ToolLoopState("", "", 0), runtime);
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
            SessionData session,
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
        debugLog(runtime, "OpenAI request round=%d retry=%d session=%s", depth + 1, retryCount, runtime.sessionId());
        OPENAI_CLIENT.sendMessage(config.endpoint, config.apiKey, config.model, history, openAiTools())
                .whenComplete((response, error) -> {
                    source.getServer().execute(() -> handleOpenAiStep(source, session, history, depth, retryCount, loopState, runtime, response, error));
                });
    }

    private void handleOpenAiStep(
            ServerCommandSource source,
            SessionData session,
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
                        runOpenAiAgent(source, session, history, depth, retryCount + 1, loopState, runtime));
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
            session.touch();
            SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
            runOpenAiAgent(source, session, history, depth + 1, 0, nextState, runtime);
            return;
        }

        history.add(OpenAIMessage.assistant(text, null));
        session.touch();
        SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        maybeGenerateSessionTitle(source, MineClawdConfig.get(), MineClawdConfig.LlmProvider.OPENAI, session, text, runtime);
        sendAgentMessage(source, "Task finished.");
        finishActiveRequest(runtime);
    }

    private void runVertexAgent(
            ServerCommandSource source,
            SessionData session,
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
        debugLog(runtime, "Vertex request round=%d retry=%d session=%s", depth + 1, retryCount, runtime.sessionId());
        VERTEX_CLIENT.sendMessage(config.vertexEndpoint, config.vertexApiKey, config.vertexModel, history, vertexTools())
                .whenComplete((response, error) -> {
                    source.getServer().execute(() -> handleVertexStep(source, session, history, depth, retryCount, loopState, runtime, response, error));
                });
    }

    private void handleVertexStep(
            ServerCommandSource source,
            SessionData session,
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
                        runVertexAgent(source, session, history, depth, retryCount + 1, loopState, runtime));
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
            session.touch();
            SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
            runVertexAgent(source, session, history, depth + 1, 0, nextState, runtime);
            return;
        }

        session.touch();
        SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        maybeGenerateSessionTitle(source, MineClawdConfig.get(), MineClawdConfig.LlmProvider.VERTEX_AI, session, text, runtime);
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
            JsonObject args = parseToolArguments(call.arguments());
            debugLog(runtime, "Executing tool (OpenAI) #%d name=%s args=%s", callIndex, call.name(), args);
            String output = executeToolCall(source, call.name(), args);
            debugLog(runtime, "Tool output #%d: %s", callIndex, output);
            String toolCallId = call.id();
            if (toolCallId == null || toolCallId.isBlank()) {
                toolCallId = "unknown";
            }
            results.add(OpenAIMessage.tool(toolCallId, output));
            outputs.add(output);
            signatures.add(call.name() + ":" + args);
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
            JsonObject args = call.args() == null ? new JsonObject() : call.args();
            debugLog(runtime, "Executing tool (Vertex) #%d name=%s args=%s", callIndex, call.name(), args);
            String output = executeToolCall(source, call.name(), args);
            debugLog(runtime, "Tool output #%d: %s", callIndex, output);
            JsonObject response = new JsonObject();
            response.addProperty("result", output);
            response.addProperty("is_error", output.startsWith("ERROR:"));
            results.add(VertexAIMessage.toolResponse(call.name(), response));
            outputs.add(output);
            signatures.add(call.name() + ":" + args);
        }
        return ToolExecutionBatch.vertex(results, String.join("\n", signatures), String.join("\n", outputs));
    }

    private String executeToolCall(ServerCommandSource source, String toolName, JsonObject args) {
        if (toolName == null || toolName.isBlank()) {
            return "ERROR: Tool call is missing required `name`.";
        }
        ToolExecutionResult result;
        switch (toolName) {
            case TOOL_APPLY_INSTANT_SERVER_SCRIPT:
            case LEGACY_TOOL_KUBEJS_EVAL:
                String code = readRequiredStringArg(args, "code");
                if (code == null || code.isBlank()) {
                    return "ERROR: Tool call is missing required string `code`.";
                }
                result = KubeJsToolExecutor.executeInstant(source, code);
                break;
            case TOOL_EXECUTE_COMMAND:
                String command = readRequiredStringArg(args, "command");
                if (command == null || command.isBlank()) {
                    return "ERROR: Tool call is missing required string `command`.";
                }
                result = KubeJsToolExecutor.executeCommand(source, command);
                break;
            case TOOL_LIST_SERVER_SCRIPTS:
                result = KubeJsToolExecutor.listServerScripts(source);
                break;
            case TOOL_READ_SERVER_SCRIPT:
                String readPath = readRequiredStringArg(args, "path");
                if (readPath == null || readPath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                result = KubeJsToolExecutor.readServerScript(source, readPath);
                break;
            case TOOL_WRITE_SERVER_SCRIPT:
                String writePath = readRequiredStringArg(args, "path");
                if (writePath == null || writePath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                String content = readRequiredStringArg(args, "content");
                if (content == null) {
                    return "ERROR: Tool call is missing required string `content`.";
                }
                result = KubeJsToolExecutor.writeServerScript(source, writePath, content);
                break;
            case TOOL_DELETE_SERVER_SCRIPT:
                String deletePath = readRequiredStringArg(args, "path");
                if (deletePath == null || deletePath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                result = KubeJsToolExecutor.deleteServerScript(source, deletePath);
                break;
            case TOOL_RELOAD_GAME:
                result = KubeJsToolExecutor.reloadGame(source);
                break;
            default:
                return "ERROR: Unknown tool " + toolName;
        }

        if (result.success()) {
            return result.output();
        }
        return "ERROR: " + result.output();
    }

    private JsonObject parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(arguments).getAsJsonObject();
        } catch (Exception ignored) {
        }
        JsonObject fallback = new JsonObject();
        String text = arguments.trim();
        fallback.addProperty("code", text);
        fallback.addProperty("command", text);
        return fallback;
    }

    private String readRequiredStringArg(JsonObject args, String key) {
        if (args == null || key == null || key.isBlank()) {
            return null;
        }
        if (!args.has(key) || args.get(key) == null || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<OpenAITool> openAiTools() {
        return List.of(
                new OpenAITool(
                        TOOL_APPLY_INSTANT_SERVER_SCRIPT,
                        "Apply immediate runtime changes by executing KubeJS JavaScript via /_exec_kubejs_internal.",
                        codeToolParameters("JavaScript code to execute immediately. Supports multi-line strings.")
                ),
                new OpenAITool(
                        TOOL_EXECUTE_COMMAND,
                        "Execute a Minecraft command and return command output/result.",
                        commandToolParameters()
                ),
                new OpenAITool(
                        TOOL_LIST_SERVER_SCRIPTS,
                        "List files inside kubejs/server_scripts/mineclawd/.",
                        noArgToolParameters()
                ),
                new OpenAITool(
                        TOOL_READ_SERVER_SCRIPT,
                        "Read a script file from kubejs/server_scripts/mineclawd/.",
                        pathToolParameters("Relative file path under kubejs/server_scripts/mineclawd/, e.g. recipes/ores.js")
                ),
                new OpenAITool(
                        TOOL_WRITE_SERVER_SCRIPT,
                        "Write or overwrite a script file inside kubejs/server_scripts/mineclawd/.",
                        writeToolParameters()
                ),
                new OpenAITool(
                        TOOL_DELETE_SERVER_SCRIPT,
                        "Delete a script file from kubejs/server_scripts/mineclawd/.",
                        pathToolParameters("Relative file path under kubejs/server_scripts/mineclawd/, e.g. recipes/ores.js")
                ),
                new OpenAITool(
                        TOOL_RELOAD_GAME,
                        "Run /reload and return command output plus detected KubeJS load errors.",
                        noArgToolParameters()
                )
        );
    }

    private List<VertexAIFunction> vertexTools() {
        return List.of(
                new VertexAIFunction(
                        TOOL_APPLY_INSTANT_SERVER_SCRIPT,
                        "Apply immediate runtime changes by executing KubeJS JavaScript via /_exec_kubejs_internal.",
                        codeToolParameters("JavaScript code to execute immediately. Supports multi-line strings.")
                ),
                new VertexAIFunction(
                        TOOL_EXECUTE_COMMAND,
                        "Execute a Minecraft command and return command output/result.",
                        commandToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_LIST_SERVER_SCRIPTS,
                        "List files inside kubejs/server_scripts/mineclawd/.",
                        noArgToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_READ_SERVER_SCRIPT,
                        "Read a script file from kubejs/server_scripts/mineclawd/.",
                        pathToolParameters("Relative file path under kubejs/server_scripts/mineclawd/, e.g. recipes/ores.js")
                ),
                new VertexAIFunction(
                        TOOL_WRITE_SERVER_SCRIPT,
                        "Write or overwrite a script file inside kubejs/server_scripts/mineclawd/.",
                        writeToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_DELETE_SERVER_SCRIPT,
                        "Delete a script file from kubejs/server_scripts/mineclawd/.",
                        pathToolParameters("Relative file path under kubejs/server_scripts/mineclawd/, e.g. recipes/ores.js")
                ),
                new VertexAIFunction(
                        TOOL_RELOAD_GAME,
                        "Run /reload and return command output plus detected KubeJS load errors.",
                        noArgToolParameters()
                )
        );
    }

    private JsonObject noArgToolParameters() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", new JsonObject());
        return root;
    }

    private JsonObject codeToolParameters(String description) {
        JsonObject properties = new JsonObject();
        JsonObject code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description", description);
        properties.add("code", code);
        return objectToolParameters(properties, "code");
    }

    private JsonObject pathToolParameters(String description) {
        JsonObject properties = new JsonObject();
        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", description);
        properties.add("path", path);
        return objectToolParameters(properties, "path");
    }

    private JsonObject commandToolParameters() {
        JsonObject properties = new JsonObject();
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description", "Minecraft command to run, with or without leading slash.");
        properties.add("command", command);
        return objectToolParameters(properties, "command");
    }

    private JsonObject writeToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Relative file path under kubejs/server_scripts/mineclawd/, e.g. logic/events.js");
        properties.add("path", path);

        JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        content.addProperty("description", "Full UTF-8 file content to write.");
        properties.add("content", content);

        return objectToolParameters(properties, "path", "content");
    }

    private JsonObject objectToolParameters(JsonObject properties, String... requiredKeys) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", properties == null ? new JsonObject() : properties);
        if (requiredKeys != null && requiredKeys.length > 0) {
            JsonArray required = new JsonArray();
            for (String key : requiredKeys) {
                if (key != null && !key.isBlank()) {
                    required.add(key);
                }
            }
            root.add("required", required);
        }
        return root;
    }

    private void ensureOpenAiHistory(List<OpenAIMessage> history, String systemPrompt) {
        if (history.isEmpty()) {
            history.add(OpenAIMessage.system(systemPrompt));
            return;
        }
        OpenAIMessage first = history.get(0);
        if (first == null || !"system".equals(first.role())) {
            history.add(0, OpenAIMessage.system(systemPrompt));
            return;
        }
        if (first.content() == null || !first.content().equals(systemPrompt)) {
            history.set(0, OpenAIMessage.system(systemPrompt));
        }
    }

    private void ensureVertexHistory(List<VertexAIMessage> history, String systemPrompt) {
        if (history.isEmpty()) {
            history.add(VertexAIMessage.user(systemPrompt));
            return;
        }
        VertexAIMessage first = history.get(0);
        if (first == null || !"user".equals(first.role()) || first.parts() == null || first.parts().isEmpty()) {
            history.add(0, VertexAIMessage.user(systemPrompt));
            return;
        }
        JsonObject firstPart = first.parts().get(0);
        if (firstPart == null || !firstPart.has("text")) {
            history.add(0, VertexAIMessage.user(systemPrompt));
            return;
        }
        String existing = firstPart.get("text").getAsString();
        if (!systemPrompt.equals(existing)) {
            history.set(0, VertexAIMessage.user(systemPrompt));
        }
    }

    private String sessionOwnerKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuidAsString();
        }
        return source.getName();
    }

    private String buildSystemPrompt(MineClawdConfig config, String ownerKey) {
        String configured = config == null ? "" : config.systemPrompt;
        String basePrompt = configured == null || configured.isBlank()
                ? BASE_SYSTEM_PROMPT
                : configured.trim();
        Persona persona = PERSONA_MANAGER.loadActivePersona(ownerKey);
        String env = buildEnvironmentInfo();
        StringBuilder prompt = new StringBuilder(basePrompt);
        if (!env.isBlank()) {
            prompt.append("\n\nEnvironment:\n").append(env);
        }
        prompt.append("\n\nPersona context:\n")
                .append("Project and tool identity remains MineClawd even if the persona uses a different name.\n")
                .append("Active soul: ").append(persona.name()).append("\n");
        if (persona.content() == null || persona.content().isBlank()) {
            prompt.append("Soul instructions are empty. Use default MineClawd behavior.");
        } else {
            prompt.append("Follow these soul instructions:\n")
                    .append(persona.content().trim());
        }
        return prompt.toString();
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
        LOGGER.info("[MineClawd Debug] [session:{}] {}", runtime.sessionId(), String.format(format, args));
    }

    private boolean isRateLimitError(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
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
        ACTIVE_REQUESTS.remove(runtime.ownerKey(), runtime.requestId());
        debugLog(runtime, "Request finished.");
    }

    private void maybeGenerateSessionTitle(
            ServerCommandSource source,
            MineClawdConfig config,
            MineClawdConfig.LlmProvider provider,
            SessionData session,
            String assistantFinalText,
            AgentRuntime runtime
    ) {
        if (session == null || session.titleGenerated()) {
            return;
        }

        String fallback = fallbackTitle(runtime.userRequest(), assistantFinalText);
        String summaryModel = resolveSummaryModel(config, provider);
        if (summaryModel.isBlank()) {
            applySessionTitle(session, fallback, runtime);
            return;
        }

        String summaryInput = String.join("\n\n",
                "User prompt:",
                runtime.userRequest() == null ? "" : runtime.userRequest().trim(),
                "Assistant final response:",
                assistantFinalText == null ? "" : assistantFinalText.trim()
        );

        if (provider == MineClawdConfig.LlmProvider.OPENAI) {
            List<OpenAIMessage> messages = new ArrayList<>();
            messages.add(OpenAIMessage.system(SUMMARY_SYSTEM_PROMPT));
            messages.add(OpenAIMessage.user(summaryInput));
            debugLog(runtime, "Generating session title with OpenAI summarize model=%s", summaryModel);
            OPENAI_CLIENT.sendMessage(config.endpoint, config.apiKey, summaryModel, messages, null)
                    .whenComplete((response, error) -> {
                        if (source.getServer() == null) {
                            return;
                        }
                        source.getServer().execute(() -> {
                            String title = fallback;
                            if (error == null && response != null && response.text() != null && !response.text().isBlank()) {
                                title = normalizeSummaryTitle(response.text());
                            } else if (error != null) {
                                debugLog(runtime, "Session title summarize failed: %s", error.getMessage());
                            }
                            applySessionTitle(session, title, runtime);
                        });
                    });
            return;
        }

        List<VertexAIMessage> messages = new ArrayList<>();
        messages.add(VertexAIMessage.user(SUMMARY_SYSTEM_PROMPT));
        messages.add(VertexAIMessage.user(summaryInput));
        debugLog(runtime, "Generating session title with Vertex summarize model=%s", summaryModel);
        VERTEX_CLIENT.sendMessage(config.vertexEndpoint, config.vertexApiKey, summaryModel, messages, List.of())
                .whenComplete((response, error) -> {
                    if (source.getServer() == null) {
                        return;
                    }
                    source.getServer().execute(() -> {
                        String title = fallback;
                        if (error == null && response != null && response.text() != null && !response.text().isBlank()) {
                            title = normalizeSummaryTitle(response.text());
                        } else if (error != null) {
                            debugLog(runtime, "Session title summarize failed: %s", error.getMessage());
                        }
                        applySessionTitle(session, title, runtime);
                    });
                });
    }

    private void applySessionTitle(SessionData session, String title, AgentRuntime runtime) {
        String normalized = SessionManager.normalizeTitle(title);
        session.setTitle(normalized);
        session.setTitleGenerated(true);
        session.touch();
        SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        debugLog(runtime, "Session title set to: %s", session.title());
    }

    private String resolveSummaryModel(MineClawdConfig config, MineClawdConfig.LlmProvider provider) {
        if (config == null) {
            return "";
        }
        if (provider == MineClawdConfig.LlmProvider.OPENAI) {
            String model = config.summarizeModel == null ? "" : config.summarizeModel.trim();
            if (!model.isBlank()) {
                return model;
            }
            return config.model == null ? "" : config.model.trim();
        }
        String model = config.vertexSummarizeModel == null ? "" : config.vertexSummarizeModel.trim();
        if (!model.isBlank()) {
            return model;
        }
        return config.vertexModel == null ? "" : config.vertexModel.trim();
    }

    private String fallbackTitle(String request, String assistantText) {
        String base = request == null ? "" : request.trim();
        if (base.isBlank()) {
            base = assistantText == null ? "" : assistantText.trim();
        }
        if (base.isBlank()) {
            return "New Session";
        }
        base = base.replace('\r', ' ').replace('\n', ' ').trim();
        if (base.length() > 80) {
            base = base.substring(0, 80).trim();
        }
        return SessionManager.normalizeTitle(base);
    }

    private String normalizeSummaryTitle(String text) {
        if (text == null) {
            return "New Session";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        while (normalized.startsWith("\"") || normalized.startsWith("'") || normalized.startsWith("`")) {
            normalized = normalized.substring(1).trim();
        }
        while (normalized.endsWith("\"") || normalized.endsWith("'") || normalized.endsWith("`")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100).trim();
        }
        return SessionManager.normalizeTitle(normalized);
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

    private record AgentRuntime(
            int toolLimit,
            boolean limitToolCallsEnabled,
            boolean debug,
            String requestId,
            String ownerKey,
            String sessionId,
            String userRequest
    ) {
    }
}
