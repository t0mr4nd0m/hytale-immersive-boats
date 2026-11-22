import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class GenerateFoamOpacity {
    private static final String FOAM = "ImmersiveBoats_Boat_Foam";
    private static final String DROPLETS = "ImmersiveBoats_Boat_Paddle_Droplets";
    private static final String SPAWNER_EXTENSION = ".particlespawner";
    private static final String SYSTEM_EXTENSION = ".particlesystem";
    private static final Path PARTICLES = Path.of("Server", "Particles", "ImmersiveBoats");
    private static final Pattern OPACITY = Pattern.compile("(\\\"Opacity\\\"\\s*:\\s*)([0-9]+(?:\\.[0-9]+)?)");

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]).resolve(PARTICLES);
        Path output = Path.of(args[1]).resolve(PARTICLES);
        Path spawners = output.resolve("Spawners");
        Files.createDirectories(spawners);
        for (String name : List.of(FOAM, DROPLETS)) {
            String original = Files.readString(source.resolve("Spawners").resolve(name + SPAWNER_EXTENSION));
            if (!OPACITY.matcher(original).find()) {
                throw new IllegalStateException("Could not find opacity in " + name);
            }
            for (int percent = 1; percent < 100; percent++) {
                BigDecimal ratio = BigDecimal.valueOf(percent, 2);
                String spawner = OPACITY.matcher(original).replaceAll(match -> match.group(1)
                        + new BigDecimal(match.group(2)).multiply(ratio).stripTrailingZeros().toPlainString());
                Files.writeString(spawners.resolve(name + "_" + percent + SPAWNER_EXTENSION), spawner);
            }
        }
        for (String name : List.of("ImmersiveBoats_Boat_Wake", "ImmersiveBoats_Boat_Paddle_Splash")) {
            String system = Files.readString(source.resolve(name + SYSTEM_EXTENSION));
            for (int percent = 1; percent < 100; percent++) {
                String variant = system.replace("\"" + FOAM + "\"", "\"" + FOAM + "_" + percent + "\"")
                        .replace("\"" + DROPLETS + "\"", "\"" + DROPLETS + "_" + percent + "\"");
                Files.writeString(output.resolve(name + "_" + percent + SYSTEM_EXTENSION), variant);
            }
        }
    }
}
