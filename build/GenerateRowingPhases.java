import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaterniond;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;

final class GenerateRowingPhases {
    private static final int FRAMES_PER_CYCLE = 72;
    private static final int PHASE_STEP = 6;
    private static final String MODEL_DIRECTORY = "Server/Models/Vehicles";
    private static final String ANIMATION_DIRECTORY = "Items/Vehicles/Animations/RowingPhases";

    public static void main(String[] args) throws Exception {
        var source = Path.of(args[0]);
        var output = Path.of(args[1]);
        var modelFile = source.resolve(MODEL_DIRECTORY).resolve("ImmersiveBoats_Boat_Pitch.json");
        var model = JsonParser.parseString(Files.readString(modelFile)).getAsJsonObject();
        var phases = new JsonObject();
        var gson = new Gson();
        Files.createDirectories(output.resolve("Common").resolve(ANIMATION_DIRECTORY));
        for (var entry : model.getAsJsonObject("AnimationSets").entrySet()) {
            var definition = entry.getValue().getAsJsonObject();
            var animation = definition.getAsJsonArray("Animations").get(0).getAsJsonObject();
            var animationFile = source.resolve("Common").resolve(animation.get("Animation").getAsString());
            var clip = JsonParser.parseString(Files.readString(animationFile)).getAsJsonObject();
            if (entry.getKey().endsWith("_Idle_Roll")) {
                continue;
            }
            if (clip.get("duration").getAsInt() != FRAMES_PER_CYCLE || !animation.get("Looping").getAsBoolean()) {
                throw new IllegalArgumentException("Expected a looping 72-frame rowing clip in " + animationFile);
            }
            for (int offset = PHASE_STEP; offset < FRAMES_PER_CYCLE; offset += PHASE_STEP) {
                String name = entry.getKey() + "_Phase_" + offset;
                String asset = ANIMATION_DIRECTORY + "/" + name + ".blockyanim";
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
                        track.setValue(shift(frames, offset));
                    }
                }
                Files.writeString(output.resolve("Common").resolve(asset), gson.toJson(shifted));
                var shiftedDefinition = definition.deepCopy();
                var shiftedAnimation = shiftedDefinition.getAsJsonArray("Animations").get(0).getAsJsonObject();
                shiftedAnimation.addProperty("Animation", asset);
                shiftedAnimation.addProperty("BlendingDuration", 0.08);
                phases.add(name, shiftedDefinition);
            }
        }
        var phaseModel = new JsonObject();
        phaseModel.addProperty("Parent", "Boat");
        phaseModel.add("AnimationSets", phases);
        Files.createDirectories(output.resolve(MODEL_DIRECTORY));
        Files.writeString(output.resolve(MODEL_DIRECTORY).resolve("ImmersiveBoats_Boat_Rowing_Phases.json"),
                gson.toJson(phaseModel));
    }

    private static JsonArray shift(JsonArray frames, int offset) {
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
            if (time != FRAMES_PER_CYCLE && time != offset) {
                var copy = frame.deepCopy();
                copy.addProperty("time", Math.floorMod(time - offset, FRAMES_PER_CYCLE));
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
        last.addProperty("time", FRAMES_PER_CYCLE);
        result.add(last);
        return result;
    }

    private static Quaterniond rotation(JsonObject frame) {
        var delta = frame.getAsJsonObject("delta");
        return new Quaterniond(delta.get("x").getAsDouble(), delta.get("y").getAsDouble(),
                delta.get("z").getAsDouble(), delta.get("w").getAsDouble()).normalize();
    }
}
