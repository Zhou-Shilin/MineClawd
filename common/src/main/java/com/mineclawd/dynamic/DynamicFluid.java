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

import java.lang.reflect.Constructor;
import java.util.function.Supplier;

public abstract class DynamicFluid extends FlowableFluid {
    private static final String NEOFORGE_STILL_CLASS = "com.mineclawd.dynamic.DynamicFluidNeoForge$Still";
    private static final String NEOFORGE_FLOWING_CLASS = "com.mineclawd.dynamic.DynamicFluidNeoForge$Flowing";

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
    protected int getMaxFlowDistance(WorldView world) {
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

    protected final int slot() {
        return slot;
    }

    public static Still createStill(
            int slot,
            Supplier<? extends FlowableFluid> stillSupplier,
            Supplier<? extends FlowableFluid> flowingSupplier,
            Supplier<? extends Item> bucketSupplier
    ) {
        Still still = instantiateVariant(
                NEOFORGE_STILL_CLASS,
                Still.class,
                slot,
                stillSupplier,
                flowingSupplier,
                bucketSupplier
        );
        if (still != null) {
            return still;
        }
        return new Still(slot, stillSupplier, flowingSupplier, bucketSupplier);
    }

    public static Flowing createFlowing(
            int slot,
            Supplier<? extends FlowableFluid> stillSupplier,
            Supplier<? extends FlowableFluid> flowingSupplier,
            Supplier<? extends Item> bucketSupplier
    ) {
        Flowing flowing = instantiateVariant(
                NEOFORGE_FLOWING_CLASS,
                Flowing.class,
                slot,
                stillSupplier,
                flowingSupplier,
                bucketSupplier
        );
        if (flowing != null) {
            return flowing;
        }
        return new Flowing(slot, stillSupplier, flowingSupplier, bucketSupplier);
    }

    private static <T extends DynamicFluid> T instantiateVariant(
            String className,
            Class<T> expectedType,
            int slot,
            Supplier<? extends FlowableFluid> stillSupplier,
            Supplier<? extends FlowableFluid> flowingSupplier,
            Supplier<? extends Item> bucketSupplier
    ) {
        try {
            Class<?> raw = Class.forName(className);
            if (!expectedType.isAssignableFrom(raw)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Class<? extends T> variantClass = (Class<? extends T>) raw;
            Constructor<? extends T> ctor = variantClass.getConstructor(int.class, Supplier.class, Supplier.class, Supplier.class);
            return ctor.newInstance(slot, stillSupplier, flowingSupplier, bucketSupplier);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    public static class Flowing extends DynamicFluid {
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

    public static class Still extends DynamicFluid {
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
