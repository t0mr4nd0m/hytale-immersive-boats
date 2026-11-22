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
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
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
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;

final class BoatWakeSystem extends DelayedEntitySystem<EntityStore> {
    private static final String PARTICLE = "ImmersiveBoats_Boat_Wake";
    private static final String PADDLE_SOUND = "SFX_Water_MoveOut";
    private static final String PADDLE_SPLASH = "ImmersiveBoats_Boat_Paddle_Splash";
    private static final String PADDLE_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch";
    private static final String SLOW_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Slow";
    private static final String FAST_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Fast";
    private static final String IDLE_ROLL_ANIMATION = "ImmersiveBoats_Boat_Idle_Roll";
    private static final List<String> BOAT_ANIMATIONS =
            List.of(SLOW_PITCH_ANIMATION, PADDLE_PITCH_ANIMATION, FAST_PITCH_ANIMATION, IDLE_ROLL_ANIMATION);
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
    private static final double HULL_HALF_WIDTH = 0.45;
    private static final double HULL_HALF_LENGTH = 0.77;
    private static final double HULL_CENTER_OFFSET = 0.23;

    private final ComponentType<EntityStore, WakeState> wakeState;
    private final PlayerPercentages volumes;
    private final PlayerPercentages opacities;
    private final BoatMotionPreferences motion;
    private final Query<EntityStore> query = Query.and(
            BoatComponent.getComponentType(), TransformComponent.getComponentType());

