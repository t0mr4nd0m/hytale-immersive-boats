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
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

final class BoatPickupSystem extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {
    // Match the draft used by Hytale's SpawnBoatInteraction. Water touching the keel still counts.
    private static final double BOAT_DRAFT = 0.09;

    private final PlayerPreferences<LandInteraction> interactions;

    BoatPickupSystem(PlayerPreferences<LandInteraction> interactions) {
        super(UseEntityEvent.Pre.class);
        this.interactions = interactions;
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
        var interaction = interactions.get(playerRef.getUuid());
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
        var hull = model == null ? null : model.getModel().getBoundingBox();
        if (transform == null || hull == null) {
            return true;
        }
        var chunks = world.getChunkStore();
        if (isDry(chunks, transform, hull)) {
            return false;
        }
        // Hytale marks the mount idle when its driver dismounts, even if a tip still touches water.
        if (states == null || !states.idle || !states.horizontalIdle) {
            return true;
        }
        var center = new Box(-0.05, hull.min.y, -0.05, 0.05, hull.max.y, 0.05);
        if (!isDry(chunks, transform, center)) {
            return true;
        }
        var support = new Box(-0.05, hull.min.y - BOAT_DRAFT, -0.05, 0.05, hull.min.y, 0.05);
        return CollisionModule.get().validatePosition(world, support, transform.getPosition(),
                CollisionConfig.MATERIAL_SOLID, null, (_, _, _, config) -> {
                    var block = config.blockType;
                    return block != null && (block.getGathering() == null || !block.getGathering().isSoft());
                }, new CollisionResult()) != CollisionModule.VALIDATE_INVALID;
    }

    private static boolean isDry(ChunkStore chunks, TransformComponent transform, Box hull) {
        var position = transform.getPosition();
        // The model and its hull face half a turn away from the body's yaw.
        double yaw = transform.getRotation().yaw() - Math.PI;
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        var center = new Vector3d((hull.min.x + hull.max.x) * 0.5, 0, (hull.min.z + hull.max.z) * 0.5)
                .rotateY(yaw).add(position);
        double halfWidth = (hull.max.x - hull.min.x) * 0.5;
        double halfLength = (hull.max.z - hull.min.z) * 0.5;
        double extentX = halfWidth * Math.abs(cos) + halfLength * Math.abs(sin);
        double extentZ = halfWidth * Math.abs(sin) + halfLength * Math.abs(cos);
        double blockRadius = (Math.abs(sin) + Math.abs(cos)) * 0.5;
        double keel = position.y + hull.min.y - BOAT_DRAFT;
        double top = position.y + hull.max.y;
        for (int x = (int) Math.floor(center.x - extentX); x < Math.ceil(center.x + extentX); x++) {
            for (int z = (int) Math.floor(center.z - extentZ); z < Math.ceil(center.z + extentZ); z++) {
                double dx = x + 0.5 - center.x;
                double dz = z + 0.5 - center.z;
                if (Math.abs(dx * cos - dz * sin) >= halfWidth + blockRadius
                        || Math.abs(dx * sin + dz * cos) >= halfLength + blockRadius) {
                    continue;
                }
                // A full water block immediately below the keel can touch it at an integer height.
                for (int y = (int) Math.ceil(keel) - 1; y < Math.ceil(top); y++) {
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
            }
        }
        return true;
    }
}
