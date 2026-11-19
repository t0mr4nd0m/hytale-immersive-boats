package org.immersiveboats;

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
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class BoatWakeSystem extends DelayedEntitySystem<EntityStore> {
    private static final String PARTICLE = "ImmersiveBoats_Boat_Wake";
    private static final String PADDLE_SOUND = "SFX_Water_MoveOut";
    private static final String PADDLE_SPLASH = "ImmersiveBoats_Boat_Paddle_Splash";
    private static final String PADDLE_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch";
    private static final String SLOW_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Slow";
    private static final String FAST_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Fast";
    private static final List<String> PITCH_ANIMATIONS =
            List.of(SLOW_PITCH_ANIMATION, PADDLE_PITCH_ANIMATION, FAST_PITCH_ANIMATION);
    private static final String PADDLE_PITCH_MODEL = "ImmersiveBoats_Boat_Pitch";
    private static final float PITCH_CYCLE_SECONDS = 2.4f;
    private static final float PITCH_STOP_GRACE_SECONDS = 0.30f;
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
    private static final double SPEED_SMOOTHING_SECONDS = 0.35;
    private static final double MIN_TRAVEL = 0.04;
    private static final double MAX_TRAVEL = 3.0;
    private static final double MIN_SIDE_TRAVEL = 0.035;
    private static final double MIN_TURN = Math.toRadians(1.5);
    private static final double TRAIL_OFFSET = 1.45;
    private static final double TRAIL_HALF_WIDTH = 0.45;
    private static final double SIDE_OFFSET = 1.0;
    private static final double TURN_OFFSET = 0.65;

    private final ComponentType<EntityStore, WakeState> wakeState;
    private final PlayerPercentages volumes;
    private final PlayerPercentages opacities;
    private final Query<EntityStore> query = Query.and(
            BoatComponent.getComponentType(), TransformComponent.getComponentType());

    BoatWakeSystem(ComponentType<EntityStore, WakeState> wakeState, PlayerPercentages volumes,
                   PlayerPercentages opacities) {
        super(0.10f);
        this.wakeState = wakeState;
        this.volumes = volumes;
        this.opacities = opacities;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store, CommandBuffer<EntityStore> commands) {
        var boat = chunk.getReferenceTo(index);
        var riders = chunk.getComponent(index, MountedByComponent.getComponentType());
        var driver = riders == null ? null : riders.getPassengerInSeat(MountedComponent.DRIVER_SEAT_INDEX, commands);
        var state = chunk.getComponent(index, wakeState);
        if (driver == null || !driver.isValid() || commands.getComponent(driver, PlayerRef.getComponentType()) == null) {
            if (state != null) {
                stopPitch(boat, state, commands);
                commands.removeComponent(boat, wakeState);
            }
            return;
        }

        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        var position = transform.getPosition();
        double yaw = transform.getRotation().yaw();
        if (state == null) {
            state = new WakeState();
            state.previous.set(position);
            state.previousYaw = yaw;
            commands.addComponent(boat, wakeState, state);
            attachPitchAnimation(boat, chunk.getComponent(index, ModelComponent.getComponentType()), commands);
            return;
        }

        double dx = position.x - state.previous.x;
        double dz = position.z - state.previous.z;
        double dy = position.y - state.previous.y;
        double turn = Math.IEEEremainder(yaw - state.previousYaw, 2 * Math.PI);
        state.previous.set(position);
        state.previousYaw = yaw;
        double travel = Math.hypot(dx, dz);
        if (travel > MAX_TRAVEL || Math.abs(dy) > 1.0) {
            state.paddleSpeed = 0;
            stopPitch(boat, state, commands);
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

        double headingX = Math.sin(yaw);
        double headingZ = Math.cos(yaw);
        double sideX = headingZ;
        double sideZ = -headingX;
        boolean advancing = Math.abs(dx * headingX + dz * headingZ) >= MIN_TRAVEL;
        double sideTravel = dx * sideX + dz * sideZ;
        boolean turning = Math.abs(turn) >= MIN_TURN;
        if (!advancing && !turning && Math.abs(sideTravel) < MIN_SIDE_TRAVEL) {
            updatePitch(boat, state, false, speedFraction, dt, commands);
            return;
        }

        var chunks = store.getExternalData().getWorld().getChunkStore();
        double surface = waterSurface(chunks, position.x, position.y, position.z);
        if (Double.isNaN(surface)) {
            stopPitch(boat, state, commands);
            return;
        }

        updatePitch(boat, state, advancing, speedFraction, dt, commands);
        if ((advancing || turning) && state.paddleCooldown <= 0) {
            var random = ThreadLocalRandom.current();
            int sound = SoundEvent.getAssetMap().getIndex(PADDLE_SOUND);
            if (sound > 0) {
                var paddlePosition = new Vector3d(position.x + sideX * state.paddleSide * SIDE_OFFSET,
                        surface + 0.05, position.z + sideZ * state.paddleSide * SIDE_OFFSET);
                playPaddleSound(sound, paddlePosition,
                        (float) random.nextDouble(MIN_PADDLE_PITCH, MAX_PADDLE_PITCH), commands);
                float splashScale = (float) (SLOW_SPLASH_SCALE + speedFraction * (FAST_SPLASH_SCALE - SLOW_SPLASH_SCALE));
                spawnFoam(PADDLE_SPLASH, paddlePosition, splashScale, commands);
            }
            state.paddleSide = -state.paddleSide;
            state.paddleCooldown = (float) random.nextDouble(MIN_PADDLE_INTERVAL, MAX_PADDLE_INTERVAL)
                    + state.paddleCooldown;
        }

        if (advancing) {
            double forwardX = dx / travel;
            double forwardZ = dz / travel;
            double trailX = position.x - forwardX * TRAIL_OFFSET;
            double trailZ = position.z - forwardZ * TRAIL_OFFSET;
            emitFoam(chunks, commands, trailX - forwardZ * TRAIL_HALF_WIDTH,
                    position.y, trailZ + forwardX * TRAIL_HALF_WIDTH);
            emitFoam(chunks, commands, trailX + forwardZ * TRAIL_HALF_WIDTH,
                    position.y, trailZ - forwardX * TRAIL_HALF_WIDTH);
        }

        // As the hull pivots, opposite ends sweep water on opposite sides.
        double turnOffset = turning ? Math.copySign(TURN_OFFSET, turn) : 0;
        if (turning || sideTravel >= MIN_SIDE_TRAVEL) {
            emitFoam(chunks, commands, position.x + sideX * SIDE_OFFSET + headingX * turnOffset,
                    position.y, position.z + sideZ * SIDE_OFFSET + headingZ * turnOffset);
        }
        if (turning || sideTravel <= -MIN_SIDE_TRAVEL) {
            emitFoam(chunks, commands, position.x - sideX * SIDE_OFFSET - headingX * turnOffset,
                    position.y, position.z - sideZ * SIDE_OFFSET - headingZ * turnOffset);
        }
    }

    private static void updatePitch(Ref<EntityStore> boat, WakeState state, boolean advancing,
                                    double speedFraction, float dt, CommandBuffer<EntityStore> commands) {
        if (state.pitchAnimation != null) {
            state.pitchCycleRemaining -= dt;
        }
        if (!advancing) {
            state.pitchGrace = Math.max(0, state.pitchGrace - dt);
            if (state.pitchGrace == 0) {
                stopPitch(boat, state, commands);
            }
            return;
        }

        state.pitchGrace = PITCH_STOP_GRACE_SECONDS;
        if (state.pitchCycleRemaining > 0 || commands.getComponent(boat, NetworkId.getComponentType()) == null) {
            return;
        }
        String animation = PADDLE_PITCH_ANIMATION;
        if (speedFraction < 1.0 / 3) {
            animation = SLOW_PITCH_ANIMATION;
        } else if (speedFraction >= 2.0 / 3) {
            animation = FAST_PITCH_ANIMATION;
        }
        if (animation.equals(state.pitchAnimation)) {
            state.pitchCycleRemaining = PITCH_CYCLE_SECONDS + state.pitchCycleRemaining % PITCH_CYCLE_SECONDS;
            return;
        }

        var model = commands.getComponent(boat, ModelComponent.getComponentType());
        if (model != null && model.getModel().getAnimationSetMap().containsKey(animation)) {
            AnimationUtils.playAnimation(boat, AnimationSlot.ServerAction, animation, true, commands);
            state.pitchAnimation = animation;
            state.pitchCycleRemaining = PITCH_CYCLE_SECONDS;
        }
    }

    private static void stopPitch(Ref<EntityStore> boat, WakeState state, CommandBuffer<EntityStore> commands) {
        if (state.pitchAnimation != null && commands.getComponent(boat, NetworkId.getComponentType()) != null) {
            AnimationUtils.stopAnimation(boat, AnimationSlot.ServerAction, true, commands);
        }
        state.pitchAnimation = null;
        state.pitchCycleRemaining = 0;
        state.pitchGrace = 0;
    }

    private static void attachPitchAnimation(Ref<EntityStore> boat, ModelComponent component,
                                             CommandBuffer<EntityStore> commands) {
        if (component == null) {
            return;
        }
        Model original = component.getModel();
        if (!"Boat".equals(original.getModelAssetId())
                || original.getAnimationSetMap().keySet().containsAll(PITCH_ANIMATIONS)) {
            return;
        }
        var source = ModelAsset.getAssetMap().getAsset(PADDLE_PITCH_MODEL);
        if (source == null) {
            return;
        }

        var animations = new HashMap<>(original.getAnimationSetMap());
        for (String animation : PITCH_ANIMATIONS) {
            var pitch = source.getAnimationSetMap().get(animation);
            if (pitch == null) {
                return;
            }
            animations.put(animation, pitch);
        }
        // Keep the original asset ID and model data so the saved reference still points to the vanilla boat.
        var animated = new Model(original.getModelAssetId(), original.getScale(), original.getRandomAttachmentIds(),
                original.getConditionalAttachmentIds(), original.getAttachments(), original.getBoundingBox(),
                original.getModel(), original.getTexture(), original.getGradientSet(), original.getGradientId(),
                original.getEyeHeight(), original.getCrouchOffset(), original.getSittingOffset(),
                original.getSleepingOffset(), animations, original.getCamera(), original.getLight(),
                original.getParticles(), original.getTrails(), original.getPhysicsValues(), original.getDetailBoxes(),
                original.getPhobia(), original.getPhobiaModelAssetId());
        commands.replaceComponent(boat, ModelComponent.getComponentType(), new ModelComponent(animated));
    }

    private void playPaddleSound(int sound, Vector3d position, float pitch, CommandBuffer<EntityStore> commands) {
        var event = SoundEvent.getAssetMap().getAsset(sound);
        if (event == null) {
            return;
        }
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
            int percent = volumes.get(player.getUuid());
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
            spawnFoam(PARTICLE, new Vector3d(x, surface + 0.03, z), 1, commands);
        }
    }

    private void spawnFoam(String particle, Vector3d position, float scale, CommandBuffer<EntityStore> commands) {
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
            int opacity = opacities.get(player.getUuid());
            if (opacity > 0) {
                String effect = opacity == PlayerPercentages.DEFAULT ? particle : particle + "_" + opacity;
                ParticleUtil.spawnParticleEffect(effect, position, 0, 0, 0, scale, null, List.of(viewer), commands);
            }
        }
    }

    private static double waterSurface(ChunkStore chunks, double x, double hullY, double z) {
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

    static final class WakeState implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private double previousYaw;
        private float paddleCooldown;
        private double paddleSpeed;
        private int paddleSide = 1;
        private String pitchAnimation;
        private float pitchCycleRemaining;
        private float pitchGrace;

        @Override
        public WakeState clone() {
            var copy = new WakeState();
            copy.previous.set(previous);
            copy.previousYaw = previousYaw;
            copy.paddleCooldown = paddleCooldown;
            copy.paddleSpeed = paddleSpeed;
            copy.paddleSide = paddleSide;
            copy.pitchAnimation = pitchAnimation;
            copy.pitchCycleRemaining = pitchCycleRemaining;
            copy.pitchGrace = pitchGrace;
            return copy;
        }
    }
}
