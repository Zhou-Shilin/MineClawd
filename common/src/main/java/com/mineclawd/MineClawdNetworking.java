package com.mineclawd;

import net.minecraft.util.Identifier;

public final class MineClawdNetworking {
    public static final Identifier CLIENT_READY = Identifier.of(MineClawd.MOD_ID, "client_ready");
    public static final Identifier CLIENT_GUI_PREF = Identifier.of(MineClawd.MOD_ID, "client_gui_pref");
    public static final Identifier OPEN_CONFIG = Identifier.of(MineClawd.MOD_ID, "open_config");
    public static final Identifier SYNC_BROADCAST_TARGET = Identifier.of(MineClawd.MOD_ID, "sync_broadcast_target");
    public static final Identifier SYNC_ASSISTIVE_TOUCH = Identifier.of(MineClawd.MOD_ID, "sync_assistive_touch");
    public static final Identifier SYNC_DYNAMIC_CONTENT = Identifier.of(MineClawd.MOD_ID, "sync_dynamic_content");
    public static final Identifier OPEN_QUESTION = Identifier.of(MineClawd.MOD_ID, "open_question");
    public static final Identifier QUESTION_RESPONSE = Identifier.of(MineClawd.MOD_ID, "question_response");
    public static final Identifier OPEN_SESSIONS = Identifier.of(MineClawd.MOD_ID, "open_sessions");
    public static final Identifier OPEN_ASSETS = Identifier.of(MineClawd.MOD_ID, "open_assets");
    public static final Identifier OPEN_HISTORY_BOOK = Identifier.of(MineClawd.MOD_ID, "open_history_book");
    public static final Identifier AGENT_STREAM_EVENT = Identifier.of(MineClawd.MOD_ID, "agent_stream_event");

    private MineClawdNetworking() {
    }

    public static void register() {
    }
}
