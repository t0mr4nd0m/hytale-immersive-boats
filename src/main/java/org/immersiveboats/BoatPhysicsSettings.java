package org.immersiveboats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

final class BoatPhysicsSettings {
    static final String EDIT_PERMISSION = "immersiveboats.settings.physics";
    private static final String ENABLED = "enhanced-physics";

    private final Path file;
    private volatile State state = new State(true);

    BoatPhysicsSettings(Path file) {
        this.file = file;
    }

    State state() {
        return state;
    }

    void load() throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        var properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        state = new State(!"false".equalsIgnoreCase(properties.getProperty(ENABLED)));
    }

    synchronized void setEnabled(boolean enabled) throws IOException {
        if (state.enabled() == enabled) {
            return;
        }
        var properties = new Properties();
        properties.setProperty(ENABLED, Boolean.toString(enabled));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + "-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Immersive Boats server settings");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = new State(enabled);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    record State(boolean enabled) {
    }
}
