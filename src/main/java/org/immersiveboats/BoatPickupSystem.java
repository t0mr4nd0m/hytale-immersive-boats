package org.immersiveboats;

import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.builtin.mounts.vehicle.BoatComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.collision.CollisionConfig;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;

final class BoatPickupSystem extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {
    // Match the draft used by Hytale's SpawnBoatInteraction. Water touching the keel still counts.
    private static final double BOAT_DRAFT = 0.09;

    private final BoatPlayerSettings playerSettings;

    BoatPickupSystem(BoatPlayerSettings playerSettings) {
        super(UseEntityEvent.Pre.class);
        this.playerSettings = playerSettings;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commands, @Nonnull UseEntityEvent.Pre event) {
        if (event.getInteractionType() != InteractionType.Use) {
            return;
        }
        var player = chunk.getReferenceTo(index);
        var playerRef = commands.getComponent(player, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        var interaction = playerSettings.get(playerRef.getUuid()).interaction();
        if (interaction == LandInteraction.BOARD) {
            return;
        }
        var target = event.getTargetEntity();
        if (!target.isValid()
                || commands.getComponent(player, MountedComponent.getComponentType()) != null
                || commands.getComponent(target, BoatComponent.getComponentType()) == null) {
            return;
        }
        if (isPickupBlocked(commands, target, store.getExternalData().getWorld())) {
            return;
        }

        event.setCancelled(true);
        // Resolve removal at command-buffer drain so two players cannot collect the same entity.
        commands.run(currentStore -> collectBoat(currentStore, player, target, interaction));
    }

    private static void collectBoat(Store<EntityStore> store, Ref<EntityStore> player, Ref<EntityStore> target,
                                    LandInteraction interaction) {
        if (!player.isValid() || !target.isValid()) {
            return;
        }
        var riders = store.getComponent(target, MountedByComponent.getComponentType());
        if (riders != null && riders.getPassengers().stream().anyMatch(Ref::isValid)) {
            return;
        }
        var boat = store.getComponent(target, BoatComponent.getComponentType());
        var transform = store.getComponent(target, TransformComponent.getComponentType());
        if (boat == null || boat.getSourceItem() == null || transform == null
                || isPickupBlocked(store, target, store.getExternalData().getWorld())) {
            return;
        }
        var item = new ItemStack(boat.getSourceItem());
        if (!item.isValid() || item.isEmpty()) {
            return;
        }
        var remainder = item;
        if (interaction == LandInteraction.PICK_UP) {
            var inventory = InventoryComponent.getCombined(store, player, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
            remainder = inventory.addItemStack(item).getRemainder();
        }
        if (!ItemStack.isEmpty(remainder)) {
            var position = new Vector3d(transform.getPosition()).add(0, 0.5, 0);
            var drop = ItemComponent.generateItemDrop(store, remainder, position, transform.getRotation(), 0, 1, 0);
            if (drop == null) {
                return;
            }
            store.addEntity(drop, AddReason.SPAWN);
        }
        store.removeEntity(target, RemoveReason.REMOVE);
    }

    private static boolean isPickupBlocked(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> target,
                                           World world) {
        var movement = accessor.getComponent(target, MovementStatesComponent.getComponentType());
        var states = movement == null ? null : movement.getMovementStates();
        var transform = accessor.getComponent(target, TransformComponent.getComponentType());
        var model = accessor.getComponent(target, ModelComponent.getComponentType());
        var bounds = BoatHull.bounds(model, accessor.getComponent(target, BoundingBox.getComponentType()));
        if (transform == null || bounds == null) {
            return true;
        }
        var chunks = world.getChunkStore();
        var position = transform.getPosition();
        double yaw = transform.getRotation().yaw();
        var hull = BoatHull.forBoat(bounds, yaw);
        // Hytale marks the mount idle when its driver dismounts, even if a tip still touches water.
        boolean idle = states != null && states.idle && states.horizontalIdle;
        return isPickupBlocked(position, hull, idle, (x, z, minY, maxY, keel) -> isDry(chunks, x, z, minY, maxY, keel),
                () -> {
                    var support = new Box(-0.05, hull.bottom() - BOAT_DRAFT, -0.05, 0.05, hull.bottom(), 0.05);
                    return CollisionModule.get().validatePosition(world, support, position,
                            CollisionConfig.MATERIAL_SOLID, null, (_, _, _, config) -> {
                                var block = config.blockType;
                                return block != null && (block.getGathering() == null || !block.getGathering().isSoft());
                            }, new CollisionResult()) == CollisionModule.VALIDATE_INVALID;
                });
    }

    static boolean isPickupBlocked(Vector3d position, BoatHull hull, boolean idle, DryColumn dry,
                                   BooleanSupplier supportedByLand) {
        double keel = position.y + hull.bottom() - BOAT_DRAFT;
        // A full water block immediately below the keel can touch it at an integer height.
        int minY = (int) Math.ceil(keel) - 1;
        int maxY = (int) Math.ceil(position.y + hull.top());
        BoatHull.ColumnCheck isDry = (x, z) -> dry.test(x, z, minY, maxY, keel);
        return !hull.allColumns(position, isDry)
                && (!idle || !hull.centerSection().allColumns(position, isDry) || !supportedByLand.getAsBoolean());
    }

    private static boolean isDry(ChunkStore chunks, int x, int z, int minY, int maxY, double keel) {
        for (int y = minY; y < maxY; y++) {
            var sectionRef = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return false;
            }
            var section = chunks.getStore().getComponent(sectionRef, FluidSection.getComponentType());
            var fluid = section == null ? null : section.getFluid(x, y, z);
            if (fluid != null && "Water".equals(fluid.getFluidFXId())) {
                int level = section.getFluidLevel(x, y, z);
                if (level > 0 && y + Math.min(1.0, (double) level / fluid.getMaxFluidLevel()) >= keel) {
                    return false;
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    interface DryColumn {
        boolean test(int x, int z, int minY, int maxY, double keel);
    }
}
