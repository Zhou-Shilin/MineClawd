package com.mineclawd.dynamic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.text.MutableText;
import net.minecraft.sound.BlockSoundGroup;

public class DynamicBlock extends Block {
    private final int slot;

    public DynamicBlock(int slot, Settings settings) {
        super(settings);
        this.slot = slot;
    }

    @Override
    public MutableText getName() {
        return DynamicContentRegistry.blockDisplayName(slot);
    }

    @Override
    public float getSlipperiness() {
        return DynamicContentRegistry.blockFriction(slot);
    }

    @Override
    public float getVelocityMultiplier() {
        return DynamicContentRegistry.blockVelocityMultiplier(slot);
    }

    @Override
    public float getJumpVelocityMultiplier() {
        return DynamicContentRegistry.blockJumpVelocityMultiplier(slot);
    }

    @Override
    public float getBlastResistance() {
        return DynamicContentRegistry.blockBlastResistance(slot);
    }

    @Override
    public BlockSoundGroup getSoundGroup(BlockState state) {
        if (DynamicContentRegistry.blockUseMaterialSounds(slot)) {
            Block material = DynamicContentRegistry.materialBlockForSlot(slot);
            if (material != null && material != this) {
                return material.getSoundGroup(material.getDefaultState());
            }
        }
        return super.getSoundGroup(state);
    }
}
