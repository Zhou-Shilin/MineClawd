package com.mineclawd.dynamic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.state.StateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

import java.util.function.Supplier;

public abstract class DynamicFluid extends FlowableFluid {
    private final int slot;
    private final Supplier<? extends FlowableFluid> stillSupplier;
    private final Supplier<? extends FlowableFluid> flowingSupplier;
    private final Supplier<? extends Item> bucketSupplier;

    protected DynamicFluid(
            int slot,
            Supplier<? extends FlowableFluid> stillSupplier,
            Supplier<? extends FlowableFluid> flowingSupplier,
            Supplier<? extends Item> bucketSupplier
    ) {
        this.slot = slot;
        this.stillSupplier = stillSupplier;
        this.flowingSupplier = flowingSupplier;
        this.bucketSupplier = bucketSupplier;
    }

    @Override
    public Fluid getStill() {
        return stillSupplier.get();
    }

    @Override
    public Fluid getFlowing() {
        return flowingSupplier.get();
    }

    @Override
    public Item getBucketItem() {
        return bucketSupplier.get();
    }

    @Override
    protected boolean isInfinite(World world) {
        return true;
    }

    @Override
    protected void beforeBreakingBlock(WorldAccess world, BlockPos pos, BlockState state) {
        Block.dropStacks(state, world, pos, state.hasBlockEntity() ? world.getBlockEntity(pos) : null);
    }

    @Override
    protected int getFlowSpeed(WorldView world) {
        return DynamicContentRegistry.fluidFlowSpeed(slot);
    }

    @Override
    protected int getLevelDecreasePerBlock(WorldView world) {
        return DynamicContentRegistry.fluidLevelDecreasePerBlock(slot);
    }

    @Override
    public int getTickRate(WorldView world) {
        return DynamicContentRegistry.fluidTickRate(slot);
    }

    @Override
    protected float getBlastResistance() {
        return DynamicContentRegistry.fluidBlastResistance(slot);
    }

    @Override
    protected BlockState toBlockState(FluidState state) {
        DynamicFluidBlock block = DynamicContentRegistry.fluidBlock(slot);
        if (block == null) {
            return Blocks.AIR.getDefaultState();
        }
        return block.getDefaultState().with(FluidBlock.LEVEL, getBlockStateLevel(state));
    }

    @Override
    protected boolean canBeReplacedWith(FluidState state, BlockView world, BlockPos pos, Fluid fluid, Direction direction) {
        return direction == Direction.DOWN && !matchesType(fluid);
    }

    @Override
    public boolean matchesType(Fluid fluid) {
        return fluid == getStill() || fluid == getFlowing();
    }

    public static final class Flowing extends DynamicFluid {
        public Flowing(
                int slot,
                Supplier<? extends FlowableFluid> stillSupplier,
                Supplier<? extends FlowableFluid> flowingSupplier,
                Supplier<? extends Item> bucketSupplier
        ) {
            super(slot, stillSupplier, flowingSupplier, bucketSupplier);
        }

        @Override
        protected void appendProperties(StateManager.Builder<Fluid, FluidState> builder) {
            super.appendProperties(builder);
            builder.add(LEVEL);
        }

        @Override
        public int getLevel(FluidState state) {
            return state.get(LEVEL);
        }

        @Override
        public boolean isStill(FluidState state) {
            return false;
        }
    }

    public static final class Still extends DynamicFluid {
        public Still(
                int slot,
                Supplier<? extends FlowableFluid> stillSupplier,
                Supplier<? extends FlowableFluid> flowingSupplier,
                Supplier<? extends Item> bucketSupplier
        ) {
            super(slot, stillSupplier, flowingSupplier, bucketSupplier);
        }

        @Override
        public int getLevel(FluidState state) {
            return 8;
        }

        @Override
        public boolean isStill(FluidState state) {
            return true;
        }
    }
}
