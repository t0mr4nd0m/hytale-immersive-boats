package org.immersiveboats;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.io.IOException;
import java.util.logging.Level;
import javax.annotation.Nonnull;

@SuppressWarnings("unused")
public class ImmersiveBoatsPlugin extends JavaPlugin {

    public ImmersiveBoatsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        var volumes = loadPercentagePreference("paddle-volumes.properties");
        var opacities = loadPercentagePreference("foam-opacities.properties");
        var motion = new BoatMotionPreferences(loadMotionPreference("idle-roll.properties"),
                loadMotionPreference("rowing-pitch.properties"));
        var registry = getEntityStoreRegistry();
        var wakeState = registry.registerComponent(BoatWakeSystem.WakeState.class, BoatWakeSystem.WakeState::new);
        registry.registerSystem(new BoatWakeSystem(wakeState, volumes, opacities, motion));
        var interactionFile = getDataDirectory().resolve("land-interactions.properties");
        var interactions = new PlayerPreferences<>(interactionFile, LandInteraction.PICK_UP,
                LandInteraction::parseSaved, LandInteraction::argument);
        try {
            interactions.load(interactionFile);
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load player boat interactions");
        }
        getCommandRegistry().registerCommand(new BoatSettingsCommand(volumes, opacities, interactions, motion));
        registry.registerSystem(new BoatPickupSystem(interactions));
    }

    private PlayerPreferences<Boolean> loadMotionPreference(String fileName) {
        var file = getDataDirectory().resolve(fileName);
        var preference = new PlayerPreferences<>(file, true,
                saved -> "false".equalsIgnoreCase(saved) ? Boolean.FALSE : null, String::valueOf);
        try {
            preference.load(file);
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load player preferences from %s", fileName);
        }
        return preference;
    }

    private PlayerPercentages loadPercentagePreference(String fileName) {
        var preferences = new PlayerPercentages(getDataDirectory().resolve(fileName));
        try {
            preferences.load(getDataDirectory().resolveSibling("org.example_demo").resolve(fileName));
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load player preferences from %s", fileName);
        }
        return preferences;
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("Immersive Boats started");
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("Immersive Boats shut down");
    }
}
