# Immersive Boats

Immersive Boats adds foam wakes, rowing sounds, and gentle pitching to boats in Hytale **0.8.0-pre.6**.

To check the wake, take a wooden boat out on the water. Turning in place produces foam on both sides. Drifting sideways produces foam on the side the boat is moving toward. The foam fades when the boat stops or you get out.

As you move forward or turn in the water, you'll hear soft splashes that alternate between the two sides. The rowing sounds stop when the boat stops.

Use `/rowing_volume` to check your current volume or `/rowing_volume 50` to set it to 50%. At 100%, you hear the original volume. At 0%, rowing sounds are muted just for you. The server remembers your preference when you reconnect. Command messages and help use the client's language, with English as the fallback.

Use `/rowing_opacity` to check your current opacity or `/rowing_opacity 50` to show the wake and paddle foam at 50% of their original opacity. At 0%, the foam is hidden just for you. At 100%, the original effect is restored. The server saves this setting for each player.

## Build

Install JDK 25 and Maven 3.9 or later. In IntelliJ IDEA, import the project from `pom.xml` and select JDK 25.

```powershell
mvn clean verify
```

The build produces `target/immersive-boats-0.0.1.jar`. Maven resolves the API from the Hytale pre-release repository. The server provides the API when it loads the plugin.