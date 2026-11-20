package org.immersiveboats;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

final class PlayerPercentages {
    static final int DEFAULT = 100;

    private final PlayerPreferences<Integer> preferences;

    PlayerPercentages(Path file) {
        preferences = new PlayerPreferences<>(file, DEFAULT, value -> {
            int percent = Integer.parseInt(value);
            return percent >= 0 && percent < DEFAULT ? percent : null;
        }, integer -> integer != null ? integer.toString() : null);
    }

    void load(Path previousFile) throws IOException {
        preferences.load(previousFile);
    }

    int get(UUID player) {
        return preferences.get(player);
    }

    void set(UUID player, int percent) throws IOException {
        preferences.set(player, percent);
    }
}
