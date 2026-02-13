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

    public static ToolExecutionResult registerItem(
            ServerCommandSource source,
            Integer slot,
            String name,
            String materialItem,
            Boolean throwable
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerItem(slot, name, materialItem, throwable);
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
            Double friction
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerBlock(slot, name, materialBlock, friction);
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
            String color
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.registerFluid(slot, name, materialFluid, color);
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
            Boolean throwable
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateItem(slot, name, materialItem, throwable);
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
            Double friction
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateBlock(slot, name, materialBlock, friction);
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
            String color
    ) {
        DynamicContentRegistry.OperationResult result = DynamicContentRegistry.updateFluid(slot, name, materialFluid, color);
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
