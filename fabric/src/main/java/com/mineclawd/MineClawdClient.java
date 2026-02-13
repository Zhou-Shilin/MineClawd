package com.mineclawd;

import com.mineclawd.dynamic.DynamicContentClientRenderer;
import com.mineclawd.dynamic.DynamicContentModelPlugin;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class MineClawdClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DynamicContentModelPlugin.register();
        DynamicContentClientRenderer.registerFluidRenderHandlers();
        MineClawdClientNetworking.init();
    }
}
