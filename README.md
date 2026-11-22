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

Run `/boat_settings` in game to open the settings menu. Adjust your rowing volume, foam opacity, boat interaction ashore, or the two boat motion toggles. **Apply** saves your changes and closes the menu. **Cancel** closes the menu and discards pending edits.

## Build

Install JDK 25 and Maven 3.9 or later. In IntelliJ IDEA, import the project from `pom.xml` and select JDK 25.

```powershell
mvn clean verify
```

The build produces `target/immersive-boats-0.0.1.jar`. Maven resolves the API from the Hytale pre-release repository. The server provides the API when it loads the plugin.