    BoatWakeSystem(ComponentType<EntityStore, WakeState> wakeState, PlayerPercentages volumes,
                   PlayerPercentages opacities, BoatMotionPreferences motion) {
        super(0.10f);
        this.wakeState = wakeState;
        this.volumes = volumes;
        this.opacities = opacities;
        this.motion = motion;
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
        if (state == null) {
            state = new WakeState();
            state.previous.set(position);
            state.previousYaw = yaw;
            commands.addComponent(boat, wakeState, state);
            attachBoatAnimations(boat, chunk.getComponent(index, ModelComponent.getComponentType()), commands);
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
        var riders = chunk.getComponent(index, MountedByComponent.getComponentType());
        var driver = riders == null ? null : riders.getPassengerInSeat(MountedComponent.DRIVER_SEAT_INDEX, commands);
        var driverPlayer = driver == null || !driver.isValid()
                ? null : commands.getComponent(driver, PlayerRef.getComponentType());
        boolean idleRoll = driverPlayer == null || motion.idleRoll().get(driverPlayer.getUuid());
        state.idleTime = idleRoll && still ? Math.min(IDLE_DELAY_SECONDS, state.idleTime + dt) : 0;
        boolean idle = state.idleTime >= IDLE_DELAY_SECONDS
                && hullOverWater(chunks, position, headingX, headingZ);
        if (idle || driverPlayer == null) {
            stopPitch(boat, state, commands);
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
        boolean rowingPitch = driverPlayer != null && motion.rowingPitch().get(driverPlayer.getUuid());
        if (!rowingPitch && state.pitchAnimation != null) {
            stopPitch(boat, state, commands);
        }
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

        double sideZ = -headingX;
        double longitudinalTravel = dx * headingX + dz * headingZ;
        double minTravel = driverPlayer == null ? MAX_IDLE_SPEED * dt : MIN_TRAVEL;
        double minSideTravel = driverPlayer == null ? minTravel : MIN_SIDE_TRAVEL;
        boolean advancing = Math.abs(longitudinalTravel) >= minTravel;
        double sideTravel = dx * headingZ + dz * sideZ;
        boolean turning = Math.abs(turn) >= (driverPlayer == null ? MAX_IDLE_TURN_SPEED * dt : MIN_TURN);
        if (!advancing && !turning && Math.abs(sideTravel) < minSideTravel) {
            if (rowingPitch) {
                updatePitch(boat, state, false, speedFraction, dt, commands);
            }
            return;
        }

        double surface = waterSurface(chunks, position.x, position.y, position.z);
        if (Double.isNaN(surface)) {
            stopPitch(boat, state, commands);
            return;
        }

        if (rowingPitch) {
            updatePitch(boat, state, advancing, speedFraction, dt, commands);
        }
        if ((advancing || turning || driverPlayer == null) && state.paddleCooldown <= 0) {
            var random = ThreadLocalRandom.current();
            double along = 0;
            double across = state.paddleSide * SIDE_OFFSET;
            if (driverPlayer == null) {
                if (advancing && Math.abs(longitudinalTravel) >= Math.abs(sideTravel)) {
                    along = Math.copySign(HULL_END_FOAM_OFFSET, longitudinalTravel);
                    across = 0;
                } else if (Math.abs(sideTravel) >= minSideTravel) {
                    across = Math.copySign(SIDE_OFFSET, sideTravel);
                } else {
                    along = Math.copySign(TURN_OFFSET, turn) * state.paddleSide;
                }
            }
            double splashX = position.x + headingX * along + headingZ * across;
            double splashZ = position.z + headingZ * along + sideZ * across;
            double splashSurface = driverPlayer == null ? waterSurface(chunks, splashX, position.y, splashZ) : surface;
            if (!Double.isNaN(splashSurface)) {
                var splashPosition = new Vector3d(splashX, splashSurface + 0.05, splashZ);
                int sound = SoundEvent.getAssetMap().getIndex(PADDLE_SOUND);
                if (sound > 0) {
                    playPaddleSound(sound, splashPosition,
                            (float) random.nextDouble(MIN_PADDLE_PITCH, MAX_PADDLE_PITCH), commands);
                }
                float splashScale = (float) (SLOW_SPLASH_SCALE + speedFraction * (FAST_SPLASH_SCALE - SLOW_SPLASH_SCALE));
                spawnFoam(PADDLE_SPLASH, splashPosition, splashScale, commands);
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
            if (model == null || !model.getModel().getAnimationSetMap().containsKey(IDLE_ROLL_ANIMATION)) {
                attachBoatAnimations(boat, model, commands);
                idle = false;
            }
        }
        for (var viewer : visibility.visibleTo.keySet()) {
            var player = viewer.isValid() ? commands.getComponent(viewer, PlayerRef.getComponentType()) : null;
            if (player == null) {
                continue;
            }
            boolean shouldRoll = idle && (!unoccupied || motion.idleRoll().get(player.getUuid()));
            if (shouldRoll == state.idleRollViewers.contains(viewer)) {
                continue;
            }
            String animation = shouldRoll ? IDLE_ROLL_ANIMATION : null;
            player.getPacketHandler().writeNoCache(
                    new PlayAnimation(networkId.getId(), null, animation, AnimationSlot.ServerAction));
            if (shouldRoll) {
                state.idleRollViewers.add(viewer);
            } else {
                state.idleRollViewers.remove(viewer);
            }
        }
    }

    private static boolean hullOverWater(ChunkStore chunks, Vector3d position, double headingX, double headingZ) {
        // The vanilla boat hitbox extends from Z -0.54 to 1.00 and from X -0.45 to 0.45.
        double centerX = position.x + headingX * HULL_CENTER_OFFSET;
        double centerZ = position.z + headingZ * HULL_CENTER_OFFSET;
        double absX = Math.abs(headingX);
        double absZ = Math.abs(headingZ);
        double extentX = HULL_HALF_LENGTH * absX + HULL_HALF_WIDTH * absZ;
        double extentZ = HULL_HALF_LENGTH * absZ + HULL_HALF_WIDTH * absX;
        double blockRadius = 0.5 * (absX + absZ);
        int maxX = (int) Math.ceil(centerX + extentX);
        int maxZ = (int) Math.ceil(centerZ + extentZ);
        for (int x = (int) Math.floor(centerX - extentX); x < maxX; x++) {
            for (int z = (int) Math.floor(centerZ - extentZ); z < maxZ; z++) {
                // The loop bounds test the world axes. These two projections test the hull axes.
                double dx = x + 0.5 - centerX;
                double dz = z + 0.5 - centerZ;
                if (Math.abs(dx * headingX + dz * headingZ) < HULL_HALF_LENGTH + blockRadius
                        && Math.abs(dx * headingZ - dz * headingX) < HULL_HALF_WIDTH + blockRadius
                        && Double.isNaN(waterSurface(chunks, x + 0.5, position.y, z + 0.5))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void attachBoatAnimations(Ref<EntityStore> boat, ModelComponent component,
                                             CommandBuffer<EntityStore> commands) {
        if (component == null) {
            return;
        }
        Model original = component.getModel();
        if (!"Boat".equals(original.getModelAssetId())
                || original.getAnimationSetMap().keySet().containsAll(BOAT_ANIMATIONS)) {
            return;
        }
        var source = ModelAsset.getAssetMap().getAsset(PADDLE_PITCH_MODEL);
        if (source == null) {
            return;
        }

        var animations = new HashMap<>(original.getAnimationSetMap());
        for (String animation : BOAT_ANIMATIONS) {
            var animationSet = source.getAnimationSetMap().get(animation);
            if (animationSet == null) {
                return;
            }
            animations.put(animation, animationSet);
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
                ParticleUtil.spawnParticleEffect(effect, position.x, position.y, position.z,
                        0, 0, 0, scale, null, null, List.of(viewer), commands);
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

    static final class WakeState implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private double previousYaw;
        private float paddleCooldown;
        private double paddleSpeed;
        private int paddleSide = 1;
        private String pitchAnimation;
        private float pitchCycleRemaining;
        private float pitchGrace;
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
            copy.pitchAnimation = pitchAnimation;
            copy.pitchCycleRemaining = pitchCycleRemaining;
            copy.pitchGrace = pitchGrace;
            copy.idleTime = idleTime;
            copy.idleRollViewers.addAll(idleRollViewers);
            return copy;
        }
    }
}
