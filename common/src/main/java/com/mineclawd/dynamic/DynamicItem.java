package com.mineclawd.dynamic;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

public class DynamicItem extends Item {
    private final int slot;

    public DynamicItem(int slot, Settings settings) {
        super(settings);
        this.slot = slot;
    }

    @Override
    public Text getName(ItemStack stack) {
        return DynamicContentRegistry.itemDisplayName(slot);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (DynamicContentRegistry.isItemThrowable(slot)) {
            world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.5F, 0.4F / (world.random.nextFloat() * 0.4F + 0.8F));
            if (!world.isClient) {
                SnowballEntity projectile = new SnowballEntity(world, user);
                projectile.setItem(stack.copyWithCount(1));
                projectile.setVelocity(user, user.getPitch(), user.getYaw(), 0.0F, 1.5F, 1.0F);
                world.spawnEntity(projectile);
            }
            user.incrementStat(Stats.USED.getOrCreateStat(this));
            if (!user.getAbilities().creativeMode) {
                stack.decrement(1);
            }
            return TypedActionResult.success(stack, world.isClient());
        }

        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material != null && material != this) {
            return material.use(world, user, hand);
        }
        return super.use(world, user, hand);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material != null && material != this) {
            return material.finishUsing(stack, world, user);
        }
        return super.finishUsing(stack, world, user);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material != null && material != this) {
            return material.getUseAction(stack);
        }
        return super.getUseAction(stack);
    }

    @Override
    public int getMaxUseTime(ItemStack stack) {
        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material != null && material != this) {
            return material.getMaxUseTime(stack);
        }
        return super.getMaxUseTime(stack);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        Item material = DynamicContentRegistry.materialItemForSlot(slot);
        if (material != null && material != this) {
            return material.hasGlint(stack);
        }
        return super.hasGlint(stack);
    }
}
