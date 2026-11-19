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

final class PlayerPercentageCommand extends AbstractPlayerCommand {
    private final String command;
    private final String translations;
    private final PlayerPercentages preferences;

    PlayerPercentageCommand(String command, String translations, PlayerPercentages preferences) {
        super(command, translations + "description");
        this.command = command;
        this.translations = translations;
        this.preferences = preferences;
        requireNoPermission();
        addUsageVariant(new SetPercentage(translations, preferences));
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                           PlayerRef player, World world) {
        player.sendMessage(Message.translation(translations + "current")
                .param("percent", preferences.get(player.getUuid()))
                .param("example", "/" + command + " 50"));
    }

    private static final class SetPercentage extends AbstractPlayerCommand {
        private final PlayerPercentages preferences;
        private final String translations;
        private final RequiredArg<Integer> percent;

        SetPercentage(String translations, PlayerPercentages preferences) {
            super(translations + "setDescription");
            this.translations = translations;
            this.preferences = preferences;
            percent = withRequiredArg("percentage", translations + "percentage", ArgTypes.INTEGER);
            requireNoPermission();
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref,
                               PlayerRef player, World world) {
            int value = context.get(percent);
            if (value < 0 || value > PlayerPercentages.DEFAULT) {
                player.sendMessage(Message.translation(translations + "outOfRange"));
                return;
            }
            try {
                preferences.set(player.getUuid(), value);
                player.sendMessage(Message.translation(translations + "saved").param("percent", value));
            } catch (IOException e) {
                player.sendMessage(Message.translation(translations + "saveFailed"));
            }
        }
    }
}
