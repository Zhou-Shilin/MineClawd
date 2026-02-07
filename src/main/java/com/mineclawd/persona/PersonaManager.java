package com.mineclawd.persona;

import com.mineclawd.MineClawd;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class PersonaManager {
    public static final String DEFAULT_SOUL = "default";
    public static final String YUKI_SOUL = "yuki";
    private static final String FILE_EXTENSION = ".md";
    private static final Pattern OWNER_SANITIZE = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Path soulsRoot;
    private final Path activeRoot;

    public PersonaManager() {
        Path mineclawdRoot = FabricLoader.getInstance().getGameDir().resolve("mineclawd");
        this.soulsRoot = mineclawdRoot.resolve("souls");
        this.activeRoot = soulsRoot.resolve(".active");
        ensureDirectory(soulsRoot);
        ensureDirectory(activeRoot);
        ensureBundledSouls();
    }

    public synchronized List<String> listSoulNames() {
        ensureBundledSouls();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(soulsRoot)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(FILE_EXTENSION))
                    .map(this::stripExtension)
                    .filter(name -> !name.isBlank())
                    .forEach(names::add);
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to list souls in {}: {}", soulsRoot, exception.getMessage());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public synchronized String resolveSoulName(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        String wanted = reference.trim().toLowerCase(Locale.ROOT);
        for (String name : listSoulNames()) {
            if (name.toLowerCase(Locale.ROOT).equals(wanted)) {
                return name;
            }
        }
        return null;
    }

    public synchronized Persona loadActivePersona(String ownerKey) {
        String soulName = getActiveSoulName(ownerKey);
        String content = readSoulContent(soulName);
        if (content == null) {
            soulName = DEFAULT_SOUL;
            content = readSoulContent(DEFAULT_SOUL);
        }
        if (content == null) {
            content = "";
        }
        return new Persona(soulName, content);
    }

    public synchronized String getActiveSoulName(String ownerKey) {
        ensureBundledSouls();
        String selected = readSelectedSoul(ownerKey);
        String resolved = resolveSoulName(selected);
        if (resolved != null) {
            return resolved;
        }
        return DEFAULT_SOUL;
    }

    public synchronized boolean setActiveSoul(String ownerKey, String soulReference) {
        String resolved = resolveSoulName(soulReference);
        if (resolved == null) {
            return false;
        }
        writeSelectedSoul(ownerKey, resolved);
        return true;
    }

    public synchronized String readSoulContent(String soulName) {
        String resolved = resolveSoulName(soulName);
        if (resolved == null) {
            return null;
        }
        Path path = soulsRoot.resolve(resolved + FILE_EXTENSION);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to read soul {}: {}", path, exception.getMessage());
            return null;
        }
    }

    private void ensureBundledSouls() {
        ensureFileIfMissing(DEFAULT_SOUL + FILE_EXTENSION, "");
        ensureFileFromResourceIfMissing(YUKI_SOUL + FILE_EXTENSION, "/mineclawd/souls/yuki.md");
    }

    private void ensureFileFromResourceIfMissing(String fileName, String resourcePath) {
        Path target = soulsRoot.resolve(fileName);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream stream = PersonaManager.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                MineClawd.LOGGER.warn("Bundled soul resource not found: {}", resourcePath);
                return;
            }
            Files.writeString(
                    target,
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (Exception exception) {
            MineClawd.LOGGER.warn("Failed to write bundled soul {}: {}", target, exception.getMessage());
        }
    }

    private void ensureFileIfMissing(String fileName, String content) {
        Path target = soulsRoot.resolve(fileName);
        if (Files.exists(target)) {
            return;
        }
        try {
            Files.writeString(
                    target,
                    content == null ? "" : content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to create default soul {}: {}", target, exception.getMessage());
        }
    }

    private String readSelectedSoul(String ownerKey) {
        Path path = activeRoot.resolve(safeOwner(ownerKey) + ".txt");
        if (!Files.isRegularFile(path)) {
            return DEFAULT_SOUL;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            MineClawd.LOGGER.warn("Failed to read active soul {}: {}", path, exception.getMessage());
            return DEFAULT_SOUL;
        }
    }

    private void writeSelectedSoul(String ownerKey, String soulName) {
        Path path = activeRoot.resolve(safeOwner(ownerKey) + ".txt");
        try {
            Files.writeString(
                    path,
                    soulName + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist active soul: " + path, exception);
        }
    }

    private void ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create soul directory: " + path, exception);
        }
    }

    private String safeOwner(String ownerKey) {
        String value = ownerKey == null ? "" : ownerKey.trim();
        value = OWNER_SANITIZE.matcher(value).replaceAll("_");
        if (value.isBlank()) {
            return "unknown";
        }
        return value;
    }

    private String stripExtension(String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(FILE_EXTENSION)) {
            return name.substring(0, name.length() - FILE_EXTENSION.length());
        }
        return name;
    }

    public record Persona(String name, String content) {
    }
}
