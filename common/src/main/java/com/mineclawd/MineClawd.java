package com.mineclawd;

import de.themoep.minedown.adventure.MineDown;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import com.mineclawd.files.WorkspaceFileToolExecutor;
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
import com.mineclawd.mod.ModDocsToolExecutor;
import com.mineclawd.persona.PersonaManager;
import com.mineclawd.persona.PersonaManager.Persona;
import com.mineclawd.player.PlayerSettingsManager;
import com.mineclawd.player.PlayerSettingsManager.RequestBroadcastTarget;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionResponsePayload;
import com.mineclawd.session.SessionManager;
import com.mineclawd.session.SessionAttachment;
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
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Base64;
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
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, UploadAssembly>> PENDING_UPLOAD_ASSEMBLIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> CLIENT_MOD_READY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> CLIENT_GUI_ENABLED = new ConcurrentHashMap<>();

    private static final String TOOL_APPLY_INSTANT_SERVER_SCRIPT = "apply-instant-server-script";
    private static final String TOOL_ASK_USER = "ask-user-question";
    private static final String TOOL_EXECUTE_COMMAND = "execute-command";
    private static final String TOOL_SEARCH = "search";
    private static final String TOOL_LIST_COMMANDS = "list_commands";
    private static final String TOOL_FETCH_MODRINTH = "fetch_modrinth";
    private static final String TOOL_FETCH_URL = "fetch_url";
    private static final String TOOL_LIST_FILES = "list-files";
    private static final String TOOL_READ_FILES = "read-files";
    private static final String TOOL_WRITE_FILES = "write-files";
    private static final String TOOL_COPY_FILES = "copy-files";
    private static final String TOOL_MOVE_FILES = "move-files";
    private static final String TOOL_GREP = "grep";
    private static final String TOOL_CURL = "curl";
    private static final String TOOL_READ_IMAGE = "read-image";
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
    private static final int PROMPT_PACKET_MAX_CHARS = 32_767;
    private static final int UPLOAD_PACKET_ID_MAX_CHARS = 64;
    private static final int UPLOAD_PACKET_PATH_MAX_CHARS = 512;
    private static final int UPLOAD_PACKET_NAME_MAX_CHARS = 256;
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final int UPLOAD_CHUNK_MAX_BYTES = 128 * 1024;
    private static final int MAX_UPLOAD_CHUNKS = (MAX_UPLOAD_BYTES + UPLOAD_CHUNK_MAX_BYTES - 1) / UPLOAD_CHUNK_MAX_BYTES;
    private static final long UPLOAD_ASSEMBLY_TTL_MS = TimeUnit.MINUTES.toMillis(3);
    private static final int MAX_PROMPT_ATTACHMENTS = 12;
    private static final int TOOL_STATUS_CHAT_MAX_CHARS = 180;
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

        // ── 1. IDENTITY ──────────────────────────────────────────────────────────
        "You are MineClawd, an advanced Minecraft in-game agent specialized in KubeJS scripting",
        "for Minecraft 1.20.1 and 1.21.1.",
        "Project identity is always MineClawd. Persona files may define another character name or",
        "voice — follow that persona style, but keep project/tool identity and command names unchanged.",
        "",

        // ── 2. EXECUTION PROTOCOL ────────────────────────────────────────────────
        "*** EXECUTION PROTOCOL ***",
        "Follow these steps on every task:",
        "",
        "STEP 1 — CLARIFY BEFORE ACTING",
        "  If key requirements are ambiguous or multiple valid implementations exist, call",
        "  `ask-user-question` before doing anything else. Never guess at risky assumptions.",
        "",
        "STEP 2 — PLAN",
        "  In your first assistant message, explain your immediate plan concisely.",
        "",
        "STEP 3 — VERIFY BEFORE CODING",
        "  When KubeJS syntax, mod command usage, or config keys are uncertain, first look them up",
        "  via `fetch_modrinth`, `fetch_url`, or `search`. Do not guess or hallucinate APIs.",
        "",
        "STEP 4 — EXECUTE WITH PROGRESS UPDATES",
        "  Send a short progress update to the player before each tool call.",
        "  Prefer `execute-command` for tasks solvable with vanilla commands; avoid KubeJS overhead.",
        "",
        "STEP 5 — VERIFY RESULTS",
        "  After every tool result, inspect the output carefully.",
        "  - On error: diagnose the root cause, fix it, and retry. Do not silently skip errors.",
        "  - After registering/changing commands: run smoke tests via `execute-command`.",
        "    If the test fails, fix and reload again before continuing.",
        "  - After dynamic content changes: verify the real in-game state before claiming success.",
        "",
        "STEP 6 — COMMUNICATE AND STOP",
        "  When the task is complete, explain the result clearly in Markdown / MineDown syntax,",
        "  then stop. Do not propose follow-up work unless the player asks.",
        "",

        // ── 3. TOOLS ─────────────────────────────────────────────────────────────
        "*** TOOL REFERENCE ***",
        "",
        "— INFORMATION & RESEARCH —",
        "  `ask-user-question`  Ask the player a targeted question when details are ambiguous.",
        "    Provide a concise `question` and up to 5 preset `options`.",
        "    Do NOT include 'Other' or 'Skip' in options; MineClawd appends them automatically.",
        "  `list_commands`      List available root commands, optionally filtered by `mod_id`.",
        "    Filtered matching is best-effort based on command names and prefixes.",
        "  `fetch_modrinth`     Fetch the Modrinth project page for an installed mod id. You can possibly find command usage, config keys, or API details in mod documentation or source code linked there.",
        "  `fetch_url`          Fetch any HTTP(S) page; HTML is returned as Markdown.",
        "    Use for command usage, config keys, API details, or mod documentation.",
        "  `search`             Web search via Tavily (available only when configured).",
        "    Use when external references are needed beyond installed-mod docs.",
        "  `list-files`         List files/directories (optional path + recursion).",
        "  `read-files`         Read text files.",
        "  `grep`               Regex search inside files.",
        "  `read-image`         Read/describe an image file with the configured vision model.",
        "",
        "— ACTION & EXECUTION —",
        "  `execute-command`    Run a vanilla Minecraft command and return its output.",
        "    Prefer this for: gamerule, time, weather, tp, effect, give, clear, kill,",
        "    summon, setblock, fill, say, and simple state checks.",
        "    If command output is sufficient, skip KubeJS entirely.",
        "  `apply-instant-server-script`",
        "    Execute KubeJS JavaScript immediately on the running server via /_exec_kubejs_internal.",
        "    Use for one-off operations: inventory inspection/editing, nearby block changes,",
        "    entity queries, or any ad-hoc server action.",
        "    Multi-line code may include normal newline characters; they are converted to \\n before",
        "    execution. No reload required.",
        "    Predefined variables: source, server, level, player (any may be null).",
        "    If server is null, use Utils.getServer().",
        "  `write-files`          Write text files. Especially KubeJS server scripts.",
        "  `copy-files`           Copy files/directories.",
        "  `move-files`           Move/rename files/directories.",
        "  `curl`                 Perform HTTP requests and return response details. Use this for downloading files. If you just need to fetch text content, prefer `fetch_url` which is more llm-friendly.",
        "  `reload-game`          Run /reload, return KubeJS loading errors.",
        "  `sync-command-tree`    Push refreshed command suggestions to online players.",
        "    Call ONLY when command registrations changed AND reload already succeeded.",
        "",
        "  All file paths are server-root-relative (the folder containing world/, logs/, mods/,",
        "  config/, etc.). Parent traversal (..) is blocked.",

        // ── 4. PERSISTENT-SCRIPT WORKFLOW ────────────────────────────────────────
        "*** PERSISTENT-SCRIPT WORKFLOW ***",
        "1. Write or edit scripts under `kubejs/server_scripts/mineclawd/`.",
        "2. Run `reload-game`. Review ALL reported errors — do not ignore warnings.",
        "3. If errors appear, fix the script and reload again. Repeat until clean.",
        "4. Run smoke tests via `execute-command` to confirm runtime behavior.",
        "5. Use `sync-command-tree` only if command registrations changed.",
        "",
        "Use persistent scripts for: registering commands, modifying recipes, listening to player",
        "behavior, modifying entity drops, world tick logic, and other server-lifecycle hooks.",
        "",
        "Error handling requirements for persistent scripts:",
        "  - Validate all arguments and guard against nulls.",
        "  - Wrap command handlers and event callbacks in try/catch with clear error context.",
        "  - Reload catches load-time syntax errors but NOT all runtime errors;",
        "    smoke-test every registered command path after reload.",
        "",

        // ── 5. KUBEJS CALLBACK BRIDGE ─────────────────────────────────────────────
        "*** KUBEJS CALLBACK BRIDGE ***",
        " Use this inside a presistent script.",
        "`mineclawd.requestWithSession(player, session_ref, request)`",
        "  Triggers MineClawd with full tool access in an existing session context.",
        "  `session_ref` may be a session id or token; prefer the stable session id.",
        "  Behaves like that player running `/mclawd <request>` without changing the active session.",
        "  For session-bound callbacks, only bind/listen for the player who owns that session.",
        "",
        "`mineclawd.requestOneShot(request, context)`",
        "  Triggers one request with tools but without session persistence.",
        "  No session history is created or updated.",
        "  If `context` is omitted, server context is used.",
        "",
        "Example use case: player wants commentary on achievements →",
        "  bind a listener to achievement events for that player,",
        "  call requestWithSession with achievement details whenever they trigger.",
        "",

        // ── 6. KUBEJS SYNTAX RULES ────────────────────────────────────────────────
        "*** STRICT KUBEJS SYNTAX RULES ***",
        "",
        "1. EVENT SYSTEM — always use the modern event object syntax. `onEvent` is forbidden.",
        "   CORRECT:  ServerEvents.recipes(event => { ... })",
        "   CORRECT:  ServerEvents.tags('item', event => { ... })",
        "   CORRECT:  StartupEvents.registry('item', event => { ... })",
        "   CORRECT:  PlayerEvents.chat(event => { ... })",
        "   CORRECT:  LevelEvents.tick(event => { ... })",
        "   WRONG:    onEvent('recipes', event => ...)  ← strictly forbidden",
        "",
        "2. RECIPE TYPES (inside ServerEvents.recipes):",
        "   Shaped:    event.shaped('minecraft:diamond', ['AAA',' B ',' B '], {A:'minecraft:dirt', B:'minecraft:stick'})",
        "   Shapeless: event.shapeless('minecraft:stick', ['minecraft:diamond', '#minecraft:logs'])",
        "   Smelting:  event.smelting('minecraft:iron_ingot', 'minecraft:raw_iron')",
        "   Remove:    event.remove({output: 'minecraft:stick'})  or  event.remove({id: 'minecraft:chest'})",
        "",
        "3. GENERAL UTILITIES:",
        "   Logging:    console.info('Message')  or  Utils.server.tell('Message')",
        "   ItemStack:  Item.of('minecraft:diamond', 64)",
        "   Java types: avoid Java.loadClass unless necessary; prefer built-in wrappers like Utils.",
        "",
        "These examples cover the most common patterns; other KubeJS features may be used as needed.",
        "",

        // ── 7. CONSTRAINTS & LIMITS ───────────────────────────────────────────────
        "*** CONSTRAINTS ***",
        "",
        "STARTUP CONTENT: Do NOT claim you can register new items, blocks, fluids, or other",
        "startup content in this session. These require `startup_scripts` + a full game restart.",
        "If asked, refuse clearly and explain this limitation.",
        "",
        "REMOVING PREVIOUS WORK: You may not remember scripts from earlier sessions.",
        "If asked to remove a feature, use `list-files` / `grep` under",
        "`kubejs/server_scripts/mineclawd/` to locate existing scripts, then remove them.",
        "",
        "PROBLEM-HANDLING GUIDELINES:",
        "  - If a tool call fails, read the full error message before retrying.",
        "  - If the same error repeats after two fix attempts, stop, report the issue,",
        "    and ask the player how to proceed.",
        "  - Prefer the simplest correct solution; avoid over-engineering.",
        "  - Do not leave temporary test blocks, entities, or commands in the world;",
        "    clean up immediately after validation.",
        "  - If unsure whether a change is safe, ask the player before applying it."
    );

    private static final String DYNAMIC_REGISTRY_PROMPT_APPENDIX = String.join("\n",

        // ── DYNAMIC REGISTRY ──────────────────────────────────────────────────────
        "*** DYNAMIC REGISTRY (RUNTIME PLACEHOLDER MODE) ***",
        "True startup registration is still impossible in-session, but you can pseudo-register",
        "content by configuring pre-registered placeholders.",
        "",
        "Tools:",
        "  `list-dynamic-content`    Inspect used and free slots for items/blocks/fluids.",
        "  `register-dynamic-item`   Claim a free item slot.",
        "    Params: name, material_item (vanilla item id), throwable.",
        "  `register-dynamic-block`  Claim a free block slot.",
        "    Params: name, material_block (vanilla block id), friction.",
        "  `register-dynamic-fluid`  Claim a free fluid slot.",
        "    Params: name, material_fluid (vanilla fluid id), color (#RRGGBB, optional).",
        "  `update-dynamic-item`     Update an existing item slot (requires slot).",
        "  `update-dynamic-block`    Update an existing block slot (requires slot).",
        "  `update-dynamic-fluid`    Update an existing fluid slot (requires slot).",
        "  `unregister-dynamic-content`  Release a slot by type + slot.",
        "",
        "Rules:",
        "  1. If the user does not specify a slot, call register tools without `slot` to auto-pick.",
        "  2. Registered placeholders appear in creative tabs; unregistered slots stay hidden.",
        "  3. Placeholder IDs are fixed: mineclawd:dynamic_item_001, _block_001, _fluid_001, etc.",
        "  4. For material_* params, pick a semantically related vanilla ID; avoid unrelated defaults.",
        "  5. After each register/update, verify real in-game state before claiming success.",
        "  6. Clean up any temporary validation setups (test blocks, entities) immediately.",
        "  7. For behavior beyond provided properties, combine with KubeJS scripts."
    );

    private static final String ASSET_TRACKING_PROMPT_APPENDIX = String.join("\n",

        // ── ASSET TRACKING ────────────────────────────────────────────────────────
        "*** ASSET TRACKING ***",
        "Use persistent asset records so future sessions can continue previous work without",
        "losing references to entities, scripts, commands, or dynamic content.",
        "",
        "Tools:",
        "  `list-assets`         Inspect all currently tracked assets.",
        "  `upsert-asset-record` Create or update a record whenever you create, update, or remove",
        "                        entities, dynamic content, special items, commands, or game mechanics.",
        "  `remove-asset-record` Remove a stale record when the referenced thing no longer exists.",
        "",
        "Categories and required fields:",
        "  entities          — entity_uuid; add entity_dimension, entity_x/y/z when known.",
        "  items_blocks_fluids — content_id (e.g. mineclawd:dynamic_item_001).",
        "  special_items     — special_item_id; special_item_nbt if available.",
        "  commands          — command text; script_path if scripted.",
        "  game_mechanics    — summary, details, script_path when applicable.",
        "",
        "All categories support optional fields: summary, script_path."
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
            PENDING_UPLOAD_ASSEMBLIES.remove(player.getUuid());
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
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.UPLOAD_WORKSPACE_FILE,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    String workspacePath = buf.readString(UPLOAD_PACKET_PATH_MAX_CHARS);
                    String originalName = buf.readString(UPLOAD_PACKET_NAME_MAX_CHARS);
                    boolean image = false;
                    if (buf.isReadable()) {
                        image = buf.readBoolean();
                    }
                    byte[] data;
                    try {
                        data = buf.readByteArray(MAX_UPLOAD_BYTES);
                    } catch (Exception exception) {
                        data = new byte[0];
                    }
                    boolean finalImage = image;
                    byte[] finalData = data;
                    server.execute(() -> instance.handleWorkspaceUploadPacket(player, workspacePath, originalName, finalImage, finalData));
                });
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.UPLOAD_WORKSPACE_FILE_CHUNK,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    String uploadId = buf.readString(UPLOAD_PACKET_ID_MAX_CHARS);
                    String workspacePath = buf.readString(UPLOAD_PACKET_PATH_MAX_CHARS);
                    String originalName = buf.readString(UPLOAD_PACKET_NAME_MAX_CHARS);
                    boolean image = false;
                    if (buf.isReadable()) {
                        image = buf.readBoolean();
                    }
                    int chunkIndex = -1;
                    int totalChunks = -1;
                    byte[] data = new byte[0];
                    try {
                        chunkIndex = buf.readInt();
                        totalChunks = buf.readInt();
                        data = buf.readByteArray(UPLOAD_CHUNK_MAX_BYTES);
                    } catch (Exception ignored) {
                    }
                    boolean finalImage = image;
                    int finalChunkIndex = chunkIndex;
                    int finalTotalChunks = totalChunks;
                    byte[] finalData = data;
                    server.execute(() -> instance.handleWorkspaceUploadChunkPacket(
                            player,
                            uploadId,
                            workspacePath,
                            originalName,
                            finalImage,
                            finalChunkIndex,
                            finalTotalChunks,
                            finalData
                    ));
                });
        NetworkManager.registerReceiver(NetworkManager.c2s(), MineClawdNetworking.SUBMIT_PROMPT,
                (buf, context) -> {
                    ServerPlayerEntity player = (ServerPlayerEntity) context.getPlayer();
                    MinecraftServer server = player.getServer();
                    if (server == null) {
                        return;
                    }
                    String request = buf.readString(PROMPT_PACKET_MAX_CHARS);
                    String attachmentsJson = "";
                    if (buf.isReadable()) {
                        attachmentsJson = buf.readString(PROMPT_PACKET_MAX_CHARS);
                    }
                    String finalAttachmentsJson = attachmentsJson;
                    server.execute(() -> instance.handleSubmitPromptPacket(player, request, finalAttachmentsJson));
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
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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

        List<AssetRecord> assets = ASSETS_MANAGER.list(sessionOwnerKey(source));
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

        String ownerKey = sessionOwnerKey(source);
        List<AssetRecord> assets = ASSETS_MANAGER.list(ownerKey);
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

        SessionData activeSession = SESSION_MANAGER.loadActiveSession(ownerKey);
        String activeSessionId = activeSession == null ? "" : activeSession.id();
        String activePersona = PERSONA_MANAGER.getActiveSoulName(ownerKey);
        List<String> personas = PERSONA_MANAGER.listSoulNames();

        String payloadString = buildAssetsOverlayPayloadJson(
                openUi,
                activeSessionId,
                activePersona,
                personas,
                payloadAssets
        );
        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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
        List<AssetRecord> assets = ASSETS_MANAGER.list(sessionOwnerKey(source));
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
        String ownerKey = sessionOwnerKey(source);
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
        String ownerKey = sessionOwnerKey(source);
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
        String ownerKey = sessionOwnerKey(source);
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

        var payload = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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
        PreparedPrompt preparedPrompt;
        try {
            preparedPrompt = preparePromptWithAttachments(
                    source,
                    ownerKey,
                    session,
                    request,
                    requestOptions.attachments(),
                    provider
            );
        } catch (Exception exception) {
            ACTIVE_REQUESTS.remove(ownerKey, requestId);
            source.sendError(Text.literal("MineClawd: failed to prepare prompt attachments: " + summarizeThrowable(exception)));
            return 0;
        }
        String promptForModel = preparedPrompt.text();

        AgentRuntime runtime = new AgentRuntime(
                resolveToolLimit(config),
                isToolLimitEnabled(config),
                DynamicContentRegistry.isRuntimeEnabled(),
                config.debugMode,
                requestId,
                ownerKey,
                sessionId,
                provider,
                promptForModel,
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
        agentLog(runtime, "User request from %s: %s", ownerKey, request);
        sendPromptEcho(source, request);
        sendTaskStatus(source, true);
        if (runtime.clientStreamEnabled()) {
            sendAgentStreamEvent(source, runtime, AgentStreamEventType.START, buildStreamStartPayload(runtime.sessionId(), request));
        }
        try {
            String systemPrompt = buildSystemPrompt(source, config, ownerKey, runtime.dynamicRegistryEnabled(), session);
            if (provider == MineClawdConfig.LlmProvider.OPENAI) {
                List<OpenAIMessage> history;
                if (runtime.sessionBacked() && session != null) {
                    history = session.openAiHistory();
                    ensureOpenAiHistory(history, systemPrompt);
                    if (preparedPrompt.openAiParts().isEmpty()) {
                        history.add(OpenAIMessage.user(promptForModel));
                    } else {
                        history.add(OpenAIMessage.userWithParts(promptForModel, preparedPrompt.openAiParts()));
                    }
                    session.touch();
                    SESSION_MANAGER.saveSession(ownerKey, session);
                } else {
                    history = new ArrayList<>();
                    ensureOpenAiHistory(history, systemPrompt);
                    if (preparedPrompt.openAiParts().isEmpty()) {
                        history.add(OpenAIMessage.user(promptForModel));
                    } else {
                        history.add(OpenAIMessage.userWithParts(promptForModel, preparedPrompt.openAiParts()));
                    }
                }
                runOpenAiAgent(source, session, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            } else {
                List<VertexAIMessage> history;
                if (runtime.sessionBacked() && session != null) {
                    history = session.vertexHistory();
                    ensureVertexHistory(history, systemPrompt);
                    normalizeVertexFunctionCallTurns(history, runtime);
                    if (preparedPrompt.vertexParts().isEmpty()) {
                        history.add(VertexAIMessage.user(promptForModel));
                    } else {
                        history.add(new VertexAIMessage("user", preparedPrompt.vertexParts()));
                    }
                    session.touch();
                    SESSION_MANAGER.saveSession(ownerKey, session);
                } else {
                    history = new ArrayList<>();
                    ensureVertexHistory(history, systemPrompt);
                    if (preparedPrompt.vertexParts().isEmpty()) {
                        history.add(VertexAIMessage.user(promptForModel));
                    } else {
                        history.add(new VertexAIMessage("user", preparedPrompt.vertexParts()));
                    }
                }
                runVertexAgent(source, session, history, 0, 0, new ToolLoopState("", "", 0), runtime);
            }
        } catch (Exception e) {
            if (runtime.clientStreamEnabled()) {
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
        var packet = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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
                result = ModDocsToolExecutor.listCommands(source, readOptionalStringArg(args, "mod_id"));
                break;
            case TOOL_FETCH_MODRINTH:
                String modrinthModId = readRequiredStringArg(args, "mod_id");
                if (modrinthModId == null || modrinthModId.isBlank()) {
                    return "ERROR: Tool call is missing required string `mod_id`.";
                }
                result = ModDocsToolExecutor.fetchModrinth(modrinthModId);
                break;
            case TOOL_FETCH_URL:
                String docsUrl = readRequiredStringArg(args, "url");
                if (docsUrl == null || docsUrl.isBlank()) {
                    return "ERROR: Tool call is missing required string `url`.";
                }
                result = ModDocsToolExecutor.fetchUrl(docsUrl);
                break;
            case TOOL_LIST_FILES:
                result = WorkspaceFileToolExecutor.listFiles(
                        source,
                        readOptionalStringArg(args, "path"),
                        readOptionalBooleanArg(args, "recursive"),
                        readOptionalIntArg(args, "limit")
                );
                break;
            case TOOL_READ_FILES:
                String readPath = readRequiredStringArg(args, "path");
                if (readPath == null || readPath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                result = WorkspaceFileToolExecutor.readFile(source, readPath);
                break;
            case TOOL_WRITE_FILES:
                String writePath = readRequiredStringArg(args, "path");
                if (writePath == null || writePath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                String content = readRequiredStringArg(args, "content");
                if (content == null) {
                    return "ERROR: Tool call is missing required string `content`.";
                }
                result = WorkspaceFileToolExecutor.writeFile(source, writePath, content);
                break;
            case TOOL_COPY_FILES:
                String fromPath = readRequiredStringArg(args, "from");
                String toPath = readRequiredStringArg(args, "to");
                if (fromPath == null || fromPath.isBlank() || toPath == null || toPath.isBlank()) {
                    return "ERROR: Tool call requires string `from` and `to`.";
                }
                result = WorkspaceFileToolExecutor.copyFiles(source, fromPath, toPath);
                break;
            case TOOL_MOVE_FILES:
                String moveFromPath = readRequiredStringArg(args, "from");
                String moveToPath = readRequiredStringArg(args, "to");
                if (moveFromPath == null || moveFromPath.isBlank() || moveToPath == null || moveToPath.isBlank()) {
                    return "ERROR: Tool call requires string `from` and `to`.";
                }
                result = WorkspaceFileToolExecutor.moveFiles(source, moveFromPath, moveToPath);
                break;
            case TOOL_GREP:
                String pattern = readRequiredStringArg(args, "pattern");
                if (pattern == null || pattern.isBlank()) {
                    return "ERROR: Tool call is missing required string `pattern`.";
                }
                result = WorkspaceFileToolExecutor.grepFiles(
                        source,
                        pattern,
                        readOptionalStringArg(args, "path"),
                        readOptionalStringArg(args, "glob"),
                        readOptionalBooleanArg(args, "case_sensitive"),
                        readOptionalIntArg(args, "max_matches")
                );
                break;
            case TOOL_CURL:
                String url = readRequiredStringArg(args, "url");
                if (url == null || url.isBlank()) {
                    return "ERROR: Tool call is missing required string `url`.";
                }
                result = WorkspaceFileToolExecutor.curl(
                        url,
                        readOptionalStringArg(args, "method"),
                        readOptionalStringArg(args, "body"),
                        readOptionalStringMapArg(args, "headers"),
                        readOptionalIntArg(args, "timeout_seconds")
                );
                break;
            case TOOL_READ_IMAGE:
                String imagePath = readRequiredStringArg(args, "path");
                if (imagePath == null || imagePath.isBlank()) {
                    return "ERROR: Tool call is missing required string `path`.";
                }
                result = readImageTool(source, runtime, imagePath, readOptionalStringArg(args, "prompt"));
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

    private Map<String, String> readOptionalStringMapArg(JsonObject args, String key) {
        if (args == null || key == null || key.isBlank() || !args.has(key) || args.get(key).isJsonNull()) {
            return Map.of();
        }
        if (!args.get(key).isJsonObject()) {
            return Map.of();
        }
        JsonObject object = args.getAsJsonObject(key);
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            if (!entry.getValue().isJsonPrimitive()) {
                continue;
            }
            try {
                map.put(entry.getKey(), entry.getValue().getAsString());
            } catch (Exception ignored) {
            }
        }
        return map;
    }

    private ToolExecutionResult readImageTool(ServerCommandSource source, AgentRuntime runtime, String path, String prompt) {
        Path imagePath = WorkspaceFileToolExecutor.resolveServerPath(source, path, false);
        Path root = WorkspaceFileToolExecutor.serverRoot(source);
        if (imagePath == null || root == null) {
            return new ToolExecutionResult(false, "Invalid path. Paths must stay inside the server root and cannot use `..`.");
        }
        if (!Files.exists(imagePath) || !Files.isRegularFile(imagePath)) {
            return new ToolExecutionResult(false, "Image file not found: " + WorkspaceFileToolExecutor.displayPath(root, imagePath));
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(imagePath);
        } catch (Exception exception) {
            return new ToolExecutionResult(false, "Failed to read image: " + summarizeThrowable(exception));
        }
        if (bytes.length == 0) {
            return new ToolExecutionResult(false, "Image file is empty.");
        }
        if (bytes.length > MAX_UPLOAD_BYTES) {
            return new ToolExecutionResult(false, "Image file is too large (max " + MAX_UPLOAD_BYTES + " bytes).");
        }

        String mimeType = detectImageMimeType(imagePath);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            return new ToolExecutionResult(false, "Unsupported image format. Use PNG/JPEG/GIF/WebP/BMP/TGA.");
        }

        String instruction = prompt == null || prompt.isBlank()
                ? "Describe this image precisely for Minecraft modding context. Mention key objects, text, coordinates, colors, and any notable details."
                : prompt.trim();
        MineClawdConfig config = MineClawdConfig.get();
        MineClawdConfig.LlmProvider provider = runtime != null && runtime.provider() != null
                ? runtime.provider()
                : (config.provider == null ? MineClawdConfig.LlmProvider.OPENAI : config.provider);

        String modelOutput;
        try {
            if (provider == MineClawdConfig.LlmProvider.VERTEX_AI) {
                modelOutput = readImageWithVertex(config, instruction, mimeType, bytes);
            } else {
                modelOutput = readImageWithOpenAi(config, instruction, mimeType, bytes);
            }
        } catch (Exception exception) {
            return new ToolExecutionResult(false, "Image analysis failed: " + summarizeThrowable(exception));
        }

        StringBuilder out = new StringBuilder();
        out.append("Image: ").append(WorkspaceFileToolExecutor.displayPath(root, imagePath)).append("\n");
        out.append("Mime-Type: ").append(mimeType).append("\n");
        out.append("Size: ").append(bytes.length).append(" bytes\n");
        if (modelOutput == null || modelOutput.isBlank()) {
            out.append("Analysis: (no text returned by model)");
        } else {
            out.append("Analysis:\n").append(modelOutput.trim());
        }
        return new ToolExecutionResult(true, out.toString());
    }

    private String readImageWithOpenAi(MineClawdConfig config, String instruction, String mimeType, byte[] bytes) {
        if (config == null || config.apiKey == null || config.apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI API key is missing.");
        }
        if (config.model == null || config.model.isBlank()) {
            throw new IllegalStateException("OpenAI model is missing.");
        }
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", instruction);

        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes));
        imagePart.add("image_url", imageUrl);

        List<OpenAIMessage> messages = List.of(OpenAIMessage.userWithParts(instruction, List.of(textPart, imagePart)));
        OpenAIResponse response = OPENAI_CLIENT
                .sendMessage(config.endpoint, config.apiKey, config.model, messages, null)
                .join();
        return response == null ? "" : (response.text() == null ? "" : response.text());
    }

    private String readImageWithVertex(MineClawdConfig config, String instruction, String mimeType, byte[] bytes) {
        if (config == null || config.vertexApiKey == null || config.vertexApiKey.isBlank()) {
            throw new IllegalStateException("Vertex API key is missing.");
        }
        if (config.vertexModel == null || config.vertexModel.isBlank()) {
            throw new IllegalStateException("Vertex model is missing.");
        }
        JsonObject inlineDataPart = new JsonObject();
        JsonObject inlineData = new JsonObject();
        inlineData.addProperty("mimeType", mimeType);
        inlineData.addProperty("data", Base64.getEncoder().encodeToString(bytes));
        inlineDataPart.add("inlineData", inlineData);

        List<JsonObject> parts = new ArrayList<>();
        parts.add(VertexAIMessage.textPart(instruction));
        parts.add(inlineDataPart);
        List<VertexAIMessage> history = List.of(new VertexAIMessage("user", parts));
        VertexAIResponse response = VERTEX_CLIENT
                .sendMessage(config.vertexEndpoint, config.vertexApiKey, config.vertexModel, history, List.of())
                .join();
        return response == null ? "" : (response.text() == null ? "" : response.text());
    }

    private String detectImageMimeType(Path path) {
        if (path == null) {
            return "";
        }
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && detected.startsWith("image/")) {
                return detected;
            }
        } catch (Exception ignored) {
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (name.endsWith(".tga")) {
            return "image/x-tga";
        }
        return "";
    }

    private PreparedPrompt preparePromptWithAttachments(
            ServerCommandSource source,
            String ownerKey,
            SessionData session,
            String request,
            List<SessionAttachment> attachments,
            MineClawdConfig.LlmProvider provider
    ) {
        String baseRequest = request == null ? "" : request.trim();
        if (attachments == null || attachments.isEmpty() || session == null) {
            return new PreparedPrompt(baseRequest, List.of(), List.of());
        }

        Path workspaceRoot = SESSION_MANAGER.ensureSessionWorkspace(ownerKey, session.id());
        List<SessionAttachment> available = new ArrayList<>();
        List<Path> availablePaths = new ArrayList<>();
        for (SessionAttachment attachment : attachments) {
            if (attachment == null || available.size() >= MAX_PROMPT_ATTACHMENTS) {
                continue;
            }
            String relativePath = sanitizeWorkspaceRelativePath(attachment.workspacePath());
            if (relativePath.isBlank()) {
                continue;
            }
            Path file = workspaceRoot.resolve(relativePath).normalize();
            if (!file.startsWith(workspaceRoot) || !Files.isRegularFile(file)) {
                continue;
            }
            available.add(new SessionAttachment(relativePath, attachment.originalName(), attachment.image()));
            availablePaths.add(file);
        }

        if (available.isEmpty()) {
            return new PreparedPrompt(baseRequest, List.of(), List.of());
        }

        String promptText = buildAttachmentPromptText(source, workspaceRoot, baseRequest, available);
        List<JsonObject> openAiParts = new ArrayList<>();
        List<JsonObject> vertexParts = new ArrayList<>();
        boolean addedAnyImage = false;

        for (int i = 0; i < available.size(); i++) {
            SessionAttachment attachment = available.get(i);
            Path file = availablePaths.get(i);
            if (!attachment.image()) {
                continue;
            }
            String mimeType = detectImageMimeType(file);
            if (mimeType.isBlank() || !mimeType.startsWith("image/")) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (Exception ignored) {
                continue;
            }
            if (bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES) {
                continue;
            }

            if (!addedAnyImage) {
                JsonObject openAiTextPart = new JsonObject();
                openAiTextPart.addProperty("type", "text");
                openAiTextPart.addProperty("text", promptText);
                openAiParts.add(openAiTextPart);
                vertexParts.add(VertexAIMessage.textPart(promptText));
                addedAnyImage = true;
            }

            JsonObject openAiImagePart = new JsonObject();
            openAiImagePart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject();
            imageUrl.addProperty("url", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes));
            openAiImagePart.add("image_url", imageUrl);
            openAiParts.add(openAiImagePart);

            JsonObject vertexInlineDataPart = new JsonObject();
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mimeType", mimeType);
            inlineData.addProperty("data", Base64.getEncoder().encodeToString(bytes));
            vertexInlineDataPart.add("inlineData", inlineData);
            vertexParts.add(vertexInlineDataPart);
        }

        if (!addedAnyImage) {
            return new PreparedPrompt(promptText, List.of(), List.of());
        }
        if (provider == MineClawdConfig.LlmProvider.VERTEX_AI) {
            return new PreparedPrompt(promptText, List.of(), List.copyOf(vertexParts));
        }
        return new PreparedPrompt(promptText, List.copyOf(openAiParts), List.of());
    }

    private String buildAttachmentPromptText(
            ServerCommandSource source,
            Path workspaceRoot,
            String request,
            List<SessionAttachment> attachments
    ) {
        StringBuilder prompt = new StringBuilder();
        String normalizedRequest = request == null ? "" : request.trim();
        if (!normalizedRequest.isBlank()) {
            prompt.append(normalizedRequest);
        } else {
            prompt.append("Please process the uploaded attachments.");
        }

        Path serverRoot = WorkspaceFileToolExecutor.serverRoot(source);
        String workspaceToolPath = "workspace";
        if (workspaceRoot != null) {
            if (serverRoot != null) {
                workspaceToolPath = WorkspaceFileToolExecutor.displayPath(serverRoot, workspaceRoot);
            } else {
                workspaceToolPath = workspaceRoot.toString().replace('\\', '/');
            }
        }
        if (workspaceToolPath == null
                || workspaceToolPath.isBlank()
                || workspaceToolPath.contains(":")
                || workspaceToolPath.startsWith("/")) {
            workspaceToolPath = "workspace";
        }

        prompt.append("\n\nAttachment workspace (server-root relative): `")
                .append(workspaceToolPath)
                .append("`\n")
                .append("Attached files:\n");

        boolean hasImage = false;
        for (SessionAttachment attachment : attachments) {
            if (attachment == null || attachment.workspacePath() == null || attachment.workspacePath().isBlank()) {
                continue;
            }
            String relative = attachment.workspacePath().replace('\\', '/');
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            String fullPath;
            if (".".equals(workspaceToolPath) || workspaceToolPath.isBlank()) {
                fullPath = relative;
            } else if (workspaceToolPath.endsWith("/")) {
                fullPath = workspaceToolPath + relative;
            } else {
                fullPath = workspaceToolPath + "/" + relative;
            }

            prompt.append("- `").append(fullPath).append("`");
            if (attachment.image()) {
                prompt.append(" (image)");
                hasImage = true;
            }
            if (attachment.originalName() != null && !attachment.originalName().isBlank()) {
                prompt.append(" [original: ").append(attachment.originalName()).append("]");
            }
            prompt.append("\n");
        }
        prompt.append("Use `read-files`/`grep` with these full paths for non-image files.");
        if (hasImage) {
            prompt.append("\nImage attachments are included as visual inputs in this prompt.");
        }
        return prompt.toString().trim();
    }

    private ToolExecutionResult listAssetsTool(String ownerKey) {
        List<AssetRecord> assets = ASSETS_MANAGER.list(ownerKey);
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

    private ToolExecutionResult upsertAssetRecordTool(String ownerKey, JsonObject args) {
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
        UpsertResult result = ASSETS_MANAGER.upsert(ownerKey, draft);
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

    private ToolExecutionResult removeAssetRecordTool(String ownerKey, JsonObject args) {
        String id = readOptionalStringArg(args, "id");
        if (id.isBlank()) {
            id = readOptionalStringArg(args, "reference");
        }
        if (id.isBlank()) {
            return new ToolExecutionResult(false, "Tool call is missing required string `id`.");
        }
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
            var buffer = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
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

    private void handleWorkspaceUploadChunkPacket(
            ServerPlayerEntity player,
            String uploadId,
            String workspacePath,
            String originalName,
            boolean image,
            int chunkIndex,
            int totalChunks,
            byte[] data
    ) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!server.isSingleplayer() && !player.hasPermissionLevel(2)) {
            sendUploadError(player, "Only OP players can upload files to the server workspace.");
            return;
        }

        String normalizedUploadId = sanitizeUploadId(uploadId);
        if (normalizedUploadId.isBlank()) {
            sendUploadError(player, "Upload failed: invalid upload id.");
            return;
        }
        if (totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS) {
            removeUploadAssembly(player.getUuid(), normalizedUploadId);
            sendUploadError(player, "Upload failed: invalid chunk count.");
            return;
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            removeUploadAssembly(player.getUuid(), normalizedUploadId);
            sendUploadError(player, "Upload failed: invalid chunk index.");
            return;
        }

        byte[] chunk = data == null ? new byte[0] : data;
        if (chunk.length == 0 || chunk.length > UPLOAD_CHUNK_MAX_BYTES) {
            removeUploadAssembly(player.getUuid(), normalizedUploadId);
            sendUploadError(player, "Upload failed: invalid chunk size.");
            return;
        }

        String normalizedRelativePath = sanitizeWorkspaceRelativePath(workspacePath);
        if (normalizedRelativePath.isBlank()) {
            removeUploadAssembly(player.getUuid(), normalizedUploadId);
            sendUploadError(player, "Upload failed: invalid target path.");
            return;
        }

        String safeOriginalName = originalName == null ? "" : originalName.trim();
        if (safeOriginalName.length() > UPLOAD_PACKET_NAME_MAX_CHARS) {
            safeOriginalName = safeOriginalName.substring(0, UPLOAD_PACKET_NAME_MAX_CHARS);
        }

        UUID playerId = player.getUuid();
        pruneExpiredUploadAssemblies(playerId);
        ConcurrentHashMap<String, UploadAssembly> playerAssemblies = PENDING_UPLOAD_ASSEMBLIES.computeIfAbsent(
                playerId,
                ignored -> new ConcurrentHashMap<>()
        );

        UploadAssembly assembly = playerAssemblies.get(normalizedUploadId);
        if (chunkIndex == 0) {
            assembly = new UploadAssembly(normalizedRelativePath, safeOriginalName, image, totalChunks, System.currentTimeMillis());
            playerAssemblies.put(normalizedUploadId, assembly);
        } else if (assembly == null) {
            sendUploadError(player, "Upload failed: missing first chunk.");
            return;
        }

        if (!assembly.workspacePath.equals(normalizedRelativePath)
                || !assembly.originalName.equals(safeOriginalName)
                || assembly.image != image
                || assembly.totalChunks != totalChunks) {
            removeUploadAssembly(playerId, normalizedUploadId);
            sendUploadError(player, "Upload failed: chunk metadata mismatch.");
            return;
        }

        if (assembly.nextChunkIndex != chunkIndex) {
            removeUploadAssembly(playerId, normalizedUploadId);
            sendUploadError(player, "Upload failed: chunk order mismatch.");
            return;
        }

        int nextTotalBytes = assembly.totalBytes + chunk.length;
        if (nextTotalBytes <= 0 || nextTotalBytes > MAX_UPLOAD_BYTES) {
            removeUploadAssembly(playerId, normalizedUploadId);
            sendUploadError(player, "Upload failed: file is larger than " + MAX_UPLOAD_BYTES + " bytes.");
            return;
        }

        try {
            assembly.buffer.write(chunk);
        } catch (Exception exception) {
            removeUploadAssembly(playerId, normalizedUploadId);
            sendUploadError(player, "Upload failed: " + summarizeThrowable(exception));
            return;
        }
        assembly.totalBytes = nextTotalBytes;
        assembly.nextChunkIndex = chunkIndex + 1;

        if (assembly.nextChunkIndex >= assembly.totalChunks) {
            byte[] completeData = assembly.buffer.toByteArray();
            removeUploadAssembly(playerId, normalizedUploadId);
            handleWorkspaceUploadPacket(player, assembly.workspacePath, assembly.originalName, assembly.image, completeData);
        }
    }

    private void pruneExpiredUploadAssemblies(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ConcurrentHashMap<String, UploadAssembly> playerAssemblies = PENDING_UPLOAD_ASSEMBLIES.get(playerId);
        if (playerAssemblies == null || playerAssemblies.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, UploadAssembly> entry : playerAssemblies.entrySet()) {
            UploadAssembly assembly = entry.getValue();
            if (assembly == null || now - assembly.createdAtEpochMs > UPLOAD_ASSEMBLY_TTL_MS) {
                playerAssemblies.remove(entry.getKey());
            }
        }
        if (playerAssemblies.isEmpty()) {
            PENDING_UPLOAD_ASSEMBLIES.remove(playerId, playerAssemblies);
        }
    }

    private void removeUploadAssembly(UUID playerId, String uploadId) {
        if (playerId == null || uploadId == null || uploadId.isBlank()) {
            return;
        }
        ConcurrentHashMap<String, UploadAssembly> playerAssemblies = PENDING_UPLOAD_ASSEMBLIES.get(playerId);
        if (playerAssemblies == null) {
            return;
        }
        playerAssemblies.remove(uploadId);
        if (playerAssemblies.isEmpty()) {
            PENDING_UPLOAD_ASSEMBLIES.remove(playerId, playerAssemblies);
        }
    }

    private String sanitizeUploadId(String rawUploadId) {
        String normalized = rawUploadId == null ? "" : rawUploadId.trim();
        if (normalized.isBlank() || normalized.length() > UPLOAD_PACKET_ID_MAX_CHARS) {
            return "";
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.';
            if (!allowed) {
                return "";
            }
        }
        return normalized;
    }

    private void handleWorkspaceUploadPacket(
            ServerPlayerEntity player,
            String workspacePath,
            String originalName,
            boolean image,
            byte[] data
    ) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        if (!server.isSingleplayer() && !player.hasPermissionLevel(2)) {
            sendUploadError(player, "Only OP players can upload files to the server workspace.");
            return;
        }
        if (data == null || data.length == 0) {
            sendUploadError(player, "Upload failed: file data is empty.");
            return;
        }
        if (data.length > MAX_UPLOAD_BYTES) {
            sendUploadError(player, "Upload failed: file is larger than " + MAX_UPLOAD_BYTES + " bytes.");
            return;
        }

        String normalizedRelativePath = sanitizeWorkspaceRelativePath(workspacePath);
        if (normalizedRelativePath.isBlank()) {
            sendUploadError(player, "Upload failed: invalid target path.");
            return;
        }

        String ownerKey = player.getUuidAsString();
        SessionData session = SESSION_MANAGER.loadOrCreateActiveSession(ownerKey);
        Path workspaceRoot = SESSION_MANAGER.ensureSessionWorkspace(ownerKey, session.id());
        Path target = workspaceRoot.resolve(normalizedRelativePath).normalize();
        if (!target.startsWith(workspaceRoot)) {
            sendUploadError(player, "Upload failed: path escapes workspace.");
            return;
        }

        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, data);
            if (image) {
                LOGGER.info("[MineClawd] Uploaded image by {} -> {} ({} bytes, original={})",
                        player.getName().getString(),
                        target,
                        data.length,
                        originalName == null ? "" : originalName);
            } else {
                LOGGER.info("[MineClawd] Uploaded file by {} -> {} ({} bytes, original={})",
                        player.getName().getString(),
                        target,
                        data.length,
                        originalName == null ? "" : originalName);
            }
        } catch (Exception exception) {
            sendUploadError(player, "Upload failed: " + summarizeThrowable(exception));
        }
    }

    private void handleSubmitPromptPacket(ServerPlayerEntity player, String request, String attachmentsJson) {
        if (player == null) {
            return;
        }
        ServerCommandSource source = player.getCommandSource();
        if (!isOp(source)) {
            source.sendError(Text.literal("MineClawd: only OP users can run this command."));
            return;
        }
        String normalizedRequest = request == null ? "" : request.trim();
        if (normalizedRequest.isBlank()) {
            return;
        }
        if (normalizedRequest.length() > PROMPT_PACKET_MAX_CHARS) {
            normalizedRequest = normalizedRequest.substring(0, PROMPT_PACKET_MAX_CHARS).trim();
        }
        List<SessionAttachment> attachments = sanitizePromptAttachments(SessionAttachment.fromJsonArray(attachmentsJson));
        handleRequest(source, normalizedRequest, RequestOptions.command(attachments));
    }

    private List<SessionAttachment> sanitizePromptAttachments(List<SessionAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<SessionAttachment> sanitized = new ArrayList<>();
        for (SessionAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            if (sanitized.size() >= MAX_PROMPT_ATTACHMENTS) {
                break;
            }
            String path = sanitizeWorkspaceRelativePath(attachment.workspacePath());
            if (path.isBlank()) {
                continue;
            }
            sanitized.add(new SessionAttachment(path, attachment.originalName(), attachment.image()));
        }
        return List.copyOf(sanitized);
    }

    private String sanitizeWorkspaceRelativePath(String rawPath) {
        String normalized = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains(":")) {
            return "";
        }
        Path relative;
        try {
            relative = Path.of(normalized).normalize();
        } catch (Exception exception) {
            return "";
        }
        if (relative.isAbsolute()) {
            return "";
        }
        for (Path segment : relative) {
            if ("..".equals(segment.toString())) {
                return "";
            }
        }
        String safe = relative.toString().replace('\\', '/');
        return safe.isBlank() || ".".equals(safe) ? "" : safe;
    }

    private void sendUploadError(ServerPlayerEntity player, String message) {
        if (player == null || message == null || message.isBlank()) {
            return;
        }
        player.sendMessage(Text.literal("[MineClawd] " + message).formatted(Formatting.RED), false);
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
                        TOOL_LIST_FILES,
                        "List files/directories under the server root. Supports optional path, recursive, and limit.",
                        listFilesToolParameters()
                ),
                new OpenAITool(
                        TOOL_READ_FILES,
                        "Read a UTF-8 text file under the server root.",
                        pathToolParameters("Relative file path under the server root, for example `logs/latest.log` or `world/serverconfig/mod.json`.")
                ),
                new OpenAITool(
                        TOOL_WRITE_FILES,
                        "Write or overwrite a UTF-8 file under the server root.",
                        writeToolParameters()
                ),
                new OpenAITool(
                        TOOL_COPY_FILES,
                        "Copy a file or directory under the server root.",
                        copyMoveToolParameters()
                ),
                new OpenAITool(
                        TOOL_MOVE_FILES,
                        "Move or rename a file or directory under the server root.",
                        copyMoveToolParameters()
                ),
                new OpenAITool(
                        TOOL_GREP,
                        "Search file contents using regex under the server root.",
                        grepToolParameters()
                ),
                new OpenAITool(
                        TOOL_CURL,
                        "Perform an HTTP request and return status/body.",
                        curlToolParameters()
                ),
                new OpenAITool(
                        TOOL_READ_IMAGE,
                        "Read and describe an image file under the server root using the configured vision model.",
                        readImageToolParameters()
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
                        "List currently tracked persistent asset records for this player/session owner.",
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
                        TOOL_LIST_FILES,
                        "List files/directories under the server root. Supports optional path, recursive, and limit.",
                        listFilesToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_READ_FILES,
                        "Read a UTF-8 text file under the server root.",
                        pathToolParameters("Relative file path under the server root, for example `logs/latest.log` or `world/serverconfig/mod.json`.")
                ),
                new VertexAIFunction(
                        TOOL_WRITE_FILES,
                        "Write or overwrite a UTF-8 file under the server root.",
                        writeToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_COPY_FILES,
                        "Copy a file or directory under the server root.",
                        copyMoveToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_MOVE_FILES,
                        "Move or rename a file or directory under the server root.",
                        copyMoveToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_GREP,
                        "Search file contents using regex under the server root.",
                        grepToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_CURL,
                        "Perform an HTTP request and return status/body.",
                        curlToolParameters()
                ),
                new VertexAIFunction(
                        TOOL_READ_IMAGE,
                        "Read and describe an image file under the server root using the configured vision model.",
                        readImageToolParameters()
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
                        "List currently tracked persistent asset records for this player/session owner.",
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
        path.addProperty("description", "Relative file path under the server root, e.g. kubejs/server_scripts/mineclawd/logic/events.js");
        properties.add("path", path);

        JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        content.addProperty("description", "Full UTF-8 file content to write.");
        properties.add("content", content);

        return objectToolParameters(properties, "path", "content");
    }

    private JsonObject listFilesToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Optional relative path under the server root. Omit for root.");
        properties.add("path", path);

        JsonObject recursive = new JsonObject();
        recursive.addProperty("type", "boolean");
        recursive.addProperty("description", "Whether to walk directories recursively (default false).");
        properties.add("recursive", recursive);

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("description", "Optional max listed entries (1-500, default 120).");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 500);
        properties.add("limit", limit);

        return objectToolParameters(properties);
    }

    private JsonObject copyMoveToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject from = new JsonObject();
        from.addProperty("type", "string");
        from.addProperty("description", "Source relative path under server root.");
        properties.add("from", from);

        JsonObject to = new JsonObject();
        to.addProperty("type", "string");
        to.addProperty("description", "Destination relative path under server root.");
        properties.add("to", to);

        return objectToolParameters(properties, "from", "to");
    }

    private JsonObject grepToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject pattern = new JsonObject();
        pattern.addProperty("type", "string");
        pattern.addProperty("description", "Regex pattern to search.");
        properties.add("pattern", pattern);

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Optional relative path under server root.");
        properties.add("path", path);

        JsonObject glob = new JsonObject();
        glob.addProperty("type", "string");
        glob.addProperty("description", "Optional file glob filter, e.g. `*.json` or `kubejs/**/*.js`.");
        properties.add("glob", glob);

        JsonObject caseSensitive = new JsonObject();
        caseSensitive.addProperty("type", "boolean");
        caseSensitive.addProperty("description", "Whether matching is case-sensitive (default false).");
        properties.add("case_sensitive", caseSensitive);

        JsonObject maxMatches = new JsonObject();
        maxMatches.addProperty("type", "integer");
        maxMatches.addProperty("description", "Optional maximum matches to return (1-500, default 120).");
        maxMatches.addProperty("minimum", 1);
        maxMatches.addProperty("maximum", 500);
        properties.add("max_matches", maxMatches);

        return objectToolParameters(properties, "pattern");
    }

    private JsonObject curlToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject url = new JsonObject();
        url.addProperty("type", "string");
        url.addProperty("description", "HTTP(S) URL to request.");
        properties.add("url", url);

        JsonObject method = new JsonObject();
        method.addProperty("type", "string");
        method.addProperty("description", "Optional HTTP method: GET, POST, PUT, PATCH, DELETE, HEAD.");
        properties.add("method", method);

        JsonObject body = new JsonObject();
        body.addProperty("type", "string");
        body.addProperty("description", "Optional request body for non-GET methods.");
        properties.add("body", body);

        JsonObject headers = new JsonObject();
        headers.addProperty("type", "object");
        headers.addProperty("description", "Optional request headers as key/value strings.");
        properties.add("headers", headers);

        JsonObject timeoutSeconds = new JsonObject();
        timeoutSeconds.addProperty("type", "integer");
        timeoutSeconds.addProperty("description", "Optional timeout in seconds (1-120, default 30).");
        timeoutSeconds.addProperty("minimum", 1);
        timeoutSeconds.addProperty("maximum", 120);
        properties.add("timeout_seconds", timeoutSeconds);

        return objectToolParameters(properties, "url");
    }

    private JsonObject readImageToolParameters() {
        JsonObject properties = new JsonObject();

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "Relative image path under the server root.");
        properties.add("path", path);

        JsonObject prompt = new JsonObject();
        prompt.addProperty("type", "string");
        prompt.addProperty("description", "Optional specific instruction for analyzing the image.");
        properties.add("prompt", prompt);

        return objectToolParameters(properties, "path");
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

    private String buildSystemPrompt(
            ServerCommandSource source,
            MineClawdConfig config,
            String ownerKey,
            boolean dynamicRegistryEnabled,
            SessionData session
    ) {
        String configured = config == null ? "" : config.systemPrompt;
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String basePrompt = BASE_SYSTEM_PROMPT;
        Persona persona = PERSONA_MANAGER.loadActivePersona(ownerKey);
        Path serverRoot = WorkspaceFileToolExecutor.serverRoot(source);
        if (serverRoot == null) {
            serverRoot = Platform.getGameFolder().toAbsolutePath().normalize();
        }
        Path serverScriptsPath = serverRoot.resolve("kubejs").resolve("server_scripts").normalize();
        String serverScriptsToolPath = WorkspaceFileToolExecutor.displayPath(serverRoot, serverScriptsPath);
        if (serverScriptsToolPath.isBlank()
                || ".".equals(serverScriptsToolPath)
                || serverScriptsToolPath.contains(":")
                || serverScriptsToolPath.startsWith("/")) {
            serverScriptsToolPath = "kubejs/server_scripts";
        }

        String env = buildEnvironmentInfo();
        String installedMods = buildInstalledModsInfo();
        StringBuilder prompt = new StringBuilder(basePrompt);
        if (!env.isBlank()) {
            prompt.append("\n\nEnvironment:\n").append(env);
        }
        if (!installedMods.isBlank()) {
            prompt.append("\n\nInstalled mods:\n").append(installedMods);
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
            Path workspacePath = SESSION_MANAGER.ensureSessionWorkspace(ownerKey, session.id());
            String workspaceToolPath = WorkspaceFileToolExecutor.displayPath(serverRoot, workspacePath);
            if (workspaceToolPath.isBlank()
                    || ".".equals(workspaceToolPath)
                    || workspaceToolPath.contains(":")
                    || workspaceToolPath.startsWith("/")) {
                workspaceToolPath = "mineclawd/sessions/<owner>/<session>/workspace";
            }
            prompt.append("\n\nSession context:\n")
                    .append("Current session id: ").append(session.id()).append("\n")
                    .append("Current session token: ").append(session.commandToken()).append("\n")
                    .append("File tools path rules:\n")
                    .append("- Use server-root-relative paths only (no drive letters, no leading slash).\n")
                    .append("- server-scripts: ").append(serverScriptsToolPath).append("\n")
                    .append("- workspace: ").append(workspaceToolPath).append("\n")
                    .append("Uploaded files/images for this session are stored in workspace.\n")
                    .append("For persistent callback scripts, prefer the stable session id when using `mineclawd.requestWithSession`.\n")
                    .append("If this session is removed later, callback requests using it will fail safely.");
        } else {
            prompt.append("\n\nFile path context:\n")
                    .append("Use server-root-relative paths in file tools (no drive letters, no leading slash).\n")
                    .append("Most-used path: server-scripts = ").append(serverScriptsToolPath).append("\n")
                    .append("Session workspace path becomes available on session-backed requests.");
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
        Map<String, Mod> byId = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Mod mod : Platform.getMods()) {
            if (mod == null) {
                continue;
            }
            String modId = mod.getModId() == null ? "" : mod.getModId().trim();
            if (modId.isBlank()) {
                continue;
            }
            byId.putIfAbsent(modId, mod);
        }
        if (byId.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Loaded mods (").append(byId.size()).append("):\n");
        for (Mod mod : byId.values()) {
            if (mod == null) {
                continue;
            }
            String modId = mod.getModId() == null ? "" : mod.getModId().trim();
            if (modId.isBlank()) {
                continue;
            }
            String name = mod.getName() == null ? "" : mod.getName().trim();
            String version = mod.getVersion() == null ? "" : mod.getVersion().trim();
            sb.append("- ").append(modId);
            if (!name.isBlank() && !name.equalsIgnoreCase(modId)) {
                sb.append(" (").append(name).append(")");
            }
            if (!version.isBlank()) {
                sb.append(" v").append(version);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
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
        payload.addProperty("short", descriptor == null ? "" : normalizeStatusText(descriptor.shortText(), 220));
        payload.addProperty("hover", descriptor == null ? "" : normalizeStatusText(descriptor.hoverText(), 700));
        return payload.toString();
    }

    private void sendToolStatusChatLine(ServerCommandSource source, ToolStatusDescriptor descriptor) {
        if (source == null || descriptor == null || descriptor.shortText() == null || descriptor.shortText().isBlank()) {
            return;
        }
        String shortText = normalizeStatusText(descriptor.shortText(), TOOL_STATUS_CHAT_MAX_CHARS);
        if (shortText.isBlank()) {
            return;
        }
        MutableText line = Text.literal(shortText).formatted(Formatting.GRAY);
        String hover = normalizeStatusText(descriptor.hoverText(), 700);
        if (!hover.isBlank()) {
            line.setStyle(line.getStyle().withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
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
                hoverText = modId.isBlank() ? "Listing server root commands." : "Filter mod_id: " + modId;
            }
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
            case TOOL_SEARCH -> {
                String query = readOptionalStringArg(args, "query");
                shortText = "Searching the web";
                hoverText = query.isBlank() ? "Web search query unavailable." : "Query: " + query;
            }
            case TOOL_LIST_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Listing files" : "Listing files in " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Listing paths under server root." : "Path: " + path;
            }
            case TOOL_READ_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Reading file" : "Reading " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
            }
            case TOOL_WRITE_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Writing file" : "Writing " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Writing a server file." : "Path: " + path;
            }
            case TOOL_COPY_FILES -> {
                shortText = "Copying files";
                hoverText = "From: " + readOptionalStringArg(args, "from") + " -> To: " + readOptionalStringArg(args, "to");
            }
            case TOOL_MOVE_FILES -> {
                shortText = "Moving files";
                hoverText = "From: " + readOptionalStringArg(args, "from") + " -> To: " + readOptionalStringArg(args, "to");
            }
            case TOOL_GREP -> {
                shortText = "Searching files";
                hoverText = "Pattern: " + readOptionalStringArg(args, "pattern");
            }
            case TOOL_CURL -> {
                String rawUrl = readOptionalStringArg(args, "url");
                String host = extractUrlHost(rawUrl);
                shortText = host.isBlank() ? "Running curl" : "Requesting " + host;
                hoverText = rawUrl.isBlank() ? "" : "URL: " + rawUrl;
            }
            case TOOL_READ_IMAGE -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Reading image" : "Reading image " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
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
            case TOOL_LIST_DYNAMIC_CONTENT -> shortText = "Inspecting dynamic content slots";
            case TOOL_REGISTER_DYNAMIC_ITEM, TOOL_REGISTER_DYNAMIC_BLOCK, TOOL_REGISTER_DYNAMIC_FLUID ->
                    shortText = "Registering dynamic content";
            case TOOL_UPDATE_DYNAMIC_ITEM, TOOL_UPDATE_DYNAMIC_BLOCK, TOOL_UPDATE_DYNAMIC_FLUID ->
                    shortText = "Updating dynamic content";
            case TOOL_UNREGISTER_DYNAMIC_CONTENT -> shortText = "Unregistering dynamic content";
            case TOOL_LIST_ASSETS -> shortText = "Listing tracked assets";
            case TOOL_UPSERT_ASSET_RECORD -> shortText = "Updating tracked asset";
            case TOOL_REMOVE_ASSET_RECORD -> shortText = "Removing tracked asset";
            default -> {
                shortText = "Running task step";
                hoverText = normalizedName.isBlank() ? "" : "Tool: " + normalizedName;
            }
        }

        return new ToolStatusDescriptor(
                normalizeStatusText(shortText, 220),
                normalizeStatusText(hoverText, 700)
        );
    }

    private ToolStatusDescriptor buildToolStatusCompletedDescriptor(String toolName, JsonObject args) {
        String normalizedName = toolName == null ? "" : toolName.trim();
        String shortText;
        String hoverText = "";

        switch (normalizedName) {
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
                hoverText = modId.isBlank() ? "Listed server root commands." : "Filter mod_id: " + modId;
            }
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
            case TOOL_SEARCH -> {
                String query = readOptionalStringArg(args, "query");
                shortText = "Completed web search";
                hoverText = query.isBlank() ? "Web search query unavailable." : "Query: " + query;
            }
            case TOOL_LIST_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Listed files" : "Listed files in " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Listed paths under server root." : "Path: " + path;
            }
            case TOOL_READ_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Read file" : "Read " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
            }
            case TOOL_WRITE_FILES -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Wrote file" : "Wrote " + summarizePathTail(path);
                hoverText = path.isBlank() ? "Wrote a server file." : "Path: " + path;
            }
            case TOOL_COPY_FILES -> {
                shortText = "Copied files";
                hoverText = "From: " + readOptionalStringArg(args, "from") + " -> To: " + readOptionalStringArg(args, "to");
            }
            case TOOL_MOVE_FILES -> {
                shortText = "Moved files";
                hoverText = "From: " + readOptionalStringArg(args, "from") + " -> To: " + readOptionalStringArg(args, "to");
            }
            case TOOL_GREP -> {
                shortText = "Searched files";
                hoverText = "Pattern: " + readOptionalStringArg(args, "pattern");
            }
            case TOOL_CURL -> {
                String rawUrl = readOptionalStringArg(args, "url");
                String host = extractUrlHost(rawUrl);
                shortText = host.isBlank() ? "Completed curl" : "Fetched " + host;
                hoverText = rawUrl.isBlank() ? "" : "URL: " + rawUrl;
            }
            case TOOL_READ_IMAGE -> {
                String path = readOptionalStringArg(args, "path");
                shortText = path.isBlank() ? "Read image" : "Read image " + summarizePathTail(path);
                hoverText = path.isBlank() ? "" : "Path: " + path;
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
            case TOOL_LIST_DYNAMIC_CONTENT -> shortText = "Inspected dynamic content slots";
            case TOOL_REGISTER_DYNAMIC_ITEM, TOOL_REGISTER_DYNAMIC_BLOCK, TOOL_REGISTER_DYNAMIC_FLUID ->
                    shortText = "Registered dynamic content";
            case TOOL_UPDATE_DYNAMIC_ITEM, TOOL_UPDATE_DYNAMIC_BLOCK, TOOL_UPDATE_DYNAMIC_FLUID ->
                    shortText = "Updated dynamic content";
            case TOOL_UNREGISTER_DYNAMIC_CONTENT -> shortText = "Unregistered dynamic content";
            case TOOL_LIST_ASSETS -> shortText = "Listed tracked assets";
            case TOOL_UPSERT_ASSET_RECORD -> shortText = "Updated tracked asset";
            case TOOL_REMOVE_ASSET_RECORD -> shortText = "Removed tracked asset";
            default -> {
                shortText = "Completed task step";
                hoverText = normalizedName.isBlank() ? "" : "Tool: " + normalizedName;
            }
        }

        return new ToolStatusDescriptor(
                normalizeStatusText(shortText, 220),
                normalizeStatusText(hoverText, 700)
        );
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
        return normalizeStatusText(normalized, 700);
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

    private String normalizeStatusText(String text, int maxChars) {
        String normalized = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        while (normalized.contains("  ")) {
            normalized = normalized.replace("  ", " ");
        }
        if (maxChars > 0 && normalized.length() > maxChars) {
            return normalized.substring(0, maxChars).trim() + "...";
        }
        return normalized;
    }

    private void debugLog(AgentRuntime runtime, String format, Object... args) {
        if (runtime == null || !runtime.debug()) {
            return;
        }
        LOGGER.info("[MineClawd Debug] [session:{}] {}", runtime.sessionId(), String.format(format, args));
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
        if (!canUseGui(player, MineClawdNetworking.OPEN_HISTORY_BOOK)) {
            sendAgentMessage(source, "Client history UI unavailable (client mod missing or `Enable GUI` is off).");
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
        if (!sendPacketToPlayer(player, MineClawdNetworking.OPEN_HISTORY_BOOK, payload, "open_history_book")) {
            sendAgentMessage(source, "History book packet failed to send; use `/mineclawd sessions list` for chat fallback.");
        }
    }

    private static final class UploadAssembly {
        private final String workspacePath;
        private final String originalName;
        private final boolean image;
        private final int totalChunks;
        private final long createdAtEpochMs;
        private final ByteArrayOutputStream buffer;
        private int nextChunkIndex;
        private int totalBytes;

        private UploadAssembly(String workspacePath, String originalName, boolean image, int totalChunks, long createdAtEpochMs) {
            this.workspacePath = workspacePath;
            this.originalName = originalName;
            this.image = image;
            this.totalChunks = totalChunks;
            this.createdAtEpochMs = createdAtEpochMs;
            this.buffer = new ByteArrayOutputStream();
            this.nextChunkIndex = 0;
            this.totalBytes = 0;
        }
    }

    private record ToolStatusDescriptor(String shortText, String hoverText) {
    }

    private record PreparedPrompt(String text, List<JsonObject> openAiParts, List<JsonObject> vertexParts) {
    }

    private record RequestOptions(
            boolean requireOp,
            boolean sessionBacked,
            String ownerKey,
            String sessionReference,
            boolean interactiveErrorActions,
            List<SessionAttachment> attachments
    ) {
        private static RequestOptions command() {
            return command(List.of());
        }

        private static RequestOptions command(List<SessionAttachment> attachments) {
            return new RequestOptions(true, true, null, null, true, attachments == null ? List.of() : List.copyOf(attachments));
        }

        private static RequestOptions sessionBound(String ownerKey, String sessionReference) {
            return new RequestOptions(false, true, ownerKey, sessionReference, true, List.of());
        }

        private static RequestOptions oneShot(String ownerKey) {
            return new RequestOptions(false, false, ownerKey, null, false, List.of());
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

    private boolean sendPacketToPlayer(ServerPlayerEntity player, Identifier channel, RegistryByteBuf payload, String context) {
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
            MineClawdConfig.LlmProvider provider,
            String userRequest,
            boolean sessionBacked,
            boolean interactiveErrorActions,
            boolean clientStreamEnabled
    ) {
    }
}
