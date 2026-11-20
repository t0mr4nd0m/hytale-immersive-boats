package org.immersiveboats;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import javax.annotation.Nonnull;

final class BoatModeCommand extends AbstractPlayerCommand {
    private static final String TRANSLATIONS = "immersive_boats.interaction.";
    private final PlayerPreferences<LandInteraction> preferences;

    BoatModeCommand(PlayerPreferences<LandInteraction> preferences) {
        super("boat_mode", TRANSLATIONS + "description");
        this.preferences = preferences;
        requireNoPermission();
        addUsageVariant(new SetInteraction(preferences));
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
        player.sendMessage(Message.translation(TRANSLATIONS + "current")
                .param("mode", preferences.get(player.getUuid()).argument()));
    }

    private static final class SetInteraction extends AbstractPlayerCommand {
        private final PlayerPreferences<LandInteraction> preferences;
        private final RequiredArg<String> mode;
        SetInteraction(PlayerPreferences<LandInteraction> preferences) {
            super(TRANSLATIONS + "setDescription");
            this.preferences = preferences;
            mode = withRequiredArg("mode", TRANSLATIONS + "mode", ArgTypes.STRING);
            requireNoPermission();
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store,
                               @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world) {
            var interaction = LandInteraction.parse(context.get(mode));
            if (interaction == null) {
                player.sendMessage(Message.translation(TRANSLATIONS + "invalid"));
                return;
            }
            try {
                preferences.set(player.getUuid(), interaction);
                player.sendMessage(Message.translation(TRANSLATIONS + "saved").param("mode", interaction.argument()));
            } catch (IOException e) {
                player.sendMessage(Message.translation(TRANSLATIONS + "saveFailed"));
            }
        }
    }
}
