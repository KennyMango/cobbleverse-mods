# CobbleBounty v0.3.1

Build-fix release for Fabric 1.21.1 / Cobblemon 1.7.3.

Fixes:
- Adds Fabric Language Kotlin to the Gradle compile classpath so Cobblemon event subscriptions compile.
- Uses Brigadier `CommandSyntaxException` instead of broad checked `Exception` in `/bounty` command handlers.
- Removes the obsolete no-argument `Pokemon.onChange()` call; the bounty marker is stored directly in the Pokémon persistent NBT during the capture event.

Build from PowerShell:
```powershell
.\gradlew.bat build
```

The regular JAR will be in `build\libs`.

# CobbleBounty 0.3.0

Server-side Fabric 1.21.1 / Cobblemon 1.7.3 daily Pokémon bounty mod.

## v0.3.0: automatic rarity buckets + bucket rewards

CobbleBounty now scans the server's loaded `spawn_pool_world` JSON resources and automatically builds bounty pools from Cobblemon's four spawn rarity buckets:

- `common`
- `uncommon`
- `rare`
- `ultra-rare`

The daily roll happens in two stages:

1. choose an enabled rarity bucket using `bucketWeights`;
2. choose a random eligible species from that bucket.

If one species has natural spawn entries in more than one bucket, CobbleBounty assigns that species to its **least-rare/easiest** bucket. This prevents a Pokémon with a common spawn route from paying an ultra-rare reward.

## Config

First launch / upgrade writes `config/cobblebounty.json` in this format:

```json
{
  "timezone": "America/Vancouver",
  "pastureSearchRadius": 16,
  "requirePastureTether": true,
  "requireCaughtAfterBountyStart": true,
  "broadcastCompletion": true,

  "enabledBuckets": [
    "common",
    "uncommon",
    "rare",
    "ultra-rare"
  ],

  "bucketWeights": {
    "common": 45.0,
    "uncommon": 30.0,
    "rare": 18.0,
    "ultra-rare": 7.0
  },

  "bucketRewards": {
    "common": {
      "item": "minecraft:enchanted_golden_apple",
      "count": 1
    },
    "uncommon": {
      "item": "minecraft:enchanted_golden_apple",
      "count": 1
    },
    "rare": {
      "item": "minecraft:enchanted_golden_apple",
      "count": 2
    },
    "ultra-rare": {
      "item": "minecraft:enchanted_golden_apple",
      "count": 3
    }
  },

  "excludedSpecies": [],
  "autoBuildPoolFromSpawnData": true,

  "manualBucketPools": {
    "common": [],
    "uncommon": [],
    "rare": [],
    "ultra-rare": []
  }
}
```

### Only pull from certain rarity buckets

For example, to disable common Pokémon:

```json
"enabledBuckets": [
  "uncommon",
  "rare",
  "ultra-rare"
]
```

### Change how often buckets are selected

Weights are relative. Example:

```json
"bucketWeights": {
  "common": 0,
  "uncommon": 60,
  "rare": 30,
  "ultra-rare": 10
}
```

With common disabled/zero, this is roughly 60% uncommon, 30% rare, 10% ultra-rare.

### Change rewards by rarity

```json
"bucketRewards": {
  "common": {"item": "minecraft:enchanted_golden_apple", "count": 1},
  "uncommon": {"item": "minecraft:enchanted_golden_apple", "count": 1},
  "rare": {"item": "minecraft:enchanted_golden_apple", "count": 2},
  "ultra-rare": {"item": "minecraft:enchanted_golden_apple", "count": 3}
}
```

The item itself can also differ by bucket if desired.

### Exclude specific Pokémon

```json
"excludedSpecies": [
  "arceus",
  "mewtwo",
  "rayquaza"
]
```

### Manual pool additions

Most servers should leave these empty. They are available for custom Pokémon/addons whose spawn data is not stored in the normal `spawn_pool_world` format.

```json
"manualBucketPools": {
  "common": [],
  "uncommon": [],
  "rare": [],
  "ultra-rare": ["custommon"]
}
```

## Commands

Player:

```mcfunction
/bounty
/bounty submit
/bounty leaderboard
```

Admin:

```mcfunction
/bounty admin reroll
/bounty admin set <species>
/bounty admin set <species> <bucket>
/bounty admin pools
/bounty admin setpasture <x> <y> <z>
/bounty admin reload
```

`/bounty admin pools` shows how many Pokémon were discovered in each rarity pool.

After editing the config:

```mcfunction
/bounty admin reload
```

## Catch verification

The v0.2 catch-verification system remains in place. A submitted Pokémon must have been captured while the exact current bounty ID was active. Old PC Pokémon and Pokémon caught before an admin reroll do not qualify when `requireCaughtAfterBountyStart=true`.

## CobbleBoard objectives

```text
bounty_total
bounty_streak
bounty_today
```

## Build

Java 21, Fabric 1.21.1, Cobblemon 1.7.3.

```powershell
.\gradlew.bat build
```

Regular JAR output:

```text
build/libs/cobblebounty-0.3.0.jar
```
