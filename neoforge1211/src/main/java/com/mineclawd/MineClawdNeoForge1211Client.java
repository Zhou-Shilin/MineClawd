package com.mineclawd;

import com.mineclawd.dynamic.DynamicContentNeoForge1211Client;
import net.neoforged.bus.api.IEventBus;

public final class MineClawdNeoForge1211Client {
    private MineClawdNeoForge1211Client() {
    }

    public static void init(IEventBus modEventBus) {
        MineClawdClientNetworking.init();
        DynamicContentNeoForge1211Client.init(modEventBus);
    }
}
