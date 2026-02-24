package com.mineclawd.dynamic;

import com.mineclawd.kubejs.KubeJsToolExecutor.ToolExecutionResult;
import net.minecraft.server.command.ServerCommandSource;

public final class DynamicContentToolExecutor {
    private DynamicContentToolExecutor() {
    }

    public static ToolExecutionResult list() {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.listState();
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult listProperties(String type) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.listEditableProperties(type);
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult registerItem(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialItem,
            Boolean throwable,
            Double throwSpeed,
            Double throwInaccuracy,
            Integer throwCooldownTicks,
            Boolean consumeOnThrow,
            Integer maxCount,
            String useAction,
            Integer useTimeTicks,
            String glintMode
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerItem(
                slot,
                name,
                materialItem,
                throwable,
                throwSpeed,
                throwInaccuracy,
                throwCooldownTicks,
                consumeOnThrow,
                maxCount,
                useAction,
                useTimeTicks,
                glintMode
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult registerBlock(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialBlock,
            Double friction,
            Double velocityMultiplier,
            Double jumpVelocityMultiplier,
            Double blastResistance,
            Boolean useMaterialSounds
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerBlock(
                slot,
                name,
                materialBlock,
                friction,
                velocityMultiplier,
                jumpVelocityMultiplier,
                blastResistance,
                useMaterialSounds
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult registerFluid(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialFluid,
            String color,
            Integer tickRate,
            Integer flowSpeed,
            Integer levelDecreasePerBlock,
            Double blastResistance,
            Boolean infinite
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerFluid(
                slot,
                name,
                materialFluid,
                color,
                tickRate,
                flowSpeed,
                levelDecreasePerBlock,
                blastResistance,
                infinite
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult unregister(ServerCommandSource source, String type, Integer slot) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.unregister(type, slot);
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult updateItem(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialItem,
            Boolean throwable,
            Double throwSpeed,
            Double throwInaccuracy,
            Integer throwCooldownTicks,
            Boolean consumeOnThrow,
            Integer maxCount,
            String useAction,
            Integer useTimeTicks,
            String glintMode
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateItem(
                slot,
                name,
                materialItem,
                throwable,
                throwSpeed,
                throwInaccuracy,
                throwCooldownTicks,
                consumeOnThrow,
                maxCount,
                useAction,
                useTimeTicks,
                glintMode
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult updateBlock(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialBlock,
            Double friction,
            Double velocityMultiplier,
            Double jumpVelocityMultiplier,
            Double blastResistance,
            Boolean useMaterialSounds
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateBlock(
                slot,
                name,
                materialBlock,
                friction,
                velocityMultiplier,
                jumpVelocityMultiplier,
                blastResistance,
                useMaterialSounds
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    public static ToolExecutionResult updateFluid(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialFluid,
            String color,
            Integer tickRate,
            Integer flowSpeed,
            Integer levelDecreasePerBlock,
            Double blastResistance,
            Boolean infinite
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateFluid(
                slot,
                name,
                materialFluid,
                color,
                tickRate,
                flowSpeed,
                levelDecreasePerBlock,
                blastResistance,
                infinite
        );
        if (result.success()) {
            persistAndSync(source);
        }
        return new ToolExecutionResult(result.success(), result.output());
    }

    private static void persistAndSync(ServerCommandSource source) {
        if (source == null || source.getServer() == null) {
            return;
        }
        DynamicContentRegistry.savePersistentState(source.getServer());
        DynamicContentRegistry.syncToAll(source.getServer());
    }
}
