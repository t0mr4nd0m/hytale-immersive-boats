# Immersive Boats

Immersive Boats makes Hytale's boats feel more realistic with animated oars, foam wakes, water sounds, improved physics, and better interactions (Stop beating your boats to pick them up!).

By T0m.R4nD0m / [t0mr4nd0m@gmail.com](mailto:t0mr4nd0m@gmail.com)

![Platform](https://img.shields.io/badge/platform-Hytale-purple)
![GitHub release](https://img.shields.io/github/v/release/T0mR4nD0m/hytale-immersive-boats)
![GitHub last commit](https://img.shields.io/github/last-commit/T0mR4nD0m/hytale-immersive-boats)
![GitHub repo size](https://img.shields.io/github/repo-size/T0mR4nD0m/hytale-immersive-boats)
![Java](https://img.shields.io/badge/java-25-orange)
![Build](https://img.shields.io/badge/build-maven-green)
![License](https://img.shields.io/github/license/T0mR4nD0m/hytale-immersive-boats)

## Water effects

- Foam wakes and soft splashes as boats move, even without a pilot.
- Two animated oars, with realistic animations and water effects, appear when a player takes the helm.
- Gentle pitching while rowing, a slight lean into turns, and side-to-side rocking at rest.

## Boat interactions

- If the boat is navigable, you can board it as usual.
- If the boat is ashore or stranded at the shoreline, your interaction preference determines whether you board it, pick it up, or drop it as an item.

## Enhanced physics

- Boats retain momentum on the water and slow down gradually when the pilot stops rowing or dismounts.
- Moving boats, players, and other entities with collision can push boats without a pilot and piloted boats that are stationary.
- Boats that are still navigable can be pushed away from or along the shore.

## Configuration

Run `/boat_settings` in game to adjust rowing volume, foam opacity, how you interact with boats ashore, visibility of animated oars and whether boats rock at rest or pitch while rowing. 

**Apply** saves your changes and closes the menu.
**Cancel** closes the menu and discards pending edits.

Enhanced physics is enabled by default. Server operators and players with the `immersiveboats.settings.physics` permission can toggle it for everyone from the same menu.

## Build

Install JDK 25 and Maven 3.9 or later. In IntelliJ IDEA, import the project from `pom.xml` and select JDK 25.

```powershell
mvn clean verify
```

The build produces `target/immersive-boats-0.0.1.jar`. Maven resolves the API from the Hytale pre-release repository. The server provides the API when it loads the plugin.
