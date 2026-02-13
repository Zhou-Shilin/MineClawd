package com.mineclawd;

import net.fabricmc.api.ModInitializer;

public final class MineClawdFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MineClawd.init();
    }
}

