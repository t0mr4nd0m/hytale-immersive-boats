package org.immersiveboats.settings;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

public final class BoatSettingsCommand extends AbstractPlayerCommand {
    private final BoatPlayerSettings playerSettings;
    private final BoatPhysicsSettings physics;

    public BoatSettingsCommand(BoatPlayerSettings playerSettings, BoatPhysicsSettings physics) {
        super("boat_settings", "immersive_boats.settings.description");
        this.playerSettings = playerSettings;
        this.physics = physics;
        requireNoPermission();
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store,
                    new BoatSettingsPage(playerRef, playerSettings, physics));
        }
    }
}
