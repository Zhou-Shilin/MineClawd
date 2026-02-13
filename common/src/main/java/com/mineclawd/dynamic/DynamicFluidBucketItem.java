package com.mineclawd.dynamic;

import net.minecraft.fluid.Fluid;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public class DynamicFluidBucketItem extends BucketItem {
    private final int slot;

    public DynamicFluidBucketItem(int slot, Fluid fluid, Settings settings) {
        super(fluid, settings);
        this.slot = slot;
    }

    @Override
    public Text getName(ItemStack stack) {
        return DynamicContentRegistry.fluidBucketDisplayName(slot);
    }
}
