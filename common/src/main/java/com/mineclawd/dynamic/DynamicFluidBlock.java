package com.mineclawd.dynamic;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.text.MutableText;

public class DynamicFluidBlock extends FluidBlock {
    private final int slot;

    public DynamicFluidBlock(int slot, FlowableFluid fluid, AbstractBlock.Settings settings) {
        super(fluid, settings);
        this.slot = slot;
    }

    @Override
    public MutableText getName() {
        return DynamicContentRegistry.fluidBucketDisplayName(slot);
    }
}
