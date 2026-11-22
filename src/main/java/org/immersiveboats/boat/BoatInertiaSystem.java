package org.immersiveboats.boat;

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
import org.immersiveboats.settings.BoatPhysicsSettings;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class BoatInertiaSystem extends EntityTickingSystem<EntityStore> {
    private static final double WATER_DRAG = 0.85;
    private static final double ANGULAR_DRAG = 2.2;
    private static final double MAX_ANGULAR_SPEED = 1.1;
    private static final double STOP_ANGULAR_SPEED = 0.025;
    private static final double MAX_ANGULAR_STEP = 0.06;
    private static final double STOP_SPEED = 0.12;
    private static final double MAX_SPEED = 12.0;
    private static final double MAX_STEP = 0.10;
    private static final double IMPACT_TRANSFER = 0.80;
    private static final double CLOSING_SPEED_EPSILON = 1e-6;
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

    public BoatInertiaSystem(ComponentType<EntityStore, Inertia> inertia, BoatPhysicsSettings settings) {
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
                cancelPilotGlide(commands, boat, state);
                commands.removeComponent(boat, inertia);
            }
            return;
        }
        var transform = chunk.getComponent(index, TransformComponent.getComponentType());
        var boundingBox = chunk.getComponent(index, BoundingBox.getComponentType());
        if (transform == null || boundingBox == null) {
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
                cancelPilotGlide(commands, boat, state);
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
        var bounds = BoatHull.bounds(chunk.getComponent(index, ModelComponent.getComponentType()), boundingBox);
        if (bounds == null) {
            return;
        }
        var hull = BoatHull.forBoat(bounds, transform.getRotation().yaw());
        if (!navigable(world, position, hull)) {
            state.stop();
            state.wasRowing = false;
            state.releaseRemaining = 0;
            state.entityPositions.clear();
        } else if (pilot != null) {
            var contacts = nearby(commands, boat, position, false);
            var lastVelocity = new Vector3d(state.velocity);
            var collisionHull = BoatHull.of(boundingBox.getBoundingBox(), 0);
            samplePilot(world, commands, boat, state, hull, collisionHull, state.previous, position, dt);
            receiveEntityPush(commands, boat, state, hull, position, contacts, dt);
            updatePilotGlide(world, commands, boat, pilot, state, hull, position, contacts, lastVelocity, dt);
        } else if (position.distanceSquared(state.previous) <= 0.25) {
            var contacts = nearby(commands, boat, position, false);
            receiveEntityPush(commands, boat, state, hull, position, contacts, dt);
            drift(world, commands, state, hull, bounds, transform, contacts, Math.min(dt, 0.25));
        } else {
            state.stop();
            state.entityPositions.clear();
        }
        state.previous.set(position);
    }

    private void receiveEntityPush(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state,
                                   BoatHull hull, Vector3d position, ArrayList<Obstacle> contacts, double dt) {
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
            // Standing in the hull or stepping off it is not a lateral impact.
            if (previous == null || hull.isOnBoard(position, current, entity.hull.bottom())
                    || hull.isOnBoard(state.previous, previous, entity.hull.bottom())) {
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
                                  Ref<EntityStore> pilot, Inertia state, BoatHull hull, Vector3d position,
                                  ArrayList<Obstacle> contacts, Vector3d lastVelocity, double dt) {
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
        var result = new CollisionResult();
        var probe = new Vector3d();
        var glideVelocity = new Vector3d(state.velocity).fma(impulse, direction);
        for (int step = 1; step <= 4; step++) {
            position.fma(step * MAX_STEP, direction, probe);
            if (!navigable(world, probe, hull) || blockedByLand(world, boxes, position, boxes, probe, result)
                    || contact(world, commands, contacts, hull, probe, glideVelocity, 0, false)) {
                return;
            }
        }
        // Let the client's movement controller apply and decay the impulse, then report the boat's position.
        velocity.addInstruction(direction.mul(impulse), pilotDrag, ChangeVelocityType.Add);
        state.glideRearm = PILOT_GLIDE_REARM_SECONDS;
    }

    private static void cancelPilotGlide(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state) {
        if (state.glideRearm <= 0 || state.pilot == null || !state.pilot.equals(getPilot(commands, boat))) {
            return;
        }
        var movement = commands.getComponent(boat, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        var velocity = commands.getComponent(state.pilot, Velocity.getComponentType());
        if (velocity != null && states != null && (states.idle || states.horizontalIdle)) {
            // The client is still applying the glide impulse after the server discards its inertia state.
            velocity.addInstruction(new Vector3d(), null, ChangeVelocityType.Set);
        }
    }

    private void samplePilot(World world, CommandBuffer<EntityStore> commands, Ref<EntityStore> boat, Inertia state,
                             BoatHull hull, BoatHull collisionHull, Vector3d previous, Vector3d position, double dt) {
        var travel = position.sub(previous, new Vector3d());
        double distance = Math.hypot(travel.x, travel.z);
        if (!samplePilotVelocity(travel, distance, dt, state.velocity)) {
            return;
        }
        var contacts = nearby(commands, boat, position, true);
        int steps = (int) Math.ceil(distance / MAX_STEP);
        var candidate = new Vector3d();
        for (int step = 1; step <= steps; step++) {
            previous.fma((double) step / steps, travel, candidate);
            // Entity collision bounds can stop the pilot short of the rotated model hull.
            if (contact(world, commands, contacts, hull, candidate, state.velocity, PILOT_CONTACT_MARGIN, true)
                    || contact(world, commands, contacts, collisionHull, candidate, state.velocity,
                    PILOT_CONTACT_MARGIN, true, true)) {
                state.velocity.zero();
                return;
            }
        }
    }

    private static boolean samplePilotVelocity(Vector3d travel, double distance, double dt, Vector3d velocity) {
        if (distance > 1.5 || Math.abs(travel.y) > 0.5) {
            velocity.zero();
            return false;
        }
        if (distance < 0.001) {
            // Input packets and server ticks need not arrive at the same cadence.
            velocity.mul(Math.exp(-8 * dt));
            return false;
        }
        velocity.set(travel.x, 0, travel.z).mul(Math.min(1 / dt, MAX_SPEED / distance));
        return true;
    }

    private void drift(World world, CommandBuffer<EntityStore> commands, Inertia state, BoatHull hull, Box bounds,
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

    private void slide(World world, CommandBuffer<EntityStore> commands, Inertia state, BoatHull hull,
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

    private static boolean canSlide(World world, BoatHull hull, Box[] boxes, Vector3d position, Vector3d candidate,
                                    CollisionResult result) {
        return navigable(world, candidate, hull) && !blockedByLand(world, boxes, position, boxes, candidate, result);
    }

    private static void turn(World world, Inertia state, BoatHull hull, Box bounds, TransformComponent transform,
                             ArrayList<Obstacle> contacts, CollisionResult result, double dt) {
        double decay = Math.exp(-ANGULAR_DRAG * dt);
        double arc = state.angularSpeed * (1 - decay) / ANGULAR_DRAG;
        int steps = (int) Math.ceil(Math.abs(arc) / MAX_ANGULAR_STEP);
        float step = (float) (arc / steps);
        var position = transform.getPosition();
        var rotation = transform.getRotation();
        for (int i = 0; i < steps; i++) {
            float yaw = (float) Math.IEEEremainder(rotation.yaw() + step, 2 * Math.PI);
            var next = BoatHull.forBoat(bounds, yaw);
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

    private static boolean obstructsTurn(BoatHull hull, BoatHull next, Vector3d position, ArrayList<Obstacle> contacts) {
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
                var box = bounds.getBoundingBox();
                var hullBounds = otherBoat
                        ? BoatHull.bounds(commands.getComponent(other, ModelComponent.getComponentType()), bounds) : box;
                if (hullBounds == null) {
                    continue;
                }
                var hull = otherBoat ? BoatHull.forBoat(hullBounds, transform.getRotation().yaw())
                        : BoatHull.of(hullBounds, 0);
                boolean item = commands.getComponent(other, ItemComponent.getComponentType()) != null;
                var collisionHull = boatsOnly ? BoatHull.of(bounds.getBoundingBox(), 0) : hull;
                contacts.add(new Obstacle(other, otherBoat, item, hull, collisionHull, transform.getPosition()));
            }
        }
        return contacts;
    }

    private boolean contact(World world, CommandBuffer<EntityStore> commands, ArrayList<Obstacle> contacts,
                            BoatHull hull, Vector3d candidate, Vector3d velocity, double margin, boolean transfer) {
        return contact(world, commands, contacts, hull, candidate, velocity, margin, transfer, false);
    }

    private boolean contact(World world, CommandBuffer<EntityStore> commands, ArrayList<Obstacle> contacts,
                            BoatHull hull, Vector3d candidate, Vector3d velocity, double margin, boolean transfer,
                            boolean useCollisionBounds) {
        var contact = new Contact(hull, candidate, velocity);
        var physics = settings.state();
        for (var other : contacts) {
            if (other.item) {
                continue;
            }
            var otherHull = useCollisionBounds ? other.collisionHull : other.hull;
            var target = other.boat ? commands.getComponent(other.ref, inertia) : null;
            boolean hasTargetVelocity = target != null && target.physics == physics;
            var targetVelocity = hasTargetVelocity ? target.contactVelocity : null;
            double impulse = contact.impulse(otherHull, other.position, targetVelocity, margin);
            if (impulse <= 0) {
                continue;
            }
            if (transfer && hasTargetVelocity && navigable(world, other.position, other.hull)) {
                applyImpact(commands, other.ref, target, contact.normal, impulse,
                        hull.impactLever(otherHull, contact.delta, contact.normal));
            }
            return true;
        }
        return false;
    }

    static final class Contact {
        private final BoatHull hull;
        private final Vector3d position;
        private final Vector3d velocity;
        private final Vector3d center;
        private final Vector3d delta = new Vector3d();
        private final Vector3d normal = new Vector3d();
        private final Vector3d relativeVelocity = new Vector3d();

        Contact(BoatHull hull, Vector3d position, Vector3d velocity) {
            this.hull = hull;
            this.position = position;
            this.velocity = velocity;
            center = hull.center(position);
        }

        double impulse(BoatHull other, Vector3d otherPosition, Vector3d targetVelocity, double margin) {
            relativeVelocity.set(velocity);
            if (targetVelocity != null) {
                relativeVelocity.sub(targetVelocity);
            }
            delta.set(other.center(otherPosition)).sub(center);
            if (hull.contactDepth(position, other, otherPosition, delta, relativeVelocity, normal) < -margin) {
                return 0;
            }
            double closingSpeed = relativeVelocity.dot(normal);
            return closingSpeed > CLOSING_SPEED_EPSILON ? IMPACT_TRANSFER * closingSpeed : 0;
        }
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

    private static Ref<EntityStore> getPilot(CommandBuffer<EntityStore> commands, Ref<EntityStore> boat) {
        var riders = commands.getComponent(boat, MountedByComponent.getComponentType());
        var driver = riders == null ? null : riders.getPassengerInSeat(MountedComponent.DRIVER_SEAT_INDEX, commands);
        return driver != null && driver.isValid() ? driver : null;
    }

    private static boolean navigable(World world, Vector3d position, BoatHull hull) {
        return navigable(position, hull, (x, keel, z) -> BoatWakeSystem.waterSurface(world.getChunkStore(), x, keel, z),
                () -> {
                    var support = new Box(-0.05, hull.bottom() - DRAFT, -0.05, 0.05, hull.bottom(), 0.05);
                    return CollisionModule.get().validatePosition(world, support, position,
                            CollisionConfig.MATERIAL_SOLID, null, (_, _, _, config) -> isHardBlock(config),
                            new CollisionResult()) == CollisionModule.VALIDATE_INVALID;
                });
    }

    static boolean navigable(Vector3d position, BoatHull hull, WaterSurface water, BooleanSupplier supportedByLand) {
        double keel = position.y + hull.bottom();
        if (waterAtKeel(water, position.x, keel, position.z)) {
            return true;
        }
        for (int across = -1; across <= 1; across++) {
            for (int along = -1; along <= 1; along++) {
                double x = hull.x() + across * hull.halfWidth() * 0.85;
                double z = hull.z() + along * hull.halfLength() * 0.85;
                if (waterAtKeel(water, hull.worldX(position, x, z), keel, hull.worldZ(position, x, z))) {
                    // Water under the ends is enough unless the centre is supported by land.
                    return !supportedByLand.getAsBoolean();
                }
            }
        }
        return false;
    }

    private static boolean waterAtKeel(WaterSurface water, double x, double keel, double z) {
        double surface = water.height(x, keel, z);
        return Math.abs(surface - keel) <= 2 * DRAFT;
    }

    @FunctionalInterface
    interface WaterSurface {
        double height(double x, double keel, double z);
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

    private record Obstacle(Ref<EntityStore> ref, boolean boat, boolean item, BoatHull hull, BoatHull collisionHull,
                            Vector3d position) {
    }

    public static final class CaptureContactVelocity extends EntityTickingSystem<EntityStore> {
        private final ComponentType<EntityStore, Inertia> inertia;
        private final Query<EntityStore> query;

        public CaptureContactVelocity(ComponentType<EntityStore, Inertia> inertia) {
            this.inertia = inertia;
            query = Query.and(BoatComponent.getComponentType(), TransformComponent.getComponentType(), inertia);
        }

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        @Nonnull
        public Set<Dependency<EntityStore>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.AFTER, MountSystems.HandleMountInput.class),
                    new SystemDependency<>(Order.BEFORE, BoatInertiaSystem.class));
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                         @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            var state = chunk.getComponent(index, inertia);
            var transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (dt <= 0 || state == null || transform == null) {
                return;
            }
            // All contacts see the target's velocity before drag or impacts from this tick.
            state.contactVelocity.set(state.velocity);
            if (state.pilot != null) {
                var travel = transform.getPosition().sub(state.previous, new Vector3d());
                samplePilotVelocity(travel, Math.hypot(travel.x, travel.z), dt, state.contactVelocity);
            }
        }
    }

    public static final class Inertia implements Component<EntityStore> {
        private final Vector3d previous = new Vector3d();
        private final Vector3d velocity = new Vector3d();
        private final Vector3d contactVelocity = new Vector3d();
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
