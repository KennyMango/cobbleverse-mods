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

## Player Commands

See today's bounty:

```mcfunction
/bounty
```

Submit the Pokémon placed in the Bounty Pasture:

```mcfunction
/bounty submit
```

View standings:

```mcfunction
/bounty leaderboard
```

The Pokémon must be caught during the current bounty when fresh-catch verification is enabled.

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
```

Streak leaderboard:

```mcfunction
/cobbleboard board create bountystreak bounty_streak <x> <y> <z> 10
/cobbleboard board title bountystreak BOUNTY STREAKS
```

Today's bounty display:

```mcfunction
/cobbleboard board create dailybounty bounty_today <x> <y> <z> 3
/cobbleboard board title dailybounty TODAY'S BOUNTY
/cobbleboard board mode dailybounty status
```

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
