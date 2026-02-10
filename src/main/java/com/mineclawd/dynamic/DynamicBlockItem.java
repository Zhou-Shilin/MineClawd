package com.mineclawd.dynamic;

import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public class DynamicBlockItem extends BlockItem {
    private final int slot;

    public DynamicBlockItem(int slot, Block block, Settings settings) {
        super(block, settings);
        this.slot = slot;
    }

    @Override
    public Text getName(ItemStack stack) {
        return DynamicContentRegistry.blockDisplayName(slot);
    }
}
