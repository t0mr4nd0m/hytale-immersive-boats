import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class GenerateFoamOpacity {
    private static final String FOAM = "ImmersiveBoats_Boat_Foam";
    private static final String SPAWNER_EXTENSION = ".particlespawner";
    private static final String SYSTEM_EXTENSION = ".particlesystem";
    private static final Path PARTICLES = Path.of("Server", "Particles", "ImmersiveBoats");
    private static final Pattern OPACITY = Pattern.compile("(\\\"Opacity\\\"\\s*:\\s*)([0-9]+(?:\\.[0-9]+)?)");

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]).resolve(PARTICLES);
        Path output = Path.of(args[1]).resolve(PARTICLES);
        Path spawners = output.resolve("Spawners");
        Files.createDirectories(spawners);
        String foam = Files.readString(source.resolve("Spawners").resolve(FOAM + SPAWNER_EXTENSION));
        if (!OPACITY.matcher(foam).find()) {
            throw new IllegalStateException("Could not find opacity in the base boat foam spawner");
        }

        for (int percent = 1; percent < 100; percent++) {
            BigDecimal ratio = BigDecimal.valueOf(percent, 2);
            String variant = FOAM + "_" + percent;
            String spawner = OPACITY.matcher(foam).replaceAll(match -> match.group(1)
                    + new BigDecimal(match.group(2)).multiply(ratio).stripTrailingZeros().toPlainString());
            Files.writeString(spawners.resolve(variant + SPAWNER_EXTENSION), spawner);
        }
        for (String name : List.of("ImmersiveBoats_Boat_Wake", "ImmersiveBoats_Boat_Paddle_Splash")) {
            String system = Files.readString(source.resolve(name + SYSTEM_EXTENSION));
            for (int percent = 1; percent < 100; percent++) {
                String variant = system.replace("\"" + FOAM + "\"", "\"" + FOAM + "_" + percent + "\"");
                Files.writeString(output.resolve(name + "_" + percent + SYSTEM_EXTENSION), variant);
            }
        }
    }
}
