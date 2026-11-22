package org.immersiveboats.boat;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.vehicle.BoatComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.immersiveboats.settings.BoatPlayerSettings;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;

public final class BoatWakeSystem extends DelayedEntitySystem<EntityStore> {
    private static final String PARTICLE = "ImmersiveBoats_Boat_Wake";
    private static final String PADDLE_SOUND = "SFX_Water_MoveOut";
    private static final String PADDLE_SPLASH = "ImmersiveBoats_Boat_Paddle_Splash";
    private static final String PADDLE_PITCH_MODEL = "ImmersiveBoats_Boat_Pitch";
    private static final String ROWING_PHASE_MODEL = "ImmersiveBoats_Boat_Rowing_Phases";
    private static final String OARS_MODEL = "Items/Vehicles/ImmersiveBoats_Boat_Oars.blockymodel";
    private static final String OARS_TEXTURE = "Items/Vehicles/ImmersiveBoats_Boat_Oars.png";
    private static final double MODEL_UNITS_PER_BLOCK = 32;
    private static final double OAR_SPLASH_DISTANCE_FACTOR = 0.5;
    private static final double OAR_PIVOT_X = 17;
    private static final double OAR_PIVOT_Y = 13;
    private static final double OAR_PIVOT_Z = -5;
    private static final double OAR_SHAFT_OFFSET = 3.5;
    private static final double OAR_BLADE_START = OAR_SHAFT_OFFSET + 20;
    private static final double OAR_BLADE_END = OAR_SHAFT_OFFSET + 39;
    private static final double MODEL_BASE_Z = 1.75;
    private static final double OAR_CATCH_DIP = Math.toRadians(24);
    private static final double MIN_PADDLE_INTERVAL = 0.72;
    private static final double MAX_PADDLE_INTERVAL = 0.98;
    private static final double MIN_PADDLE_PITCH = 0.96;
    private static final double MAX_PADDLE_PITCH = 1.04;
    private static final float PADDLE_VOLUME = 0.45f;
    private static final double SLOW_PADDLE_SPEED = 1.0;
    private static final double FAST_PADDLE_SPEED = 6.0;
    private static final double SLOW_PADDLE_RATE = 0.75;
    private static final double FAST_PADDLE_RATE = 1.30;
    private static final float SLOW_SPLASH_SCALE = 0.75f;
    private static final float FAST_SPLASH_SCALE = 1.20f;
    private static final double HULL_END_FOAM_OFFSET = 1.0;
    private static final double SPEED_SMOOTHING_SECONDS = 0.35;
    private static final double MIN_TRAVEL = 0.04;
    private static final double MAX_TRAVEL = 3.0;
    private static final double MIN_SIDE_TRAVEL = 0.035;
    private static final double MIN_TURN = Math.toRadians(1.5);
    private static final double LEADING_FOAM_HALF_WIDTH = 0.45;
    private static final double SIDE_OFFSET = 1.0;
    private static final double TURN_OFFSET = 0.65;
    private static final float IDLE_DELAY_SECONDS = 0.5f;
    private static final double MAX_IDLE_SPEED = 0.05;
    private static final double MAX_IDLE_TURN_SPEED = Math.toRadians(2);

    private final ComponentType<EntityStore, WakeState> wakeState;
    private final BoatPlayerSettings playerSettings;
    private final Query<EntityStore> query = Query.and(
            BoatComponent.getComponentType(), TransformComponent.getComponentType());

