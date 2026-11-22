package org.immersiveboats.settings;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class BoatSettingsPage extends InteractiveCustomUIPage<BoatSettingsPage.Input> {
    private static final String TRANSLATIONS = "immersive_boats.settings.";
    private final BoatPlayerSettings playerSettings;
    private final BoatPhysicsSettings physics;
    private boolean editingPhysics;
    private boolean initialPhysics;

    BoatSettingsPage(PlayerRef player, BoatPlayerSettings playerSettings, BoatPhysicsSettings physics) {
        super(player, CustomPageLifetime.CanDismiss, Input.CODEC);
        this.playerSettings = playerSettings;
        this.physics = physics;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        UUID player = playerRef.getUuid();
        var preferences = playerSettings.get(player);
        commands.append("ImmersiveBoats/Settings.ui");
        commands.set("#Heading.Text", Message.translation(TRANSLATIONS + "title"));
        commands.set("#VolumeLabel.Text", Message.translation(TRANSLATIONS + "volume"));
        commands.set("#OpacityLabel.Text", Message.translation(TRANSLATIONS + "opacity"));
        commands.set("#InteractionLabel.Text", Message.translation(TRANSLATIONS + "interaction"));
        commands.set("#IdleRollLabel.Text", Message.translation(TRANSLATIONS + "idleRoll"));
        commands.set("#RowingPitchLabel.Text", Message.translation(TRANSLATIONS + "rowingPitch"));
        commands.set("#ShowOarsLabel.Text", Message.translation(TRANSLATIONS + "showOars"));
        initialPhysics = physics.state().enabled();
        editingPhysics = canEditPhysics(player);
        commands.set("#PhysicsLabel.Text", Message.translation(TRANSLATIONS + "physics"));
        commands.set("#EnhancedPhysics.Value", initialPhysics);
        commands.set("#EnhancedPhysics.Visible", editingPhysics);
        commands.set("#PhysicsStatus.Visible", !editingPhysics);
        commands.set("#PhysicsStatus.Text",
                Message.translation(TRANSLATIONS + (initialPhysics ? "enabled" : "disabled")));
        commands.set("#Apply.Text", Message.translation(TRANSLATIONS + "apply"));
        commands.set("#Cancel.Text", Message.translation(TRANSLATIONS + "cancel"));
        commands.set("#Volume.Value", preferences.volume());
        commands.set("#Opacity.Value", preferences.opacity());
        commands.set("#IdleRoll.Value", preferences.idleRoll());
        commands.set("#RowingPitch.Value", preferences.rowingPitch());
        commands.set("#ShowOars.Value", preferences.showOars());
        commands.set("#Mode.Entries", Arrays.stream(LandInteraction.values())
                .map(mode -> new DropdownEntryInfo(
                        LocalizableString.fromMessageId(TRANSLATIONS + "mode." + mode.argument()), mode.argument()))
                .toList());
        commands.set("#Mode.Value", preferences.interaction().argument());

        var apply = EventData.of("Action", "apply")
                .append("@Volume", "#Volume.Value")
                .append("@Opacity", "#Opacity.Value")
                .append("@Mode", "#Mode.Value")
                .append("@IdleRoll", "#IdleRoll.Value")
                .append("@RowingPitch", "#RowingPitch.Value")
                .append("@ShowOars", "#ShowOars.Value");
        if (editingPhysics) {
            apply.append("@EnhancedPhysics", "#EnhancedPhysics.Value");
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Apply", apply);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#Cancel", EventData.of("Action", "cancel"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull Input input) {
        if ("cancel".equals(input.action)) {
            close();
            return;
        }
        String error = "apply".equals(input.action) ? apply(input) : "invalidAction";
        if (error == null) {
            close();
            return;
        }
        sendUpdate(new UICommandBuilder().set("#Status.Text", Message.translation(TRANSLATIONS + error)));
    }

    @Nullable
    private String apply(Input input) {
        if (isInvalidPercentage(input.volume) || isInvalidPercentage(input.opacity)) {
            return "invalidPercentage";
        }
        var mode = LandInteraction.parse(input.mode);
        if (mode == null) {
            return "invalidMode";
        }
        if (input.idleRoll == null || input.rowingPitch == null || input.showOars == null
                || (editingPhysics && input.enhancedPhysics == null)) {
            return "invalidAction";
        }

        UUID player = playerRef.getUuid();
        boolean changePhysics = editingPhysics && input.enhancedPhysics != initialPhysics;
        if (changePhysics && !canEditPhysics(player)) {
            return "physicsPermission";
        }
        try {
            playerSettings.save(player, new BoatPlayerSettings.Values(input.volume, input.opacity, mode,
                    input.idleRoll, input.rowingPitch, input.showOars));
            if (changePhysics) {
                physics.setEnabled(input.enhancedPhysics);
            }
        } catch (IOException e) {
            return "saveFailed";
        }
        return null;
    }

    private static boolean isInvalidPercentage(Integer value) {
        return value == null || value < 0 || value > BoatPlayerSettings.MAX_PERCENT;
    }

    private static boolean canEditPhysics(UUID player) {
        return PermissionsModule.get().hasPermission(player, BoatPhysicsSettings.EDIT_PERMISSION);
    }

    static final class Input {
        private static final BuilderCodec<Input> CODEC = BuilderCodec.builder(Input.class, Input::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value,
                        data -> data.action).add()
                .append(new KeyedCodec<>("@Volume", Codec.INTEGER), (data, value) -> data.volume = value,
                        data -> data.volume).add()
                .append(new KeyedCodec<>("@Opacity", Codec.INTEGER), (data, value) -> data.opacity = value,
                        data -> data.opacity).add()
                .append(new KeyedCodec<>("@Mode", Codec.STRING), (data, value) -> data.mode = value,
                        data -> data.mode).add()
                .append(new KeyedCodec<>("@IdleRoll", Codec.BOOLEAN), (data, value) -> data.idleRoll = value,
                        data -> data.idleRoll).add()
                .append(new KeyedCodec<>("@RowingPitch", Codec.BOOLEAN), (data, value) -> data.rowingPitch = value,
                        data -> data.rowingPitch).add()
                .append(new KeyedCodec<>("@ShowOars", Codec.BOOLEAN), (data, value) -> data.showOars = value,
                        data -> data.showOars).add()
                .append(new KeyedCodec<>("@EnhancedPhysics", Codec.BOOLEAN),
                        (data, value) -> data.enhancedPhysics = value, data -> data.enhancedPhysics).add()
                .build();

        private String action = "";
        private Integer volume;
        private Integer opacity;
        private String mode;
        private Boolean idleRoll;
        private Boolean rowingPitch;
        private Boolean showOars;
        private Boolean enhancedPhysics;
    }
}
