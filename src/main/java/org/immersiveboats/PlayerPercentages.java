package org.immersiveboats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class PlayerPercentages {
    static final int DEFAULT = 100;

    private final Path file;
    private volatile Map<UUID, Integer> values = Map.of();

    PlayerPercentages(Path file) {
        this.file = file;
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
        var loaded = new HashMap<UUID, Integer>();
        for (var key : properties.stringPropertyNames()) {
            try {
                int percent = Integer.parseInt(properties.getProperty(key));
                if (percent >= 0 && percent < DEFAULT) {
                    loaded.put(UUID.fromString(key), percent);
                }
            } catch (IllegalArgumentException ignored) {
                // Skip malformed entries so the remaining preferences still load.
            }
        }
        values = Map.copyOf(loaded);
    }

    int get(UUID player) {
        return values.getOrDefault(player, DEFAULT);
    }

    synchronized void set(UUID player, int percent) throws IOException {
        if (get(player) == percent) {
            return;
        }
        var updated = new HashMap<>(values);
        if (percent == DEFAULT) {
            updated.remove(player);
        } else {
            updated.put(player, percent);
        }
        var properties = new Properties();
        updated.forEach((uuid, value) -> properties.setProperty(uuid.toString(), value.toString()));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + "-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Percentages by player UUID");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            values = Map.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