    public BoatWakeSystem(ComponentType<EntityStore, WakeState> wakeState, BoatPlayerSettings playerSettings) {
        super(0.10f);
        this.wakeState = wakeState;
        this.playerSettings = playerSettings;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
        var boat = chunk.getReferenceTo(index);
        var state = chunk.getComponent(index, wakeState);

        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        var position = transform.getPosition();
        double yaw = transform.getRotation().yaw();
        var driver = getDriver(chunk.getComponent(index, MountedByComponent.getComponentType()), commands);
        var driverPlayer = driver == null ? null : commands.getComponent(driver, PlayerRef.getComponentType());
        var preferences = driverPlayer == null ? null : playerSettings.get(driverPlayer.getUuid());
        boolean showOars = preferences != null && preferences.showOars();
        var model = chunk.getComponent(index, ModelComponent.getComponentType());
        boolean modelChanged = updateBoatModel(boat, model, showOars, commands);
        if (state == null) {
            state = new WakeState();
            state.previous.set(position);
            state.previousYaw = yaw;
            commands.addComponent(boat, wakeState, state);
            return;
        }

        double dx = position.x - state.previous.x;
        double dz = position.z - state.previous.z;
        double dy = position.y - state.previous.y;
        double turn = Math.IEEEremainder(yaw - state.previousYaw, 2 * Math.PI);
        state.previous.set(position);
        state.previousYaw = yaw;
        double travel = Math.hypot(dx, dz);
        double headingX = Math.sin(yaw);
        double headingZ = Math.cos(yaw);
        var chunks = store.getExternalData().getWorld().getChunkStore();
        boolean still = Math.hypot(travel, dy) <= MAX_IDLE_SPEED * dt
                && Math.abs(turn) <= MAX_IDLE_TURN_SPEED * dt;
        boolean idleRoll = preferences == null || preferences.idleRoll();
        state.idleTime = idleRoll && still ? Math.min(IDLE_DELAY_SECONDS, state.idleTime + dt) : 0;
        boolean idle = state.idleTime >= IDLE_DELAY_SECONDS
                && hullOverWater(chunks, position, yaw, model, chunk.getComponent(index, BoundingBox.getComponentType()));
        boolean rockingWhileRowing = preferences != null && preferences.rowingPitch();
        if (idle || driverPlayer == null) {
            state.rowing.resetMotion();
        }
        if (idle || driverPlayer == null || modelChanged || (!showOars && !rockingWhileRowing)) {
            stopRowingAnimation(boat, state, commands);
        }
        updateIdleRoll(boat, state, idle, driverPlayer == null, commands);

        if (idle) {
            if (driverPlayer == null) {
                state.paddleSpeed = 0;
                state.paddleCooldown = 0;
                state.paddleSide = 1;
            }
            return;
        }
        if (travel > MAX_TRAVEL || Math.abs(dy) > 1.0) {
            state.paddleSpeed = 0;
            state.rowing.resetMotion();
            stopRowingAnimation(boat, state, commands);
            return;
        }
        double speed = travel / dt;
        double blend = -Math.expm1(-dt / SPEED_SMOOTHING_SECONDS);
        state.paddleSpeed += (speed - state.paddleSpeed) * blend;
        double speedFraction = Math.clamp((state.paddleSpeed - SLOW_PADDLE_SPEED)
                / (FAST_PADDLE_SPEED - SLOW_PADDLE_SPEED), 0.0, 1.0);
        float paddleElapsed = (float) (dt * (SLOW_PADDLE_RATE
                + speedFraction * (FAST_PADDLE_RATE - SLOW_PADDLE_RATE)));
        state.paddleCooldown = Math.max(-paddleElapsed, state.paddleCooldown - paddleElapsed);

        double sideZ = -headingX;
        double longitudinalTravel = dx * headingX + dz * headingZ;
        double minTravel = driverPlayer == null ? MAX_IDLE_SPEED * dt : MIN_TRAVEL;
        double minSideTravel = driverPlayer == null ? minTravel : MIN_SIDE_TRAVEL;
        boolean advancing = Math.abs(longitudinalTravel) >= minTravel;
        double sideTravel = dx * headingZ + dz * sideZ;
        boolean turning = Math.abs(turn) >= (driverPlayer == null ? MAX_IDLE_TURN_SPEED * dt : MIN_TURN);
        boolean coasting = driverPlayer != null && state.rowing.sampleMotion(travel, turn, dt);
        boolean moving = advancing || turning || Math.abs(sideTravel) >= minSideTravel;
        if (!moving && !state.rowing.active()) {
            return;
        }

        double surface = waterSurface(chunks, position.x, position.y, position.z);
        if (Double.isNaN(surface)) {
            state.rowing.resetMotion();
            stopRowingAnimation(boat, state, commands);
            return;
        }

        if (!modelChanged && (showOars || rockingWhileRowing)) {
            double bankTurn = Math.abs(turn) >= MIN_TURN && Math.abs(turn) <= Math.PI / 2 ? turn : 0;
            // The model is rendered half a turn from the entity yaw, so the bow points against heading.
            boolean reversing = longitudinalTravel > 0;
            boolean canPlay = model != null && commands.getComponent(boat, NetworkId.getComponentType()) != null;
            var update = state.rowing.advance(advancing, reversing, coasting, rockingWhileRowing, showOars, bankTurn,
                    speedFraction, dt, animation -> canPlay && model.getModel().getAnimationSetMap().containsKey(animation));
            if (update.stopped()) {
                sendRowingStop(boat, commands);
            } else if (update.playback() != null) {
                AnimationUtils.playAnimation(boat, AnimationSlot.ServerAction, update.playback(), true, commands);
            }
            if (model != null && update.splash() != null) {
                var splash = update.splash();
                float scale = model.getModel().getScale();
                boolean splashed = false;
                if (splash.usesOar(-1)) {
                    splashed = emitOarSplash(chunks, position, yaw, scale, splash, -1, speedFraction, commands);
                }
                if (splash.usesOar(1)) {
                    splashed |= emitOarSplash(chunks, position, yaw, scale, splash, 1, speedFraction, commands);
                }
                if (splashed) {
                    playPaddleSound(new Vector3d(position.x, surface + 0.05, position.z), commands);
                }
            }
        }
        if (!moving) {
            return;
        }
        if (driverPlayer == null && state.paddleCooldown <= 0) {
            var random = ThreadLocalRandom.current();
            double along = 0;
            double across = state.paddleSide * SIDE_OFFSET;
            if (advancing && Math.abs(longitudinalTravel) >= Math.abs(sideTravel)) {
                along = Math.copySign(HULL_END_FOAM_OFFSET, longitudinalTravel);
                across = 0;
            } else if (Math.abs(sideTravel) >= minSideTravel) {
                across = Math.copySign(SIDE_OFFSET, sideTravel);
            } else {
                along = Math.copySign(TURN_OFFSET, turn) * state.paddleSide;
            }
            double splashX = position.x + headingX * along + headingZ * across;
            double splashZ = position.z + headingZ * along + sideZ * across;
            double splashSurface = waterSurface(chunks, splashX, position.y, splashZ);
            if (!Double.isNaN(splashSurface)) {
                var splashPosition = new Vector3d(splashX, splashSurface + 0.05, splashZ);
                playPaddleSound(splashPosition, commands);
                spawnFoam(PARTICLE, splashPosition, 0, paddleSplashScale(speedFraction), commands);
            }
            state.paddleSide = -state.paddleSide;
            state.paddleCooldown = (float) random.nextDouble(MIN_PADDLE_INTERVAL, MAX_PADDLE_INTERVAL)
                    + state.paddleCooldown;
        }

        if (advancing) {
            double foamOffset = Math.copySign(HULL_END_FOAM_OFFSET, longitudinalTravel);
            double foamX = position.x + headingX * foamOffset;
            double foamZ = position.z + headingZ * foamOffset;
            emitFoam(chunks, commands, foamX - headingZ * LEADING_FOAM_HALF_WIDTH,
                    position.y, foamZ - sideZ * LEADING_FOAM_HALF_WIDTH);
            emitFoam(chunks, commands, foamX + headingZ * LEADING_FOAM_HALF_WIDTH,
                    position.y, foamZ + sideZ * LEADING_FOAM_HALF_WIDTH);
        }

        // As the hull pivots, opposite ends sweep water on opposite sides.
        double turnOffset = turning ? Math.copySign(TURN_OFFSET, turn) : 0;
        if (turning || sideTravel >= minSideTravel) {
            emitFoam(chunks, commands, position.x + headingZ * SIDE_OFFSET + headingX * turnOffset,
                    position.y, position.z + sideZ * SIDE_OFFSET + headingZ * turnOffset);
        }
        if (turning || sideTravel <= -minSideTravel) {
            emitFoam(chunks, commands, position.x - headingZ * SIDE_OFFSET - headingX * turnOffset,
                    position.y, position.z - sideZ * SIDE_OFFSET - headingZ * turnOffset);
        }
    }

