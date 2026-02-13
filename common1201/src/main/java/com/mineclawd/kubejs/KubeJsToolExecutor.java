package com.mineclawd.kubejs;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

public final class KubeJsToolExecutor {
    private static final int MAX_FILE_CHARS = 64_000;
    private static final int MAX_LIST_RESULTS = 200;
    private static final int MAX_ERROR_LINES = 120;
    private static final String KUBEJS_PREFIX = "kubejs/server_scripts/mineclawd/";

    private KubeJsToolExecutor() {
    }

    public static ToolExecutionResult executeInstant(ServerCommandSource source, String code) {
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

    public static ToolExecutionResult executeCommand(ServerCommandSource source, String commandText) {
        if (source == null || source.getServer() == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        String command = normalizeCommand(commandText);
        if (command.isBlank()) {
            return new ToolExecutionResult(false, "Command is empty.");
        }
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if ("mineclawd".equals(root) || "mclawd".equals(root) || "_exec_kubejs_internal".equals(root)) {
            return new ToolExecutionResult(false, "This command is blocked by MineClawd for safety: " + root);
        }
        if ("reload".equals(root)) {
            return new ToolExecutionResult(false, "Use the `reload-game` tool instead so KubeJS load errors can be captured.");
        }

        CapturingCommandOutput output = new CapturingCommandOutput();
        ServerCommandSource toolSource = source.withOutput(output).withMaxLevel(4);
        int result;
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
        StringBuilder out = new StringBuilder();
        out.append("command: /").append(command).append("\n");
        out.append("result: ").append(result).append("\n");
        if (text.isBlank()) {
            out.append("output: ").append(success ? "OK (no output)" : "ERROR (no output)");
        } else {
            out.append("output:\n").append(text.trim());
        }
        return new ToolExecutionResult(success, out.toString().trim());
    }

    public static ToolExecutionResult listServerScripts(ServerCommandSource source) {
        Path root = scriptsRoot(source);
        if (root == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        try {
            Files.createDirectories(root);
            List<Path> files = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            files.sort((a, b) -> relativePath(root, a).compareToIgnoreCase(relativePath(root, b)));
            if (files.isEmpty()) {
                return new ToolExecutionResult(true, "No files found under " + KUBEJS_PREFIX);
            }

            StringBuilder out = new StringBuilder();
            out.append("Files under ").append(KUBEJS_PREFIX).append("\n");
            int count = 0;
            for (Path file : files) {
                count++;
                if (count > MAX_LIST_RESULTS) {
                    out.append("... truncated at ").append(MAX_LIST_RESULTS).append(" files.\n");
                    break;
                }
                String relative = relativePath(root, file);
                long size = 0L;
                FileTime modified = FileTime.fromMillis(0L);
                try {
                    size = Files.size(file);
                    modified = Files.getLastModifiedTime(file);
                } catch (IOException ignored) {
                }
                out.append("- ").append(relative)
                        .append(" (").append(size).append(" bytes, modified ").append(modified).append(")\n");
            }
            out.append("Total files: ").append(files.size());
            return new ToolExecutionResult(true, out.toString().trim());
        } catch (IOException e) {
            return new ToolExecutionResult(false, "Failed to list scripts: " + formatException(e));
        }
    }

    public static ToolExecutionResult readServerScript(ServerCommandSource source, String relativePath) {
        Path root = scriptsRoot(source);
        if (root == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        Path file = resolveScriptPath(root, relativePath);
        if (file == null) {
            return new ToolExecutionResult(false, "Invalid path. Use a relative path like `features/mobs.js`.");
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return new ToolExecutionResult(false, "File not found: " + normalizedDisplayPath(root, file));
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean truncated = false;
            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS);
                truncated = true;
            }
            StringBuilder out = new StringBuilder();
            out.append("FILE ").append(normalizedDisplayPath(root, file)).append("\n");
            if (truncated) {
                out.append("WARNING: content truncated to ").append(MAX_FILE_CHARS).append(" chars.\n");
            }
            out.append("-----BEGIN CONTENT-----\n");
            out.append(content);
            if (!content.endsWith("\n")) {
                out.append("\n");
            }
            out.append("-----END CONTENT-----");
            return new ToolExecutionResult(true, out.toString());
        } catch (IOException e) {
            return new ToolExecutionResult(false, "Failed to read file: " + formatException(e));
        }
    }

    public static ToolExecutionResult writeServerScript(ServerCommandSource source, String relativePath, String content) {
        Path root = scriptsRoot(source);
        if (root == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        Path file = resolveScriptPath(root, relativePath);
        if (file == null) {
            return new ToolExecutionResult(false, "Invalid path. Use a relative path like `features/mobs.js`.");
        }
        if (content == null) {
            content = "";
        }
        try {
            Files.createDirectories(root);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            long size = Files.size(file);
            return new ToolExecutionResult(true, "Wrote " + normalizedDisplayPath(root, file) + " (" + size + " bytes).");
        } catch (IOException e) {
            return new ToolExecutionResult(false, "Failed to write file: " + formatException(e));
        }
    }

    public static ToolExecutionResult deleteServerScript(ServerCommandSource source, String relativePath) {
        Path root = scriptsRoot(source);
        if (root == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        Path file = resolveScriptPath(root, relativePath);
        if (file == null) {
            return new ToolExecutionResult(false, "Invalid path. Use a relative path like `features/mobs.js`.");
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return new ToolExecutionResult(false, "File not found: " + normalizedDisplayPath(root, file));
        }
        try {
            Files.delete(file);
            return new ToolExecutionResult(true, "Deleted " + normalizedDisplayPath(root, file) + ".");
        } catch (IOException e) {
            return new ToolExecutionResult(false, "Failed to delete file: " + formatException(e));
        }
    }

    public static ToolExecutionResult reloadGame(ServerCommandSource source) {
        if (source == null || source.getServer() == null) {
            return new ToolExecutionResult(false, "No server available.");
        }

        Path runDir = source.getServer().getRunDirectory().toPath();
        Map<Path, Long> logOffsets = snapshotLogOffsets(runDir);
        CapturingCommandOutput output = new CapturingCommandOutput();
        ServerCommandSource toolSource = source.withOutput(output).withMaxLevel(4);
        int result;
        try {
            result = source.getServer()
                    .getCommandManager()
                    .getDispatcher()
                    .execute("reload", toolSource);
        } catch (Exception e) {
            return new ToolExecutionResult(false, "Reload command exception: " + formatException(e));
        }

        String commandOutput = output.joined();
        List<String> kubeErrors = collectKubeErrors(runDir, logOffsets);
        StringBuilder out = new StringBuilder();
        out.append("reload command result: ").append(result > 0 ? "success" : "failed").append("\n");
        if (commandOutput.isBlank()) {
            out.append("command output: (none)\n");
        } else {
            out.append("command output:\n").append(commandOutput.trim()).append("\n");
        }
        if (kubeErrors.isEmpty()) {
            out.append("kubejs errors: none detected in new log output.");
        } else {
            out.append("kubejs errors detected:\n");
            int count = 0;
            for (String line : kubeErrors) {
                count++;
                if (count > MAX_ERROR_LINES) {
                    out.append("... truncated at ").append(MAX_ERROR_LINES).append(" lines.");
                    break;
                }
                out.append(line).append("\n");
            }
        }

        boolean success = result > 0 && kubeErrors.isEmpty();
        return new ToolExecutionResult(success, out.toString().trim());
    }

    public static ToolExecutionResult syncCommandTree(ServerCommandSource source) {
        if (source == null || source.getServer() == null) {
            return new ToolExecutionResult(false, "No server available.");
        }
        int refreshed = 0;
        try {
            var server = source.getServer();
            var commandManager = server.getCommandManager();
            for (var player : server.getPlayerManager().getPlayerList()) {
                commandManager.sendCommandTree(player);
                refreshed++;
            }
            return new ToolExecutionResult(true, "Refreshed command tree for " + refreshed + " online player(s).");
        } catch (Exception e) {
            return new ToolExecutionResult(false, "Failed to sync command tree: " + formatException(e));
        }
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

    private static String normalizeCommand(String commandText) {
        if (commandText == null) {
            return "";
        }
        String command = commandText.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    private static Path scriptsRoot(ServerCommandSource source) {
        if (source == null || source.getServer() == null) {
            return null;
        }
        return source.getServer()
                .getRunDirectory()
                .toPath()
                .resolve("kubejs")
                .resolve("server_scripts")
                .resolve("mineclawd")
                .normalize();
    }

    private static Path resolveScriptPath(Path root, String relative) {
        if (root == null || relative == null) {
            return null;
        }
        String candidate = relative.trim().replace('\\', '/');
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isBlank() || candidate.contains("..") || candidate.contains(":")) {
            return null;
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    private static String relativePath(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        Path rel = root.relativize(file);
        return rel.toString().replace('\\', '/');
    }

    private static String normalizedDisplayPath(Path root, Path file) {
        String rel = relativePath(root, file);
        if (rel.isBlank()) {
            return KUBEJS_PREFIX;
        }
        return KUBEJS_PREFIX + rel;
    }

    private static Map<Path, Long> snapshotLogOffsets(Path runDir) {
        Map<Path, Long> offsets = new HashMap<>();
        List<Path> logs = List.of(
                runDir.resolve("logs").resolve("latest.log"),
                runDir.resolve("kubejs").resolve("logs").resolve("server.txt"),
                runDir.resolve("kubejs").resolve("logs").resolve("server.log")
        );
        for (Path log : logs) {
            offsets.put(log, currentSize(log));
        }
        return offsets;
    }

    private static long currentSize(Path path) {
        try {
            if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
                return Files.size(path);
            }
        } catch (IOException ignored) {
        }
        return 0L;
    }

    private static List<String> collectKubeErrors(Path runDir, Map<Path, Long> offsets) {
        if (runDir == null) {
            return List.of();
        }
        Map<Path, Long> safeOffsets = offsets == null ? Map.of() : offsets;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<Path> logs = List.of(
                runDir.resolve("logs").resolve("latest.log"),
                runDir.resolve("kubejs").resolve("logs").resolve("server.txt"),
                runDir.resolve("kubejs").resolve("logs").resolve("server.log")
        );

        for (int attempt = 0; attempt < 6; attempt++) {
            for (Path path : logs) {
                long offset = safeOffsets.getOrDefault(path, 0L);
                String delta = readDelta(path, offset);
                if (delta.isBlank()) {
                    continue;
                }
                extractErrorLines(delta, result);
            }
            if (!result.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(80L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new ArrayList<>(result);
    }

    private static String readDelta(Path path, long offset) {
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            return "";
        }
        long start = Math.max(0L, offset);
        long size = currentSize(path);
        if (size <= start) {
            return "";
        }
        long length = size - start;
        if (length > 512_000L) {
            start = size - 512_000L;
            length = 512_000L;
        }
        byte[] data = new byte[(int) length];
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(start);
            raf.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void extractErrorLines(String text, LinkedHashSet<String> result) {
        if (text == null || text.isBlank() || result == null) {
            return;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        boolean takingStack = false;
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                takingStack = false;
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            boolean directError = isKubeErrorLine(lower);
            boolean stackLine = takingStack && isStackLikeLine(line, lower);
            if (directError || stackLine) {
                result.add(cleanLogPrefix(line));
                takingStack = directError;
            } else if (line.startsWith("[")) {
                takingStack = false;
            }
            if (result.size() >= MAX_ERROR_LINES) {
                return;
            }
        }
    }

    private static boolean isKubeErrorLine(String lower) {
        if (lower == null || lower.isBlank()) {
            return false;
        }
        if (lower.contains("with 0 errors")
                || lower.contains("0 errors and 0 warnings")
                || lower.contains("reloaded with no kubejs errors")) {
            return false;
        }
        boolean mentionsKube = lower.contains("kubejs")
                || lower.contains("server_scripts:")
                || lower.contains("startup_scripts:")
                || lower.contains("mineclawd/");
        if (!mentionsKube) {
            return false;
        }
        return lower.contains(" error")
                || lower.contains("exception")
                || lower.contains("failed")
                || lower.contains("syntaxerror")
                || lower.contains("referenceerror")
                || lower.contains("typeerror");
    }

    private static boolean isStackLikeLine(String line, String lower) {
        return line.startsWith("at ")
                || line.startsWith("\tat ")
                || line.startsWith("Caused by:")
                || lower.contains("rhino")
                || lower.contains("script")
                || lower.contains("line ");
    }

    private static String cleanLogPrefix(String line) {
        if (line == null) {
            return "";
        }
        int marker = line.indexOf("]: ");
        if (marker >= 0 && marker + 3 < line.length()) {
            return line.substring(marker + 3).trim();
        }
        return line.trim();
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
