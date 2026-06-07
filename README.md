# The Backrooms Mod — Forge 1.21.1

> *"If you're not careful and you noclip out of reality in the wrong areas,  
> you'll end up in the Backrooms..."*

---

## 📦 What's in the Mod

### 🕳️ Null Zones
Random chunks in the Overworld contain **null zones** — a single vertical column of 
**Ghost Wall blocks** that stretches from the surface all the way down to bedrock (y = -64).

- **Ghost Wall blocks** look like normal sandstone walls but have NO collision.  
  Players walk/fall straight through them.
- Each chunk has a **1-in-12 chance** of receiving a null zone on load.
- Null zones are **deterministic** — same seed = same null zone positions, always.
- **Random nearby null zones** also appear near players periodically (1-in-20 per 
  3 seconds) to simulate the world "glitching".

### 🌀 Falling Into the Void → The Backrooms
When a player falls through a null zone column and drops below Y = -65:
- They are **teleported to The Backrooms dimension**.
- A flavor message appears: *"You have noclipped out of reality..."*

### 🏢 The Backrooms Dimension
Inspired by the original SCP / creepypasta and the screenshot you shared:

| Feature | Block |
|---------|-------|
| **Floor** | Yellow Carpet (aged carpet aesthetic) |
| **Walls/Fill** | Sandstone (aged yellow wallpaper look) |
| **Ceiling** | Chiseled Stone Bricks (drop ceiling tiles) |
| **Lights** | (Glowstone embedded in ceiling — no skylight) |
| **Base** | Bedrock |

The dimension is **flat and enclosed**, mimicking endless hallways with:
- No sky / ceiling
- No weather
- Constant dim ambient light (`ambient_light: 0.1`)
- Cave sound ambience looping softly
- Fixed "day" time (no time cycle)

---

## 🔧 How to Build

### Requirements
- Java 21 (JDK)
- Forge MDK for 1.21.1 (forge-1.21.1-47.3.0-mdk)

### Setup Steps

1. Download the [Forge 1.21.1 MDK](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html)
2. Extract the MDK, then **replace the `src/` folder** with this mod's `src/` folder
3. **Replace `build.gradle`** with this mod's `build.gradle`
4. **Replace `src/main/resources/META-INF/mods.toml`** with this mod's `mods.toml`
5. In the mod root directory, run:
   ```bash
   ./gradlew genEclipseRuns    # if using Eclipse
   ./gradlew genIntellijRuns   # if using IntelliJ IDEA
   ```
6. Open the project in your IDE and import the Gradle project
7. Run the `runClient` Gradle task to test

### Building the JAR
```bash
./gradlew build
```
The output JAR will be in `build/libs/backrooms-1.0.0.jar`.

---

## 📁 Project Structure

```
src/main/java/com/backrooms/mod/
├── BackroomsMod.java              ← Main mod class / entry point
├── block/
│   ├── ModBlocks.java             ← Block registry (DeferredRegister)
│   ├── GhostWallBlock.java        ← No-collision wall block (the "ghost block")
│   └── NullZoneMarkerBlock.java   ← Invisible server-side column marker
├── dimension/
│   └── ModDimensions.java         ← ResourceKey<Level> for the Backrooms dim
├── event/
│   ├── NullZoneEventHandler.java  ← Core logic: chunk gen, tick check, teleport
│   ├── BackroomsTeleporter.java   ← ITeleporter: places player safely in Backrooms
│   └── WorldEventHandler.java     ← Clears NullZoneManager on world unload
└── world/
    ├── NullZoneManager.java        ← Thread-safe tracker of all null zone XZ positions
    └── BackroomsWorldGenData.java  ← Constants for world gen

src/main/resources/data/backrooms/
├── dimension/
│   └── backrooms.json             ← Flat superflat dimension definition
├── dimension_type/
│   └── backrooms_type.json        ← Dim type: enclosed, no sky, ambient glow
└── worldgen/
    ├── biome/
    │   └── backrooms_biome.json   ← Biome: cave sounds, warm yellowish colors
    ├── noise_settings/
    │   └── backrooms_noise.json   ← Flat noise settings
    └── world_preset/
        └── backrooms_world.json   ← World preset including all 4 dimensions
```

---

## 🎮 Gameplay Tips

- **Finding null zones**: Walk through the Overworld and watch for walls that seem 
  slightly off or that you can clip into. They look like sandstone.
- **Escaping The Backrooms**: Currently you're trapped! (Future feature: find a 
  glowing "exit" crack in the wall...)
- **F3 debug**: Use F3 to watch your Y coordinate — if it drops rapidly to -65, 
  you're in a null zone!

---

## 🛠️ Customization (config coming soon)

Currently tunable by editing `NullZoneEventHandler.java`:

| Constant | Default | Meaning |
|----------|---------|---------|
| `NULL_ZONE_SPAWN_CHANCE` | 12 | 1-in-N chance per chunk |
| `NULL_ZONE_RANDOM_NEARBY_CHANCE` | 60 | Ticks between random nearby checks |
| `COLUMNS_PER_NULL_ZONE` | 1 | Ghost columns per null zone chunk |
| `VOID_THRESHOLD_Y` | -65 | Y below which teleport triggers |

---

## 📜 License

MIT — free to use in modpacks, fork, modify.