    private static void stopRowingAnimation(Ref<EntityStore> boat, WakeState state, CommandBuffer<EntityStore> commands) {
        if (state.rowing.stop()) {
            sendRowingStop(boat, commands);
        }
    }

    private static void sendRowingStop(Ref<EntityStore> boat, CommandBuffer<EntityStore> commands) {
        if (commands.getComponent(boat, NetworkId.getComponentType()) != null) {
            AnimationUtils.stopAnimation(boat, AnimationSlot.ServerAction, true, commands);
        }
    }

    private void updateIdleRoll(Ref<EntityStore> boat, WakeState state, boolean idle, boolean unoccupied,
                                CommandBuffer<EntityStore> commands) {
        if (!idle && state.idleRollViewers.isEmpty()) {
            return;
        }
        var visibility = commands.getComponent(boat, EntityTrackerSystems.Visible.getComponentType());
        var networkId = commands.getComponent(boat, NetworkId.getComponentType());
        if (visibility == null || networkId == null) {
            state.idleRollViewers.clear();
            return;
        }

        state.idleRollViewers.retainAll(visibility.visibleTo.keySet());
        if (idle) {
            var model = commands.getComponent(boat, ModelComponent.getComponentType());
            if (model == null || !model.getModel().getAnimationSetMap().containsKey(BoatAnimation.IDLE_ROLL_ANIMATION)) {
                idle = false;
            }
        }
        for (var viewer : visibility.visibleTo.keySet()) {
            var player = viewer.isValid() ? commands.getComponent(viewer, PlayerRef.getComponentType()) : null;
            if (player == null) {
                continue;
            }
            boolean shouldRoll = idle && (!unoccupied || playerSettings.get(player.getUuid()).idleRoll());
            if (shouldRoll == state.idleRollViewers.contains(viewer)) {
                continue;
            }
            String animation = shouldRoll ? BoatAnimation.IDLE_ROLL_ANIMATION : null;
            player.getPacketHandler().writeNoCache(
                    new PlayAnimation(networkId.getId(), null, animation, AnimationSlot.ServerAction));
            if (shouldRoll) {
                state.idleRollViewers.add(viewer);
            } else {
                state.idleRollViewers.remove(viewer);
            }
        }
    }

