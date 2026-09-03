# Cobbleverse Mods

Custom server-side mods for the Cobbleverse Minecraft server.

This repository contains:

- **CobbleBoard** — floating scoreboard/leaderboard displays
- **CobbleBounty** — daily Pokémon catching bounties
- **Cobbleverse Pokédex Game** — daily Pokémon guessing game

## Building the JARs

Requirements:

- Java 21
- Minecraft 1.21.1
- Fabric

Each mod is its own Gradle project.

### CobbleBoard

```powershell
cd cobbleboard
.\gradlew.bat clean build
```

### CobbleBounty

```powershell
cd cobblebounty
.\gradlew.bat clean build
```

### Pokédex Game

```powershell
cd cobbleverse-pokedex-game
.\gradlew.bat clean build
```

The finished JAR for each mod will be inside:

```text
build/libs/
```

Copy the normal mod JAR to the server `mods` folder. Do not use a `-sources.jar`.

---

# CobbleBoard

CobbleBoard turns vanilla scoreboard objectives into floating in-world displays.

## Basic Commands

Create a board:

```mcfunction
/cobbleboard board create <id> <objective> <x> <y> <z> <limit>
```

Example:

```mcfunction
/cobbleboard board create bounty bounty_total 100 65 -200 10
/cobbleboard board title bounty BOUNTY HUNTERS
```

Move an existing board:

```mcfunction
/cobbleboard board move <id> <x> <y> <z>
```

Refresh it:

```mcfunction
/cobbleboard board refresh <id>
```

Change display mode:

```mcfunction
/cobbleboard board mode <id> panel
/cobbleboard board mode <id> stacked
/cobbleboard board mode <id> status
```

`panel` is the normal leaderboard display.

`status` is intended for information displays such as today's Pokémon bounty and does not show leaderboard rank numbers or scores.

Useful commands:

```mcfunction
/cobbleboard board list
/cobbleboard board info <id>
/cobbleboard board delete <id>
/cobbleboard board limit <id> <limit>
/cobbleboard board scale <id> <scale>
/cobbleboard board width <id> <width>
/cobbleboard standings <objective>
```

---

# CobbleBounty

CobbleBounty selects a Pokémon for players to catch and submit each day.

The target must be caught during the current active bounty when fresh-catch verification is enabled. Old PC Pokémon and Pokémon caught before an admin reroll will not qualify.

## Player Commands

See today's bounty:

```mcfunction
/bounty
```

This shows the target, rarity, reward, completion status, current streak, and lifetime total.

Submit the Pokémon placed in the Bounty Pasture:

```mcfunction
/bounty submit
```

View lifetime standings:

```mcfunction
/bounty leaderboard
```

View the previous 7 daily bounties:

```mcfunction
/bounty history
```

View your personal bounty statistics:

```mcfunction
/bounty stats
```

Stats include:

- Lifetime completions
- Current streak
- Best streak
- First completions
- Common completions
- Uncommon completions
- Rare completions
- Ultra-Rare completions

## Daily Notifications

When a new daily bounty becomes available, players are notified with:

```text
★ A new Pokémon Bounty is available! Use /bounty for details.
```

The login reminder is only shown once per player per day.

When someone completes the bounty, the server can announce the completion and their current streak.

The first player to complete the bounty each day is also tracked and can receive a separate first-completion announcement.

## Admin Setup

Place a Cobblemon Pasture Block where players will submit their Pokémon.

Set it as the Bounty Pasture:

```mcfunction
/bounty admin setpasture <x> <y> <z>
```

Force a Pokémon for testing:

```mcfunction
/bounty admin set <pokemon>
```

Force a Pokémon and rarity:

```mcfunction
/bounty admin set <pokemon> <bucket>
```

Example:

```mcfunction
/bounty admin set pikachu uncommon
```

Reroll:

```mcfunction
/bounty admin reroll
```

Check the discovered rarity pools:

```mcfunction
/bounty admin pools
```

Reload configuration:

```mcfunction
/bounty admin reload
```

## Rarity Buckets

Available bounty rarity buckets:

```text
common
uncommon
rare
ultra-rare
```

The bucket determines which reward entry is used and can be weighted independently in `config/cobblebounty.json`.

## Optional Features

Streak milestone rewards are prepared in the config but disabled by default:

```json
"enableStreakMilestones": false,
"streakMilestoneRewards": {}
```

No additional streak rewards are given while this is disabled.

Rarity-based presentation settings are also prepared but disabled by default:

```json
"enableRarityPresentation": false,
"rarityPresentation": {
  "common": "gray",
  "uncommon": "green",
  "rare": "light_purple",
  "ultra-rare": "gold"
}
```

Daily and first-completion announcements can also be controlled through:

```json
"dailyAnnouncementEnabled": true,
"firstCompletionAnnouncementEnabled": true
```

## CobbleBoard Displays

CobbleBounty provides:

```text
bounty_total
bounty_streak
bounty_today
```

Lifetime leaderboard:

```mcfunction
/cobbleboard board create bounty bounty_total <x> <y> <z> 10
/cobbleboard board title bounty BOUNTY HUNTERS
/cobbleboard board mode bounty panel
```

Streak leaderboard:

```mcfunction
/cobbleboard board create bountystreak bounty_streak <x> <y> <z> 10
/cobbleboard board title bountystreak BOUNTY STREAKS
/cobbleboard board mode bountystreak panel
```

Today's bounty display:

```mcfunction
/cobbleboard board create dailybounty bounty_today <x> <y> <z> 3
/cobbleboard board title dailybounty TODAY'S BOUNTY
/cobbleboard board mode dailybounty status
```

The `bounty_today` status display shows the current species, rarity, and reward without exposing the scoreboard ordering numbers.

---

# Cobbleverse Pokédex Game

A daily Pokémon guessing challenge.

## Player Commands

View the current puzzle and your hint:

```mcfunction
/pokedexgame status
```

Guess a Pokémon:

```mcfunction
/guess <pokemon>
```

Example:

```mcfunction
/guess pikachu
```

Check the next daily reset:

```mcfunction
/pokedexgame next
```

View the leaderboard:

```mcfunction
/pokedexgame leaderboard
```

## Admin Commands

Start a new random puzzle:

```mcfunction
/pokedexgame admin new
```

Force a Pokémon:

```mcfunction
/pokedexgame admin force <pokemon>
```

Reveal the answer:

```mcfunction
/pokedexgame admin answer
```

Preview all hints:

```mcfunction
/pokedexgame admin preview
```

Reroll the hints without changing the Pokémon:

```mcfunction
/pokedexgame admin rerollhints
```

Reset the current day:

```mcfunction
/pokedexgame admin resetday
```

Reload configuration:

```mcfunction
/pokedexgame admin reload
```

## CobbleBoard Leaderboard

The main points objective is:

```text
pokedex_points
```

Example:

```mcfunction
/cobbleboard board create pokedex pokedex_points <x> <y> <z> 10
/cobbleboard board title pokedex POKEDEX MASTERS
/cobbleboard board mode pokedex panel
```
