package com.mineclawd;

import de.themoep.minedown.adventure.MineDown;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.assets.AssetsManager;
import com.mineclawd.assets.AssetsManager.AssetCategory;
import com.mineclawd.assets.AssetsManager.AssetDraft;
import com.mineclawd.assets.AssetsManager.AssetRecord;
import com.mineclawd.assets.AssetsManager.UpsertResult;
import com.mineclawd.assets.AssetsOverlayPayload;
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
import com.mineclawd.mod.ModToolsExecutor;
import com.mineclawd.persona.PersonaManager;
import com.mineclawd.persona.PersonaManager.Persona;
import com.mineclawd.player.PlayerSettingsManager;
import com.mineclawd.player.PlayerSettingsManager.RequestBroadcastTarget;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionResponsePayload;
import com.mineclawd.session.SessionManager;
import com.mineclawd.session.SessionManager.SessionData;
import com.mineclawd.session.SessionManager.SessionSummary;
import com.mineclawd.session.SessionOverlayPayload;
import com.mineclawd.web.SearchToolExecutor;
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
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.SharedConstants;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private static final AssetsManager ASSETS_MANAGER = new AssetsManager();
    private static final PersonaManager PERSONA_MANAGER = new PersonaManager();
    private static final PlayerSettingsManager PLAYER_SETTINGS = new PlayerSettingsManager();
    private static final ConcurrentHashMap<String, String> ACTIVE_REQUESTS = new ConcurrentHashMap<>();
    private static final Set<String> CANCELLED_REQUEST_IDS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, CompletableFuture<?>> ACTIVE_NETWORK_REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FailedRequestContext> FAILED_REQUESTS_BY_OWNER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, PendingQuestion> PENDING_QUESTIONS_BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingQuestion> PENDING_QUESTIONS_BY_PLAYER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, PendingQuestion> PENDING_OTHER_TEXT_INPUT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> CLIENT_MOD_READY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> CLIENT_GUI_ENABLED = new ConcurrentHashMap<>();

    private static final String TOOL_APPLY_INSTANT_SERVER_SCRIPT = "apply-instant-server-script";
    private static final String TOOL_ASK_USER = "ask-user-question";
    private static final String TOOL_EXECUTE_COMMAND = "execute-command";
    private static final String TOOL_SEARCH = "search";
    private static final String TOOL_LIST_COMMANDS = "list_commands";
    private static final String TOOL_FETCH_MODRINTH = "fetch_modrinth";
    private static final String TOOL_FETCH_URL = "fetch_url";
    private static final String TOOL_LIST_SERVER_SCRIPTS = "list-server-scripts";
    private static final String TOOL_READ_SERVER_SCRIPT = "read-server-script";
    private static final String TOOL_WRITE_SERVER_SCRIPT = "write-server-script";
    private static final String TOOL_DELETE_SERVER_SCRIPT = "delete-server-script";
    private static final String TOOL_RELOAD_GAME = "reload-game";
    private static final String TOOL_SYNC_COMMAND_TREE = "sync-command-tree";
    private static final String TOOL_LIST_DYNAMIC_CONTENT = "list-dynamic-content";
    private static final String TOOL_LIST_DYNAMIC_PROPERTIES = "list-dynamic-properties";
    private static final String TOOL_REGISTER_DYNAMIC_ITEM = "register-dynamic-item";
    private static final String TOOL_REGISTER_DYNAMIC_BLOCK = "register-dynamic-block";
    private static final String TOOL_REGISTER_DYNAMIC_FLUID = "register-dynamic-fluid";
    private static final String TOOL_UPDATE_DYNAMIC_ITEM = "update-dynamic-item";
    private static final String TOOL_UPDATE_DYNAMIC_BLOCK = "update-dynamic-block";
    private static final String TOOL_UPDATE_DYNAMIC_FLUID = "update-dynamic-fluid";
    private static final String TOOL_UNREGISTER_DYNAMIC_CONTENT = "unregister-dynamic-content";
    private static final String TOOL_LIST_ASSETS = "list-assets";
    private static final String TOOL_UPSERT_ASSET_RECORD = "upsert-asset-record";
    private static final String TOOL_REMOVE_ASSET_RECORD = "remove-asset-record";
    private static final String LEGACY_TOOL_KUBEJS_EVAL = "kubejs_eval";
    private static final int TOOL_LIMIT_MIN = 1;
    private static final int TOOL_LIMIT_MAX = 20;
    private static final int MAX_REPEAT_TOOL_CALLS = 1;
    private static final int VERTEX_FUNCTION_RESPONSE_MISMATCH_RETRIES = 1;
    private static final int RATE_LIMIT_RETRIES = 2;
    private static final long RATE_LIMIT_BACKOFF_MS = 1500L;
    private static final int MAX_QUESTION_OPTIONS = 5;
    private static final String BUILT_IN_QUESTION_OTHER_OPTION = "Other";
    private static final String BUILT_IN_QUESTION_CUSTOM_INPUT_PROMPT =
            "Please type your custom answer in chat within 60 seconds, or use `/mineclawd choose cancel`.";
    private static final long QUESTION_TIMEOUT_SECONDS = 60L;
    private static final int QUESTION_ID_LENGTH = 8;
    private static final int HISTORY_PAGE_MAX_CHARS = 900;
    private static final int HISTORY_MAX_ENTRIES = 300;
    private static final int HISTORY_PACKET_MAX_CHARS = 262_144;
    private static final int SESSIONS_PACKET_MAX_CHARS = 262_144;
    private static final int ASSETS_PACKET_MAX_CHARS = 262_144;
    private static final int CONFIG_SYNC_PACKET_MAX_CHARS = 32_767;
    private static final int AGENT_STREAM_REQUEST_ID_MAX_CHARS = 64;
    private static final int AGENT_STREAM_PACKET_MAX_CHARS = 32_767;
    private static final int AGENT_STREAM_CHUNK_CHARS = 3_000;
    private static final int TOOL_STATUS_CHAT_MAX_CHARS = 180;
    private static final int TRACE_LOG_MAX_CHARS = 12_000;
    private static final int FAILED_REQUEST_TOKEN_LENGTH = 8;
    private static final long FAILED_REQUEST_TTL_MS = TimeUnit.MINUTES.toMillis(30);
    private static final String HISTORY_BOOK_TITLE = "MineClawd History";
    private static final String GLOBAL_ASSET_OWNER_KEY = "global";
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
            "tavily-api-key",
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
        "  Do not include `Other` or `Skip` inside `options`; MineClawd appends a built-in `Other` option and handles skip separately.",
        "- `execute-command`: Execute a normal Minecraft command and get command output.",
        "  Prefer this when vanilla commands can solve the task directly (for example: `gamerule`, `time`, `weather`, `tp`, `effect`, `give`, `clear`, `kill`, `summon`, `setblock`, `fill`, `say`, simple checks).",
        "  If command output is enough, do not use KubeJS.",
        "- `list_commands`: List available root commands, optionally filtered by `mod_id`.",
        "  Filtered command matching is best-effort based on command names and prefixes.",
        "- Installed mods are provided in the system prompt under Environment/Installed Mods. Use those ids directly.",
        "- `fetch_modrinth`: Fetch the Modrinth project page content for an installed mod id.",
        "- `fetch_url`: Fetch any HTTP(S) page/content by URL. HTML responses are converted to Markdown.",
        "  Use these tools when you need command usage, config keys, APIs, or behavior details from other mods.",
        "- `search`: Search the web via Tavily and return concise source snippets (available only when configured).",
        "  Use this when external references are needed beyond installed-mod docs.",
        "- `apply-instant-server-script`: Execute immediate KubeJS JavaScript on the running server via /_exec_kubejs_internal.",
        "  Use this for instant actions such as checking or editing player inventory, changing nearby blocks, querying entities, and all the one-off server operations. (This is the most commonly used tool.)",
        "When details are uncertain (for example exact KubeJS syntax, other-mod command usage, or config keys), do not guess.",
        "First verify by using `fetch_modrinth` / `fetch_url` and `search` when available.",
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
            "- `list-dynamic-properties`: list editable property keys/ranges for item/block/fluid. Use this before advanced edits to avoid wrong params.",
            "- `register-dynamic-item`: claim a free item slot with broad item behavior options.",
            "- `register-dynamic-block`: claim a free block slot with broad physical/sound options.",
            "- `register-dynamic-fluid`: claim a free fluid slot with broad flow/physics/color options.",
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
            "7. For advanced behavior beyond provided properties, combine this with KubeJS scripts. However, always prefer native dynamic registry properties when possible."
    );
    private static final String ASSET_TRACKING_PROMPT_APPENDIX = String.join("\n",
            "Asset tracking workflow:",
            "Use persistent asset records so future sessions can continue previous work without losing references.",
            "Use tools:",
            "- `list-assets`: inspect all currently tracked assets.",
            "- `upsert-asset-record`: create or update an asset record whenever you create/update/remove entities, dynamic content, special items, commands, or game mechanics.",
            "- `remove-asset-record`: remove a stale asset record when the thing no longer exists.",
            "Asset categories:",
            "- `entities`: include `entity_uuid`. Add `entity_dimension`, `entity_x`, `entity_y`, `entity_z` when known.",
            "- `items_blocks_fluids`: include `content_id` (for example `mineclawd:dynamic_item_001`).",
            "- `special_items`: include `special_item_id` and `special_item_nbt` if available.",
            "- `commands`: include command text in `command` and script path if scripted.",
            "- `game_mechanics`: include clear `summary`, `details`, and script path when applicable.",
            "Common optional fields for any category: `summary` and `script_path`."
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
                    CLIENT_MOD_READY.remove(player.getUuid());
                    CLIENT_GUI_ENABLED.remove(player.getUuid());
                    DynamicContentRegistry.loadPersistentState(server);
                    instance.sendBroadcastTargetSync(player);
                    instance.sendAssistiveTouchSync(player);
                    DynamicContentRegistry.syncToPlayer(player);
                });
        });
        PlayerEvent.PLAYER_QUIT.register(player -> {
            CLIENT_MOD_READY.remove(player.getUuid());
            CLIENT_GUI_ENABLED.remove(player.getUuid());
        });
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.CLIENT_READY,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    boolean guiEnabled = true;
                    if (buf.isReadable()) {
                        guiEnabled = buf.readBoolean();
                    }
                    boolean finalGuiEnabled = guiEnabled;
                    server.execute(() -> {
                        CLIENT_MOD_READY.put(player.getUuid(), Boolean.TRUE);
                        CLIENT_GUI_ENABLED.put(player.getUuid(), finalGuiEnabled);
                        LOGGER.info("[MineClawd] Client mod ready: player={} gui={}", player.getName().getString(), finalGuiEnabled);
                        instance.sendBroadcastTargetSync(player);
                        instance.sendAssistiveTouchSync(player);
                        DynamicContentRegistry.syncToPlayer(player);
                    });
                });
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.CLIENT_GUI_PREF,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    boolean guiEnabled = true;
                    if (buf.isReadable()) {
                        guiEnabled = buf.readBoolean();
                    }
                    boolean finalGuiEnabled = guiEnabled;
                    server.execute(() -> {
                        CLIENT_GUI_ENABLED.put(player.getUuid(), finalGuiEnabled);
                        if (finalGuiEnabled) {
                            instance.sendBroadcastTargetSync(player);
                            instance.sendAssistiveTouchSync(player);
                        }
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
                .then(CommandManager.literal("assets")
                        .executes(context -> listAssets(context.getSource()))
                        .then(CommandManager.literal("list")
                                .executes(context -> listAssets(context.getSource())))
                        .then(CommandManager.literal("teleport")
                                .then(CommandManager.argument("asset", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestAssetReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String assetRef = StringArgumentType.getString(context, "asset");
                                            return teleportToAsset(context.getSource(), assetRef);
                                        })))
                        .then(CommandManager.literal("give")
                                .then(CommandManager.argument("asset", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestAssetReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String assetRef = StringArgumentType.getString(context, "asset");
                                            return giveAssetToPlayer(context.getSource(), assetRef);
                                        })))
                        .then(CommandManager.literal("remove-record")
                                .then(CommandManager.argument("asset", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestAssetReference(context.getSource(), builder))
                                        .executes(context -> {
                                            String assetRef = StringArgumentType.getString(context, "asset");
                                            return removeAssetRecord(context.getSource(), assetRef);
                                        }))))
                .then(CommandManager.literal("history")
                        .executes(context -> showCurrentSessionHistory(context.getSource())))
                .then(CommandManager.literal("new")
                        .executes(context -> createNewSession(context.getSource())))
                .then(CommandManager.literal("stop")
                        .executes(context -> stopActiveRequest(context.getSource())))
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
                .then(CommandManager.literal("assistivetouch")
                        .executes(context -> setAssistiveTouch(context.getSource(), null))
                        .then(CommandManager.argument("state", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(List.of("toggle", "on", "off", "true", "false"), builder))
                                .executes(context -> {
                                    String state = StringArgumentType.getString(context, "state");
                                    return setAssistiveTouch(context.getSource(), state);
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
        if (!canUseGui(player, MineClawdNetworking.OPEN_CONFIG)) {
            sendAgentMessage(source, "Client GUI unavailable (client mod missing or `Enable GUI` is off).");
            sendAgentMessage(source, "You can still configure from server commands: `/mineclawd config <key> <value>`.");
            sendAgentMessage(source, "Available keys: `" + String.join("`, `", CONFIG_KEYS) + "`.");
            return 1;
        }
        RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeString(target.commandValue(), 64);
        payload.writeString(buildConfigSyncPayload(), CONFIG_SYNC_PACKET_MAX_CHARS);
        if (!sendPacketToPlayer(player, MineClawdNetworking.OPEN_CONFIG, payload, "open_config")) {
            sendAgentMessage(source, "Failed to open config GUI due to a network sync error.");
            sendAgentMessage(source, "You can still configure from server commands: `/mineclawd config <key> <value>`.");
            sendAgentMessage(source, "Available keys: `" + String.join("`, `", CONFIG_KEYS) + "`.");
        }
        return 1;
    }

    private String buildConfigSyncPayload() {
        MineClawdConfig config = MineClawdConfig.get();
        if (config == null) {
            config = MineClawdConfig.HANDLER.defaults();
        }
        JsonObject root = new JsonObject();
        MineClawdConfig.LlmProvider provider = config.provider == null
                ? MineClawdConfig.LlmProvider.OPENAI
                : config.provider;
        root.addProperty("provider", provider == MineClawdConfig.LlmProvider.OPENAI ? "openai" : "vertex-ai");
        root.addProperty("endpoint", config.endpoint == null ? "" : config.endpoint);
        root.addProperty("apiKey", config.apiKey == null ? "" : config.apiKey);
        root.addProperty("tavilyApiKey", config.tavilyApiKey == null ? "" : config.tavilyApiKey);
        root.addProperty("model", config.model == null ? "" : config.model);
        root.addProperty("summarizeModel", config.summarizeModel == null ? "" : config.summarizeModel);
        root.addProperty("vertexEndpoint", config.vertexEndpoint == null ? "" : config.vertexEndpoint);
        root.addProperty("vertexApiKey", config.vertexApiKey == null ? "" : config.vertexApiKey);
        root.addProperty("vertexModel", config.vertexModel == null ? "" : config.vertexModel);
        root.addProperty("vertexSummarizeModel", config.vertexSummarizeModel == null ? "" : config.vertexSummarizeModel);
        root.addProperty("debugMode", config.debugMode);
        root.addProperty("limitToolCalls", config.limitToolCalls);
        root.addProperty("toolCallLimit", Math.max(TOOL_LIMIT_MIN, Math.min(TOOL_LIMIT_MAX, config.toolCallLimit)));
        root.addProperty("systemPrompt", config.systemPrompt == null ? "" : config.systemPrompt);
        MineClawdConfig.DynamicRegistryMode mode = config.dynamicRegistryMode == null
                ? MineClawdConfig.DynamicRegistryMode.AUTO
                : config.dynamicRegistryMode;
        root.addProperty("dynamicRegistryMode", mode.name().toLowerCase(Locale.ROOT));
        String payload = root.toString();
        if (payload.length() <= CONFIG_SYNC_PACKET_MAX_CHARS) {
            return payload;
        }
        String systemPrompt = config.systemPrompt == null ? "" : config.systemPrompt;
        if (systemPrompt.length() > 4096) {
            root.addProperty("systemPrompt", systemPrompt.substring(0, 4096));
            payload = root.toString();
            if (payload.length() <= CONFIG_SYNC_PACKET_MAX_CHARS) {
                return payload;
            }
        }
        root.addProperty("systemPrompt", "");
        payload = root.toString();
        if (payload.length() <= CONFIG_SYNC_PACKET_MAX_CHARS) {
            return payload;
        }
        return "{}";
    }

    private void sendBroadcastTargetSync(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        if (!canUseGui(player, MineClawdNetworking.SYNC_BROADCAST_TARGET)) {
            return;
        }
        RequestBroadcastTarget target = PLAYER_SETTINGS.getRequestBroadcastTarget(player.getUuidAsString());
        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeString(target.commandValue());
        sendPacketToPlayer(player, MineClawdNetworking.SYNC_BROADCAST_TARGET, payload, "sync_broadcast_target");
    }

    private void sendAssistiveTouchSync(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        if (!canUseGui(player, MineClawdNetworking.SYNC_ASSISTIVE_TOUCH)) {
            return;
        }
        boolean enabled = PLAYER_SETTINGS.isAssistiveTouchEnabled(player.getUuidAsString());
        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeBoolean(enabled);
        sendPacketToPlayer(player, MineClawdNetworking.SYNC_ASSISTIVE_TOUCH, payload, "sync_assistive_touch");
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
            case "tavily-api-key" -> maskSecret(config.tavilyApiKey);
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
        String normalizedRawValue = rawValue == null ? "" : rawValue;
        String value = normalizedRawValue.trim();
        if ("\"\"".equals(value) || "''".equals(value)) {
            normalizedRawValue = "";
            value = "";
        }

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
            case "tavily-api-key" -> config.tavilyApiKey = value;
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
                    config.systemPrompt = normalizedRawValue;
                }
            }
            default -> {
                source.sendError(Text.literal("MineClawd: unsupported config key."));
                return 0;
            }
        }

        MineClawdConfig.HANDLER.save();
        String shown = switch (key) {
            case "api-key", "vertex-api-key", "tavily-api-key" -> maskSecret(value);
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
            case "tavily-api-key", "tavily-key", "search-api-key", "web-search-api-key" -> "tavily-api-key";
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
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
            sendSessionsOverlayToPlayer(source, player, false, session.id());
            return 1;
        }
        sendAgentMessage(source, "Started new session `" + session.commandToken() + "`.");
        return 1;
    }

    private int stopActiveRequest(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        String ownerKey = sessionOwnerKey(source);
        String requestId = ACTIVE_REQUESTS.remove(ownerKey);
        if (requestId == null || requestId.isBlank()) {
            sendAgentMessage(source, "No running request to stop.");
            return 0;
        }

        CANCELLED_REQUEST_IDS.add(requestId);
        CompletableFuture<?> inFlight = ACTIVE_NETWORK_REQUESTS.remove(requestId);
        if (inFlight != null) {
            inFlight.cancel(true);
        }

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            PendingQuestion pending = PENDING_QUESTIONS_BY_PLAYER.remove(player.getUuid());
            if (pending != null) {
                PENDING_QUESTIONS_BY_ID.remove(pending.id(), pending);
                completePendingQuestion(pending, "SKIPPED: Request was stopped by user.");
            }
        }

        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.AGENT_STREAM_EVENT)) {
            sendAgentStreamPacket(player, requestId, AgentStreamEventType.TOOL_STATUS_CLEAR, "");
            sendAgentStreamPacket(player, requestId, AgentStreamEventType.ERROR, "Generation stopped by user.");
            sendAgentStreamPacket(player, requestId, AgentStreamEventType.DONE, "");
        }

        sendTaskStatus(source, false);
        sendAgentMessage(source, "Stopped the running request.");
        return 1;
    }

    private int listSessions(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
            sendSessionsOverlayToPlayer(source, player, true, null);
            return 1;
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

    private int listAssets(ServerCommandSource source) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_ASSETS)) {
            sendAssetsOverlayToPlayer(source, player, true);
            return 1;
        }

        List<AssetRecord> assets = ASSETS_MANAGER.list(assetOwnerKey());
        if (assets.isEmpty()) {
            sendAgentMessage(source, "No assets tracked yet. Agent should use `upsert-asset-record` after creating content.");
            return 1;
        }

        sendAgentMessage(source, "Assets (`" + assets.size() + "` total):");
        for (AssetRecord asset : assets) {
            if (asset == null) {
                continue;
            }
            String summary = asset.summary().isBlank() ? "" : " - " + asset.summary();
            sendAgentMessage(
                    source,
                    "`" + asset.id() + "` [" + asset.category().displayName() + "] " + asset.name() + summary
            );
        }
        sendAgentMessage(source, "Actions: `/mineclawd assets teleport <asset>`, `/mineclawd assets give <asset>`, `/mineclawd assets remove-record <asset>`.");
        return 1;
    }

    private void sendAssetsOverlayToPlayer(ServerCommandSource source, ServerPlayerEntity player, boolean openUi) {
        if (source == null || player == null) {
            return;
        }
        if (!canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_ASSETS)) {
            return;
        }

        String requestOwnerKey = sessionOwnerKey(source);
        List<AssetRecord> assets = ASSETS_MANAGER.list(assetOwnerKey());
        List<AssetsOverlayPayload.AssetItem> payloadAssets = new ArrayList<>();
        for (AssetRecord asset : assets) {
            if (asset == null) {
                continue;
            }
            payloadAssets.add(new AssetsOverlayPayload.AssetItem(
                    asset.id(),
                    asset.category().id(),
                    asset.name(),
                    asset.summary(),
                    asset.scriptPath(),
                    asset.details(),
                    asset.contentId(),
                    asset.specialItemId(),
                    asset.specialItemNbt(),
                    asset.command(),
                    asset.entityUuid(),
                    asset.entityDimension(),
                    asset.entityX(),
                    asset.entityY(),
                    asset.entityZ(),
                    asset.sessionId(),
                    asset.updatedAtEpochMilli()
            ));
        }

        SessionData activeSession = SESSION_MANAGER.loadActiveSession(requestOwnerKey);
        String activeSessionId = activeSession == null ? "" : activeSession.id();
        String activePersona = PERSONA_MANAGER.getActiveSoulName(requestOwnerKey);
        List<String> personas = PERSONA_MANAGER.listSoulNames();

        String payloadString = buildAssetsOverlayPayloadJson(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                payloadAssets
        );
        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeString(payloadString, ASSETS_PACKET_MAX_CHARS);
        if (!sendPacketToPlayer(player, MineClawdNetworking.OPEN_ASSETS, payload, "open_assets")) {
            sendAgentMessage(source, "Assets overlay packet failed to send; falling back to chat/list commands.");
        }
    }

    private String buildAssetsOverlayPayloadJson(
            boolean openUi,
            String activeSessionId,
            String activePersona,
            List<String> personas,
            List<AssetsOverlayPayload.AssetItem> assets
    ) {
        List<AssetsOverlayPayload.AssetItem> mutableAssets = new ArrayList<>(assets == null ? List.of() : assets);
        String payloadString = new AssetsOverlayPayload(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                mutableAssets
        ).toJson();
        while (payloadString.length() > ASSETS_PACKET_MAX_CHARS && !mutableAssets.isEmpty()) {
            mutableAssets.remove(mutableAssets.size() - 1);
            payloadString = new AssetsOverlayPayload(
                    openUi,
                    activeSessionId,
                    activePersona,
                    personas,
                    mutableAssets
            ).toJson();
        }
        if (payloadString.length() <= ASSETS_PACKET_MAX_CHARS) {
            return payloadString;
        }
        return new AssetsOverlayPayload(
                openUi,
                activeSessionId,
                activePersona,
                List.of(),
                List.of()
        ).toJson();
    }

    private CompletableFuture<Suggestions> suggestAssetReference(ServerCommandSource source, SuggestionsBuilder builder) {
        if (source == null || !isOp(source)) {
            return Suggestions.empty();
        }
        List<AssetRecord> assets = ASSETS_MANAGER.list(assetOwnerKey());
        if (assets.isEmpty()) {
            return Suggestions.empty();
        }
        List<String> candidates = new ArrayList<>(assets.size() * 2);
        for (AssetRecord asset : assets) {
            if (asset == null) {
                continue;
            }
            if (!asset.id().isBlank()) {
                candidates.add(asset.id());
            }
            if (!asset.name().isBlank()) {
                candidates.add(asset.name());
            }
        }
        return CommandSource.suggestMatching(candidates, builder);
    }

    private int removeAssetRecord(ServerCommandSource source, String reference) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (reference == null || reference.isBlank()) {
            source.sendError(Text.literal("MineClawd: asset reference is required."));
            return 0;
        }
        String ownerKey = assetOwnerKey();
        AssetRecord record = ASSETS_MANAGER.resolve(ownerKey, reference);
        if (record == null) {
            source.sendError(Text.literal("MineClawd: asset not found. Use `/mineclawd assets` to inspect ids."));
            return 0;
        }
        if (!ASSETS_MANAGER.remove(ownerKey, record.id())) {
            source.sendError(Text.literal("MineClawd: failed to remove asset record."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_ASSETS)) {
            sendAssetsOverlayToPlayer(source, player, true);
        } else {
            sendAgentMessage(source, "Removed asset record `" + record.id() + "`.");
        }
        return 1;
    }

    private int teleportToAsset(ServerCommandSource source, String reference) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }
        String ownerKey = assetOwnerKey();
        AssetRecord record = ASSETS_MANAGER.resolve(ownerKey, reference);
        if (record == null) {
            source.sendError(Text.literal("MineClawd: asset not found. Use `/mineclawd assets` to inspect ids."));
            return 0;
        }
        if (record.category() != AssetCategory.ENTITIES) {
            source.sendError(Text.literal("MineClawd: teleport is only available for `Entities` assets."));
            return 0;
        }
        if (record.entityUuid().isBlank()) {
            source.sendError(Text.literal("MineClawd: this asset does not have a valid `entity_uuid`."));
            return 0;
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(record.entityUuid());
        } catch (Exception exception) {
            source.sendError(Text.literal("MineClawd: invalid entity UUID stored in asset record."));
            return 0;
        }

        Entity target = null;
        ServerWorld targetWorld = null;
        if (source.getServer() != null) {
            for (ServerWorld world : source.getServer().getWorlds()) {
                Entity found = world.getEntity(uuid);
                if (found != null) {
                    target = found;
                    targetWorld = world;
                    break;
                }
            }
        }

        if (target == null || targetWorld == null) {
            source.sendError(Text.literal("MineClawd: entity `" + record.entityUuid() + "` was not found in loaded worlds."));
            return 0;
        }

        String worldId = targetWorld.getRegistryKey().getValue().toString();
        String command = String.format(
                Locale.ROOT,
                "execute in %s run tp %s %.3f %.3f %.3f",
                worldId,
                player.getName().getString(),
                target.getX(),
                target.getY(),
                target.getZ()
        );
        ToolExecutionResult result = KubeJsToolExecutor.executeCommand(source, command);
        if (!result.success()) {
            source.sendError(Text.literal("MineClawd: teleport failed. " + result.output()));
            return 0;
        }

        UpsertResult upsert = ASSETS_MANAGER.upsert(ownerKey, new AssetDraft(
                record.id(),
                record.category().id(),
                record.name(),
                record.summary(),
                record.scriptPath(),
                record.details(),
                record.contentId(),
                record.specialItemId(),
                record.specialItemNbt(),
                record.command(),
                record.entityUuid(),
                worldId,
                target.getX(),
                target.getY(),
                target.getZ(),
                record.sessionId()
        ));
        if (!upsert.success()) {
            LOGGER.debug("Failed to refresh entity position for asset {}: {}", record.id(), upsert.message());
        }

        sendAgentMessage(source, "Teleported to `" + record.name() + "` (`" + record.id() + "`).");
        return 1;
    }

    private int giveAssetToPlayer(ServerCommandSource source, String reference) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }
        String ownerKey = assetOwnerKey();
        AssetRecord record = ASSETS_MANAGER.resolve(ownerKey, reference);
        if (record == null) {
            source.sendError(Text.literal("MineClawd: asset not found. Use `/mineclawd assets` to inspect ids."));
            return 0;
        }

        String itemId;
        String itemSuffix = "";
        if (record.category() == AssetCategory.ITEMS_BLOCKS_FLUIDS) {
            itemId = resolveGiveItemId(record.contentId());
            if (itemId.isBlank()) {
                source.sendError(Text.literal("MineClawd: this asset does not have a valid `content_id` item."));
                return 0;
            }
        } else if (record.category() == AssetCategory.SPECIAL_ITEMS) {
            itemId = resolveGiveItemId(record.specialItemId());
            if (itemId.isBlank()) {
                source.sendError(Text.literal("MineClawd: this special item asset does not have a valid `special_item_id`."));
                return 0;
            }
            itemSuffix = record.specialItemNbt();
        } else {
            source.sendError(Text.literal("MineClawd: give is only available for `Items/Blocks/Fluids` or `Special Items` assets."));
            return 0;
        }

        ToolExecutionResult result = runGiveItemCommand(source, player, itemId, itemSuffix);
        if (!result.success()) {
            source.sendError(Text.literal("MineClawd: give failed. " + result.output()));
            return 0;
        }
        sendAgentMessage(source, "Gave `" + record.name() + "` to `" + player.getName().getString() + "`.");
        return 1;
    }

    private ToolExecutionResult runGiveItemCommand(
            ServerCommandSource source,
            ServerPlayerEntity player,
            String itemId,
            String suffix
    ) {
        if (source == null || player == null || itemId == null || itemId.isBlank()) {
            return new ToolExecutionResult(false, "invalid give command input");
        }
        String playerName = player.getName().getString();
        String safeSuffix = suffix == null ? "" : suffix.trim();
        if (!safeSuffix.isBlank()) {
            ToolExecutionResult withSuffix = KubeJsToolExecutor.executeCommand(
                    source,
                    "give " + playerName + " " + itemId + safeSuffix + " 1"
            );
            if (withSuffix.success()) {
                return withSuffix;
            }
        }
        return KubeJsToolExecutor.executeCommand(source, "give " + playerName + " " + itemId + " 1");
    }

    private String resolveGiveItemId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "";
        }
        String normalized = rawId.trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(normalized);
        if (id == null && !normalized.contains(":")) {
            Identifier mineclawdId = Identifier.tryParse(MOD_ID + ":" + normalized);
            if (mineclawdId != null && Registries.ITEM.containsId(mineclawdId)) {
                id = mineclawdId;
            }
            if (id == null) {
                Identifier vanillaId = Identifier.tryParse("minecraft:" + normalized);
                if (vanillaId != null && Registries.ITEM.containsId(vanillaId)) {
                    id = vanillaId;
                }
            }
        }
        if (id != null && Registries.ITEM.containsId(id)) {
            return id.toString();
        }
        if (id == null || !MOD_ID.equals(id.getNamespace())) {
            return "";
        }
        String path = id.getPath();
        if (!path.startsWith("dynamic_fluid_") || path.startsWith("dynamic_fluid_bucket_")) {
            return "";
        }
        String suffix = path.substring("dynamic_fluid_".length());
        Identifier bucket = Identifier.tryParse(MOD_ID + ":dynamic_fluid_bucket_" + suffix);
        if (bucket != null && Registries.ITEM.containsId(bucket)) {
            return bucket.toString();
        }
        return "";
    }

    private void sendSessionsOverlayToPlayer(
            ServerCommandSource source,
            ServerPlayerEntity player,
            boolean openUi,
            String preferredSessionRef
    ) {
        if (source == null || player == null) {
            return;
        }
        if (!canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
            return;
        }

        String ownerKey = sessionOwnerKey(source);
        List<SessionSummary> summaries = SESSION_MANAGER.listSessions(ownerKey);
        SessionData activeSession = SESSION_MANAGER.loadActiveSession(ownerKey);
        SessionData selectedSession = preferredSessionRef == null || preferredSessionRef.isBlank()
                ? activeSession
                : SESSION_MANAGER.resolve(ownerKey, preferredSessionRef);
        if (selectedSession == null) {
            selectedSession = activeSession;
        }
        if (selectedSession == null && !summaries.isEmpty()) {
            selectedSession = SESSION_MANAGER.resolve(ownerKey, summaries.get(0).id());
        }

        List<SessionOverlayPayload.SessionItem> sessionItems = new ArrayList<>();
        for (SessionSummary summary : summaries) {
            if (summary == null) {
                continue;
            }
            sessionItems.add(new SessionOverlayPayload.SessionItem(
                    summary.id(),
                    summary.title(),
                    summary.token(),
                    summary.updatedAtEpochMilli(),
                    summary.active()
            ));
        }

        List<SessionOverlayPayload.HistoryItem> historyItems = new ArrayList<>();
        for (HistoryEntry entry : collectVisibleHistoryEntries(selectedSession)) {
            if (entry == null || entry.content() == null || entry.content().isBlank()) {
                continue;
            }
            historyItems.add(new SessionOverlayPayload.HistoryItem(entry.assistant(), entry.content()));
        }

        String activeSessionId = selectedSession == null ? "" : selectedSession.id();
        String activePersona = PERSONA_MANAGER.getActiveSoulName(ownerKey);
        List<String> personas = PERSONA_MANAGER.listSoulNames();
        String payloadString = buildSessionsOverlayPayloadJson(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                sessionItems,
                historyItems
        );

        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeString(payloadString, SESSIONS_PACKET_MAX_CHARS);
        if (!sendPacketToPlayer(player, MineClawdNetworking.OPEN_SESSIONS, payload, "open_sessions")) {
            sendAgentMessage(source, "Sessions overlay packet failed to send; use `/mineclawd sessions list` as fallback.");
        }
    }

    private String buildSessionsOverlayPayloadJson(
            boolean openUi,
            String activeSessionId,
            String activePersona,
            List<String> personas,
            List<SessionOverlayPayload.SessionItem> sessionItems,
            List<SessionOverlayPayload.HistoryItem> historyItems
    ) {
        List<SessionOverlayPayload.HistoryItem> mutableHistory = new ArrayList<>(
                historyItems == null ? List.of() : historyItems
        );
        String payloadString = new SessionOverlayPayload(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                sessionItems,
                mutableHistory
        ).toJson();
        while (payloadString.length() > SESSIONS_PACKET_MAX_CHARS && mutableHistory.size() > 1) {
            mutableHistory.remove(0);
            payloadString = new SessionOverlayPayload(
                    openUi,
                    activeSessionId,
                    activePersona,
                    personas,
                    sessionItems,
                    mutableHistory
            ).toJson();
        }
        if (payloadString.length() <= SESSIONS_PACKET_MAX_CHARS) {
            return payloadString;
        }
        payloadString = new SessionOverlayPayload(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                sessionItems,
                List.of(new SessionOverlayPayload.HistoryItem(true, "History is too large to transfer in one payload."))
        ).toJson();
        if (payloadString.length() <= SESSIONS_PACKET_MAX_CHARS) {
            return payloadString;
        }
        return new SessionOverlayPayload(
                openUi,
                activeSessionId,
                activePersona,
                List.of(),
                List.of(),
                List.of()
        ).toJson();
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
            SessionData requested = SESSION_MANAGER.resolve(ownerKey, sessionRef);
            if (requested == null) {
                source.sendError(Text.literal("MineClawd: session not found. Use /mineclawd sessions list."));
                return 0;
            }
            SessionData active = SESSION_MANAGER.loadActiveSession(ownerKey);
            if (active == null || !active.id().equalsIgnoreCase(requested.id())) {
                source.sendError(Text.literal("MineClawd: cannot switch sessions while a request is running."));
                return 0;
            }
            if (source.getEntity() instanceof ServerPlayerEntity player
                    && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
                sendSessionsOverlayToPlayer(source, player, false, requested.id());
                return 1;
            }
            sendAgentMessage(source, "MineClawd is still running in this session.");
            return 1;
        }

        SessionData session = SESSION_MANAGER.resumeSession(ownerKey, sessionRef);
        if (session == null) {
            source.sendError(Text.literal("MineClawd: session not found. Use /mineclawd sessions list."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
            sendSessionsOverlayToPlayer(source, player, false, session.id());
            return 1;
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
            sendAgentMessage(source, BUILT_IN_QUESTION_CUSTOM_INPUT_PROMPT);
            return 1;
        }

        int optionIndex = parseOptionIndex(option, pending.options().size());
        if (optionIndex < 0) {
            source.sendError(Text.literal("MineClawd: invalid option. Use tab completion for valid choices."));
            return 0;
        }
        String selected = pending.options().get(optionIndex);
        if (isBuiltInQuestionOtherChoice(selected)) {
            PENDING_OTHER_TEXT_INPUT.put(player.getUuid(), pending);
            sendAgentMessage(source, BUILT_IN_QUESTION_CUSTOM_INPUT_PROMPT);
            return 1;
        }
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
        if (source.getEntity() instanceof ServerPlayerEntity player
                && canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_SESSIONS)) {
            sendSessionsOverlayToPlayer(source, player, false, null);
            return 1;
        }
        sendAgentMessage(source, "Switched persona to `" + resolved + "`.");
        return 1;
    }

    private int setAssistiveTouch(ServerCommandSource source, String rawState) {
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("MineClawd: this command must be executed by a player."));
            return 0;
        }

        boolean current = PLAYER_SETTINGS.isAssistiveTouchEnabled(player.getUuidAsString());
        Boolean desired = null;
        if (rawState != null && !rawState.isBlank()) {
            String normalized = rawState.trim().toLowerCase(Locale.ROOT);
            if ("toggle".equals(normalized) || "switch".equals(normalized)) {
                desired = !current;
            } else {
                desired = parseBooleanValue(normalized);
            }
            if (desired == null) {
                source.sendError(Text.literal("MineClawd: invalid value. Use toggle/on/off/true/false."));
                return 0;
            }
        }

        boolean next = desired == null ? !current : desired;
        PLAYER_SETTINGS.setAssistiveTouchEnabled(player.getUuidAsString(), next);
        sendAssistiveTouchSync(player);
        sendAgentMessage(source, "AssistiveTouch is now `" + (next ? "enabled" : "disabled") + "`.");
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
        MineClawdConfig.LlmProvider configuredProvider = config.provider == null
                ? MineClawdConfig.LlmProvider.OPENAI
                : config.provider;

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

        MineClawdConfig.LlmProvider provider = resolveProviderForSessionContinuity(configuredProvider, session);
        if (!validateConfig(source, config, provider)) {
            ACTIVE_REQUESTS.remove(ownerKey, requestId);
            return 0;
        }
        boolean providerSwitchedForSessionContinuity = provider != configuredProvider;

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
                requestOptions.interactiveErrorActions(),
                source.getEntity() instanceof ServerPlayerEntity requester
                        && canUseAssistiveOverlay(requester, MineClawdNetworking.AGENT_STREAM_EVENT)
        );
        debugLog(runtime, "Request from %s session=%s: %s", ownerKey, sessionId, request);
        debugLog(runtime, "Request id: %s", requestId);
        debugLog(runtime, "Tool call limit enabled: %s", runtime.limitToolCallsEnabled());
        if (runtime.limitToolCallsEnabled()) {
            debugLog(runtime, "Tool call limit: %d", runtime.toolLimit());
        }
        if (providerSwitchedForSessionContinuity) {
            String providerName = provider == MineClawdConfig.LlmProvider.VERTEX_AI ? "vertex-ai" : "openai";
            String configuredProviderName = configuredProvider == MineClawdConfig.LlmProvider.VERTEX_AI ? "vertex-ai" : "openai";
            sendAgentMessage(source, "Using `" + providerName + "` for this request to match existing session history (configured provider is `" + configuredProviderName + "`).");
        }
        traceLog(runtime, "USER", request);
        agentLog(runtime, "User request from %s: %s", ownerKey, request);
        sendPromptEcho(source, request);
        sendTaskStatus(source, true);
        if (runtime.clientStreamEnabled()) {
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.START, buildStreamStartPayload(runtime.sessionId(), request));
        }
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
            if (runtime.clientStreamEnabled()) {
                clearToolCallProgress(source, runtime);
                sendAgentStreamEvent(source, runtime, AgentStreamEventType.ERROR, "MineClawd failed to start request: " + e.getMessage());
                sendAgentStreamEvent(source, runtime, AgentStreamEventType.DONE, "");
            } else {
                source.sendError(Text.literal("MineClawd: failed to start request: " + e.getMessage()));
            }
            sendTaskStatus(source, false);
            finishActiveRequest(runtime);
            return 0;
        }

        return 1;
    }

    private MineClawdConfig.LlmProvider resolveProviderForSessionContinuity(
            MineClawdConfig.LlmProvider configuredProvider,
            SessionData session
    ) {
        MineClawdConfig.LlmProvider base = configuredProvider == null
                ? MineClawdConfig.LlmProvider.OPENAI
                : configuredProvider;
        if (session == null) {
            return base;
        }
        int openAiVisibleCount = countOpenAiVisibleMessages(session.openAiHistory());
        int vertexVisibleCount = countVertexVisibleMessages(session.vertexHistory());
        if (base == MineClawdConfig.LlmProvider.OPENAI && openAiVisibleCount == 0 && vertexVisibleCount > 0) {
            return MineClawdConfig.LlmProvider.VERTEX_AI;
        }
        if (base == MineClawdConfig.LlmProvider.VERTEX_AI && vertexVisibleCount == 0 && openAiVisibleCount > 0) {
            return MineClawdConfig.LlmProvider.OPENAI;
        }
        return base;
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
                : "MineClawd finished working for " + requesterName + ".";
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

    private void queueAgentStreamDelta(ServerCommandSource source, AgentRuntime runtime, String chunk) {
        if (source == null || runtime == null || !runtime.clientStreamEnabled() || chunk == null || chunk.isEmpty()) {
            return;
        }
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (isRuntimeInactive(runtime)) {
                cleanupInactiveRuntime(runtime);
                return;
            }
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.DELTA, chunk);
        });
    }

    private String buildStreamStartPayload(String sessionId, String request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId == null ? "" : sessionId);
        String normalizedRequest = request == null ? "" : request.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalizedRequest.length() > 600) {
            normalizedRequest = normalizedRequest.substring(0, 600).trim();
        }
        payload.addProperty("request", normalizedRequest);
        return payload.toString();
    }

    private void sendAgentStreamEvent(
            ServerCommandSource source,
            AgentRuntime runtime,
            AgentStreamEventType type,
            String payload
    ) {
        if (source == null || runtime == null || type == null || !runtime.clientStreamEnabled()) {
            return;
        }
        if (type != AgentStreamEventType.START && isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!canUseAssistiveOverlay(player, MineClawdNetworking.AGENT_STREAM_EVENT)) {
            return;
        }

        String requestId = runtime.requestId() == null ? "" : runtime.requestId();
        String message = payload == null ? "" : payload;
        if ((type == AgentStreamEventType.DELTA || type == AgentStreamEventType.ERROR)
                && message.length() > AGENT_STREAM_CHUNK_CHARS) {
            int index = 0;
            while (index < message.length()) {
                int end = Math.min(message.length(), index + AGENT_STREAM_CHUNK_CHARS);
                String chunk = message.substring(index, end);
                sendAgentStreamPacket(player, requestId, type, chunk);
                index = end;
            }
            return;
        }
        sendAgentStreamPacket(player, requestId, type, message);
    }

    private void sendAgentStreamPacket(
            ServerPlayerEntity player,
            String requestId,
            AgentStreamEventType type,
            String payload
    ) {
        if (player == null || type == null || !canUseAssistiveOverlay(player, MineClawdNetworking.AGENT_STREAM_EVENT)) {
            return;
        }
        String safeRequestId = requestId == null ? "" : requestId;
        String safePayload = payload == null ? "" : payload;
        if (safePayload.length() > AGENT_STREAM_PACKET_MAX_CHARS) {
            safePayload = safePayload.substring(0, AGENT_STREAM_PACKET_MAX_CHARS);
        }
        var packet = new PacketByteBuf(Unpooled.buffer());
        packet.writeString(safeRequestId, AGENT_STREAM_REQUEST_ID_MAX_CHARS);
        packet.writeByte(type.id());
        packet.writeString(safePayload, AGENT_STREAM_PACKET_MAX_CHARS);
        sendPacketToPlayer(player, MineClawdNetworking.AGENT_STREAM_EVENT, packet, "agent_stream_event");
    }

    private void finishRequestWithRuntimeError(ServerCommandSource source, AgentRuntime runtime, String message) {
        if (runtime != null && isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        String normalized = message == null || message.isBlank() ? "unknown error" : message;
        if (runtime != null && runtime.clientStreamEnabled()) {
            clearToolCallProgress(source, runtime);
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.ERROR, "Oops! " + normalized);
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.DONE, "");
        } else if (source != null) {
            source.sendError(Text.literal("MineClawd: " + normalized));
        }
        sendTaskStatus(source, false);
        finishActiveRequest(runtime);
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
        MinecraftServer server = source.getServer();
        boolean hadInternalCommand = hasInternalKubeJsCommand(server);
        LOGGER.info("[MineClawd Debug][KubeJS] ensureKubeJsScript start: runDir={} internalCommandBefore={} source={} perm2={} perm4={}",
                server.getRunDirectory(),
                hadInternalCommand,
                source.getName(),
                source.hasPermissionLevel(2),
                source.hasPermissionLevel(4));

        boolean created = KubeJsScriptManager.ensureScript(server);
        if (created) {
            sendAgentMessage(source, "Generated the internal KubeJS script and reloading `server_scripts`.");
            server.getCommandManager().executeWithPrefix(source, "/kubejs reload server_scripts");
            LOGGER.info("[MineClawd Debug][KubeJS] ensureKubeJsScript updated script and reloaded server_scripts: internalCommandAfterReload={}",
                    hasInternalKubeJsCommand(server));
        } else {
            LOGGER.info("[MineClawd Debug][KubeJS] ensureKubeJsScript script unchanged: internalCommandAfterCheck={}",
                    hasInternalKubeJsCommand(server));
        }
    }

    private boolean hasInternalKubeJsCommand(MinecraftServer server) {
        if (server == null || server.getCommandManager() == null || server.getCommandManager().getDispatcher() == null) {
            return false;
        }
        return server.getCommandManager().getDispatcher().getRoot().getChild("_exec_kubejs_internal_mc") != null
                || server.getCommandManager().getDispatcher().getRoot().getChild("_exec_kubejs_internal") != null;
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
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        if (runtime.limitToolCallsEnabled() && depth >= runtime.toolLimit()) {
            finishRequestWithRuntimeError(source, runtime, "tool loop limit reached.");
            return;
        }

        MineClawdConfig config = MineClawdConfig.get();
        debugLog(runtime, "OpenAI request round=%d retry=%d session=%s", depth + 1, retryCount, runtime.sessionId());
        AtomicBoolean streamedThisRound = new AtomicBoolean(false);
        CompletableFuture<OpenAIResponse> requestFuture = OPENAI_CLIENT.sendMessage(
                        config.endpoint,
                        config.apiKey,
                        config.model,
                        history,
                        openAiTools(runtime.dynamicRegistryEnabled(), hasConfiguredTavilyKey(config)),
                        runtime.clientStreamEnabled()
                                ? chunk -> {
                                    if (chunk == null || chunk.isEmpty()) {
                                        return;
                                    }
                                    streamedThisRound.set(true);
                                    queueAgentStreamDelta(source, runtime, chunk);
                                }
                                : null)
                ;
        trackActiveNetworkRequest(runtime, requestFuture);
        requestFuture.whenComplete((response, error) -> {
            clearActiveNetworkRequest(runtime, requestFuture);
            if (source.getServer() == null) {
                finishActiveRequest(runtime);
                return;
            }
            source.getServer().execute(() -> handleOpenAiStep(
                    source,
                    session,
                    history,
                    depth,
                    retryCount,
                    loopState,
                    runtime,
                    streamedThisRound.get(),
                    response,
                    error));
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
            boolean streamedThisRound,
            OpenAIResponse response,
            Throwable error
    ) {
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
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
        if (text != null && !text.isBlank()) {
            traceLog(runtime, "ASSISTANT", text);
            agentLog(runtime, "Agent response: %s", text);
        }
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            for (OpenAIToolCall call : response.toolCalls()) {
                debugLog(runtime, "OpenAI tool call: id=%s name=%s args=%s",
                        call == null ? "(null)" : call.id(),
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.arguments());
                agentLog(runtime, "Tool call: %s args=%s",
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.arguments());
            }
        }
        if (text != null && !text.isBlank()) {
            if (runtime.clientStreamEnabled()) {
                if (!streamedThisRound) {
                    sendAgentStreamEvent(source, runtime, AgentStreamEventType.DELTA, text);
                }
            } else {
                sendAgentMessage(source, text);
            }
        }

        List<OpenAIToolCall> toolCalls = response.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            final boolean hadAssistantTextBeforeTools = text != null && !text.isBlank();
            history.add(OpenAIMessage.assistant(text, toolCalls));
            executeOpenAiToolCallsAsync(source, toolCalls, runtime)
                    .whenComplete((batch, batchError) -> {
                        if (source.getServer() == null) {
                            finishActiveRequest(runtime);
                            return;
                        }
                        source.getServer().execute(() -> {
                            if (isRuntimeInactive(runtime)) {
                                cleanupInactiveRuntime(runtime);
                                return;
                            }
                            if (batchError != null || batch == null) {
                                finishRequestWithRuntimeError(source, runtime, "tool execution failed: " + summarizeThrowable(batchError));
                                return;
                            }
                            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
                            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                                finishRequestWithRuntimeError(source, runtime, "repeated tool call detected. Stopping.");
                                return;
                            }
                            List<OpenAIMessage> toolMessages = batch.messages();
                            history.addAll(toolMessages);
                            if (session != null) {
                                session.touch();
                                SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
                            }
                            if (runtime.clientStreamEnabled() && hadAssistantTextBeforeTools) {
                                sendAgentStreamEvent(source, runtime, AgentStreamEventType.DELTA, "\n\n");
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
        if (runtime.clientStreamEnabled()) {
            clearToolCallProgress(source, runtime);
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.DONE, "");
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
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        if (runtime.limitToolCallsEnabled() && depth >= runtime.toolLimit()) {
            finishRequestWithRuntimeError(source, runtime, "tool loop limit reached.");
            return;
        }

        MineClawdConfig config = MineClawdConfig.get();
        debugLog(runtime, "Vertex request round=%d retry=%d session=%s", depth + 1, retryCount, runtime.sessionId());
        AtomicBoolean streamedThisRound = new AtomicBoolean(false);
        CompletableFuture<VertexAIResponse> requestFuture = VERTEX_CLIENT.sendMessage(
                        config.vertexEndpoint,
                        config.vertexApiKey,
                        config.vertexModel,
                        history,
                        vertexTools(runtime.dynamicRegistryEnabled(), hasConfiguredTavilyKey(config)),
                        runtime.clientStreamEnabled()
                                ? chunk -> {
                                    if (chunk == null || chunk.isEmpty()) {
                                        return;
                                    }
                                    streamedThisRound.set(true);
                                    queueAgentStreamDelta(source, runtime, chunk);
                                }
                                : null)
                ;
        trackActiveNetworkRequest(runtime, requestFuture);
        requestFuture.whenComplete((response, error) -> {
            clearActiveNetworkRequest(runtime, requestFuture);
            if (source.getServer() == null) {
                finishActiveRequest(runtime);
                return;
            }
            source.getServer().execute(() -> handleVertexStep(
                    source,
                    session,
                    history,
                    depth,
                    retryCount,
                    loopState,
                    runtime,
                    streamedThisRound.get(),
                    response,
                    error));
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
            boolean streamedThisRound,
            VertexAIResponse response,
            Throwable error
    ) {
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
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
        if (text != null && !text.isBlank()) {
            traceLog(runtime, "ASSISTANT", text);
            agentLog(runtime, "Agent response: %s", text);
        }
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            for (VertexAIToolCall call : response.toolCalls()) {
                debugLog(runtime, "Vertex tool call: name=%s args=%s",
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.args());
                agentLog(runtime, "Tool call: %s args=%s",
                        call == null ? "(null)" : call.name(),
                        call == null ? "(null)" : call.args());
            }
        }
        if (text != null && !text.isBlank()) {
            if (runtime.clientStreamEnabled()) {
                if (!streamedThisRound) {
                    sendAgentStreamEvent(source, runtime, AgentStreamEventType.DELTA, text);
                }
            } else {
                sendAgentMessage(source, text);
            }
        }

        if (response.modelMessage() != null) {
            history.add(response.modelMessage());
        }

        List<VertexAIToolCall> toolCalls = response.toolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            final boolean hadAssistantTextBeforeTools = text != null && !text.isBlank();
            executeVertexToolCallsAsync(source, toolCalls, runtime)
                    .whenComplete((batch, batchError) -> {
                        if (source.getServer() == null) {
                            finishActiveRequest(runtime);
                            return;
                        }
                        source.getServer().execute(() -> {
                            if (isRuntimeInactive(runtime)) {
                                cleanupInactiveRuntime(runtime);
                                return;
                            }
                            if (batchError != null || batch == null) {
                                finishRequestWithRuntimeError(source, runtime, "tool execution failed: " + summarizeThrowable(batchError));
                                return;
                            }
                            ToolLoopState nextState = loopState.next(batch.signature(), batch.output());
                            if (nextState.repeatCount() > MAX_REPEAT_TOOL_CALLS) {
                                finishRequestWithRuntimeError(source, runtime, "repeated tool call detected. Stopping.");
                                return;
                            }
                            List<VertexAIMessage> toolMessages = batch.vertexMessages();
                            history.addAll(toolMessages);
                            if (session != null) {
                                session.touch();
                                SESSION_MANAGER.saveSession(runtime.ownerKey(), session);
                            }
                            if (runtime.clientStreamEnabled() && hadAssistantTextBeforeTools) {
                                sendAgentStreamEvent(source, runtime, AgentStreamEventType.DELTA, "\n\n");
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
        if (runtime.clientStreamEnabled()) {
            clearToolCallProgress(source, runtime);
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.DONE, "");
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
        if (runtime != null && isRuntimeInactive(runtime)) {
            return CompletableFuture.completedFuture(null);
        }
        if (toolCalls == null || index >= toolCalls.size()) {
            return CompletableFuture.completedFuture(null);
        }
        OpenAIToolCall call = toolCalls.get(index);
        if (call == null) {
            return executeOpenAiToolCallsSequential(source, toolCalls, runtime, index + 1, results, outputs, signatures);
        }

        JsonObject args = parseToolArguments(call.arguments());
        int callIndex = index + 1;
        ToolStatusDescriptor statusDescriptor = announceToolCallProgress(source, runtime, call.name(), args);
        ToolStatusDescriptor completionDescriptor = buildToolStatusCompletedDescriptor(call.name(), args);
        traceLog(runtime, "TOOL_CALL", "name=" + safeForLog(call.name()) + " args=" + args);
        debugLog(runtime, "Executing tool (OpenAI) #%d name=%s args=%s", callIndex, call.name(), args);
        return executeToolCallAsync(source, call.name(), args, runtime)
                .handle((output, throwable) -> {
                    String finalOutput = output;
                    if (throwable != null) {
                        finalOutput = "ERROR: " + summarizeThrowable(throwable);
                    } else if (finalOutput == null || finalOutput.isBlank()) {
                        finalOutput = "ERROR: Tool returned empty output.";
                    }
                    clearToolCallProgress(source, runtime, completionDescriptor == null ? statusDescriptor : completionDescriptor);
                    traceLog(runtime, "TOOL_RESULT", "name=" + safeForLog(call.name()) + " output=" + finalOutput);
                    debugLog(runtime, "Tool output #%d: %s", callIndex, finalOutput);
                    agentLog(runtime, "Tool result: %s -> %s", call.name(), finalOutput);
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
        if (runtime != null && isRuntimeInactive(runtime)) {
            return CompletableFuture.completedFuture(null);
        }
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
        ToolStatusDescriptor statusDescriptor = announceToolCallProgress(source, runtime, call.name(), args);
        ToolStatusDescriptor completionDescriptor = buildToolStatusCompletedDescriptor(call.name(), args);
        traceLog(runtime, "TOOL_CALL", "name=" + safeForLog(call.name()) + " args=" + args);
        debugLog(runtime, "Executing tool (Vertex) #%d name=%s args=%s", callIndex, call.name(), args);
        return executeToolCallAsync(source, call.name(), args, runtime)
                .handle((output, throwable) -> {
                    String finalOutput = output;
                    if (throwable != null) {
                        finalOutput = "ERROR: " + summarizeThrowable(throwable);
                    } else if (finalOutput == null || finalOutput.isBlank()) {
                        finalOutput = "ERROR: Tool returned empty output.";
                    }
                    clearToolCallProgress(source, runtime, completionDescriptor == null ? statusDescriptor : completionDescriptor);
                    traceLog(runtime, "TOOL_RESULT", "name=" + safeForLog(call.name()) + " output=" + finalOutput);
                    debugLog(runtime, "Tool output #%d: %s", callIndex, finalOutput);
                    agentLog(runtime, "Tool result: %s -> %s", call.name(), finalOutput);
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
        return CompletableFuture.completedFuture(executeToolCallSync(source, toolName, args, runtime));
    }

    private String executeToolCallSync(ServerCommandSource source, String toolName, JsonObject args, AgentRuntime runtime) {
        if (toolName == null || toolName.isBlank()) {
            return "ERROR: Tool call is missing required `name`.";
        }
        if (runtime != null && isRuntimeInactive(runtime)) {
            return "ERROR: Request was stopped by user.";
        }
        String ownerKey = runtime == null || runtime.ownerKey() == null || runtime.ownerKey().isBlank()
                ? sessionOwnerKey(source)
                : runtime.ownerKey();
        MineClawdConfig config = MineClawdConfig.get();
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
            case TOOL_SEARCH:
                if (!hasConfiguredTavilyKey(config)) {
                    return "ERROR: Search tool is disabled. Configure `tavily-api-key` first.";
                }
                String query = readRequiredStringArg(args, "query");
                if (query == null || query.isBlank()) {
                    return "ERROR: Tool call is missing required string `query`.";
                }
                result = SearchToolExecutor.searchWeb(config.tavilyApiKey, query, readOptionalIntArg(args, "max_results"));
                break;
            case TOOL_LIST_COMMANDS:
                result = ModToolsExecutor.listCommands(source, readOptionalStringArg(args, "mod_id"));
                break;
            case TOOL_FETCH_MODRINTH:
                String modrinthModId = readRequiredStringArg(args, "mod_id");
                if (modrinthModId == null || modrinthModId.isBlank()) {
                    return "ERROR: Tool call is missing required string `mod_id`.";
                }
                result = ModToolsExecutor.fetchModrinth(modrinthModId);
                break;
            case TOOL_FETCH_URL:
                String docsUrl = readRequiredStringArg(args, "url");
                if (docsUrl == null || docsUrl.isBlank()) {
                    return "ERROR: Tool call is missing required string `url`.";
                }
                result = ModToolsExecutor.fetchUrl(docsUrl);
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
            case TOOL_LIST_DYNAMIC_PROPERTIES:
                result = DynamicContentToolExecutor.listProperties(readOptionalStringArg(args, "type"));
                break;
            case TOOL_REGISTER_DYNAMIC_ITEM:
                result = DynamicContentToolExecutor.registerItem(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_item"),
                        readOptionalBooleanArg(args, "throwable"),
                        readOptionalDoubleArg(args, "throw_speed"),
                        readOptionalDoubleArg(args, "throw_inaccuracy"),
                        readOptionalIntArg(args, "throw_cooldown_ticks"),
                        readOptionalBooleanArg(args, "consume_on_throw"),
                        readOptionalIntArg(args, "max_count"),
                        readRequiredStringArg(args, "use_action"),
                        readOptionalIntArg(args, "use_time_ticks"),
                        readRequiredStringArg(args, "glint_mode")
                );
                break;
            case TOOL_REGISTER_DYNAMIC_BLOCK:
                result = DynamicContentToolExecutor.registerBlock(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_block"),
                        readOptionalDoubleArg(args, "friction"),
                        readOptionalDoubleArg(args, "velocity_multiplier"),
                        readOptionalDoubleArg(args, "jump_velocity_multiplier"),
                        readOptionalDoubleArg(args, "blast_resistance"),
                        readOptionalBooleanArg(args, "use_material_sounds")
                );
                break;
            case TOOL_REGISTER_DYNAMIC_FLUID:
                result = DynamicContentToolExecutor.registerFluid(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_fluid"),
                        readRequiredStringArg(args, "color"),
                        readOptionalIntArg(args, "tick_rate"),
                        readOptionalIntArg(args, "flow_speed"),
                        readOptionalIntArg(args, "level_decrease_per_block"),
                        readOptionalDoubleArg(args, "blast_resistance"),
                        readOptionalBooleanArg(args, "infinite")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_ITEM:
                result = DynamicContentToolExecutor.updateItem(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_item"),
                        readOptionalBooleanArg(args, "throwable"),
                        readOptionalDoubleArg(args, "throw_speed"),
                        readOptionalDoubleArg(args, "throw_inaccuracy"),
                        readOptionalIntArg(args, "throw_cooldown_ticks"),
                        readOptionalBooleanArg(args, "consume_on_throw"),
                        readOptionalIntArg(args, "max_count"),
                        readRequiredStringArg(args, "use_action"),
                        readOptionalIntArg(args, "use_time_ticks"),
                        readRequiredStringArg(args, "glint_mode")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_BLOCK:
                result = DynamicContentToolExecutor.updateBlock(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_block"),
                        readOptionalDoubleArg(args, "friction"),
                        readOptionalDoubleArg(args, "velocity_multiplier"),
                        readOptionalDoubleArg(args, "jump_velocity_multiplier"),
                        readOptionalDoubleArg(args, "blast_resistance"),
                        readOptionalBooleanArg(args, "use_material_sounds")
                );
                break;
            case TOOL_UPDATE_DYNAMIC_FLUID:
                result = DynamicContentToolExecutor.updateFluid(
                        source,
                        readOptionalIntArg(args, "slot"),
                        readRequiredStringArg(args, "name"),
                        readRequiredStringArg(args, "material_fluid"),
                        readRequiredStringArg(args, "color"),
                        readOptionalIntArg(args, "tick_rate"),
                        readOptionalIntArg(args, "flow_speed"),
                        readOptionalIntArg(args, "level_decrease_per_block"),
                        readOptionalDoubleArg(args, "blast_resistance"),
                        readOptionalBooleanArg(args, "infinite")
                );
                break;
            case TOOL_UNREGISTER_DYNAMIC_CONTENT:
                result = DynamicContentToolExecutor.unregister(
                        source,
                        readRequiredStringArg(args, "type"),
                        readOptionalIntArg(args, "slot")
                );
                break;
            case TOOL_LIST_ASSETS:
                result = listAssetsTool(ownerKey);
                break;
            case TOOL_UPSERT_ASSET_RECORD:
                result = upsertAssetRecordTool(ownerKey, args);
                break;
            case TOOL_REMOVE_ASSET_RECORD:
                result = removeAssetRecordTool(ownerKey, args);
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

    private String readOptionalStringArg(JsonObject args, String key) {
        String value = readRequiredStringArg(args, key);
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private ToolExecutionResult listAssetsTool(String ignoredOwnerKey) {
        List<AssetRecord> assets = ASSETS_MANAGER.list(assetOwnerKey());
        if (assets.isEmpty()) {
            return new ToolExecutionResult(true, "No assets tracked yet.");
        }
        StringBuilder out = new StringBuilder();
        out.append("Tracked assets (").append(assets.size()).append(")\n");
        for (AssetRecord asset : assets) {
            if (asset == null) {
                continue;
            }
            out.append("- `").append(asset.id()).append("` [").append(asset.category().id()).append("] ");
            out.append(asset.name());
            if (!asset.summary().isBlank()) {
                out.append(" - ").append(asset.summary());
            }
            if (asset.category() == AssetCategory.ENTITIES && !asset.entityUuid().isBlank()) {
                out.append(" (uuid=").append(asset.entityUuid()).append(")");
            }
            if (asset.category() == AssetCategory.ITEMS_BLOCKS_FLUIDS && !asset.contentId().isBlank()) {
                out.append(" (content_id=").append(asset.contentId()).append(")");
            }
            if (asset.category() == AssetCategory.SPECIAL_ITEMS && !asset.specialItemId().isBlank()) {
                out.append(" (special_item_id=").append(asset.specialItemId()).append(")");
            }
            if (asset.category() == AssetCategory.COMMANDS && !asset.command().isBlank()) {
                out.append(" (command=").append(asset.command()).append(")");
            }
            out.append("\n");
        }
        return new ToolExecutionResult(true, out.toString().trim());
    }

    private ToolExecutionResult upsertAssetRecordTool(String ignoredOwnerKey, JsonObject args) {
        String scriptPath = readOptionalStringArg(args, "script_path");
        if (scriptPath.isBlank()) {
            scriptPath = readOptionalStringArg(args, "scriptPath");
        }
        AssetDraft draft = new AssetDraft(
                readOptionalStringArg(args, "id"),
                readOptionalStringArg(args, "category"),
                readOptionalStringArg(args, "name"),
                readOptionalStringArg(args, "summary"),
                scriptPath,
                readOptionalStringArg(args, "details"),
                readOptionalStringArg(args, "content_id"),
                readOptionalStringArg(args, "special_item_id"),
                readOptionalStringArg(args, "special_item_nbt"),
                readOptionalStringArg(args, "command"),
                readOptionalStringArg(args, "entity_uuid"),
                readOptionalStringArg(args, "entity_dimension"),
                readOptionalDoubleArg(args, "entity_x"),
                readOptionalDoubleArg(args, "entity_y"),
                readOptionalDoubleArg(args, "entity_z"),
                readOptionalStringArg(args, "session_id")
        );
        UpsertResult result = ASSETS_MANAGER.upsert(assetOwnerKey(), draft);
        if (!result.success()) {
            return new ToolExecutionResult(false, result.message());
        }
        AssetRecord record = result.record();
        if (record == null) {
            return new ToolExecutionResult(true, result.message());
        }
        StringBuilder out = new StringBuilder(result.message());
        out.append("\nid: ").append(record.id());
        out.append("\ncategory: ").append(record.category().id());
        out.append("\nname: ").append(record.name());
        if (!record.summary().isBlank()) {
            out.append("\nsummary: ").append(record.summary());
        }
        if (!record.scriptPath().isBlank()) {
            out.append("\nscript_path: ").append(record.scriptPath());
        }
        if (!record.entityUuid().isBlank()) {
            out.append("\nentity_uuid: ").append(record.entityUuid());
        }
        if (!record.contentId().isBlank()) {
            out.append("\ncontent_id: ").append(record.contentId());
        }
        if (!record.specialItemId().isBlank()) {
            out.append("\nspecial_item_id: ").append(record.specialItemId());
        }
        return new ToolExecutionResult(true, out.toString());
    }

    private ToolExecutionResult removeAssetRecordTool(String ignoredOwnerKey, JsonObject args) {
        String id = readOptionalStringArg(args, "id");
        if (id.isBlank()) {
            id = readOptionalStringArg(args, "reference");
        }
        if (id.isBlank()) {
            return new ToolExecutionResult(false, "Tool call is missing required string `id`.");
        }
        String ownerKey = assetOwnerKey();
        AssetRecord record = ASSETS_MANAGER.resolve(ownerKey, id);
        if (record == null) {
            return new ToolExecutionResult(false, "Asset record was not found.");
        }
        boolean removed = ASSETS_MANAGER.remove(ownerKey, record.id());
        if (!removed) {
            return new ToolExecutionResult(false, "Failed to remove asset record.");
        }
        return new ToolExecutionResult(true, "Removed asset record `" + record.id() + "`.");
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
        Set<String> seen = ConcurrentHashMap.newKeySet();
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
                    String dedupeKey = normalized.toLowerCase(Locale.ROOT);
                    if (isBuiltInQuestionChoice(dedupeKey) || !seen.add(dedupeKey)) {
                        continue;
                    }
                    options.add(normalized);
                }
            } catch (Exception ignored) {
            }
        }
        return options;
    }

    private List<String> appendBuiltInQuestionOptions(List<String> options) {
        List<String> merged = new ArrayList<>();
        if (options != null) {
            for (String option : options) {
                if (option == null || option.isBlank()) {
                    continue;
                }
                if (isBuiltInQuestionOtherChoice(option)) {
                    continue;
                }
                merged.add(option);
            }
        }
        merged.add(BUILT_IN_QUESTION_OTHER_OPTION);
        return merged;
    }

    private boolean isBuiltInQuestionChoice(String choice) {
        return isBuiltInQuestionOtherChoice(choice) || isBuiltInQuestionSkipChoice(choice);
    }

    private boolean isBuiltInQuestionOtherChoice(String choice) {
        String normalized = normalizeQuestionChoice(choice);
        if (normalized.isBlank()) {
            return false;
        }
        if ("other".equals(normalized)
                || "others".equals(normalized)
                || "custom".equals(normalized)
                || "custom text".equals(normalized)
                || "custom response".equals(normalized)
                || "custom answer".equals(normalized)
                || "other option".equals(normalized)
                || "other options".equals(normalized)
                || "\u5176\u4ed6".equals(normalized)
                || "\u5176\u5b83".equals(normalized)
                || "\u81ea\u5b9a\u4e49".equals(normalized)
                || "\u81ea\u5b9a\u4e49\u6587\u672c".equals(normalized)
                || "\u81ea\u5b9a\u4e49\u8f93\u5165".equals(normalized)
                || "\u81ea\u5b9a\u4e49\u56de\u7b54".equals(normalized)
                || "\u81ea\u5df1\u586b\u5199".equals(normalized)
                || "\u81ea\u884c\u586b\u5199".equals(normalized)) {
            return true;
        }
        if (normalized.startsWith("other ")) {
            return normalized.contains("custom")
                    || normalized.contains("text")
                    || normalized.contains("input")
                    || normalized.contains("type")
                    || normalized.contains("specify")
                    || normalized.contains("answer")
                    || normalized.contains("response");
        }
        if (normalized.startsWith("\u5176\u4ed6") || normalized.startsWith("\u5176\u5b83")) {
            return normalized.contains("\u586b\u5199")
                    || normalized.contains("\u8f93\u5165")
                    || normalized.contains("\u81ea\u5b9a\u4e49");
        }
        return false;
    }

    private boolean isBuiltInQuestionSkipChoice(String choice) {
        String normalized = normalizeQuestionChoice(choice);
        if (normalized.isBlank()) {
            return false;
        }
        return "skip".equals(normalized)
                || "skip question".equals(normalized)
                || "skip this".equals(normalized)
                || "skip this question".equals(normalized)
                || "\u8df3\u8fc7".equals(normalized)
                || "\u8df3\u8fc7\u95ee\u9898".equals(normalized);
    }

    private String normalizeQuestionChoice(String choice) {
        if (choice == null || choice.isBlank()) {
            return "";
        }
        String normalized = choice.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replace("(", " ")
                .replace(")", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace(".", " ")
                .replace(",", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private CompletableFuture<String> askUserQuestion(ServerCommandSource source, JsonObject args, AgentRuntime runtime) {
        if (!(source.getEntity() instanceof ServerPlayerEntity player) || source.getServer() == null) {
            return CompletableFuture.completedFuture("ERROR: ask-user-question requires a player source.");
        }
        if (runtime != null && isRuntimeInactive(runtime)) {
            return CompletableFuture.completedFuture("ERROR: Request was stopped by user.");
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
            return CompletableFuture.completedFuture("ERROR: ask-user-question requires at least one non-built-in option in `options` (do not include Other/Skip).");
        }
        List<String> optionsWithBuiltIn = appendBuiltInQuestionOptions(options);

        String questionId = buildQuestionId();
        PendingQuestion pending = new PendingQuestion(
                questionId,
                player.getUuid(),
                player.getName().getString(),
                question,
                optionsWithBuiltIn,
                System.currentTimeMillis() + (QUESTION_TIMEOUT_SECONDS * 1000L)
        );

        PendingQuestion existing = PENDING_QUESTIONS_BY_PLAYER.put(player.getUuid(), pending);
        if (existing != null) {
            completePendingQuestion(existing, "SKIPPED: Replaced by a newer question.");
        }
        PENDING_QUESTIONS_BY_ID.put(questionId, pending);
        PENDING_OTHER_TEXT_INPUT.remove(player.getUuid());

        boolean questionUiAvailable = canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_QUESTION);
        boolean deliveredToQuestionUi = false;
        if (questionUiAvailable) {
            QuestionPromptPayload payload = new QuestionPromptPayload(
                    questionId,
                    question,
                    optionsWithBuiltIn,
                    pending.expiresAtEpochMillis()
            );
            var buffer = new PacketByteBuf(Unpooled.buffer());
            buffer.writeString(payload.toJson());
            deliveredToQuestionUi = sendPacketToPlayer(player, MineClawdNetworking.OPEN_QUESTION, buffer, "open_question");
            if (!deliveredToQuestionUi) {
                sendPendingQuestionFallback(source, player, pending);
            }
        } else {
            sendPendingQuestionFallback(source, player, pending);
        }
        boolean notifyInChat = !deliveredToQuestionUi;

        CompletableFuture.delayedExecutor(QUESTION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (source.getServer() == null) {
                completePendingQuestion(pending, "SKIPPED: Question timeout.");
                return;
            }
            source.getServer().execute(() -> {
                PendingQuestion current = PENDING_QUESTIONS_BY_ID.get(questionId);
                if (current == pending) {
                    completePendingQuestion(current, "SKIPPED: User did not respond within 60 seconds.");
                    if (notifyInChat) {
                        player.sendMessage(Text.empty().append(agentPrefix())
                                .append(renderAgentBody(null, "Question timed out. Continuing with skip result.")), false);
                    }
                }
            });
        });

        debugLog(runtime, "Waiting for user answer questionId=%s options=%d", questionId, optionsWithBuiltIn.size());
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

        MutableText skip = Text.literal("Skip")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GRAY)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mineclawd choose skip")));
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
        boolean notifyInChat = !canUseAssistiveOverlay(player, MineClawdNetworking.OPEN_QUESTION);

        switch (response.type()) {
            case OPTION -> {
                int index = response.optionIndex();
                if (index < 0 || index >= pending.options().size()) {
                    if (notifyInChat) {
                        player.sendMessage(Text.empty().append(agentPrefix())
                                .append(renderAgentBody(null, "Invalid option index from client UI. Please retry.")), false);
                    }
                    return;
                }
                String selected = pending.options().get(index);
                if (isBuiltInQuestionOtherChoice(selected)) {
                    PENDING_OTHER_TEXT_INPUT.put(player.getUuid(), pending);
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, BUILT_IN_QUESTION_CUSTOM_INPUT_PROMPT)), false);
                    return;
                }
                completePendingQuestion(pending, "User selected option " + (index + 1) + ": " + selected);
                if (notifyInChat) {
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Selection received: `" + selected + "`.")), false);
                }
            }
            case OTHER -> {
                String custom = response.value() == null ? "" : response.value().trim();
                if (custom.isBlank()) {
                    completePendingQuestion(pending, "SKIPPED: User submitted empty custom response.");
                    if (notifyInChat) {
                        player.sendMessage(Text.empty().append(agentPrefix())
                                .append(renderAgentBody(null, "Custom response was empty, treated as skip.")), false);
                    }
                    return;
                }
                completePendingQuestion(pending, "User provided custom response: " + custom);
                if (notifyInChat) {
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Custom response received.")), false);
                }
            }
            case SKIP -> {
                String reason = response.value() == null ? "" : response.value().trim();
                if (reason.isBlank()) {
                    reason = "User skipped.";
                }
                completePendingQuestion(pending, "SKIPPED: " + reason);
                if (notifyInChat) {
                    player.sendMessage(Text.empty().append(agentPrefix())
                            .append(renderAgentBody(null, "Skipped.")), false);
                }
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

    private List<OpenAITool> openAiTools(boolean dynamicRegistryEnabled, boolean searchEnabled) {
        List<OpenAITool> tools = new ArrayList<>(List.of(
                new OpenAITool(
                        TOOL_ASK_USER,
                        "Ask the player a clarification question with up to five preset options. Do not include Other/Skip in options; MineClawd injects Other and handles skip.",
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
                        TOOL_LIST_COMMANDS,
                        "List available root commands. Optionally filter with mod_id (best-effort by command name/prefix).",
                        listCommandsToolParameters()
                ),
                new OpenAITool(
                        TOOL_FETCH_MODRINTH,
                        "Fetch the Modrinth project page content for an installed mod id.",
                        fetchModrinthToolParameters()
                ),
                new OpenAITool(
                        TOOL_FETCH_URL,
                        "Fetch any HTTP(S) URL and return readable content. HTML is converted to Markdown.",
                        fetchUrlToolParameters()
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
                ),
                new OpenAITool(
                        TOOL_LIST_ASSETS,
                        "List currently tracked persistent asset records (server-global across players).",
                        noArgToolParameters()
                ),
                new OpenAITool(
                        TOOL_UPSERT_ASSET_RECORD,
                        "Create or update an asset tracking record for entities, items/blocks/fluids, special items, commands, or game mechanics.",
                        assetUpsertToolParameters()
                ),
                new OpenAITool(
                        TOOL_REMOVE_ASSET_RECORD,
                        "Remove an obsolete asset tracking record by id/reference.",
                        assetRemoveToolParameters()
                )
        ));
        if (searchEnabled) {
            tools.add(new OpenAITool(
                    TOOL_SEARCH,
                    "Search the web via Tavily and return concise snippets with URLs. Use this when external references are needed.",
                    searchToolParameters()
            ));
        }
        if (dynamicRegistryEnabled) {
            tools.addAll(List.of(
                    new OpenAITool(
                            TOOL_LIST_DYNAMIC_CONTENT,
                            "List currently active dynamic placeholder entries and free slots.",
                            noArgToolParameters()
                    ),
                    new OpenAITool(
                            TOOL_LIST_DYNAMIC_PROPERTIES,
                            "List editable property keys/ranges for dynamic items, blocks, and fluids.",
                            dynamicPropertyListToolParameters()
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

    private List<VertexAIFunction> vertexTools(boolean dynamicRegistryEnabled, boolean searchEnabled) {
        List<VertexAIFunction> tools = new ArrayList<>(List.of(
                new VertexAIFunction(
                        TOOL_ASK_USER,
                        "Ask the player a clarification question with up to five preset options. Do not include Other/Skip in options; MineClawd injects Other and handles skip.",
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
                        TOOL_LIST_COMMANDS,
                        "List available root commands. Optionally filter with mod_id (best-effort by command name/prefix).",
                        listCommandsToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_FETCH_MODRINTH,
                        "Fetch the Modrinth project page content for an installed mod id.",
                        fetchModrinthToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_FETCH_URL,
                        "Fetch any HTTP(S) URL and return readable content. HTML is converted to Markdown.",
                        fetchUrlToolParameters()
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
                ),
                new VertexAIFunction(
                        TOOL_LIST_ASSETS,
                        "List currently tracked persistent asset records (server-global across players).",
                        noArgToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_UPSERT_ASSET_RECORD,
                        "Create or update an asset tracking record for entities, items/blocks/fluids, special items, commands, or game mechanics.",
                        assetUpsertToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_REMOVE_ASSET_RECORD,
                        "Remove an obsolete asset tracking record by id/reference.",
                        assetRemoveToolParameters()
                )
        ));
        if (searchEnabled) {
            tools.add(new VertexAIFunction(
                    TOOL_SEARCH,
                    "Search the web via Tavily and return concise snippets with URLs. Use this when external references are needed.",
                    searchToolParameters()
            ));
        }
        if (dynamicRegistryEnabled) {
            tools.addAll(List.of(
                    new VertexAIFunction(
                            TOOL_LIST_DYNAMIC_CONTENT,
                            "List currently active dynamic placeholder entries and free slots.",
                            noArgToolParameters()
                    ),
                    new VertexAIFunction(
                            TOOL_LIST_DYNAMIC_PROPERTIES,
                            "List editable property keys/ranges for dynamic items, blocks, and fluids.",
                            dynamicPropertyListToolParameters()
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

    private JsonObject listCommandsToolParameters() {
        JsonObject properties = new JsonObject();
        JsonObject modId = new JsonObject();
        modId.addProperty("type", "string");
        modId.addProperty("description", "Optional installed mod id filter, for example `kubejs`.");
        properties.add("mod_id", modId);
        return objectToolParameters(properties);
    }

    private JsonObject fetchModrinthToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject modId = new JsonObject();
        modId.addProperty("type", "string");
        modId.addProperty("description", "Installed mod id to resolve a Modrinth page for, for example `kubejs`.");
        properties.add("mod_id", modId);

        return objectToolParameters(properties, "mod_id");
    }

    private JsonObject fetchUrlToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject url = new JsonObject();
        url.addProperty("type", "string");
        url.addProperty("description", "HTTP(S) URL to fetch. HTML will be converted to Markdown.");
        properties.add("url", url);

        return objectToolParameters(properties, "url");
    }

    private JsonObject searchToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Web search query.");
        properties.add("query", query);

        JsonObject maxResults = new JsonObject();
        maxResults.addProperty("type", "integer");
        maxResults.addProperty("description", "Optional number of search results to return (1-10, default 5).");
        maxResults.addProperty("minimum", 1);
        maxResults.addProperty("maximum", 10);
        properties.add("max_results", maxResults);

        return objectToolParameters(properties, "query");
    }

    private JsonObject questionToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.addProperty("description", "Question for the player. Keep concise.");
        properties.add("question", question);

        JsonObject options = new JsonObject();
        options.addProperty("type", "array");
        options.addProperty("description", "Preset options (1-5 items). Do not include Other/Skip; MineClawd appends a built-in Other option and handles skip.");
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

    private JsonObject dynamicPropertyListToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        JsonArray values = new JsonArray();
        values.add("all");
        values.add("item");
        values.add("items");
        values.add("block");
        values.add("blocks");
        values.add("fluid");
        values.add("fluids");
        type.add("enum", values);
        type.addProperty("description", "Optional property group filter. Omit for all.");
        properties.add("type", type);

        return objectToolParameters(properties);
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
        material.addProperty("description", "Vanilla material item id, e.g. minecraft:diamond.");
        properties.add("material_item", material);

        JsonObject throwable = new JsonObject();
        throwable.addProperty("type", "boolean");
        throwable.addProperty("description", "Whether this item behaves like a throwable projectile.");
        properties.add("throwable", throwable);

        JsonObject throwSpeed = new JsonObject();
        throwSpeed.addProperty("type", "number");
        throwSpeed.addProperty("description", "Projectile speed (0.1..4.0).");
        properties.add("throw_speed", throwSpeed);

        JsonObject throwInaccuracy = new JsonObject();
        throwInaccuracy.addProperty("type", "number");
        throwInaccuracy.addProperty("description", "Projectile inaccuracy/divergence (0.0..5.0).");
        properties.add("throw_inaccuracy", throwInaccuracy);

        JsonObject throwCooldownTicks = new JsonObject();
        throwCooldownTicks.addProperty("type", "integer");
        throwCooldownTicks.addProperty("description", "Optional cooldown after throw (0..1200 ticks).");
        properties.add("throw_cooldown_ticks", throwCooldownTicks);

        JsonObject consumeOnThrow = new JsonObject();
        consumeOnThrow.addProperty("type", "boolean");
        consumeOnThrow.addProperty("description", "Whether to consume one item per throw (default true).");
        properties.add("consume_on_throw", consumeOnThrow);

        JsonObject maxCount = new JsonObject();
        maxCount.addProperty("type", "integer");
        maxCount.addProperty("description", "Stack size override (0..99). 0 means follow material item.");
        properties.add("max_count", maxCount);

        JsonObject useAction = new JsonObject();
        useAction.addProperty("type", "string");
        JsonArray useActionEnum = new JsonArray();
        useActionEnum.add("material");
        useActionEnum.add("none");
        useActionEnum.add("eat");
        useActionEnum.add("drink");
        useActionEnum.add("bow");
        useActionEnum.add("spear");
        useActionEnum.add("crossbow");
        useActionEnum.add("spyglass");
        useActionEnum.add("toot_horn");
        useActionEnum.add("brush");
        useActionEnum.add("block");
        useAction.add("enum", useActionEnum);
        useAction.addProperty("description", "Item use animation override.");
        properties.add("use_action", useAction);

        JsonObject useTimeTicks = new JsonObject();
        useTimeTicks.addProperty("type", "integer");
        useTimeTicks.addProperty("description", "Use-time override (0..72000 ticks). 0 means follow material item.");
        properties.add("use_time_ticks", useTimeTicks);

        JsonObject glintMode = new JsonObject();
        glintMode.addProperty("type", "string");
        JsonArray glintEnum = new JsonArray();
        glintEnum.add("material");
        glintEnum.add("true");
        glintEnum.add("false");
        glintMode.add("enum", glintEnum);
        glintMode.addProperty("description", "Enchantment glint mode.");
        properties.add("glint_mode", glintMode);

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
        material.addProperty("description", "Vanilla material block id, e.g. minecraft:stone.");
        properties.add("material_block", material);

        JsonObject friction = new JsonObject();
        friction.addProperty("type", "number");
        friction.addProperty("description", "Block friction/slipperiness (0.0..2.0).");
        properties.add("friction", friction);

        JsonObject velocityMultiplier = new JsonObject();
        velocityMultiplier.addProperty("type", "number");
        velocityMultiplier.addProperty("description", "Entity velocity multiplier on this block (0.0..10.0).");
        properties.add("velocity_multiplier", velocityMultiplier);

        JsonObject jumpVelocityMultiplier = new JsonObject();
        jumpVelocityMultiplier.addProperty("type", "number");
        jumpVelocityMultiplier.addProperty("description", "Entity jump multiplier on this block (0.0..10.0).");
        properties.add("jump_velocity_multiplier", jumpVelocityMultiplier);

        JsonObject blastResistance = new JsonObject();
        blastResistance.addProperty("type", "number");
        blastResistance.addProperty("description", "Blast resistance override (0.0..1200.0).");
        properties.add("blast_resistance", blastResistance);

        JsonObject useMaterialSounds = new JsonObject();
        useMaterialSounds.addProperty("type", "boolean");
        useMaterialSounds.addProperty("description", "If true, use sound group from material block.");
        properties.add("use_material_sounds", useMaterialSounds);

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
        material.addProperty("description", "Vanilla material fluid id, e.g. minecraft:water.");
        properties.add("material_fluid", material);

        JsonObject color = new JsonObject();
        color.addProperty("type", "string");
        color.addProperty("description", "Custom fluid tint color in #RRGGBB or `default`.");
        properties.add("color", color);

        JsonObject tickRate = new JsonObject();
        tickRate.addProperty("type", "integer");
        tickRate.addProperty("description", "Fluid tick rate (1..200).");
        properties.add("tick_rate", tickRate);

        JsonObject flowSpeed = new JsonObject();
        flowSpeed.addProperty("type", "integer");
        flowSpeed.addProperty("description", "Fluid flow speed / max flow distance (1..16).");
        properties.add("flow_speed", flowSpeed);

        JsonObject levelDecrease = new JsonObject();
        levelDecrease.addProperty("type", "integer");
        levelDecrease.addProperty("description", "Fluid level decrease per block (1..8).");
        properties.add("level_decrease_per_block", levelDecrease);

        JsonObject blastResistance = new JsonObject();
        blastResistance.addProperty("type", "number");
        blastResistance.addProperty("description", "Fluid blast resistance (0.0..1200.0).");
        properties.add("blast_resistance", blastResistance);

        JsonObject infinite = new JsonObject();
        infinite.addProperty("type", "boolean");
        infinite.addProperty("description", "Whether this fluid is infinite.");
        properties.add("infinite", infinite);

        return objectToolParameters(properties, "name");
    }

    private JsonObject dynamicItemUpdateToolParameters() {
        JsonObject properties = dynamicItemToolParameters().get("properties").getAsJsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        return objectToolParameters(properties, "slot");
    }

    private JsonObject dynamicBlockUpdateToolParameters() {
        JsonObject properties = dynamicBlockToolParameters().get("properties").getAsJsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

        return objectToolParameters(properties, "slot");
    }

    private JsonObject dynamicFluidUpdateToolParameters() {
        JsonObject properties = dynamicFluidToolParameters().get("properties").getAsJsonObject();

        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "Existing placeholder slot index (1-30).");
        slot.addProperty("minimum", 1);
        slot.addProperty("maximum", 30);
        properties.add("slot", slot);

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

    private JsonObject assetUpsertToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "Optional asset id. Omit to auto-generate from category/name.");
        properties.add("id", id);

        JsonObject category = new JsonObject();
        category.addProperty("type", "string");
        JsonArray categoryEnum = new JsonArray();
        categoryEnum.add("entities");
        categoryEnum.add("items_blocks_fluids");
        categoryEnum.add("special_items");
        categoryEnum.add("commands");
        categoryEnum.add("game_mechanics");
        category.add("enum", categoryEnum);
        category.addProperty("description", "Asset category.");
        properties.add("category", category);

        JsonObject name = new JsonObject();
        name.addProperty("type", "string");
        name.addProperty("description", "Display name for this asset.");
        properties.add("name", name);

        JsonObject summary = new JsonObject();
        summary.addProperty("type", "string");
        summary.addProperty("description", "Optional one-sentence summary.");
        properties.add("summary", summary);

        JsonObject scriptPath = new JsonObject();
        scriptPath.addProperty("type", "string");
        scriptPath.addProperty("description", "Optional script path, for example `commands/fly.js`.");
        properties.add("script_path", scriptPath);

        JsonObject details = new JsonObject();
        details.addProperty("type", "string");
        details.addProperty("description", "Optional free-form details.");
        properties.add("details", details);

        JsonObject entityUuid = new JsonObject();
        entityUuid.addProperty("type", "string");
        entityUuid.addProperty("description", "Entity UUID for `entities` category.");
        properties.add("entity_uuid", entityUuid);

        JsonObject entityDimension = new JsonObject();
        entityDimension.addProperty("type", "string");
        entityDimension.addProperty("description", "Entity dimension id, for example `minecraft:overworld`.");
        properties.add("entity_dimension", entityDimension);

        JsonObject entityX = new JsonObject();
        entityX.addProperty("type", "number");
        entityX.addProperty("description", "Entity X coordinate.");
        properties.add("entity_x", entityX);

        JsonObject entityY = new JsonObject();
        entityY.addProperty("type", "number");
        entityY.addProperty("description", "Entity Y coordinate.");
        properties.add("entity_y", entityY);

        JsonObject entityZ = new JsonObject();
        entityZ.addProperty("type", "number");
        entityZ.addProperty("description", "Entity Z coordinate.");
        properties.add("entity_z", entityZ);

        JsonObject contentId = new JsonObject();
        contentId.addProperty("type", "string");
        contentId.addProperty("description", "Dynamic content id for `items_blocks_fluids`, for example `mineclawd:dynamic_item_001`.");
        properties.add("content_id", contentId);

        JsonObject specialItemId = new JsonObject();
        specialItemId.addProperty("type", "string");
        specialItemId.addProperty("description", "Item id for `special_items`.");
        properties.add("special_item_id", specialItemId);

        JsonObject specialItemNbt = new JsonObject();
        specialItemNbt.addProperty("type", "string");
        specialItemNbt.addProperty("description", "Optional NBT/component suffix used when giving special item.");
        properties.add("special_item_nbt", specialItemNbt);

        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description", "Command text for `commands` category.");
        properties.add("command", command);

        JsonObject sessionId = new JsonObject();
        sessionId.addProperty("type", "string");
        sessionId.addProperty("description", "Optional session id that created/owns this asset.");
        properties.add("session_id", sessionId);

        return objectToolParameters(properties, "category", "name");
    }

    private JsonObject assetRemoveToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "Asset id or reference to remove.");
        properties.add("id", id);

        JsonObject reference = new JsonObject();
        reference.addProperty("type", "string");
        reference.addProperty("description", "Alias for id.");
        properties.add("reference", reference);

        return objectToolParameters(properties, "id");
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

    private String assetOwnerKey() {
        return GLOBAL_ASSET_OWNER_KEY;
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
        String installedMods = buildInstalledModsInfo();
        StringBuilder prompt = new StringBuilder(basePrompt);
        if (!env.isBlank()) {
            prompt.append("\n\nEnvironment:\n").append(env);
        }
        if (!installedMods.isBlank()) {
            prompt.append("\n\nInstalled Mods:\n").append(installedMods);
        }
        if (hasConfiguredTavilyKey(config)) {
            prompt.append("\n\nSearch tool status:\n")
                    .append("`search` is enabled (Tavily API key is configured). Use it whenever external facts are uncertain.");
        } else {
            prompt.append("\n\nSearch tool status:\n")
                    .append("`search` is disabled because `tavily-api-key` is not configured. ")
                    .append("If the player asks for web search, explain this and ask an operator to configure `tavily-api-key`.");
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
        prompt.append("\n\n")
                .append(ASSET_TRACKING_PROMPT_APPENDIX);
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

    private String buildInstalledModsInfo() {
        Collection<dev.architectury.platform.Mod> mods = Platform.getMods();
        if (mods == null || mods.isEmpty()) {
            return "";
        }
        Map<String, dev.architectury.platform.Mod> byId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (dev.architectury.platform.Mod mod : mods) {
            if (mod == null || mod.getModId() == null || mod.getModId().isBlank()) {
                continue;
            }
            byId.putIfAbsent(mod.getModId(), mod);
        }
        if (byId.isEmpty()) {
            return "";
        }

        final int maxLines = 220;
        final int maxChars = 14_000;
        StringBuilder out = new StringBuilder();
        out.append("Loaded mods (").append(byId.size()).append("):\n");
        int count = 0;
        for (dev.architectury.platform.Mod mod : byId.values()) {
            if (mod == null) {
                continue;
            }
            count++;
            if (count > maxLines) {
                out.append("- ... truncated; total loaded mods: ").append(byId.size()).append("\n");
                break;
            }
            String id = mod.getModId() == null ? "" : mod.getModId().trim();
            String version = mod.getVersion() == null ? "" : mod.getVersion().trim();
            String name = mod.getName() == null ? "" : mod.getName().trim();

            out.append("- ").append(id);
            if (!version.isBlank()) {
                out.append(" v").append(version);
            }
            if (!name.isBlank() && !name.equalsIgnoreCase(id)) {
                out.append(" (").append(name).append(")");
            }
            out.append("\n");
            if (out.length() >= maxChars) {
                out.append("- ... truncated due to prompt size.\n");
                break;
            }
        }
        return out.toString().trim();
    }

    private boolean hasConfiguredTavilyKey(MineClawdConfig config) {
        return config != null && config.tavilyApiKey != null && !config.tavilyApiKey.isBlank();
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

    private ToolStatusDescriptor announceToolCallProgress(
            ServerCommandSource source,
            AgentRuntime runtime,
            String toolName,
            JsonObject args
    ) {
        if (source == null || runtime == null || isRuntimeInactive(runtime)) {
            return null;
        }
        ToolStatusDescriptor descriptor = buildToolStatusDescriptor(toolName, args);
        if (descriptor == null || descriptor.shortText() == null || descriptor.shortText().isBlank()) {
            return null;
        }
        if (runtime.clientStreamEnabled()) {
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.TOOL_STATUS, buildToolStatusPayload(descriptor));
            return descriptor;
        }
        sendToolStatusChatLine(source, descriptor);
        return descriptor;
    }

    private void clearToolCallProgress(ServerCommandSource source, AgentRuntime runtime) {
        clearToolCallProgress(source, runtime, null);
    }

    private void clearToolCallProgress(
            ServerCommandSource source,
            AgentRuntime runtime,
            ToolStatusDescriptor completionDescriptor
    ) {
        if (source == null || runtime == null || isRuntimeInactive(runtime)) {
            return;
        }
        if (runtime.clientStreamEnabled()) {
            String payload = completionDescriptor == null ? "" : buildToolStatusPayload(completionDescriptor);
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.TOOL_STATUS_CLEAR, payload);
            return;
        }
        if (completionDescriptor != null && completionDescriptor.shortText() != null && !completionDescriptor.shortText().isBlank()) {
            sendToolStatusChatLine(source, completionDescriptor);
        }
    }

    private String buildToolStatusPayload(ToolStatusDescriptor descriptor) {
        JsonObject payload = new JsonObject();
        payload.addProperty("short", descriptor == null ? "" : safeForLog(descriptor.shortText()));
        payload.addProperty("hover", descriptor == null ? "" : trimForTrace(descriptor.hoverText()));
        return payload.toString();
    }

    private void sendToolStatusChatLine(ServerCommandSource source, ToolStatusDescriptor descriptor) {
        if (source == null || descriptor == null || descriptor.shortText() == null || descriptor.shortText().isBlank()) {
            return;
        }
        String shortText = descriptor.shortText().trim();
        if (shortText.length() > TOOL_STATUS_CHAT_MAX_CHARS) {
            shortText = shortText.substring(0, TOOL_STATUS_CHAT_MAX_CHARS).trim() + "...";
        }
        MutableText line = Text.literal(shortText).formatted(Formatting.GRAY);
        String hover = descriptor.hoverText();
        if (hover != null && !hover.isBlank()) {
            line.setStyle(line.getStyle().withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(trimForTrace(hover)))));
        }

        if (source.getEntity() instanceof ServerPlayerEntity player) {
            player.sendMessage(line, false);
            return;
        }
        source.sendFeedback(() -> line, false);
    }

    private ToolStatusDescriptor buildToolStatusDescriptor(String toolName, JsonObject args) {
        String normalizedName = toolName == null ? "" : toolName.trim();
        String shortText;
        String hoverText = "";

        switch (normalizedName) {
            case TOOL_FETCH_URL -> {
                String rawUrl = readOptionalStringArg(args, "url");
                String host = extractUrlHost(rawUrl);
                shortText = host.isBlank() ? "Fetching URL" : "Fetching " + host;
                hoverText = rawUrl.isBlank() ? "Fetching URL content." : "URL: " + rawUrl.trim();
            }
            case TOOL_FETCH_MODRINTH -> {
                String modId = readOptionalStringArg(args, "mod_id");
                shortText = modId.isBlank() ? "Fetching Modrinth page" : "Fetching Modrinth page for " + modId;
                hoverText = modId.isBlank() ? "Fetching Modrinth project page." : "Modrinth mod_id: " + modId;
            }
            case TOOL_EXECUTE_COMMAND -> {
                String command = readOptionalStringArg(args, "command");
                shortText = "Executing command " + summarizeCommandForStatus(command);
                hoverText = command.isBlank() ? "" : "Command: " + normalizeCommandForHover(command);
            }
            case TOOL_APPLY_INSTANT_SERVER_SCRIPT, LEGACY_TOOL_KUBEJS_EVAL -> {
                shortText = "Applying instant script";
                hoverText = "Executing KubeJS instant script (code hidden).";
            }
            case TOOL_LIST_COMMANDS -> {
                String modId = readOptionalStringArg(args, "mod_id");
                shortText = modId.isBlank() ? "Listing commands" : "Listing commands for " + modId;
                hoverText = modId.isBlank() ? "Listing server root commands." : "List commands using mod_id filter: " + modId;
            }
            case TOOL_LIST_SERVER_SCRIPTS -> {
                shortText = "Listing server scripts";
                hoverText = "Listing files under kubejs/server_scripts/mineclawd/.";
            }
            case TOOL_READ_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Reading server script" : "Reading script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
            }
            case TOOL_WRITE_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Writing server script" : "Writing script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Writing a server script file." : "Path: " + path;
            }
            case TOOL_DELETE_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Deleting server script" : "Deleting script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Deleting a server script file." : "Path: " + path;
            }
            case TOOL_RELOAD_GAME -> {
                shortText = "Reloading game scripts";
                hoverText = "Running /reload and checking KubeJS loading errors.";
            }
            case TOOL_SYNC_COMMAND_TREE -> {
                shortText = "Syncing command tree";
                hoverText = "Refreshing command suggestions for online players.";
            }
            case TOOL_ASK_USER -> {
                shortText = "Asking a clarification question";
                hoverText = readOptionalStringArg(args, "question");
            }
            case TOOL_LIST_DYNAMIC_CONTENT -> {
                shortText = "Inspecting dynamic content slots";
            }
            case TOOL_LIST_DYNAMIC_PROPERTIES -> {
                shortText = "Inspecting dynamic properties";
            }
            case TOOL_REGISTER_DYNAMIC_ITEM, TOOL_REGISTER_DYNAMIC_BLOCK, TOOL_REGISTER_DYNAMIC_FLUID -> {
                shortText = "Registering dynamic content";
            }
            case TOOL_UPDATE_DYNAMIC_ITEM, TOOL_UPDATE_DYNAMIC_BLOCK, TOOL_UPDATE_DYNAMIC_FLUID -> {
                shortText = "Updating dynamic content";
            }
            case TOOL_UNREGISTER_DYNAMIC_CONTENT -> {
                shortText = "Unregistering dynamic content";
            }
            case TOOL_LIST_ASSETS -> {
                shortText = "Listing tracked assets";
            }
            case TOOL_UPSERT_ASSET_RECORD -> {
                shortText = "Updating tracked asset";
            }
            case TOOL_REMOVE_ASSET_RECORD -> {
                shortText = "Removing tracked asset";
            }
            default -> {
                shortText = "Running task step";
                hoverText = normalizedName.isBlank() ? "" : "Tool: " + normalizedName;
            }
        }

        return new ToolStatusDescriptor(safeForLog(shortText), safeForLog(hoverText));
    }

    private ToolStatusDescriptor buildToolStatusCompletedDescriptor(String toolName, JsonObject args) {
        String normalizedName = toolName == null ? "" : toolName.trim();
        String shortText;
        String hoverText = "";

        switch (normalizedName) {
            case TOOL_FETCH_URL -> {
                String rawUrl = readOptionalStringArg(args, "url");
                String host = extractUrlHost(rawUrl);
                shortText = host.isBlank() ? "Fetched URL" : "Fetched " + host;
                hoverText = rawUrl.isBlank() ? "Fetched URL content." : "URL: " + rawUrl.trim();
            }
            case TOOL_FETCH_MODRINTH -> {
                String modId = readOptionalStringArg(args, "mod_id");
                shortText = modId.isBlank() ? "Fetched Modrinth page" : "Fetched Modrinth page for " + modId;
                hoverText = modId.isBlank() ? "Fetched Modrinth project page." : "Modrinth mod_id: " + modId;
            }
            case TOOL_EXECUTE_COMMAND -> {
                String command = readOptionalStringArg(args, "command");
                shortText = "Executed command " + summarizeCommandForStatus(command);
                hoverText = command.isBlank() ? "" : "Command: " + normalizeCommandForHover(command);
            }
            case TOOL_APPLY_INSTANT_SERVER_SCRIPT, LEGACY_TOOL_KUBEJS_EVAL -> {
                shortText = "Applied instant script";
                hoverText = "Executed KubeJS instant script (code hidden).";
            }
            case TOOL_LIST_COMMANDS -> {
                String modId = readOptionalStringArg(args, "mod_id");
                shortText = modId.isBlank() ? "Listed commands" : "Listed commands for " + modId;
                hoverText = modId.isBlank() ? "Listed server root commands." : "List commands using mod_id filter: " + modId;
            }
            case TOOL_LIST_SERVER_SCRIPTS -> {
                shortText = "Listed server scripts";
                hoverText = "Listed files under kubejs/server_scripts/mineclawd/.";
            }
            case TOOL_READ_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Read server script" : "Read script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
            }
            case TOOL_WRITE_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Wrote server script" : "Wrote script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Wrote a server script file." : "Path: " + path;
            }
            case TOOL_DELETE_SERVER_SCRIPT -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Deleted server script" : "Deleted script " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Deleted a server script file." : "Path: " + path;
            }
            case TOOL_RELOAD_GAME -> {
                shortText = "Reloaded game scripts";
                hoverText = "Ran /reload and checked KubeJS loading errors.";
            }
            case TOOL_SYNC_COMMAND_TREE -> {
                shortText = "Synced command tree";
                hoverText = "Refreshed command suggestions for online players.";
            }
            case TOOL_ASK_USER -> {
                shortText = "Asked a clarification question";
                hoverText = readOptionalStringArg(args, "question");
            }
            case TOOL_LIST_DYNAMIC_CONTENT -> {
                shortText = "Inspected dynamic content slots";
            }
            case TOOL_LIST_DYNAMIC_PROPERTIES -> {
                shortText = "Inspected dynamic properties";
            }
            case TOOL_REGISTER_DYNAMIC_ITEM, TOOL_REGISTER_DYNAMIC_BLOCK, TOOL_REGISTER_DYNAMIC_FLUID -> {
                shortText = "Registered dynamic content";
            }
            case TOOL_UPDATE_DYNAMIC_ITEM, TOOL_UPDATE_DYNAMIC_BLOCK, TOOL_UPDATE_DYNAMIC_FLUID -> {
                shortText = "Updated dynamic content";
            }
            case TOOL_UNREGISTER_DYNAMIC_CONTENT -> {
                shortText = "Unregistered dynamic content";
            }
            case TOOL_LIST_ASSETS -> {
                shortText = "Listed tracked assets";
            }
            case TOOL_UPSERT_ASSET_RECORD -> {
                shortText = "Updated tracked asset";
            }
            case TOOL_REMOVE_ASSET_RECORD -> {
                shortText = "Removed tracked asset";
            }
            default -> {
                shortText = "Completed task step";
                hoverText = normalizedName.isBlank() ? "" : "Tool: " + normalizedName;
            }
        }

        return new ToolStatusDescriptor(safeForLog(shortText), safeForLog(hoverText));
    }

    private String summarizePathTail(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isBlank()) {
            return "file";
        }
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }

    private String summarizeCommandForStatus(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isBlank()) {
            return "command";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        int space = normalized.indexOf(' ');
        if (space > 0) {
            return normalized.substring(0, space);
        }
        return normalized;
    }

    private String normalizeCommandForHover(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isBlank()) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized;
    }

    private String extractUrlHost(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.startsWith("www.")) {
                lower = lower.substring(4);
            }
            return lower;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void debugLog(AgentRuntime runtime, String format, Object... args) {
        if (runtime == null || !runtime.debug()) {
            return;
        }
        LOGGER.info("[MineClawd Debug] [session:{}] {}", runtime.sessionId(), String.format(format, args));
    }

    private void traceLog(AgentRuntime runtime, String channel, String message) {
        String sessionId = runtime == null || runtime.sessionId() == null || runtime.sessionId().isBlank()
                ? "unknown"
                : runtime.sessionId();
        String safeChannel = channel == null || channel.isBlank() ? "TRACE" : channel.trim();
        LOGGER.info("[MineClawd Trace] [session:{}] [{}] {}", sessionId, safeChannel, trimForTrace(message));
    }

    private String trimForTrace(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.length() <= TRACE_LOG_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, TRACE_LOG_MAX_CHARS).trim() + " ...[truncated]";
    }

    private String safeForLog(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private void agentLog(AgentRuntime runtime, String format, Object... args) {
        if (runtime == null) {
            return;
        }
        LOGGER.info("[MineClawd] [session:{}] {}", runtime.sessionId(), String.format(format, args));
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

    private boolean isRuntimeActive(AgentRuntime runtime) {
        if (runtime == null || runtime.ownerKey() == null || runtime.ownerKey().isBlank()) {
            return false;
        }
        String requestId = runtime.requestId();
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        if (CANCELLED_REQUEST_IDS.contains(requestId)) {
            return false;
        }
        return requestId.equals(ACTIVE_REQUESTS.get(runtime.ownerKey()));
    }

    private boolean isRuntimeInactive(AgentRuntime runtime) {
        return !isRuntimeActive(runtime);
    }

    private void trackActiveNetworkRequest(AgentRuntime runtime, CompletableFuture<?> requestFuture) {
        if (runtime == null || requestFuture == null || runtime.requestId() == null || runtime.requestId().isBlank()) {
            return;
        }
        ACTIVE_NETWORK_REQUESTS.put(runtime.requestId(), requestFuture);
    }

    private void clearActiveNetworkRequest(AgentRuntime runtime, CompletableFuture<?> requestFuture) {
        if (runtime == null || requestFuture == null || runtime.requestId() == null || runtime.requestId().isBlank()) {
            return;
        }
        ACTIVE_NETWORK_REQUESTS.remove(runtime.requestId(), requestFuture);
    }

    private void cleanupInactiveRuntime(AgentRuntime runtime) {
        if (runtime == null || runtime.requestId() == null || runtime.requestId().isBlank()) {
            return;
        }
        ACTIVE_NETWORK_REQUESTS.remove(runtime.requestId());
        CANCELLED_REQUEST_IDS.remove(runtime.requestId());
    }

    private void scheduleRateLimitRetry(ServerCommandSource source, AgentRuntime runtime, int retryCount, Runnable action) {
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        long delay = RATE_LIMIT_BACKOFF_MS * (retryCount + 1L);
        debugLog(runtime, "Rate limited. Scheduling retry %d in %d ms.", retryCount + 1, delay);
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
            if (isRuntimeInactive(runtime)) {
                cleanupInactiveRuntime(runtime);
                return;
            }
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
        CompletableFuture<?> inFlight = ACTIVE_NETWORK_REQUESTS.remove(runtime.requestId());
        if (inFlight != null && !inFlight.isDone()) {
            inFlight.cancel(true);
        }
        CANCELLED_REQUEST_IDS.remove(runtime.requestId());
        debugLog(runtime, "Request finished.");
        agentLog(runtime, "Request finished.");
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
        if (isRuntimeInactive(runtime)) {
            cleanupInactiveRuntime(runtime);
            return;
        }
        rollbackFailedPrompt(session, runtime, provider);
        String errorMessage = sanitizeErrorMessage(rawError);
        if (session != null && runtime.interactiveErrorActions()) {
            FailedRequestContext failed = registerFailedRequest(runtime, provider);
            if (runtime.clientStreamEnabled()) {
                sendAgentStreamEvent(source, runtime, AgentStreamEventType.ERROR, buildClientStreamErrorPayload(errorMessage, failed));
            } else {
                sendLlmErrorWithActions(source, failed, errorMessage);
            }
        } else {
            if (runtime.clientStreamEnabled()) {
                sendAgentStreamEvent(source, runtime, AgentStreamEventType.ERROR, "Oops! " + errorMessage);
            } else {
                sendAgentLine(source, Text.literal("Oops! " + errorMessage).formatted(Formatting.RED));
            }
        }
        if (runtime.clientStreamEnabled()) {
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.DONE, "");
        }
        sendTaskStatus(source, false);
        finishActiveRequest(runtime);
    }

    private String buildClientStreamErrorPayload(String errorMessage, FailedRequestContext failed) {
        String normalized = errorMessage == null || errorMessage.isBlank() ? "unknown error" : errorMessage;
        StringBuilder builder = new StringBuilder("Oops! ").append(normalized);
        if (failed != null && failed.token() != null && !failed.token().isBlank()) {
            builder.append("\nRetry token: ").append(failed.token());
        }
        return builder.toString();
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
        NbtCompound nbt = book.getOrCreateNbt();
        nbt.putString(WrittenBookItem.TITLE_KEY, HISTORY_BOOK_TITLE);
        nbt.putString(WrittenBookItem.AUTHOR_KEY, "MineClawd");
        nbt.putBoolean(WrittenBookItem.RESOLVED_KEY, true);

        NbtList pages = new NbtList();
        for (Text page : buildHistoryPages(session, entries)) {
            String json = Text.Serializer.toJson(page);
            pages.add(NbtString.of(json == null ? "{\"text\":\"\"}" : json));
        }
        if (pages.isEmpty()) {
            pages.add(NbtString.of("{\"text\":\"No history.\"}"));
        }
        nbt.put(WrittenBookItem.PAGES_KEY, pages);
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
        if (pages.size() <= WrittenBookItem.MAX_PAGES) {
            return pages;
        }
        List<Text> trimmed = new ArrayList<>(pages.subList(0, WrittenBookItem.MAX_PAGES - 1));
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
        if (!canUseGui(player, MineClawdNetworking.OPEN_HISTORY_BOOK)) {
            sendAgentMessage(source, "Client history UI unavailable (client mod missing or `Enable GUI` is off).");
            return;
        }

        List<Text> pages = buildHistoryPages(session, entries);
        JsonArray pageArray = new JsonArray();
        for (Text page : pages) {
            String json = page == null ? null : Text.Serializer.toJson(page);
            String safeJson = json == null ? "{\"text\":\"\"}" : json;
            try {
                pageArray.add(JsonParser.parseString(safeJson));
            } catch (Exception ignored) {
                pageArray.add(safeJson);
            }
        }
        if (pageArray.isEmpty()) {
            String fallbackJson = Text.Serializer.toJson(Text.literal("No history."));
            if (fallbackJson == null) {
                pageArray.add("No history.");
            } else {
                try {
                    pageArray.add(JsonParser.parseString(fallbackJson));
                } catch (Exception ignored) {
                    pageArray.add(fallbackJson);
                }
            }
        }
        JsonObject payloadObject = new JsonObject();
        payloadObject.add("pages", pageArray);
        String payloadString = payloadObject.toString();
        if (payloadString.length() > HISTORY_PACKET_MAX_CHARS) {
            payloadObject = new JsonObject();
            JsonArray fallbackPages = new JsonArray();
            String overflowJson = Text.Serializer.toJson(
                    Text.literal("History is too large to display in one transfer.")
                            .formatted(Formatting.RED)
            );
            if (overflowJson == null) {
                fallbackPages.add("History is too large to display in one transfer.");
            } else {
                try {
                    fallbackPages.add(JsonParser.parseString(overflowJson));
                } catch (Exception ignored) {
                    fallbackPages.add(overflowJson);
                }
            }
            payloadObject.add("pages", fallbackPages);
            payloadString = payloadObject.toString();
        }

        var payload = new PacketByteBuf(Unpooled.buffer());
        payload.writeString(payloadString, HISTORY_PACKET_MAX_CHARS);
        if (!sendPacketToPlayer(player, MineClawdNetworking.OPEN_HISTORY_BOOK, payload, "open_history_book")) {
            sendAgentMessage(source, "History book packet failed to send; use `/mineclawd sessions list` for chat fallback.");
        }
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

    private boolean canUseGui(ServerPlayerEntity player, Identifier channel) {
        if (!canSendToClient(player, channel)) {
            return false;
        }
        if (player == null) {
            return false;
        }
        return !Boolean.FALSE.equals(CLIENT_GUI_ENABLED.get(player.getUuid()));
    }

    private boolean canUseAssistiveOverlay(ServerPlayerEntity player, Identifier channel) {
        if (!canUseGui(player, channel)) {
            return false;
        }
        if (player == null) {
            return false;
        }
        return PLAYER_SETTINGS.isAssistiveTouchEnabled(player.getUuidAsString());
    }

    private boolean sendPacketToPlayer(ServerPlayerEntity player, Identifier channel, PacketByteBuf payload, String context) {
        if (player == null || channel == null || payload == null) {
            return false;
        }
        try {
            NetworkManager.sendToPlayer(player, channel, payload);
            return true;
        } catch (Throwable throwable) {
            CLIENT_MOD_READY.remove(player.getUuid());
            CLIENT_GUI_ENABLED.remove(player.getUuid());
            LOGGER.warn(
                    "[MineClawd] Failed to send packet context={} player={} channel={} reason={}",
                    context,
                    player.getName().getString(),
                    channel,
                    summarizeThrowable(throwable)
            );
            return false;
        }
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

    private record ToolStatusDescriptor(String shortText, String hoverText) {
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
            boolean interactiveErrorActions,
            boolean clientStreamEnabled
    ) {
    }
}
