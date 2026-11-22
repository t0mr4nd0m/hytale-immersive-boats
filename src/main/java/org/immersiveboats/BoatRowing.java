package org.immersiveboats;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class BoatRowing {
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
    static final String IDLE_ROLL_ANIMATION = "ImmersiveBoats_Boat_Idle_Roll";
    private static final List<String> PITCH_ANIMATIONS =
            List.of(SLOW_PITCH_ANIMATION, PADDLE_PITCH_ANIMATION, FAST_PITCH_ANIMATION);
    static final List<String> ANIMATIONS = boatAnimations();
    static final String PHASE_SUFFIX = "_Phase_";
    private static final float ANIMATION_FRAMES_PER_SECOND = 60;
    private static final int ROWING_CYCLE_FRAMES = 72;
    private static final int ROWING_PHASE_STEP = 6;
    private static final float ROWING_CYCLE_SECONDS = ROWING_CYCLE_FRAMES / ANIMATION_FRAMES_PER_SECOND;
    private static final float FORWARD_CATCH_SECONDS = 18 / ANIMATION_FRAMES_PER_SECOND;
    private static final float REVERSE_CATCH_SECONDS = 30 / ANIMATION_FRAMES_PER_SECOND;
    private static final float POWER_STROKE_SECONDS = 24 / ANIMATION_FRAMES_PER_SECOND;
    private static final float ROWING_STOP_GRACE_SECONDS = 0.30f;
    private static final float TURN_STOP_GRACE_SECONDS = 0.20f;
    private static final double FORWARD_CATCH_YAW = Math.toRadians(-17);
    private static final double REVERSE_CATCH_YAW = Math.toRadians(37);
    private static final double TURN_BANK_ANGLE = Math.toRadians(3);

    private String rowingAnimation;
    private float rowingCycleRemaining;
    private float rowingGrace;
    private boolean strokeSplashed;
    private final RowingMotion rowingMotion = new RowingMotion();
    private RowingMode rowingMode;
    private int turnDirection;
    private float turnGrace;
    private boolean reversing;

    record Update(String playback, boolean stopped, Splash splash) {
        private static final Update NONE = new Update(null, false, null);
    }

    record Splash(boolean reverse, int turnDirection, double bladeYaw, double hullPitch, double hullBank) {
        boolean usesOar(int side) {
            return turnDirection == 0 || turnDirection == side;
        }
    }

    boolean active() {
        return rowingAnimation != null;
    }

    boolean sampleMotion(double travel, double turn, double dt) {
        return rowingMotion.sample(travel, turn, dt);
    }

    void resetMotion() {
        rowingMotion.reset();
    }

    Update advance(boolean advancing, boolean reversing, boolean coasting, boolean rockingWhileRowing,
                   boolean showOars, double turn, double speedFraction, float dt, Predicate<String> available) {
        boolean wasActive = active();
        String previousAnimation = rowingAnimation;
        float previousRemaining = rowingCycleRemaining;
        var previousMode = rowingMode;
        if (previousRemaining <= dt) {
            strokeSplashed = false;
        }
        double progress = previousMode == null || !previousMode.stroke || coasting || strokeSplashed
                ? -1 : oarCatchProgress(previousRemaining, dt, previousMode.reverse);
        if (progress >= 0) {
            strokeSplashed = true;
        }
        String playback = updateAnimation(advancing, reversing, !coasting, rockingWhileRowing, showOars,
                turn, speedFraction, dt, available);
        Splash splash = null;
        if (previousMode != null && rowingMode != null && rowingMode.stroke && progress >= 0) {
            double startYaw = previousMode.reverse ? REVERSE_CATCH_YAW : FORWARD_CATCH_YAW;
            double endYaw = previousMode.reverse ? FORWARD_CATCH_YAW : REVERSE_CATCH_YAW;
            double bladeYaw = Math.fma(smoothStep(progress), endYaw - startYaw, startYaw);
            double pitch = oarCatchHullPitch(previousAnimation, previousMode, progress);
            double bank = previousMode.bank ? -previousMode.turnDirection * TURN_BANK_ANGLE : 0;
            splash = new Splash(previousMode.reverse, previousMode.turnDirection, bladeYaw, pitch, bank);
        }
        boolean stopped = wasActive && !active();
        return playback == null && !stopped && splash == null ? Update.NONE : new Update(playback, stopped, splash);
    }

    BoatRowing copy() {
        var copy = new BoatRowing();
        copy.rowingAnimation = rowingAnimation;
        copy.rowingCycleRemaining = rowingCycleRemaining;
        copy.rowingGrace = rowingGrace;
        copy.strokeSplashed = strokeSplashed;
        copy.rowingMode = rowingMode;
        copy.turnDirection = turnDirection;
        copy.turnGrace = turnGrace;
        copy.reversing = reversing;
        return copy;
    }

    private String updateAnimation(boolean advancing, boolean reversing, boolean paddling, boolean rockingWhileRowing,
                                   boolean showOars, double turn, double speedFraction, float dt,
                                   Predicate<String> available) {
        boolean wrapped = false;
        if (rowingAnimation != null) {
            rowingCycleRemaining -= dt;
            if (rowingCycleRemaining <= 0) {
                rowingCycleRemaining = ROWING_CYCLE_SECONDS + rowingCycleRemaining % ROWING_CYCLE_SECONDS;
                wrapped = true;
            }
        }
        if (!paddling) {
            rowingGrace = 0;
        } else if (advancing) {
            rowingGrace = ROWING_STOP_GRACE_SECONDS;
            this.reversing = reversing;
        } else {
            rowingGrace = Math.max(0, rowingGrace - dt);
        }
        if (turn != 0) {
            turnDirection = turn > 0 ? 1 : -1;
            turnGrace = TURN_STOP_GRACE_SECONDS;
        } else {
            turnGrace = Math.max(0, turnGrace - dt);
            if (turnGrace == 0) {
                turnDirection = 0;
            }
        }

        boolean rowing = rowingGrace > 0;
        boolean pitch = rowing && rockingWhileRowing;
        boolean turning = turnDirection != 0;
        boolean stroke = showOars && (rowing || (paddling && turning));
        boolean bank = rockingWhileRowing && turning;
        if (!pitch && !stroke && !bank) {
            stop();
            return null;
        }
        var mode = new RowingMode(pitch, stroke, bank, stroke && rowing && this.reversing, turnDirection);
        if (mode.equals(rowingMode) && !wrapped) {
            return null;
        }
        String animation = mode.animation(speedFraction);
        if (animation.equals(rowingAnimation)) {
            rowingMode = mode;
            return null;
        }

        boolean continueStroke = rowingMode != null && rowingMode.stroke && mode.stroke;
        String playback = animation;
        if (continueStroke) {
            float elapsed = ROWING_CYCLE_SECONDS - rowingCycleRemaining;
            // When reversing would jump past an unplayed catch, let that catch finish first.
            if (!strokeSplashed && mode.reverse != rowingMode.reverse
                    && elapsed >= catchSeconds(mode.reverse) && elapsed < catchSeconds(rowingMode.reverse)) {
                return null;
            }
            int phase = Math.round(elapsed * ANIMATION_FRAMES_PER_SECOND / ROWING_PHASE_STEP)
                    * ROWING_PHASE_STEP % ROWING_CYCLE_FRAMES;
            if (phase != 0) {
                String shifted = animation + PHASE_SUFFIX + phase;
                if (available.test(shifted)) {
                    playback = shifted;
                } else if (!wrapped) {
                    return null;
                }
            }
        }
        if (!available.test(playback)) {
            return null;
        }
        rowingAnimation = animation;
        rowingMode = mode;
        if (!continueStroke) {
            rowingCycleRemaining = ROWING_CYCLE_SECONDS;
            strokeSplashed = false;
        }
        return playback;
    }

    boolean stop() {
        boolean wasActive = active();
        rowingAnimation = null;
        rowingMode = null;
        turnDirection = 0;
        turnGrace = 0;
        reversing = false;
        rowingCycleRemaining = 0;
        rowingGrace = 0;
        strokeSplashed = false;
        return wasActive;
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

    private static double oarCatchProgress(float remaining, float dt, boolean reverse) {
        if (remaining <= 0) {
            return -1;
        }
        float catchRemaining = ROWING_CYCLE_SECONDS - catchSeconds(reverse);
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

    private static float catchSeconds(boolean reverse) {
        return reverse ? REVERSE_CATCH_SECONDS : FORWARD_CATCH_SECONDS;
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
}
