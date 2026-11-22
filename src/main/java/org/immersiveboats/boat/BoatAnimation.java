package org.immersiveboats.boat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared contract for the rowing controller and the animation build. */
public final class BoatAnimation {
    static final String PADDLE_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch";
    static final String SLOW_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Slow";
    static final String FAST_PITCH_ANIMATION = "ImmersiveBoats_Boat_Paddle_Pitch_Fast";
    static final String TURN_ANIMATION = "ImmersiveBoats_Boat_Turn";
    static final String OARS_ROWING_ANIMATION = "ImmersiveBoats_Boat_Oars_Rowing";
    static final String POSITIVE_YAW_BANK = "_Bank_Positive_Yaw";
    static final String NEGATIVE_YAW_BANK = "_Bank_Negative_Yaw";
    static final String POSITIVE_YAW_TURN = "_Turn_Positive_Yaw";
    static final String NEGATIVE_YAW_TURN = "_Turn_Negative_Yaw";
    static final String REVERSE = "_Reverse";
    static final String STROKE = "_Stroke";
    static final String IDLE_ROLL_ANIMATION = "ImmersiveBoats_Boat_Idle_Roll";
    static final List<String> PITCH_ANIMATIONS =
            List.of(SLOW_PITCH_ANIMATION, PADDLE_PITCH_ANIMATION, FAST_PITCH_ANIMATION);

    public static final String PHASE_SUFFIX = "_Phase_";
    public static final int FRAMES_PER_SECOND = 60;
    public static final int ROWING_CYCLE_FRAMES = 72;
    public static final int ROWING_PHASE_STEP = 6;
    public static final int FORWARD_CATCH_FRAME = 18;
    public static final int REVERSE_CATCH_FRAME = 30;
    public static final int POWER_STROKE_FRAMES = 24;
    public static final Map<String, Clip> CLIPS = clips();

    private BoatAnimation() {
    }

    private static Map<String, Clip> clips() {
        var clips = new LinkedHashMap<String, Clip>();
        var both = List.of("Port_Oar", "Starboard_Oar");
        var port = List.of("Port_Oar");
        var starboard = List.of("Starboard_Oar");
        clips.put(IDLE_ROLL_ANIMATION, new Clip(144, -1, List.of(), false));
        for (String pitch : PITCH_ANIMATIONS) {
            addRowingPair(clips, pitch, both);
            addRowingPair(clips, pitch + POSITIVE_YAW_BANK, starboard);
            addRowingPair(clips, pitch + NEGATIVE_YAW_BANK, port);
        }
        addRowingPair(clips, OARS_ROWING_ANIMATION, both);
        addRowingPair(clips, OARS_ROWING_ANIMATION + POSITIVE_YAW_TURN, starboard);
        addRowingPair(clips, OARS_ROWING_ANIMATION + NEGATIVE_YAW_TURN, port);
        for (String bank : List.of(POSITIVE_YAW_BANK, NEGATIVE_YAW_BANK)) {
            clips.put(TURN_ANIMATION + bank, new Clip(ROWING_CYCLE_FRAMES, -1, List.of(), true));
        }
        clips.put(TURN_ANIMATION + POSITIVE_YAW_BANK + STROKE,
                new Clip(ROWING_CYCLE_FRAMES, FORWARD_CATCH_FRAME, starboard, true));
        clips.put(TURN_ANIMATION + NEGATIVE_YAW_BANK + STROKE,
                new Clip(ROWING_CYCLE_FRAMES, FORWARD_CATCH_FRAME, port, true));
        return Collections.unmodifiableMap(clips);
    }

    private static void addRowingPair(Map<String, Clip> clips, String name, List<String> nodes) {
        clips.put(name, new Clip(ROWING_CYCLE_FRAMES, FORWARD_CATCH_FRAME, nodes, true));
        clips.put(name + REVERSE, new Clip(ROWING_CYCLE_FRAMES, REVERSE_CATCH_FRAME, nodes, true));
    }

    public record Clip(int duration, int catchFrame, List<String> splashNodes, boolean phaseVariants) {
    }
}