    private static boolean hullOverWater(ChunkStore chunks, Vector3d position, double yaw,
                                         ModelComponent model, BoundingBox collisionBounds) {
        var bounds = BoatHull.bounds(model, collisionBounds);
        return bounds != null && hullOverWater(position, BoatHull.forBoat(bounds, yaw),
                (x, z) -> !Double.isNaN(waterSurface(chunks, x + 0.5, position.y, z + 0.5)));
    }

    static boolean hullOverWater(Vector3d position, BoatHull hull, BoatHull.ColumnCheck hasWater) {
        return hull.allColumns(position, hasWater);
    }

    private static boolean updateBoatModel(Ref<EntityStore> boat, ModelComponent component, boolean showOars,
                                           CommandBuffer<EntityStore> commands) {
        if (component == null) {
            return false;
        }
        Model original = component.getModel();
        if (!"Boat".equals(original.getModelAssetId())) {
            return false;
        }
        boolean hasOars = OARS_MODEL.equals(original.getModel());
        boolean hasAnimations = original.getAnimationSetMap().keySet().containsAll(BoatAnimation.CLIPS.keySet());
        if (hasOars == showOars && hasAnimations) {
            return false;
        }
        var hull = ModelAsset.getAssetMap().getAsset(original.getModelAssetId());
        if (hull == null) {
            return false;
        }
        var animations = original.getAnimationSetMap();
        if (!hasAnimations) {
            var source = ModelAsset.getAssetMap().getAsset(PADDLE_PITCH_MODEL);
            if (source == null) {
                return false;
            }
            animations = new HashMap<>(animations);
            for (String animation : BoatAnimation.CLIPS.keySet()) {
                var animationSet = source.getAnimationSetMap().get(animation);
                if (animationSet == null) {
                    return false;
                }
                animations.put(animation, animationSet);
            }
            var phaseModel = ModelAsset.getAssetMap().getAsset(ROWING_PHASE_MODEL);
            if (phaseModel != null) {
                for (var phase : phaseModel.getAnimationSetMap().entrySet()) {
                    if (phase.getKey().contains(BoatAnimation.PHASE_SUFFIX)) {
                        animations.put(phase.getKey(), phase.getValue());
                    }
                }
            }
        }

        var attachments = original.getAttachments();
        if (hasOars != showOars) {
            var updated = new ArrayList<ModelAttachment>();
            if (attachments != null) {
                for (var attachment : attachments) {
                    if (showOars || !hull.getModel().equals(attachment.getModel())) {
                        updated.add(attachment);
                    }
                }
            }
            if (showOars) {
                // Animation tracks bind to the primary skeleton. Reuse the vanilla hull on its shared Base node.
                updated.add(new ModelAttachment(hull.getModel(), hull.getTexture(),
                        hull.getGradientSet(), hull.getGradientId(), 1));
            }
            attachments = updated.toArray(ModelAttachment[]::new);
        }
        // Keep the vanilla asset ID in the saved model reference, regardless of current occupancy.
        var model = new Model(original.getModelAssetId(), original.getScale(), original.getRandomAttachmentIds(),
                original.getConditionalAttachmentIds(), attachments, original.getBoundingBox(),
                showOars ? OARS_MODEL : hull.getModel(), showOars ? OARS_TEXTURE : hull.getTexture(),
                showOars ? null : hull.getGradientSet(), showOars ? null : hull.getGradientId(),
                original.getEyeHeight(), original.getCrouchOffset(), original.getSittingOffset(),
                original.getSleepingOffset(), animations, original.getCamera(), original.getLight(),
                original.getParticles(), original.getTrails(), original.getPhysicsValues(), original.getDetailBoxes(),
                original.getPhobia(), original.getPhobiaModelAssetId());
        commands.replaceComponent(boat, ModelComponent.getComponentType(), new ModelComponent(model));
        return true;
    }

