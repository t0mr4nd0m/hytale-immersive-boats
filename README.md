# Immersive Boats

Immersive Boats adds rowing effects and makes it easier to pick up Hytale’s boats.

## Effects on the water

- Foam wakes as you maneuver.
- Soft splashes as you row.
- Gentle pitching while rowing and side-to-side rocking at rest.

## Boat interactions

Press **F** (the default interaction key) to interact with a boat.

- If the boat can still sail, you board it as usual.
- If the boat is fully ashore or stranded at the shoreline, your personal interaction setting applies.

## Configuration

Use `/rowing_volume` to check your current volume or `/rowing_volume 50` to set it to 50%. At 100%, you hear the original volume. At 0%, rowing sounds are muted just for you. The server remembers your preference when you reconnect. Command messages and help use the client's language, with English as the fallback.

Use `/rowing_opacity` to check your current opacity or `/rowing_opacity 50` to show the wake and paddle foam at 50% of their original opacity. At 0%, the foam is hidden just for you. At 100%, the original effect is restored. The server saves this setting for each player.

Use `/boat_mode` to check your current boat interaction mode with boats that are fully ashore or stranded at the shoreline.

| Command | Interaction ashore |
| --- | --- |
| `/boat_mode board` | Board the boat as in vanilla Hytale. |
| `/boat_mode pick-up` | Pick up the boat directly. If your inventory is full, it drops as an item. This is the default. |
| `/boat_mode drop` | Drop the boat as an item even when your inventory has room. |

## Build

Install JDK 25 and Maven 3.9 or later. In IntelliJ IDEA, import the project from `pom.xml` and select JDK 25.

```powershell
mvn clean verify
```

The build produces `target/immersive-boats-0.0.1.jar`. Maven resolves the API from the Hytale pre-release repository. The server provides the API when it loads the plugin.
