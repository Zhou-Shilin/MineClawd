package com.mineclawd.kubejs;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.stream.Collectors;

public final class KubeJsToolExecutor {
    private KubeJsToolExecutor() {
    }

    public static ToolExecutionResult execute(ServerCommandSource source, String code) {
        if (source == null || source.getServer() == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        String encoded = encodeCode(code);
        CapturingCommandOutput output = new CapturingCommandOutput();
        ServerCommandSource toolSource = source.withOutput(output).withMaxLevel(4);
        int result;
        String command = "_exec_kubejs_internal " + encoded;
        try {
            result = source.getServer()
                    .getCommandManager()
                    .getDispatcher()
                    .execute(command, toolSource);
        } catch (Exception e) {
            return new ToolExecutionResult(false, "Command execution exception: " + formatException(e));
        }
        String text = output.joined();
        boolean success = result > 0;
        if (text.isBlank()) {
            text = success ? "OK (no output)" : "ERROR (no output)";
        }
        return new ToolExecutionResult(success, text);
    }

    private static String encodeCode(String code) {
        if (code == null) {
            return "";
        }
        String normalized = code.replace("\r\n", "\n").replace("\r", "\n");
        String escaped = normalized.replace("\n", "\\n");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(escaped.getBytes(StandardCharsets.UTF_8));
    }

    private static String formatException(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String type = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message;
    }

    public record ToolExecutionResult(boolean success, String output) {
    }

    private static final class CapturingCommandOutput implements CommandOutput {
        private final List<Text> messages = new ArrayList<>();

        @Override
        public void sendMessage(Text message) {
            if (message != null) {
                messages.add(message);
            }
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        public String joined() {
            return messages.stream()
                    .map(Text::getString)
                    .collect(Collectors.joining("\n"))
                    .trim();
        }
    }
}
