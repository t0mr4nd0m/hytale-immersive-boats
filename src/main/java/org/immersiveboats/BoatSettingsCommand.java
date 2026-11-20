package org.immersiveboats;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

final class BoatSettingsCommand extends AbstractPlayerCommand {
    private final PlayerPercentages volumes;
    private final PlayerPercentages opacities;
    private final PlayerPreferences<LandInteraction> interactions;
    private final BoatMotionPreferences motion;

    BoatSettingsCommand(PlayerPercentages volumes, PlayerPercentages opacities,
                        PlayerPreferences<LandInteraction> interactions, BoatMotionPreferences motion) {
        super("boat_settings", "immersive_boats.settings.description");
        this.volumes = volumes;
        this.opacities = opacities;
        this.interactions = interactions;
        this.motion = motion;
        requireNoPermission();
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        var player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store,
                    new BoatSettingsPage(playerRef, volumes, opacities, interactions, motion));
        }
    }
}