    private boolean emitOarSplash(ChunkStore chunks, Vector3d position, double yaw, float modelScale,
                                  BoatRowing.Splash stroke, int side,
                                  double speedFraction, CommandBuffer<EntityStore> commands) {
        // Follow the shaft offset, oar pivot, and hull bank through the model hierarchy.
        double bank = stroke.hullBank();
        double hullPitch = stroke.hullPitch();
        var bladeAxis = new Vector3d(side, 0, 0)
                .rotateZ(-side * OAR_CATCH_DIP)
                .rotateY(side * stroke.bladeYaw());
        // The tangent of the blade's sweep gives the direction in which it pushes water.
        int sweep = stroke.reverse() ? -side : side;
        var sweepDirection = new Vector3d(sweep * bladeAxis.z, 0, -sweep * bladeAxis.x)
                .rotateX(hullPitch)
                .rotateZ(bank)
                .rotateY(yaw - Math.PI);
        float splashYaw = (float) Math.atan2(-sweepDirection.x, -sweepDirection.z);
        bladeAxis.rotateX(hullPitch)
                .rotateZ(bank)
                .mul(modelScale / MODEL_UNITS_PER_BLOCK)
                .rotateY(yaw - Math.PI);
        var pivot = new Vector3d(side * OAR_PIVOT_X, OAR_PIVOT_Y, OAR_PIVOT_Z)
                .rotateX(hullPitch)
                .rotateZ(bank)
                .add(0, 0, MODEL_BASE_Z)
                .mul(modelScale / MODEL_UNITS_PER_BLOCK)
                .rotateY(yaw - Math.PI)
                .add(position);
        // Bring the splash closer to the hull without changing the blade's waterline calculation.
        bladeAxis.x *= OAR_SPLASH_DISTANCE_FACTOR;
        bladeAxis.z *= OAR_SPLASH_DISTANCE_FACTOR;
        pivot.x = Math.fma(pivot.x - position.x, OAR_SPLASH_DISTANCE_FACTOR, position.x);
        pivot.z = Math.fma(pivot.z - position.z, OAR_SPLASH_DISTANCE_FACTOR, position.z);
        var tip = new Vector3d(bladeAxis).mul(OAR_BLADE_END).add(pivot);
        double surface = waterSurface(chunks, tip.x, position.y, tip.z);
        if (Double.isNaN(surface)) {
            return false;
        }
        // Use the point along the blade at the waterline, clamped to the visible blade.
        double contact = Math.clamp((surface - pivot.y) / bladeAxis.y, OAR_BLADE_START, OAR_BLADE_END);
        var splash = bladeAxis.mul(contact).add(pivot);
        surface = waterSurface(chunks, splash.x, position.y, splash.z);
        if (Double.isNaN(surface)) {
            return false;
        }
        splash.y = surface + 0.03;
        spawnFoam(PADDLE_SPLASH, splash, splashYaw, paddleSplashScale(speedFraction), commands);
        return true;
    }

    private static float paddleSplashScale(double speedFraction) {
        return (float) (SLOW_SPLASH_SCALE + speedFraction * (FAST_SPLASH_SCALE - SLOW_SPLASH_SCALE));
    }

