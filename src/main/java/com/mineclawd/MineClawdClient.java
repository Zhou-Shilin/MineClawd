package com.mineclawd;

import com.mineclawd.config.MineClawdConfigScreen;
import com.mineclawd.dynamic.DynamicContentClientRenderer;
import com.mineclawd.dynamic.DynamicContentModelPlugin;
import com.mineclawd.dynamic.DynamicContentRegistry;
import com.mineclawd.question.QuestionPromptPayload;
import com.mineclawd.question.QuestionPromptScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class MineClawdClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MineClawdNetworking.register();
        DynamicContentModelPlugin.register();
        DynamicContentClientRenderer.registerFluidRenderHandlers();
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.OPEN_CONFIG,
                (client, handler, buf, responseSender) -> {
                    String broadcastTarget = "self";
                    if (buf.isReadable()) {
                        broadcastTarget = buf.readString(64);
                    }
                    String finalBroadcastTarget = broadcastTarget;
                    client.execute(() -> {
                        MineClawdConfigScreen.syncBroadcastTargetFromServer(finalBroadcastTarget);
                        client.setScreen(MineClawdConfigScreen.create(client.currentScreen, finalBroadcastTarget));
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.SYNC_BROADCAST_TARGET,
                (client, handler, buf, responseSender) -> {
                    String broadcastTarget = "self";
                    if (buf.isReadable()) {
                        broadcastTarget = buf.readString(64);
                    }
                    String finalBroadcastTarget = broadcastTarget;
                    client.execute(() -> MineClawdConfigScreen.syncBroadcastTargetFromServer(finalBroadcastTarget));
                });
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.SYNC_DYNAMIC_CONTENT,
                (client, handler, buf, responseSender) -> {
                    String payload = buf.readString(32767);
                    client.execute(() -> {
                        DynamicContentRegistry.applySyncPayload(payload);
                        if (client.world != null) {
                            client.reloadResources();
                        }
                    });
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                MineClawdConfigScreen.clearBroadcastTargetServerSync());
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.OPEN_QUESTION,
                (client, handler, buf, responseSender) -> {
                    String payload = buf.readString(32767);
                    client.execute(() -> {
                        QuestionPromptPayload prompt = QuestionPromptPayload.fromJson(payload);
                        if (prompt == null) {
                            return;
                        }
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("[MineClawd] Please answer the question in the popup window."), false);
                        }
                        client.setScreen(new QuestionPromptScreen(client.currentScreen, prompt));
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.OPEN_HISTORY_BOOK,
                (client, handler, buf, responseSender) -> {
                    ItemStack stack = buf.readItemStack();
                    client.execute(() -> {
                        if (stack == null || stack.isEmpty()) {
                            if (client.player != null) {
                                client.player.sendMessage(Text.literal("[MineClawd] History is empty."), false);
                            }
                            return;
                        }
                        client.setScreen(new BookScreen(new BookScreen.WrittenBookContents(stack)));
                    });
                });
    }
}
