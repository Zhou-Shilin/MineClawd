package com.mineclawd.dynamic;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

public final class DynamicContentPersistentState extends PersistentState {
    private static final String STATE_ID = "mineclawd_dynamic_content";
    private static final String PAYLOAD_KEY = "payload";
    private static final PersistentState.Type<DynamicContentPersistentState> TYPE =
            new PersistentState.Type<>(
                    DynamicContentPersistentState::new,
                    DynamicContentPersistentState::fromNbt,
                    DataFixTypes.LEVEL
            );

    private String payload = "";

    public static DynamicContentPersistentState getOrCreate(MinecraftServer server) {
        if (server == null || server.getOverworld() == null) {
            return null;
        }
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(TYPE, STATE_ID);
    }

    public static DynamicContentPersistentState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup wrapperLookup) {
        DynamicContentPersistentState state = new DynamicContentPersistentState();
        if (nbt != null) {
            state.payload = nbt.getString(PAYLOAD_KEY);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup wrapperLookup) {
        nbt.putString(PAYLOAD_KEY, payload == null ? "" : payload);
        return nbt;
    }

    public String payload() {
        return payload == null ? "" : payload;
    }

    public void setPayload(String payload) {
        this.payload = payload == null ? "" : payload;
        markDirty();
    }
}