    private void playPaddleSound(Vector3d position, CommandBuffer<EntityStore> commands) {
        int sound = SoundEvent.getAssetMap().getIndex(PADDLE_SOUND);
        if (sound <= 0) {
            return;
        }
        var event = SoundEvent.getAssetMap().getAsset(sound);
        if (event == null) {
            return;
        }
        float pitch = (float) ThreadLocalRandom.current().nextDouble(MIN_PADDLE_PITCH, MAX_PADDLE_PITCH);
        var listeners = SpatialResource.<EntityStore>getThreadLocalReferenceList();
        commands.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure()
                .collect(position, event.getMaxDistance(), listeners);
        for (var listener : listeners) {
            if (!listener.isValid()) {
                continue;
            }
            var player = commands.getComponent(listener, PlayerRef.getComponentType());
            if (player == null) {
                continue;
            }
            int percent = playerSettings.get(player.getUuid()).volume();
            if (percent > 0) {
                SoundUtil.playSoundEvent3dToPlayer(listener, sound, SoundCategory.SFX,
                        position.x, position.y, position.z, PADDLE_VOLUME * (percent / 100f), pitch, commands);
            }
        }
    }

    private void emitFoam(ChunkStore chunks, CommandBuffer<EntityStore> commands,
                                 double x, double hullY, double z) {
        double surface = waterSurface(chunks, x, hullY, z);
        if (!Double.isNaN(surface)) {
            spawnFoam(PARTICLE, new Vector3d(x, surface + 0.03, z), 0, 1, commands);
        }
    }

    private void spawnFoam(String particle, Vector3d position, float yaw, float scale,
                           CommandBuffer<EntityStore> commands) {
        var viewers = SpatialResource.<EntityStore>getThreadLocalReferenceList();
        commands.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure()
                .collect(position, ParticleUtil.DEFAULT_PARTICLE_DISTANCE, viewers);
        for (var viewer : viewers) {
            if (!viewer.isValid()) {
                continue;
            }
            var player = commands.getComponent(viewer, PlayerRef.getComponentType());
            if (player == null) {
                continue;
            }
            int opacity = playerSettings.get(player.getUuid()).opacity();
            if (opacity > 0) {
                String effect = opacity == BoatPlayerSettings.MAX_PERCENT ? particle : particle + "_" + opacity;
                ParticleUtil.spawnParticleEffect(effect, position.x, position.y, position.z,
                        yaw, 0, 0, scale, null, null, List.of(viewer), commands);
            }
        }
    }

    static double waterSurface(ChunkStore chunks, double x, double hullY, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int hullBlockY = (int) Math.floor(hullY);
        for (int y = hullBlockY + 1; y >= hullBlockY - 1; y--) {
            var ref = chunks.getChunkSectionReferenceAtBlock(blockX, y, blockZ);
            if (ref == null || !ref.isValid()) {
                continue;
            }
            var section = chunks.getStore().getComponent(ref, FluidSection.getComponentType());
            if (section == null) {
                continue;
            }
            int level = section.getFluidLevel(blockX, y, blockZ);
            if (level <= 0) {
                continue;
            }
            var fluid = section.getFluid(blockX, y, blockZ);
            if (fluid != null && "Water".equals(fluid.getFluidFXId())) {
                double surface = y + Math.min(1.0, (double) level / fluid.getMaxFluidLevel());
                return Math.abs(surface - hullY) <= 0.65 ? surface : Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static Ref<EntityStore> getDriver(MountedByComponent riders, CommandBuffer<EntityStore> commands) {
        var driver = riders == null ? null : riders.getPassengerInSeat(MountedComponent.DRIVER_SEAT_INDEX, commands);
        return driver != null && driver.isValid() ? driver : null;
    }

    public static final class WakeState implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private double previousYaw;
        private float paddleCooldown;
        private double paddleSpeed;
        private int paddleSide = 1;
        private BoatRowing rowing = new BoatRowing();
        private float idleTime;
        private final Set<Ref<EntityStore>> idleRollViewers = new HashSet<>();

        @Override
        @SuppressWarnings("MethodDoesntCallSuperMethod")
        public WakeState clone() {
            // Allocate a separate position vector for the copy.
            var copy = new WakeState();
            copy.previous.set(previous);
            copy.previousYaw = previousYaw;
            copy.paddleCooldown = paddleCooldown;
            copy.paddleSpeed = paddleSpeed;
            copy.paddleSide = paddleSide;
            copy.rowing = rowing.copy();
            copy.idleTime = idleTime;
            copy.idleRollViewers.addAll(idleRollViewers);
            return copy;
        }
    }
}
