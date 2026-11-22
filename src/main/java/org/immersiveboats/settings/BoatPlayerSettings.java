package org.immersiveboats.settings;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BoatPlayerSettings {
    public static final int MAX_PERCENT = 100;
    private static final Values DEFAULTS = new Values(MAX_PERCENT, MAX_PERCENT, LandInteraction.PICK_UP,
            true, true, true);
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String VOLUME = "rowing-volume";
    private static final String OPACITY = "foam-opacity";
    private static final String LAND_INTERACTION = "land-interaction";
    private static final String IDLE_ROLL = "idle-roll";
    private static final String ROWING_PITCH = "rowing-pitch";
    private static final String SHOW_OARS = "show-oars";
    private static final String SUFFIX = ".properties";

    private final Path playersDirectory;
    private final Map<UUID, Values> values = new ConcurrentHashMap<>();

    public BoatPlayerSettings(Path dataDirectory) {
        playersDirectory = dataDirectory.resolve("players");
    }

    public void load() throws IOException {
        if (!Files.notExists(playersDirectory)) {
            try (var files = Files.newDirectoryStream(playersDirectory, "*" + SUFFIX)) {
                for (var file : files) {
                    String name = file.getFileName().toString();
                    var player = parseUuid(name.substring(0, name.length() - SUFFIX.length()));
                    if (player == null) {
                        continue;
                    }
                    try {
                        values.put(player, decode(read(file)));
                    } catch (IOException | IllegalArgumentException e) {
                        LOGGER.at(Level.WARNING).withCause(e).log("Could not load boat preferences from %s", file);
                    }
                }
            }
        }
    }

    public Values get(UUID player) {
        return values.getOrDefault(player, DEFAULTS);
    }

    synchronized void save(UUID player, Values updated) throws IOException {
        var file = file(player);
        if (!values.containsKey(player) && !Files.notExists(file)) {
            throw new IOException("Could not safely replace unread boat preferences for " + player);
        }
        if (updated.equals(get(player)) && Files.isRegularFile(file)) {
            return;
        }
        write(file, updated);
        values.put(player, updated);
    }

    private Path file(UUID player) {
        return playersDirectory.resolve(player + SUFFIX);
    }

    private static UUID parseUuid(String value) {
        try {
            var uuid = UUID.fromString(value);
            return uuid.toString().equalsIgnoreCase(value) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Properties read(Path file) throws IOException {
        var properties = new Properties();
        try (var input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return properties;
    }

    private static Values decode(Properties properties) {
        var interaction = LandInteraction.parse(properties.getProperty(LAND_INTERACTION));
        return new Values(percent(properties.getProperty(VOLUME)), percent(properties.getProperty(OPACITY)),
                interaction == null ? DEFAULTS.interaction() : interaction,
                enabled(properties, IDLE_ROLL), enabled(properties, ROWING_PITCH), enabled(properties, SHOW_OARS));
    }

    private static int percent(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= MAX_PERCENT ? parsed : MAX_PERCENT;
        } catch (NumberFormatException e) {
            return MAX_PERCENT;
        }
    }

    private static boolean enabled(Properties properties, String key) {
        return !"false".equalsIgnoreCase(properties.getProperty(key));
    }

    private void write(Path file, Values updated) throws IOException {
        var properties = new Properties();
        properties.setProperty(VOLUME, Integer.toString(updated.volume()));
        properties.setProperty(OPACITY, Integer.toString(updated.opacity()));
        properties.setProperty(LAND_INTERACTION, updated.interaction().argument());
        properties.setProperty(IDLE_ROLL, Boolean.toString(updated.idleRoll()));
        properties.setProperty(ROWING_PITCH, Boolean.toString(updated.rowingPitch()));
        properties.setProperty(SHOW_OARS, Boolean.toString(updated.showOars()));
        Files.createDirectories(playersDirectory);
        var temporary = Files.createTempFile(playersDirectory, file.getFileName() + "-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "Immersive Boats player settings");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record Values(int volume, int opacity, LandInteraction interaction, boolean idleRoll, boolean rowingPitch,
                  boolean showOars) {
    }
}
