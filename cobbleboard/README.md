# CobbleBoard 0.4.3 — Silent stale-entity cleanup

- Removes stale board entities directly through the world entity API.
- Stops repeated `No entity was found` console spam.
- Keeps the v0.4.1/v0.4.2 fix for stale boards, deleted boards, and top-10 ranking churn.
- `/cobbleboard board cleanup` still purges all CobbleBoard entities and rebuilds configured boards.


## v0.4.1 - Stale entity / overlap fix

- Fixed leaderboard rows visually overlapping after standings change (including when an 11th+ player enters the top 10).
- Refresh now removes any previously saved display entities for that board by command tag before rebuilding it.
- Startup now purges stale/orphaned CobbleBoard entities left by a crash or restart, then recreates configured boards once.
- Deleting a board now also removes tagged entities that were not present in the current in-memory entity list.
- New admin repair command: `/cobbleboard board cleanup` purges all CobbleBoard display entities and immediately rebuilds only the boards still configured.

# CobbleBoard 0.2.0

Server-side Fabric 1.21.1 leaderboard mod for Cobbleverse.

## Ranking rules
1. Higher scoreboard score ranks first.
2. Ties are ordered by who reached that exact score first.
3. Admin manual-rank overrides can change the visible standings without changing the underlying scoreboard score.

Ranking state persists in `config/cobbleboard-rankings.json`.
Display definitions persist in `config/cobbleboard-displays.json`.

## Ranking commands

```text
/cobbleboard track <objective>
/cobbleboard standings <objective>
/cobbleboard rank <objective> <player> <position>
/cobbleboard rank <objective> <player> auto
/cobbleboard swap <objective> <playerA> <playerB>
/cobbleboard resetoverrides <objective>
```

## Hologram commands

Create a Top 10 board at coordinates:

```text
/cobbleboard board create kanto ctb_kanto -5828 64 4026 10
```

Set its title:

```text
/cobbleboard board title kanto KANTO GYM CHALLENGE
```

Change number of displayed players:

```text
/cobbleboard board limit kanto 10
```

Move it:

```text
/cobbleboard board move kanto -5828 64 4026
```

Refresh immediately:

```text
/cobbleboard board refresh kanto
```

List configured boards:

```text
/cobbleboard board list
```

Delete:

```text
/cobbleboard board delete kanto
```

Boards auto-refresh every 5 seconds.

## Build (Windows)
Java 21 is required.

```powershell
.\gradlew.bat build
```

Use the regular JAR from `build/libs/` (not the `-sources.jar`).


## v0.3.0 styling commands

Default theme:
- Title: yellow
- Player names: aqua (light blue)
- Objective score: red
- Manual overrides no longer show `*` on public holograms.

Commands:
```text
/cobbleboard board spacing <id> <blocks>
/cobbleboard board titlespacing <id> <blocks>
/cobbleboard board color <id> title <color>
/cobbleboard board color <id> name <color>
/cobbleboard board color <id> score <color>
/cobbleboard board info <id>
/cobbleboard board resetstyle <id>
```

Examples:
```text
/cobbleboard board spacing kanto 0.38
/cobbleboard board titlespacing kanto 0.50
/cobbleboard board color kanto name aqua
/cobbleboard board color kanto score red
```

Style changes save to `config/cobbleboard-displays.json` and refresh immediately.
Existing v0.2 boards are migrated automatically by Gson field defaults when loaded; use
`/cobbleboard board resetstyle <id>` if you want to force the v0.3 defaults.


## v0.4.0 podium color update

Default rank colors:
- 1st place: `gold`
- 2nd place: `light_purple`
- 3rd place: `dark_red`
- 4th place and below: `gray`

Other default styling remains unchanged:
- Title: `yellow`
- Player names: `aqua`
- Objective score: `red`
- Manual rank overrides remain hidden on the public hologram.


## v0.4.0 Floating Panel Mode

Boards now default to `panel` mode. Panel mode renders the title and every leaderboard row inside a single vanilla Text Display entity, so the leaderboard appears as one large floating board instead of separate stacked hologram entities.

Commands:

```text
/cobbleboard board mode <id> panel
/cobbleboard board mode <id> stacked
/cobbleboard board scale <id> <0.25-5.0>
/cobbleboard board width <id> <80-1000>
```

Recommended starting values are `scale 1.35` and `width 240`. Existing board definitions automatically use panel mode unless explicitly changed to `stacked`.
