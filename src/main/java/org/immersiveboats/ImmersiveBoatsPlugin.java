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
        var playerSettings = new BoatPlayerSettings(getDataDirectory());
        try {
            playerSettings.load();
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load all player boat settings");
        }
        var physics = new BoatPhysicsSettings(getDataDirectory().resolve("boat-physics.properties"));
        try {
            physics.load();
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Could not load server boat physics settings");
        }
        var registry = getEntityStoreRegistry();
        var wakeState = registry.registerComponent(BoatWakeSystem.WakeState.class, BoatWakeSystem.WakeState::new);
        var inertia = registry.registerComponent(BoatInertiaSystem.Inertia.class, BoatInertiaSystem.Inertia::new);
        registry.registerSystem(new BoatWakeSystem(wakeState, playerSettings));
        registry.registerSystem(new BoatInertiaSystem(inertia, physics));
        getCommandRegistry().registerCommand(new BoatSettingsCommand(playerSettings, physics));
        registry.registerSystem(new BoatPickupSystem(playerSettings));
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
