package com.mineclawd;

import com.mineclawd.dynamic.DynamicContentNeoForge1201Client;
import net.minecraftforge.eventbus.api.IEventBus;

public final class MineClawdNeoForge1201Client {
    private MineClawdNeoForge1201Client() {
    }

    public static void init(IEventBus modEventBus) {
        MineClawdClientNetworking.init();
        DynamicContentNeoForge1201Client.init(modEventBus);
    }
}
