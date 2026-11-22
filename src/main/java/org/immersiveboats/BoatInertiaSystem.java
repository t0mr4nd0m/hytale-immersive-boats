package org.immersiveboats;

import com.hypixel.hytale.builtin.mounts.MountSystems;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.vehicle.BoatComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.collision.CollisionConfig;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.modules.collision.TangiableEntitySpatialSystem;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class BoatInertiaSystem extends EntityTickingSystem<EntityStore> {
    private static final double WATER_DRAG = 0.85;
    private static final double ANGULAR_DRAG = 2.2;
    private static final double MAX_ANGULAR_SPEED = 1.1;
    private static final double STOP_ANGULAR_SPEED = 0.025;
    private static final double MAX_ANGULAR_STEP = 0.06;
    private static final double STOP_SPEED = 0.12;
    private static final double MAX_SPEED = 12.0;
    private static final double MAX_STEP = 0.10;
    private static final double IMPACT_TRANSFER = 0.80;
    private static final double IMPACT_TURN = 0.65;
    // A piloted boat may stop a little short of the hull on the client.
    private static final double PILOT_CONTACT_MARGIN = 0.16;
    private static final double DRAFT = 0.09;
    private static final double MAX_PILOT_GLIDE_SPEED = 3.0;
    private static final double PILOT_RELEASE_WINDOW = 0.20;
    private static final double PILOT_GLIDE_REARM_SECONDS = 2.0;
    private static final double ENTITY_CONTACT_MARGIN = 0.22;
    private static final double ENTITY_IMPACT_TRANSFER = 0.55;
    private static final double MIN_ENTITY_CLOSING_SPEED = 0.25;
    private static final double MAX_IDLE_PILOT_SPEED = 0.45;
    private static final double PILOT_PUSH_INTERVAL = 0.40;

    private final ComponentType<EntityStore, Inertia> inertia;
    private final BoatPhysicsSettings settings;
    private final VelocityConfig pilotDrag = new VelocityConfig();
    private final Query<EntityStore> query = Query.and(BoatComponent.getComponentType(),
            TransformComponent.getComponentType(), BoundingBox.getComponentType());
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, MountSystems.HandleMountInput.class),
            new SystemDependency<>(Order.AFTER, TangiableEntitySpatialSystem.class),
            new SystemDependency<>(Order.BEFORE, BoatWakeSystem.class));

    BoatInertiaSystem(ComponentType<EntityStore, Inertia> inertia, BoatPhysicsSettings settings) {
        this.inertia = inertia;
        this.settings = settings;
        pilotDrag.setAirResistance(0.97f);
        pilotDrag.setGroundResistance(0.90f);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    @Nonnull
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
        if (dt <= 0) {
            return;
        }
        var boat = chunk.getReferenceTo(index);
        var physics = settings.state();
        var state = chunk.getComponent(index, inertia);
        if (!physics.enabled()) {
            if (state != null) {
                commands.removeComponent(boat, inertia);
            }
            return;
        }
        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        var position = transform.getPosition();
        var pilot = getPilot(commands, boat);
        if (state == null || state.physics != physics) {
            var fresh = new Inertia();
            fresh.physics = physics;
            fresh.previous.set(position);
            fresh.pilot = pilot;
            if (state == null) {
                commands.addComponent(boat, inertia, fresh);
            } else {
                commands.replaceComponent(boat, inertia, fresh);
            }
            return;
        }

        if (!Objects.equals(pilot, state.pilot)) {
            state.pilot = pilot;
            state.wasRowing = false;
            state.releaseRemaining = 0;
            state.glideRearm = 0;
            state.pilotPushCooldown = 0;
            if (pilot != null) {
                state.previous.set(position);
                state.stop();
                return;
            }
        }
        state.pilotPushCooldown = Math.max(0, state.pilotPushCooldown - dt);

        var world = store.getExternalData().getWorld();
        var bounds = boatBounds(commands, boat, chunk.getComponent(index, BoundingBox.getComponentType()));
        var hull = Hull.of(bounds, transform.getRotation().yaw() - Math.PI);
        if (!navigable(world, position, hull)) {
            state.stop();
            state.wasRowing = false;
            state.releaseRemaining = 0;
            state.entityPositions.clear();
        } else {
            var contacts = nearby(commands, boat, position, false);
            if (pilot != null) {
                var lastVelocity = new Vector3d(state.velocity);
                samplePilot(world, commands, boat, state, hull, state.previous, position, dt);
                receiveEntityPush(commands, boat, state, hull, position, contacts, dt);
                updatePilotGlide(world, commands, boat, pilot, state, hull, position, lastVelocity, dt);
            } else if (position.distanceSquared(state.previous) <= 0.25) {
                receiveEntityPush(commands, boat, state, hull, position, contacts, dt);
                drift(world, commands, state, hull, bounds, transform, contacts, Math.min(dt, 0.25));
            } else {
                state.stop();
                state.entityPositions.clear();
            }
        }
        state.previous.set(position);
    }

    private void receiveEntityPush(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state,
                                   Hull hull, Vector3d position, ArrayList<Obstacle> contacts, double dt) {
        var previousPositions = state.entityPositions;
        var currentPositions = state.nextEntityPositions;
        currentPositions.clear();
        state.entityPositions = currentPositions;
        state.nextEntityPositions = previousPositions;
        var targetCenter = hull.center(position);
        var normal = new Vector3d();
        var strongestNormal = new Vector3d();
        double strongestSpeed = MIN_ENTITY_CLOSING_SPEED;
        double lever = 0;
        for (var entity : contacts) {
            if (entity.boat) {
                continue;
            }
            var current = new Vector3d(entity.position);
            currentPositions.put(entity.ref, current);
            var previous = previousPositions.get(entity.ref);
            if (previous == null) {
                continue;
            }
            var travel = current.sub(previous, new Vector3d());
            double distance = Math.hypot(travel.x, travel.z);
            if (distance < 0.001 || distance > 1.5 || Math.abs(travel.y) > 1.0) {
                continue;
            }
            var velocity = new Vector3d(travel.x, 0, travel.z).mul(Math.min(1 / dt, MAX_SPEED / distance));
            int steps = (int) Math.ceil(distance / MAX_STEP);
            var sample = new Vector3d();
            for (int step = 1; step <= steps; step++) {
                previous.fma((double) step / steps, travel, sample);
                var delta = targetCenter.sub(entity.hull.center(sample), new Vector3d());
                if (entity.hull.contactDepth(sample, hull, position, delta, velocity, normal) < -ENTITY_CONTACT_MARGIN) {
                    continue;
                }
                double speed = velocity.dot(normal) - state.velocity.dot(normal);
                if (speed <= MIN_ENTITY_CLOSING_SPEED) {
                    continue;
                }
                if (speed > strongestSpeed) {
                    strongestSpeed = speed;
                    strongestNormal.set(normal);
                    lever = entity.hull.impactLever(hull, delta, normal);
                }
                break;
            }
        }
        if (strongestSpeed > MIN_ENTITY_CLOSING_SPEED) {
            applyImpact(commands, boat, state, strongestNormal, strongestSpeed * ENTITY_IMPACT_TRANSFER, lever);
        }
    }

    private void updatePilotGlide(World world, CommandBuffer<EntityStore> commands, Ref<EntityStore> boat,
                                  Ref<EntityStore> pilot, Inertia state, Hull hull, Vector3d position,
                                  Vector3d lastVelocity, double dt) {
        state.glideRearm = Math.max(0, state.glideRearm - dt);
        var movement = commands.getComponent(boat, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        if (states == null || state.glideRearm > 0 || state.pilotPushCooldown > 0) {
            state.wasRowing = false;
            state.releaseRemaining = 0;
            return;
        }
        if (!states.horizontalIdle && !states.idle) {
            state.wasRowing = state.velocity.lengthSquared() >= 0.5 * 0.5;
            state.releaseRemaining = 0;
            return;
        }
        if (state.wasRowing) {
            state.wasRowing = false;
            state.releaseVelocity.set(lastVelocity);
            state.releaseRemaining = PILOT_RELEASE_WINDOW;
        } else {
            state.releaseRemaining -= dt;
        }
        if (state.releaseRemaining <= 0) {
            return;
        }

        double speed = state.releaseVelocity.length();
        if (speed < 0.5 || position.distanceSquared(state.previous) > 1.5 * 1.5
                || Math.abs(position.y - state.previous.y) > 0.5) {
            state.releaseRemaining = 0;
            return;
        }
        var direction = state.releaseVelocity.div(speed, new Vector3d());
        double currentSpeed = state.velocity.dot(direction);
        double impulse = Math.min(MAX_PILOT_GLIDE_SPEED, speed * 0.8 - Math.max(0, currentSpeed));
        if (impulse < 0.35) {
            return;
        }
        state.releaseRemaining = 0;
        var velocity = commands.getComponent(pilot, Velocity.getComponentType());
        if (velocity == null || commands.getComponent(pilot, PlayerRef.getComponentType()) == null) {
            return;
        }
        var boxes = hull.blockBoxes();
        var obstacles = nearby(commands, boat, position, false);
        var result = new CollisionResult();
        var probe = new Vector3d();
        for (int step = 1; step <= 4; step++) {
            position.fma(step * MAX_STEP, direction, probe);
            if (!navigable(world, probe, hull) || blockedByLand(world, boxes, position, boxes, probe, result)
                    || contact(world, commands, obstacles, hull, probe, direction, 0, false)) {
                return;
            }
        }
        // Let the client's movement controller apply and decay the impulse, then report the boat's position.
        velocity.addInstruction(direction.mul(impulse), pilotDrag, ChangeVelocityType.Add);
        state.glideRearm = PILOT_GLIDE_REARM_SECONDS;
    }

    private void samplePilot(World world, CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state,
                             Hull hull, Vector3d previous, Vector3d position, double dt) {
        var travel = position.sub(previous, new Vector3d());
        double distance = Math.hypot(travel.x, travel.z);
        if (distance > 1.5 || Math.abs(travel.y) > 0.5) {
            state.velocity.zero();
            return;
        }
        if (distance < 0.001) {
            // Input packets and server ticks need not arrive at the same cadence.
            state.velocity.mul(Math.exp(-8 * dt));
            return;
        }
        state.velocity.set(travel.x, 0, travel.z).mul(Math.min(1 / dt, MAX_SPEED / distance));
        var contacts = nearby(commands, boat, position, true);
        int steps = (int) Math.ceil(distance / MAX_STEP);
        var candidate = new Vector3d();
        for (int step = 1; step <= steps; step++) {
            previous.fma((double) step / steps, travel, candidate);
            if (contact(world, commands, contacts, hull, candidate, state.velocity, PILOT_CONTACT_MARGIN, true)) {
                state.velocity.zero();
                return;
            }
        }
    }

    private void drift(World world, CommandBuffer<EntityStore> commands, Inertia state, Hull hull, Box bounds,
                       TransformComponent transform, ArrayList<Obstacle> contacts, double dt) {
        var position = transform.getPosition();
        if (state.velocity.lengthSquared() < STOP_SPEED * STOP_SPEED
                && Math.abs(state.angularSpeed) < STOP_ANGULAR_SPEED) {
            state.stop();
            return;
        }
        var result = new CollisionResult();
        if (state.velocity.lengthSquared() >= STOP_SPEED * STOP_SPEED) {
            slide(world, commands, state, hull, position, contacts, result, dt);
        } else {
            state.velocity.zero();
        }
        if (Math.abs(state.angularSpeed) >= STOP_ANGULAR_SPEED) {
            turn(world, state, hull, bounds, transform, contacts, result, dt);
        } else {
            state.angularSpeed = 0;
        }
    }

    private void slide(World world, CommandBuffer<EntityStore> commands, Inertia state, Hull hull,
                       Vector3d position, ArrayList<Obstacle> contacts, CollisionResult result, double dt) {
        double decay = Math.exp(-WATER_DRAG * dt);
        var travel = state.velocity.mul((1 - decay) / WATER_DRAG, new Vector3d());
        int steps = (int) Math.ceil(travel.length() / MAX_STEP);
        var step = travel.div(steps);
        var boxes = hull.blockBoxes();
        var candidate = new Vector3d();
        for (int i = 0; i < steps; i++) {
            position.add(step, candidate);
            if (!canSlide(world, hull, boxes, position, candidate, result)) {
                // Preserve the part of a diagonal push that runs along the shore.
                candidate.set(position).add(step.x, 0, 0);
                if (step.x != 0 && step.z != 0 && canSlide(world, hull, boxes, position, candidate, result)) {
                    step.z = 0;
                    state.velocity.z = 0;
                } else {
                    candidate.set(position).add(0, 0, step.z);
                    if (step.x == 0 || step.z == 0 || !canSlide(world, hull, boxes, position, candidate, result)) {
                        state.stop();
                        return;
                    }
                    step.x = 0;
                    state.velocity.x = 0;
                }
            }
            if (contact(world, commands, contacts, hull, candidate, state.velocity, 0, true)) {
                state.velocity.zero();
                return;
            }
            position.set(candidate);
        }
        state.velocity.mul(decay);
    }

    private static boolean canSlide(World world, Hull hull, Box[] boxes, Vector3d position, Vector3d candidate,
                                    CollisionResult result) {
        return navigable(world, candidate, hull) && !blockedByLand(world, boxes, position, boxes, candidate, result);
    }

    private static void turn(World world, Inertia state, Hull hull, Box bounds, TransformComponent transform,
                             ArrayList<Obstacle> contacts, CollisionResult result, double dt) {
        double decay = Math.exp(-ANGULAR_DRAG * dt);
        double arc = state.angularSpeed * (1 - decay) / ANGULAR_DRAG;
        int steps = (int) Math.ceil(Math.abs(arc) / MAX_ANGULAR_STEP);
        float step = (float) (arc / steps);
        var position = transform.getPosition();
        var rotation = transform.getRotation();
        for (int i = 0; i < steps; i++) {
            float yaw = (float) Math.IEEEremainder(rotation.yaw() + step, 2 * Math.PI);
            var next = Hull.of(bounds, yaw - Math.PI);
            if (!navigable(world, position, next)
                    || blockedByLand(world, hull.blockBoxes(), position, next.blockBoxes(), position, result)
                    || obstructsTurn(hull, next, position, contacts)) {
                state.angularSpeed = 0;
                return;
            }
            rotation.setYaw(yaw);
            hull = next;
        }
        state.angularSpeed *= decay;
    }

    private static boolean obstructsTurn(Hull hull, Hull next, Vector3d position, ArrayList<Obstacle> contacts) {
        var center = hull.center(position);
        var nextCenter = next.center(position);
        for (var other : contacts) {
            if (other.item) {
                continue;
            }
            var delta = other.hull.center(other.position).sub(center);
            var nextDelta = new Vector3d(delta).add(center).sub(nextCenter);
            double depth = next.contactDepth(position, other.hull, other.position, nextDelta, null, null);
            double currentDepth = hull.contactDepth(position, other.hull, other.position, delta, null, null);
            if (depth > Math.max(0, currentDepth) + 1e-6) {
                return true;
            }
        }
        return false;
    }

    private ArrayList<Obstacle> nearby(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat,
                                       Vector3d position, boolean boatsOnly) {
        var refs = SpatialResource.<EntityStore>getThreadLocalReferenceList();
        commands.getResource(CollisionModule.get().getTangibleEntitySpatialResourceType())
                .getSpatialStructure().collect(position, 6, refs);
        var contacts = new ArrayList<Obstacle>();
        for (var other : refs) {
            if (other.equals(boat) || !other.isValid()) {
                continue;
            }
            boolean otherBoat = commands.getComponent(other, BoatComponent.getComponentType()) != null;
            if (boatsOnly && !otherBoat) {
                continue;
            }
            var mount = commands.getComponent(other, MountedComponent.getComponentType());
            if (mount != null && !mount.isBlockMount()) {
                continue;
            }
            var transform = commands.getComponent(other, TransformComponent.getComponentType());
            var bounds = commands.getComponent(other, BoundingBox.getComponentType());
            if (transform != null && bounds != null) {
                var box = otherBoat ? boatBounds(commands, other, bounds) : bounds.getBoundingBox();
                var hull = Hull.of(box, otherBoat ? transform.getRotation().yaw() - Math.PI : 0);
                boolean item = commands.getComponent(other, ItemComponent.getComponentType()) != null;
                contacts.add(new Obstacle(other, otherBoat, item, hull, transform.getPosition()));
            }
        }
        return contacts;
    }

    private boolean contact(World world, CommandBuffer<EntityStore> commands, ArrayList<Obstacle> contacts,
                            Hull hull, Vector3d candidate, Vector3d velocity, double margin, boolean transfer) {
        var center = hull.center(candidate);
        var normal = new Vector3d();
        for (var other : contacts) {
            if (other.item) {
                continue;
            }
            var delta = other.hull.center(other.position).sub(center);
            if (hull.contactDepth(candidate, other.hull, other.position, delta, velocity, normal) < -margin) {
                continue;
            }
            double closingSpeed = velocity.dot(normal);
            if (closingSpeed <= 0) {
                continue;
            }
            if (transfer && other.boat && navigable(world, other.position, other.hull)) {
                var target = commands.getComponent(other.ref, inertia);
                if (target != null && target.physics == settings.state()) {
                    double targetSpeed = target.velocity.dot(normal);
                    double impulse = IMPACT_TRANSFER * Math.max(0, closingSpeed - targetSpeed);
                    applyImpact(commands, other.ref, target, normal, impulse, hull.impactLever(other.hull, delta, normal));
                }
            }
            return true;
        }
        return false;
    }

    private void applyImpact(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state,
                             Vector3d normal, double impulse, double lever) {
        var pilot = getPilot(commands, boat);
        if (pilot == null) {
            state.velocity.fma(impulse, normal);
            state.angularSpeed = Math.clamp(state.angularSpeed + lever * impulse * IMPACT_TURN,
                    -MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED);
            if (state.velocity.lengthSquared() > MAX_SPEED * MAX_SPEED) {
                state.velocity.normalize(MAX_SPEED);
            }
            return;
        }
        if (impulse < STOP_SPEED || state.pilotPushCooldown > 0
                || state.velocity.lengthSquared() > MAX_IDLE_PILOT_SPEED * MAX_IDLE_PILOT_SPEED
                || commands.getComponent(pilot, PlayerRef.getComponentType()) == null) {
            return;
        }
        var movement = commands.getComponent(boat, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        var velocity = commands.getComponent(pilot, Velocity.getComponentType());
        if (velocity == null || states == null || (!states.idle && !states.horizontalIdle)) {
            return;
        }
        var push = normal.mul(Math.min(impulse, MAX_PILOT_GLIDE_SPEED), new Vector3d());
        velocity.addInstruction(push, pilotDrag, ChangeVelocityType.Add);
        state.pilotPushCooldown = PILOT_PUSH_INTERVAL;
        state.releaseRemaining = 0;
        state.wasRowing = false;
    }

    private static Box boatBounds(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, BoundingBox bounds) {
        var model = commands.getComponent(boat, ModelComponent.getComponentType());
        var hull = model == null ? null : model.getModel().getBoundingBox();
        return hull == null ? bounds.getBoundingBox() : hull;
    }

    private static Ref<EntityStore> getPilot(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat) {
        var riders = commands.getComponent(boat, MountedByComponent.getComponentType());
        var driver = riders == null ? null : riders.getPassengerInSeat(MountedComponent.DRIVER_SEAT_INDEX, commands);
        return driver != null && driver.isValid() ? driver : null;
    }

    private static boolean navigable(World world, Vector3d position, Hull hull) {
        double keel = position.y + hull.bottom;
        if (waterAtKeel(world, position.x, keel, position.z)) {
            return true;
        }
        for (int across = -1; across <= 1; across++) {
            for (int along = -1; along <= 1; along++) {
                double x = hull.x + across * hull.halfWidth * 0.85;
                double z = hull.z + along * hull.halfLength * 0.85;
                if (waterAtKeel(world, position.x + x * hull.cos + z * hull.sin, keel,
                        position.z + z * hull.cos - x * hull.sin)) {
                    // Water under the ends is enough unless the centre is supported by land.
                    var support = new Box(-0.05, hull.bottom - DRAFT, -0.05, 0.05, hull.bottom, 0.05);
                    int validation = CollisionModule.get().validatePosition(world, support, position,
                            CollisionConfig.MATERIAL_SOLID, null, (_, _, _, config) -> isHardBlock(config),
                            new CollisionResult());
                    return validation != CollisionModule.VALIDATE_INVALID;
                }
            }
        }
        return false;
    }

    private static boolean waterAtKeel(World world, double x, double keel, double z) {
        double surface = BoatWakeSystem.waterSurface(world.getChunkStore(), x, keel, z);
        return Math.abs(surface - keel) <= 2 * DRAFT;
    }

    private static boolean blockedByLand(World world, Box[] previous, Vector3d position, Box[] next,
                                         Vector3d candidate, CollisionResult result) {
        for (int i = 0; i < next.length; i++) {
            var before = previous[i];
            var after = next[i];
            int validation = CollisionModule.get().validatePosition(world, after, candidate,
                    CollisionConfig.MATERIAL_SOLID, null, (_, _, _, config) ->
                            isHardBlock(config) && deepensOverlap(before, position, after, candidate, config), result);
            if (validation == CollisionModule.VALIDATE_INVALID) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHardBlock(CollisionConfig config) {
        var block = config.blockType;
        return block == null || block.getGathering() == null || !block.getGathering().isSoft();
    }

    private static boolean deepensOverlap(Box before, Vector3d position, Box after, Vector3d candidate,
                                          CollisionConfig config) {
        int x = config.blockX + config.getBoundingBoxOffsetX();
        int y = config.blockY + config.getBoundingBoxOffsetY();
        int z = config.blockZ + config.getBoundingBoxOffsetZ();
        int details = config.getDetailCount();
        for (int i = 0; i < Math.max(1, details); i++) {
            var block = details <= 1 ? config.getBoundingBox() : config.getBoundingBox(i);
            if (landOverlap(after, candidate, block, x, y, z) > landOverlap(before, position, block, x, y, z) + 1e-7) {
                return true;
            }
        }
        return false;
    }

    private static double landOverlap(Box hull, Vector3d position, Box block, int x, int y, int z) {
        if (hull.max.y + position.y <= block.min.y + y || hull.min.y + position.y >= block.max.y + y) {
            return 0;
        }
        double depthX = Math.min(hull.max.x + position.x - (block.min.x + x),
                block.max.x + x - (hull.min.x + position.x));
        double depthZ = Math.min(hull.max.z + position.z - (block.min.z + z),
                block.max.z + z - (hull.min.z + position.z));
        double depth = Math.min(depthX, depthZ);
        return Math.max(0, depth);
    }

    private record Obstacle(Ref<EntityStore> ref, boolean boat, boolean item, Hull hull, Vector3d position) {
    }

    private record Hull(double x, double z, double halfWidth, double halfLength,
                        double bottom, double top, double cos, double sin) {
        static Hull of(Box box, double yaw) {
            return new Hull((box.min.x + box.max.x) / 2, (box.min.z + box.max.z) / 2,
                    (box.max.x - box.min.x) / 2, (box.max.z - box.min.z) / 2,
                    box.min.y, box.max.y, Math.cos(yaw), Math.sin(yaw));
        }

        Vector3d center(Vector3d position) {
            return new Vector3d(position).add(x * cos + z * sin, 0, z * cos - x * sin);
        }

        double contactDepth(Vector3d position, Hull other, Vector3d otherPosition, Vector3d delta,
                            Vector3d velocity, Vector3d normal) {
            if (position.y + top <= otherPosition.y + other.bottom
                    || otherPosition.y + other.top <= position.y + bottom) {
                return Double.NEGATIVE_INFINITY;
            }
            // The shallowest overlap identifies the face in contact, even when the centres are far offset.
            double minDepth = Double.POSITIVE_INFINITY;
            double bestSpeed = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                var axes = i < 2 ? this : other;
                boolean lateral = i % 2 == 0;
                double ax = lateral ? axes.cos : axes.sin;
                double az = lateral ? -axes.sin : axes.cos;
                double projection = delta.x * ax + delta.z * az;
                double depth = radius(ax, az) + other.radius(ax, az) - Math.abs(projection);
                double sign = projection < 0 ? -1 : 1;
                double speed = velocity == null ? 0 : sign * (velocity.x * ax + velocity.z * az);
                if (depth < minDepth - 1e-9 || (Math.abs(depth - minDepth) <= 1e-9 && speed > bestSpeed)) {
                    bestSpeed = speed;
                    if (normal != null) {
                        normal.set(sign * ax, 0, sign * az);
                    }
                }
                minDepth = Math.min(minDepth, depth);
            }
            return minDepth;
        }

        double impactLever(Hull target, Vector3d delta, Vector3d normal) {
            // Estimate the contact point from the overlap along the tangent of the struck face.
            double tx = -normal.z;
            double tz = normal.x;
            double sourceCenter = -(delta.x * tx + delta.z * tz);
            double sourceRadius = radius(tx, tz);
            double targetRadius = target.radius(tx, tz);
            double start = Math.max(-targetRadius, sourceCenter - sourceRadius);
            double end = Math.min(targetRadius, sourceCenter + sourceRadius);
            return Math.clamp((start + end) * 0.5, -targetRadius, targetRadius);
        }

        private double radius(double ax, double az) {
            return halfWidth * Math.abs(ax * cos - az * sin) + halfLength * Math.abs(ax * sin + az * cos);
        }

        Box[] blockBoxes() {
            // Match the inset hull used by SpawnBoatInteraction for terrain collisions.
            double inset = 0.2;
            double width = Math.max(0, halfWidth - inset);
            double stern = z - halfLength + inset + width;
            double bow = z + halfLength - inset - width * 0.6;
            var boxes = new Box[4];
            for (int i = 0; i < boxes.length; i++) {
                double size = i == boxes.length - 1 ? width * 0.6 : width;
                double localZ = stern + (bow - stern) * i / (boxes.length - 1);
                double cx = x * cos + localZ * sin;
                double cz = localZ * cos - x * sin;
                boxes[i] = new Box(cx - size, bottom, cz - size, cx + size, top, cz + size);
            }
            return boxes;
        }
    }

    static final class Inertia implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private final Vector3d velocity = new Vector3d();
        private final Vector3d releaseVelocity = new Vector3d();
        private Ref<EntityStore> pilot;
        private boolean wasRowing;
        private double releaseRemaining;
        private double glideRearm;
        private double angularSpeed;
        private double pilotPushCooldown;
        private BoatPhysicsSettings.State physics;
        private Map<Ref<EntityStore>, Vector3d> entityPositions = new HashMap<>();
        private Map<Ref<EntityStore>, Vector3d> nextEntityPositions = new HashMap<>();

        private void stop() {
            velocity.zero();
            angularSpeed = 0;
        }

        @Override
        @SuppressWarnings("MethodDoesntCallSuperMethod")
        public Inertia clone() {
            // Movement history and pending impulses belong to the original boat.
            return new Inertia();
        }
    }
}
