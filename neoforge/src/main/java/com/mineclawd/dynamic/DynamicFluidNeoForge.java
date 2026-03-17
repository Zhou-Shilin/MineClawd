package com.mineclawd.dynamic;

import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.function.Supplier;

public final class DynamicFluidNeoForge {
    private DynamicFluidNeoForge() {
    }

    public static final class Still extends DynamicFluid.Still {
        public Still(
                int slot,
                Supplier<? extends FlowableFluid> stillSupplier,
                Supplier<? extends FlowableFluid> flowingSupplier,
                Supplier<? extends Item> bucketSupplier
        ) {
            super(slot, stillSupplier, flowingSupplier, bucketSupplier);
        }

        @Override
        public FluidType getFluidType() {
            return resolveType(slot());
        }
    }

    public static final class Flowing extends DynamicFluid.Flowing {
        public Flowing(
                int slot,
                Supplier<? extends FlowableFluid> stillSupplier,
                Supplier<? extends FlowableFluid> flowingSupplier,
                Supplier<? extends Item> bucketSupplier
        ) {
            super(slot, stillSupplier, flowingSupplier, bucketSupplier);
        }

        @Override
        public FluidType getFluidType() {
            return resolveType(slot());
        }
    }

    private static FluidType resolveType(int slot) {
        Fluid materialFluid = DynamicContentRegistry.materialFluidForSlot(slot);
        if (materialFluid != null && materialFluid != Fluids.EMPTY) {
            return materialFluid.getFluidType();
        }
        return Fluids.WATER.getFluidType();
    }
}
