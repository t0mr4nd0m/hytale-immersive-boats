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
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
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

import java.util.ArrayList;
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
    private static final String TURN_ANIMATION = "ImmersiveBoats_Boat_Turn";
    private static final String OARS_ROWING_ANIMATION = "ImmersiveBoats_Boat_Oars_Rowing";
    private static final String POSITIVE_YAW_BANK = "_Bank_Positive_Yaw";
    private static final String NEGATIVE_YAW_BANK = "_Bank_Negative_Yaw";
    private static final String POSITIVE_YAW_TURN = "_Turn_Positive_Yaw";
    private static final String NEGATIVE_YAW_TURN = "_Turn_Negative_Yaw";
    private static final String REVERSE = "_Reverse";
    private static final String STROKE = "_Stroke";
    private static final String IDLE_ROLL_ANIMATION = "ImmersiveBoats_Boat_Idle_Roll";
    private static final List<String> PITCH_ANIMATIONS =
            List.of(SLOW_PITCH_ANIMATION, PADDLE_PITCH_ANIMATION, FAST_PITCH_ANIMATION);
    private static final List<String> BOAT_ANIMATIONS = boatAnimations();
    private static final String PADDLE_PITCH_MODEL = "ImmersiveBoats_Boat_Pitch";
    private static final String OARS_MODEL = "Items/Vehicles/ImmersiveBoats_Boat_Oars.blockymodel";
    private static final String OARS_TEXTURE = "Items/Vehicles/ImmersiveBoats_Boat_Oars.png";
    private static final float ANIMATION_FRAMES_PER_SECOND = 60;
    private static final float ROWING_CYCLE_SECONDS = 72 / ANIMATION_FRAMES_PER_SECOND;
    private static final float FORWARD_CATCH_SECONDS = 18 / ANIMATION_FRAMES_PER_SECOND;
    private static final float REVERSE_CATCH_SECONDS = 30 / ANIMATION_FRAMES_PER_SECOND;
    private static final float POWER_STROKE_SECONDS = 24 / ANIMATION_FRAMES_PER_SECOND;
    private static final double MODEL_UNITS_PER_BLOCK = 32;
    private static final double OAR_SPLASH_DISTANCE_FACTOR = 0.5;
    private static final double OAR_PIVOT_X = 17;
    private static final double OAR_PIVOT_Y = 13;
    private static final double OAR_PIVOT_Z = -5;
    private static final double OAR_SHAFT_OFFSET = 3.5;
    private static final double OAR_BLADE_START = OAR_SHAFT_OFFSET + 20;
    private static final double OAR_BLADE_END = OAR_SHAFT_OFFSET + 39;
    private static final double MODEL_BASE_Z = 1.75;
    private static final double TURN_BANK_ANGLE = Math.toRadians(3);
    private static final double OAR_CATCH_DIP = Math.toRadians(24);
    private static final double FORWARD_CATCH_YAW = Math.toRadians(-17);
    private static final double REVERSE_CATCH_YAW = Math.toRadians(37);
    private static final float ROWING_STOP_GRACE_SECONDS = 0.30f;
    private static final float TURN_STOP_GRACE_SECONDS = 0.20f;
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
        var driver = getDriver(chunk.getComponent(index, MountedByComponent.getComponentType()), commands);
        var driverPlayer = driver == null ? null : commands.getComponent(driver, PlayerRef.getComponentType());
        boolean showOars = driverPlayer != null && motion.showOars().get(driverPlayer.getUuid());
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
        boolean idleRoll = driverPlayer == null || motion.idleRoll().get(driverPlayer.getUuid());
        state.idleTime = idleRoll && still ? Math.min(IDLE_DELAY_SECONDS, state.idleTime + dt) : 0;
        boolean idle = state.idleTime >= IDLE_DELAY_SECONDS
                && hullOverWater(chunks, position, headingX, headingZ);
        boolean rockingWhileRowing = driverPlayer != null && motion.rowingPitch().get(driverPlayer.getUuid());
        if (idle || driverPlayer == null) {
            state.rowingMotion.reset();
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
            state.rowingMotion.reset();
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
        boolean coasting = driverPlayer != null && state.rowingMotion.sample(travel, turn, dt);
        boolean moving = advancing || turning || Math.abs(sideTravel) >= minSideTravel;
        if (!moving && state.rowingAnimation == null) {
            return;
        }

        double surface = waterSurface(chunks, position.x, position.y, position.z);
        if (Double.isNaN(surface)) {
            state.rowingMotion.reset();
            stopRowingAnimation(boat, state, commands);
            return;
        }

        if (!modelChanged && (showOars || rockingWhileRowing)) {
            double bankTurn = Math.abs(turn) >= MIN_TURN && Math.abs(turn) <= Math.PI / 2 ? turn : 0;
            // The model is rendered half a turn from the entity yaw, so the bow points against heading.
            boolean reversing = longitudinalTravel > 0;
            String previousAnimation = state.rowingAnimation;
            float previousRemaining = state.rowingCycleRemaining;
            updateRowingAnimation(boat, state, advancing, reversing, !coasting, rockingWhileRowing, showOars, bankTurn,
                    speedFraction, dt, commands);
            var mode = state.rowingMode;
            double catchProgress = mode == null ? -1 : oarCatchProgress(previousRemaining, dt, mode.reverse);
            if (model != null && mode != null && mode.stroke && previousAnimation != null
                    && previousAnimation.equals(state.rowingAnimation)
                    && catchProgress >= 0) {
                float scale = model.getModel().getScale();
                double startYaw = mode.reverse ? REVERSE_CATCH_YAW : FORWARD_CATCH_YAW;
                double endYaw = mode.reverse ? FORWARD_CATCH_YAW : REVERSE_CATCH_YAW;
                double bladeYaw = Math.fma(smoothStep(catchProgress), endYaw - startYaw, startYaw);
                double hullPitch = oarCatchHullPitch(previousAnimation, mode, catchProgress);
                boolean splashed = false;
                if (mode.turnDirection <= 0) {
                    splashed = emitOarSplash(chunks, position, yaw, scale, mode, -1, bladeYaw, hullPitch,
                            speedFraction, commands);
                }
                if (mode.turnDirection >= 0) {
                    splashed |= emitOarSplash(chunks, position, yaw, scale, mode, 1, bladeYaw, hullPitch,
                            speedFraction, commands);
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

    private static void updateRowingAnimation(Ref<EntityStore> boat, WakeState state, boolean advancing,
                                              boolean reversing, boolean paddling, boolean rockingWhileRowing,
                                              boolean showOars, double turn,
                                              double speedFraction, float dt,
                                              CommandBuffer<EntityStore> commands) {
        if (state.rowingAnimation != null) {
            state.rowingCycleRemaining -= dt;
        }
        if (!paddling) {
            state.rowingGrace = 0;
        } else if (advancing) {
            state.rowingGrace = ROWING_STOP_GRACE_SECONDS;
            state.reversing = reversing;
        } else {
            state.rowingGrace = Math.max(0, state.rowingGrace - dt);
        }
        if (turn != 0) {
            state.turnDirection = turn > 0 ? 1 : -1;
            state.turnGrace = TURN_STOP_GRACE_SECONDS;
        } else {
            state.turnGrace = Math.max(0, state.turnGrace - dt);
            if (state.turnGrace == 0) {
                state.turnDirection = 0;
            }
        }

        boolean rowing = state.rowingGrace > 0;
        boolean pitch = rowing && rockingWhileRowing;
        boolean turning = state.turnDirection != 0;
        boolean stroke = showOars && (rowing || (paddling && turning));
        boolean bank = rockingWhileRowing && turning;
        if (!pitch && !stroke && !bank) {
            stopRowingAnimation(boat, state, commands);
            return;
        }
        var mode = new RowingMode(pitch, stroke, bank, stroke && rowing && state.reversing, state.turnDirection);
        if ((mode.equals(state.rowingMode) && state.rowingCycleRemaining > 0)
                || commands.getComponent(boat, NetworkId.getComponentType()) == null) {
            return;
        }
        String animation = mode.animation(speedFraction);
        if (animation.equals(state.rowingAnimation)) {
            state.rowingCycleRemaining = ROWING_CYCLE_SECONDS + state.rowingCycleRemaining % ROWING_CYCLE_SECONDS;
            return;
        }

        var model = commands.getComponent(boat, ModelComponent.getComponentType());
        if (model != null && model.getModel().getAnimationSetMap().containsKey(animation)) {
            AnimationUtils.playAnimation(boat, AnimationSlot.ServerAction, animation, true, commands);
            state.rowingAnimation = animation;
            state.rowingMode = mode;
            state.rowingCycleRemaining = ROWING_CYCLE_SECONDS;
        }
    }

    private static void stopRowingAnimation(Ref<EntityStore> boat, WakeState state, CommandBuffer<EntityStore> commands) {
        if (state.rowingAnimation != null && commands.getComponent(boat, NetworkId.getComponentType()) != null) {
            AnimationUtils.stopAnimation(boat, AnimationSlot.ServerAction, true, commands);
        }
        state.rowingAnimation = null;
        state.rowingMode = null;
        state.turnDirection = 0;
        state.turnGrace = 0;
        state.reversing = false;
        state.rowingCycleRemaining = 0;
        state.rowingGrace = 0;
    }

    private record RowingMode(boolean pitch, boolean stroke, boolean bank, boolean reverse, int turnDirection) {
        String animation(double speedFraction) {
            String direction = reverse ? REVERSE : "";
            if (bank) {
                String suffix = turnDirection > 0 ? POSITIVE_YAW_BANK : NEGATIVE_YAW_BANK;
                if (pitch) {
                    return pitchAnimation(speedFraction) + suffix + direction;
                }
                return TURN_ANIMATION + suffix + (stroke ? STROKE : "");
            }
            String suffix = switch (turnDirection) {
                case 1 -> POSITIVE_YAW_TURN;
                case -1 -> NEGATIVE_YAW_TURN;
                default -> "";
            };
            return (pitch ? pitchAnimation(speedFraction) : OARS_ROWING_ANIMATION) + suffix + direction;
        }

        private static String pitchAnimation(double speedFraction) {
            return PITCH_ANIMATIONS.get(Math.min(2, (int) (speedFraction * 3)));
        }
    }

    private static List<String> boatAnimations() {
        var animations = new ArrayList<>(List.of(IDLE_ROLL_ANIMATION));
        for (String base : PITCH_ANIMATIONS) {
            for (String bank : List.of("", POSITIVE_YAW_BANK, NEGATIVE_YAW_BANK)) {
                animations.add(base + bank);
                animations.add(base + bank + REVERSE);
            }
        }
        for (String turn : List.of("", POSITIVE_YAW_TURN, NEGATIVE_YAW_TURN)) {
            animations.add(OARS_ROWING_ANIMATION + turn);
            animations.add(OARS_ROWING_ANIMATION + turn + REVERSE);
        }
        for (String bank : List.of(POSITIVE_YAW_BANK, NEGATIVE_YAW_BANK)) {
            animations.add(TURN_ANIMATION + bank);
            animations.add(TURN_ANIMATION + bank + STROKE);
        }
        return List.copyOf(animations);
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
        boolean hasAnimations = original.getAnimationSetMap().keySet().containsAll(BOAT_ANIMATIONS);
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
            for (String animation : BOAT_ANIMATIONS) {
                var animationSet = source.getAnimationSetMap().get(animation);
                if (animationSet == null) {
                    return false;
                }
                animations.put(animation, animationSet);
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

    private static double oarCatchProgress(float remaining, float dt, boolean reverse) {
        if (remaining <= 0) {
            return -1;
        }
        float catchRemaining = ROWING_CYCLE_SECONDS - (reverse ? REVERSE_CATCH_SECONDS : FORWARD_CATCH_SECONDS);
        float untilCatch = remaining - catchRemaining;
        if (untilCatch <= 0) {
            untilCatch += ROWING_CYCLE_SECONDS;
        }
        if (dt < untilCatch) {
            return -1;
        }
        // The wake tick can arrive just after the catch, already partway through the power stroke.
        return Math.clamp((dt - untilCatch) / POWER_STROKE_SECONDS, 0f, 1f);
    }

    private static double oarCatchHullPitch(String animation, RowingMode mode, double strokeProgress) {
        if (!mode.pitch) {
            return 0;
        }
        double peak = animation.startsWith(SLOW_PITCH_ANIMATION) ? 0.65
                : animation.startsWith(FAST_PITCH_ANIMATION) ? 1.65 : 1.2;
        // The hull lifts on frame 18 and levels on frame 54, including in reverse clips.
        double catchFrame = mode.reverse ? 30 : 18;
        double progress = smoothStep((catchFrame + 24 * strokeProgress - 18) / 36);
        return -Math.toRadians(peak) * (1 - progress);
    }

    private static double smoothStep(double progress) {
        double clamped = Math.clamp(progress, 0.0, 1.0);
        return clamped * clamped * (3 - 2 * clamped);
    }

    private boolean emitOarSplash(ChunkStore chunks, Vector3d position, double yaw, float modelScale,
                                  RowingMode mode, int side, double bladeYaw, double hullPitch,
                                  double speedFraction, CommandBuffer<EntityStore> commands) {
        // Follow the shaft offset, oar pivot, and hull bank through the model hierarchy.
        double bank = mode.bank ? -mode.turnDirection * TURN_BANK_ANGLE : 0;
        var bladeAxis = new Vector3d(side, 0, 0)
                .rotateZ(-side * OAR_CATCH_DIP)
                .rotateY(side * bladeYaw);
        // The tangent of the blade's sweep gives the direction in which it pushes water.
        int sweep = mode.reverse ? -side : side;
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
            int opacity = opacities.get(player.getUuid());
            if (opacity > 0) {
                String effect = opacity == PlayerPercentages.DEFAULT ? particle : particle + "_" + opacity;
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

    private static final class RowingMotion {
        private static final double SAMPLE_SECONDS = 0.15;
        private static final double MIN_COAST_DECELERATION = 1.5;
        private static final double MAX_COAST_DECELERATION = 6.0;
        private static final double MAX_SAMPLE_TURN = Math.toRadians(3);
        private static final double MIN_SAMPLE_SPEED = 0.2;
        private static final int COAST_SAMPLES = 2;

        private double elapsed;
        private double distance;
        private double turn;
        private double previousSpeed;
        private double previousDuration;
        private int slowingSamples;
        private boolean coasting;

        boolean sample(double travel, double yawChange, double dt) {
            elapsed += dt;
            distance += travel;
            turn += Math.abs(yawChange);
            if (elapsed < SAMPLE_SECONDS) {
                return coasting;
            }

            double speed = distance / elapsed;
            double acceleration = (speed - previousSpeed) / ((elapsed + previousDuration) * 0.5);
            if (speed <= MIN_SAMPLE_SPEED || acceleration >= MIN_COAST_DECELERATION) {
                coasting = false;
            }

            // Coasting produces repeated moderate speed drops. Reject turns and isolated, abrupt drops.
            if (previousDuration > 0 && speed > MIN_SAMPLE_SPEED && turn < MAX_SAMPLE_TURN
                    && acceleration <= -MIN_COAST_DECELERATION && acceleration >= -MAX_COAST_DECELERATION) {
                if (++slowingSamples >= COAST_SAMPLES) {
                    coasting = true;
                }
            } else {
                slowingSamples = 0;
            }

            previousSpeed = speed;
            previousDuration = elapsed;
            elapsed = 0;
            distance = 0;
            turn = 0;
            return coasting;
        }

        void reset() {
            elapsed = 0;
            distance = 0;
            turn = 0;
            previousSpeed = 0;
            previousDuration = 0;
            slowingSamples = 0;
            coasting = false;
        }
    }

    static final class WakeState implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private double previousYaw;
        private float paddleCooldown;
        private double paddleSpeed;
        private int paddleSide = 1;
        private String rowingAnimation;
        private float rowingCycleRemaining;
        private float rowingGrace;
        private final RowingMotion rowingMotion = new RowingMotion();
        private RowingMode rowingMode;
        private int turnDirection;
        private float turnGrace;
        private boolean reversing;
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
            copy.rowingAnimation = rowingAnimation;
            copy.rowingCycleRemaining = rowingCycleRemaining;
            copy.rowingGrace = rowingGrace;
            copy.rowingMode = rowingMode;
            copy.turnDirection = turnDirection;
            copy.turnGrace = turnGrace;
            copy.reversing = reversing;
            copy.idleTime = idleTime;
            copy.idleRollViewers.addAll(idleRollViewers);
            return copy;
        }
    }
}
