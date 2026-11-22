package org.immersiveboats;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import org.joml.Vector3d;

import javax.annotation.Nullable;

record BoatHull(double x, double z, double halfWidth, double halfLength,
                    double bottom, double top, double cos, double sin) {
    @Nullable
    static Box bounds(@Nullable ModelComponent model, @Nullable BoundingBox collisionBounds) {
        // The model bounds already include the model scale.
        var modelBounds = model == null ? null : model.getModel().getBoundingBox();
        return modelBounds != null ? modelBounds : collisionBounds == null ? null : collisionBounds.getBoundingBox();
    }

    static BoatHull forBoat(Box box, double bodyYaw) {
        // Hytale draws the hull half a turn away from the entity's body yaw.
        return of(box, bodyYaw - Math.PI);
    }

    static BoatHull of(Box box, double yaw) {
        return new BoatHull((box.min.x + box.max.x) / 2, (box.min.z + box.max.z) / 2,
                (box.max.x - box.min.x) / 2, (box.max.z - box.min.z) / 2,
                box.min.y, box.max.y, Math.cos(yaw), Math.sin(yaw));
    }

    Vector3d center(Vector3d position) {
        return new Vector3d(worldX(position, x, z), position.y, worldZ(position, x, z));
    }

    BoatHull centerSection() {
        return new BoatHull(0, 0, 0.05, 0.05, bottom, top, cos, sin);
    }

    double worldX(Vector3d position, double localX, double localZ) {
        return position.x + (localX * cos + localZ * sin);
    }

    double worldZ(Vector3d position, double localX, double localZ) {
        return position.z + (localZ * cos - localX * sin);
    }

    boolean allColumns(Vector3d position, ColumnCheck check) {
        double centerX = worldX(position, x, z);
        double centerZ = worldZ(position, x, z);
        double absCos = Math.abs(cos);
        double absSin = Math.abs(sin);
        double extentX = halfWidth * absCos + halfLength * absSin;
        double extentZ = halfWidth * absSin + halfLength * absCos;
        double blockRadius = 0.5 * (absCos + absSin);
        int maxX = (int) Math.ceil(centerX + extentX);
        int maxZ = (int) Math.ceil(centerZ + extentZ);
        for (int blockX = (int) Math.floor(centerX - extentX); blockX < maxX; blockX++) {
            for (int blockZ = (int) Math.floor(centerZ - extentZ); blockZ < maxZ; blockZ++) {
                double dx = blockX + 0.5 - centerX;
                double dz = blockZ + 0.5 - centerZ;
                if (Math.abs(dx * cos - dz * sin) < halfWidth + blockRadius
                        && Math.abs(dx * sin + dz * cos) < halfLength + blockRadius
                        && !check.test(blockX, blockZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    @FunctionalInterface
    interface ColumnCheck {
        boolean test(int x, int z);
    }

    boolean isOnBoard(Vector3d position, Vector3d entityPosition, double entityBottom) {
        double dx = entityPosition.x - position.x;
        double dz = entityPosition.z - position.z;
        return entityPosition.y + entityBottom >= position.y + bottom - 0.01
                && Math.abs(dx * cos - dz * sin - x) <= halfWidth
                && Math.abs(dx * sin + dz * cos - z) <= halfLength;
    }

    double contactDepth(Vector3d position, BoatHull other, Vector3d otherPosition, Vector3d delta,
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

    double impactLever(BoatHull target, Vector3d delta, Vector3d normal) {
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
