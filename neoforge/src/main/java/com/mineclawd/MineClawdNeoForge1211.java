package com.mineclawd;

import com.mineclawd.config.MineClawdConfigScreen;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(MineClawd.MOD_ID)
public final class MineClawdNeoForge1211 {
    public MineClawdNeoForge1211(IEventBus modEventBus, ModContainer modContainer) {
        MineClawd.init();

        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
            modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                    (IConfigScreenFactory) (container, parent) -> MineClawdConfigScreen.create(parent));
            MineClawdNeoForge1211Client.init(modEventBus);
        });
    }
}
