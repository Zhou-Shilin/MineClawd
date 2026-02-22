package com.mineclawd;

import dev.architectury.platform.forge.EventBuses;
import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import net.minecraft.client.gui.screen.Screen;
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
            if (hasClass("dev.isxander.yacl3.api.YetAnotherConfigLib")) {
                ModLoadingContext.get().registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> createConfigScreen(parent))
                );
            }
            MineClawdNeoForge1201Client.init(modEventBus);
        });
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, MineClawdNeoForge1201.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Screen createConfigScreen(Screen parent) {
        try {
            Class<?> configScreenClass = Class.forName("com.mineclawd.config.MineClawdConfigScreen");
            return (Screen) configScreenClass.getMethod("create", Screen.class).invoke(null, parent);
        } catch (ReflectiveOperationException ignored) {
            return parent;
        }
    }
}
