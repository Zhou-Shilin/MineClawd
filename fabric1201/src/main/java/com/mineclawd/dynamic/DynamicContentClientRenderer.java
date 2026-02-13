package com.mineclawd.dynamic;

import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

public final class DynamicContentClientRenderer {
    private DynamicContentClientRenderer() {
    }

    public static void registerFluidRenderHandlers() {
        if (!DynamicContentRegistry.placeholdersRegistered()) {
            return;
        }
        for (int slot = 0; slot < DynamicContentRegistry.SLOT_COUNT; slot++) {
            Fluid still = DynamicContentRegistry.stillFluid(slot);
            Fluid flowing = DynamicContentRegistry.flowingFluid(slot);
            FluidRenderHandler handler = new SlotFluidRenderHandler(slot);
            FluidRenderHandlerRegistry.INSTANCE.register(still, flowing, handler);
            BlockRenderLayerMap.INSTANCE.putFluids(RenderLayer.getTranslucent(), still, flowing);
        }
    }

    private static final class SlotFluidRenderHandler implements FluidRenderHandler {
        private final int slot;

        private SlotFluidRenderHandler(int slot) {
            this.slot = slot;
        }

        @Override
        public Sprite[] getFluidSprites(BlockRenderView view, BlockPos pos, FluidState state) {
            Fluid material = DynamicContentRegistry.materialFluidForSlot(slot);
            FluidRenderHandler base = FluidRenderHandlerRegistry.INSTANCE.get(material);
            if (base != null && base != this) {
                Sprite[] sprites = base.getFluidSprites(view, pos, state);
                if (sprites != null && sprites.length > 0) {
                    return sprites;
                }
            }
            FluidRenderHandler water = FluidRenderHandlerRegistry.INSTANCE.get(Fluids.WATER);
            if (water != null && water != this) {
                return water.getFluidSprites(view, pos, state);
            }
            return new Sprite[0];
        }

        @Override
        public int getFluidColor(BlockRenderView view, BlockPos pos, FluidState state) {
            Integer custom = DynamicContentRegistry.fluidCustomColor(slot);
            if (custom != null) {
                return 0xFF000000 | (custom & 0xFFFFFF);
            }
            Fluid material = DynamicContentRegistry.materialFluidForSlot(slot);
            FluidRenderHandler base = FluidRenderHandlerRegistry.INSTANCE.get(material);
            if (base != null && base != this) {
                return base.getFluidColor(view, pos, state);
            }
            return 0xFF3F76E4;
        }
    }
}
