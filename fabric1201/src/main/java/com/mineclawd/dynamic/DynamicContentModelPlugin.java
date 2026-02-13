package com.mineclawd.dynamic;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.render.block.BlockModels;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.util.ModelIdentifier;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class DynamicContentModelPlugin {
    private DynamicContentModelPlugin() {
    }

    public static void register() {
        ModelLoadingPlugin.register(pluginContext -> {
            registerDynamicBlockResolvers(pluginContext);
            registerDynamicFluidBlockResolvers(pluginContext);
            pluginContext.resolveModel().register(context -> {
                Identifier id = context.id();
                UnbakedModel dynamicItem = resolveDynamicItemModel(id, context);
                if (dynamicItem != null) {
                    return dynamicItem;
                }
                UnbakedModel dynamicBlockItem = resolveDynamicBlockItemModel(id, context);
                if (dynamicBlockItem != null) {
                    return dynamicBlockItem;
                }
                return resolveDynamicBucketModel(id, context);
            });
        });
    }

    private static void registerDynamicBlockResolvers(ModelLoadingPlugin.Context pluginContext) {
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            DynamicBlock dynamicBlock = DynamicContentRegistry.dynamicBlock(slot);
            int slotIndex = slot;
            if (dynamicBlock == null) {
                continue;
            }
            pluginContext.registerBlockStateResolver(dynamicBlock, context -> {
                Block material = DynamicContentRegistry.materialBlockForSlot(slotIndex);
                if (material == null || material == dynamicBlock) {
                    material = Blocks.STONE;
                }
                BlockState materialState = material.getDefaultState();
                ModelIdentifier modelId = BlockModels.getModelId(materialState);
                UnbakedModel model = context.getOrLoadModel(modelId);
                for (BlockState state : dynamicBlock.getStateManager().getStates()) {
                    context.setModel(state, model);
                }
            });
        }
    }

    private static void registerDynamicFluidBlockResolvers(ModelLoadingPlugin.Context pluginContext) {
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            DynamicFluidBlock dynamicFluidBlock = DynamicContentRegistry.dynamicFluidBlock(slot);
            int slotIndex = slot;
            if (dynamicFluidBlock == null) {
                continue;
            }
            pluginContext.registerBlockStateResolver(dynamicFluidBlock, context -> {
                Fluid materialFluid = DynamicContentRegistry.materialFluidForSlot(slotIndex);
                Block source = isLava(materialFluid) ? Blocks.LAVA : Blocks.WATER;
                for (BlockState state : dynamicFluidBlock.getStateManager().getStates()) {
                    int level = state.get(FluidBlock.LEVEL);
                    BlockState sourceState = source.getDefaultState().with(FluidBlock.LEVEL, level);
                    ModelIdentifier sourceModelId = BlockModels.getModelId(sourceState);
                    UnbakedModel sourceModel = context.getOrLoadModel(sourceModelId);
                    context.setModel(state, sourceModel);
                }
            });
        }
    }

    private static UnbakedModel resolveDynamicItemModel(
            Identifier requestId,
            net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver.Context context
    ) {
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            Identifier dynamicId = DynamicContentRegistry.dynamicItemId(slot);
            if (dynamicId == null) {
                continue;
            }
            if (!isItemModelRequestFor(requestId, dynamicId)) {
                continue;
            }
            Item materialItem = DynamicContentRegistry.materialItemForSlot(slot);
            if (materialItem == null || materialItem == Items.AIR) {
                materialItem = Items.STICK;
            }
            Identifier materialId = Registries.ITEM.getId(materialItem);
            return context.getOrLoadModel(itemModelId(materialId));
        }
        return null;
    }

    private static UnbakedModel resolveDynamicBlockItemModel(
            Identifier requestId,
            net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver.Context context
    ) {
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            Identifier dynamicId = DynamicContentRegistry.dynamicBlockId(slot);
            if (dynamicId == null) {
                continue;
            }
            if (!isItemModelRequestFor(requestId, dynamicId)) {
                continue;
            }
            Block materialBlock = DynamicContentRegistry.materialBlockForSlot(slot);
            if (materialBlock == null || materialBlock == Blocks.AIR) {
                materialBlock = Blocks.STONE;
            }
            Identifier materialBlockId = Registries.BLOCK.getId(materialBlock);
            return context.getOrLoadModel(itemModelId(materialBlockId));
        }
        return null;
    }

    private static UnbakedModel resolveDynamicBucketModel(
            Identifier requestId,
            net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver.Context context
    ) {
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            Identifier dynamicId = DynamicContentRegistry.dynamicFluidBucketId(slot);
            if (dynamicId == null) {
                continue;
            }
            if (!isItemModelRequestFor(requestId, dynamicId)) {
                continue;
            }
            Fluid materialFluid = DynamicContentRegistry.materialFluidForSlot(slot);
            Item bucketItem = isLava(materialFluid) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
            Identifier bucketId = Registries.ITEM.getId(bucketItem);
            return context.getOrLoadModel(itemModelId(bucketId));
        }
        return null;
    }

    private static boolean isItemModelRequestFor(Identifier requestId, Identifier itemId) {
        if (requestId.equals(itemModelId(itemId))) {
            return true;
        }
        if (requestId instanceof ModelIdentifier modelId) {
            return "inventory".equals(modelId.getVariant())
                    && itemId.getNamespace().equals(modelId.getNamespace())
                    && itemId.getPath().equals(modelId.getPath());
        }
        return false;
    }

    private static Identifier itemModelId(Identifier itemId) {
        return Identifier.of(itemId.getNamespace(), "item/" + itemId.getPath());
    }

    private static boolean isLava(Fluid fluid) {
        return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
    }
}
