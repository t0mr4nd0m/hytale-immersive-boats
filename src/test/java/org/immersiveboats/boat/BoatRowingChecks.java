package org.immersiveboats.boat;

import java.util.function.Predicate;

public final class BoatRowingChecks {
    private static final Predicate<String> ALL_ANIMATIONS = _ -> true;
    private static final String OARS = "ImmersiveBoats_Boat_Oars_Rowing";

    static void main() {
        changesSideWithoutRestarting();
        splashesOnThePreviousSideIfSwitchAndCatchCoincide();
        waitsForPendingCatchBeforeReversing();
        reversingAfterCatchDoesNotSplashTwice();
        missingPhaseDefersSwitchToCycleBoundary();
        splashesOncePerCycleInBothDirections();
        detectsSustainedDecelerationAndResumesOnAcceleration();
        rejectsDecelerationFromTurnsAndAbruptImpacts();
        suppressesSplashesWhileCoastingOrOarsHidden();
        System.out.println("Passed 9 rowing regression tests.");
    }

    private static void changesSideWithoutRestarting() {
        var rowing = new BoatRowing();
        checkPlayback(step(rowing, false, 1, 0.1f), OARS + "_Turn_Positive_Yaw");
        checkPlayback(step(rowing, false, -1, 0.2f), OARS + "_Turn_Negative_Yaw_Phase_12");
        check(step(rowing, false, -1, 0.11f).splash().usesOar(-1), "Turn must splash on the new side");
    }

    private static void splashesOnThePreviousSideIfSwitchAndCatchCoincide() {
        var rowing = new BoatRowing();
        step(rowing, false, 1, 0.1f);
        step(rowing, false, 1, 0.2f);
        var change = step(rowing, false, -1, 0.11f);
        checkPlayback(change, OARS + "_Turn_Negative_Yaw_Phase_18");
        check(change.splash().usesOar(1) && !change.splash().usesOar(-1),
                "The blade that reached the water must own the splash");
        check(step(rowing, false, -1, 0.10f).splash() == null, "Switch must not repeat the splash");
    }

    private static void waitsForPendingCatchBeforeReversing() {
        var rowing = new BoatRowing();
        checkPlayback(step(rowing, true, 0, 0.1f), OARS + "_Reverse");
        step(rowing, true, 0, 0.2f);
        var pending = step(rowing, false, 0, 0.15f);
        check(pending.playback() == null && pending.splash() == null, "Do not skip the pending reverse catch");
        var caught = step(rowing, false, 0, 0.16f);
        checkPlayback(caught, OARS + "_Phase_30");
        check(caught.splash() != null && caught.splash().reverse(), "Finish the previous catch before switching");
    }

    private static void reversingAfterCatchDoesNotSplashTwice() {
        var rowing = new BoatRowing();
        step(rowing, false, 0, 0.1f);
        check(step(rowing, false, 0, 0.32f).splash() != null, "Expected forward catch");
        checkPlayback(step(rowing, true, 0, 0.08f), OARS + "_Reverse_Phase_24");
        check(step(rowing, true, 0, 0.16f).splash() == null, "Reverse catch must not duplicate the forward splash");
    }

    private static void missingPhaseDefersSwitchToCycleBoundary() {
        var rowing = new BoatRowing();
        Predicate<String> baseAnimations = name -> !name.contains(BoatAnimation.PHASE_SUFFIX);
        step(rowing, false, 0, 0.1f);
        var pending = rowing.advance(true, false, false, false, true, 1, 0.5, 0.2f, baseAnimations);
        check(pending.playback() == null, "Missing phase clip must not restart the stroke");
        step(rowing, false, 0, 0.35f);
        var boundary = rowing.advance(true, false, false, false, true, 1, 0.5, 0.70f, baseAnimations);
        checkPlayback(boundary, OARS + "_Turn_Positive_Yaw");
    }

    private static void splashesOncePerCycleInBothDirections() {
        for (boolean reverse : new boolean[]{false, true}) {
            for (float dt : new float[]{0.05f, 0.1f, 0.116f, 0.166f}) {
                var rowing = new BoatRowing();
                step(rowing, reverse, 0, dt);
                int count = 0;
                for (int tick = 1; tick * dt < 3.55f; tick++) {
                    var splash = step(rowing, reverse, 0, dt).splash();
                    if (splash == null) {
                        continue;
                    }
                    float catchTime = count * 1.2f + (reverse ? 0.5f : 0.3f);
                    float elapsed = tick * dt;
                    check(elapsed >= catchTime - 0.0001f && elapsed <= catchTime + dt + 0.0001f,
                            "Splash must land on the catch frame within one update");
                    check(splash.reverse() == reverse && splash.usesOar(-1) && splash.usesOar(1),
                            "Straight rowing makes both oars splash in the selected direction");
                    count++;
                }
                check(count == 3, "Expected one splash in each of three cycles");
            }
        }
    }

    private static void detectsSustainedDecelerationAndResumesOnAcceleration() {
        var rowing = new BoatRowing();
        check(!sample(rowing, 5, 0) && !sample(rowing, 5, 0), "Steady speed must not count as coasting");
        check(!sample(rowing, 4.5, 0), "One speed drop is insufficient");
        check(sample(rowing, 4, 0), "Two moderate speed drops trigger coasting");
        check(!sample(rowing, 4.5, 0), "Acceleration ends coasting");
        sample(rowing, 4, 0);
        check(sample(rowing, 3.5, 0), "Expected coasting before reset");
        rowing.resetMotion();
        check(!sample(rowing, 3, 0), "Reset must discard coasting history");
    }

    private static void rejectsDecelerationFromTurnsAndAbruptImpacts() {
        var rowing = new BoatRowing();
        for (double speed : new double[]{5, 4.5, 4, 3.5}) {
            check(!sample(rowing, speed, Math.toRadians(5)), "Turning must not count as coasting");
        }
        rowing.resetMotion();
        for (double speed : new double[]{5, 5, 2, 2, 3, 5}) {
            check(!sample(rowing, speed, 0), "A sudden impact must not count as sustained deceleration");
        }
    }

    private static void suppressesSplashesWhileCoastingOrOarsHidden() {
        var rowing = new BoatRowing();
        step(rowing, false, 0, 0.1f);
        step(rowing, false, 0, 0.2f);
        var coasting = rowing.advance(true, false, true, false, true, 0, 0.5, 0.15f, ALL_ANIMATIONS);
        check(coasting.stopped() && coasting.splash() == null, "Coasting should stop the stroke and suppress its splash");
        var hidden = rowing.advance(true, false, false, true, false, 0, 0.5, 0.1f, ALL_ANIMATIONS);
        checkPlayback(hidden, "ImmersiveBoats_Boat_Paddle_Pitch");
        var pitch = rowing.advance(true, false, false, true, false, 0, 0.5, 0.35f, ALL_ANIMATIONS);
        check(rowing.active() && pitch.splash() == null, "Hidden oars should keep hull pitch without splashing");
    }

    private static BoatRowing.Update step(BoatRowing rowing, boolean reverse, double turn, float dt) {
        return rowing.advance(true, reverse, false, false, true, turn, 0.5, dt, ALL_ANIMATIONS);
    }

    private static boolean sample(BoatRowing rowing, double speed, double yaw) {
        return rowing.sampleMotion(speed * 0.2, yaw, 0.2);
    }

    private static void checkPlayback(BoatRowing.Update update, String expected) {
        check(expected.equals(update.playback()), "Expected " + expected + ", got " + update.playback());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
