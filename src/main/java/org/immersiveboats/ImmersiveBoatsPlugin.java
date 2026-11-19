package org.immersiveboats;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.io.IOException;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class ImmersiveBoatsPlugin extends JavaPlugin {

    public ImmersiveBoatsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        var volumes = registerPreference("rowing_volume", "volume", "paddle-volumes.properties");
        var opacities = registerPreference("rowing_opacity", "opacity", "foam-opacities.properties");
        var registry = getEntityStoreRegistry();
        var wakeState = registry.registerComponent(BoatWakeSystem.WakeState.class, BoatWakeSystem.WakeState::new);
        registry.registerSystem(new BoatWakeSystem(wakeState, volumes, opacities));
    }

    private PlayerPercentages registerPreference(String command, String translationKey, String fileName) {
        var preferences = new PlayerPercentages(getDataDirectory().resolve(fileName));
        try {
            preferences.load(getDataDirectory().resolveSibling("org.example_demo").resolve(fileName));
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load player preferences from %s", fileName);
        }
        getCommandRegistry().registerCommand(
                new PlayerPercentageCommand(command, "immersive_boats." + translationKey + ".", preferences));
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
