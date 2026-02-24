package com.mineclawd.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.AgentStreamEventType;
import com.mineclawd.MineClawd;
import com.mineclawd.MineClawdClientNetworking;
import com.mineclawd.MineClawdNetworking;
import com.mineclawd.assets.AssetsOverlayPayload;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionResponsePayload;
import com.mineclawd.session.SessionOverlayPayload;
import de.themoep.minedown.adventure.MineDown;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AgentResponseOverlay {
    private static final int MIN_WIDTH = 176;
    private static final int MIN_HEIGHT = 82;
    private static final int QUESTION_MIN_HEIGHT = 182;
    private static final int QUESTION_MIN_WIDTH = 264;
    private static final int EDGE_PADDING = 0;
    private static final int DEFAULT_MARGIN = 12;
    private static final int HEADER_HEIGHT = 22;
    private static final int CONTENT_PADDING = 8;
    private static final int RESIZE_HANDLE_SIZE = 14;
    private static final int ORB_RADIUS = 15;
    private static final int ORB_DRAG_THRESHOLD = 1;
    private static final int SCROLL_STEP = 14;
    private static final long CURSOR_BLINK_MS = 450L;
    private static final int QUESTION_MAX_LINES = 4;
    private static final int QUESTION_BUTTON_HEIGHT = 18;
    private static final int QUESTION_BUTTON_GAP = 4;
    private static final int QUESTION_BLOCK_GAP = 6;
    private static final int HEADER_BUTTON_SIZE = 10;
    private static final int HEADER_BUTTON_TOP = 5;
    private static final int HEADER_BUTTON_RIGHT_MARGIN = 8;
    private static final int HEADER_BUTTON_GAP = 4;
    private static final int MENU_WIDTH = 122;
    private static final int MENU_ITEM_HEIGHT = 16;
    private static final int INPUT_HEIGHT = 18;
    private static final int INPUT_GAP = 6;
    private static final int INPUT_SEND_BUTTON_WIDTH = 40;
    private static final int INPUT_TEXT_PADDING = 4;
    private static final int INPUT_MAX_CHARS = 600;
    private static final long THINKING_DOTS_FRAME_MS = 350L;
    private static final long TOOL_STATUS_ANIM_STEP_MS = 95L;
    private static final long TOOL_STATUS_ANIM_PAUSE_MS = 1000L;
    private static final long TOOL_STATUS_MIN_VISIBLE_MS = 900L;
    private static final int TOOL_STATUS_WINDOW_CHARS = 14;
    private static final int TOOL_STATUS_TOOLTIP_MAX_WIDTH = 260;
    private static final int ORB_ICON_OFFSET_X = 1;
    private static final int ORB_ICON_OFFSET_Y = 1;
    private static final int SESSIONS_NEW_BUTTON_WIDTH = 72;
    private static final int SESSIONS_NEW_BUTTON_HEIGHT = 14;
    private static final int SESSIONS_ROW_HEIGHT = 26;
    private static final int SESSIONS_ROW_GAP = 4;
    private static final int ASSETS_FILTER_BUTTON_HEIGHT = 14;
    private static final int ASSETS_FILTER_BUTTON_GAP = 4;
    private static final int ASSETS_ROW_HEIGHT = 30;
    private static final int ASSETS_ROW_GAP = 4;
    private static final int ASSETS_ACTION_BAR_HEIGHT = 40;
    private static final int ASSETS_ACTION_BUTTON_HEIGHT = 15;
    private static final int ASSETS_ACTION_BUTTON_GAP = 4;
    private static final float OVERLAY_Z = 4000.0F;
    private static final GsonComponentSerializer ADVENTURE_GSON = GsonComponentSerializer.gson();
    private static final Identifier ORB_ICON_TEXTURE = Identifier.of(MineClawd.MOD_ID, "textures/gui/icon-simplified.png");
    private static final int ORB_ICON_TEXTURE_SIZE = 1024;
    private static final String USER_MARKER = "\u0001usr:";
    private static final String TOOL_MARKER = "\u0001tool:";
    private static final int USER_PREFIX_COLOR = 0xFF88D6FF;
    private static final int USER_TEXT_COLOR = 0xFFCBEAFF;
    private static final int TOOL_TEXT_COLOR = 0xFF9CA9B8;
    private static final int AGENT_PREFIX_COLOR = 0xFFE0D68A;
    private static final int AGENT_TEXT_COLOR = 0xFFEFF4FF;
    private static final Pattern MINEDOWN_ACTION_COLON_PATTERN = Pattern.compile(
            "\\((run_command|suggest_command|copy_to_clipboard|change_page|open_url|show_text|hover|insert|show_entity|show_item|custom|show_dialog)\\s*:\\s*",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter
            .ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static boolean visible = false;
    private static boolean minimized = false;
    private static boolean generating = false;
    private static boolean layoutInitialized = false;
    private static boolean assistiveTouchEnabled = true;
    private static boolean guiEnabled = true;
    private static OverlayMode mode = OverlayMode.RESPONSE;

    private static int panelX = 0;
    private static int panelY = 0;
    private static int panelWidth = 360;
    private static int panelHeight = 220;
    private static int expandedPanelX = Integer.MIN_VALUE;
    private static int expandedPanelY = Integer.MIN_VALUE;

    private static boolean dragging = false;
    private static boolean resizing = false;
    private static int dragOffsetX = 0;
    private static int dragOffsetY = 0;
    private static int resizeStartMouseX = 0;
    private static int resizeStartMouseY = 0;
    private static int resizeStartWidth = 0;
    private static int resizeStartHeight = 0;

    private static boolean orbPressed = false;
    private static boolean orbDragging = false;
    private static int orbPressMouseX = 0;
    private static int orbPressMouseY = 0;

    private static String activeRequestId = "";
    private static String activeSessionId = "";
    private static String activeToolStatusText = "";
    private static String activeToolStatusHover = "";
    private static long toolStatusAnimStartEpochMs = 0L;
    private static long toolStatusShownEpochMs = 0L;
    private static long toolStatusPendingClearEpochMs = 0L;
    private static boolean autoCreateSessionOnFirstSubmit = true;
    private static boolean awaitingFirstAssistantDelta = false;
    private static final StringBuilder content = new StringBuilder();
    private static final List<OrderedText> wrappedLines = new ArrayList<>();
    private static boolean parsedDirty = true;
    private static boolean wrappedDirty = true;
    private static int wrappedWidth = -1;
    private static Text parsedText = Text.empty();
    private static double scrollY = 0.0;
    private static boolean followTail = true;

    private static QuestionPromptPayload pendingQuestion = null;
    private static long pendingQuestionDeadlineEpochMillis = 0L;
    private static final List<QuestionButtonBounds> questionButtons = new ArrayList<>();
    private static boolean menuOpen = false;
    private static final List<MenuItemBounds> menuItems = new ArrayList<>();
    private static final List<SessionRowBounds> sessionRows = new ArrayList<>();
    private static final List<AssetRowBounds> assetRows = new ArrayList<>();
    private static final List<AssetFilterButtonBounds> assetFilterButtons = new ArrayList<>();
    private static final RectBounds newSessionButton = new RectBounds();
    private static final RectBounds inputFieldBounds = new RectBounds();
    private static final RectBounds inputSendBounds = new RectBounds();
    private static final RectBounds assetTeleportButton = new RectBounds();
    private static final RectBounds assetGiveButton = new RectBounds();
    private static final RectBounds assetModifyButton = new RectBounds();
    private static final RectBounds assetDeleteButton = new RectBounds();
    private static final RectBounds toolStatusBounds = new RectBounds();
    private static final List<SessionOverlayPayload.SessionItem> sessionItems = new ArrayList<>();
    private static final List<AssetsOverlayPayload.AssetItem> assetItems = new ArrayList<>();
    private static final List<String> personaNames = new ArrayList<>();
    private static String activePersona = "";
    private static AssetFilter activeAssetFilter = AssetFilter.ALL;
    private static double sessionsScrollY = 0.0;
    private static double assetsScrollY = 0.0;
    private static String selectedAssetId = "";
    private static boolean inputFocused = false;
    private static boolean inputDragSelecting = false;
    private static String inputDraft = "";
    private static int inputCursorIndex = 0;
    private static int inputSelectionIndex = 0;
    private static int inputViewStart = 0;

    private AgentResponseOverlay() {
    }

    public static void onClientGuiPreferenceChanged(MinecraftClient client, boolean enabled) {
        guiEnabled = enabled;
        if (!enabled) {
            hideAll();
            return;
        }
        MinecraftClient instance = client == null ? MinecraftClient.getInstance() : client;
        ensureIdleOrbVisible(instance);
    }

    public static void syncAssistiveTouchFromServer(MinecraftClient client, boolean enabled) {
        assistiveTouchEnabled = enabled;
        if (!enabled) {
            hideAll();
            return;
        }
        MinecraftClient instance = client == null ? MinecraftClient.getInstance() : client;
        ensureIdleOrbVisible(instance);
    }

    public static void onClientPlayerQuit() {
        hideAll();
        assistiveTouchEnabled = true;
        guiEnabled = MineClawdClientNetworking.isGuiEnabled();
        layoutInitialized = false;
        expandedPanelX = Integer.MIN_VALUE;
        expandedPanelY = Integer.MIN_VALUE;
        autoCreateSessionOnFirstSubmit = true;
        clearToolStatusState();
    }

    public static void handleStreamEvent(String requestId, AgentStreamEventType type, String payload) {
        if (!isOverlayEnabled()) {
            return;
        }
        String normalizedId = requestId == null ? "" : requestId;
        AgentStreamEventType eventType = type == null ? AgentStreamEventType.DELTA : type;
        String text = payload == null ? "" : payload;

        if (eventType == AgentStreamEventType.START) {
            StreamStartPayload startPayload = StreamStartPayload.fromJson(text);
            String startSessionId = startPayload == null ? "" : startPayload.sessionId();
            String prompt = startPayload == null ? "" : startPayload.request();
            clearToolStatusState();
            if (!startSessionId.isBlank()) {
                if (!startSessionId.equals(activeSessionId)) {
                    activeSessionId = startSessionId;
                    clearResponseContent();
                } else if (content.length() > 0) {
                    content.append("\n\n");
                }
            } else if (content.length() > 0) {
                content.append("\n\n");
            }
            if (!prompt.isBlank()) {
                content.append(markUserLines(prompt)).append("\n\n");
            }
            activeRequestId = normalizedId;
            autoCreateSessionOnFirstSubmit = false;
            awaitingFirstAssistantDelta = true;
            parsedDirty = true;
            wrappedDirty = true;
            wrappedWidth = -1;
            followTail = true;
            visible = true;
            generating = true;
            if (minimized) {
                restoreExpandedPanelFromOrb(MinecraftClient.getInstance());
            }
            minimized = false;
            mode = OverlayMode.RESPONSE;
            dragging = false;
            resizing = false;
            orbPressed = false;
            orbDragging = false;
            menuOpen = false;
            return;
        }

        if (!normalizedId.isBlank() && !activeRequestId.isBlank() && !activeRequestId.equals(normalizedId)) {
            return;
        }
        if (activeRequestId.isBlank() && !normalizedId.isBlank()) {
            activeRequestId = normalizedId;
        }
        if (!visible) {
            visible = true;
        }

        if (eventType == AgentStreamEventType.TOOL_STATUS) {
            ToolStatusPayload status = ToolStatusPayload.fromJson(text);
            if (status == null || status.shortText().isBlank()) {
                clearToolStatusState();
            } else {
                String shortText = status.shortText().trim();
                if (!shortText.equals(activeToolStatusText)) {
                    toolStatusAnimStartEpochMs = System.currentTimeMillis();
                }
                activeToolStatusText = shortText;
                activeToolStatusHover = status.hoverText() == null ? "" : status.hoverText().trim();
                toolStatusShownEpochMs = System.currentTimeMillis();
                toolStatusPendingClearEpochMs = 0L;
            }
            return;
        }

        if (eventType == AgentStreamEventType.TOOL_STATUS_CLEAR) {
            ToolStatusPayload status = ToolStatusPayload.fromJson(text);
            if (status != null && status.shortText() != null && !status.shortText().isBlank()) {
                appendToolStatusCompletion(status.shortText().trim());
                clearToolStatusState();
                return;
            }
            requestToolStatusClear();
            return;
        }

        if (eventType == AgentStreamEventType.DELTA) {
            if (!text.isBlank()) {
                awaitingFirstAssistantDelta = false;
            }
            appendText(text);
            generating = true;
            mode = OverlayMode.RESPONSE;
            return;
        }

        if (eventType == AgentStreamEventType.ERROR) {
            awaitingFirstAssistantDelta = false;
            appendText(text);
            clearToolStatusState();
            generating = false;
            return;
        }

        if (eventType == AgentStreamEventType.DONE) {
            awaitingFirstAssistantDelta = false;
            clearToolStatusState();
            generating = false;
        }
    }

    public static void handleQuestionPrompt(MinecraftClient client, QuestionPromptPayload payload) {
        if (payload == null || !isOverlayEnabled()) {
            return;
        }
        pendingQuestion = payload;
        long expiresAt = payload.expiresAtEpochMillis();
        pendingQuestionDeadlineEpochMillis = expiresAt > 0L ? expiresAt : (System.currentTimeMillis() + 60000L);
        questionButtons.clear();
        visible = true;
        if (minimized) {
            restoreExpandedPanelFromOrb(client == null ? MinecraftClient.getInstance() : client);
        }
        minimized = false;
        mode = OverlayMode.RESPONSE;
        MinecraftClient instance = client == null ? MinecraftClient.getInstance() : client;
        if (instance != null) {
            ensureLayout(instance);
            int minHeight = minPanelHeight(instance, panelWidth);
            if (panelHeight < minHeight) {
                panelHeight = minHeight;
                clampPanelToScreen(instance);
            }
        }
    }

    public static void handleSessionsPayload(MinecraftClient client, SessionOverlayPayload payload) {
        if (payload == null || !isOverlayEnabled()) {
            return;
        }
        sessionItems.clear();
        sessionItems.addAll(payload.sessions());
        personaNames.clear();
        personaNames.addAll(payload.personas());
        activePersona = payload.activePersona() == null ? "" : payload.activePersona().trim();
        activeSessionId = payload.activeSessionId() == null ? "" : payload.activeSessionId().trim();
        applyHistory(payload.history());
        activeRequestId = "";
        awaitingFirstAssistantDelta = false;
        clearToolStatusState();
        generating = false;
        followTail = true;
        scrollY = 0.0;
        sessionsScrollY = 0.0;
        visible = true;
        if (minimized) {
            restoreExpandedPanelFromOrb(client == null ? MinecraftClient.getInstance() : client);
        }
        minimized = false;
        if (payload.openUi()) {
            mode = OverlayMode.SESSIONS;
            inputFocused = false;
            inputDragSelecting = false;
        }
        MinecraftClient instance = client == null ? MinecraftClient.getInstance() : client;
        if (instance != null) {
            ensureLayout(instance);
        }
    }

    public static void handleAssetsPayload(MinecraftClient client, AssetsOverlayPayload payload) {
        if (payload == null || !isOverlayEnabled()) {
            return;
        }
        assetItems.clear();
        assetItems.addAll(payload.assets());
        personaNames.clear();
        personaNames.addAll(payload.personas());
        activePersona = payload.activePersona() == null ? "" : payload.activePersona().trim();
        activeSessionId = payload.activeSessionId() == null ? "" : payload.activeSessionId().trim();
        awaitingFirstAssistantDelta = false;
        clearToolStatusState();
        assetsScrollY = 0.0;
        assetRows.clear();
        assetFilterButtons.clear();
        if (assetItems.isEmpty()) {
            selectedAssetId = "";
        } else if (selectedAssetId.isBlank() || findAssetById(selectedAssetId) == null || !isSelectedAssetVisibleWithFilter(selectedAssetId)) {
            selectedAssetId = firstVisibleAssetId();
        }
        visible = true;
        if (minimized) {
            restoreExpandedPanelFromOrb(client == null ? MinecraftClient.getInstance() : client);
        }
        minimized = false;
        if (payload.openUi()) {
            mode = OverlayMode.ASSETS;
            inputFocused = false;
            inputDragSelecting = false;
        }
        MinecraftClient instance = client == null ? MinecraftClient.getInstance() : client;
        if (instance != null) {
            ensureLayout(instance);
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (toolStatusPendingClearEpochMs > 0L && now >= toolStatusPendingClearEpochMs) {
            clearToolStatusState();
        }
        if (!guiEnabled) {
            hideAll();
            return;
        }
        if (!assistiveTouchEnabled) {
            hideAll();
            return;
        }
        ensureIdleOrbVisible(client);
        if (!visible) {
            return;
        }
        ensureLayout(client);
        if (pendingQuestion != null && System.currentTimeMillis() >= pendingQuestionDeadlineEpochMillis) {
            submitQuestion(client, new QuestionResponsePayload(
                    pendingQuestion.questionId(),
                    QuestionResponsePayload.Type.SKIP,
                    -1,
                    "Timed out after 60 seconds."
            ));
        }
    }

    public static void renderHud(DrawContext context, MinecraftClient client) {
        if (client == null || client.currentScreen != null || !isOverlayEnabled()) {
            return;
        }
        render(context, client, 0, 0, false);
    }

    public static void renderOnScreen(DrawContext context, MinecraftClient client, int mouseX, int mouseY) {
        if (client == null || client.currentScreen == null || !isOverlayEnabled()) {
            return;
        }
        render(context, client, mouseX, mouseY, true);
    }

    public static boolean mouseClicked(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible) {
            return false;
        }
        ensureLayout(client);
        if (minimized) {
            if (!isPointInOrb(mouseX, mouseY)) {
                return false;
            }
            if (button == 0) {
                orbPressed = true;
                orbDragging = false;
                orbPressMouseX = (int) Math.round(mouseX);
                orbPressMouseY = (int) Math.round(mouseY);
                dragOffsetX = orbPressMouseX - panelX;
                dragOffsetY = orbPressMouseY - panelY;
                return true;
            }
            return true;
        }

        if (button == 0 && menuOpen) {
            MenuItemBounds menuItem = findMenuItem(mouseX, mouseY);
            if (menuItem != null) {
                handleMenuAction(client, menuItem.action);
                menuOpen = false;
                return true;
            }
            if (isPointInMenuDropdown(mouseX, mouseY) || isPointInMenuButton(mouseX, mouseY)) {
                return true;
            }
            if (!isPointInMenuDropdown(mouseX, mouseY) && !isPointInMenuButton(mouseX, mouseY)) {
                menuOpen = false;
            }
        }

        if (!isPointInPanel(mouseX, mouseY)) {
            inputFocused = false;
            inputDragSelecting = false;
            return false;
        }

        if (button == 0 && isPointInMenuButton(mouseX, mouseY)) {
            menuOpen = !menuOpen;
            return true;
        }

        if (button == 0 && handleInputClick(client, mouseX, mouseY)) {
            return true;
        }
        if (button == 0) {
            inputFocused = false;
            inputDragSelecting = false;
        }

        if (button == 0) {
            QuestionButtonBounds questionButton = findQuestionButton(mouseX, mouseY);
            if (questionButton != null) {
                handleQuestionButtonClick(client, questionButton);
                return true;
            }
        }

        if (button == 0 && isPointInCloseButton(mouseX, mouseY)) {
            setAssistiveTouchEnabled(client, false, true);
            return true;
        }

        if (button == 0 && isPointInMinimizeButton(mouseX, mouseY)) {
            minimizeToOrb(client);
            return true;
        }

        if (mode == OverlayMode.SESSIONS && button == 0) {
            if (newSessionButton.contains(mouseX, mouseY)) {
                mode = OverlayMode.RESPONSE;
                clearResponseContent();
                autoCreateSessionOnFirstSubmit = false;
                sendCommand(client, "mineclawd sessions new");
                return true;
            }
            SessionRowBounds row = findSessionRow(mouseX, mouseY);
            if (row != null && row.sessionId != null && !row.sessionId.isBlank()) {
                mode = OverlayMode.RESPONSE;
                autoCreateSessionOnFirstSubmit = false;
                sendCommand(client, "mineclawd sessions resume " + row.sessionId);
                return true;
            }
        }

        if (mode == OverlayMode.ASSETS && button == 0) {
            AssetFilterButtonBounds filterButton = findAssetFilterButton(mouseX, mouseY);
            if (filterButton != null && filterButton.filter != null) {
                if (activeAssetFilter != filterButton.filter) {
                    activeAssetFilter = filterButton.filter;
                    assetsScrollY = 0.0;
                    selectedAssetId = firstVisibleAssetId();
                }
                return true;
            }
            if (assetTeleportButton.contains(mouseX, mouseY)) {
                performAssetAction(client, AssetAction.TELEPORT);
                return true;
            }
            if (assetGiveButton.contains(mouseX, mouseY)) {
                performAssetAction(client, AssetAction.GIVE);
                return true;
            }
            if (assetModifyButton.contains(mouseX, mouseY)) {
                performAssetAction(client, AssetAction.MODIFY);
                return true;
            }
            if (assetDeleteButton.contains(mouseX, mouseY)) {
                performAssetAction(client, AssetAction.DELETE);
                return true;
            }
            AssetRowBounds row = findAssetRow(mouseX, mouseY);
            if (row != null && row.assetId != null && !row.assetId.isBlank()) {
                selectedAssetId = row.assetId;
                return true;
            }
        }

        if (button == 0 && isPointInResizeHandle(mouseX, mouseY)) {
            resizing = true;
            resizeStartMouseX = (int) Math.round(mouseX);
            resizeStartMouseY = (int) Math.round(mouseY);
            resizeStartWidth = panelWidth;
            resizeStartHeight = panelHeight;
            return true;
        }

        if (button == 0 && isPointInHeader(mouseX, mouseY)) {
            dragging = true;
            dragOffsetX = (int) Math.round(mouseX) - panelX;
            dragOffsetY = (int) Math.round(mouseY) - panelY;
            return true;
        }
        return true;
    }

    public static boolean mouseReleased(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible) {
            return false;
        }
        if (button == 0 && inputDragSelecting) {
            inputDragSelecting = false;
            return true;
        }
        if (minimized && button == 0 && orbPressed) {
            boolean wasDragging = orbDragging;
            orbPressed = false;
            orbDragging = false;
            if (!wasDragging && isPointInOrb(mouseX, mouseY)) {
                restoreExpandedPanelFromOrb(client);
                minimized = false;
            }
            return true;
        }
        if (dragging || resizing) {
            dragging = false;
            resizing = false;
            return true;
        }
        return false;
    }

    public static boolean mouseDragged(MinecraftClient client, double mouseX, double mouseY, int button) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible || button != 0) {
            return false;
        }
        ensureLayout(client);

        if (minimized) {
            if (!orbPressed) {
                return false;
            }
            int roundedX = (int) Math.round(mouseX);
            int roundedY = (int) Math.round(mouseY);
            if (!orbDragging
                    && (Math.abs(roundedX - orbPressMouseX) >= ORB_DRAG_THRESHOLD
                    || Math.abs(roundedY - orbPressMouseY) >= ORB_DRAG_THRESHOLD)) {
                orbDragging = true;
            }
            if (orbDragging) {
                panelX = roundedX - dragOffsetX;
                panelY = roundedY - dragOffsetY;
                clampPanelToScreen(client);
            }
            return true;
        }

        if (inputDragSelecting && shouldRenderInputBar()) {
            setInputCursorFromMouse(client, mouseX, true);
            return true;
        }

        if (dragging) {
            panelX = (int) Math.round(mouseX) - dragOffsetX;
            panelY = (int) Math.round(mouseY) - dragOffsetY;
            clampPanelToScreen(client);
            return true;
        }
        if (resizing) {
            int minWidth = minPanelWidth();
            int maxWidth = Math.max(minWidth, client.getWindow().getScaledWidth() - EDGE_PADDING * 2);
            int newWidth = resizeStartWidth + ((int) Math.round(mouseX) - resizeStartMouseX);
            int clampedWidth = MathHelper.clamp(newWidth, minWidth, maxWidth);
            int minHeight = minPanelHeight(client, clampedWidth);
            int maxHeight = Math.max(minHeight, client.getWindow().getScaledHeight() - EDGE_PADDING * 2);
            int newHeight = resizeStartHeight + ((int) Math.round(mouseY) - resizeStartMouseY);
            panelWidth = clampedWidth;
            panelHeight = MathHelper.clamp(newHeight, minHeight, maxHeight);
            clampPanelToScreen(client);
            return true;
        }
        return false;
    }

    public static boolean mouseScrolled(MinecraftClient client, double mouseX, double mouseY, double amountY) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible || minimized || amountY == 0.0) {
            return false;
        }
        ensureLayout(client);
        if (!isPointInPanel(mouseX, mouseY)) {
            return false;
        }

        if (mode == OverlayMode.SESSIONS) {
            double max = maxSessionsScroll(client);
            if (max <= 0.0) {
                sessionsScrollY = 0.0;
                return true;
            }
            sessionsScrollY = MathHelper.clamp(sessionsScrollY - (amountY * SCROLL_STEP), 0.0, max);
            return true;
        }
        if (mode == OverlayMode.ASSETS) {
            double max = maxAssetsScroll(client);
            if (max <= 0.0) {
                assetsScrollY = 0.0;
                return true;
            }
            assetsScrollY = MathHelper.clamp(assetsScrollY - (amountY * SCROLL_STEP), 0.0, max);
            return true;
        }

        double max = maxResponseScroll(client);
        if (max > 0.0) {
            scrollY = MathHelper.clamp(scrollY - (amountY * SCROLL_STEP), 0.0, max);
            followTail = (max - scrollY) <= 2.0;
        }
        return true;
    }

    public static boolean charTyped(MinecraftClient client, char character, int keyCode) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible || minimized) {
            return false;
        }
        // When input is not focused or input bar is not shown, don't consume char events
        // so they can reach the underlying screen (e.g. chat bar)
        if (!inputFocused || !shouldRenderInputBar()) {
            return false;
        }
        if (Character.isISOControl(character)) {
            return true;
        }
        appendInputCharacter(character);
        return true;
    }

    public static boolean keyPressed(MinecraftClient client, int keyCode, int scanCode, int modifiers) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible || minimized) {
            return false;
        }
        // Allow Escape to pass through to the underlying screen when input is not focused,
        // so the user can close the chat/pause/creative screen normally
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (inputFocused && shouldRenderInputBar()) {
                inputFocused = false;
                inputDragSelecting = false;
                return true;
            }
            // Let Escape pass through to close the underlying screen
            return false;
        }
        // When the input bar is not shown or not focused, don't consume non-Escape
        // key events so they can reach the underlying screen
        if (!shouldRenderInputBar() || !inputFocused) {
            return false;
        }
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (generating) {
                requestStopGeneration(client);
            } else {
                submitInputDraft(client);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            deleteInputBackward(ctrl);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            deleteInputForward(ctrl);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            moveInputCursorHorizontal(false, ctrl, shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            moveInputCursorHorizontal(true, ctrl, shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            setInputCursor(0, shift);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            setInputCursor(currentInputDraft().length(), shift);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            String draft = currentInputDraft();
            inputCursorIndex = draft.length();
            inputSelectionIndex = 0;
            inputViewStart = 0;
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            copySelectedInput(client);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_X) {
            if (copySelectedInput(client)) {
                deleteInputSelection();
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
            String clipboard = client == null || client.keyboard == null ? "" : client.keyboard.getClipboard();
            if (clipboard != null && !clipboard.isBlank()) {
                appendInputText(clipboard);
            }
            return true;
        }
        return true;
    }

    private static void render(DrawContext context, MinecraftClient client, int mouseX, int mouseY, boolean interactiveMode) {
        if (!isOverlayEnabled() || !visible || context == null || client == null || client.player == null) {
            return;
        }
        ensureLayout(client);
        TextRenderer renderer = client.textRenderer;
        if (renderer == null) {
            return;
        }

        if (minimized) {
            renderMinimized(context, renderer);
            return;
        }
        expandedPanelX = panelX;
        expandedPanelY = panelY;

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, OVERLAY_Z);
        try {
            context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xB0101018);
            context.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xC0507088);
            context.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0xC0507088);
            context.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xC0507088);
            context.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0xC0507088);

            context.fill(panelX, panelY, panelX + panelWidth, panelY + HEADER_HEIGHT, 0xCC1C2532);
            String modeLabel = switch (mode) {
                case SESSIONS -> "MineClawd Sessions";
                case ASSETS -> "MineClawd Assets";
                case RESPONSE -> "MineClawd Response";
            };
            context.drawTextWithShadow(renderer, modeLabel, panelX + 8, panelY + 7, 0xFFF0F6FF);
            int menuBtnLeft = menuButtonLeft();
            int headerTextRightLimit = menuBtnLeft - 6;
            int personaLeft = panelX + Math.max(96, renderer.getWidth(modeLabel) + 14);

            String statusText = "";
            int statusColor = 0xFF88D6FF;
            if (pendingQuestion != null) {
                statusText = "Question";
                statusColor = 0xFFE8D6A2;
            } else if (generating) {
                statusText = "Streaming";
            }
            toolStatusBounds.clear();
            if (!statusText.isBlank() && headerTextRightLimit > personaLeft) {
                int statusMaxWidth = headerTextRightLimit - personaLeft;
                if (statusMaxWidth > 0) {
                    String visibleStatus = renderer.trimToWidth(statusText, statusMaxWidth);
                    int statusWidth = renderer.getWidth(visibleStatus);
                    if (statusWidth > 0) {
                        int statusLeft = headerTextRightLimit - statusWidth;
                        context.drawTextWithShadow(renderer, visibleStatus, statusLeft, panelY + 7, statusColor);
                        headerTextRightLimit = statusLeft - 8;
                    }
                }
            }

            if (!activePersona.isBlank() && headerTextRightLimit > personaLeft) {
                String personaText = "Soul: " + activePersona;
                int maxWidth = headerTextRightLimit - personaLeft;
                if (maxWidth > 0) {
                    context.drawTextWithShadow(renderer, renderer.trimToWidth(personaText, maxWidth), personaLeft, panelY + 7, 0xFFA7BDD6);
                }
            }

            int minBtnLeft = minimizeButtonLeft();
            int minBtnTop = panelY + HEADER_BUTTON_TOP;
            context.fill(minBtnLeft, minBtnTop, minBtnLeft + HEADER_BUTTON_SIZE, minBtnTop + HEADER_BUTTON_SIZE, 0xCC4A5568);
            context.fill(minBtnLeft + 2, minBtnTop + 6, minBtnLeft + 8, minBtnTop + 7, 0xFFE6ECF5);

            int closeBtnLeft = closeButtonLeft();
            int closeBtnTop = panelY + HEADER_BUTTON_TOP;
            context.fill(closeBtnLeft, closeBtnTop, closeBtnLeft + HEADER_BUTTON_SIZE, closeBtnTop + HEADER_BUTTON_SIZE, 0xCC6B4040);
            context.fill(closeBtnLeft + 2, closeBtnTop + 2, closeBtnLeft + 3, closeBtnTop + 8, 0xFFFBE9E9);
            context.fill(closeBtnLeft + 7, closeBtnTop + 2, closeBtnLeft + 8, closeBtnTop + 8, 0xFFFBE9E9);
            context.fill(closeBtnLeft + 2, closeBtnTop + 2, closeBtnLeft + 8, closeBtnTop + 3, 0xFFFBE9E9);
            context.fill(closeBtnLeft + 2, closeBtnTop + 7, closeBtnLeft + 8, closeBtnTop + 8, 0xFFFBE9E9);

            int menuBtnTop = panelY + HEADER_BUTTON_TOP;
            context.fill(menuBtnLeft, menuBtnTop, menuBtnLeft + HEADER_BUTTON_SIZE, menuBtnTop + HEADER_BUTTON_SIZE, 0xCC4A5568);
            context.fill(menuBtnLeft + 2, menuBtnTop + 2, menuBtnLeft + 8, menuBtnTop + 3, 0xFFE6ECF5);
            context.fill(menuBtnLeft + 2, menuBtnTop + 5, menuBtnLeft + 8, menuBtnTop + 6, 0xFFE6ECF5);
            context.fill(menuBtnLeft + 2, menuBtnTop + 8, menuBtnLeft + 8, menuBtnTop + 9, 0xFFE6ECF5);

            int handleLeft = panelX + panelWidth - RESIZE_HANDLE_SIZE;
            int handleTop = panelY + panelHeight - RESIZE_HANDLE_SIZE;
            context.fill(handleLeft + 4, handleTop + 10, handleLeft + 12, handleTop + 11, 0xAA9BB0C8);
            context.fill(handleLeft + 7, handleTop + 7, handleLeft + 12, handleTop + 8, 0xAA9BB0C8);
            context.fill(handleLeft + 10, handleTop + 4, handleLeft + 12, handleTop + 5, 0xAA9BB0C8);

            int contentLeft = panelX + CONTENT_PADDING;
            int contentTop = panelY + HEADER_HEIGHT + 3;
            int contentRight = panelX + panelWidth - CONTENT_PADDING;
            int contentBottom = panelY + panelHeight - CONTENT_PADDING;
            boolean showInputBar = shouldRenderInputBar();
            if (showInputBar) {
                contentBottom -= (INPUT_HEIGHT + INPUT_GAP);
            } else {
                inputFieldBounds.clear();
                inputSendBounds.clear();
            }
            int contentWidth = Math.max(10, contentRight - contentLeft);

            if (pendingQuestion != null) {
                int questionHeight = renderQuestionBlock(context, renderer, contentLeft, contentTop, contentRight, mouseX, mouseY, interactiveMode);
                contentTop += questionHeight + QUESTION_BLOCK_GAP;
            } else {
                questionButtons.clear();
            }
            if (contentTop >= contentBottom) {
                contentTop = contentBottom - 1;
            }

            newSessionButton.clear();
            assetRows.clear();
            assetTeleportButton.clear();
            assetGiveButton.clear();
            assetModifyButton.clear();
            assetDeleteButton.clear();
            if (mode == OverlayMode.SESSIONS) {
                renderSessionsContent(context, renderer, contentLeft, contentTop, contentRight, contentBottom, mouseX, mouseY, interactiveMode);
            } else if (mode == OverlayMode.ASSETS) {
                renderAssetsContent(context, renderer, contentLeft, contentTop, contentRight, contentBottom, mouseX, mouseY, interactiveMode);
            } else {
                renderResponseContent(context, renderer, contentLeft, contentTop, contentRight, contentBottom, contentWidth);
            }
            if (showInputBar) {
                renderInputBar(context, renderer, mouseX, mouseY, interactiveMode);
            }

            if (interactiveMode && isPointInPanel(mouseX, mouseY)) {
                context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x10000000);
            }

            if (menuOpen) {
                context.getMatrices().push();
                context.getMatrices().translate(0.0F, 0.0F, 2000.0F);
                renderMenuDropdown(context, renderer, mouseX, mouseY, interactiveMode);
                context.getMatrices().pop();
            } else {
                menuItems.clear();
            }
            if (interactiveMode && toolStatusBounds.contains(mouseX, mouseY) && !activeToolStatusHover.isBlank()) {
                renderSimpleTooltip(context, renderer, client, mouseX, mouseY, activeToolStatusHover);
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static int renderQuestionBlock(
            DrawContext context,
            TextRenderer renderer,
            int left,
            int top,
            int right,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        if (pendingQuestion == null || renderer == null) {
            questionButtons.clear();
            return 0;
        }

        questionButtons.clear();
        int width = Math.max(20, right - left);
        int blockHeight = estimateQuestionBlockHeight(renderer, width);
        int bottom = top + blockHeight;
        context.fill(left, top, right, bottom, 0x8C1D2733);
        context.fill(left, top, right, top + 1, 0xBBAA8C4A);
        context.fill(left, bottom - 1, right, bottom, 0xBBAA8C4A);
        context.fill(left, top, left + 1, bottom, 0xBBAA8C4A);
        context.fill(right - 1, top, right, bottom, 0xBBAA8C4A);

        int textWidth = Math.max(20, width - 16);
        List<OrderedText> questionLines = renderer.wrapLines(Text.literal(pendingQuestion.question()), textWidth);
        if (questionLines.isEmpty()) {
            questionLines = List.of(Text.empty().asOrderedText());
        }
        int maxLines = Math.max(1, Math.min(QUESTION_MAX_LINES, questionLines.size()));
        int y = top + 6;

        context.drawTextWithShadow(renderer, "Need your input", left + 8, y, 0xFFF4E7C8);
        y += renderer.fontHeight + 3;

        for (int i = 0; i < maxLines; i++) {
            context.drawTextWithShadow(renderer, questionLines.get(i), left + 8, y, 0xFFEAEFF5);
            y += renderer.fontHeight + 1;
        }
        y += 4;

        List<String> options = pendingQuestion.options();
        int buttonWidth = Math.max(20, width - 12);
        for (int i = 0; i < options.size(); i++) {
            int buttonLeft = left + 6;
            int buttonTop = y;
            int buttonRight = buttonLeft + buttonWidth;
            int buttonBottom = buttonTop + QUESTION_BUTTON_HEIGHT;
            boolean hovered = interactiveMode
                    && mouseX >= buttonLeft && mouseX <= buttonRight
                    && mouseY >= buttonTop && mouseY <= buttonBottom;
            int fill = hovered ? 0xCC3E556D : 0xB8334558;
            context.fill(buttonLeft, buttonTop, buttonRight, buttonBottom, fill);
            String label = (i + 1) + ". " + options.get(i);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(label, buttonWidth - 8), buttonLeft + 4, buttonTop + 5, 0xFFF4F8FF);
            questionButtons.add(QuestionButtonBounds.option(buttonLeft, buttonTop, buttonRight, buttonBottom, i));
            y += QUESTION_BUTTON_HEIGHT + QUESTION_BUTTON_GAP;
        }

        int skipLeft = left + 6;
        int skipTop = y;
        int skipRight = skipLeft + buttonWidth;
        int skipBottom = skipTop + QUESTION_BUTTON_HEIGHT;
        boolean skipHovered = interactiveMode
                && mouseX >= skipLeft && mouseX <= skipRight
                && mouseY >= skipTop && mouseY <= skipBottom;
        int skipFill = skipHovered ? 0xCC5C4A4A : 0xB84C3D3D;
        context.fill(skipLeft, skipTop, skipRight, skipBottom, skipFill);
        String skipLabel = "Skip (" + remainingQuestionSeconds() + "s)";
        context.drawTextWithShadow(renderer, renderer.trimToWidth(skipLabel, buttonWidth - 8), skipLeft + 4, skipTop + 5, 0xFFFDF1F1);
        questionButtons.add(QuestionButtonBounds.skip(skipLeft, skipTop, skipRight, skipBottom));
        return blockHeight;
    }

    private static void renderResponseContent(
            DrawContext context,
            TextRenderer renderer,
            int contentLeft,
            int contentTop,
            int contentRight,
            int contentBottom,
            int contentWidth
    ) {
        rebuildWrappedLines(renderer, contentWidth, MinecraftClient.getInstance());
        List<OrderedText> thinkingLines = buildThinkingLines(renderer, contentWidth);
        String activeToolLine = activeToolStatusText == null ? "" : renderer.trimToWidth(activeToolStatusText, Math.max(8, contentWidth));
        boolean hasActiveToolLine = !activeToolLine.isBlank();

        if (followTail) {
            scrollY = maxResponseScroll(MinecraftClient.getInstance());
        } else {
            scrollY = MathHelper.clamp(scrollY, 0.0, maxResponseScroll(MinecraftClient.getInstance()));
        }

        int lineHeight = renderer.fontHeight + 1;
        int y = contentTop - (int) Math.round(scrollY);

        context.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        toolStatusBounds.clear();
        for (OrderedText line : wrappedLines) {
            if (y + lineHeight >= contentTop && y <= contentBottom) {
                context.drawTextWithShadow(renderer, line, contentLeft, y, 0xFFE7EEF8);
            }
            y += lineHeight;
        }
        if (hasActiveToolLine) {
            if (y + lineHeight >= contentTop && y <= contentBottom) {
                drawAnimatedToolStatus(context, renderer, activeToolLine, contentLeft, y, TOOL_TEXT_COLOR);
                int statusWidth = renderer.getWidth(activeToolLine);
                if (statusWidth > 0) {
                    toolStatusBounds.set(contentLeft, y - 1, contentLeft + statusWidth, y + lineHeight);
                }
            }
            y += lineHeight;
        }
        for (OrderedText line : thinkingLines) {
            if (y + lineHeight >= contentTop && y <= contentBottom) {
                context.drawTextWithShadow(renderer, line, contentLeft, y, 0xFFE7EEF8);
            }
            y += lineHeight;
        }

        if (generating && !shouldShowThinkingPlaceholder() && ((System.currentTimeMillis() / CURSOR_BLINK_MS) % 2L == 0L)) {
            int cursorY = contentTop - (int) Math.round(scrollY);
            int cursorX = contentLeft;
            List<OrderedText> renderLines = new ArrayList<>(wrappedLines.size() + thinkingLines.size() + (hasActiveToolLine ? 1 : 0));
            renderLines.addAll(wrappedLines);
            if (hasActiveToolLine) {
                renderLines.add(Text.literal(activeToolLine).asOrderedText());
            }
            renderLines.addAll(thinkingLines);
            if (!renderLines.isEmpty()) {
                int lastLineIndex = renderLines.size() - 1;
                OrderedText lastLine = renderLines.get(lastLineIndex);
                cursorY += lastLineIndex * lineHeight;
                cursorX += renderer.getWidth(lastLine);
            }
            if (cursorY + lineHeight >= contentTop && cursorY <= contentBottom) {
                context.drawTextWithShadow(renderer, "|", cursorX, cursorY, 0xFFFFFFFF);
            }
        }
        context.disableScissor();
    }

    private static void renderSessionsContent(
            DrawContext context,
            TextRenderer renderer,
            int contentLeft,
            int contentTop,
            int contentRight,
            int contentBottom,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        sessionRows.clear();

        int width = Math.max(10, contentRight - contentLeft);
        context.fill(contentLeft, contentTop, contentRight, contentTop + 16, 0x70323D4A);
        context.drawTextWithShadow(renderer, "Select Session", contentLeft + 4, contentTop + 4, 0xFFE7EEF8);
        int newButtonRight = contentRight - 4;
        int newButtonLeft = newButtonRight - SESSIONS_NEW_BUTTON_WIDTH;
        int newButtonTop = contentTop + 1;
        int newButtonBottom = newButtonTop + SESSIONS_NEW_BUTTON_HEIGHT;
        boolean newButtonHovered = interactiveMode
                && mouseX >= newButtonLeft && mouseX <= newButtonRight
                && mouseY >= newButtonTop && mouseY <= newButtonBottom;
        int newButtonFill = newButtonHovered ? 0xFF4A6882 : 0xFF38536A;
        context.fill(newButtonLeft, newButtonTop, newButtonRight, newButtonBottom, newButtonFill);
        context.drawTextWithShadow(renderer, "New Session", newButtonLeft + 5, newButtonTop + 3, 0xFFF2F7FF);
        newSessionButton.set(newButtonLeft, newButtonTop, newButtonRight, newButtonBottom);
        int top = contentTop + 18;
        int availableHeight = Math.max(1, contentBottom - top);
        int rowSpan = SESSIONS_ROW_HEIGHT + SESSIONS_ROW_GAP;
        int totalHeight = Math.max(0, (sessionItems.size() * rowSpan) - SESSIONS_ROW_GAP);
        double maxScroll = Math.max(0.0, totalHeight - availableHeight);
        sessionsScrollY = MathHelper.clamp(sessionsScrollY, 0.0, maxScroll);

        if (sessionItems.isEmpty()) {
            context.drawTextWithShadow(renderer, "No sessions yet.", contentLeft + 4, top + 4, 0xFFA9B6C6);
            return;
        }

        int rowWidth = Math.max(20, width - 2);
        context.enableScissor(contentLeft, top, contentRight, contentBottom);
        for (int i = 0; i < sessionItems.size(); i++) {
            SessionOverlayPayload.SessionItem item = sessionItems.get(i);
            int rowTop = top - (int) Math.round(sessionsScrollY) + (i * rowSpan);
            int rowBottom = rowTop + SESSIONS_ROW_HEIGHT;
            if (rowBottom < top || rowTop > contentBottom) {
                continue;
            }
            boolean active = item.active() || item.id().equalsIgnoreCase(activeSessionId);
            boolean hovered = interactiveMode
                    && mouseX >= contentLeft && mouseX <= contentLeft + rowWidth
                    && mouseY >= rowTop && mouseY <= rowBottom;
            int fill = active
                    ? (hovered ? 0xC2516D88 : 0xB3435D76)
                    : (hovered ? 0xA03B4D60 : 0x902D3C4D);
            context.fill(contentLeft, rowTop, contentLeft + rowWidth, rowBottom, fill);
            context.fill(contentLeft, rowTop, contentLeft + rowWidth, rowTop + 1, 0x80A9BFD9);
            context.fill(contentLeft, rowBottom - 1, contentLeft + rowWidth, rowBottom, 0x60506472);

            String token = item.token() == null || item.token().isBlank() ? item.id() : item.token();
            String title = item.title() == null || item.title().isBlank() ? "Untitled" : item.title();
            String line1 = token + " - " + title;
            context.drawTextWithShadow(renderer, renderer.trimToWidth(line1, rowWidth - 8), contentLeft + 4, rowTop + 4, 0xFFF2F7FF);

            String updatedLabel = formatSessionUpdatedAt(item.updatedAtEpochMillis());
            context.drawTextWithShadow(renderer, renderer.trimToWidth(updatedLabel, rowWidth - 8), contentLeft + 4, rowTop + 14, 0xFFA8B9CC);

            sessionRows.add(new SessionRowBounds(contentLeft, rowTop, contentLeft + rowWidth, rowBottom, item.id()));
        }
        context.disableScissor();
    }

    private static void renderAssetsContent(
            DrawContext context,
            TextRenderer renderer,
            int contentLeft,
            int contentTop,
            int contentRight,
            int contentBottom,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        assetRows.clear();
        assetFilterButtons.clear();

        List<AssetsOverlayPayload.AssetItem> visibleAssets = filteredAssets();
        int width = Math.max(10, contentRight - contentLeft);
        context.fill(contentLeft, contentTop, contentRight, contentTop + 16, 0x70323D4A);
        String title = "Assets (" + visibleAssets.size() + "/" + assetItems.size() + ")";
        context.drawTextWithShadow(renderer, renderer.trimToWidth(title, width - 8), contentLeft + 4, contentTop + 4, 0xFFE7EEF8);

        int filterTop = contentTop + 18;
        int filterBottom = filterTop + ASSETS_FILTER_BUTTON_HEIGHT;
        renderAssetFilterButtons(context, renderer, contentLeft, contentRight, filterTop, filterBottom, mouseX, mouseY, interactiveMode);

        int listTop = filterBottom + 2;
        int actionTop = Math.max(listTop + 22, contentBottom - ASSETS_ACTION_BAR_HEIGHT);
        int listBottom = Math.max(listTop, actionTop - 4);

        int rowSpan = ASSETS_ROW_HEIGHT + ASSETS_ROW_GAP;
        int totalHeight = Math.max(0, (visibleAssets.size() * rowSpan) - ASSETS_ROW_GAP);
        int availableHeight = Math.max(1, listBottom - listTop);
        double maxScroll = Math.max(0.0, totalHeight - availableHeight);
        assetsScrollY = MathHelper.clamp(assetsScrollY, 0.0, maxScroll);

        if (visibleAssets.isEmpty()) {
            selectedAssetId = "";
            String emptyText = activeAssetFilter == AssetFilter.ALL
                    ? "No assets tracked yet."
                    : "No assets in this category.";
            context.drawTextWithShadow(renderer, emptyText, contentLeft + 4, listTop + 4, 0xFFA9B6C6);
        } else if (selectedAssetId.isBlank() || !isSelectedAssetVisibleWithFilter(selectedAssetId)) {
            selectedAssetId = visibleAssets.get(0).id();
        }

        context.enableScissor(contentLeft, listTop, contentRight, listBottom);
        int rowWidth = Math.max(20, width - 2);
        for (int i = 0; i < visibleAssets.size(); i++) {
            AssetsOverlayPayload.AssetItem item = visibleAssets.get(i);
            int rowTop = listTop - (int) Math.round(assetsScrollY) + (i * rowSpan);
            int rowBottom = rowTop + ASSETS_ROW_HEIGHT;
            if (rowBottom < listTop || rowTop > listBottom) {
                continue;
            }
            boolean selected = item.id().equalsIgnoreCase(selectedAssetId);
            boolean hovered = interactiveMode
                    && mouseX >= contentLeft && mouseX <= contentLeft + rowWidth
                    && mouseY >= rowTop && mouseY <= rowBottom;
            int fill = selected
                    ? (hovered ? 0xC2546A83 : 0xB1455D74)
                    : (hovered ? 0xA03B4D60 : 0x902D3C4D);
            context.fill(contentLeft, rowTop, contentLeft + rowWidth, rowBottom, fill);
            context.fill(contentLeft, rowTop, contentLeft + rowWidth, rowTop + 1, 0x80A9BFD9);
            context.fill(contentLeft, rowBottom - 1, contentLeft + rowWidth, rowBottom, 0x60506472);

            String line1 = categoryDisplayName(item.category()) + " - " + (item.name().isBlank() ? item.id() : item.name());
            String line2 = item.summary();
            if (line2 == null || line2.isBlank()) {
                line2 = item.id();
            } else {
                line2 = item.id() + " - " + line2;
            }
            context.drawTextWithShadow(renderer, renderer.trimToWidth(line1, rowWidth - 8), contentLeft + 4, rowTop + 4, 0xFFF2F7FF);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(line2, rowWidth - 8), contentLeft + 4, rowTop + 15, 0xFFA8B9CC);
            assetRows.add(new AssetRowBounds(contentLeft, rowTop, contentLeft + rowWidth, rowBottom, item.id()));
        }
        context.disableScissor();

        AssetsOverlayPayload.AssetItem selected = selectedVisibleAsset();
        boolean canTeleport = selected != null && isEntityAsset(selected) && !selected.entityUuid().isBlank();
        boolean canGive = selected != null && canGiveAsset(selected);
        int buttonTop = actionTop + 2;
        int buttonBottom = buttonTop + ASSETS_ACTION_BUTTON_HEIGHT;
        int sectionWidth = Math.max(20, contentRight - contentLeft);
        int buttonWidth = Math.max(24, (sectionWidth - (ASSETS_ACTION_BUTTON_GAP * 3)) / 4);
        int left = contentLeft;
        left = renderAssetActionButton(context, renderer, left, buttonTop, buttonWidth, buttonBottom, mouseX, mouseY, interactiveMode, "Teleport", AssetAction.TELEPORT, canTeleport);
        left += ASSETS_ACTION_BUTTON_GAP;
        left = renderAssetActionButton(context, renderer, left, buttonTop, buttonWidth, buttonBottom, mouseX, mouseY, interactiveMode, "Give", AssetAction.GIVE, canGive);
        left += ASSETS_ACTION_BUTTON_GAP;
        left = renderAssetActionButton(context, renderer, left, buttonTop, buttonWidth, buttonBottom, mouseX, mouseY, interactiveMode, "Modify", AssetAction.MODIFY, selected != null);
        left += ASSETS_ACTION_BUTTON_GAP;
        renderAssetActionButton(context, renderer, left, buttonTop, buttonWidth, buttonBottom, mouseX, mouseY, interactiveMode, "Delete", AssetAction.DELETE, selected != null);

        String hint = selected == null ? "Select an asset to use actions." : ("Selected: " + selected.id());
        context.drawTextWithShadow(renderer, renderer.trimToWidth(hint, width - 4), contentLeft + 2, buttonBottom + 3, 0xFF96A7BB);
    }

    private static void renderAssetFilterButtons(
            DrawContext context,
            TextRenderer renderer,
            int left,
            int right,
            int top,
            int bottom,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        AssetFilter[] filters = AssetFilter.values();
        if (filters.length == 0) {
            return;
        }
        int totalGap = Math.max(0, (filters.length - 1) * ASSETS_FILTER_BUTTON_GAP);
        int available = Math.max(1, (right - left) - totalGap);
        int buttonWidth = Math.max(18, available / filters.length);
        int x = left;
        for (int i = 0; i < filters.length; i++) {
            AssetFilter filter = filters[i];
            String label = filter.label;
            int width = buttonWidth;
            if (i == filters.length - 1) {
                width = Math.max(width, right - x);
            }
            int buttonRight = Math.min(right, x + width);
            if (buttonRight <= x) {
                break;
            }
            boolean active = filter == activeAssetFilter;
            boolean hovered = interactiveMode
                    && mouseX >= x && mouseX <= buttonRight
                    && mouseY >= top && mouseY <= bottom;
            int fill = active
                    ? (hovered ? 0xFF527293 : 0xFF44627E)
                    : (hovered ? 0xFF35495E : 0xFF293846);
            context.fill(x, top, buttonRight, bottom, fill);
            context.fill(x, top, buttonRight, top + 1, active ? 0xFFB1CAE3 : 0xFF6D8196);
            context.fill(x, bottom - 1, buttonRight, bottom, 0xFF1C2734);
            context.drawTextWithShadow(renderer, renderer.trimToWidth(label, Math.max(1, width - 6)), x + 3, top + 3, active ? 0xFFF4F9FF : 0xFFD0DEED);
            assetFilterButtons.add(new AssetFilterButtonBounds(x, top, buttonRight, bottom, filter));
            x = buttonRight + ASSETS_FILTER_BUTTON_GAP;
            if (x >= right) {
                break;
            }
        }
    }

    private static int renderAssetActionButton(
            DrawContext context,
            TextRenderer renderer,
            int left,
            int top,
            int width,
            int bottom,
            int mouseX,
            int mouseY,
            boolean interactiveMode,
            String label,
            AssetAction action,
            boolean enabled
    ) {
        int right = left + width;
        boolean hovered = enabled
                && interactiveMode
                && mouseX >= left && mouseX <= right
                && mouseY >= top && mouseY <= bottom;
        int fill = enabled
                ? (hovered ? 0xFF476582 : 0xFF37516A)
                : 0xFF2A3442;
        context.fill(left, top, right, bottom, fill);
        context.fill(left, top, right, top + 1, enabled ? 0xFF8CA9C6 : 0xFF5A6470);
        context.fill(left, bottom - 1, right, bottom, 0xFF1D2B3A);
        context.drawTextWithShadow(renderer, renderer.trimToWidth(label, width - 6), left + 3, top + 4, enabled ? 0xFFF2F7FF : 0xFF9BA8B7);

        switch (action) {
            case TELEPORT -> assetTeleportButton.set(left, top, right, bottom);
            case GIVE -> assetGiveButton.set(left, top, right, bottom);
            case MODIFY -> assetModifyButton.set(left, top, right, bottom);
            case DELETE -> assetDeleteButton.set(left, top, right, bottom);
        }
        if (!enabled) {
            switch (action) {
                case TELEPORT -> assetTeleportButton.clear();
                case GIVE -> assetGiveButton.clear();
                case MODIFY -> assetModifyButton.clear();
                case DELETE -> assetDeleteButton.clear();
            }
        }
        return right;
    }

    private static void renderMenuDropdown(
            DrawContext context,
            TextRenderer renderer,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        menuItems.clear();
        int left = menuButtonLeft() - (MENU_WIDTH - HEADER_BUTTON_SIZE);
        int top = panelY + HEADER_HEIGHT + 2;
        int right = left + MENU_WIDTH;
        int bottom = top + (menuItemCount() * MENU_ITEM_HEIGHT);

        context.fill(left, top, right, bottom, 0xFF1B2430);
        context.fill(left, top, right, top + 1, 0xFF8FA6BE);
        context.fill(left, bottom - 1, right, bottom, 0xFF8FA6BE);
        context.fill(left, top, left + 1, bottom, 0xFF8FA6BE);
        context.fill(right - 1, top, right, bottom, 0xFF8FA6BE);

        drawMenuItem(context, renderer, mouseX, mouseY, interactiveMode, left, top, "Config", MenuAction.CONFIG);
        drawMenuItem(context, renderer, mouseX, mouseY, interactiveMode, left, top + MENU_ITEM_HEIGHT, "Sessions", MenuAction.SESSIONS);
        drawMenuItem(context, renderer, mouseX, mouseY, interactiveMode, left, top + MENU_ITEM_HEIGHT * 2, "Assets", MenuAction.ASSETS);
        drawMenuItem(context, renderer, mouseX, mouseY, interactiveMode, left, top + MENU_ITEM_HEIGHT * 3, "Persona", MenuAction.PERSONA);
    }

    private static void drawMenuItem(
            DrawContext context,
            TextRenderer renderer,
            int mouseX,
            int mouseY,
            boolean interactiveMode,
            int left,
            int top,
            String label,
            MenuAction action
    ) {
        int right = left + MENU_WIDTH;
        int bottom = top + MENU_ITEM_HEIGHT;
        boolean hovered = interactiveMode
                && mouseX >= left && mouseX <= right
                && mouseY >= top && mouseY <= bottom;
        int fill = hovered ? 0xFF415A74 : 0xFF2A3645;
        context.fill(left + 1, top, right - 1, bottom, fill);
        context.fill(left + 1, bottom - 1, right - 1, bottom, 0xFF1C2734);
        String text = label;
        if (action == MenuAction.PERSONA && !activePersona.isBlank()) {
            text = "Persona (" + activePersona + ")";
        }
        context.drawTextWithShadow(renderer, renderer.trimToWidth(text, MENU_WIDTH - 8), left + 4, top + 4, 0xFFF0F6FF);
        menuItems.add(new MenuItemBounds(left, top, right, bottom, action));
    }

    private static int menuItemCount() {
        return 4;
    }

    private static boolean shouldRenderInputBar() {
        return mode == OverlayMode.RESPONSE && !minimized;
    }

    private static void renderInputBar(
            DrawContext context,
            TextRenderer renderer,
            int mouseX,
            int mouseY,
            boolean interactiveMode
    ) {
        int barTop = panelY + panelHeight - CONTENT_PADDING - INPUT_HEIGHT;
        int barBottom = barTop + INPUT_HEIGHT;
        int sendRight = panelX + panelWidth - CONTENT_PADDING;
        int sendLeft = sendRight - INPUT_SEND_BUTTON_WIDTH;
        int fieldLeft = panelX + CONTENT_PADDING;
        int fieldRight = Math.max(fieldLeft + 12, sendLeft - 4);

        context.fill(fieldLeft, barTop, fieldRight, barBottom, 0xCC18212D);
        int fieldBorder = inputFocused ? 0xFFA0C5E9 : 0xFF5A6B80;
        context.fill(fieldLeft, barTop, fieldRight, barTop + 1, fieldBorder);
        context.fill(fieldLeft, barBottom - 1, fieldRight, barBottom, fieldBorder);
        context.fill(fieldLeft, barTop, fieldLeft + 1, barBottom, fieldBorder);
        context.fill(fieldRight - 1, barTop, fieldRight, barBottom, fieldBorder);

        boolean sendHovered = interactiveMode
                && mouseX >= sendLeft && mouseX <= sendRight
                && mouseY >= barTop && mouseY <= barBottom;
        boolean stopMode = generating;
        int sendFill = stopMode
                ? (sendHovered ? 0xFF6B4646 : 0xFF533737)
                : (sendHovered ? 0xFF45647F : 0xFF344C63);
        context.fill(sendLeft, barTop, sendRight, barBottom, sendFill);
        context.fill(sendLeft, barTop, sendRight, barTop + 1, 0xFF86A6C6);
        context.fill(sendLeft, barBottom - 1, sendRight, barBottom, 0xFF1D2B3A);
        if (stopMode) {
            int iconSize = Math.max(6, Math.min(INPUT_HEIGHT - 6, INPUT_SEND_BUTTON_WIDTH - 16));
            int cx = sendLeft + ((INPUT_SEND_BUTTON_WIDTH - iconSize) / 2);
            int cy = barTop + ((INPUT_HEIGHT - iconSize) / 2);
            drawCircle(context, cx + (iconSize / 2), cy + (iconSize / 2), Math.max(3, iconSize / 2), 0xFFEADDDD);
            int stopPad = Math.max(1, iconSize / 4);
            context.fill(cx + stopPad, cy + stopPad, cx + iconSize - stopPad, cy + iconSize - stopPad, 0xFF5A3131);
        } else {
            context.drawTextWithShadow(renderer, "Send", sendLeft + 8, barTop + 5, 0xFFF2F7FF);
        }

        int textAreaWidth = Math.max(8, fieldRight - fieldLeft - (INPUT_TEXT_PADDING * 2));
        String draft = currentInputDraft();
        normalizeInputCursor();
        if (draft.isBlank()) {
            context.drawTextWithShadow(renderer, "Ask MineClawd...", fieldLeft + INPUT_TEXT_PADDING, barTop + 5, 0xFF8697AA);
        } else {
            InputRenderWindow window = computeInputRenderWindow(renderer, draft, textAreaWidth);
            String visible = window.visible;
            int renderBaseX = fieldLeft + INPUT_TEXT_PADDING;
            int selectionStart = Math.min(inputCursorIndex, inputSelectionIndex);
            int selectionEnd = Math.max(inputCursorIndex, inputSelectionIndex);
            context.enableScissor(fieldLeft + 1, barTop + 1, fieldRight - 1, barBottom - 1);
            if (selectionStart != selectionEnd) {
                int visibleStart = Math.max(selectionStart, window.startIndex);
                int visibleEnd = Math.min(selectionEnd, window.endIndex);
                if (visibleEnd > visibleStart) {
                    int highlightLeft = renderBaseX + renderer.getWidth(draft.substring(window.startIndex, visibleStart));
                    int highlightRight = renderBaseX + renderer.getWidth(draft.substring(window.startIndex, visibleEnd));
                    context.fill(highlightLeft, barTop + 2, highlightRight, barBottom - 2, 0x885988B7);
                }
            }
            context.drawTextWithShadow(renderer, visible, fieldLeft + INPUT_TEXT_PADDING, barTop + 5, 0xFFE8F1FB);
            if (inputFocused && ((System.currentTimeMillis() / CURSOR_BLINK_MS) % 2L == 0L)) {
                int caretIndex = MathHelper.clamp(inputCursorIndex, window.startIndex, window.endIndex);
                int cursorX = renderBaseX + renderer.getWidth(draft.substring(window.startIndex, caretIndex));
                context.drawTextWithShadow(renderer, "|", cursorX, barTop + 5, 0xFFF6FBFF);
            }
            context.disableScissor();
        }

        inputFieldBounds.set(fieldLeft, barTop, fieldRight, barBottom);
        inputSendBounds.set(sendLeft, barTop, sendRight, barBottom);
    }

    private static boolean handleInputClick(MinecraftClient client, double mouseX, double mouseY) {
        if (!shouldRenderInputBar()) {
            inputFocused = false;
            inputDragSelecting = false;
            return false;
        }
        if (inputFieldBounds.contains(mouseX, mouseY)) {
            inputFocused = true;
            inputDragSelecting = true;
            setInputCursorFromMouse(client, mouseX, false);
            return true;
        }
        if (inputSendBounds.contains(mouseX, mouseY)) {
            inputFocused = true;
            inputDragSelecting = false;
            if (generating) {
                requestStopGeneration(client);
            } else {
                submitInputDraft(client);
            }
            return true;
        }
        inputDragSelecting = false;
        return false;
    }

    private static int estimateQuestionBlockHeight(TextRenderer renderer, int width) {
        if (pendingQuestion == null || renderer == null) {
            return 0;
        }
        int textWidth = Math.max(20, width - 16);
        int lineCount = renderer.wrapLines(Text.literal(pendingQuestion.question()), textWidth).size();
        lineCount = Math.max(1, Math.min(QUESTION_MAX_LINES, lineCount));
        int lineHeight = renderer.fontHeight + 1;
        int buttonCount = Math.max(1, pendingQuestion.options().size() + 1);
        int buttonsHeight = buttonCount * QUESTION_BUTTON_HEIGHT + Math.max(0, buttonCount - 1) * QUESTION_BUTTON_GAP;
        return 6 + renderer.fontHeight + 3 + (lineCount * lineHeight) + 4 + buttonsHeight + 6;
    }

    private static void handleQuestionButtonClick(MinecraftClient client, QuestionButtonBounds button) {
        if (pendingQuestion == null || button == null) {
            return;
        }
        if (button.skip) {
            appendQuestionAnswerToHistory(buildQuestionAnswerSummary("Skipped this question."));
            submitQuestion(client, new QuestionResponsePayload(
                    pendingQuestion.questionId(),
                    QuestionResponsePayload.Type.SKIP,
                    -1,
                    "User skipped."
            ));
            return;
        }
        int optionIndex = button.optionIndex;
        List<String> options = pendingQuestion.options();
        if (optionIndex < 0 || optionIndex >= options.size()) {
            return;
        }
        String selected = options.get(optionIndex);
        appendQuestionAnswerToHistory(buildQuestionAnswerSummary("Selected option " + (optionIndex + 1) + ": " + selected));
        submitQuestion(client, new QuestionResponsePayload(
                pendingQuestion.questionId(),
                QuestionResponsePayload.Type.OPTION,
                optionIndex,
                selected
        ));
    }

    private static String buildQuestionAnswerSummary(String answerLine) {
        String question = pendingQuestion == null ? "" : pendingQuestion.question();
        String trimmedQuestion = question == null ? "" : question.trim();
        if (trimmedQuestion.isBlank()) {
            return answerLine;
        }
        return "AskUserQuestion: " + trimmedQuestion + "\n" + answerLine;
    }

    private static void appendQuestionAnswerToHistory(String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        if (content.length() > 0) {
            content.append("\n\n");
        }
        content.append(markUserLines(answer.trim()));
        parsedDirty = true;
        wrappedDirty = true;
        wrappedWidth = -1;
        followTail = true;
    }

    private static void submitQuestion(MinecraftClient client, QuestionResponsePayload payload) {
        if (pendingQuestion == null || payload == null) {
            return;
        }
        pendingQuestion = null;
        pendingQuestionDeadlineEpochMillis = 0L;
        questionButtons.clear();
        if (client == null || client.getNetworkHandler() == null) {
            return;
        }
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        buffer.writeString(payload.toJson());
        NetworkManager.sendToServer(MineClawdNetworking.QUESTION_RESPONSE, buffer);
    }

    private static long remainingQuestionSeconds() {
        if (pendingQuestion == null) {
            return 0L;
        }
        long remainingMillis = pendingQuestionDeadlineEpochMillis - System.currentTimeMillis();
        return Math.max(0L, (remainingMillis + 999L) / 1000L);
    }

    private static QuestionButtonBounds findQuestionButton(double x, double y) {
        for (QuestionButtonBounds button : questionButtons) {
            if (button.contains(x, y)) {
                return button;
            }
        }
        return null;
    }

    private static void renderMinimized(DrawContext context, TextRenderer renderer) {
        int centerX = panelX + ORB_RADIUS;
        int centerY = panelY + ORB_RADIUS;
        int color = generating ? 0xCC3A5F7F : 0xCC33424F;
        drawCircle(context, centerX, centerY, ORB_RADIUS, color);
        drawCircle(context, centerX, centerY, ORB_RADIUS - 1, 0x66384657);
        int iconSize = (ORB_RADIUS * 2) - 8;
        int iconLeft = centerX - (iconSize / 2) + ORB_ICON_OFFSET_X;
        int iconTop = centerY - (iconSize / 2) + ORB_ICON_OFFSET_Y;
        context.drawTexture(
                ORB_ICON_TEXTURE,
                iconLeft,
                iconTop,
                iconSize,
                iconSize,
                0.0F,
                0.0F,
                ORB_ICON_TEXTURE_SIZE,
                ORB_ICON_TEXTURE_SIZE,
                ORB_ICON_TEXTURE_SIZE,
                ORB_ICON_TEXTURE_SIZE
        );
        if (generating && ((System.currentTimeMillis() / CURSOR_BLINK_MS) % 2L == 0L)) {
            drawCircle(context, centerX + ORB_RADIUS - 4, centerY + ORB_RADIUS - 4, 2, 0xFFF5FCFF);
        }
    }

    private static boolean shouldShowThinkingPlaceholder() {
        return generating && awaitingFirstAssistantDelta;
    }

    private static String animatedThinkingText() {
        long frame = (System.currentTimeMillis() / THINKING_DOTS_FRAME_MS) % 3L;
        return switch ((int) frame) {
            case 0 -> "Thinking.";
            case 1 -> "Thinking..";
            default -> "Thinking...";
        };
    }

    private static List<OrderedText> buildThinkingLines(TextRenderer renderer, int contentWidth) {
        if (!shouldShowThinkingPlaceholder() || renderer == null || contentWidth <= 0) {
            return List.of();
        }
        Text line = withAgentHighlight(Text.literal(animatedThinkingText()), true);
        List<OrderedText> lines = renderer.wrapLines(line, contentWidth);
        if (lines == null || lines.isEmpty()) {
            return List.of(line.asOrderedText());
        }
        return lines;
    }

    private static void drawAnimatedToolStatus(
            DrawContext context,
            TextRenderer renderer,
            String text,
            int left,
            int top,
            int idleColor
    ) {
        if (context == null || renderer == null || text == null || text.isBlank()) {
            return;
        }
        String value = text;
        int length = value.length();
        int window = Math.max(4, Math.min(TOOL_STATUS_WINDOW_CHARS, length));
        int positions = Math.max(1, (length - window) + 1);
        long cycleMoveMs = positions * TOOL_STATUS_ANIM_STEP_MS;
        long cycleMs = cycleMoveMs + TOOL_STATUS_ANIM_PAUSE_MS;
        if (toolStatusAnimStartEpochMs <= 0L) {
            toolStatusAnimStartEpochMs = System.currentTimeMillis();
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - toolStatusAnimStartEpochMs);
        long phase = cycleMs <= 0L ? 0L : (elapsed % cycleMs);
        int highlightStart = phase >= cycleMoveMs
                ? (positions - 1)
                : (int) Math.min(positions - 1, phase / TOOL_STATUS_ANIM_STEP_MS);
        int highlightEnd = Math.min(length, highlightStart + window);

        String prefix = value.substring(0, highlightStart);
        String highlight = value.substring(highlightStart, highlightEnd);
        String suffix = value.substring(highlightEnd);

        int cursorX = left;
        if (!prefix.isEmpty()) {
            context.drawTextWithShadow(renderer, prefix, cursorX, top, idleColor);
            cursorX += renderer.getWidth(prefix);
        }
        if (!highlight.isEmpty()) {
            context.drawTextWithShadow(renderer, highlight, cursorX, top, 0xFFF2F6FF);
            cursorX += renderer.getWidth(highlight);
        }
        if (!suffix.isEmpty()) {
            context.drawTextWithShadow(renderer, suffix, cursorX, top, idleColor);
        }
    }

    private static void renderSimpleTooltip(
            DrawContext context,
            TextRenderer renderer,
            MinecraftClient client,
            int mouseX,
            int mouseY,
            String text
    ) {
        if (context == null || renderer == null || client == null || text == null || text.isBlank()) {
            return;
        }
        List<OrderedText> lines = renderer.wrapLines(Text.literal(text), TOOL_STATUS_TOOLTIP_MAX_WIDTH);
        if (lines == null || lines.isEmpty()) {
            lines = List.of(Text.literal(text).asOrderedText());
        }

        int maxWidth = 0;
        for (OrderedText line : lines) {
            if (line == null) {
                continue;
            }
            maxWidth = Math.max(maxWidth, renderer.getWidth(line));
        }
        int padding = 4;
        int lineHeight = renderer.fontHeight + 1;
        int boxWidth = maxWidth + (padding * 2);
        int boxHeight = (lines.size() * lineHeight) + (padding * 2);

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int x = mouseX + 10;
        int y = mouseY + 10;
        if (x + boxWidth > screenWidth - 6) {
            x = Math.max(6, mouseX - boxWidth - 10);
        }
        if (y + boxHeight > screenHeight - 6) {
            y = Math.max(6, mouseY - boxHeight - 10);
        }

        context.fill(x, y, x + boxWidth, y + boxHeight, 0xF018202B);
        context.fill(x, y, x + boxWidth, y + 1, 0xFF9BB2CA);
        context.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFF9BB2CA);
        context.fill(x, y, x + 1, y + boxHeight, 0xFF9BB2CA);
        context.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, 0xFF9BB2CA);

        int lineY = y + padding;
        for (OrderedText line : lines) {
            context.drawTextWithShadow(renderer, line, x + padding, lineY, 0xFFF3F7FF);
            lineY += lineHeight;
        }
    }

    private static void clearToolStatusState() {
        activeToolStatusText = "";
        activeToolStatusHover = "";
        toolStatusAnimStartEpochMs = 0L;
        toolStatusShownEpochMs = 0L;
        toolStatusPendingClearEpochMs = 0L;
        toolStatusBounds.clear();
    }

    private static void requestToolStatusClear() {
        if (activeToolStatusText.isBlank()) {
            clearToolStatusState();
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = toolStatusShownEpochMs <= 0L ? TOOL_STATUS_MIN_VISIBLE_MS : now - toolStatusShownEpochMs;
        if (elapsed >= TOOL_STATUS_MIN_VISIBLE_MS) {
            clearToolStatusState();
            return;
        }
        toolStatusPendingClearEpochMs = now + (TOOL_STATUS_MIN_VISIBLE_MS - elapsed);
    }

    private static void appendText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        followTail = followTail || isNearTail();
        content.append(text);
        parsedDirty = true;
        wrappedDirty = true;
    }

    private static void appendToolStatusCompletion(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        followTail = followTail || isNearTail();
        if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }
        content.append(markToolLines(text.trim())).append('\n');
        parsedDirty = true;
        wrappedDirty = true;
    }

    private static void applyHistory(List<SessionOverlayPayload.HistoryItem> history) {
        clearResponseContent();
        awaitingFirstAssistantDelta = false;
        if (history == null || history.isEmpty()) {
            return;
        }
        for (SessionOverlayPayload.HistoryItem item : history) {
            if (item == null || item.content() == null || item.content().isBlank()) {
                continue;
            }
            if (content.length() > 0) {
                content.append("\n\n");
            }
            if (item.assistant()) {
                content.append(item.content().trim());
            } else {
                content.append(markUserLines(item.content().trim()));
            }
        }
        parsedDirty = true;
        wrappedDirty = true;
    }

    private static void clearResponseContent() {
        content.setLength(0);
        wrappedLines.clear();
        parsedText = Text.empty();
        parsedDirty = true;
        wrappedDirty = true;
        wrappedWidth = -1;
        scrollY = 0.0;
        followTail = true;
        awaitingFirstAssistantDelta = false;
    }

    private static void rebuildWrappedLines(TextRenderer renderer, int maxWidth, MinecraftClient client) {
        if (parsedDirty) {
            parsedText = parseMineDown(client, content.toString().replace('\r', '\n'));
            parsedDirty = false;
            wrappedDirty = true;
        }
        if (!wrappedDirty && wrappedWidth == maxWidth) {
            return;
        }

        wrappedDirty = false;
        wrappedWidth = maxWidth;
        wrappedLines.clear();
        if (maxWidth <= 4) {
            wrappedLines.add(parsedText.asOrderedText());
            return;
        }
        wrappedLines.addAll(renderer.wrapLines(parsedText, maxWidth));
        if (wrappedLines.isEmpty()) {
            wrappedLines.add(Text.empty().asOrderedText());
        }
    }

    private static Text parseMineDown(MinecraftClient client, String markdown) {
        String normalized = normalizeMineDownActions(markdown);
        String input = normalized == null ? "" : normalized;
        if (input.isBlank()) {
            return Text.empty();
        }
        String[] lines = input.split("\\n", -1);
        MutableText combined = Text.empty();
        boolean nextAgentLineNeedsPrefix = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i];
            boolean userLine = line.startsWith(USER_MARKER);
            boolean toolLine = line.startsWith(TOOL_MARKER);
            String raw;
            if (userLine) {
                raw = line.substring(USER_MARKER.length());
            } else if (toolLine) {
                raw = line.substring(TOOL_MARKER.length());
            } else {
                raw = line;
            }
            Text parsed = parseSingleMineDownLine(client, raw);
            if (userLine) {
                parsed = withUserHighlight(parsed);
                nextAgentLineNeedsPrefix = true;
            } else if (toolLine) {
                parsed = withToolHighlight(parsed);
                nextAgentLineNeedsPrefix = true;
            } else if (!raw.isBlank()) {
                parsed = withAgentHighlight(parsed, nextAgentLineNeedsPrefix);
                nextAgentLineNeedsPrefix = false;
            }
            combined.append(parsed == null ? Text.literal(raw) : parsed);
            if (i < lines.length - 1) {
                combined.append(Text.literal("\n"));
            }
        }
        return combined;
    }

    private static Text parseMineDownPerLine(MinecraftClient client, String input) {
        if (input == null || input.isBlank()) {
            return Text.empty();
        }
        String[] lines = input.split("\\n", -1);
        MutableText combined = Text.empty();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Text parsed = parseSingleMineDownLine(client, line);
            combined.append(parsed == null ? Text.literal(line) : parsed);
            if (i < lines.length - 1) {
                combined.append(Text.literal("\n"));
            }
        }
        return combined;
    }

    private static Text parseSingleMineDownLine(MinecraftClient client, String line) {
        if (line == null || line.isBlank()) {
            return Text.empty();
        }
        try {
            Component parsed = MineDown.parse(line);
            String json = ADVENTURE_GSON.serialize(parsed);
            Text text = deserializeStyledText(client, json);
            if (text != null) {
                return text;
            }
        } catch (Exception ignored) {
        }
        return Text.literal(line);
    }

    private static Text withUserHighlight(Text body) {
        MutableText prefix = Text.literal("You: ")
                .setStyle(net.minecraft.text.Style.EMPTY.withColor(TextColor.fromRgb(USER_PREFIX_COLOR & 0xFFFFFF)).withBold(true));
        MutableText contentText = body == null ? Text.empty() : body.copy();
        if (contentText.getStyle().getColor() == null) {
            contentText.setStyle(contentText.getStyle().withColor(TextColor.fromRgb(USER_TEXT_COLOR & 0xFFFFFF)));
        }
        return Text.empty().append(prefix).append(contentText);
    }

    private static Text withToolHighlight(Text body) {
        MutableText contentText = body == null ? Text.empty() : body.copy();
        if (contentText.getStyle().getColor() == null) {
            contentText.setStyle(contentText.getStyle().withColor(TextColor.fromRgb(TOOL_TEXT_COLOR & 0xFFFFFF)));
        }
        return contentText;
    }

    private static Text withAgentHighlight(Text body, boolean includePrefix) {
        if (body == null || body.getString().isBlank()) {
            return Text.empty();
        }
        MutableText contentText = body.copy();
        if (contentText.getStyle().getColor() == null) {
            contentText.setStyle(contentText.getStyle().withColor(TextColor.fromRgb(AGENT_TEXT_COLOR & 0xFFFFFF)));
        }
        if (!includePrefix) {
            return contentText;
        }
        MutableText prefix = Text.literal("MineClawd: ")
                .setStyle(net.minecraft.text.Style.EMPTY.withColor(TextColor.fromRgb(AGENT_PREFIX_COLOR & 0xFFFFFF)).withBold(true));
        return Text.empty().append(prefix).append(contentText);
    }

    private static String markUserLines(String text) {
        if (text == null || text.isBlank()) {
            return USER_MARKER;
        }
        return USER_MARKER + text.replace("\n", "\n" + USER_MARKER);
    }

    private static String markToolLines(String text) {
        if (text == null || text.isBlank()) {
            return TOOL_MARKER;
        }
        return TOOL_MARKER + text.replace("\n", "\n" + TOOL_MARKER);
    }

    private static String normalizeMineDownActions(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }
        return MINEDOWN_ACTION_COLON_PATTERN.matcher(markdown).replaceAll("($1=");
    }

    private static Text deserializeStyledText(MinecraftClient client, String json) {
        if (client == null || json == null || json.isBlank()) {
            return null;
        }
        try {
            Class<?> serializationClass = Class.forName("net.minecraft.text.Text$Serialization");
            for (Method method : serializationClass.getMethods()) {
                if (!"fromJson".equals(method.getName())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 2) {
                    continue;
                }
                Class<?> firstType = method.getParameterTypes()[0];
                Class<?> secondType = method.getParameterTypes()[1];
                Object registryLookup = resolveRegistryLookup(client, secondType);
                if (registryLookup == null) {
                    continue;
                }
                Object jsonInput = convertJsonInput(json, firstType);
                if (jsonInput == null) {
                    continue;
                }
                Object value = method.invoke(null, jsonInput, registryLookup);
                if (value instanceof Text text) {
                    return text;
                }
            }
        } catch (Exception ignored) {
        }
        Text legacy = parseWithLegacySerializer(json);
        if (legacy != null) {
            return legacy;
        }
        return parseSimpleStyledText(json);
    }

    private static Object convertJsonInput(String json, Class<?> expectedType) {
        if (json == null || expectedType == null) {
            return null;
        }
        if (expectedType == String.class) {
            return json;
        }
        if (JsonElement.class.isAssignableFrom(expectedType)) {
            try {
                return JsonParser.parseString(json);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Text parseWithLegacySerializer(String json) {
        try {
            Class<?> serializerClass = Class.forName("net.minecraft.text.Text$Serializer");
            for (Method method : serializerClass.getMethods()) {
                if (!"fromJson".equals(method.getName())
                        || !Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != String.class) {
                    continue;
                }
                Object value = method.invoke(null, json);
                if (value instanceof Text text) {
                    return text;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Text parseSimpleStyledText(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return parseSimpleStyledElement(element);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Text parseSimpleStyledElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Text.empty();
        }
        if (element.isJsonPrimitive()) {
            return Text.literal(element.getAsString());
        }
        if (element.isJsonArray()) {
            MutableText text = Text.empty();
            for (JsonElement child : element.getAsJsonArray()) {
                text.append(parseSimpleStyledElement(child));
            }
            return text;
        }
        if (!element.isJsonObject()) {
            return Text.literal(element.toString());
        }
        JsonObject object = element.getAsJsonObject();
        MutableText text = Text.empty();
        if (object.has("text") && object.get("text").isJsonPrimitive()) {
            text = Text.literal(object.get("text").getAsString());
        }
        applySimpleStyle(text, object);
        if (object.has("extra") && object.get("extra").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("extra")) {
                text.append(parseSimpleStyledElement(child));
            }
        }
        if (object.has("children") && object.get("children").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("children")) {
                text.append(parseSimpleStyledElement(child));
            }
        }
        return text;
    }

    private static void applySimpleStyle(MutableText text, JsonObject object) {
        if (text == null || object == null) {
            return;
        }
        net.minecraft.text.Style style = text.getStyle();
        if (isStyleFlagEnabled(object, "bold")) {
            style = style.withBold(true);
        }
        if (isStyleFlagEnabled(object, "italic")) {
            style = style.withItalic(true);
        }
        if (isStyleFlagEnabled(object, "underlined")) {
            style = style.withUnderline(true);
        }
        if (isStyleFlagEnabled(object, "strikethrough")) {
            style = style.withStrikethrough(true);
        }
        if (isStyleFlagEnabled(object, "obfuscated")) {
            style = style.withObfuscated(true);
        }
        if (object.has("color")) {
            style = applySimpleColor(style, object.get("color"));
        }
        text.setStyle(style);
    }

    private static boolean isStyleFlagEnabled(JsonObject object, String key) {
        if (object == null || key == null) {
            return false;
        }
        if (isJsonTruthValue(object.get(key))) {
            return true;
        }
        JsonObject decorations = object.has("decorations") && object.get("decorations").isJsonObject()
                ? object.getAsJsonObject("decorations")
                : null;
        if (decorations == null) {
            return false;
        }
        if (isJsonTruthValue(decorations.get(key))) {
            return true;
        }
        if (isJsonTruthValue(decorations.get(key.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        return isJsonTruthValue(decorations.get(key.toUpperCase(Locale.ROOT)));
    }

    private static boolean isJsonTruthValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isString()) {
                return "true".equalsIgnoreCase(element.getAsString());
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt() != 0;
            }
            return false;
        }
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("value")) {
            return isJsonTruthValue(object.get("value"));
        }
        if (object.has("state")) {
            return isJsonTruthValue(object.get("state"));
        }
        if (object.has("enabled")) {
            return isJsonTruthValue(object.get("enabled"));
        }
        return false;
    }

    private static net.minecraft.text.Style applySimpleColor(net.minecraft.text.Style style, JsonElement colorElement) {
        if (style == null || colorElement == null || colorElement.isJsonNull()) {
            return style;
        }
        if (colorElement.isJsonObject()) {
            JsonObject colorObject = colorElement.getAsJsonObject();
            if (colorObject.has("value")) {
                return applySimpleColor(style, colorObject.get("value"));
            }
            if (colorObject.has("hex")) {
                return applySimpleColor(style, colorObject.get("hex"));
            }
            if (colorObject.has("name")) {
                return applySimpleColor(style, colorObject.get("name"));
            }
            return style;
        }
        if (!colorElement.isJsonPrimitive()) {
            return style;
        }
        String rawColor = colorElement.getAsString();
        if (rawColor == null || rawColor.isBlank()) {
            return style;
        }
        Formatting color = Formatting.byName(rawColor.toLowerCase(Locale.ROOT));
        if (color != null) {
            return style.withColor(color);
        }
        if (rawColor.startsWith("#") && rawColor.length() == 7) {
            try {
                int rgb = Integer.parseInt(rawColor.substring(1), 16);
                return style.withColor(TextColor.fromRgb(rgb));
            } catch (NumberFormatException ignored) {
            }
        }
        return style;
    }

    private static boolean isOverlayEnabled() {
        return guiEnabled && assistiveTouchEnabled;
    }

    private static void hideAll() {
        visible = false;
        minimized = false;
        generating = false;
        mode = OverlayMode.RESPONSE;
        menuOpen = false;
        dragging = false;
        resizing = false;
        orbPressed = false;
        orbDragging = false;
        activeRequestId = "";
        activeSessionId = "";
        activePersona = "";
        awaitingFirstAssistantDelta = false;
        clearToolStatusState();
        questionButtons.clear();
        sessionRows.clear();
        sessionItems.clear();
        assetRows.clear();
        assetItems.clear();
        assetFilterButtons.clear();
        newSessionButton.clear();
        assetTeleportButton.clear();
        assetGiveButton.clear();
        assetModifyButton.clear();
        assetDeleteButton.clear();
        inputFieldBounds.clear();
        inputSendBounds.clear();
        inputFocused = false;
        inputDragSelecting = false;
        inputDraft = "";
        inputCursorIndex = 0;
        inputSelectionIndex = 0;
        inputViewStart = 0;
        sessionsScrollY = 0.0;
        assetsScrollY = 0.0;
        selectedAssetId = "";
        activeAssetFilter = AssetFilter.ALL;
        pendingQuestion = null;
        pendingQuestionDeadlineEpochMillis = 0L;
        clearResponseContent();
    }

    private static boolean shouldShowAssistiveOrb(MinecraftClient client) {
        return client != null
                && client.world != null
                && client.player != null
                && client.player.hasPermissionLevel(2);
    }

    private static void ensureIdleOrbVisible(MinecraftClient client) {
        if (client == null || !isOverlayEnabled()) {
            return;
        }
        if (!shouldShowAssistiveOrb(client)) {
            if (!generating && pendingQuestion == null && content.length() == 0) {
                visible = false;
            }
            return;
        }
        if (visible) {
            return;
        }
        visible = true;
        minimized = false;
        mode = OverlayMode.RESPONSE;
        ensureLayout(client);
        minimizeToOrb(client);
    }

    private static void minimizeToOrb(MinecraftClient client) {
        if (client == null) {
            return;
        }
        expandedPanelX = panelX;
        expandedPanelY = panelY;
        menuOpen = false;
        inputFocused = false;
        inputDragSelecting = false;
        dragging = false;
        resizing = false;
        orbPressed = false;
        orbDragging = false;
        minimized = true;
        int centerX = minimizeButtonLeft() + (HEADER_BUTTON_SIZE / 2);
        int centerY = panelY + HEADER_BUTTON_TOP + (HEADER_BUTTON_SIZE / 2);
        panelX = centerX - ORB_RADIUS;
        panelY = centerY - ORB_RADIUS;
        clampPanelToScreen(client);
    }

    private static void restoreExpandedPanelFromOrb(MinecraftClient client) {
        if (client == null) {
            return;
        }
        if (expandedPanelX == Integer.MIN_VALUE || expandedPanelY == Integer.MIN_VALUE) {
            ensureLayout(client);
            return;
        }
        int centerX = panelX + ORB_RADIUS;
        int centerY = panelY + ORB_RADIUS;
        int headerCenterOffsetX = panelWidth - HEADER_BUTTON_RIGHT_MARGIN - HEADER_BUTTON_GAP - (HEADER_BUTTON_SIZE * 3 / 2);
        int headerCenterOffsetY = HEADER_BUTTON_TOP + (HEADER_BUTTON_SIZE / 2);
        panelX = centerX - headerCenterOffsetX;
        panelY = centerY - headerCenterOffsetY;
        clampPanelToScreen(client);
        expandedPanelX = panelX;
        expandedPanelY = panelY;
    }

    private static void setAssistiveTouchEnabled(MinecraftClient client, boolean enabled, boolean notifyServer) {
        assistiveTouchEnabled = enabled;
        if (!enabled) {
            hideAll();
        } else {
            ensureIdleOrbVisible(client == null ? MinecraftClient.getInstance() : client);
        }
        if (notifyServer) {
            sendCommand(client, "mineclawd assistivetouch " + (enabled ? "on" : "off"));
        }
    }

    private static Object resolveRegistryLookup(MinecraftClient client, Class<?> expectedType) {
        if (client == null || expectedType == null) {
            return null;
        }
        Object worldLookup = client.world == null ? null : client.world.getRegistryManager();
        Object resolved = adaptRegistryLookup(worldLookup, expectedType);
        if (resolved != null) {
            return resolved;
        }
        Object handler = client.getNetworkHandler();
        if (handler == null) {
            return null;
        }
        try {
            Method method = handler.getClass().getMethod("getRegistryManager");
            Object networkLookup = method.invoke(handler);
            resolved = adaptRegistryLookup(networkLookup, expectedType);
            if (resolved != null) {
                return resolved;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object adaptRegistryLookup(Object source, Class<?> expectedType) {
        if (source == null || expectedType == null) {
            return null;
        }
        if (expectedType.isInstance(source)) {
            return source;
        }
        for (Method method : source.getClass().getMethods()) {
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (!expectedType.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                Object value = method.invoke(source);
                if (expectedType.isInstance(value)) {
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static double maxResponseScroll(MinecraftClient client) {
        TextRenderer renderer = client == null ? null : client.textRenderer;
        if (renderer == null || minimized || mode != OverlayMode.RESPONSE) {
            return 0.0;
        }
        int lineHeight = renderer.fontHeight + 1;
        int questionOffset = pendingQuestion == null
                ? 0
                : estimateQuestionBlockHeight(renderer, Math.max(10, panelWidth - CONTENT_PADDING * 2)) + QUESTION_BLOCK_GAP;
        int inputOffset = shouldRenderInputBar() ? (INPUT_HEIGHT + INPUT_GAP) : 0;
        int contentHeight = Math.max(1, panelHeight - HEADER_HEIGHT - CONTENT_PADDING - 3 - questionOffset - inputOffset);
        int textHeight = wrappedLines.size() * lineHeight;
        if (activeToolStatusText != null && !activeToolStatusText.isBlank()) {
            textHeight += lineHeight;
        }
        if (shouldShowThinkingPlaceholder()) {
            textHeight += lineHeight;
        }
        return Math.max(0.0, textHeight - contentHeight);
    }

    private static double maxSessionsScroll(MinecraftClient client) {
        TextRenderer renderer = client == null ? null : client.textRenderer;
        if (renderer == null || minimized || mode != OverlayMode.SESSIONS) {
            return 0.0;
        }
        int questionOffset = pendingQuestion == null
                ? 0
                : estimateQuestionBlockHeight(renderer, Math.max(10, panelWidth - CONTENT_PADDING * 2)) + QUESTION_BLOCK_GAP;
        int contentHeight = Math.max(1, panelHeight - HEADER_HEIGHT - CONTENT_PADDING - 3 - questionOffset);
        int listHeight = Math.max(1, contentHeight - 18);
        int rowSpan = SESSIONS_ROW_HEIGHT + SESSIONS_ROW_GAP;
        int totalHeight = Math.max(0, (sessionItems.size() * rowSpan) - SESSIONS_ROW_GAP);
        return Math.max(0.0, totalHeight - listHeight);
    }

    private static double maxAssetsScroll(MinecraftClient client) {
        TextRenderer renderer = client == null ? null : client.textRenderer;
        if (renderer == null || minimized || mode != OverlayMode.ASSETS) {
            return 0.0;
        }
        int questionOffset = pendingQuestion == null
                ? 0
                : estimateQuestionBlockHeight(renderer, Math.max(10, panelWidth - CONTENT_PADDING * 2)) + QUESTION_BLOCK_GAP;
        int contentHeight = Math.max(1, panelHeight - HEADER_HEIGHT - CONTENT_PADDING - 3 - questionOffset);
        int listHeight = Math.max(1, contentHeight - 18 - ASSETS_FILTER_BUTTON_HEIGHT - 2 - ASSETS_ACTION_BAR_HEIGHT);
        int rowSpan = ASSETS_ROW_HEIGHT + ASSETS_ROW_GAP;
        int totalHeight = Math.max(0, (filteredAssets().size() * rowSpan) - ASSETS_ROW_GAP);
        return Math.max(0.0, totalHeight - listHeight);
    }

    private static boolean isNearTail() {
        return (maxResponseScroll(MinecraftClient.getInstance()) - scrollY) <= 2.0;
    }

    private static int minPanelWidth() {
        if (pendingQuestion != null) {
            return Math.max(MIN_WIDTH, QUESTION_MIN_WIDTH);
        }
        return MIN_WIDTH;
    }

    private static int minPanelHeight(MinecraftClient client, int width) {
        TextRenderer renderer = client == null ? null : client.textRenderer;
        int baseLineHeight = renderer == null ? 9 : renderer.fontHeight;
        int minContentHeight = Math.max(baseLineHeight * 2 + 8, 18);
        if (mode == OverlayMode.ASSETS) {
            minContentHeight = Math.max(minContentHeight, 100);
        }
        int inputOffset = mode == OverlayMode.RESPONSE ? (INPUT_HEIGHT + INPUT_GAP) : 0;
        if (pendingQuestion == null) {
            int base = HEADER_HEIGHT + 3 + CONTENT_PADDING + minContentHeight + CONTENT_PADDING + inputOffset;
            return Math.max(MIN_HEIGHT, base);
        }
        if (renderer == null) {
            int fallback = HEADER_HEIGHT + 3 + CONTENT_PADDING + QUESTION_MIN_HEIGHT + CONTENT_PADDING + inputOffset;
            return Math.max(Math.max(MIN_HEIGHT, QUESTION_MIN_HEIGHT), fallback);
        }
        int questionWidth = Math.max(20, width - CONTENT_PADDING * 2);
        int questionHeight = estimateQuestionBlockHeight(renderer, questionWidth);
        int computed = HEADER_HEIGHT + 3 + CONTENT_PADDING + questionHeight + QUESTION_BLOCK_GAP + minContentHeight + CONTENT_PADDING + inputOffset;
        return Math.max(Math.max(MIN_HEIGHT, QUESTION_MIN_HEIGHT), computed);
    }

    private static void ensureLayout(MinecraftClient client) {
        if (client == null) {
            return;
        }
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int minWidth = minPanelWidth();
        int maxPanelWidth = Math.max(minWidth, screenWidth - EDGE_PADDING * 2);
        panelWidth = MathHelper.clamp(panelWidth, minWidth, maxPanelWidth);
        int minHeight = minPanelHeight(client, panelWidth);
        int maxPanelHeight = Math.max(minHeight, screenHeight - EDGE_PADDING * 2);
        panelHeight = MathHelper.clamp(panelHeight, minHeight, maxPanelHeight);

        if (!layoutInitialized) {
            panelWidth = Math.min(Math.max(screenWidth / 3, 320), maxPanelWidth);
            minHeight = minPanelHeight(client, panelWidth);
            maxPanelHeight = Math.max(minHeight, screenHeight - EDGE_PADDING * 2);
            panelHeight = Math.min(Math.max(screenHeight / 3, 200), maxPanelHeight);
            panelX = Math.max(EDGE_PADDING, screenWidth - panelWidth - DEFAULT_MARGIN);
            panelY = Math.max(EDGE_PADDING, Math.max(DEFAULT_MARGIN, (screenHeight - panelHeight) / 4));
            expandedPanelX = panelX;
            expandedPanelY = panelY;
            layoutInitialized = true;
        }
        clampPanelToScreen(client);
        if (!minimized) {
            expandedPanelX = panelX;
            expandedPanelY = panelY;
        }
    }

    private static void clampPanelToScreen(MinecraftClient client) {
        if (client == null) {
            return;
        }
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        if (minimized) {
            int orbSize = ORB_RADIUS * 2;
            int maxX = Math.max(EDGE_PADDING, screenWidth - orbSize - EDGE_PADDING);
            int maxY = Math.max(EDGE_PADDING, screenHeight - orbSize - EDGE_PADDING);
            panelX = MathHelper.clamp(panelX, EDGE_PADDING, maxX);
            panelY = MathHelper.clamp(panelY, EDGE_PADDING, maxY);
            return;
        }
        int maxX = Math.max(EDGE_PADDING, screenWidth - panelWidth - EDGE_PADDING);
        int maxY = Math.max(EDGE_PADDING, screenHeight - panelHeight - EDGE_PADDING);
        panelX = MathHelper.clamp(panelX, EDGE_PADDING, maxX);
        panelY = MathHelper.clamp(panelY, EDGE_PADDING, maxY);
    }

    private static boolean isPointInPanel(double x, double y) {
        return x >= panelX && x <= panelX + panelWidth && y >= panelY && y <= panelY + panelHeight;
    }

    private static boolean isPointInHeader(double x, double y) {
        return x >= panelX && x <= panelX + panelWidth && y >= panelY && y <= panelY + HEADER_HEIGHT;
    }

    private static boolean isPointInResizeHandle(double x, double y) {
        return x >= panelX + panelWidth - RESIZE_HANDLE_SIZE
                && x <= panelX + panelWidth
                && y >= panelY + panelHeight - RESIZE_HANDLE_SIZE
                && y <= panelY + panelHeight;
    }

    private static int minimizeButtonLeft() {
        return closeButtonLeft() - HEADER_BUTTON_GAP - HEADER_BUTTON_SIZE;
    }

    private static int closeButtonLeft() {
        return panelX + panelWidth - HEADER_BUTTON_RIGHT_MARGIN - HEADER_BUTTON_SIZE;
    }

    private static int menuButtonLeft() {
        return minimizeButtonLeft() - HEADER_BUTTON_GAP - HEADER_BUTTON_SIZE;
    }

    private static boolean isPointInMinimizeButton(double x, double y) {
        int left = minimizeButtonLeft();
        int top = panelY + HEADER_BUTTON_TOP;
        return x >= left && x <= left + HEADER_BUTTON_SIZE && y >= top && y <= top + HEADER_BUTTON_SIZE;
    }

    private static boolean isPointInCloseButton(double x, double y) {
        int left = closeButtonLeft();
        int top = panelY + HEADER_BUTTON_TOP;
        return x >= left && x <= left + HEADER_BUTTON_SIZE && y >= top && y <= top + HEADER_BUTTON_SIZE;
    }

    private static boolean isPointInMenuButton(double x, double y) {
        int left = menuButtonLeft();
        int top = panelY + HEADER_BUTTON_TOP;
        return x >= left && x <= left + HEADER_BUTTON_SIZE && y >= top && y <= top + HEADER_BUTTON_SIZE;
    }

    private static boolean isPointInMenuDropdown(double x, double y) {
        int left = menuButtonLeft() - (MENU_WIDTH - HEADER_BUTTON_SIZE);
        int top = panelY + HEADER_HEIGHT + 2;
        int right = left + MENU_WIDTH;
        int bottom = top + (menuItemCount() * MENU_ITEM_HEIGHT);
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private static boolean isPointInOrb(double x, double y) {
        int centerX = panelX + ORB_RADIUS;
        int centerY = panelY + ORB_RADIUS;
        double dx = x - centerX;
        double dy = y - centerY;
        return (dx * dx) + (dy * dy) <= (ORB_RADIUS * ORB_RADIUS);
    }

    private static boolean isInteractive(MinecraftClient client) {
        return client != null && client.currentScreen != null;
    }

    public static boolean capturesKeyboardInput(MinecraftClient client) {
        // Only capture keyboard input when the input bar is shown and focused
        return isOverlayEnabled()
                && isInteractive(client)
                && visible
                && !minimized
                && shouldRenderInputBar()
                && inputFocused;
    }

    public static boolean capturesMouseInput(MinecraftClient client, double mouseX, double mouseY) {
        if (!isOverlayEnabled() || !isInteractive(client) || !visible) {
            return false;
        }
        ensureLayout(client);
        if (minimized) {
            return isPointInOrb(mouseX, mouseY);
        }
        if (menuOpen && (isPointInMenuButton(mouseX, mouseY) || isPointInMenuDropdown(mouseX, mouseY))) {
            return true;
        }
        return isPointInPanel(mouseX, mouseY);
    }

    private static void drawCircle(DrawContext context, int centerX, int centerY, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        int radiusSquared = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.floor(Math.sqrt(Math.max(0, radiusSquared - (dy * dy))));
            context.fill(centerX - dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, color);
        }
    }

    private static MenuItemBounds findMenuItem(double x, double y) {
        for (MenuItemBounds item : menuItems) {
            if (item.contains(x, y)) {
                return item;
            }
        }
        return null;
    }

    private static SessionRowBounds findSessionRow(double x, double y) {
        for (SessionRowBounds row : sessionRows) {
            if (row.contains(x, y)) {
                return row;
            }
        }
        return null;
    }

    private static AssetRowBounds findAssetRow(double x, double y) {
        for (AssetRowBounds row : assetRows) {
            if (row.contains(x, y)) {
                return row;
            }
        }
        return null;
    }

    private static AssetFilterButtonBounds findAssetFilterButton(double x, double y) {
        for (AssetFilterButtonBounds button : assetFilterButtons) {
            if (button.contains(x, y)) {
                return button;
            }
        }
        return null;
    }

    private static AssetsOverlayPayload.AssetItem findAssetById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (AssetsOverlayPayload.AssetItem item : assetItems) {
            if (item != null && id.equalsIgnoreCase(item.id())) {
                return item;
            }
        }
        return null;
    }

    private static List<AssetsOverlayPayload.AssetItem> filteredAssets() {
        if (assetItems.isEmpty()) {
            return List.of();
        }
        if (activeAssetFilter == null || activeAssetFilter == AssetFilter.ALL) {
            return assetItems;
        }
        List<AssetsOverlayPayload.AssetItem> filtered = new ArrayList<>();
        for (AssetsOverlayPayload.AssetItem item : assetItems) {
            if (item == null) {
                continue;
            }
            String category = item.category() == null ? "" : item.category().trim();
            if (activeAssetFilter.categoryKey.equalsIgnoreCase(category)) {
                filtered.add(item);
            }
        }
        return filtered;
    }

    private static boolean isSelectedAssetVisibleWithFilter(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        for (AssetsOverlayPayload.AssetItem item : filteredAssets()) {
            if (item != null && id.equalsIgnoreCase(item.id())) {
                return true;
            }
        }
        return false;
    }

    private static String firstVisibleAssetId() {
        List<AssetsOverlayPayload.AssetItem> visibleAssets = filteredAssets();
        if (visibleAssets.isEmpty()) {
            return "";
        }
        AssetsOverlayPayload.AssetItem item = visibleAssets.get(0);
        return item == null || item.id() == null ? "" : item.id().trim();
    }

    private static AssetsOverlayPayload.AssetItem selectedVisibleAsset() {
        AssetsOverlayPayload.AssetItem selected = findAssetById(selectedAssetId);
        if (selected == null || !isSelectedAssetVisibleWithFilter(selected.id())) {
            return null;
        }
        return selected;
    }

    private static boolean isEntityAsset(AssetsOverlayPayload.AssetItem item) {
        return item != null && "entities".equalsIgnoreCase(item.category());
    }

    private static boolean canGiveAsset(AssetsOverlayPayload.AssetItem item) {
        if (item == null || item.category() == null) {
            return false;
        }
        String category = item.category().toLowerCase(Locale.ROOT);
        if ("items_blocks_fluids".equals(category)) {
            return item.contentId() != null && !item.contentId().isBlank();
        }
        if ("special_items".equals(category)) {
            return item.specialItemId() != null && !item.specialItemId().isBlank();
        }
        return false;
    }

    private static String categoryDisplayName(String category) {
        if (category == null || category.isBlank()) {
            return "Asset";
        }
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "entities" -> "Entities";
            case "items_blocks_fluids" -> "Items/Blocks/Fluids";
            case "special_items" -> "Special Items";
            case "commands" -> "Commands";
            case "game_mechanics" -> "Game Mechanics";
            default -> category;
        };
    }

    private static void performAssetAction(MinecraftClient client, AssetAction action) {
        AssetsOverlayPayload.AssetItem selected = selectedVisibleAsset();
        if (selected == null || action == null) {
            return;
        }
        switch (action) {
            case TELEPORT -> {
                if (!isEntityAsset(selected) || selected.entityUuid().isBlank()) {
                    return;
                }
                sendCommand(client, "mineclawd assets teleport " + selected.id());
            }
            case GIVE -> {
                if (!canGiveAsset(selected)) {
                    return;
                }
                sendCommand(client, "mineclawd assets give " + selected.id());
            }
            case MODIFY -> startAssetFollowupSession(client, selected, false);
            case DELETE -> startAssetFollowupSession(client, selected, true);
        }
    }

    private static void startAssetFollowupSession(MinecraftClient client, AssetsOverlayPayload.AssetItem item, boolean deleteFlow) {
        if (client == null || item == null) {
            return;
        }
        mode = OverlayMode.RESPONSE;
        menuOpen = false;
        clearResponseContent();
        autoCreateSessionOnFirstSubmit = false;
        sendCommand(client, "mineclawd sessions new");
        String category = categoryDisplayName(item.category());
        String name = item.name() == null || item.name().isBlank() ? item.id() : item.name();
        if (deleteFlow) {
            setInputDraftText("I want to delete " + name + " (" + category + "). Please help me remove it. ", true);
        } else {
            setInputDraftText("Help me modify " + name + " (" + category + "). I want ", true);
        }
        inputFocused = true;
        inputDragSelecting = false;
        minimized = false;
        visible = true;
    }

    private static String formatSessionUpdatedAt(long epochMillis) {
        if (epochMillis <= 0L) {
            return "Updated: unknown";
        }
        return "Updated: " + SESSION_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static void handleMenuAction(MinecraftClient client, MenuAction action) {
        if (action == null) {
            return;
        }
        if (action == MenuAction.CONFIG) {
            sendCommand(client, "mineclawd config");
            return;
        }
        if (action == MenuAction.SESSIONS) {
            mode = OverlayMode.SESSIONS;
            inputFocused = false;
            inputDragSelecting = false;
            sendCommand(client, "mineclawd sessions");
            return;
        }
        if (action == MenuAction.ASSETS) {
            mode = OverlayMode.ASSETS;
            inputFocused = false;
            inputDragSelecting = false;
            sendCommand(client, "mineclawd assets");
            return;
        }
        cyclePersona(client);
    }

    private static void cyclePersona(MinecraftClient client) {
        if (personaNames.isEmpty()) {
            sendCommand(client, "mineclawd persona");
            return;
        }
        int index = -1;
        for (int i = 0; i < personaNames.size(); i++) {
            if (personaNames.get(i).equalsIgnoreCase(activePersona)) {
                index = i;
                break;
            }
        }
        int nextIndex = (index + 1 + personaNames.size()) % personaNames.size();
        String nextPersona = personaNames.get(nextIndex);
        if (nextPersona == null || nextPersona.isBlank()) {
            sendCommand(client, "mineclawd persona");
            return;
        }
        activePersona = nextPersona;
        sendCommand(client, "mineclawd persona " + nextPersona);
    }

    private static String currentInputDraft() {
        if (inputDraft == null) {
            inputDraft = "";
        }
        return inputDraft;
    }

    private static void normalizeInputCursor() {
        String draft = currentInputDraft();
        int length = draft.length();
        inputCursorIndex = MathHelper.clamp(inputCursorIndex, 0, length);
        inputSelectionIndex = MathHelper.clamp(inputSelectionIndex, 0, length);
        inputViewStart = MathHelper.clamp(inputViewStart, 0, length);
    }

    private static void setInputDraftText(String text, boolean moveCursorToEnd) {
        String normalized = text == null ? "" : sanitizeInputInsert(text);
        if (normalized.length() > INPUT_MAX_CHARS) {
            normalized = normalized.substring(0, INPUT_MAX_CHARS);
        }
        inputDraft = normalized;
        if (moveCursorToEnd) {
            inputCursorIndex = normalized.length();
            inputSelectionIndex = inputCursorIndex;
        } else {
            normalizeInputCursor();
        }
        inputViewStart = 0;
    }

    private static void appendInputCharacter(char character) {
        appendInputText(String.valueOf(character));
    }

    private static void appendInputText(String text) {
        String normalized = sanitizeInputInsert(text);
        if (normalized.isEmpty()) {
            return;
        }
        replaceInputSelection(normalized);
    }

    private static String sanitizeInputInsert(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean hasInputSelection() {
        normalizeInputCursor();
        return inputCursorIndex != inputSelectionIndex;
    }

    private static void deleteInputSelection() {
        if (!hasInputSelection()) {
            return;
        }
        String draft = currentInputDraft();
        int start = Math.min(inputCursorIndex, inputSelectionIndex);
        int end = Math.max(inputCursorIndex, inputSelectionIndex);
        inputDraft = draft.substring(0, start) + draft.substring(end);
        inputCursorIndex = start;
        inputSelectionIndex = start;
        if (inputViewStart > inputCursorIndex) {
            inputViewStart = inputCursorIndex;
        }
    }

    private static void replaceInputSelection(String text) {
        String insertion = sanitizeInputInsert(text);
        String draft = currentInputDraft();
        normalizeInputCursor();
        int start = Math.min(inputCursorIndex, inputSelectionIndex);
        int end = Math.max(inputCursorIndex, inputSelectionIndex);
        int preservedCount = draft.length() - (end - start);
        int remaining = INPUT_MAX_CHARS - preservedCount;
        if (remaining <= 0) {
            insertion = "";
        } else if (insertion.length() > remaining) {
            insertion = insertion.substring(0, remaining);
        }
        inputDraft = draft.substring(0, start) + insertion + draft.substring(end);
        inputCursorIndex = start + insertion.length();
        inputSelectionIndex = inputCursorIndex;
        if (inputViewStart > inputCursorIndex) {
            inputViewStart = inputCursorIndex;
        }
    }

    private static void setInputCursor(int cursor, boolean keepSelection) {
        String draft = currentInputDraft();
        int clamped = MathHelper.clamp(cursor, 0, draft.length());
        inputCursorIndex = clamped;
        if (!keepSelection) {
            inputSelectionIndex = clamped;
        }
    }

    private static int previousWordBoundary(String text, int cursor) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int index = MathHelper.clamp(cursor, 0, text.length());
        while (index > 0 && Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        while (index > 0 && !Character.isWhitespace(text.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private static int nextWordBoundary(String text, int cursor) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int index = MathHelper.clamp(cursor, 0, text.length());
        while (index < text.length() && !Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void moveInputCursorHorizontal(boolean moveRight, boolean ctrl, boolean shift) {
        String draft = currentInputDraft();
        normalizeInputCursor();
        int cursor = inputCursorIndex;
        if (ctrl) {
            cursor = moveRight ? nextWordBoundary(draft, cursor) : previousWordBoundary(draft, cursor);
        } else {
            cursor = moveRight ? Math.min(draft.length(), cursor + 1) : Math.max(0, cursor - 1);
        }
        setInputCursor(cursor, shift);
    }

    private static void deleteInputBackward(boolean ctrl) {
        String draft = currentInputDraft();
        normalizeInputCursor();
        if (hasInputSelection()) {
            deleteInputSelection();
            return;
        }
        if (inputCursorIndex <= 0) {
            return;
        }
        int deleteFrom = ctrl ? previousWordBoundary(draft, inputCursorIndex) : (inputCursorIndex - 1);
        inputDraft = draft.substring(0, deleteFrom) + draft.substring(inputCursorIndex);
        inputCursorIndex = deleteFrom;
        inputSelectionIndex = deleteFrom;
        if (inputViewStart > inputCursorIndex) {
            inputViewStart = inputCursorIndex;
        }
    }

    private static void deleteInputForward(boolean ctrl) {
        String draft = currentInputDraft();
        normalizeInputCursor();
        if (hasInputSelection()) {
            deleteInputSelection();
            return;
        }
        if (inputCursorIndex >= draft.length()) {
            return;
        }
        int deleteTo = ctrl ? nextWordBoundary(draft, inputCursorIndex) : (inputCursorIndex + 1);
        inputDraft = draft.substring(0, inputCursorIndex) + draft.substring(deleteTo);
        inputSelectionIndex = inputCursorIndex;
    }

    private static boolean copySelectedInput(MinecraftClient client) {
        if (client == null || client.keyboard == null || !hasInputSelection()) {
            return false;
        }
        String draft = currentInputDraft();
        int start = Math.min(inputCursorIndex, inputSelectionIndex);
        int end = Math.max(inputCursorIndex, inputSelectionIndex);
        client.keyboard.setClipboard(draft.substring(start, end));
        return true;
    }

    private static void setInputCursorFromMouse(MinecraftClient client, double mouseX, boolean keepSelectionAnchor) {
        if (client == null || client.textRenderer == null) {
            return;
        }
        String draft = currentInputDraft();
        normalizeInputCursor();
        int textAreaWidth = Math.max(8, inputFieldBounds.right - inputFieldBounds.left - (INPUT_TEXT_PADDING * 2));
        InputRenderWindow window = computeInputRenderWindow(client.textRenderer, draft, textAreaWidth);
        int relativeX = (int) Math.round(mouseX) - (inputFieldBounds.left + INPUT_TEXT_PADDING);
        int cursor = cursorIndexAtPixel(client.textRenderer, draft, window.startIndex, window.endIndex, relativeX);
        setInputCursor(cursor, keepSelectionAnchor);
    }

    private static int cursorIndexAtPixel(
            TextRenderer renderer,
            String draft,
            int start,
            int end,
            int pixelX
    ) {
        if (renderer == null || draft == null) {
            return 0;
        }
        if (pixelX <= 0) {
            return start;
        }
        int clampedEnd = MathHelper.clamp(end, start, draft.length());
        int cursor = start;
        for (int i = start; i < clampedEnd; i++) {
            int leftWidth = renderer.getWidth(draft.substring(start, i));
            int rightWidth = renderer.getWidth(draft.substring(start, i + 1));
            int mid = (leftWidth + rightWidth) / 2;
            if (pixelX < mid) {
                return i;
            }
            cursor = i + 1;
        }
        return cursor;
    }

    private static InputRenderWindow computeInputRenderWindow(TextRenderer renderer, String draft, int textAreaWidth) {
        if (renderer == null) {
            return new InputRenderWindow(0, 0, "");
        }
        String safeDraft = draft == null ? "" : draft;
        int length = safeDraft.length();
        if (length == 0 || textAreaWidth <= 0) {
            inputViewStart = 0;
            return new InputRenderWindow(0, 0, "");
        }
        normalizeInputCursor();
        int start = MathHelper.clamp(inputViewStart, 0, length);
        if (inputCursorIndex < start) {
            start = inputCursorIndex;
        }
        while (start < inputCursorIndex
                && renderer.getWidth(safeDraft.substring(start, inputCursorIndex)) > textAreaWidth) {
            start++;
        }

        int end = start;
        while (end < length) {
            int next = end + 1;
            if (renderer.getWidth(safeDraft.substring(start, next)) > textAreaWidth) {
                break;
            }
            end = next;
        }

        while (end < inputCursorIndex && start < inputCursorIndex) {
            start++;
            while (start < inputCursorIndex
                    && renderer.getWidth(safeDraft.substring(start, inputCursorIndex)) > textAreaWidth) {
                start++;
            }
            end = start;
            while (end < length) {
                int next = end + 1;
                if (renderer.getWidth(safeDraft.substring(start, next)) > textAreaWidth) {
                    break;
                }
                end = next;
            }
        }

        inputViewStart = MathHelper.clamp(start, 0, length);
        int safeEnd = MathHelper.clamp(end, inputViewStart, length);
        return new InputRenderWindow(inputViewStart, safeEnd, safeDraft.substring(inputViewStart, safeEnd));
    }

    private static void submitInputDraft(MinecraftClient client) {
        if (client == null) {
            return;
        }
        String draft = currentInputDraft().trim();
        if (draft.isBlank()) {
            return;
        }
        mode = OverlayMode.RESPONSE;
        menuOpen = false;
        if (autoCreateSessionOnFirstSubmit && activeSessionId.isBlank()) {
            sendCommand(client, "mineclawd sessions new");
            autoCreateSessionOnFirstSubmit = false;
        }
        sendCommand(client, "mclawd " + draft);
        setInputDraftText("", true);
    }

    private static void requestStopGeneration(MinecraftClient client) {
        if (client == null) {
            return;
        }
        sendCommand(client, "mineclawd stop");
        clearToolStatusState();
        generating = false;
        awaitingFirstAssistantDelta = false;
        activeRequestId = "";
    }

    private static void sendCommand(MinecraftClient client, String command) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return;
        }
        if (command == null || command.isBlank()) {
            return;
        }
        client.player.networkHandler.sendChatCommand(command.trim());
    }

    private static final class QuestionButtonBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final boolean skip;
        private final int optionIndex;

        private QuestionButtonBounds(int left, int top, int right, int bottom, boolean skip, int optionIndex) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.skip = skip;
            this.optionIndex = optionIndex;
        }

        private static QuestionButtonBounds option(int left, int top, int right, int bottom, int optionIndex) {
            return new QuestionButtonBounds(left, top, right, bottom, false, optionIndex);
        }

        private static QuestionButtonBounds skip(int left, int top, int right, int bottom) {
            return new QuestionButtonBounds(left, top, right, bottom, true, -1);
        }

        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class RectBounds {
        private int left;
        private int top;
        private int right;
        private int bottom;
        private boolean active;

        private void set(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.active = true;
        }

        private void clear() {
            this.active = false;
        }

        private boolean contains(double x, double y) {
            return active && x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private enum OverlayMode {
        RESPONSE,
        SESSIONS,
        ASSETS
    }

    private enum MenuAction {
        CONFIG,
        SESSIONS,
        ASSETS,
        PERSONA
    }

    private enum AssetAction {
        TELEPORT,
        GIVE,
        MODIFY,
        DELETE
    }

    private enum AssetFilter {
        ALL("All", ""),
        ENTITIES("Entities", "entities"),
        ITEMS_BLOCKS_FLUIDS("Items/Blocks/Fluids", "items_blocks_fluids"),
        SPECIAL_ITEMS("Special Items", "special_items"),
        COMMANDS("Commands", "commands"),
        GAME_MECHANICS("Game Mechanics", "game_mechanics");

        private final String label;
        private final String categoryKey;

        AssetFilter(String label, String categoryKey) {
            this.label = label;
            this.categoryKey = categoryKey == null ? "" : categoryKey;
        }
    }

    private static final class MenuItemBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final MenuAction action;

        private MenuItemBounds(int left, int top, int right, int bottom, MenuAction action) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.action = action;
        }

        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class SessionRowBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final String sessionId;

        private SessionRowBounds(int left, int top, int right, int bottom, String sessionId) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.sessionId = sessionId == null ? "" : sessionId.trim();
        }

        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class AssetRowBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final String assetId;

        private AssetRowBounds(int left, int top, int right, int bottom, String assetId) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.assetId = assetId == null ? "" : assetId.trim();
        }

        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class AssetFilterButtonBounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;
        private final AssetFilter filter;

        private AssetFilterButtonBounds(int left, int top, int right, int bottom, AssetFilter filter) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.filter = filter;
        }

        private boolean contains(double x, double y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private static final class InputRenderWindow {
        private final int startIndex;
        private final int endIndex;
        private final String visible;

        private InputRenderWindow(int startIndex, int endIndex, String visible) {
            this.startIndex = Math.max(0, startIndex);
            this.endIndex = Math.max(this.startIndex, endIndex);
            this.visible = visible == null ? "" : visible;
        }
    }

    private record StreamStartPayload(String sessionId, String request) {
        private static StreamStartPayload fromJson(String payload) {
            if (payload == null || payload.isBlank()) {
                return null;
            }
            try {
                JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
                String sessionId = root.has("sessionId") && root.get("sessionId").isJsonPrimitive()
                        ? root.get("sessionId").getAsString().trim()
                        : "";
                String request = root.has("request") && root.get("request").isJsonPrimitive()
                        ? root.get("request").getAsString().trim()
                        : "";
                return new StreamStartPayload(sessionId, request);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private record ToolStatusPayload(String shortText, String hoverText) {
        private static ToolStatusPayload fromJson(String payload) {
            if (payload == null || payload.isBlank()) {
                return null;
            }
            try {
                JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
                String shortText = root.has("short") && root.get("short").isJsonPrimitive()
                        ? root.get("short").getAsString().trim()
                        : "";
                String hoverText = root.has("hover") && root.get("hover").isJsonPrimitive()
                        ? root.get("hover").getAsString().trim()
                        : "";
                return new ToolStatusPayload(shortText, hoverText);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
