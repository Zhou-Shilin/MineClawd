package com.mineclawd;

import net.minecraft.util.Identifier;

public final class MineClawdNetworking {
    public static final Identifier OPEN_CONFIG = new Identifier(MineClawd.MOD_ID, "open_config");
    public static final Identifier SYNC_BROADCAST_TARGET = new Identifier(MineClawd.MOD_ID, "sync_broadcast_target");
    public static final Identifier SYNC_DYNAMIC_CONTENT = new Identifier(MineClawd.MOD_ID, "sync_dynamic_content");
    public static final Identifier OPEN_QUESTION = new Identifier(MineClawd.MOD_ID, "open_question");
    public static final Identifier QUESTION_RESPONSE = new Identifier(MineClawd.MOD_ID, "question_response");
    public static final Identifier OPEN_HISTORY_BOOK = new Identifier(MineClawd.MOD_ID, "open_history_book");

    private MineClawdNetworking() {
    }

    public static void register() {
    }
}
