package com.mineclawd;

import de.themoep.minedown.adventure.MineDown;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.config.MineClawdConfig;
import com.mineclawd.dynamic.DynamicContentRegistry;
import com.mineclawd.dynamic.DynamicContentToolExecutor;
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
import com.mineclawd.player.PlayerSettingsManager;
import com.mineclawd.player.PlayerSettingsManager.RequestBroadcastTarget;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionResponsePayload;
import com.mineclawd.session.SessionManager;
import com.mineclawd.session.SessionManager.SessionData;
import com.mineclawd.session.SessionManager.SessionSummary;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.ChatEvent;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.SharedConstants;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class MineClawd {
    public static final String MOD_ID = "mineclawd";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static MineClawd INSTANCE;

    private static final OpenAIClient OPENAI_CLIENT = new OpenAIClient();
    private static final VertexAIClient VERTEX_CLIENT = new VertexAIClient();
    private static final SessionManager SESSION_MANAGER = new SessionManager();
    private static final PersonaManager PERSONA_MANAGER = new PersonaManager();
    private static final PlayerSettingsManager PLAYER_SETTINGS = new PlayerSettingsManager();
    private static final ConcurrentHashMap<String, String> ACTIVE_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FailedRequestContext> FAILED_REQUESTS_BY_OWNER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingQuestion> PENDING_QUESTIONS_BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingQuestion> PENDING_QUESTIONS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingQuestion> PENDING_OTHER_TEXT_INPUT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> CLIENT_MOD_READY = new ConcurrentHashMap<>();

    private static final String TOOL_APPLY_INSTANT_SERVER_SCRIPT = "apply-instant-server-script";
    private static final String TOOL_ASK_USER = "ask-user-question";
    private static final String TOOL_EXECUTE_COMMAND = "execute-command";
    private static final String TOOL_LIST_SERVER_SCRIPTS = "list-server-scripts";
    private static final String TOOL_READ_SERVER_SCRIPT = "read-server-script";
    private static final String TOOL_WRITE_SERVER_SCRIPT = "write-server-script";
    private static final String TOOL_DELETE_SERVER_SCRIPT = "delete-server-script";
    private static final String TOOL_RELOAD_GAME = "reload-game";
    private static final String TOOL_SYNC_COMMAND_TREE = "sync-command-tree";
    private static final String TOOL_LIST_DYNAMIC_CONTENT = "list-dynamic-content";
    private static final String TOOL_REGISTER_DYNAMIC_ITEM = "register-dynamic-item";
    private static final String TOOL_REGISTER_DYNAMIC_BLOCK = "register-dynamic-block";
    private static final String TOOL_REGISTER_DYNAMIC_FLUID = "register-dynamic-fluid";
    private static final String TOOL_UPDATE_DYNAMIC_ITEM = "update-dynamic-item";
    private static final String TOOL_UPDATE_DYNAMIC_BLOCK = "update-dynamic-block";
    private static final String TOOL_UPDATE_DYNAMIC_FLUID = "update-dynamic-fluid";
    private static final String TOOL_UNREGISTER_DYNAMIC_CONTENT = "unregister-dynamic-content";
    private static final String LEGACY_TOOL_KUBEJS_EVAL = "kubejs_eval";
    private static final int TOOL_LIMIT_MIN = 1;
    private static final int TOOL_LIMIT_MAX = 20;
    private static final int MAX_REPEAT_TOOL_CALLS = 1;
    private static final int VERTEX_FUNCTION_RESPONSE_MISMATCH_RETRIES = 1;
    private static final int RATE_LIMIT_RETRIES = 2;
    private static final long RATE_LIMIT_BACKOFF_MS = 1500L;
    private static final int MAX_QUESTION_OPTIONS = 5;
    private static final long QUESTION_TIMEOUT_SECONDS = 60L;
    private static final int QUESTION_ID_LENGTH = 8;
    private static final int HISTORY_PAGE_MAX_CHARS = 900;
    private static final int HISTORY_MAX_ENTRIES = 300;
    private static final int HISTORY_PACKET_MAX_CHARS = 262_144;
    private static final int FAILED_REQUEST_TOKEN_LENGTH = 8;
    private static final long FAILED_REQUEST_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final String HISTORY_BOOK_TITLE = "MineClawd History";
    private static final GsonComponentSerializer ADVENTURE_GSON = GsonComponentSerializer.gson();
    private static final Pattern MINEDOWN_ACTION_COLON_PATTERN = Pattern.compile(
            "\\((run_command|suggest_command|copy_to_clipboard|change_page|open_url|show_text|hover|insert|show_entity|show_item|custom|show_dialog)\\s*:\\s*",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final List<String> CONFIG_KEYS = List.of(
            "provider",
            "endpoint",
            "api-key",
            "model",
            "summarize-model",
            "vertex-endpoint",
            "vertex-api-key",
            "vertex-model",
            "vertex-summarize-model",
            "debug-mode",
            "limit-tool-calls",
            "tool-call-limit",
            "system-prompt",
            "broadcast-requests-to",
            "dynamic-registry-mode"
    );
    private static final List<String> BOOLEAN_VALUES = List.of("true", "false");
    private static final List<String> PROVIDER_VALUES = List.of("openai", "vertex-ai");
    private static final List<String> BROADCAST_VALUES = List.of("self", "all", "ops");
    private static final List<String> DYNAMIC_REGISTRY_MODE_VALUES = List.of("auto", "enabled", "disabled");
    private static final String SUMMARY_SYSTEM_PROMPT = String.join("\n",
            "You generate titles for Minecraft agent sessions.",
            "Return exactly one concise sentence as plain text.",
            "Example: register-diamond-command",
            "Keep it under 80 characters."
    );
    private static final String BASE_SYSTEM_PROMPT = String.join("\n",
        "You are MineClawd, an advanced Minecraft in-game agent specialized in KubeJS scripting for Minecraft 1.20.1 and 1.21.1.",

        "Tool overview:",
        "- `ask-user-question`: Ask the player a targeted clarification question when details are ambiguous.",
        "  Use this before making risky assumptions, especially if multiple valid implementations exist.",
        "  Provide a concise `question` and up to 5 preset `options`.",
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
        "- `sync-command-tree`: Push refreshed command suggestions/tab-completion to online players after command registration changes.",

        "Persistent-script workflow:",
        "1. Use file tools to edit scripts under `kubejs/server_scripts/mineclawd/`.",
        "2. Run `reload-game` after changes.",
        "3. If reload reports errors, fix scripts and reload again until clean.",

        "KubeJS callback bridge:",
        "- `mineclawd.requestWithSession(player, session_ref, request)` triggers MineClawd with full tool access using an existing session context.",
        "  `session_ref` can be a session id or token. Prefer the stable session id for long-lived listeners.",
        "  This behaves like that player running `/mclawd <request>` without changing the active session.",
        "- `mineclawd.requestOneShot(request, context)` triggers one single request with tools but without session persistence.",
        "  No session history is created or updated in one-shot mode.",
        "  If `context` is omitted, server context is used.",
        "For session-bound callbacks, only bind/listen for the same player who owns that session.",
        "When to use it: eg. the player wants you to comment on them when they did a achievement, so you bind a listener to achievement events for that player and trigger a MineClawd request with the achievement details whenever they get one.",

        "Use persistent scripts for tasks like: registering commands, modifying recipes, listening to player behavior, modifying entity drops, and world tick logic.",
        "Do NOT claim you can register new items, blocks, fluids, or other startup content in this session.",
        "Those require `startup_scripts` plus a full game restart to take effect. If asked, refuse and explain this limit clearly.",
        "If the player asks you to remove a feature you implemented, you may not remember you've implemented it since the player may started a new session. In that case, use the `list-server-scripts` tool to check if any of your scripts are still present, and remove them if needed.",

        "For multi-line code in `apply-instant-server-script`, include normal newline characters; they will be converted to \\n before execution.",
        "When implementing persistent custom commands or handlers, include robust error handling: validate args, guard nulls, and use try/catch with clear error context.",
        "Reload can catch load-time syntax errors, but not all runtime command-path errors. After registering or changing commands, run smoke tests via `execute-command` and fix failures immediately.",
        "Use `sync-command-tree` if and only if command registrations changed and reload already succeeded. Do not call it for unrelated tasks.",
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
        "2.5. If key requirements are unclear, call `ask-user-question` instead of guessing.",
        "3. After receiving tool results, verify the output. If there is an error, analyze it, fix it, and retry.",
        "4. Use Markdown and MineDown syntax for player messages.",
        "5. When the task is complete, explain the result and stop."
    );
    private static final String DYNAMIC_REGISTRY_PROMPT_APPENDIX = String.join("\n",
            "Additional instructions for dynamic registry tools:",
            "Runtime dynamic placeholder mode is enabled on this server.",
            "Though true startup registration is still impossible in-session, you can pseudo-register content by configuring pre-registered placeholders.",
            "In this way, you can 'register' new items, blocks, and fluids with custom properties.",
            "Use these tools:",
            "- `list-dynamic-content`: inspect used and free slots for items/blocks/fluids.",
            "- `register-dynamic-item`: claim a free item slot. Supports `name`, `material_item` (vanilla item id), and `throwable`.",
            "- `register-dynamic-block`: claim a free block slot. Supports `name`, `material_block` (vanilla block id), and `friction`.",
            "- `register-dynamic-fluid`: claim a free fluid slot. Supports `name`, `material_fluid` (vanilla fluid id), and optional pure `color` (#RRGGBB).",
            "- `update-dynamic-item`: update properties on an existing item slot (`slot` required).",
            "- `update-dynamic-block`: update properties on an existing block slot (`slot` required).",
            "- `update-dynamic-fluid`: update properties on an existing fluid slot (`slot` required).",
            "- `unregister-dynamic-content`: release a slot by `type` + `slot`.",
            "Rules:",
            "1. If user does not specify slot, call register tools without `slot` to auto-pick the first unused slot.",
            "2. Registered placeholders become visible in creative tabs; unregistered slots stay hidden.",
            "3. Placeholder IDs are fixed, such as `mineclawd:dynamic_item_001`, `mineclawd:dynamic_block_001`, `mineclawd:dynamic_fluid_001`.",
            "4. For `material_item`, `material_block`, and `material_fluid`, pick a vanilla ID that is semantically related to the requested feature; avoid unrelated defaults.",
            "5. After each dynamic register/update, run an actual in-game verification and inspect real world state/output before claiming success.",
            "6. For temporary validation setups (for example a test block high above the player), clean up immediately and restore modified blocks.",
            "7. For advanced behavior beyond provided properties, combine this with KubeJS scripts."
    );

    public static void init() {
        if (INSTANCE != null) {
            return;
        }
        INSTANCE = new MineClawd();
        MineClawd instance = INSTANCE;
        MineClawdConfig.HANDLER.load();
        MineClawdConfig.HANDLER.save();
        DynamicContentRegistry.bootstrap(MineClawdConfig.get());
        LifecycleEvent.SERVER_STARTED.register(DynamicContentRegistry::loadPersistentState);
        LifecycleEvent.SERVER_STOPPED.register(server -> DynamicContentRegistry.clearServerStateCache());

        KubeJsScriptManager.ensureScriptInGameDir();
        MineClawdNetworking.register();
        PlayerEvent.PLAYER_JOIN.register(player -> {
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            server.execute(() -> {
                    DynamicContentRegistry.loadPersistentState(server);
                    instance.sendBroadcastTargetSync(player);
                    DynamicContentRegistry.syncToPlayer(player);
                });
        });
        PlayerEvent.PLAYER_QUIT.register(player -> CLIENT_MOD_READY.remove(player.getUuid()));
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.CLIENT_READY,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    server.execute(() -> {
                        CLIENT_MOD_READY.put(player.getUuid(), Boolean.TRUE);
                        instance.sendBroadcastTargetSync(player);
                        DynamicContentRegistry.syncToPlayer(player);
                    });
                });
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.QUESTION_RESPONSE,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    String payload = buf.readString(32767);
                    server.execute(() -> instance.handleQuestionResponsePacket(player, payload));
                });
        ChatEvent.RECEIVED.register((sender, message) -> {
            if (instance.handlePendingOtherTextInput(message, sender)) {
                return EventResult.pass();
            }
            return EventResult.interruptFalse();
        });
        CommandRegistrationEvent.EVENT.register(instance::registerCommands);
        LOGGER.info("MineClawd initialized. /mineclawd is ready for the agent loop.");
    }

    private void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            net.minecraft.command.CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(CommandManager.literal("mineclawd")
                .requires(this::isOp)
                .then(CommandManager.literal("config")
                        .executes(context -> openConfig(context.getSource()))
                        .then(CommandManager.argument("key", StringArgumentType.word())
                                .suggests((context, builder) -> suggestConfigKey(context.getSource(), builder))
                                .executes(context -> {
                                    String key = StringArgumentType.getString(context, "key");
                                    return showConfigValue(context.getSource(), key);
                                })
                                .then(CommandManager.argument("value", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            String key = StringArgumentType.getString(context, "key");
                                            return suggestConfigValue(context.getSource(), key, builder);
                                        })
                                        .executes(context -> {
                                            String key = StringArgumentType.getString(context, "key");
                                            String value = StringArgumentType.getString(context, "value");
                                            return setConfigValue(context.getSource(), key, value);
                                        }))))
                .then(CommandManager.literal("sessions")
                        .executes(context -> listSessions(context.getSource()))
                        .then(CommandManager.literal("new")
                                .executes(context -> createNewSession(context.getSource())))
                        .then(CommandManager.literal("list")
                                .executes(context -> listSessions(context.getSource())))
                        .then(CommandManager.literal("repair")
                                .executes(context -> repairSession(context.getSource(), null))
                                .then(CommandManager.argument("session", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestSessionReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String sessionRef = StringArgumentType.getString(context, "session");
                                            return repairSession(context.getSource(), sessionRef);
                                        })))
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
                .then(CommandManager.literal("history")
                        .executes(context -> showCurrentSessionHistory(context.getSource())))
                .then(CommandManager.literal("new")
                        .executes(context -> createNewSession(context.getSource())))
                .then(CommandManager.literal("retry")
                        .then(CommandManager.argument("token", StringArgumentType.word())
                                .executes(context -> {
                                    String token = StringArgumentType.getString(context, "token");
                                    return retryFailedRequest(context.getSource(), token);
                                })))
                .then(CommandManager.literal("persona")
                        .executes(context -> showActivePersona(context.getSource()))
                        .then(CommandManager.argument("soul", StringArgumentType.word())
                                .suggests((context, builder) -> suggestSoulName(context.getSource(), builder))
                                .executes(context -> {
                                    String soul = StringArgumentType.getString(context, "soul");
                                    return switchPersona(context.getSource(), soul);
                                })))
                .then(CommandManager.literal("choose")
                        .executes(context -> showPendingQuestion(context.getSource()))
                        .then(CommandManager.argument("option", StringArgumentType.word())
                                .suggests((context, builder) -> suggestChooseOption(context.getSource(), builder))
                                .executes(context -> {
                                    String option = StringArgumentType.getString(context, "option");
                                    return choosePendingQuestion(context.getSource(), option);
                                })))
                .then(CommandManager.literal("prompt")
                        .then(CommandManager.argument("request", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String request = StringArgumentType.getString(context, "request");
                                    return handleRequest(context.getSource(), request);
                                }))));

        dispatcher.register(CommandManager.literal("mclawd")
                .requires(this::isOp)
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
        if (!canSendToClient(player, MineClawdNetworking.OPEN_CONFIG)) {
            sendAgentMessage(source, "Client mod not detected. Install MineClawd on your client for GUI config.");
            sendAgentMessage(source, "You can still configure from server commands: `/mineclawd config <key> <value>`.");
            sendAgentMessage(source, "Available keys: `" + String.join("`, `", CONFIG_KEYS) + "`.");
            return 1;
        }
        RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
        payload.writeString(target.commandValue());
        NetworkManager.sendToPlayer(player, MineClawdNetworking.OPEN_CONFIG, payload);
        return 1;
    }

    private void sendBroadcastTargetSync(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        if (!canSendToClient(player, MineClawdNetworking.SYNC_BROADCAST_TARGET)) {
            return;
        }
        RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
        payload.writeString(target.commandValue());
        NetworkManager.sendToPlayer(player, MineClawdNetworking.SYNC_BROADCAST_TARGET, payload);
    }

    private CompletableFuture<Suggestions> suggestConfigKey(ServerCommandSource source, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        return CommandSource.suggestMatching(CONFIG_KEYS, builder);
    }

    private CompletableFuture<Suggestions> suggestConfigValue(ServerCommandSource source, String keyInput, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        String key = resolveConfigKey(keyInput);
        if (key.isBlank()) {
            return Suggestions.empty();
        }
        List<String> values = switch (key) {
            case "provider" -> PROVIDER_VALUES;
            case "debug-mode", "limit-tool-calls" -> BOOLEAN_VALUES;
            case "broadcast-requests-to" -> BROADCAST_VALUES;
            case "dynamic-registry-mode" -> DYNAMIC_REGISTRY_MODE_VALUES;
            case "tool-call-limit" -> {
                List<String> numbers = new ArrayList<>();
                for (int i = TOOL_LIMIT_MIN; i <= TOOL_LIMIT_MAX; i++) {
                    numbers.add(Integer.toString(i));
                }
                yield numbers;
            }
            default -> List.of();
        };
        if (values.isEmpty()) {
            return Suggestions.empty();
        }
        return CommandSource.suggestMatching(values, builder);
    }

    private int showConfigValue(ServerCommandSource source, String keyInput) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }

        String key = resolveConfigKey(keyInput);
        if (key.isBlank()) {
            source.sendError(Text.literal("MineClawd: unknown config key. Use /mineclawd config for GUI or tab complete keys."));
            return 0;
        }

        if ("broadcast-requests-to".equals(key)) {
            if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
                source.sendError(Text.literal("MineClawd: broadcast-requests-to is per-player and must be set in-game."));
                return 0;
            }
            RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
            sendAgentMessage(source, "`broadcast-requests-to` = `" + target.commandValue() + "` (" + target.displayName() + ")");
            return 1;
        }

        MineClawdConfig config = MineClawdConfig.get();
        String value = switch (key) {
            case "provider" -> {
                MineClawdConfig.LlmProvider provider = config.provider == null
                        ? MineClawdConfig.LlmProvider.OPENAI
                        : config.provider;
                yield provider == MineClawdConfig.LlmProvider.OPENAI ? "openai" : "vertex-ai";
            }
            case "endpoint" -> config.endpoint;
            case "api-key" -> maskSecret(config.apiKey);
            case "model" -> config.model;
            case "summarize-model" -> config.summarizeModel;
            case "vertex-endpoint" -> config.vertexEndpoint;
            case "vertex-api-key" -> maskSecret(config.vertexApiKey);
            case "vertex-model" -> config.vertexModel;
            case "vertex-summarize-model" -> config.vertexSummarizeModel;
            case "debug-mode" -> Boolean.toString(config.debugMode);
            case "limit-tool-calls" -> Boolean.toString(config.limitToolCalls);
            case "tool-call-limit" -> Integer.toString(config.toolCallLimit);
            case "dynamic-registry-mode" -> {
                MineClawdConfig.DynamicRegistryMode mode = config.dynamicRegistryMode == null
                        ? MineClawdConfig.DynamicRegistryMode.AUTO
                        : config.dynamicRegistryMode;
                yield mode.name().toLowerCase(Locale.ROOT);
            }
            case "system-prompt" -> config.systemPrompt == null || config.systemPrompt.isBlank()
                    ? "<default>"
                    : config.systemPrompt;
            default -> "";
        };

        sendAgentMessage(source, "`" + key + "` = `" + value + "`");
        return 1;
    }

    private int setConfigValue(ServerCommandSource source, String keyInput, String rawValue) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }

        String key = resolveConfigKey(keyInput);
        if (key.isBlank()) {
            source.sendError(Text.literal("MineClawd: unknown config key. Available: " + String.join(", ", CONFIG_KEYS)));
            return 0;
        }
        String value = rawValue == null ? "" : rawValue.trim();

        if ("broadcast-requests-to".equals(key)) {
            if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
                source.sendError(Text.literal("MineClawd: broadcast-requests-to is per-player and must be set in-game."));
                return 0;
            }
            RequestBroadcastTarget target = RequestBroadcastTarget.fromUserInput(value);
            if (target == null) {
                source.sendError(Text.literal("MineClawd: invalid broadcast target. Use self, all, or ops."));
                return 0;
            }
            PLAYER_SETTINGS.setRequestBroadcastTarget(player.getUuidAsString(), target);
            sendBroadcastTargetSync(player);
            sendAgentMessage(source, "Updated `broadcast-requests-to` to `" + target.commandValue() + "` (" + target.displayName() + ").");
            return 1;
        }

        MineClawdConfig config = MineClawdConfig.get();
        switch (key) {
            case "provider" -> {
                MineClawdConfig.LlmProvider provider = parseProvider(value);
                if (provider == null) {
                    source.sendError(Text.literal("MineClawd: invalid provider. Use openai or vertex-ai."));
                    return 0;
                }
                config.provider = provider;
            }
            case "endpoint" -> config.endpoint = value;
            case "api-key" -> config.apiKey = value;
            case "model" -> config.model = value;
            case "summarize-model" -> config.summarizeModel = value;
            case "vertex-endpoint" -> config.vertexEndpoint = value;
            case "vertex-api-key" -> config.vertexApiKey = value;
            case "vertex-model" -> config.vertexModel = value;
            case "vertex-summarize-model" -> config.vertexSummarizeModel = value;
            case "debug-mode" -> {
                Boolean bool = parseBooleanValue(value);
                if (bool == null) {
                    source.sendError(Text.literal("MineClawd: invalid boolean. Use true/false."));
                    return 0;
                }
                config.debugMode = bool;
            }
            case "limit-tool-calls" -> {
                Boolean bool = parseBooleanValue(value);
                if (bool == null) {
                    source.sendError(Text.literal("MineClawd: invalid boolean. Use true/false."));
                    return 0;
                }
                config.limitToolCalls = bool;
            }
            case "tool-call-limit" -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value);
                } catch (NumberFormatException exception) {
                    source.sendError(Text.literal("MineClawd: tool-call-limit must be a number."));
                    return 0;
                }
                if (parsed < TOOL_LIMIT_MIN || parsed > TOOL_LIMIT_MAX) {
                    source.sendError(Text.literal("MineClawd: tool-call-limit must be between " + TOOL_LIMIT_MIN + " and " + TOOL_LIMIT_MAX + "."));
                    return 0;
                }
                config.toolCallLimit = parsed;
            }
            case "dynamic-registry-mode" -> {
                MineClawdConfig.DynamicRegistryMode mode = parseDynamicRegistryMode(value);
                if (mode == null) {
                    source.sendError(Text.literal("MineClawd: invalid dynamic-registry-mode. Use auto, enabled, or disabled."));
                    return 0;
                }
                config.dynamicRegistryMode = mode;
            }
            case "system-prompt" -> {
                if ("default".equalsIgnoreCase(value)) {
                    config.systemPrompt = "";
                } else {
                    config.systemPrompt = rawValue == null ? "" : rawValue;
                }
            }
            default -> {
                source.sendError(Text.literal("MineClawd: unsupported config key."));
                return 0;
            }
        }

        MineClawdConfig.HANDLER.save();
        String shown = switch (key) {
            case "api-key", "vertex-api-key" -> maskSecret(value);
            case "system-prompt" -> value.isBlank() ? "<default>" : value;
            case "dynamic-registry-mode" -> {
                MineClawdConfig.DynamicRegistryMode mode = config.dynamicRegistryMode == null
                        ? MineClawdConfig.DynamicRegistryMode.AUTO
                        : config.dynamicRegistryMode;
                yield mode.name().toLowerCase(Locale.ROOT);
            }
            default -> value;
        };
        String message = "Updated `" + key + "` to `" + shown + "`.";
        if ("dynamic-registry-mode".equals(key)) {
            message += " Restart the game/server to apply this change.";
        }
        sendAgentMessage(source, message);
        return 1;
    }

    private String resolveConfigKey(String keyInput) {
        if (keyInput == null || keyInput.isBlank()) {
            return "";
        }
        String key = keyInput.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (key) {
            case "provider" -> "provider";
            case "endpoint", "openai-endpoint" -> "endpoint";
            case "api-key", "openai-api-key", "key" -> "api-key";
            case "model", "openai-model" -> "model";
            case "summarize-model", "openai-summarize-model" -> "summarize-model";
            case "vertex-endpoint" -> "vertex-endpoint";
            case "vertex-api-key" -> "vertex-api-key";
            case "vertex-model" -> "vertex-model";
            case "vertex-summarize-model" -> "vertex-summarize-model";
            case "debug-mode" -> "debug-mode";
            case "limit-tool-calls" -> "limit-tool-calls";
            case "tool-call-limit" -> "tool-call-limit";
            case "dynamic-registry-mode", "dynamic-runtime-registry", "dynamic-registry", "dynamic-content-mode" -> "dynamic-registry-mode";
            case "system-prompt" -> "system-prompt";
            case "broadcast-requests-to", "request-broadcast", "broadcast", "broadcast-requests" -> "broadcast-requests-to";
            default -> "";
        };
    }

    private MineClawdConfig.LlmProvider parseProvider(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "openai", "open-ai" -> MineClawdConfig.LlmProvider.OPENAI;
            case "vertex-ai", "vertexai", "vertex", "google-vertex-ai" -> MineClawdConfig.LlmProvider.VERTEX_AI;
            default -> null;
        };
    }

    private MineClawdConfig.DynamicRegistryMode parseDynamicRegistryMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "auto" -> MineClawdConfig.DynamicRegistryMode.AUTO;
            case "enabled", "enable", "on", "true" -> MineClawdConfig.DynamicRegistryMode.ENABLED;
            case "disabled", "disable", "off", "false" -> MineClawdConfig.DynamicRegistryMode.DISABLED;
            default -> null;
        };
    }

    private Boolean parseBooleanValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "1", "yes", "on" -> Boolean.TRUE;
            case "false", "0", "no", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        if (value.length() <= 6) {
            return "******";
        }
        String tail = value.substring(value.length() - 4);
        return "****" + tail;
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

    private int repairSession(ServerCommandSource source, String sessionRef) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: cannot repair sessions while a request is running."));
            return 0;
        }

        SessionData session = sessionRef == null || sessionRef.isBlank()
                ? SESSION_MANAGER.loadActiveSession(ownerKey)
                : SESSION_MANAGER.resolve(ownerKey, sessionRef);
        if (session == null) {
            source.sendError(Text.literal("MineClawd: session not found. Use /mineclawd sessions list."));
            return 0;
        }

        boolean changed = normalizeVertexFunctionCallTurns(session.vertexHistory(), null);
        if (changed) {
            session.touch();
            SESSION_MANAGER.saveSession(ownerKey, session);
            sendAgentMessage(source, "Repaired session `" + session.commandToken() + "` Vertex history.");
        } else {
            sendAgentMessage(source, "Session `" + session.commandToken() + "` does not need repair.");
        }
        return 1;
    }

    private int showCurrentSessionHistory(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }
        SessionData session = SESSION_MANAGER.loadActiveSession(sessionOwnerKey(source));
        if (session == null) {
            sendAgentMessage(source, "No active session. Use `/mineclawd sessions new` first.");
            return 1;
        }

        List<HistoryEntry> entries = collectVisibleHistoryEntries(session);
        sendHistoryBookToPlayer(source, player, session, entries);
        return 1;
    }

    private int retryFailedRequest(ServerCommandSource source, String token) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        if (ACTIVE_REQUESTS.containsKey(ownerKey)) {
            source.sendError(Text.literal("MineClawd: another request is still running for this session."));
            return 0;
        }
        FailedRequestContext failed = FAILED_REQUESTS_BY_OWNER.get(ownerKey);
        if (failed == null) {
            source.sendError(Text.literal("MineClawd: retry token is invalid or expired."));
            return 0;
        }
        if (failed.isExpired() || !failed.matchesToken(token)) {
            FAILED_REQUESTS_BY_OWNER.remove(ownerKey, failed);
            source.sendError(Text.literal("MineClawd: retry token is invalid or expired."));
            return 0;
        }
        SessionData active = SESSION_MANAGER.loadActiveSession(ownerKey);
        if (active == null || !failed.sessionId().equals(active.id())) {
            source.sendError(Text.literal("MineClawd: active session changed. Resume the original session or resend manually."));
            return 0;
        }

        int result = handleRequest(source, failed.request());
        if (result > 0) {
            FAILED_REQUESTS_BY_OWNER.remove(ownerKey, failed);
        }
        return result;
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

    private CompletableFuture<Suggestions> suggestChooseOption(ServerCommandSource source, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return Suggestions.empty();
        }
        PendingQuestion pending = PENDING_QUESTIONS_BY_PLAYER.get(player.getUuid());
        if (pending == null) {
            return Suggestions.empty();
        }

        List<String> suggestions = new ArrayList<>();
        suggestions.add("skip");
        suggestions.add("other");
        suggestions.add("cancel");
        for (int i = 0; i < pending.options().size(); i++) {
            suggestions.add(Integer.toString(i + 1));
        }
        return CommandSource.suggestMatching(suggestions, builder);
    }

    private int showPendingQuestion(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }
        PendingQuestion pending = PENDING_QUESTIONS_BY_PLAYER.get(player.getUuid());
        if (pending == null) {
            sendAgentMessage(source, "No pending question.");
            return 1;
        }
        sendPendingQuestionFallback(source, player, pending);
        return 1;
    }

    private int choosePendingQuestion(ServerCommandSource source, String rawOption) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }
        PendingQuestion pending = PENDING_QUESTIONS_BY_PLAYER.get(player.getUuid());
        if (pending == null) {
            source.sendError(Text.literal("MineClawd: no pending question."));
            return 0;
        }

        String option = rawOption == null ? "" : rawOption.trim().toLowerCase(Locale.ROOT);
        if (option.isBlank()) {
            source.sendError(Text.literal("MineClawd: choose an option, `other`, `skip`, or `cancel`."));
            return 0;
        }

        if ("cancel".equals(option)) {
            if (PENDING_OTHER_TEXT_INPUT.remove(player.getUuid()) != null) {
                sendAgentMessage(source, "Canceled custom text input. Please choose an option.");
                sendPendingQuestionFallback(source, player, pending);
                return 1;
            }
            source.sendError(Text.literal("MineClawd: not waiting for custom text input."));
            return 0;
        }

        if ("skip".equals(option)) {
            completePendingQuestion(pending, "SKIPPED: User explicitly skipped the question.");
            sendAgentMessage(source, "Skipped.");
            return 1;
        }

        if ("other".equals(option)) {
            PENDING_OTHER_TEXT_INPUT.put(player.getUuid(), pending);
            sendAgentMessage(source, "Please type your custom answer in chat within 60 seconds, or use `/mineclawd choose cancel`.");
            return 1;
        }

        int optionIndex = parseOptionIndex(option, pending.options().size());
        if (optionIndex < 0) {
            source.sendError(Text.literal("MineClawd: invalid option. Use tab completion for valid choices."));
            return 0;
        }
        String selected = pending.options().get(optionIndex);
        completePendingQuestion(
                pending,
                "User selected option " + (optionIndex + 1) + ": " + selected
        );
        sendAgentMessage(source, "Selected option `" + (optionIndex + 1) + "`.");
        return 1;
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
        return handleRequest(source, request, RequestOptions.command());
    }

    public static String enqueueKubeJsSessionRequest(ServerPlayerEntity player, String sessionReference, String request) {
        MineClawd instance = INSTANCE;
        if (instance == null) {
            return "ERROR: MineClawd is not initialized.";
        }
        return instance.enqueueKubeJsSessionRequestInternal(player, sessionReference, request);
    }

    public static String enqueueKubeJsOneShotRequest(ServerCommandSource source, MinecraftServer server, String request) {
        MineClawd instance = INSTANCE;
        if (instance == null) {
            return "ERROR: MineClawd is not initialized.";
        }
        return instance.enqueueKubeJsOneShotRequestInternal(source, server, request);
    }

    private String enqueueKubeJsSessionRequestInternal(ServerPlayerEntity player, String sessionReference, String request) {
        if (player == null || player.getServer() == null) {
            return "ERROR: requestWithSession requires an online player.";
        }
        String normalizedRequest = normalizeIncomingRequest(request);
        if (normalizedRequest.isBlank()) {
            return "ERROR: requestWithSession requires a non-empty request.";
        }
        String reference = sessionReference == null ? "" : sessionReference.trim();
        if (reference.isBlank()) {
            return "ERROR: requestWithSession requires `session_ref` (session id or token).";
        }

        String ownerKey = player.getUuidAsString();
        if (SESSION_MANAGER.resolve(ownerKey, reference) == null) {
            return "ERROR: session `" + reference + "` was not found for player `" + player.getName().getString()
                    + "`. It may have been removed.";
        }

        ServerCommandSource source = player.getCommandSource().withMaxLevel(4);
        int result = handleRequest(source, normalizedRequest, RequestOptions.sessionBound(ownerKey, reference));
        if (result <= 0) {
            return "ERROR: failed to start session callback request.";
        }
        return "OK: started session callback request for player `" + player.getName().getString()
                + "` using session `" + reference + "`.";
    }

    private String enqueueKubeJsOneShotRequestInternal(ServerCommandSource source, MinecraftServer server, String request) {
        MinecraftServer resolvedServer = source != null && source.getServer() != null
                ? source.getServer()
                : server;
        if (resolvedServer == null) {
            return "ERROR: requestOneShot requires a server context.";
        }
        String normalizedRequest = normalizeIncomingRequest(request);
        if (normalizedRequest.isBlank()) {
            return "ERROR: requestOneShot requires a non-empty request.";
        }
        ServerCommandSource resolvedSource = source != null && source.getServer() != null
                ? source.withMaxLevel(4)
                : resolvedServer.getCommandSource().withMaxLevel(4);
        String oneShotOwnerKey = "kubejs-oneshot-" + UUID.randomUUID().toString().replace("-", "");
        int result = handleRequest(resolvedSource, normalizedRequest, RequestOptions.oneShot(oneShotOwnerKey));
        if (result <= 0) {
            return "ERROR: failed to start one-shot callback request.";
        }
        return "OK: started one-shot callback request.";
    }

    private String normalizeIncomingRequest(String request) {
        if (request == null) {
            return "";
        }
        return request.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private int handleRequest(ServerCommandSource source, String request, RequestOptions options) {
        RequestOptions requestOptions = options == null ? RequestOptions.command() : options;
        if (requestOptions.requireOp() && !isOp(source)) {
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

        String ownerKey = requestOptions.ownerKey() == null || requestOptions.ownerKey().isBlank()
                ? sessionOwnerKey(source)
                : requestOptions.ownerKey().trim();
        String requestId = UUID.randomUUID().toString();
        if (ACTIVE_REQUESTS.putIfAbsent(ownerKey, requestId) != null) {
            source.sendError(Text.literal("MineClawd: another request is still running for this session."));
            return 0;
        }

        SessionData session = null;
        String sessionId = "single-turn";
        try {
            if (requestOptions.sessionBacked()) {
                String sessionRef = requestOptions.sessionReference();
                if (sessionRef != null && !sessionRef.isBlank()) {
                    session = SESSION_MANAGER.resolve(ownerKey, sessionRef);
                    if (session == null) {
                        ACTIVE_REQUESTS.remove(ownerKey, requestId);
                        source.sendError(Text.literal("MineClawd: bound session was not found. It may have been removed."));
                        return 0;
                    }
                } else {
                    session = SESSION_MANAGER.loadOrCreateActiveSession(ownerKey);
                }
                sessionId = session.id();
            }
        } catch (Exception exception) {
            ACTIVE_REQUESTS.remove(ownerKey, requestId);
            source.sendError(Text.literal("MineClawd: failed to load session: " + exception.getMessage()));
            return 0;
        }

        AgentRuntime runtime = new AgentRuntime(
                resolveToolLimit(config),
                isToolLimitEnabled(config),
                DynamicContentRegistry.isRuntimeEnabled(),
                config.debugMode,
                requestId,
                ownerKey,
                sessionId,
                request,
                requestOptions.sessionBacked(),
                requestOptions.interactiveErrorActions()
        );
        debugLog(runtime, "Request from %s session=%s: %s", ownerKey, sessionId, request);
        debugLog(runtime, "Request id: %s", requestId);
        debugLog(runtime, "Tool call limit enabled: %s", runtime.limitToolCallsEnabled());
        if (runtime.limitToolCallsEnabled()) {
            debugLog(runtime, "Tool call limit: %d", runtime.toolLimit());
        }
        sendPromptEcho(source, request);
        sendTaskStatus(source, true);
        try {
            String systemPrompt = buildSystemPrompt(config, ownerKey, runtime.dynamicRegistryEnabled(), session);
            if (provider == MineClawdConfig.LlmProvider.OPENAI) {
                List<OpenAIMessage> history;
                if (runtime.sessionBacked() && session != null) {
                    history = session.openAiHistory();
                    ensureOpenAiHistory(history, systemPrompt);
                    history.add(OpenAIMessage.user(request));
                    session.touch();
                    SESSION_MANAGER.saveSession(ownerKey, session);
                } else {
                    history = new ArrayList<>();
                    ensureOpenAiHistory(history, systemPrompt);
                    history.add(OpenAIMessage.user(request));
                }
                runOpenAiAgent(source, session, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            } else {
                List<VertexAIMessage> history;
                if (runtime.sessionBacked() && session != null) {
                    history = session.vertexHistory();
                    ensureVertexHistory(history, systemPrompt);
                    normalizeVertexFunctionCallTurns(history, runtime);
                    history.add(VertexAIMessage.user(request));
                    session.touch();
                    SESSION_MANAGER.saveSession(ownerKey, session);
                } else {
                    history = new ArrayList<>();
                    ensureVertexHistory(history, systemPrompt);
                    history.add(VertexAIMessage.user(request));
                }
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
            RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
            broadcastPromptLine(source, player, line, target);
            return;
        }
        source.sendFeedback(() -> line, false);
    }

    private void broadcastPromptLine(
            ServerCommandSource source,
            ServerPlayerEntity sender,
            MutableText line,
            RequestBroadcastTarget target
    ) {
        if (source.getServer() == null || sender == null) {
            if (sender != null) {
                sender.sendMessage(line, false);
            }
            return;
        }
        RequestBroadcastTarget mode = target == null ? RequestBroadcastTarget.SELF : target;
        if (mode == RequestBroadcastTarget.SELF) {
            sender.sendMessage(line, false);
            return;
        }
        if (mode == RequestBroadcastTarget.ALL) {
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                player.sendMessage(line.copy(), false);
            }
            return;
        }
        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (source.getServer().getPlayerManager().isOperator(player.getGameProfile())) {
                player.sendMessage(line.copy(), false);
            }
        }
    }

    private void sendTaskStatus(ServerCommandSource source, boolean started) {
        if (source == null) {
            return;
        }
        String requesterName = source.getName();
        String text = started
                ? "MineClawd started working for " + requesterName + "..."
                : "MineClawd finished task requested by " + requesterName + ".";
        MutableText line = Text.literal(text).formatted(Formatting.GRAY);

        if (!(source.getEntity() instanceof ServerPlayerEntity requester) || source.getServer() == null) {
            source.sendFeedback(() -> line, false);
            return;
        }

        RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(requester.getUuidAsString());
        requester.sendMessage(line.copy(), false);
        if (target == RequestBroadcastTarget.SELF) {
            return;
        }

        if (target == RequestBroadcastTarget.ALL) {
            for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                if (player == null || player.getUuid().equals(requester.getUuid())) {
                    continue;
                }
                player.sendMessage(line.copy(), false);
            }
            return;
        }

        for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
            if (player == null || player.getUuid().equals(requester.getUuid())) {
                continue;
            }
            if (source.getServer().getPlayerManager().isOperator(player.getGameProfile())) {
                player.sendMessage(line.copy(), false);
            }
        }
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
        OPENAI_CLIENT.sendMessage(config.endpoint, config.apiKey, config.model, history, openAiTools(runtime.dynamicRegistryEnabled()))
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
            handleLlmRequestFailure(
                    source,
                    session,
                    runtime,
                    MineClawdConfig.LlmProvider.OPENAI,
                    summarizeThrowable(error)
            );
            return;
        }
        if (response == null) {
            handleLlmRequestFailure(
                    source,
                    session,
                    runtime,
                    MineClawdConfig.LlmProvider.OPENAI,
                    "LLM returned no response."
            );
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
            executeOpenAiToolCallsAsync(source, toolCalls, runtime)
                    .whenComplete((batch, batchError) -> {
                        if (source.getServer() == null) {
                            finishActiveRequest(runtime);
                            return;
                        }
                        source.getServer().execute(() -> {
                            if (batchError != null || batch == null) {
                                source.sendError(Text.literal("MineClawd: tool execution failed: " + summarizeThrowable(batchError)));
                                finishActiveRequest(runtime);
                                return;
                            }
                            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
                            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                                source.sendError(Text.literal("MineClawd: repeated tool call detected. Stopping."));
                                finishActiveRequest(runtime);
                                return;
                            }
                            List<OpenAIMessage> toolMessages = batch.messages();
                            history.addAll(toolMessages);
                            if (session != null) {
                                session.touch();
                                SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
                            }
                            runOpenAiAgent(source, session, history, depth + 1, 0, nextState, runtime);
                        });
                    });
            return;
        }

        history.add(OpenAIMessage.assistant(text, null));
        if (session != null) {
            session.touch();
            SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        }
        FAILED_REQUESTS_BY_OWNER.remove(runtime.ownerKey());
        if (session != null) {
            maybeGenerateSessionTitle(source, MineClawdConfig.get(), MineClawdConfig.LlmProvider.OPENAI, session, text, runtime);
        }
        sendTaskStatus(source, false);
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
        VERTEX_CLIENT.sendMessage(config.vertexEndpoint, config.vertexApiKey, config.vertexModel, history, vertexTools(runtime.dynamicRegistryEnabled()))
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
            if (isVertexFunctionResponseMismatch(error)
                    && retryCount < VERTEX_FUNCTION_RESPONSE_MISMATCH_RETRIES
                    && normalizeVertexFunctionCallTurns(history, runtime)) {
                if (session != null) {
                    session.touch();
                    SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
                }
                runVertexAgent(source, session, history, depth, retryCount + 1, loopState, runtime);
                return;
            }
            if (isRateLimitError(error) && retryCount < RATE_LIMIT_RETRIES) {
                scheduleRateLimitRetry(source, runtime, retryCount, () ->
                        runVertexAgent(source, session, history, depth, retryCount + 1, loopState, runtime));
                return;
            }
            handleLlmRequestFailure(
                    source,
                    session,
                    runtime,
                    MineClawdConfig.LlmProvider.VERTEX_AI,
                    summarizeThrowable(error)
            );
            return;
        }
        if (response == null) {
            handleLlmRequestFailure(
                    source,
                    session,
                    runtime,
                    MineClawdConfig.LlmProvider.VERTEX_AI,
                    "LLM returned no response."
            );
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
            executeVertexToolCallsAsync(source, toolCalls, runtime)
                    .whenComplete((batch, batchError) -> {
                        if (source.getServer() == null) {
                            finishActiveRequest(runtime);
                            return;
                        }
                        source.getServer().execute(() -> {
                            if (batchError != null || batch == null) {
                                source.sendError(Text.literal("MineClawd: tool execution failed: " + summarizeThrowable(batchError)));
                                finishActiveRequest(runtime);
                                return;
                            }
                            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
                            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                                source.sendError(Text.literal("MineClawd: repeated tool call detected. Stopping."));
                                finishActiveRequest(runtime);
                                return;
                            }
                            List<VertexAIMessage> toolMessages = batch.vertexMessages();
                            history.addAll(toolMessages);
                            if (session != null) {
                                session.touch();
                                SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
                            }
                            runVertexAgent(source, session, history, depth + 1, 0, nextState, runtime);
                        });
                    });
            return;
        }

        if (session != null) {
            session.touch();
            SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        }
        FAILED_REQUESTS_BY_OWNER.remove(runtime.ownerKey());
        if (session != null) {
            maybeGenerateSessionTitle(source, MineClawdConfig.get(), MineClawdConfig.LlmProvider.VERTEX_AI, session, text, runtime);
        }
        sendTaskStatus(source, false);
        finishActiveRequest(runtime);
    }

    private CompletableFuture<ToolExecutionBatch> executeOpenAiToolCallsAsync(
            ServerCommandSource source,
            List<OpenAIToolCall> toolCalls,
            AgentRuntime runtime
    ) {
        List<OpenAIMessage> results = Collections.synchronizedList(new ArrayList<>());
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        List<String> signatures = Collections.synchronizedList(new ArrayList<>());
        return executeOpenAiToolCallsSequential(source, toolCalls, runtime, 0, results, outputs, signatures)
                .thenApply(unused -> ToolExecutionBatch.openAi(
                        new ArrayList<>(results),
                        String.join("\n", signatures),
                        String.join("\n", outputs)
                ));
    }

    private CompletableFuture<Void> executeOpenAiToolCallsSequential(
            ServerCommandSource source,
            List<OpenAIToolCall> toolCalls,
            AgentRuntime runtime,
            int index,
            List<OpenAIMessage> results,
            List<String> outputs,
            List<String> signatures
    ) {
        if (toolCalls == null || index >= toolCalls.size()) {
            return CompletableFuture.completedFuture(null);
        }
        OpenAIToolCall call = toolCalls.get(index);
        if (call == null) {
            return executeOpenAiToolCallsSequential(source, toolCalls, runtime, index + 1, results, outputs, signatures);
        }

        JsonObject args = parseToolArguments(call.arguments());
        int callIndex = index + 1;
        debugLog(runtime, "Executing tool (OpenAI) #%d name=%s args=%s", callIndex, call.name(), args);
        return executeToolCallAsync(source, call.name(), args, runtime)
                .handle((output, throwable) -> {
                    String finalOutput = output;
                    if (throwable != null) {
                        finalOutput = "ERROR: " + summarizeThrowable(throwable);
                    } else if (finalOutput == null || finalOutput.isBlank()) {
                        finalOutput = "ERROR: Tool returned empty output.";
                    }
                    debugLog(runtime, "Tool output #%d: %s", callIndex, finalOutput);
                    String toolCallId = call.id();
                    if (toolCallId == null || toolCallId.isBlank()) {
                        toolCallId = "unknown";
                    }
                    results.add(OpenAIMessage.tool(toolCallId, finalOutput));
                    outputs.add(finalOutput);
                    signatures.add(call.name() + ":" + args);
                    return null;
                })
                .thenCompose(unused -> executeOpenAiToolCallsSequential(
                        source,
                        toolCalls,
                        runtime,
                        index + 1,
                        results,
                        outputs,
                        signatures
                ));
    }

    private CompletableFuture<ToolExecutionBatch> executeVertexToolCallsAsync(
            ServerCommandSource source,
            List<VertexAIToolCall> toolCalls,
            AgentRuntime runtime
    ) {
        List<JsonObject> responseParts = Collections.synchronizedList(new ArrayList<>());
        List<String> outputs = Collections.synchronizedList(new ArrayList<>());
        List<String> signatures = Collections.synchronizedList(new ArrayList<>());
        return executeVertexToolCallsSequential(source, toolCalls, runtime, 0, responseParts, outputs, signatures)
                .thenApply(unused -> ToolExecutionBatch.vertex(
                        responseParts.isEmpty()
                                ? List.of()
                                : List.of(new VertexAIMessage("user", new ArrayList<>(responseParts))),
                        String.join("\n", signatures),
                        String.join("\n", outputs)
                ));
    }

    private CompletableFuture<Void> executeVertexToolCallsSequential(
            ServerCommandSource source,
            List<VertexAIToolCall> toolCalls,
            AgentRuntime runtime,
            int index,
            List<JsonObject> responseParts,
            List<String> outputs,
            List<String> signatures
    ) {
        if (toolCalls == null || index >= toolCalls.size()) {
            return CompletableFuture.completedFuture(null);
        }
        VertexAIToolCall call = toolCalls.get(index);
        if (call == null) {
            JsonObject response = new JsonObject();
            response.addProperty("result", "ERROR: Empty tool call entry.");
            response.addProperty("is_error", true);
            responseParts.add(VertexAIMessage.functionResponsePart("", response));
            outputs.add("ERROR: Empty tool call entry.");
            signatures.add("(null):{}");
            return executeVertexToolCallsSequential(source, toolCalls, runtime, index + 1, responseParts, outputs, signatures);
        }

        JsonObject args = call.args() == null ? new JsonObject() : call.args();
        int callIndex = index + 1;
        debugLog(runtime, "Executing tool (Vertex) #%d name=%s args=%s", callIndex, call.name(), args);
        return executeToolCallAsync(source, call.name(), args, runtime)
                .handle((output, throwable) -> {
                    String finalOutput = output;
                    if (throwable != null) {
                        finalOutput = "ERROR: " + summarizeThrowable(throwable);
                    } else if (finalOutput == null || finalOutput.isBlank()) {
                        finalOutput = "ERROR: Tool returned empty output.";
                    }
                    debugLog(runtime, "Tool output #%d: %s", callIndex, finalOutput);
                    JsonObject response = new JsonObject();
                    response.addProperty("result", finalOutput);
                    response.addProperty("is_error", finalOutput.startsWith("ERROR:"));
                    responseParts.add(VertexAIMessage.functionResponsePart(call.name(), response));
                    outputs.add(finalOutput);
                    signatures.add(call.name() + ":" + args);
                    return null;
                })
                .thenCompose(unused -> executeVertexToolCallsSequential(
                        source,
                        toolCalls,
                        runtime,
                        index + 1,
                        responseParts,
                        outputs,
                        signatures
                ));
    }

    private CompletableFuture<String> executeToolCallAsync(
            ServerCommandSource source,
            String toolName,
            JsonObject args,
            AgentRuntime runtime
    ) {
        if (TOOL_ASK_USER.equals(toolName)) {
            return askUserQuestion(source, args, runtime);
        }
        return CompletableFuture.completedFuture(executeToolCallSync(source, toolName, args));
    }

    private String executeToolCallSync(ServerCommandSource source, String toolName, JsonObject args) {
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
            case TOOL_SYNC_COMMAND_TREE:
                result = KubeJsToolExecutor.syncCommandTree(source);
                break;
            case TOOL_LIST_DYNAMIC_CONTENT:
                result = DynamicContentToolExecutor.list();
                break;
            case TOOL_REGISTER_DYNAMIC_ITEM:
                result = DynamicContentToolExecutor.registerItem(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_item"),
                        readOptionalBooleanArg(args, "throwable")
                );
                break;
            case TOOL_REGISTER_DYNAMIC_BLOCK:
                result = DynamicContentToolExecutor.registerBlock(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_block"),
                        readOptionalDoubleArg(args, "friction")
                );
                break;
            case TOOL_REGISTER_DYNAMIC_FLUID:
                result = DynamicContentToolExecutor.registerFluid(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_fluid"),
                        readRequiredStringArg(args, "color")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_ITEM:
                result = DynamicContentToolExecutor.updateItem(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_item"),
                        readOptionalBooleanArg(args, "throwable")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_BLOCK:
                result = DynamicContentToolExecutor.updateBlock(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_block"),
                        readOptionalDoubleArg(args, "friction")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_FLUID:
                result = DynamicContentToolExecutor.updateFluid(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_fluid"),
                        readRequiredStringArg(args, "color")
                );
                break;
            case TOOL_UNREGISTER_DYNAMIC_CONTENT:
                result = DynamicContentToolExecutor.unregister(
                        source,
                        readRequiredStringArg(args, "type"),
                        readOptionalIntArg(args, "slot")
                );
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

    private Integer readOptionalIntArg(JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double readOptionalDoubleArg(JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean readOptionalBooleanArg(JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        try {
            return args.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> readQuestionOptions(JsonObject args) {
        if (args == null) {
            return List.of();
        }
        JsonArray raw = null;
        if (args.has("options") && args.get("options").isJsonArray()) {
            raw = args.getAsJsonArray("options");
        } else if (args.has("choices") && args.get("choices").isJsonArray()) {
            raw = args.getAsJsonArray("choices");
        }
        if (raw == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (int i = 0; i < raw.size() && options.size() < MAX_QUESTION_OPTIONS; i++) {
            try {
                String option = raw.get(i).getAsString();
                if (option == null || option.isBlank()) {
                    continue;
                }
                String normalized = option.replace('\r', ' ').replace('\n', ' ').trim();
                if (normalized.length() > 160) {
                    normalized = normalized.substring(0, 160).trim();
                }
                if (!normalized.isBlank()) {
                    options.add(normalized);
                }
            } catch (Exception ignored) {
            }
        }
        return options;
    }

    private CompletableFuture<String> askUserQuestion(ServerCommandSource source, JsonObject args, AgentRuntime runtime) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player) || source.getServer() == null) {
            return CompletableFuture.completedFuture("ERROR: ask-user-question requires a player source.");
        }

        String question = readRequiredStringArg(args, "question");
        if (question == null || question.isBlank()) {
            return CompletableFuture.completedFuture("ERROR: ask-user-question is missing required string `question`.");
        }
        question = question.replace('\r', ' ').replace('\n', ' ').trim();
        if (question.length() > 400) {
            question = question.substring(0, 400).trim();
        }
        List<String> options = readQuestionOptions(args);
        if (options.isEmpty()) {
            return CompletableFuture.completedFuture("ERROR: ask-user-question requires at least one option in `options`.");
        }

        String questionId = buildQuestionId();
        PendingQuestion pending = new PendingQuestion(
                questionId,
                player.getUuid(),
                player.getName().getString(),
                question,
                options,
                System.currentTimeMillis() + (QUESTION_TIMEOUT_SECONDS * 1000L)
        );

        PendingQuestion existing = PENDING_QUESTIONS_BY_PLAYER.put(player.getUuid(), pending);
        if (existing != null) {
            completePendingQuestion(existing, "SKIPPED: Replaced by a newer question.");
        }
        PENDING_QUESTIONS_BY_ID.put(questionId, pending);
        PENDING_OTHER_TEXT_INPUT.remove(player.getUuid());

        if (canSendToClient(player, MineClawdNetworking.OPEN_QUESTION)) {
            QuestionPromptPayload payload = new QuestionPromptPayload(
                    questionId,
                    question,
                    options,
                    pending.expiresAtEpochMillis()
            );
            var buffer = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
            buffer.writeString(payload.toJson());
            NetworkManager.sendToPlayer(player, MineClawdNetworking.OPEN_QUESTION, buffer);
            player.sendMessage(Text.empty().append(agentPrefix()).append(renderAgentBody(null,
                    "I need one decision from you. Please answer in the popup (`" + QUESTION_TIMEOUT_SECONDS + "s` timeout)."
            )), false);
        } else {
            sendPendingQuestionFallback(source, player, pending);
        }

        CompletableFuture.delayedExecutor(QUESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (source.getServer() == null) {
                completePendingQuestion(pending, "SKIPPED: Question timeout.");
                return;
            }
            source.getServer().execute(() -> {
                PendingQuestion current = PENDING_QUESTIONS_BY_ID.get(questionId);
                if (current == pending) {
                    completePendingQuestion(current, "SKIPPED: User did not respond within 60 seconds.");
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Question timed out. Continuing with skip result.")), false);
                }
            });
        });

        debugLog(runtime, "Waiting for user answer questionId=%s options=%d", questionId, options.size());
        return pending.future();
    }

    private void sendPendingQuestionFallback(ServerCommandSource source, ServerPlayerEntity player, PendingQuestion pending) {
        player.sendMessage(Text.empty().append(agentPrefix())
                .append(renderAgentBody(null, "I need your input. Choose below (`60s` timeout).")), false);
        player.sendMessage(Text.empty().append(agentPrefix()).append(Text.literal(pending.question())), false);
        for (int i = 0; i < pending.options().size(); i++) {
            int index = i + 1;
            String option = pending.options().get(i);
            MutableText optionLine = Text.literal(index + ". " + option)
                    .setStyle(Style.EMPTY
                            .withColor(Formatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mineclawd choose " + index)));
            player.sendMessage(Text.empty().append(agentPrefix()).append(optionLine), false);
        }

        MutableText other = Text.literal("Other (type custom text)")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mineclawd choose other")));
        MutableText skip = Text.literal("Skip")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GRAY)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mineclawd choose skip")));
        player.sendMessage(Text.empty().append(agentPrefix()).append(other), false);
        player.sendMessage(Text.empty().append(agentPrefix()).append(skip), false);
        sendAgentMessage(source, "Waiting for your choice via `/mineclawd choose <option>`.");
    }

    private int parseOptionIndex(String token, int optionCount) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        int index;
        try {
            index = Integer.parseInt(token.trim()) - 1;
        } catch (NumberFormatException exception) {
            return -1;
        }
        if (index < 0 || index >= optionCount) {
            return -1;
        }
        return index;
    }

    private void completePendingQuestion(PendingQuestion pending, String responseText) {
        if (pending == null) {
            return;
        }
        if (!pending.complete(responseText == null ? "SKIPPED: Empty response." : responseText)) {
            return;
        }
        PENDING_QUESTIONS_BY_ID.remove(pending.id(), pending);
        PENDING_QUESTIONS_BY_PLAYER.remove(pending.playerUuid(), pending);
        PENDING_OTHER_TEXT_INPUT.remove(pending.playerUuid(), pending);
    }

    private void handleQuestionResponsePacket(ServerPlayerEntity player, String payload) {
        if (player == null || payload == null || payload.isBlank()) {
            return;
        }
        QuestionResponsePayload response = QuestionResponsePayload.fromJson(payload);
        if (response == null || response.questionId() == null || response.questionId().isBlank()) {
            return;
        }
        PendingQuestion pending = PENDING_QUESTIONS_BY_ID.get(response.questionId());
        if (pending == null || !pending.playerUuid().equals(player.getUuid())) {
            return;
        }

        switch (response.type()) {
            case OPTION -> {
                int index = response.optionIndex();
                if (index < 0 || index >= pending.options().size()) {
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Invalid option index from client UI. Please retry.")), false);
                    return;
                }
                String selected = pending.options().get(index);
                completePendingQuestion(pending, "User selected option " + (index + 1) + ": " + selected);
                player.sendMessage(Text.empty().append(agentPrefix())
                        .append(renderAgentBody(null, "Selection received: `" + selected + "`.")), false);
            }
            case OTHER -> {
                String custom = response.value() == null ? "" : response.value().trim();
                if (custom.isBlank()) {
                    completePendingQuestion(pending, "SKIPPED: User submitted empty custom response.");
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Custom response was empty, treated as skip.")), false);
                    return;
                }
                completePendingQuestion(pending, "User provided custom response: " + custom);
                player.sendMessage(Text.empty().append(agentPrefix())
                        .append(renderAgentBody(null, "Custom response received.")), false);
            }
            case SKIP -> {
                String reason = response.value() == null ? "" : response.value().trim();
                if (reason.isBlank()) {
                    reason = "User skipped.";
                }
                completePendingQuestion(pending, "SKIPPED: " + reason);
                player.sendMessage(Text.empty().append(agentPrefix())
                        .append(renderAgentBody(null, "Skipped.")), false);
            }
        }
    }

    private boolean handlePendingOtherTextInput(Text message, ServerPlayerEntity sender) {
        if (sender == null) {
            return true;
        }
        PendingQuestion pending = PENDING_OTHER_TEXT_INPUT.get(sender.getUuid());
        if (pending == null) {
            return true;
        }
        String text = message == null ? "" : message.getString();
        if (text == null || text.isBlank()) {
            sender.sendMessage(Text.empty().append(agentPrefix())
                    .append(renderAgentBody(null, "Custom response cannot be empty. Type again or `/mineclawd choose cancel`.")), false);
            return false;
        }
        completePendingQuestion(pending, "User provided custom response: " + text.trim());
        sender.sendMessage(Text.empty().append(agentPrefix())
                .append(renderAgentBody(null, "Custom response received.")), false);
        return false;
    }

    private String buildQuestionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, QUESTION_ID_LENGTH).toLowerCase(Locale.ROOT);
    }

    private List<OpenAITool> openAiTools(boolean dynamicRegistryEnabled) {
        List<OpenAITool> tools = new ArrayList<>(List.of(
                new OpenAITool(
                        TOOL_ASK_USER,
                        "Ask the player a clarification question with up to five preset options. Use this when requirements are ambiguous.",
                        questionToolParameters()
                ),
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
                ),
                new OpenAITool(
                        TOOL_SYNC_COMMAND_TREE,
                        "Refresh command tree/tab-completion for all online players. Use only after command registration changes.",
                        noArgToolParameters()
                )
        ));
        if (dynamicRegistryEnabled) {
            tools.addAll(List.of(
                    new OpenAITool(
                            TOOL_LIST_DYNAMIC_CONTENT,
                            "List currently active dynamic placeholder entries and free slots.",
                            noArgToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_REGISTER_DYNAMIC_ITEM,
                            "Claim a free dynamic item placeholder slot.",
                            dynamicItemToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_REGISTER_DYNAMIC_BLOCK,
                            "Claim a free dynamic block placeholder slot.",
                            dynamicBlockToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_REGISTER_DYNAMIC_FLUID,
                            "Claim a free dynamic fluid placeholder slot.",
                            dynamicFluidToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_UPDATE_DYNAMIC_ITEM,
                            "Update properties for an existing dynamic item slot.",
                            dynamicItemUpdateToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_UPDATE_DYNAMIC_BLOCK,
                            "Update properties for an existing dynamic block slot.",
                            dynamicBlockUpdateToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_UPDATE_DYNAMIC_FLUID,
                            "Update properties for an existing dynamic fluid slot.",
                            dynamicFluidUpdateToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_UNREGISTER_DYNAMIC_CONTENT,
                            "Release a dynamic placeholder entry by type and slot.",
                            dynamicUnregisterToolParameters()
                    )
            ));
        }
        return List.copyOf(tools);
    }

    private List<VertexAIFunction> vertexTools(boolean dynamicRegistryEnabled) {
        List<VertexAIFunction> tools = new ArrayList<>(List.of(
                new VertexAIFunction(
                        TOOL_ASK_USER,
                        "Ask the player a clarification question with up to five preset options. Use this when requirements are ambiguous.",
                        questionToolParameters()
                ),
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
                ),
                new VertexAIFunction(
                        TOOL_SYNC_COMMAND_TREE,
                        "Refresh command tree/tab-completion for all online players. Use only after command registration changes.",
                        noArgToolParameters()
                )
        ));
        if (dynamicRegistryEnabled) {
            tools.addAll(List.of(
                    new VertexAIFunction(
                            TOOL_LIST_DYNAMIC_CONTENT,
                            "List currently active dynamic placeholder entries and free slots.",
                            noArgToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_REGISTER_DYNAMIC_ITEM,
                            "Claim a free dynamic item placeholder slot.",
                            dynamicItemToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_REGISTER_DYNAMIC_BLOCK,
                            "Claim a free dynamic block placeholder slot.",
                            dynamicBlockToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_REGISTER_DYNAMIC_FLUID,
                            "Claim a free dynamic fluid placeholder slot.",
                            dynamicFluidToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_UPDATE_DYNAMIC_ITEM,
                            "Update properties for an existing dynamic item slot.",
                            dynamicItemUpdateToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_UPDATE_DYNAMIC_BLOCK,
                            "Update properties for an existing dynamic block slot.",
                            dynamicBlockUpdateToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_UPDATE_DYNAMIC_FLUID,
                            "Update properties for an existing dynamic fluid slot.",
                            dynamicFluidUpdateToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_UNREGISTER_DYNAMIC_CONTENT,
                            "Release a dynamic placeholder entry by type and slot.",
                            dynamicUnregisterToolParameters()
                    )
            ));
        }
        return List.copyOf(tools);
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

    private JsonObject questionToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.addProperty("description", "Question for the player. Keep concise.");
        properties.add("question", question);

        JsonObject options = new JsonObject();
        options.addProperty("type", "array");
        options.addProperty("description", "Preset options (1-5 items). A built-in Other/Skip path is always available.");
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        options.add("items", item);
        options.addProperty("minItems", 1);
        options.addProperty("maxItems", MAX_QUESTION_OPTIONS);
        properties.add("options", options);

        return objectToolParameters(properties, "question", "options");
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

    private JsonObject dynamicItemToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Optional placeholder slot index (1-30). Omit to use first free slot.");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Display name for this dynamic item.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Vanilla material item id, e.g. minecraft:diamond. Choose one related to the requested feature.");
        properties.add("material_item", material);

        JsonObject throwable = new JsonObject();
        throwable.addProperty("type", "boolean");
        throwable.addProperty("description", "Whether this item should behave as a throwable projectile.");
        properties.add("throwable", throwable);

        return objectToolParameters(properties, "name");
    }

    private JsonObject dynamicBlockToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Optional placeholder slot index (1-30). Omit to use first free slot.");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Display name for this dynamic block.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Vanilla material block id, e.g. minecraft:stone. Choose one related to the requested feature.");
        properties.add("material_block", material);

        JsonObject friction = new JsonObject();
        friction.addProperty("type", "number");
        friction.addProperty("description", "Optional block friction/slipperiness (0.0 to 2.0).");
        properties.add("friction", friction);

        return objectToolParameters(properties, "name");
    }

    private JsonObject dynamicFluidToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Optional placeholder slot index (1-30). Omit to use first free slot.");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Display name for this dynamic fluid.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Vanilla material fluid id, e.g. minecraft:water. Choose one related to the requested feature.");
        properties.add("material_fluid", material);

        JsonObject color = new JsonObject();
        color.addProperty("type", "string");
        color.addProperty("description", "Optional custom pure color in #RRGGBB format. Use 'default' to clear.");
        properties.add("color", color);

        return objectToolParameters(properties, "name");
    }

    private JsonObject dynamicItemUpdateToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Optional updated display name.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Optional updated vanilla material item id; keep it related to requested behavior.");
        properties.add("material_item", material);

        JsonObject throwable = new JsonObject();
        throwable.addProperty("type", "boolean");
        throwable.addProperty("description", "Optional updated throwable behavior.");
        properties.add("throwable", throwable);

        return objectToolParameters(properties, "slot");
    }

    private JsonObject dynamicBlockUpdateToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Optional updated display name.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Optional updated vanilla material block id; keep it related to requested behavior.");
        properties.add("material_block", material);

        JsonObject friction = new JsonObject();
        friction.addProperty("type", "number");
        friction.addProperty("description", "Optional updated block friction/slipperiness (0.0 to 2.0).");
        properties.add("friction", friction);

        return objectToolParameters(properties, "slot");
    }

    private JsonObject dynamicFluidUpdateToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Optional updated display name.");
        properties.add("name", name);

        JsonObject material = new JsonObject();
        material.addProperty("type", "string");
        material.addProperty("description", "Optional updated vanilla material fluid id; keep it related to requested behavior.");
        properties.add("material_fluid", material);

        JsonObject color = new JsonObject();
        color.addProperty("type", "string");
        color.addProperty("description", "Optional updated pure color (#RRGGBB) or `default`.");
        properties.add("color", color);

        return objectToolParameters(properties, "slot");
    }

    private JsonObject dynamicUnregisterToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        type.addProperty("description", "Placeholder type: item, block, or fluid.");
        properties.add("type", type);

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        return objectToolParameters(properties, "type", "slot");
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

    private boolean normalizeVertexFunctionCallTurns(List<VertexAIMessage> history, AgentRuntime runtime) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        List<VertexAIMessage> normalized = new ArrayList<>();
        boolean changed = false;

        for (int index = 0; index < history.size();) {
            VertexAIMessage message = history.get(index);
            if (message == null) {
                changed = true;
                index++;
                continue;
            }

            int functionCallCount = countFunctionCallParts(message);
            if (functionCallCount <= 0) {
                normalized.add(message);
                index++;
                continue;
            }

            normalized.add(message);
            int scan = index + 1;
            int consumedResponseMessages = 0;
            List<JsonObject> collectedResponses = new ArrayList<>();
            while (scan < history.size()) {
                VertexAIMessage candidate = history.get(scan);
                if (!isFunctionResponseOnlyMessage(candidate)) {
                    break;
                }
                consumedResponseMessages++;
                for (JsonObject part : candidate.parts()) {
                    if (part != null && part.has("functionResponse") && part.get("functionResponse").isJsonObject()) {
                        collectedResponses.add(part.deepCopy());
                    }
                }
                scan++;
            }

            if (collectedResponses.size() < functionCallCount) {
                normalized.remove(normalized.size() - 1);
                changed = true;
                index = scan;
                continue;
            }

            List<JsonObject> merged = new ArrayList<>();
            for (int i = 0; i < functionCallCount; i++) {
                merged.add(collectedResponses.get(i));
            }
            normalized.add(new VertexAIMessage("user", merged));

            if (consumedResponseMessages != 1 || collectedResponses.size() != functionCallCount) {
                changed = true;
            }
            index = scan;
        }

        if (changed) {
            history.clear();
            history.addAll(normalized);
            debugLog(runtime, "Normalized Vertex function-call turns in session history.");
        }
        return changed;
    }

    private int countFunctionCallParts(VertexAIMessage message) {
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (JsonObject part : message.parts()) {
            if (part != null && part.has("functionCall") && part.get("functionCall").isJsonObject()) {
                count++;
            }
        }
        return count;
    }

    private boolean isFunctionResponseOnlyMessage(VertexAIMessage message) {
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            return false;
        }
        if (!"user".equals(message.role())) {
            return false;
        }
        for (JsonObject part : message.parts()) {
            if (part == null || !part.has("functionResponse") || !part.get("functionResponse").isJsonObject()) {
                return false;
            }
        }
        return true;
    }

    private String sessionOwnerKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuidAsString();
        }
        return source.getName();
    }

    private String buildSystemPrompt(
            MineClawdConfig config,
            String ownerKey,
            boolean dynamicRegistryEnabled,
            SessionData session
    ) {
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
        if (session != null) {
            prompt.append("\n\nSession context:\n")
                    .append("Current session id: ").append(session.id()).append("\n")
                    .append("Current session token: ").append(session.commandToken()).append("\n")
                    .append("For persistent callback scripts, prefer the stable session id when using `mineclawd.requestWithSession`.\n")
                    .append("If this session is removed later, callback requests using it will fail safely.");
        }
        if (dynamicRegistryEnabled) {
            prompt.append("\n\n")
                    .append(DYNAMIC_REGISTRY_PROMPT_APPENDIX);
        }
        return prompt.toString();
    }

    private String buildEnvironmentInfo() {
        String mc = "";
        try {
            mc = SharedConstants.getGameVersion().getName();
        } catch (Exception ignored) {
        }
        String loader = Platform.isFabric() ? modVersion("fabricloader") : modVersion("neoforge");
        String kubejs = modVersion("kubejs");
        String mineclawd = modVersion(MOD_ID);

        StringBuilder sb = new StringBuilder();
        if (!mc.isBlank()) {
            sb.append("Minecraft ").append(mc).append("\n");
        }
        if (!loader.isBlank()) {
            sb.append(Platform.isFabric() ? "Fabric Loader " : "NeoForge ")
                    .append(loader)
                    .append("\n");
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
        return Platform.getOptionalMod(id)
                .map(dev.architectury.platform.Mod::getVersion)
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
        sendAgentLine(source, renderAgentBody(source, markdown));
    }

    private void sendAgentLine(ServerCommandSource source, Text body) {
        if (source == null || body == null) {
            return;
        }
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

    private Text renderAgentBody(ServerCommandSource source, String markdown) {
        String normalized = normalizeMineDownActions(markdown);
        String input = normalized == null ? "" : normalized.trim();
        if (input.isBlank()) {
            return Text.empty();
        }
        try {
            Component parsed = MineDown.parse(input);
            String json = ADVENTURE_GSON.serialize(parsed);
            Text text = source == null
                    ? null
                    : Text.Serialization.fromJson(json, source.getRegistryManager());
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

    private String summarizeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = cursor.getClass().getSimpleName();
        }
        return message;
    }

    private boolean isVertexFunctionResponseMismatch(Throwable throwable) {
        String message = summarizeThrowable(throwable);
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("number of function response parts")
                && lower.contains("function call parts");
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

    private void handleLlmRequestFailure(
            ServerCommandSource source,
            SessionData session,
            AgentRuntime runtime,
            MineClawdConfig.LlmProvider provider,
            String rawError
    ) {
        if (runtime == null) {
            return;
        }
        rollbackFailedPrompt(session, runtime, provider);
        String errorMessage = sanitizeErrorMessage(rawError);
        if (session != null && runtime.interactiveErrorActions()) {
            FailedRequestContext failed = registerFailedRequest(runtime, provider);
            sendLlmErrorWithActions(source, failed, errorMessage);
        } else {
            sendAgentLine(source, Text.literal("Oops! " + errorMessage).formatted(Formatting.RED));
        }
        finishActiveRequest(runtime);
    }

    private FailedRequestContext registerFailedRequest(AgentRuntime runtime, MineClawdConfig.LlmProvider provider) {
        String token = UUID.randomUUID().toString().replace("-", "");
        if (token.length() > FAILED_REQUEST_TOKEN_LENGTH) {
            token = token.substring(0, FAILED_REQUEST_TOKEN_LENGTH);
        }
        FailedRequestContext context = new FailedRequestContext(
                token,
                runtime.ownerKey(),
                runtime.sessionId(),
                runtime.userRequest() == null ? "" : runtime.userRequest(),
                provider == null ? MineClawdConfig.LlmProvider.OPENAI : provider,
                System.currentTimeMillis()
        );
        FAILED_REQUESTS_BY_OWNER.put(runtime.ownerKey(), context);
        debugLog(runtime, "Stored failed request token=%s session=%s", context.token(), context.sessionId());
        return context;
    }

    private void rollbackFailedPrompt(
            SessionData session,
            AgentRuntime runtime,
            MineClawdConfig.LlmProvider provider
    ) {
        if (session == null || runtime == null || runtime.userRequest() == null || runtime.userRequest().isBlank()) {
            return;
        }

        boolean removed = provider == MineClawdConfig.LlmProvider.VERTEX_AI
                ? removeLastVertexUserPrompt(session.vertexHistory(), runtime.userRequest())
                : removeLastOpenAiUserPrompt(session.openAiHistory(), runtime.userRequest());
        if (!removed) {
            return;
        }

        session.touch();
        SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
        debugLog(runtime, "Removed failed prompt from session history after LLM request error.");
    }

    private boolean removeLastOpenAiUserPrompt(List<OpenAIMessage> history, String request) {
        if (history == null || history.isEmpty() || request == null) {
            return false;
        }
        String target = request.trim();
        for (int i = history.size() - 1; i >= 0; i--) {
            OpenAIMessage message = history.get(i);
            if (message == null || message.role() == null || !"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            String content = message.content() == null ? "" : message.content().trim();
            if (!content.equals(target)) {
                continue;
            }
            if (i != history.size() - 1) {
                return false;
            }
            history.remove(i);
            return true;
        }
        return false;
    }

    private boolean removeLastVertexUserPrompt(List<VertexAIMessage> history, String request) {
        if (history == null || history.isEmpty() || request == null) {
            return false;
        }
        String target = request.trim();
        for (int i = history.size() - 1; i >= 0; i--) {
            VertexAIMessage message = history.get(i);
            if (message == null || message.role() == null || !"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            String content = extractVertexText(message.parts());
            if (!content.equals(target)) {
                continue;
            }
            if (i != history.size() - 1) {
                return false;
            }
            history.remove(i);
            return true;
        }
        return false;
    }

    private void sendLlmErrorWithActions(
            ServerCommandSource source,
            FailedRequestContext failed,
            String errorMessage
    ) {
        if (source == null) {
            return;
        }
        sendAgentLine(source, Text.literal("Oops! " + errorMessage).formatted(Formatting.RED));
        if (failed == null) {
            return;
        }

        String retryCommand = "/mineclawd retry " + failed.token();
        String clipboardCommand = buildAdjustPromptCommand(failed.request());
        MutableText retry = Text.literal("[Retry]")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, retryCommand)));
        MutableText adjust = Text.literal("[Adjust Prompt]")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboardCommand)));
        MutableText body = Text.empty()
                .append(retry)
                .append(Text.literal(" "))
                .append(adjust)
                .append(Text.literal(" (copies `/mclawd ...`)").formatted(Formatting.GRAY));
        sendAgentLine(source, body);
    }

    private String buildAdjustPromptCommand(String request) {
        String normalized = request == null ? "" : request.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() > 32000) {
            normalized = normalized.substring(0, 32000).trim();
        }
        return "/mclawd " + normalized;
    }

    private String sanitizeErrorMessage(String raw) {
        String normalized = raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        if (normalized.isBlank()) {
            return "unknown error";
        }
        if (normalized.length() > 240) {
            return normalized.substring(0, 240).trim() + "...";
        }
        return normalized;
    }

    private List<HistoryEntry> collectVisibleHistoryEntries(SessionData session) {
        if (session == null) {
            return List.of();
        }
        int openAiVisibleCount = countOpenAiVisibleMessages(session.openAiHistory());
        int vertexVisibleCount = countVertexVisibleMessages(session.vertexHistory());
        if (openAiVisibleCount == 0 && vertexVisibleCount == 0) {
            return List.of();
        }
        if (openAiVisibleCount >= vertexVisibleCount) {
            return collectOpenAiHistoryEntries(session.openAiHistory());
        }
        return collectVertexHistoryEntries(session.vertexHistory());
    }

    private int countOpenAiVisibleMessages(List<OpenAIMessage> history) {
        int count = 0;
        if (history == null) {
            return count;
        }
        for (OpenAIMessage message : history) {
            if (message == null || message.role() == null || message.content() == null) {
                continue;
            }
            String role = message.role().toLowerCase(Locale.ROOT);
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (message.content().isBlank()) {
                continue;
            }
            count++;
        }
        return count;
    }

    private int countVertexVisibleMessages(List<VertexAIMessage> history) {
        int count = 0;
        if (history == null) {
            return count;
        }
        for (int i = 0; i < history.size(); i++) {
            VertexAIMessage message = history.get(i);
            if (message == null || message.role() == null) {
                continue;
            }
            String role = message.role().toLowerCase(Locale.ROOT);
            String content = extractVertexText(message.parts());
            if (i == 0 && "user".equals(role) && isLikelySystemPromptText(content)) {
                continue;
            }
            if (!"user".equals(role) && !"model".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (content.isBlank()) {
                continue;
            }
            count++;
        }
        return count;
    }

    private List<HistoryEntry> collectOpenAiHistoryEntries(List<OpenAIMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<HistoryEntry> entries = new ArrayList<>();
        for (OpenAIMessage message : history) {
            if (message == null || message.role() == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String role = message.role().toLowerCase(Locale.ROOT);
            if ("user".equals(role)) {
                appendHistoryEntry(entries, new HistoryEntry(false, message.content().trim()));
            } else if ("assistant".equals(role)) {
                appendHistoryEntry(entries, new HistoryEntry(true, message.content().trim()));
            }
        }
        return entries;
    }

    private List<HistoryEntry> collectVertexHistoryEntries(List<VertexAIMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            VertexAIMessage message = history.get(i);
            if (message == null || message.role() == null) {
                continue;
            }
            String content = extractVertexText(message.parts());
            if (content.isBlank()) {
                continue;
            }
            String role = message.role().toLowerCase(Locale.ROOT);
            if (i == 0 && "user".equals(role) && isLikelySystemPromptText(content)) {
                continue;
            }
            if ("user".equals(role)) {
                appendHistoryEntry(entries, new HistoryEntry(false, content));
            } else if ("model".equals(role) || "assistant".equals(role)) {
                appendHistoryEntry(entries, new HistoryEntry(true, content));
            }
        }
        return entries;
    }

    private void appendHistoryEntry(List<HistoryEntry> entries, HistoryEntry entry) {
        if (entries == null || entry == null) {
            return;
        }
        if (entries.size() >= HISTORY_MAX_ENTRIES) {
            entries.remove(0);
        }
        entries.add(entry);
    }

    private String extractVertexText(List<JsonObject> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonObject part : parts) {
            if (part == null || !part.has("text")) {
                continue;
            }
            String text;
            try {
                text = part.get("text").getAsString();
            } catch (Exception exception) {
                continue;
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.trim());
        }
        return sb.toString().trim();
    }

    private boolean isLikelySystemPromptText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("you are mineclawd")
                || lower.contains("tool overview:")
                || lower.contains("project and tool identity remains mineclawd")
                || lower.contains("persona context:");
    }

    private ItemStack buildHistoryBook(SessionData session, List<HistoryEntry> entries) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        List<RawFilteredPair<Text>> pages = new ArrayList<>();
        for (Text page : buildHistoryPages(session, entries)) {
            if (page != null) {
                pages.add(RawFilteredPair.of(page));
            }
        }
        if (pages.isEmpty()) {
            pages.add(RawFilteredPair.of(Text.literal("No history.")));
        }
        WrittenBookContentComponent content = new WrittenBookContentComponent(
                RawFilteredPair.of(HISTORY_BOOK_TITLE),
                "MineClawd",
                0,
                pages,
                true
        );
        book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, content);
        return book;
    }

    private List<Text> buildHistoryPages(SessionData session, List<HistoryEntry> entries) {
        List<Text> pages = new ArrayList<>();
        String updated = session == null
                ? "unknown"
                : SESSION_TIME_FORMAT.format(Instant.ofEpochMilli(session.updatedAtEpochMilli()));
        String token = session == null ? "unknown" : session.commandToken();
        MutableText titlePage = Text.empty()
                .append(Text.literal("MineClawd Session History\n").formatted(Formatting.GOLD, Formatting.BOLD))
                .append(Text.literal("Session: ").formatted(Formatting.GRAY))
                .append(Text.literal(token + "\n").formatted(Formatting.BLACK))
                .append(Text.literal("Updated: ").formatted(Formatting.GRAY))
                .append(Text.literal(updated + "\n").formatted(Formatting.BLACK))
                .append(Text.literal("Entries: ").formatted(Formatting.GRAY))
                .append(Text.literal(Integer.toString(entries == null ? 0 : entries.size())).formatted(Formatting.BLACK));
        pages.add(titlePage);

        if (entries == null || entries.isEmpty()) {
            pages.add(Text.literal("No visible chat messages yet.").formatted(Formatting.GRAY));
            return trimHistoryPages(pages);
        }

        MutableText currentPage = Text.empty();
        int currentSize = 0;
        for (HistoryEntry entry : entries) {
            Text section = toHistorySection(entry);
            int sectionLength = section.getString().length();
            if (currentSize > 0 && currentSize + sectionLength > HISTORY_PAGE_MAX_CHARS) {
                pages.add(currentPage);
                currentPage = Text.empty();
                currentSize = 0;
            }
            currentPage.append(section.copy());
            currentSize += sectionLength;
        }
        if (currentSize > 0) {
            pages.add(currentPage);
        }

        return trimHistoryPages(pages);
    }

    private List<Text> trimHistoryPages(List<Text> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of(Text.literal("No history."));
        }
        if (pages.size() <= WritableBookContentComponent.MAX_PAGE_COUNT) {
            return pages;
        }
        List<Text> trimmed = new ArrayList<>(pages.subList(0, WritableBookContentComponent.MAX_PAGE_COUNT - 1));
        trimmed.add(Text.literal("History truncated because it exceeds the maximum book page count.")
                .formatted(Formatting.RED));
        return trimmed;
    }

    private Text toHistorySection(HistoryEntry entry) {
        if (entry == null) {
            return Text.empty();
        }
        MutableText prefix = entry.assistant()
                ? Text.literal("MineClawd: ").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD)
                : Text.literal("You: ").formatted(Formatting.YELLOW, Formatting.BOLD);
        Text body = entry.assistant()
                ? renderAgentBody(null, entry.content())
                : Text.literal(entry.content());
        return Text.empty()
                .append(prefix)
                .append(body)
                .append(Text.literal("\n\n"));
    }

    private void sendHistoryBookToPlayer(
            ServerCommandSource source,
            ServerPlayerEntity player,
            SessionData session,
            List<HistoryEntry> entries
    ) {
        if (player == null) {
            return;
        }
        if (!canSendToClient(player, MineClawdNetworking.OPEN_HISTORY_BOOK)) {
            sendAgentMessage(source, "Client mod is required for `/mineclawd history` book UI.");
            return;
        }

        var registryLookup = player.getServerWorld().getRegistryManager();
        List<Text> pages = buildHistoryPages(session, entries);
        JsonArray pageArray = new JsonArray();
        for (Text page : pages) {
            String json = Text.Serialization.toJsonString(page == null ? Text.empty() : page, registryLookup);
            try {
                pageArray.add(JsonParser.parseString(json));
            } catch (Exception ignored) {
                pageArray.add(json);
            }
        }
        if (pageArray.isEmpty()) {
            String fallbackJson = Text.Serialization.toJsonString(Text.literal("No history."), registryLookup);
            try {
                pageArray.add(JsonParser.parseString(fallbackJson));
            } catch (Exception ignored) {
                pageArray.add(fallbackJson);
            }
        }
        JsonObject payloadObject = new JsonObject();
        payloadObject.add("pages", pageArray);
        String payloadString = payloadObject.toString();
        if (payloadString.length() > HISTORY_PACKET_MAX_CHARS) {
            payloadObject = new JsonObject();
            JsonArray fallbackPages = new JsonArray();
            String overflowJson = Text.Serialization.toJsonString(
                    Text.literal("History is too large to display in one transfer.")
                            .formatted(Formatting.RED),
                    registryLookup
            );
            try {
                fallbackPages.add(JsonParser.parseString(overflowJson));
            } catch (Exception ignored) {
                fallbackPages.add(overflowJson);
            }
            payloadObject.add("pages", fallbackPages);
            payloadString = payloadObject.toString();
        }

        var payload = new RegistryByteBuf(Unpooled.buffer(), registryLookup);
        payload.writeString(payloadString, HISTORY_PACKET_MAX_CHARS);
        NetworkManager.sendToPlayer(player, MineClawdNetworking.OPEN_HISTORY_BOOK, payload);
    }

    private record RequestOptions(
            boolean requireOp,
            boolean sessionBacked,
            String ownerKey,
            String sessionReference,
            boolean interactiveErrorActions
    ) {
        private static RequestOptions command() {
            return new RequestOptions(true, true, null, null, true);
        }

        private static RequestOptions sessionBound(String ownerKey, String sessionReference) {
            return new RequestOptions(false, true, ownerKey, sessionReference, true);
        }

        private static RequestOptions oneShot(String ownerKey) {
            return new RequestOptions(false, false, ownerKey, null, false);
        }
    }

    private record HistoryEntry(boolean assistant, String content) {
    }

    public static boolean canSendToClient(ServerPlayerEntity player, Identifier channel) {
        if (player == null || channel == null) {
            return false;
        }
        if (Boolean.TRUE.equals(CLIENT_MOD_READY.get(player.getUuid()))) {
            return true;
        }
        return NetworkManager.canPlayerReceive(player, channel);
    }

    private record FailedRequestContext(
            String token,
            String ownerKey,
            String sessionId,
            String request,
            MineClawdConfig.LlmProvider provider,
            long createdAtEpochMilli
    ) {
        private boolean matchesToken(String candidate) {
            return candidate != null && !candidate.isBlank() && token.equalsIgnoreCase(candidate.trim());
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - createdAtEpochMilli > FAILED_REQUEST_TTL_MS;
        }
    }

    private static final class PendingQuestion {
        private final String id;
        private final UUID playerUuid;
        private final String playerName;
        private final String question;
        private final List<String> options;
        private final long expiresAtEpochMillis;
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private PendingQuestion(
                String id,
                UUID playerUuid,
                String playerName,
                String question,
                List<String> options,
                long expiresAtEpochMillis
        ) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.playerName = playerName == null ? "" : playerName;
            this.question = question == null ? "" : question;
            this.options = options == null ? List.of() : List.copyOf(options);
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }

        private String id() {
            return id;
        }

        private UUID playerUuid() {
            return playerUuid;
        }

        private String playerName() {
            return playerName;
        }

        private String question() {
            return question;
        }

        private List<String> options() {
            return options;
        }

        private long expiresAtEpochMillis() {
            return expiresAtEpochMillis;
        }

        private CompletableFuture<String> future() {
            return future;
        }

        private boolean complete(String answer) {
            if (!completed.compareAndSet(false, true)) {
                return false;
            }
            future.complete(answer == null ? "SKIPPED: Empty answer." : answer);
            return true;
        }
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
            boolean dynamicRegistryEnabled,
            boolean debug,
            String requestId,
            String ownerKey,
            String sessionId,
            String userRequest,
            boolean sessionBacked,
            boolean interactiveErrorActions
    ) {
    }
}
