package org.immersiveboats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

final class PlayerPreferences<T> {

    private final Path file;
    private final T defaultValue;
    private final Function<String, T> parse;
    private final Function<T, String> format;
    private volatile Map<UUID, T> values = Map.of();

    PlayerPreferences(Path file, T defaultValue, Function<String, T> parse, Function<T, String> format) {
        this.file = file;
        this.defaultValue = defaultValue;
        this.parse = parse;
        this.format = format;
    }

    void load(Path previousFile) throws IOException {
        Path source = Files.notExists(file) ? previousFile : file;
        if (Files.notExists(source)) {
            return;
        }
        var properties = new Properties();
        try (var input = Files.newInputStream(source)) {
            properties.load(input);
        }
        var loaded = new HashMap<UUID, T>();
        for (var key : properties.stringPropertyNames()) {
            try {
                T value = parse.apply(properties.getProperty(key));
                if (value != null && !defaultValue.equals(value)) {
                    loaded.put(UUID.fromString(key), value);
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entries so the remaining preferences still load.
            }
        }
        values = Map.copyOf(loaded);
    }

    T get(UUID player) {
        return values.getOrDefault(player, defaultValue);
    }

    synchronized void set(UUID player, T value) throws IOException {
        if (get(player).equals(value)) {
            return;
        }
        var updated = new HashMap<>(values);
        if (defaultValue.equals(value)) {
            updated.remove(player);
        } else {
            updated.put(player, value);
        }
        var properties = new Properties();
        updated.forEach((uuid, preference) -> properties.setProperty(uuid.toString(), format.apply(preference)));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + "-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Preferences by player UUID");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            values = Map.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
