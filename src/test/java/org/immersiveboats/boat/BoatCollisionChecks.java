package org.immersiveboats.boat;

import com.hypixel.hytale.math.shape.Box;
import org.joml.Vector3d;

public final class BoatCollisionChecks {
    static void main() {
        for (double yaw : new double[]{0, Math.PI / 4}) {
            checkImpulse("matched speed", yaw, 4, 0);
            checkImpulse("leader pulling away", yaw, 5, 0);
            checkImpulse("trailing boat catches up", yaw, 2, 1.6);
        }
        System.out.println("Passed 6 boat collision regression cases.");
    }

    private static void checkImpulse(String scenario, double yaw, double leaderSpeed, double expected) {
        var hull = BoatHull.of(new Box(-0.9, 0, -1.08, 0.9, 0.5, 2), yaw);
        var rearPosition = new Vector3d();
        var heading = new Vector3d(0, 0, 1).rotateY(yaw);
        // These hulls already touch, so the closing speed determines whether the contact blocks.
        var leaderPosition = new Vector3d(heading).mul(3);
        var rearVelocity = new Vector3d(heading).mul(4);
        var leaderVelocity = new Vector3d(heading).mul(leaderSpeed);
        var contact = new BoatInertiaSystem.Contact(hull, rearPosition, rearVelocity);
        double impulse = contact.impulse(hull, leaderPosition, leaderVelocity, 0);
        if (!Double.isFinite(impulse) || Math.abs(impulse - expected) > 1e-9) {
            throw new AssertionError(scenario + " at yaw " + yaw + ": expected impulse " + expected
                    + ", got " + impulse);
        }
    }
}
