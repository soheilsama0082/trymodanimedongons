# AnimeDungeons Fabric — Minecraft 1.16.5

An early source foundation for an anime-inspired dungeon RPG mod using Fabric Loader. This repository intentionally documents what is and is not implemented; it is not represented as a finished or tested release.

## Requirements

- Minecraft Java Edition 1.16.5
- Fabric Loader 0.11.7 or compatible 1.16.5 loader
- Fabric API `0.42.0+1.16`
- Java 16 for building/running this configured target
- Gradle 7.3.3 (provided by the GitHub Actions workflow; no Gradle Wrapper is included)

## Build with GitHub Actions

1. Upload the complete repository contents to a GitHub repository.
2. Ensure the default branch is `main` or `master`.
3. Open **Actions** and run **Build AnimeDungeons Fabric**, or push a commit.
4. If the job succeeds, download the `AnimeDungeons-Fabric-1.16.5` artifact. The JAR is under `build/libs/`.
5. Put the mod JAR and the matching Fabric API JAR in the server's `mods` folder. Clients need Fabric Loader and Fabric API; for future client-visible custom models/animations they will also need the mod and any required resource assets.

The workflow uses the installed Gradle command and does not require `gradlew`. The workflow has not been executed in this environment. If it fails, use the first Gradle compiler/dependency error in the Actions log as the source of truth.

## First-run configuration

On the first server startup the mod attempts to create:

- `config/animedungeons.properties` — settings
- `config/animedungeons-playerdata.properties` — saved XP

Important settings include `entrance_x`, `entrance_y`, `entrance_z`, minimum levels for ranks F–S, room count/spacing, cooldown, XP values, and the public-boss toggle. Restart after editing the config.

## Commands

- `/adungeon` — help
- `/adungeon locate` — shows the configured entrance coordinates
- `/adungeon enter <F|E|D|C|B|A|S>` — enter a dungeon
- `/adungeon exit` — leave current dungeon
- `/adungeon level` — show level and XP
- `/adungeon party create`
- `/adungeon party invite <player>`
- `/adungeon party join <leader>`
- `/adungeon party leave`
- `/adungeon party info`
- `/adungeon generate <rank>` — operator-level command alias for entering/generating a rank
- `/adungeon cancel` — operator-level exit alias

## Implemented in this foundation

- Fabric mod metadata and Gradle build definition for 1.16.5.
- Command registration.
- Rank gates and basic level/XP progression with XP persistence.
- Procedurally varied connected room-chain geometry: multiple vertical layers, ice/cursed palettes, corridors, pillars, crystals and increasing room dimensions/count by rank.
- Vanilla-based named elite enemies and a named rank boss with a boss bar.
- Rank-scaled reward rolls and a server-side area-burst ability attached to rewarded swords through item NBT.
- Basic in-memory party create/invite/join/leave/info.
- Basic instance membership, disconnect handling and failure cooldown timestamp tracking.
- Config generation and GitHub Actions build workflow.

## Important limitations — not yet a complete requested mod

This is a prototype foundation, not a finished game-ready release. In particular:

- Dungeon instances are separate **regions in the same Overworld**, not isolated dimensions. Coordinates are spaced apart, but a robust region-protection/cleanup system is not implemented.
- The current dungeon generator is a winding room chain, not yet a truly enormous natural cave network with hand-authored rooms, branching paths, hidden keys, puzzles and reliable navigation.
- Enemies and bosses still use vanilla entity types with custom names/effects. Custom 3D models, animations, new entity types and anime-grade attack patterns require additional entity/client rendering work and are not implemented here.
- The `public_boss_enabled` setting is currently only a config placeholder; a scheduled public Overworld boss encounter is not implemented.
- Boss completion currently checks entity death/removal. Proper boss phase state machines, contribution tracking and reward eligibility checks need implementation.
- Dead-party-member revival after a victory is not implemented. Respawn/rejoin recovery and safe offline-instance recovery are incomplete.
- Cooldown values are written to disk, but a full robust wipe/anti-bypass system and proper online display of remaining time need more work.
- Parties are memory-only and do not survive server restarts. Leadership handoff and invite expiry are incomplete.
- Rewarded swords have a basic right-click area burst. There is no full custom weapon system, unique skill trees, balanced rarity system or client-side visual effects yet.
- Generated blocks are not cleaned up automatically. Use a test world and back it up before testing; do not run this prototype on a valuable production world.
- This source has **not been compiled or tested against a running Minecraft server in this environment**. API mismatches may remain. A successful GitHub Actions build is required before installing it.

## Safety for testing

Use a fresh disposable test world and back up the server first. This prototype writes generated blocks into the Overworld in spaced regions and does not currently remove them automatically.
