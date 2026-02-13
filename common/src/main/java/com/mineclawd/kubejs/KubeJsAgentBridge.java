package com.mineclawd.kubejs;

import com.mineclawd.MineClawd;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

public final class KubeJsAgentBridge {
    private KubeJsAgentBridge() {
    }

    public static String requestWithSession(Object playerLike, String sessionReference, String request) {
        ServerPlayerEntity player = asPlayer(playerLike);
        if (player == null) {
            return "ERROR: requestWithSession requires a ServerPlayer argument (for example `event.player`).";
        }
        return MineClawd.enqueueKubeJsSessionRequest(player, sessionReference, request);
    }

    public static String requestOneShot(Object sourceLike, Object serverLike, String request) {
        ServerCommandSource source = asSource(sourceLike);
        MinecraftServer server = asServer(serverLike, source);
        if (server == null && sourceLike instanceof ServerPlayerEntity player) {
            server = player.getServer();
        }
        return MineClawd.enqueueKubeJsOneShotRequest(source, server, request);
    }

    private static ServerPlayerEntity asPlayer(Object value) {
        if (value instanceof ServerPlayerEntity player) {
            return player;
        }
        return null;
    }

    private static ServerCommandSource asSource(Object value) {
        if (value instanceof ServerCommandSource source) {
            return source.withMaxLevel(4);
        }
        if (value instanceof ServerPlayerEntity player) {
            return player.getCommandSource().withMaxLevel(4);
        }
        return null;
    }

    private static MinecraftServer asServer(Object value, ServerCommandSource source) {
        if (source != null && source.getServer() != null) {
            return source.getServer();
        }
        if (value instanceof MinecraftServer server) {
            return server;
        }
        if (value instanceof ServerPlayerEntity player) {
            return player.getServer();
        }
        if (value instanceof ServerCommandSource sourceValue) {
            return sourceValue.getServer();
        }
        return null;
    }
}
