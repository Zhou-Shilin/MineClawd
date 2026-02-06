package com.mineclawd;

import com.mineclawd.config.MineClawdConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public class MineClawdClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MineClawdNetworking.register();
        ClientPlayNetworking.registerGlobalReceiver(MineClawdNetworking.OPEN_CONFIG,
                (client, handler, buf, responseSender) -> client.execute(() ->
                        client.setScreen(MineClawdConfigScreen.create(client.currentScreen))));
    }
}
