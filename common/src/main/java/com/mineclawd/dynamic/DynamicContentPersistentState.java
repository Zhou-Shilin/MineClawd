package com.mineclawd.dynamic;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;

public final class DynamicContentPersistentState extends PersistentState {
    private static final String STATE_ID = "mineclawd_dynamic_content";
    private static final String PAYLOAD_KEY = "payload";

    private String payload = "";

    public static DynamicContentPersistentState getOrCreate(MinecraftServer server) {
        if (server == null || server.getOverworld() == null) {
            return null;
        }
        PersistentStateManager manager = server.getOverworld().getPersistentStateManager();
        return manager.getOrCreate(DynamicContentPersistentState::fromNbt, DynamicContentPersistentState::new, STATE_ID);
    }

    public static DynamicContentPersistentState fromNbt(NbtCompound nbt) {
        DynamicContentPersistentState state = new DynamicContentPersistentState();
        if (nbt != null) {
            state.payload = nbt.getString(PAYLOAD_KEY);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
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
