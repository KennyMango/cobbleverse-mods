# Cobbleverse Pokédex Game v0.5.4

## v0.5.4 final update

- Wordle-day join/help text now explicitly tells players that **every answer is Pokémon-related**.
- The message clarifies that answers can come from Pokémon, moves, items, abilities, regions, and other Pokémon concepts.
- Keeps v0.5.3 dictionary API validation and caching behavior.
- Keeps private admin test mode and alternating Pokédex/Wordle days.


## v0.5.3 — API-backed legitimate-word guesses

Wordle answers remain curated locally in `config/cobbleverse-pokedex-game/wordle-answers.txt`. Guesses now work like this:

1. Must be exactly 5 letters A-Z.
2. Answers and words in `wordle-valid-guesses.txt` are accepted immediately.
3. Unknown words are checked asynchronously against Datamuse.
4. HTTP 2xx = valid word and the guess counts normally. HTTP 404 = invalid word and no attempt is used.
5. API failures/timeouts do not consume an attempt.
6. API results are cached in `word-validation-cache.txt`, so a word normally only makes one web request ever.

New config options in `cobbleverse-pokedex-game.json`:

```json
"wordValidationApiEnabled": true,
"wordValidationApiBaseUrl": "https://api.datamuse.com/words?sp=",
"wordValidationTimeoutSeconds": 5
```

The check is asynchronous so a slow dictionary service does not block the Minecraft server tick. Admin test Wordle sessions use the same validation path. The local valid-guesses file remains an override: if the API fails to recognize a legitimate word, add it there and run `/pokedexgame admin reload`.

---

# Cobbleverse Daily Game v0.5.2

Minecraft 1.21.1 / Fabric / Cobblemon 1.7.x

## v0.5.2: Open Wordle Guesses

The daily game now alternates automatically by calendar date:

- Pokédex day: the existing private 10-hint mystery Pokémon challenge.
- Wordle day: a private 5-letter Pokémon-themed Wordle with 6 attempts.
- Default anchor: `2026-08-29` is a Wordle day. The day before/after is Pokédex, then Wordle, and so on.
- Timezone remains `America/Vancouver` by default.
- Calendar parity is deterministic, so restarts or being offline do not break the rotation.

### Same player command

`/guess <answer>` works for both games. On Pokédex days it expects a Pokémon. On Wordle days it expects a 5-letter word.

`/pokedexgame status` automatically shows the correct UI for today's game.

## Wordle scoring

Players get six attempts. Default points are:

1. 10 points
2. 8 points
3. 6 points
4. 4 points
5. 2 points
6. 1 point

Wordle feedback uses colored `[LETTER]` blocks in chat:

- Green: correct letter and position
- Yellow: correct letter, wrong position
- Dark gray: letter is not used by the answer

Repeated letters use standard Wordle matching rules.

## Expandable Wordle dictionary

### v0.5.2 Wordle guess behavior
Players may submit **any exactly five-letter A-Z guess**, even when that guess is not listed in either Wordle dictionary file. The guess consumes an attempt and returns the normal green/yellow/gray elimination pattern. The dictionary files now control answer selection and optional curated words only; they no longer gate normal player guesses. Non-five-letter or non-alphabetic input is still rejected without consuming an attempt. Admin Wordle test mode uses the same rule.


On first server launch v0.5.2 creates:

- `config/cobbleverse-pokedex-game/wordle-answers.txt`
- `config/cobbleverse-pokedex-game/wordle-valid-guesses.txt`

`wordle-answers.txt` contains words that can be selected as the daily answer.
`wordle-valid-guesses.txt` is retained for compatibility and curated word lists, but normal gameplay now accepts any exactly five-letter A-Z guess.
Every answer is automatically accepted as a valid guess.

To expand the dictionary, add one word per line. Entries must contain exactly five A-Z letters. Blank lines and lines starting with `#` are ignored. Invalid entries are skipped with a server-console warning. Duplicate entries are removed automatically.

After editing either file, run:

`/pokedexgame admin reload`

No JAR rebuild or server restart is required.

## Wordle repeat protection

`wordleNoRepeatAnswers` defaults to 60. The mod avoids the most recent 60 Wordle answers where the dictionary is large enough. If the available answer pool becomes exhausted, it falls back to the full answer list instead of failing the daily reset.

The existing Pokédex answer history remains separate and continues its 14-answer no-repeat behavior.

## Join message

Players receive the intro only while today's puzzle is unfinished for them. Solved Pokédex/Wordle players do not receive it again when reconnecting that day. A Wordle player who uses all six attempts is also treated as completed, so they are not spammed on reconnect. The intro becomes eligible again after the next daily reset.

## Admin commands

- `/pokedexgame admin new` — reroll today's answer without undoing already awarded lifetime points.
- `/pokedexgame admin resetday` — undo today's awarded win/points, clear today's player progress, and select a new answer for the same game type.
- `/pokedexgame admin force <pokemon>` — force a Pokémon on a Pokédex day.
- `/pokedexgame admin forceword <word>` — force a word from `wordle-answers.txt` on a Wordle day.
- `/pokedexgame admin answer` — reveal today's answer.
- `/pokedexgame admin preview` — Pokédex-day hint preview only.
- `/pokedexgame admin rerollhints` — Pokédex-day hint rebuild only.
- `/pokedexgame admin reload` — reload JSON config and both Wordle dictionary files.

## Persistent stats and scoreboard

Existing v0.4.x stats migrate automatically. Historical `correctGuesses` are treated as Pokédex wins during migration.

Main existing scoreboard objective names are deliberately preserved so existing CobbleBoard boards continue to work:

- `pokedex_points` — combined lifetime points from both puzzle types
- `pokedex_correct` — combined successful daily puzzles
- `pokedex_avg10` — average Pokédex hint number x10

New optional objectives:

- `pokedex_wins` — Pokédex solves
- `wordle_wins` — Wordle solves

Your existing CobbleBoard tracking `pokedex_points` can therefore remain unchanged.

## Pokédex hint progression

1. Generation + region — 10 pts
2. 50-wide National Dex range — 9 pts
3. Gender ratio — 8 pts
4. Egg Group — 7 pts
5. Broad BST range — 6 pts
6. Weight category — 5 pts
7. Height category — 4 pts
8. One type — 3 pts
9. Full typing — 2 pts
10. First letter + name length + 25-wide Dex range — 1 pt

## Build

PowerShell:

`./gradlew.bat build`

Use the normal JAR from `build/libs`, not the sources JAR.


## v0.5.1 Admin Test Mode
Admin/OPs can test any puzzle without changing the live daily round or any lifetime statistics.

Commands:
- `/pokedexgame admin test pokemon <pokemon>` - test a chosen Pokémon.
- `/pokedexgame admin test word <word>` - test a chosen Wordle answer.
- `/pokedexgame admin test random pokemon` - random Pokémon test.
- `/pokedexgame admin test random word` - random Wordle test.
- `/pokedexgame admin test current` - clone the current daily puzzle into a private test session.
- `/pokedexgame admin test status` - show current private test progress.
- `/pokedexgame admin test answer` - reveal only your test answer.
- `/pokedexgame admin test reset` - restart the selected test from hint/attempt 1.
- `/pokedexgame admin test stop` - leave test mode and return `/guess` to the normal daily puzzle.

Test sessions are in-memory and private to each admin. They do not award points, increment wins, mark the daily puzzle complete, alter streak/progress data, or update scoreboard/CobbleBoard values.
