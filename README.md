# Astral Addon

A [Meteor Client](https://meteorclient.com) addon for Minecraft 1.21.11 by Forg, built as a curated module set for Astral Survival.

Maintained as the **Astral Addon** fork of the original addon.

## Categories

Astral Addon uses two Meteor lists:
- `Astral World`
- `Astral Utility`

## Modules

### Stash Hunting
| Module | Description |
|---|---|
| BetterStashFinder | Enhanced stash finder with Xaero waypoint integration, weighted stash scoring, multi-chunk site clustering, selective cross-module trail correlation, configurable alert thresholds, illegal-bedrock detection, revisit priority, age guesses, ender and utility kit-station detection, and dungeon/mineshaft/human-touch anomaly heuristics |
| SearchArea | Defines a search area bounding box for stash hunting routes |
| PortalPatternFinder | Scans cave air for the shapes of removed nether portals |
| BannerFinder | Finds and highlights banners with tracers, alerts, and persistent seen-banner memory |
| ChestIndex | Indexes opened containers client-side, remembers tracked-item storage, and renders matching containers in-world |
| OldChunkNotifier | Flags likely old chunks based on flowing border fluids and can render them for follow-up scouting |

### Movement & Flight
| Module | Description |
|---|---|
| ElytraFlyPlusPlus | Enhanced elytra flight with configurable speed and pitch control |
| AFKVanillaFly | Keeps you airborne while AFK using vanilla flight |
| Pitch40Util | Assists with pitch-40 elytra boost technique |
| AutoPortal | Automatically builds, lights, and can auto-enter nether portals |
| AutoIceBoat | Boat automation for ice highways with steering-aware auto-track |
| RocketSpeed | Automatically uses fireworks when speed drops below the configured threshold |
| PearlChecker | Tracks nearby ender pearls, alerts on throws, and can report landing positions |

### Automation
| Module | Description |
|---|---|
| AutoFarm | All-in-one farming utility: tills, harvests, plants, and bonemeal crops |
| AutoMine | Automatically targets burrow or surround blocks around nearby enemies and can hand them off to Meteor's PacketMine |
| AutoCraft | Automatically crafts items using the recipe book |
| AutoEXPPlus | Automatically manages XP bottle throwing |
| AutoLogPlus | Highly configurable auto-disconnect for travel, crystals, pearls, maces, players, totems, rockets, and laggy conditions |
| AutoDyeShulkers | Automatically dyes shulker boxes |
| Lavacast | Automates the lavacast process with a 5-stage state machine |
| BlockIn | Cages you inside blocks to avoid taking damage |
| LawnMower | Automatically breaks grass, flowers, and saplings around you |
| AutoMoss | Automatically uses bone meal on specific blocks |
| DamBuster | Automatically places and lights TNT around you |
| OreSim | Predicts ore veins from a known world seed and renders them in-world |

### Seed Tools
| Module | Description |
|---|---|
| SlimeChunks | Predicts overworld slime chunks from a known seed, can keep a nearest waypoint, and can hand the nearest chunk to Baritone |
| LootLocator | Finds seed-valid structures whose loot tables can roll a target item, with presets for enchanted gapples, elytra, netherite upgrades, heavy cores, and silence trims |

### ESP & Rendering
| Module | Description |
|---|---|
| AdvancedItemESP | ESP for items with advanced filtering options |
| MobGearESP | Shows the gear worn by nearby mobs |
| AstralGlow | Applies a colourful glow outline to tracked players, with optional opt-in UUID + username sharing for public registries |
| Rendering | Various render tweaks: post-process shaders, structure void, dinnerbone mode, deadmau5 ears, christmas chests |

### Utility
| Module | Description |
|---|---|
| CoordLogger | Logs teleports and global world events with journaling, dedupe, and dimension tagging |
| AutoExtinguish | Puts you out by clearing nearby fire, places water at your feet with optional pickup, and can clear persistent Nether fire on netherrack or soul soil |
| SoundLocator | Turns key sound packets like explosions, pearls, totems, anchors, and portals into location alerts |
| TrailFollower | Follows a recorded movement trail and can persist trail state for resume-after-disconnect workflows |
| TrailMaker | Records a movement trail for TrailFollower and can save, load, and reverse named routes |
| BookTools | Utilities for reading and copying books |
| StashBrander | Brands stash signs with configurable text |
| AdBlocker | Blocks chat advertisement spam |
| DiscordNotifs | Sends configurable Discord webhook notifications with server and dimension context plus module-event support |
| GrimAirPlace | Places blocks in the air for Grim-based servers |
| Beyblade | Let it rip |
| DeathExplore | Allows you to continue exploring after death |
| DropTest | Tracks entity drops over time and outputs statistics to `.minecraft/meteor-client/astral/` |
| MinecartDetector | Detects problematic minecarts, highlights suspicious clusters, and logs them to `.minecraft/meteor-client/astral/` |
| TabGuiScale | Changes GUI scale when opening the tab/player list |
| WhisperLogger | Logs incoming and outgoing whispers to a local Astral csv file |

## Requirements
- Minecraft **1.21.11** (Fabric)
- [Meteor Client](https://meteorclient.com) dev build for **1.21.11**
- [Fabric Loader](https://fabricmc.net) 0.16.x or later

## Optional Dependencies
The following mods are supported but are not required:
- [Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) for BetterStashFinder waypoint creation
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)

## Installation
1. Install Meteor Client for 1.21.11.
2. Drop `astral-3.5.0.jar` into your `.minecraft/mods` folder.
3. Launch the game. Modules appear under the Astral category set listed above.

## Notes
- `SpeedMine`, `Surround`, and `AutoCrystal` already exist in Meteor, so Astral does not ship duplicate copies of them.
- `AstralGlow` sharing is off by default. If enabled, it sends only your UUID and current username to the configured endpoint.
- `AstralGlow` now defaults to the live Astral registry at `http://138.68.235.32:8787`.
- `AstralGlow` now refreshes on join/world change and on manual `.glow refresh`, instead of polling every 20 minutes.
- Fixed an Astral startup crash where early mixins could touch Meteor modules before the module registry existed.
- Fixed an AutoFarm multiplayer crash caused by using the low-level block-breaking progress call on mature crops.
- The repo-root `glow_list.json` is still kept as a public mirror/backup list for `AstralGlow`.
- If you want opt-in users to appear there automatically, deploy the companion service in `tools/glow-registry/` and point `share-url` at its `/share` endpoint.
- A ready-to-use `render.yaml` is included for a free Render deployment of that service.
- The companion service can serve `/glow_list.json` directly or mirror updates back into this repo.
- Astral's public survival seed `7557068879127401510` is prefilled by default in the seed-based modules.
- `SlimeChunks`, `LootLocator`, and `OreSim` fall back to Astral's default survival seed when their own `seed` setting is left blank.
- Use `.chunkbase`, `.chunkbase open`, or `.chunkbase <dimension>` to jump out to Chunkbase with the Astral seed, current coordinates, and your chosen dimension.
- `LootLocator` is structure-based. It finds structures that can roll the target item, not guaranteed chest contents, so it deliberately skips unsupported sources like simple dungeons.
- `SlimeChunks` is overworld-only, while `LootLocator` searches the current dimension you are standing in.

## AstralGlow Registry
- `list-url` is the JSON file Astral reads to decide who should glow.
- `share-url` is an opt-in POST endpoint that accepts only `uuid` and `username`.
- `tools/glow-registry/` contains a small reference service that accepts `/share`, serves `/glow_list.json`, and can mirror that list back to GitHub.
- The public registry format includes usernames, UUIDs, and timestamps so users can inspect what is stored.
- `.glow status` shows the last fetch/share result, and `.glow refresh` forces an immediate refresh/share attempt for testing.

## Building From Source
```bash
./gradlew build
# output: build/libs/astral-3.5.0.jar
```

## Credits

This addon consolidates work from several open-source projects. Full credit goes to the original authors and upstream addon maintainers.

Special project credit:
- **Forg** - original author and maintainer of the upstream addon
- **Astral** - renamed fork and curated continuation

| Source | Author(s) | Modules |
|---|---|---|
| [stardust](https://github.com/0xTas/stardust) | 0xTas | AdBlocker, AutoDyeShulkers, BookTools, StashBrander |
| [Trouser-Streak](https://github.com/etianl/Trouser-Streak) | etianl, windoid | AdvancedItemESP, MobGearESP, PortalPatternFinder |
| [jeff mod](https://github.com/miles352/meteor-stashhunting-addon) | miles352, xqyet, WarriorLost, other credited contributors | AFKVanillaFly, AutoLogPlus, AutoPortal, BetterStashFinder, DiscordNotifs, ElytraFlyPlusPlus, GrimAirPlace, Pitch40Util, SearchArea, TrailFollower, TrailMaker |
| [meteor-rejects](https://github.com/AntiqueWhite/meteor-rejects) | Meteor Development / AntiqueWhite | AutoCraft, AutoFarm, AutoEXPPlus, BlockIn, CoordLogger, Lavacast, Rendering |
| [Numby-hack](https://github.com/cqb13/Numby-hack) | cqb13 | Beyblade |
| [forgs-decluttered-addon](https://github.com/jackforg/forgs-decluttered-addon) | Forg | AutoIceBoat, BannerFinder, DamBuster, DeathExplore, DropTest, ForgGlow, LawnMower, MinecartDetector, AutoMoss, RocketSpeed, TabGuiScale |
