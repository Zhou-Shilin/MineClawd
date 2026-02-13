package com.mineclawd;

import com.mineclawd.config.MineClawdConfigScreen;
import dev.architectury.platform.forge.EventBuses;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MineClawd.MOD_ID)
public final class MineClawdNeoForge1201 {
    public MineClawdNeoForge1201() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(MineClawd.MOD_ID, modEventBus);
        MineClawd.init();

        EnvExecutor.runInEnv(Env.CLIENT, () -> () -> {
            ModLoadingContext.get().registerExtensionPoint(
                    ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> MineClawdConfigScreen.create(parent))
            );
            MineClawdNeoForge1201Client.init(modEventBus);
        });
    }
}
