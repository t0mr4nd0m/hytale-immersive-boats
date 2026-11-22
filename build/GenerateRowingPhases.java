import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.immersiveboats.BoatAnimation;
import org.joml.Quaterniond;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GenerateRowingPhases {
    private static final String MODEL_DIRECTORY = "Server/Models/Vehicles";
    private static final String ANIMATION_DIRECTORY = "Items/Vehicles/Animations/RowingPhases";
    private static final List<String> OAR_NODES = List.of("Port_Oar", "Starboard_Oar");

    public static void main(String[] args) throws Exception {
        var source = Path.of(args[0]);
        var output = Path.of(args[1]);
        var modelFile = source.resolve(MODEL_DIRECTORY).resolve("ImmersiveBoats_Boat_Pitch.json");
        var model = JsonParser.parseString(Files.readString(modelFile)).getAsJsonObject();
        var configured = model.getAsJsonObject("AnimationSets");
        var phases = new JsonObject();
        var gson = new Gson();
        Files.createDirectories(output.resolve("Common").resolve(ANIMATION_DIRECTORY));
        for (var entry : BoatAnimation.CLIPS.entrySet()) {
            String name = entry.getKey();
            var expected = entry.getValue();
            var definition = configured.getAsJsonObject(name);
            require(definition != null, name, "missing animation set in " + modelFile);
            var animations = definition.getAsJsonArray("Animations");
            require(animations != null && animations.size() == 1, name, "expected a single animation");
            var animation = animations.get(0).getAsJsonObject();
            require(animation.has("Animation"), name, "missing Animation asset path");
            require(animation.has("Looping") && animation.get("Looping").getAsBoolean(), name, "must loop");
            String asset = animation.get("Animation").getAsString();
            var animationFile = source.resolve("Common").resolve(asset);
            require(Files.isRegularFile(animationFile), name, "missing animation file " + animationFile);
            require(Files.isRegularFile(output.resolve("Common").resolve(asset)), name,
                    "animation file was not copied to the build output");
            var clip = JsonParser.parseString(Files.readString(animationFile)).getAsJsonObject();
            validateClip(name, clip, expected, 0);
            if (!expected.phaseVariants()) {
                continue;
            }
            for (int offset = BoatAnimation.ROWING_PHASE_STEP; offset < expected.duration();
                 offset += BoatAnimation.ROWING_PHASE_STEP) {
                String shiftedName = name + BoatAnimation.PHASE_SUFFIX + offset;
                String shiftedAsset = ANIMATION_DIRECTORY + "/" + shiftedName + ".blockyanim";
                var shifted = clip.deepCopy();
                for (var node : shifted.getAsJsonObject("nodeAnimations").entrySet()) {
                    var channels = node.getValue().getAsJsonObject();
                    for (var track : channels.entrySet()) {
                        var frames = track.getValue().getAsJsonArray();
                        if (frames.isEmpty()) {
                            continue;
                        }
                        if (!track.getKey().equals("orientation")) {
                            throw new IllegalArgumentException("Unsupported rowing track in " + animationFile);
                        }
                        track.setValue(shift(frames, offset, expected.duration()));
                    }
                }
                validateClip(shiftedName, shifted, expected, offset);
                Files.writeString(output.resolve("Common").resolve(shiftedAsset), gson.toJson(shifted));
                var shiftedDefinition = definition.deepCopy();
                var shiftedAnimation = shiftedDefinition.getAsJsonArray("Animations").get(0).getAsJsonObject();
                shiftedAnimation.addProperty("Animation", shiftedAsset);
                shiftedAnimation.addProperty("BlendingDuration", 0.08);
                phases.add(shiftedName, shiftedDefinition);
            }
        }
        var phaseModel = new JsonObject();
        phaseModel.addProperty("Parent", "Boat");
        phaseModel.add("AnimationSets", phases);
        Files.createDirectories(output.resolve(MODEL_DIRECTORY));
        Files.writeString(output.resolve(MODEL_DIRECTORY).resolve("ImmersiveBoats_Boat_Rowing_Phases.json"),
                gson.toJson(phaseModel));
        System.out.printf("Validated %d boat animations and %d phase variants.%n", BoatAnimation.CLIPS.size(),
                phases.size());
    }

    private static void validateClip(String name, JsonObject clip, BoatAnimation.Clip expected, int offset) {
        require(clip.has("duration") && clip.get("duration").getAsDouble() == expected.duration(), name,
                "expected duration " + expected.duration() + " frames");
        var nodes = clip.getAsJsonObject("nodeAnimations");
        require(nodes != null, name, "missing node animations");
        for (var entry : nodes.entrySet()) {
            var orientation = entry.getValue().getAsJsonObject().getAsJsonArray("orientation");
            if (orientation != null && !orientation.isEmpty()) {
                validateLoop(name, entry.getKey(), orientation, expected.duration());
            }
        }
        for (String oar : OAR_NODES) {
            var node = nodes.getAsJsonObject(oar);
            var track = node == null ? null : node.getAsJsonArray("orientation");
            boolean animated = track != null && !track.isEmpty();
            require(animated == expected.splashNodes().contains(oar), name,
                    oar + (animated ? " should be stationary" : " is missing its stroke"));
            if (animated) {
                int catchFrame = Math.floorMod(expected.catchFrame() - offset, expected.duration());
                int endFrame = Math.floorMod(expected.catchFrame() + BoatAnimation.POWER_STROKE_FRAMES - offset,
                        expected.duration());
                require(containsFrame(track, catchFrame), name, oar + " is missing splash frame " + catchFrame);
                require(containsFrame(track, endFrame), name, oar + " is missing power stroke end frame " + endFrame);
            }
        }
    }

    private static void validateLoop(String name, String node, JsonArray track, int duration) {
        int previous = -1;
        for (var value : track) {
            var keyframe = value.getAsJsonObject();
            int frame = keyframe.get("time").getAsInt();
            require(frame > previous && frame <= duration && keyframe.get("time").getAsDouble() == frame, name,
                    node + " has an invalid or out-of-order keyframe at " + keyframe.get("time"));
            previous = frame;
        }
        var first = track.get(0).getAsJsonObject();
        var last = track.get(track.size() - 1).getAsJsonObject();
        require(first.get("time").getAsInt() == 0 && previous == duration, name,
                node + " must cover frames 0 through " + duration);
        require(first.get("delta").equals(last.get("delta")), name, node + " must end in its starting pose");
    }

    private static boolean containsFrame(JsonArray track, int frame) {
        for (var keyframe : track) {
            if (keyframe.getAsJsonObject().get("time").getAsDouble() == frame) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String name, String message) {
        if (!condition) {
            throw new IllegalArgumentException(name + ": " + message);
        }
    }

    private static JsonArray shift(JsonArray frames, int offset, int duration) {
        var shifted = new ArrayList<JsonObject>();
        var before = frames.get(0).getAsJsonObject();
        var after = frames.get(frames.size() - 1).getAsJsonObject();
        for (var value : frames) {
            var frame = value.getAsJsonObject();
            int time = frame.get("time").getAsInt();
            if (time <= offset) {
                before = frame;
            } else if (time < after.get("time").getAsInt()) {
                after = frame;
            }
            if (time != duration && time != offset) {
                var copy = frame.deepCopy();
                copy.addProperty("time", Math.floorMod(time - offset, duration));
                shifted.add(copy);
            }
        }
        int begin = before.get("time").getAsInt();
        int end = after.get("time").getAsInt();
        double t = (double) (offset - begin) / (end - begin);
        if ("smooth".equals(before.get("interpolationType").getAsString())) {
            t = t * t * (3 - 2 * t);
        }
        var orientation = rotation(before).slerp(rotation(after), t);
        var seam = before.deepCopy();
        var delta = seam.getAsJsonObject("delta");
        delta.addProperty("x", orientation.x);
        delta.addProperty("y", orientation.y);
        delta.addProperty("z", orientation.z);
        delta.addProperty("w", orientation.w);
        seam.addProperty("time", 0);
        shifted.sort(Comparator.comparingInt(frame -> frame.get("time").getAsInt()));
        var result = new JsonArray();
        result.add(seam);
        shifted.forEach(result::add);
        var last = seam.deepCopy();
        last.addProperty("time", duration);
        result.add(last);
        return result;
    }

    private static Quaterniond rotation(JsonObject frame) {
        var delta = frame.getAsJsonObject("delta");
        return new Quaterniond(delta.get("x").getAsDouble(), delta.get("y").getAsDouble(),
                delta.get("z").getAsDouble(), delta.get("w").getAsDouble()).normalize();
    }
}
