package org.immersiveboats.boat;

import com.hypixel.hytale.math.shape.Box;
import org.joml.Vector3d;

public final class BoatShorelineChecks {
    // The bounds of the vanilla boat model at scale 2. The bow extends farther from the origin.
    private static final Box BOUNDS = new Box(-0.9, 0, -1.08, 0.9, 0.5, 2);
    private static final double BOW_TOWARD_LAND = 0;
    private static final double BOW_TOWARD_WATER = Math.PI;

    static void main() {
        var waterSide = new Vector3d(0.5, 0, 1.5);
        check("bow over shore", waterSide, BOW_TOWARD_LAND, true, false, false, true, false);
        check("stern short of shore", waterSide, BOW_TOWARD_WATER, true, false, true, true, false);

        var landSide = new Vector3d(0.5, 0, -1.5);
        check("dry hull, bow toward land", landSide, BOW_TOWARD_LAND, true, true, false, false, true);
        check("wet bow, grounded and idle", landSide, BOW_TOWARD_WATER, true, true, false, false, true);
        check("dry hull, not idle", landSide, BOW_TOWARD_LAND, false, true, false, false, true);
        check("wet bow, not idle", landSide, BOW_TOWARD_WATER, false, true, false, false, false);
        check("dry hull, no support", landSide, BOW_TOWARD_LAND, true, false, false, false, true);
        check("wet bow, no support", landSide, BOW_TOWARD_WATER, true, false, false, true, false);
        System.out.println("Passed 8 bow/stern shoreline regression cases.");
    }

    private static void check(String scenario, Vector3d position, double yaw, boolean idle, boolean supported,
                              boolean rocking, boolean navigable, boolean collectible) {
        var hull = BoatHull.forBoat(BOUNDS, yaw);
        expect(scenario, "idle-rock eligibility", rocking,
                BoatWakeSystem.hullOverWater(position, hull, (_, z) -> z >= 0));
        expect(scenario, "navigation", navigable, BoatInertiaSystem.navigable(position, hull,
                (_, _, z) -> z >= 0 ? 0 : Double.NaN, () -> supported));
        expect(scenario, "pickup", collectible, !BoatPickupSystem.isPickupBlocked(position, hull, idle,
                (_, z, minY, maxY, keel) -> z < 0 || minY > -1 || maxY <= -1 || keel > 0,
                () -> supported));
    }

    private static void expect(String scenario, String rule, boolean expected, boolean actual) {
        if (actual != expected) {
            throw new AssertionError(scenario + ", " + rule + ": expected " + expected + ", got " + actual);
        }
    }
}
