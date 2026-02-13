package com.mineclawd.dynamic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineclawd.MineClawd;
import com.mineclawd.MineClawdNetworking;
import com.mineclawd.config.MineClawdConfig;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DynamicContentRegistry {
    public static final int SLOT_COUNT = 30;

    private static final String DEFAULT_ITEM_MATERIAL = "minecraft:stick";
    private static final String DEFAULT_BLOCK_MATERIAL = "minecraft:stone";
    private static final String DEFAULT_FLUID_MATERIAL = "minecraft:water";

    private static final DynamicItem[] ITEM_PLACEHOLDERS = new DynamicItem[SLOT_COUNT];
    private static final DynamicBlock[] BLOCK_PLACEHOLDERS = new DynamicBlock[SLOT_COUNT];
    private static final DynamicBlockItem[] BLOCK_ITEM_PLACEHOLDERS = new DynamicBlockItem[SLOT_COUNT];
    private static final DynamicFluid.Still[] STILL_FLUIDS = new DynamicFluid.Still[SLOT_COUNT];
    private static final DynamicFluid.Flowing[] FLOWING_FLUIDS = new DynamicFluid.Flowing[SLOT_COUNT];
    private static final DynamicFluidBlock[] FLUID_BLOCKS = new DynamicFluidBlock[SLOT_COUNT];
    private static final DynamicFluidBucketItem[] FLUID_BUCKETS = new DynamicFluidBucketItem[SLOT_COUNT];

    private static final List<ItemSlotState> ITEM_STATES = new ArrayList<>(SLOT_COUNT);
    private static final List<BlockSlotState> BLOCK_STATES = new ArrayList<>(SLOT_COUNT);
    private static final List<FluidSlotState> FLUID_STATES = new ArrayList<>(SLOT_COUNT);
    private static final DeferredRegister<Item> ITEM_REGISTER = DeferredRegister.create(MineClawd.MOD_ID, RegistryKeys.ITEM);
    private static final DeferredRegister<Block> BLOCK_REGISTER = DeferredRegister.create(MineClawd.MOD_ID, RegistryKeys.BLOCK);
    private static final DeferredRegister<Fluid> FLUID_REGISTER = DeferredRegister.create(MineClawd.MOD_ID, RegistryKeys.FLUID);
    private static final DeferredRegister<ItemGroup> ITEM_GROUP_REGISTER = DeferredRegister.create(MineClawd.MOD_ID, RegistryKeys.ITEM_GROUP);

    private static boolean bootstrapped;
    private static boolean runtimeEnabled;
    private static boolean persistentStateLoaded;
    private static boolean deferredRegistersAttached;

    static {
        resetAllStates();
    }

    private DynamicContentRegistry() {
    }

    public static synchronized void bootstrap(MineClawdConfig config) {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        MineClawdConfig.DynamicRegistryMode mode = config == null || config.dynamicRegistryMode == null
                ? MineClawdConfig.DynamicRegistryMode.AUTO
                : config.dynamicRegistryMode;
        runtimeEnabled = resolveRuntimeEnabled(mode);
        if (!runtimeEnabled) {
            MineClawd.LOGGER.info(
                    "Dynamic placeholder registry is disabled (mode={}, env={}).",
                    mode.name().toLowerCase(Locale.ROOT),
                    Platform.getEnvironment().name().toLowerCase(Locale.ROOT)
            );
            return;
        }

        registerPlaceholders();
        if (Platform.getEnvironment() == Env.SERVER) {
            MineClawd.LOGGER.warn("Dynamic placeholder registry is enabled on a dedicated server. Clients now need MineClawd installed to join.");
        }
    }

    public static synchronized boolean isRuntimeEnabled() {
        return runtimeEnabled;
    }

    public static synchronized boolean placeholdersRegistered() {
        return runtimeEnabled && ITEM_PLACEHOLDERS[0] != null;
    }

    public static synchronized OperationResult listState() {
        if (!runtimeEnabled) {
            return OperationResult.success("Dynamic placeholder registry is disabled in this environment.");
        }

        StringBuilder out = new StringBuilder();
        out.append("Dynamic registry status\n");
        out.append("items: ").append(countActiveItems()).append("/").append(SLOT_COUNT).append(" active\n");
        appendItemList(out);
        out.append("\n");
        out.append("blocks: ").append(countActiveBlocks()).append("/").append(SLOT_COUNT).append(" active\n");
        appendBlockList(out);
        out.append("\n");
        out.append("fluids: ").append(countActiveFluids()).append("/").append(SLOT_COUNT).append(" active\n");
        appendFluidList(out);
        return OperationResult.success(out.toString().trim());
    }

    public static synchronized OperationResult registerItem(
            Integer requestedSlot,
            String displayName,
            String materialItemInput,
            Boolean throwable
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        int slot = requestedSlot == null ? firstInactiveItemSlot() : requestedSlot - 1;
        if (slot < 0) {
            return OperationResult.error("No free dynamic item slot left. All 30 slots are already active.");
        }
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }

        ItemSlotState current = ITEM_STATES.get(slot);
        String materialItemId = resolveItemMaterialId(materialItemInput, current.materialItemId());
        if (materialItemId == null) {
            return OperationResult.error("`material_item` must reference a vanilla item id such as `minecraft:diamond`.");
        }

        String finalName = normalizeName(displayName, current.displayName(), "Dynamic Item " + slotLabel(slot));
        boolean finalThrowable = throwable == null ? current.throwable() : throwable;
        ITEM_STATES.set(slot, new ItemSlotState(true, finalName, materialItemId, finalThrowable));

        return OperationResult.success(
                "Registered item slot " + slotNumber(slot) + " -> `" + itemIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_item: " + materialItemId + "\n"
                        + "throwable: " + finalThrowable
        );
    }

    public static synchronized OperationResult registerBlock(
            Integer requestedSlot,
            String displayName,
            String materialBlockInput,
            Double frictionInput
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        int slot = requestedSlot == null ? firstInactiveBlockSlot() : requestedSlot - 1;
        if (slot < 0) {
            return OperationResult.error("No free dynamic block slot left. All 30 slots are already active.");
        }
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }

        BlockSlotState current = BLOCK_STATES.get(slot);
        String materialBlockId = resolveBlockMaterialId(materialBlockInput, current.materialBlockId());
        if (materialBlockId == null) {
            return OperationResult.error("`material_block` must reference a vanilla block id such as `minecraft:ice`.");
        }

        float friction = frictionInput == null
                ? (current.active() ? current.friction() : defaultFrictionFromMaterial(materialBlockId))
                : clampFriction(frictionInput.floatValue());
        String finalName = normalizeName(displayName, current.displayName(), "Dynamic Block " + slotLabel(slot));
        BLOCK_STATES.set(slot, new BlockSlotState(true, finalName, materialBlockId, friction));

        return OperationResult.success(
                "Registered block slot " + slotNumber(slot) + " -> `" + blockIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_block: " + materialBlockId + "\n"
                        + "friction: " + friction
        );
    }

    public static synchronized OperationResult registerFluid(
            Integer requestedSlot,
            String displayName,
            String materialFluidInput,
            String colorInput
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        int slot = requestedSlot == null ? firstInactiveFluidSlot() : requestedSlot - 1;
        if (slot < 0) {
            return OperationResult.error("No free dynamic fluid slot left. All 30 slots are already active.");
        }
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }

        FluidSlotState current = FLUID_STATES.get(slot);
        String materialFluidId = resolveFluidMaterialId(materialFluidInput, current.materialFluidId());
        if (materialFluidId == null) {
            return OperationResult.error("`material_fluid` must reference a vanilla fluid id such as `minecraft:water`.");
        }

        Integer color = parseFluidColorInput(colorInput, current.customColorRgb(), materialFluidId);
        if (color == Integer.MIN_VALUE) {
            return OperationResult.error("`color` must be `#RRGGBB`, `RRGGBB`, or `default`.");
        }

        String finalName = normalizeName(displayName, current.displayName(), "Dynamic Fluid " + slotLabel(slot));
        FLUID_STATES.set(slot, new FluidSlotState(true, finalName, materialFluidId, color));

        String colorText = color == null
                ? "default(from material)"
                : "#" + String.format(Locale.ROOT, "%06X", color);
        return OperationResult.success(
                "Registered fluid slot " + slotNumber(slot) + " -> `" + fluidStillIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_fluid: " + materialFluidId + "\n"
                        + "color: " + colorText + "\n"
                        + "bucket item: `" + fluidBucketIdString(slot) + "`"
        );
    }

    public static synchronized OperationResult unregister(String typeInput, Integer slotInput) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        if (typeInput == null || typeInput.isBlank()) {
            return OperationResult.error("`type` is required and must be one of: item, block, fluid.");
        }
        if (slotInput == null) {
            return OperationResult.error("`slot` is required and must be between 1 and 30.");
        }

        int slot = slotInput - 1;
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }

        String type = typeInput.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "item", "items" -> {
                ITEM_STATES.set(slot, defaultItemState(slot));
                return OperationResult.success("Unregistered `" + itemIdString(slot) + "`.");
            }
            case "block", "blocks" -> {
                BLOCK_STATES.set(slot, defaultBlockState(slot));
                return OperationResult.success("Unregistered `" + blockIdString(slot) + "`.");
            }
            case "fluid", "fluids" -> {
                FLUID_STATES.set(slot, defaultFluidState(slot));
                return OperationResult.success("Unregistered `" + fluidStillIdString(slot) + "` and `" + fluidBucketIdString(slot) + "`.");
            }
            default -> {
                return OperationResult.error("Unknown type `" + typeInput + "`. Use item, block, or fluid.");
            }
        }
    }

    public static synchronized OperationResult updateItem(
            Integer slotInput,
            String displayName,
            String materialItemInput,
            Boolean throwable
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        if (slotInput == null) {
            return OperationResult.error("`slot` is required for update.");
        }
        int slot = slotInput - 1;
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }
        ItemSlotState current = ITEM_STATES.get(slot);
        if (!current.active()) {
            return OperationResult.error("Item slot " + slotInput + " is not active. Use `register-dynamic-item` first.");
        }

        String materialItemId = resolveItemMaterialId(materialItemInput, current.materialItemId());
        if (materialItemId == null) {
            return OperationResult.error("`material_item` must reference a vanilla item id such as `minecraft:diamond`.");
        }
        String finalName = normalizeName(displayName, current.displayName(), current.displayName());
        boolean finalThrowable = throwable == null ? current.throwable() : throwable;
        ITEM_STATES.set(slot, new ItemSlotState(true, finalName, materialItemId, finalThrowable));

        return OperationResult.success(
                "Updated item slot " + slotNumber(slot) + " -> `" + itemIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_item: " + materialItemId + "\n"
                        + "throwable: " + finalThrowable
        );
    }

    public static synchronized OperationResult updateBlock(
            Integer slotInput,
            String displayName,
            String materialBlockInput,
            Double frictionInput
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        if (slotInput == null) {
            return OperationResult.error("`slot` is required for update.");
        }
        int slot = slotInput - 1;
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }
        BlockSlotState current = BLOCK_STATES.get(slot);
        if (!current.active()) {
            return OperationResult.error("Block slot " + slotInput + " is not active. Use `register-dynamic-block` first.");
        }

        String materialBlockId = resolveBlockMaterialId(materialBlockInput, current.materialBlockId());
        if (materialBlockId == null) {
            return OperationResult.error("`material_block` must reference a vanilla block id such as `minecraft:ice`.");
        }
        float friction = frictionInput == null ? current.friction() : clampFriction(frictionInput.floatValue());
        String finalName = normalizeName(displayName, current.displayName(), current.displayName());
        BLOCK_STATES.set(slot, new BlockSlotState(true, finalName, materialBlockId, friction));

        return OperationResult.success(
                "Updated block slot " + slotNumber(slot) + " -> `" + blockIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_block: " + materialBlockId + "\n"
                        + "friction: " + friction
        );
    }

    public static synchronized OperationResult updateFluid(
            Integer slotInput,
            String displayName,
            String materialFluidInput,
            String colorInput
    ) {
        if (!runtimeEnabled) {
            return disabledResult();
        }
        if (slotInput == null) {
            return OperationResult.error("`slot` is required for update.");
        }
        int slot = slotInput - 1;
        if (!isSlotInRange(slot)) {
            return OperationResult.error("`slot` must be between 1 and 30.");
        }
        FluidSlotState current = FLUID_STATES.get(slot);
        if (!current.active()) {
            return OperationResult.error("Fluid slot " + slotInput + " is not active. Use `register-dynamic-fluid` first.");
        }

        String materialFluidId = resolveFluidMaterialId(materialFluidInput, current.materialFluidId());
        if (materialFluidId == null) {
            return OperationResult.error("`material_fluid` must reference a vanilla fluid id such as `minecraft:water`.");
        }
        Integer color = parseFluidColorInput(colorInput, current.customColorRgb(), materialFluidId);
        if (color == Integer.MIN_VALUE) {
            return OperationResult.error("`color` must be `#RRGGBB`, `RRGGBB`, or `default`.");
        }
        String finalName = normalizeName(displayName, current.displayName(), current.displayName());
        FLUID_STATES.set(slot, new FluidSlotState(true, finalName, materialFluidId, color));

        String colorText = color == null ? "default(from material)" : "#" + String.format(Locale.ROOT, "%06X", color);
        return OperationResult.success(
                "Updated fluid slot " + slotNumber(slot) + " -> `" + fluidStillIdString(slot) + "`\n"
                        + "name: " + finalName + "\n"
                        + "material_fluid: " + materialFluidId + "\n"
                        + "color: " + colorText + "\n"
                        + "bucket item: `" + fluidBucketIdString(slot) + "`"
        );
    }

    public static synchronized String buildSyncPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", runtimeEnabled);
        root.add("items", writeItems());
        root.add("blocks", writeBlocks());
        root.add("fluids", writeFluids());
        return root.toString();
    }

    public static synchronized void applySyncPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
            if (!enabled) {
                resetAllStates();
                return;
            }
            readItems(root.getAsJsonArray("items"));
            readBlocks(root.getAsJsonArray("blocks"));
            readFluids(root.getAsJsonArray("fluids"));
        } catch (Exception e) {
            MineClawd.LOGGER.warn("Failed to apply dynamic state sync payload: {}", e.getMessage());
        }
    }

    public static void syncToPlayer(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        if (!MineClawd.canSendToClient(player, MineClawdNetworking.SYNC_DYNAMIC_CONTENT)) {
            return;
        }
        var buf = new RegistryByteBuf(Unpooled.buffer(), player.getServerWorld().getRegistryManager());
        buf.writeString(buildSyncPayload());
        NetworkManager.sendToPlayer(player, MineClawdNetworking.SYNC_DYNAMIC_CONTENT, buf);
    }

    public static void syncToAll(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            syncToPlayer(player);
        }
    }

    public static synchronized void loadPersistentState(MinecraftServer server) {
        if (!runtimeEnabled) {
            return;
        }
        if (persistentStateLoaded) {
            return;
        }
        DynamicContentPersistentState persistent = DynamicContentPersistentState.getOrCreate(server);
        if (persistent == null) {
            return;
        }
        String payload = persistent.payload();
        resetAllStates();
        if (payload.isBlank()) {
            persistentStateLoaded = true;
            return;
        }
        applySyncPayload(payload);
        persistentStateLoaded = true;
    }

    public static synchronized void savePersistentState(MinecraftServer server) {
        if (!runtimeEnabled) {
            return;
        }
        DynamicContentPersistentState persistent = DynamicContentPersistentState.getOrCreate(server);
        if (persistent == null) {
            return;
        }
        persistent.setPayload(buildSyncPayload());
        if (server != null && server.getOverworld() != null) {
            server.getOverworld().getPersistentStateManager().save();
        }
        persistentStateLoaded = true;
    }

    public static synchronized void clearServerStateCache() {
        persistentStateLoaded = false;
        resetAllStates();
    }

    public static synchronized MutableText itemDisplayName(int slot) {
        if (!isSlotInRange(slot)) {
            return Text.literal("Dynamic Item");
        }
        ItemSlotState state = ITEM_STATES.get(slot);
        return Text.literal(state.active() ? state.displayName() : "Dynamic Item " + slotLabel(slot));
    }

    public static synchronized MutableText blockDisplayName(int slot) {
        if (!isSlotInRange(slot)) {
            return Text.literal("Dynamic Block");
        }
        BlockSlotState state = BLOCK_STATES.get(slot);
        return Text.literal(state.active() ? state.displayName() : "Dynamic Block " + slotLabel(slot));
    }

    public static synchronized MutableText fluidBucketDisplayName(int slot) {
        if (!isSlotInRange(slot)) {
            return Text.literal("Dynamic Fluid Bucket");
        }
        FluidSlotState state = FLUID_STATES.get(slot);
        String base = state.active() ? state.displayName() : "Dynamic Fluid " + slotLabel(slot);
        return Text.literal(base + " Bucket");
    }

    public static synchronized boolean isItemThrowable(int slot) {
        return isSlotInRange(slot) && ITEM_STATES.get(slot).active() && ITEM_STATES.get(slot).throwable();
    }

    public static synchronized boolean isItemActive(int slot) {
        return isSlotInRange(slot) && ITEM_STATES.get(slot).active();
    }

    public static synchronized boolean isBlockActive(int slot) {
        return isSlotInRange(slot) && BLOCK_STATES.get(slot).active();
    }

    public static synchronized boolean isFluidActive(int slot) {
        return isSlotInRange(slot) && FLUID_STATES.get(slot).active();
    }

    public static synchronized float blockFriction(int slot) {
        if (!isSlotInRange(slot)) {
            return Blocks.STONE.getSlipperiness();
        }
        BlockSlotState state = BLOCK_STATES.get(slot);
        if (!state.active()) {
            return defaultBlockState(slot).friction();
        }
        return state.friction();
    }

    public static synchronized Item materialItemForSlot(int slot) {
        if (!isSlotInRange(slot)) {
            return Items.STICK;
        }
        return resolveItem(ITEM_STATES.get(slot).materialItemId(), Items.STICK);
    }

    public static synchronized Block materialBlockForSlot(int slot) {
        if (!isSlotInRange(slot)) {
            return Blocks.STONE;
        }
        return resolveBlock(BLOCK_STATES.get(slot).materialBlockId(), Blocks.STONE);
    }

    public static synchronized Fluid materialFluidForSlot(int slot) {
        if (!isSlotInRange(slot)) {
            return Fluids.WATER;
        }
        return resolveFluid(FLUID_STATES.get(slot).materialFluidId(), Fluids.WATER);
    }

    public static synchronized Integer fluidCustomColor(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return FLUID_STATES.get(slot).customColorRgb();
    }

    public static synchronized int fluidTickRate(int slot) {
        // Dynamic fluids should keep water-like movement by default.
        return 5;
    }

    public static synchronized int fluidFlowSpeed(int slot) {
        // Dynamic fluids should keep water-like movement by default.
        return 4;
    }

    public static synchronized int fluidLevelDecreasePerBlock(int slot) {
        // Dynamic fluids should keep water-like movement by default.
        return 1;
    }

    public static synchronized float fluidBlastResistance(int slot) {
        return 100.0F;
    }

    public static synchronized DynamicFluidBlock fluidBlock(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return FLUID_BLOCKS[slot];
    }

    public static synchronized DynamicBlock dynamicBlock(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return BLOCK_PLACEHOLDERS[slot];
    }

    public static synchronized DynamicFluidBlock dynamicFluidBlock(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return FLUID_BLOCKS[slot];
    }

    public static synchronized Identifier dynamicItemId(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return Identifier.of(MineClawd.MOD_ID, "dynamic_item_" + slotLabel(slot));
    }

    public static synchronized Identifier dynamicBlockId(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return Identifier.of(MineClawd.MOD_ID, "dynamic_block_" + slotLabel(slot));
    }

    public static synchronized Identifier dynamicFluidBucketId(int slot) {
        if (!isSlotInRange(slot)) {
            return null;
        }
        return Identifier.of(MineClawd.MOD_ID, "dynamic_fluid_bucket_" + slotLabel(slot));
    }

    public static synchronized Fluid stillFluid(int slot) {
        if (!isSlotInRange(slot) || STILL_FLUIDS[slot] == null) {
            return Fluids.EMPTY;
        }
        return STILL_FLUIDS[slot];
    }

    public static synchronized Fluid flowingFluid(int slot) {
        if (!isSlotInRange(slot) || FLOWING_FLUIDS[slot] == null) {
            return Fluids.EMPTY;
        }
        return FLOWING_FLUIDS[slot];
    }

    private static boolean resolveRuntimeEnabled(MineClawdConfig.DynamicRegistryMode mode) {
        if (mode == MineClawdConfig.DynamicRegistryMode.ENABLED) {
            return true;
        }
        if (mode == MineClawdConfig.DynamicRegistryMode.DISABLED) {
            return false;
        }
        return Platform.getEnvironment() == Env.CLIENT;
    }

    private static void registerPlaceholders() {
        if (deferredRegistersAttached || ITEM_PLACEHOLDERS[0] != null) {
            return;
        }
        deferredRegistersAttached = true;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int slotIndex = slot;
            String label = slotLabel(slotIndex);

            RegistrySupplier<DynamicItem> itemSupplier = ITEM_REGISTER.register(
                    "dynamic_item_" + label,
                    () -> new DynamicItem(slotIndex, new Item.Settings())
            );
            itemSupplier.listen(item -> ITEM_PLACEHOLDERS[slotIndex] = item);

            RegistrySupplier<DynamicBlock> blockSupplier = BLOCK_REGISTER.register(
                    "dynamic_block_" + label,
                    () -> new DynamicBlock(slotIndex, AbstractBlock.Settings.copy(Blocks.STONE))
            );
            blockSupplier.listen(block -> BLOCK_PLACEHOLDERS[slotIndex] = block);
            RegistrySupplier<DynamicBlockItem> blockItemSupplier = ITEM_REGISTER.register(
                    "dynamic_block_" + label,
                    () -> new DynamicBlockItem(slotIndex, blockSupplier.get(), new Item.Settings())
            );
            blockItemSupplier.listen(blockItem -> BLOCK_ITEM_PLACEHOLDERS[slotIndex] = blockItem);

            RegistrySupplier<DynamicFluid.Still> stillSupplier = FLUID_REGISTER.register(
                    "dynamic_fluid_" + label,
                    () -> new DynamicFluid.Still(
                            slotIndex,
                            () -> STILL_FLUIDS[slotIndex],
                            () -> FLOWING_FLUIDS[slotIndex],
                            () -> FLUID_BUCKETS[slotIndex]
                    )
            );
            stillSupplier.listen(still -> STILL_FLUIDS[slotIndex] = still);
            RegistrySupplier<DynamicFluid.Flowing> flowingSupplier = FLUID_REGISTER.register(
                    "flowing_dynamic_fluid_" + label,
                    () -> new DynamicFluid.Flowing(
                            slotIndex,
                            () -> STILL_FLUIDS[slotIndex],
                            () -> FLOWING_FLUIDS[slotIndex],
                            () -> FLUID_BUCKETS[slotIndex]
                    )
            );
            flowingSupplier.listen(flowing -> FLOWING_FLUIDS[slotIndex] = flowing);

            RegistrySupplier<DynamicFluidBlock> fluidBlockSupplier = BLOCK_REGISTER.register(
                    "dynamic_fluid_block_" + label,
                    () -> new DynamicFluidBlock(
                            slotIndex,
                            stillSupplier.get(),
                            AbstractBlock.Settings.copy(Blocks.WATER).noCollision().strength(100.0F).dropsNothing()
                    )
            );
            fluidBlockSupplier.listen(fluidBlock -> FLUID_BLOCKS[slotIndex] = fluidBlock);
            RegistrySupplier<DynamicFluidBucketItem> bucketSupplier = ITEM_REGISTER.register(
                    "dynamic_fluid_bucket_" + label,
                    () -> new DynamicFluidBucketItem(
                            slotIndex,
                            stillSupplier.get(),
                            new Item.Settings().maxCount(1).recipeRemainder(Items.BUCKET)
                    )
            );
            bucketSupplier.listen(bucket -> FLUID_BUCKETS[slotIndex] = bucket);
        }

        ITEM_GROUP_REGISTER.register(
                "dynamic_content",
                () -> CreativeTabRegistry.create(builder -> {
                    builder.displayName(Text.literal("MineClawd Dynamic"));
                    builder.icon(() -> new ItemStack(ITEM_PLACEHOLDERS[0] == null ? Items.STICK : ITEM_PLACEHOLDERS[0]));
                    builder.entries((displayContext, entries) -> addCreativeEntries(entries));
                })
        );

        FLUID_REGISTER.register();
        BLOCK_REGISTER.register();
        ITEM_REGISTER.register();
        ITEM_GROUP_REGISTER.register();
    }

    private static void addCreativeEntries(ItemGroup.Entries entries) {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (isItemActive(slot) && ITEM_PLACEHOLDERS[slot] != null) {
                entries.add(new ItemStack(ITEM_PLACEHOLDERS[slot]));
            }
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (isBlockActive(slot) && BLOCK_ITEM_PLACEHOLDERS[slot] != null) {
                entries.add(new ItemStack(BLOCK_ITEM_PLACEHOLDERS[slot]));
            }
        }
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (isFluidActive(slot) && FLUID_BUCKETS[slot] != null) {
                entries.add(new ItemStack(FLUID_BUCKETS[slot]));
            }
        }
    }

    private static OperationResult disabledResult() {
        return OperationResult.error("Dynamic placeholder registry is disabled. Enable `dynamic-registry-mode` and restart this server.");
    }

    private static int firstInactiveItemSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!ITEM_STATES.get(i).active()) {
                return i;
            }
        }
        return -1;
    }

    private static int firstInactiveBlockSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!BLOCK_STATES.get(i).active()) {
                return i;
            }
        }
        return -1;
    }

    private static int firstInactiveFluidSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (!FLUID_STATES.get(i).active()) {
                return i;
            }
        }
        return -1;
    }

    private static int countActiveItems() {
        int count = 0;
        for (ItemSlotState state : ITEM_STATES) {
            if (state.active()) {
                count++;
            }
        }
        return count;
    }

    private static int countActiveBlocks() {
        int count = 0;
        for (BlockSlotState state : BLOCK_STATES) {
            if (state.active()) {
                count++;
            }
        }
        return count;
    }

    private static int countActiveFluids() {
        int count = 0;
        for (FluidSlotState state : FLUID_STATES) {
            if (state.active()) {
                count++;
            }
        }
        return count;
    }

    private static void appendItemList(StringBuilder out) {
        boolean any = false;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemSlotState state = ITEM_STATES.get(slot);
            if (!state.active()) {
                continue;
            }
            any = true;
            out.append("- ").append(slotNumber(slot))
                    .append(" `").append(itemIdString(slot)).append("`")
                    .append(" name=\"").append(state.displayName()).append("\"")
                    .append(" material=").append(state.materialItemId())
                    .append(" throwable=").append(state.throwable())
                    .append("\n");
        }
        if (!any) {
            out.append("- none\n");
        }
    }

    private static void appendBlockList(StringBuilder out) {
        boolean any = false;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            BlockSlotState state = BLOCK_STATES.get(slot);
            if (!state.active()) {
                continue;
            }
            any = true;
            out.append("- ").append(slotNumber(slot))
                    .append(" `").append(blockIdString(slot)).append("`")
                    .append(" name=\"").append(state.displayName()).append("\"")
                    .append(" material=").append(state.materialBlockId())
                    .append(" friction=").append(state.friction())
                    .append("\n");
        }
        if (!any) {
            out.append("- none\n");
        }
    }

    private static void appendFluidList(StringBuilder out) {
        boolean any = false;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            FluidSlotState state = FLUID_STATES.get(slot);
            if (!state.active()) {
                continue;
            }
            any = true;
            out.append("- ").append(slotNumber(slot))
                    .append(" `").append(fluidStillIdString(slot)).append("`")
                    .append(" bucket=`").append(fluidBucketIdString(slot)).append("`")
                    .append(" name=\"").append(state.displayName()).append("\"")
                    .append(" material=").append(state.materialFluidId())
                    .append(" color=");
            if (state.customColorRgb() == null) {
                out.append("default");
            } else {
                out.append("#").append(String.format(Locale.ROOT, "%06X", state.customColorRgb()));
            }
            out.append("\n");
        }
        if (!any) {
            out.append("- none\n");
        }
    }

    private static JsonArray writeItems() {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemSlotState state = ITEM_STATES.get(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slotNumber(slot));
            entry.addProperty("active", state.active());
            entry.addProperty("name", state.displayName());
            entry.addProperty("material_item", state.materialItemId());
            entry.addProperty("throwable", state.throwable());
            array.add(entry);
        }
        return array;
    }

    private static JsonArray writeBlocks() {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            BlockSlotState state = BLOCK_STATES.get(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slotNumber(slot));
            entry.addProperty("active", state.active());
            entry.addProperty("name", state.displayName());
            entry.addProperty("material_block", state.materialBlockId());
            entry.addProperty("friction", state.friction());
            array.add(entry);
        }
        return array;
    }

    private static JsonArray writeFluids() {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            FluidSlotState state = FLUID_STATES.get(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slotNumber(slot));
            entry.addProperty("active", state.active());
            entry.addProperty("name", state.displayName());
            entry.addProperty("material_fluid", state.materialFluidId());
            if (state.customColorRgb() != null) {
                entry.addProperty("color", "#" + String.format(Locale.ROOT, "%06X", state.customColorRgb()));
            }
            array.add(entry);
        }
        return array;
    }

    private static void readItems(JsonArray array) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int slot = readSlot(entry);
            if (!isSlotInRange(slot)) {
                continue;
            }
            boolean active = readBoolean(entry, "active", false);
            String name = readString(entry, "name", "Dynamic Item " + slotLabel(slot));
            String material = resolveItemMaterialId(readString(entry, "material_item", DEFAULT_ITEM_MATERIAL), DEFAULT_ITEM_MATERIAL);
            if (material == null) {
                material = DEFAULT_ITEM_MATERIAL;
            }
            boolean throwable = readBoolean(entry, "throwable", false);
            ITEM_STATES.set(slot, new ItemSlotState(active, name, material, throwable));
        }
    }

    private static void readBlocks(JsonArray array) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int slot = readSlot(entry);
            if (!isSlotInRange(slot)) {
                continue;
            }
            boolean active = readBoolean(entry, "active", false);
            String name = readString(entry, "name", "Dynamic Block " + slotLabel(slot));
            String material = resolveBlockMaterialId(readString(entry, "material_block", DEFAULT_BLOCK_MATERIAL), DEFAULT_BLOCK_MATERIAL);
            if (material == null) {
                material = DEFAULT_BLOCK_MATERIAL;
            }
            float friction = clampFriction(readFloat(entry, "friction", defaultFrictionFromMaterial(material)));
            BLOCK_STATES.set(slot, new BlockSlotState(active, name, material, friction));
        }
    }

    private static void readFluids(JsonArray array) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            int slot = readSlot(entry);
            if (!isSlotInRange(slot)) {
                continue;
            }
            boolean active = readBoolean(entry, "active", false);
            String name = readString(entry, "name", "Dynamic Fluid " + slotLabel(slot));
            String material = resolveFluidMaterialId(readString(entry, "material_fluid", DEFAULT_FLUID_MATERIAL), DEFAULT_FLUID_MATERIAL);
            if (material == null) {
                material = DEFAULT_FLUID_MATERIAL;
            }
            Integer color = null;
            if (entry.has("color") && !entry.get("color").isJsonNull()) {
                Integer parsed = parseFluidColorInput(readString(entry, "color", ""), null, material);
                if (parsed != Integer.MIN_VALUE) {
                    color = parsed;
                }
            }
            FLUID_STATES.set(slot, new FluidSlotState(active, name, material, color));
        }
    }

    private static int readSlot(JsonObject entry) {
        if (entry == null || !entry.has("slot")) {
            return -1;
        }
        try {
            return entry.get("slot").getAsInt() - 1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String readString(JsonObject entry, String key, String fallback) {
        if (entry == null || key == null || key.isBlank() || !entry.has(key)) {
            return fallback;
        }
        try {
            String value = entry.get(key).getAsString();
            return value == null || value.isBlank() ? fallback : value;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject entry, String key, boolean fallback) {
        if (entry == null || key == null || key.isBlank() || !entry.has(key)) {
            return fallback;
        }
        try {
            return entry.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float readFloat(JsonObject entry, String key, float fallback) {
        if (entry == null || key == null || key.isBlank() || !entry.has(key)) {
            return fallback;
        }
        try {
            return entry.get(key).getAsFloat();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalizeName(String requested, String existing, String fallback) {
        String candidate = requested == null ? "" : requested.trim();
        if (candidate.isBlank()) {
            candidate = existing == null ? "" : existing.trim();
        }
        if (candidate.isBlank()) {
            candidate = fallback;
        }
        if (candidate.length() > 80) {
            candidate = candidate.substring(0, 80).trim();
        }
        return candidate;
    }

    private static String resolveItemMaterialId(String requested, String fallback) {
        Identifier id = parseVanillaIdentifier(requested, fallback);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return null;
        }
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) {
            return null;
        }
        return id.toString();
    }

    private static String resolveBlockMaterialId(String requested, String fallback) {
        Identifier id = parseVanillaIdentifier(requested, fallback);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            return null;
        }
        return id.toString();
    }

    private static String resolveFluidMaterialId(String requested, String fallback) {
        Identifier id = parseVanillaIdentifier(requested, fallback);
        if (id == null || !Registries.FLUID.containsId(id)) {
            return null;
        }
        Fluid fluid = Registries.FLUID.get(id);
        if (fluid == Fluids.EMPTY) {
            return null;
        }
        return id.toString();
    }

    private static Identifier parseVanillaIdentifier(String requested, String fallback) {
        String raw = requested == null || requested.isBlank() ? fallback : requested.trim();
        if (raw.isBlank()) {
            return null;
        }
        raw = raw.toLowerCase(Locale.ROOT);
        if (!raw.contains(":")) {
            raw = "minecraft:" + raw;
        }
        Identifier id = Identifier.tryParse(raw);
        if (id == null || !"minecraft".equals(id.getNamespace())) {
            return null;
        }
        return id;
    }

    private static Integer parseFluidColorInput(String colorInput, Integer existing, String materialFluidId) {
        if (colorInput == null || colorInput.isBlank()) {
            return existing;
        }
        String raw = colorInput.trim().toLowerCase(Locale.ROOT);
        if ("default".equals(raw) || "none".equals(raw) || "material".equals(raw)) {
            return null;
        }
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() != 6) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(raw, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static float defaultFrictionFromMaterial(String materialBlockId) {
        Block block = resolveBlock(materialBlockId, Blocks.STONE);
        return block.getSlipperiness();
    }

    private static float clampFriction(float value) {
        if (Float.isNaN(value)) {
            return Blocks.STONE.getSlipperiness();
        }
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 2.0F) {
            return 2.0F;
        }
        return value;
    }

    private static Item resolveItem(String idText, Item fallback) {
        Identifier id = Identifier.tryParse(idText == null ? "" : idText);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return fallback;
        }
        Item item = Registries.ITEM.get(id);
        return item == null ? fallback : item;
    }

    private static Block resolveBlock(String idText, Block fallback) {
        Identifier id = Identifier.tryParse(idText == null ? "" : idText);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return fallback;
        }
        Block block = Registries.BLOCK.get(id);
        return block == null ? fallback : block;
    }

    private static Fluid resolveFluid(String idText, Fluid fallback) {
        Identifier id = Identifier.tryParse(idText == null ? "" : idText);
        if (id == null || !Registries.FLUID.containsId(id)) {
            return fallback;
        }
        Fluid fluid = Registries.FLUID.get(id);
        return fluid == null ? fallback : fluid;
    }

    private static boolean isSlotInRange(int slot) {
        return slot >= 0 && slot < SLOT_COUNT;
    }

    private static int slotNumber(int slot) {
        return slot + 1;
    }

    private static String slotLabel(int slot) {
        return String.format(Locale.ROOT, "%03d", slotNumber(slot));
    }

    private static String itemIdString(int slot) {
        return MineClawd.MOD_ID + ":dynamic_item_" + slotLabel(slot);
    }

    private static String blockIdString(int slot) {
        return MineClawd.MOD_ID + ":dynamic_block_" + slotLabel(slot);
    }

    private static String fluidStillIdString(int slot) {
        return MineClawd.MOD_ID + ":dynamic_fluid_" + slotLabel(slot);
    }

    private static String fluidBucketIdString(int slot) {
        return MineClawd.MOD_ID + ":dynamic_fluid_bucket_" + slotLabel(slot);
    }

    private static void resetAllStates() {
        ITEM_STATES.clear();
        BLOCK_STATES.clear();
        FLUID_STATES.clear();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ITEM_STATES.add(defaultItemState(slot));
            BLOCK_STATES.add(defaultBlockState(slot));
            FLUID_STATES.add(defaultFluidState(slot));
        }
    }

    private static ItemSlotState defaultItemState(int slot) {
        return new ItemSlotState(false, "Dynamic Item " + slotLabel(slot), DEFAULT_ITEM_MATERIAL, false);
    }

    private static BlockSlotState defaultBlockState(int slot) {
        return new BlockSlotState(false, "Dynamic Block " + slotLabel(slot), DEFAULT_BLOCK_MATERIAL, defaultFrictionFromMaterial(DEFAULT_BLOCK_MATERIAL));
    }

    private static FluidSlotState defaultFluidState(int slot) {
        return new FluidSlotState(false, "Dynamic Fluid " + slotLabel(slot), DEFAULT_FLUID_MATERIAL, null);
    }

    public record OperationResult(boolean success, String output) {
        public static OperationResult success(String output) {
            return new OperationResult(true, output);
        }

        public static OperationResult error(String output) {
            return new OperationResult(false, output);
        }
    }

    public record ItemSlotState(
            boolean active,
            String displayName,
            String materialItemId,
            boolean throwable
    ) {
    }

    public record BlockSlotState(
            boolean active,
            String displayName,
            String materialBlockId,
            float friction
    ) {
    }

    public record FluidSlotState(
            boolean active,
            String displayName,
            String materialFluidId,
            Integer customColorRgb
    ) {
    }
}
