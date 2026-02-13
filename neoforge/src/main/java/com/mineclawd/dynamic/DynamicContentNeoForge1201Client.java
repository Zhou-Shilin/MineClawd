package com.mineclawd.dynamic;

import com.mineclawd.MineClawd;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DynamicContentNeoForge1201Client {
    private static boolean initialized;

    private DynamicContentNeoForge1201Client() {
    }

    public static synchronized void init(IEventBus modEventBus) {
        if (initialized || modEventBus == null) {
            return;
        }
        initialized = true;
        modEventBus.addListener(DynamicContentNeoForge1201Client::onModifyBakingResult);
    }

    @SuppressWarnings("unchecked")
    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!DynamicContentRegistry.placeholdersRegistered()) {
            return;
        }

        Map<Object, Object> models = (Map<Object, Object>) (Map<?, ?>) event.getModels();
        if (models == null || models.isEmpty()) {
            return;
        }

        Map<String, Object> modelByName = new HashMap<>(models.size());
        Map<String, List<Object>> keysByName = new HashMap<>(models.size());
        for (Map.Entry<Object, Object> entry : models.entrySet()) {
            String modelName = normalizeModelName(entry.getKey());
            if (modelName == null) {
                continue;
            }
            modelByName.putIfAbsent(modelName, entry.getValue());
            keysByName.computeIfAbsent(modelName, ignored -> new ArrayList<>()).add(entry.getKey());
        }

        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            String label = String.format(Locale.ROOT, "%03d", slot + 1);
            patchDynamicBlockStateModels(slot, label, models, modelByName, keysByName);
            patchDynamicFluidBlockStateModels(slot, label, models, modelByName, keysByName);
            patchDynamicItemModels(slot, models, modelByName, keysByName);
            patchDynamicBlockItemModels(slot, models, modelByName, keysByName);
            patchDynamicBucketModels(slot, models, modelByName, keysByName);
        }
    }

    private static void patchDynamicBlockStateModels(
            int slot,
            String label,
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName
    ) {
        Block material = DynamicContentRegistry.materialBlockForSlot(slot);
        if (material == null || material == Blocks.AIR) {
            material = Blocks.STONE;
        }

        String sourceModel = modelNameForState(material.getDefaultState());
        Identifier materialId = Registries.BLOCK.getId(material);
        List<String> sourceCandidates = new ArrayList<>();
        if (sourceModel != null) {
            sourceCandidates.add(sourceModel);
        }
        sourceCandidates.add(materialId + "#");

        replaceModelByCandidates(
                models,
                modelByName,
                keysByName,
                List.of(
                        MineClawd.MOD_ID + ":dynamic_block_" + label + "#",
                        MineClawd.MOD_ID + ":dynamic_block_" + label
                ),
                sourceCandidates
        );
    }

    private static void patchDynamicFluidBlockStateModels(
            int slot,
            String label,
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName
    ) {
        Fluid materialFluid = DynamicContentRegistry.materialFluidForSlot(slot);
        Block sourceBlock = isLava(materialFluid) ? Blocks.LAVA : Blocks.WATER;
        Identifier sourceBlockId = Registries.BLOCK.getId(sourceBlock);

        for (int level = 0; level <= 15; level++) {
            List<String> sourceCandidates = new ArrayList<>();
            try {
                BlockState sourceState = sourceBlock.getDefaultState().with(FluidBlock.LEVEL, level);
                String sourceModel = modelNameForState(sourceState);
                if (sourceModel != null) {
                    sourceCandidates.add(sourceModel);
                }
            } catch (RuntimeException ignored) {
            }
            sourceCandidates.add(sourceBlockId + "#level=" + level);
            sourceCandidates.add(sourceBlockId + "#");

            replaceModelByCandidates(
                    models,
                    modelByName,
                    keysByName,
                    List.of(
                            MineClawd.MOD_ID + ":dynamic_fluid_block_" + label + "#level=" + level
                    ),
                    sourceCandidates
            );
        }
    }

    private static void patchDynamicItemModels(
            int slot,
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName
    ) {
        Identifier dynamicId = DynamicContentRegistry.dynamicItemId(slot);
        if (dynamicId == null) {
            return;
        }

        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material == null || material == Items.AIR) {
            material = Items.STICK;
        }
        Identifier materialId = Registries.ITEM.getId(material);

        replaceModelByCandidates(
                models,
                modelByName,
                keysByName,
                itemModelNameCandidates(dynamicId),
                itemModelNameCandidates(materialId)
        );
    }

    private static void patchDynamicBlockItemModels(
            int slot,
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName
    ) {
        Identifier dynamicId = DynamicContentRegistry.dynamicBlockId(slot);
        if (dynamicId == null) {
            return;
        }

        Block material = DynamicContentRegistry.materialBlockForSlot(slot);
        if (material == null || material == Blocks.AIR) {
            material = Blocks.STONE;
        }
        Identifier materialId = Registries.BLOCK.getId(material);

        replaceModelByCandidates(
                models,
                modelByName,
                keysByName,
                itemModelNameCandidates(dynamicId),
                itemModelNameCandidates(materialId)
        );
    }

    private static void patchDynamicBucketModels(
            int slot,
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName
    ) {
        Identifier dynamicId = DynamicContentRegistry.dynamicFluidBucketId(slot);
        if (dynamicId == null) {
            return;
        }

        Fluid material = DynamicContentRegistry.materialFluidForSlot(slot);
        Item sourceBucket = isLava(material) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        Identifier sourceId = Registries.ITEM.getId(sourceBucket);

        replaceModelByCandidates(
                models,
                modelByName,
                keysByName,
                itemModelNameCandidates(dynamicId),
                itemModelNameCandidates(sourceId)
        );
    }

    private static List<String> itemModelNameCandidates(Identifier id) {
        if (id == null) {
            return List.of();
        }
        String plain = id.toString();
        String itemPath = id.getNamespace() + ":item/" + id.getPath();
        return List.of(
                itemPath,
                itemPath + "#inventory",
                plain + "#inventory",
                plain
        );
    }

    private static void replaceModelByCandidates(
            Map<Object, Object> models,
            Map<String, Object> modelByName,
            Map<String, List<Object>> keysByName,
            List<String> targetCandidates,
            List<String> sourceCandidates
    ) {
        Object sourceModel = resolveSourceModel(modelByName, sourceCandidates);
        if (sourceModel == null) {
            return;
        }

        for (String targetCandidate : targetCandidates) {
            String normalizedTarget = normalizeModelName(targetCandidate);
            if (normalizedTarget == null) {
                continue;
            }
            List<Object> keys = keysByName.get(normalizedTarget);
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            for (Object key : keys) {
                models.put(key, sourceModel);
            }
            modelByName.put(normalizedTarget, sourceModel);
        }
    }

    private static Object resolveSourceModel(Map<String, Object> modelByName, List<String> sourceCandidates) {
        for (String sourceCandidate : sourceCandidates) {
            String normalizedSource = normalizeModelName(sourceCandidate);
            if (normalizedSource == null) {
                continue;
            }
            Object direct = modelByName.get(normalizedSource);
            if (direct != null) {
                return direct;
            }
        }
        for (String sourceCandidate : sourceCandidates) {
            String normalizedSource = normalizeModelName(sourceCandidate);
            if (normalizedSource == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : modelByName.entrySet()) {
                if (entry.getKey().startsWith(normalizedSource)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String modelNameForState(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            return normalizeModelName(BlockModels.getModelId(state));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeModelName(Object modelKey) {
        if (modelKey == null) {
            return null;
        }
        String text = modelKey.toString();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static boolean isLava(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }
}
