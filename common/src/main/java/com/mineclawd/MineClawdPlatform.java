package com.mineclawd;

import dev.architectury.event.EventResult;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Consumer;

public interface MineClawdPlatform {
    boolean isClientEnvironment();

    String currentLoaderName();

    String modVersion(String modId);

    void registerServerQuestionResponse(Consumer<ServerQuestionResponseContext> handler);

    void registerClientOpenConfig(Consumer<PacketByteBuf> handler);

    void registerClientSyncBroadcastTarget(Consumer<PacketByteBuf> handler);

    void registerClientSyncDynamicContent(Consumer<PacketByteBuf> handler);

    void registerClientOpenQuestion(Consumer<PacketByteBuf> handler);

    void registerClientOpenHistoryBook(Consumer<PacketByteBuf> handler);

    void registerClientDisconnect(Runnable handler);

    void sendToServerQuestionResponse(String payloadJson);

    boolean canSend(ServerPlayerEntity player, net.minecraft.util.Identifier channel);

    void sendToPlayer(ServerPlayerEntity player, net.minecraft.util.Identifier channel, PacketByteBuf payload);

    PacketByteBuf createPacketBuf();

    void registerCommands(java.util.function.Consumer<CommandRegistrationContext> handler);

    void registerServerStarted(Consumer<net.minecraft.server.MinecraftServer> handler);

    void registerServerStopped(Consumer<net.minecraft.server.MinecraftServer> handler);

    void registerPlayerJoin(Consumer<ServerPlayerEntity> handler);

    void registerServerChat(java.util.function.Function<ServerChatContext, EventResult> handler);

    void registerConfigScreen();

    void registerDynamicClientHooks();

    BookScreen buildBookScreen(ItemStack stack);

    record ServerQuestionResponseContext(ServerPlayerEntity player, PacketByteBuf payload) {
    }

    record CommandRegistrationContext(
            com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource> dispatcher,
            net.minecraft.command.CommandRegistryAccess registryAccess,
            net.minecraft.server.command.CommandManager.RegistrationEnvironment environment
    ) {
    }

    record ServerChatContext(
            net.minecraft.server.network.ServerPlayerEntity sender,
            net.minecraft.text.Text message
    ) {
    }
}
