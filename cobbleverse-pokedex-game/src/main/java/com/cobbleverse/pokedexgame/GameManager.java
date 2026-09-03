package com.cobbleverse.pokedexgame;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class GameManager {
    public enum GameType { POKEDEX, WORDLE }

    private final Path statsPath;
    private final Path wordleDirectory;
    private final Random random = new Random();
    private final Map<UUID, Long> lastGuessAt = new HashMap<>();
    private final Set<UUID> solved = new HashSet<>();
    private final Set<UUID> completed = new HashSet<>();
    private final Map<UUID, TestSession> testSessions = new HashMap<>();
    private PersistentStats stats;
    private GameConfig config;
    private WordleDictionary wordleDictionary;

    private GameType activeType = GameType.POKEDEX;
    private CobblemonSpeciesAccess.SpeciesInfo pokemonAnswer;
    private String wordleAnswer = "";
    private List<String> hints = List.of();
    private String activeDate = "";

    private static final class TestSession {
        final GameType type;
        final CobblemonSpeciesAccess.SpeciesInfo pokemon;
        final String word;
        final List<String> hints;
        int hintNumber = 1;
        final List<String> wordleGuesses = new ArrayList<>();
        boolean completed = false;
        boolean solved = false;

        TestSession(CobblemonSpeciesAccess.SpeciesInfo pokemon, List<String> hints) {
            this.type = GameType.POKEDEX;
            this.pokemon = pokemon;
            this.word = "";
            this.hints = hints;
        }

        TestSession(String word) {
            this.type = GameType.WORDLE;
            this.pokemon = null;
            this.word = word;
            this.hints = List.of();
        }
    }

    public GameManager(Path statsPath, GameConfig config) {
        this.statsPath = statsPath;
        this.wordleDirectory = statsPath.getParent().resolve("cobbleverse-pokedex-game");
        this.config = config;
        this.stats = PersistentStats.load(statsPath);
        this.wordleDictionary = new WordleDictionary(wordleDirectory);
    }

    public void reload(GameConfig config) {
        this.config = config;
        this.wordleDictionary.reload();
    }

    public boolean active() {
        return activeType == GameType.POKEDEX ? pokemonAnswer != null : wordleAnswer != null && !wordleAnswer.isBlank();
    }
    public GameType gameType() { return activeType; }
    public boolean isWordleDay() { return activeType == GameType.WORDLE; }

    /** True when /guess for this player is currently a Wordle guess (daily or private admin test). */
    public boolean isWordleContext(ServerPlayer player) {
        TestSession test = testSessions.get(player.getUUID());
        return test != null ? test.type == GameType.WORDLE : activeType == GameType.WORDLE;
    }

    /** Local answer/override dictionaries always count as valid without an API request. */
    public boolean isKnownLocalWordleGuess(String word) {
        return wordleDictionary.isValidGuess(word);
    }
    public String activeDate() { return activeDate; }
    public int solvedCount() { return solved.size(); }

    public void initializeDaily(MinecraftServer server) {
        ensureDailyRound(server, false);
        syncAllScoreboards(server);
    }
    public void tick(MinecraftServer server) { ensureDailyRound(server, true); }

    private GameType typeForDate(LocalDate date) {
        long delta = ChronoUnit.DAYS.between(config.wordleAnchorDate(), date);
        return Math.floorMod(delta, 2L) == 0L ? GameType.WORDLE : GameType.POKEDEX;
    }

    private void ensureDailyRound(MinecraftServer server, boolean announceRollover) {
        LocalDate todayDate = LocalDate.now(config.zoneId());
        String today = todayDate.toString();
        GameType expectedType = typeForDate(todayDate);
        if (active() && today.equals(activeDate) && activeType == expectedType) return;

        if (active() && announceRollover) revealPreviousAnswer(server);

        if (today.equals(stats.dailyDate) && stats.dailyAnswer != null && !stats.dailyAnswer.isBlank()) {
            GameType savedType = parseType(stats.dailyGameType);
            if (savedType == expectedType && restoreSavedRound(savedType, today)) return;
        }

        startNewDaily(server, todayDate, announceRollover);
    }

    private boolean restoreSavedRound(GameType type, String today) {
        if (type == GameType.POKEDEX) {
            CobblemonSpeciesAccess.SpeciesInfo restored = CobblemonSpeciesAccess.byGuess(stats.dailyAnswer);
            if (restored == null) return false;
            pokemonAnswer = restored;
            wordleAnswer = "";
            hints = buildHints(restored);
        } else {
            String restored = WordleDictionary.normalize(stats.dailyAnswer);
            if (!restored.matches("[A-Z]{5}")) return false;
            wordleAnswer = restored;
            pokemonAnswer = null;
            hints = List.of();
        }

        activeType = type;
        activeDate = today;
        solved.clear();
        completed.clear();
        for (String uuid : stats.dailySolvedPlayers) parseUuidInto(uuid, solved);
        for (String uuid : stats.dailyCompletedPlayers) parseUuidInto(uuid, completed);
        completed.addAll(solved);
        return true;
    }

    private boolean startNewDaily(MinecraftServer server, LocalDate date, boolean announce) {
        GameType type = typeForDate(date);
        boolean ok = type == GameType.POKEDEX ? chooseNewPokemon() : chooseNewWordle();
        if (!ok) return false;

        activeType = type;
        activeDate = date.toString();
        solved.clear();
        completed.clear();
        lastGuessAt.clear();

        stats.dailyDate = activeDate;
        stats.dailyGameType = type.name();
        stats.dailyAnswer = type == GameType.POKEDEX ? pokemonAnswer.name() : wordleAnswer;
        stats.dailySolvedPlayers.clear();
        stats.dailyCompletedPlayers.clear();
        stats.dailyPlayerHints.clear();
        stats.dailyWordleGuesses.clear();
        stats.save(statsPath);

        if (announce) broadcastNewPuzzle(server);
        return true;
    }

    private boolean chooseNewPokemon() {
        List<CobblemonSpeciesAccess.SpeciesInfo> pool = CobblemonSpeciesAccess.implemented(config.minPokedex, config.maxPokedex);
        if (pool.isEmpty()) return false;
        Set<String> excluded = new HashSet<>();
        for (String recent : stats.recentDailyAnswers) if (recent != null && !recent.isBlank()) excluded.add(normalizeSpeciesName(recent));
        if (activeType == GameType.POKEDEX && stats.dailyAnswer != null && !stats.dailyAnswer.isBlank()) excluded.add(normalizeSpeciesName(stats.dailyAnswer));
        List<CobblemonSpeciesAccess.SpeciesInfo> eligible = pool.stream().filter(s -> !excluded.contains(normalizeSpeciesName(s.name()))).toList();
        if (eligible.isEmpty()) eligible = pool;
        pokemonAnswer = eligible.get(random.nextInt(eligible.size()));
        wordleAnswer = "";
        hints = buildHints(pokemonAnswer);
        rememberRecent(stats.recentDailyAnswers, pokemonAnswer.name(), 14);
        return true;
    }

    private boolean chooseNewWordle() {
        List<String> pool = wordleDictionary.answers();
        if (pool.isEmpty()) return false;
        Set<String> excluded = new HashSet<>();
        for (String recent : stats.recentWordleAnswers) excluded.add(WordleDictionary.normalize(recent));
        List<String> eligible = pool.stream().filter(w -> !excluded.contains(w)).toList();
        if (eligible.isEmpty()) eligible = pool;
        wordleAnswer = eligible.get(random.nextInt(eligible.size()));
        pokemonAnswer = null;
        hints = List.of();
        rememberRecent(stats.recentWordleAnswers, wordleAnswer, config.wordleNoRepeatAnswers);
        return true;
    }

    private static void rememberRecent(List<String> list, String value, int max) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        list.removeIf(v -> v != null && v.trim().toUpperCase(Locale.ROOT).equals(normalized));
        list.add(value);
        while (list.size() > max && !list.isEmpty()) list.remove(0);
    }

    private static String normalizeSpeciesName(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT); }
    private static void parseUuidInto(String text, Set<UUID> target) { try { target.add(UUID.fromString(text)); } catch (Exception ignored) {} }
    private static GameType parseType(String value) { try { return GameType.valueOf(value); } catch (Exception ignored) { return GameType.POKEDEX; } }

    public boolean adminResetRandom(MinecraftServer server) {
        LocalDate today = LocalDate.now(config.zoneId());
        boolean ok = activeType == GameType.WORDLE ? chooseNewWordle() : chooseNewPokemon();
        if (!ok) return false;
        activeDate = today.toString();
        solved.clear(); completed.clear(); lastGuessAt.clear();
        stats.dailyDate = activeDate;
        stats.dailyGameType = activeType.name();
        stats.dailyAnswer = activeType == GameType.WORDLE ? wordleAnswer : pokemonAnswer.name();
        stats.dailySolvedPlayers.clear(); stats.dailyCompletedPlayers.clear(); stats.dailyPlayerHints.clear(); stats.dailyWordleGuesses.clear();
        stats.save(statsPath);
        broadcastNewPuzzle(server);
        return true;
    }

    public int adminResetDay(MinecraftServer server) {
        int revertedPlayers = 0;
        for (String uuidText : new HashSet<>(stats.dailySolvedPlayers)) {
            PlayerStats ps = stats.players.get(uuidText);
            if (ps == null) continue;
            if (activeType == GameType.POKEDEX) {
                int hintNumber = Math.max(1, Math.min(10, stats.dailyPlayerHints.getOrDefault(uuidText, 1)));
                ps.points = Math.max(0, ps.points - config.pointsByHint[hintNumber - 1]);
                ps.correctGuesses = Math.max(0, ps.correctGuesses - 1);
                ps.pokedexWins = Math.max(0, ps.pokedexWins - 1);
                ps.totalHintsAtSolve = Math.max(0, ps.totalHintsAtSolve - hintNumber);
            } else {
                int attempts = Math.max(1, Math.min(6, stats.dailyWordleGuesses.getOrDefault(uuidText, List.of()).size()));
                ps.points = Math.max(0, ps.points - config.wordlePointsByAttempt[attempts - 1]);
                ps.correctGuesses = Math.max(0, ps.correctGuesses - 1);
                ps.wordleWins = Math.max(0, ps.wordleWins - 1);
            }
            revertedPlayers++;
            if (ps.lastKnownName != null && !ps.lastKnownName.isBlank()) ScoreboardSync.sync(server, ps.lastKnownName, ps);
        }
        boolean started = startNewDaily(server, LocalDate.now(config.zoneId()), true);
        if (!started) stats.save(statsPath);
        return started ? revertedPlayers : -1;
    }

    public boolean adminForcePokemon(MinecraftServer server, String guess) {
        if (activeType != GameType.POKEDEX) return false;
        CobblemonSpeciesAccess.SpeciesInfo species = CobblemonSpeciesAccess.byGuess(guess);
        if (species == null || species.dex() < config.minPokedex || species.dex() > config.maxPokedex) return false;
        pokemonAnswer = species; wordleAnswer = ""; hints = buildHints(species);
        resetForcedRoundState();
        stats.dailyAnswer = species.name();
        stats.save(statsPath);
        broadcast(server, Component.literal("★ DAILY POKÉDEX RESET BY ADMIN ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        return true;
    }

    public boolean adminForceWordle(MinecraftServer server, String word) {
        if (activeType != GameType.WORDLE) return false;
        String normalized = WordleDictionary.normalize(word);
        if (!wordleDictionary.isAnswer(normalized)) return false;
        wordleAnswer = normalized; pokemonAnswer = null; hints = List.of();
        resetForcedRoundState();
        stats.dailyAnswer = wordleAnswer;
        stats.save(statsPath);
        broadcast(server, Component.literal("★ DAILY WORDLE RESET BY ADMIN ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        return true;
    }

    private void resetForcedRoundState() {
        activeDate = LocalDate.now(config.zoneId()).toString();
        solved.clear(); completed.clear(); lastGuessAt.clear();
        stats.dailyDate = activeDate; stats.dailyGameType = activeType.name();
        stats.dailySolvedPlayers.clear(); stats.dailyCompletedPlayers.clear(); stats.dailyPlayerHints.clear(); stats.dailyWordleGuesses.clear();
    }

    public boolean isTesting(ServerPlayer player) { return testSessions.containsKey(player.getUUID()); }
    public GameType testGameType(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t == null ? null : t.type; }

    public boolean adminStartTestPokemon(ServerPlayer player, String guess) {
        CobblemonSpeciesAccess.SpeciesInfo species = CobblemonSpeciesAccess.byGuess(guess);
        if (species == null || species.dex() < config.minPokedex || species.dex() > config.maxPokedex) return false;
        testSessions.put(player.getUUID(), new TestSession(species, buildHints(species)));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminStartTestWordle(ServerPlayer player, String word) {
        String normalized = WordleDictionary.normalize(word);
        if (!wordleDictionary.isAnswer(normalized)) return false;
        testSessions.put(player.getUUID(), new TestSession(normalized));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminStartTestCurrent(ServerPlayer player) {
        if (!active()) return false;
        if (activeType == GameType.POKEDEX) testSessions.put(player.getUUID(), new TestSession(pokemonAnswer, buildHints(pokemonAnswer)));
        else testSessions.put(player.getUUID(), new TestSession(wordleAnswer));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminStartRandomTestPokemon(ServerPlayer player) {
        List<CobblemonSpeciesAccess.SpeciesInfo> pool = CobblemonSpeciesAccess.implemented(config.minPokedex, config.maxPokedex);
        if (pool.isEmpty()) return false;
        CobblemonSpeciesAccess.SpeciesInfo species = pool.get(random.nextInt(pool.size()));
        testSessions.put(player.getUUID(), new TestSession(species, buildHints(species)));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminStartRandomTestWordle(ServerPlayer player) {
        List<String> pool = wordleDictionary.answers();
        if (pool.isEmpty()) return false;
        testSessions.put(player.getUUID(), new TestSession(pool.get(random.nextInt(pool.size()))));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminResetTest(ServerPlayer player) {
        TestSession t = testSessions.get(player.getUUID());
        if (t == null) return false;
        if (t.type == GameType.POKEDEX) testSessions.put(player.getUUID(), new TestSession(t.pokemon, buildHints(t.pokemon)));
        else testSessions.put(player.getUUID(), new TestSession(t.word));
        lastGuessAt.remove(player.getUUID());
        return true;
    }

    public boolean adminStopTest(ServerPlayer player) {
        lastGuessAt.remove(player.getUUID());
        return testSessions.remove(player.getUUID()) != null;
    }

    public String adminTestAnswer(ServerPlayer player) {
        TestSession t = testSessions.get(player.getUUID());
        if (t == null) return null;
        return t.type == GameType.POKEDEX ? t.pokemon.name() + " (#" + t.pokemon.dex() + ")" : t.word;
    }

    public int testHintNumber(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t == null ? 1 : t.hintNumber; }
    public String testCurrentHint(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t == null || t.type != GameType.POKEDEX ? "" : t.hints.get(t.hintNumber - 1); }
    public int testPokemonPoints(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t == null ? 0 : config.pointsByHint[t.hintNumber - 1]; }
    public List<String> testWordleGuesses(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t == null ? List.of() : List.copyOf(t.wordleGuesses); }
    public List<String> testWordlePatterns(ServerPlayer player) {
        TestSession t = testSessions.get(player.getUUID());
        if (t == null || t.type != GameType.WORDLE) return List.of();
        List<String> out = new ArrayList<>();
        for (String g : t.wordleGuesses) out.add(wordlePattern(g, t.word));
        return out;
    }
    public boolean testCompleted(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t != null && t.completed; }
    public boolean testSolved(ServerPlayer player) { TestSession t = testSessions.get(player.getUUID()); return t != null && t.solved; }
    public int testWordlePointsIfSolvedNow(ServerPlayer player) {
        TestSession t = testSessions.get(player.getUUID());
        if (t == null || t.type != GameType.WORDLE) return 0;
        int nextAttempt = Math.min(6, t.wordleGuesses.size() + 1);
        return config.wordlePointsByAttempt[nextAttempt - 1];
    }

    public int playerHintNumber(ServerPlayer player) { return Math.max(1, Math.min(10, stats.dailyPlayerHints.getOrDefault(player.getUUID().toString(), 1))); }
    public int playerPoints(ServerPlayer player) { return config.pointsByHint[playerHintNumber(player) - 1]; }
    public String playerCurrentHint(ServerPlayer player) { return activeType != GameType.POKEDEX || !active() ? "No Pokédex puzzle is active." : hints.get(playerHintNumber(player) - 1); }

    private int revealNextPrivateHint(ServerPlayer player) {
        int current = playerHintNumber(player);
        if (current >= 10) return 10;
        int next = current + 1;
        stats.dailyPlayerHints.put(player.getUUID().toString(), next);
        stats.save(statsPath);
        return next;
    }

    public GuessResult guess(ServerPlayer player, String text) {
        TestSession test = testSessions.get(player.getUUID());
        if (test != null) return guessTest(player, text, test);
        if (!active()) return GuessResult.NO_ROUND;
        if (completed.contains(player.getUUID())) return GuessResult.ALREADY_SOLVED;

        long now = System.currentTimeMillis();
        long last = lastGuessAt.getOrDefault(player.getUUID(), 0L);
        long cooldown = config.guessCooldownSeconds * 1000L;
        if (now - last < cooldown) return new GuessResult.Cooldown((cooldown - (now - last) + 999) / 1000);
        lastGuessAt.put(player.getUUID(), now);

        return activeType == GameType.POKEDEX ? guessPokemon(player, text) : guessWordle(player, text);
    }

    private GuessResult guessTest(ServerPlayer player, String text, TestSession test) {
        if (test.completed) return GuessResult.TEST_ALREADY_COMPLETED;
        return test.type == GameType.POKEDEX ? guessTestPokemon(text, test) : guessTestWordle(text, test);
    }

    private GuessResult guessTestPokemon(String text, TestSession test) {
        int current = test.hintNumber;
        if (!CobblemonSpeciesAccess.matches(test.pokemon, text)) {
            if (test.hintNumber < 10) test.hintNumber++;
            int now = test.hintNumber;
            return new GuessResult.TestWrong(now, test.hints.get(now - 1), config.pointsByHint[now - 1], now > current);
        }
        int points = config.pointsByHint[test.hintNumber - 1];
        test.completed = true; test.solved = true;
        return new GuessResult.TestCorrect(points, test.hintNumber, test.pokemon.name());
    }

    private GuessResult guessTestWordle(String text, TestSession test) {
        String guess = WordleDictionary.normalize(text);
        if (!guess.matches("[A-Z]{5}")) return new GuessResult.WordleInvalid("Guess must be exactly 5 letters.");
        if (test.wordleGuesses.size() >= 6) { test.completed = true; return new GuessResult.TestWordleFailed(test.word); }
        test.wordleGuesses.add(guess);
        int attempt = test.wordleGuesses.size();
        String pattern = wordlePattern(guess, test.word);
        if (guess.equals(test.word)) {
            int points = config.wordlePointsByAttempt[attempt - 1];
            test.completed = true; test.solved = true;
            return new GuessResult.TestWordleCorrect(guess, pattern, attempt, points);
        }
        if (attempt >= 6) { test.completed = true; return new GuessResult.TestWordleFailedAfterGuess(guess, pattern, test.word); }
        return new GuessResult.TestWordleWrong(guess, pattern, attempt, 6 - attempt, config.wordlePointsByAttempt[attempt]);
    }

    private GuessResult guessPokemon(ServerPlayer player, String text) {
        int hintNumber = playerHintNumber(player);
        if (!CobblemonSpeciesAccess.matches(pokemonAnswer, text)) {
            int newHint = revealNextPrivateHint(player);
            return new GuessResult.Wrong(newHint, hints.get(newHint - 1), config.pointsByHint[newHint - 1], newHint > hintNumber);
        }
        int points = playerPoints(player);
        PlayerStats ps = awardSolve(player, points);
        ps.pokedexWins += 1;
        ps.totalHintsAtSolve += hintNumber;
        stats.save(statsPath);
        ScoreboardSync.sync(player.getServer(), player.getGameProfile().getName(), ps);
        return new GuessResult.Correct(points, hintNumber, ps.points);
    }

    private GuessResult guessWordle(ServerPlayer player, String text) {
        String guess = WordleDictionary.normalize(text);
        if (!guess.matches("[A-Z]{5}")) return new GuessResult.WordleInvalid("Guess must be exactly 5 letters.");

        String uuid = player.getUUID().toString();
        List<String> guesses = stats.dailyWordleGuesses.computeIfAbsent(uuid, k -> new ArrayList<>());
        if (guesses.size() >= 6) {
            markCompleted(player, false);
            stats.save(statsPath);
            return new GuessResult.WordleFailed(wordleAnswer);
        }
        guesses.add(guess);
        int attempt = guesses.size();
        String pattern = wordlePattern(guess, wordleAnswer);

        if (guess.equals(wordleAnswer)) {
            int points = config.wordlePointsByAttempt[attempt - 1];
            PlayerStats ps = awardSolve(player, points);
            ps.wordleWins += 1;
            stats.save(statsPath);
            ScoreboardSync.sync(player.getServer(), player.getGameProfile().getName(), ps);
            return new GuessResult.WordleCorrect(guess, pattern, attempt, points, ps.points);
        }

        if (attempt >= 6) {
            markCompleted(player, false);
            stats.save(statsPath);
            return new GuessResult.WordleFailedAfterGuess(guess, pattern, wordleAnswer);
        }
        stats.save(statsPath);
        return new GuessResult.WordleWrong(guess, pattern, attempt, 6 - attempt, config.wordlePointsByAttempt[attempt]);
    }

    private PlayerStats awardSolve(ServerPlayer player, int points) {
        solved.add(player.getUUID());
        completed.add(player.getUUID());
        stats.dailySolvedPlayers.add(player.getUUID().toString());
        stats.dailyCompletedPlayers.add(player.getUUID().toString());
        PlayerStats ps = stats.get(player.getUUID(), player.getGameProfile().getName());
        ps.points += points;
        ps.correctGuesses += 1;
        return ps;
    }

    private void markCompleted(ServerPlayer player, boolean solvedIt) {
        completed.add(player.getUUID());
        stats.dailyCompletedPlayers.add(player.getUUID().toString());
        if (solvedIt) {
            solved.add(player.getUUID());
            stats.dailySolvedPlayers.add(player.getUUID().toString());
        }
    }

    /** Standard Wordle duplicate-letter handling. G=green, Y=yellow, X=gray. */
    public static String wordlePattern(String guess, String answer) {
        char[] g = guess.toCharArray();
        char[] a = answer.toCharArray();
        char[] out = {'X','X','X','X','X'};
        boolean[] used = new boolean[5];
        for (int i = 0; i < 5; i++) if (g[i] == a[i]) { out[i] = 'G'; used[i] = true; }
        for (int i = 0; i < 5; i++) {
            if (out[i] == 'G') continue;
            for (int j = 0; j < 5; j++) {
                if (!used[j] && g[i] == a[j]) { out[i] = 'Y'; used[j] = true; break; }
            }
        }
        return new String(out);
    }

    public List<String> wordleGuesses(ServerPlayer player) { return List.copyOf(stats.dailyWordleGuesses.getOrDefault(player.getUUID().toString(), List.of())); }
    public List<String> wordlePatterns(ServerPlayer player) {
        List<String> out = new ArrayList<>();
        for (String guess : wordleGuesses(player)) out.add(wordlePattern(guess, wordleAnswer));
        return out;
    }
    public int wordleAttemptsUsed(ServerPlayer player) { return wordleGuesses(player).size(); }
    public int wordlePointsIfSolvedNow(ServerPlayer player) {
        int nextAttempt = Math.min(6, wordleAttemptsUsed(player) + 1);
        return config.wordlePointsByAttempt[nextAttempt - 1];
    }

    public void revealAnswer(MinecraftServer server) {
        if (!active()) return;
        if (activeType == GameType.POKEDEX) {
            broadcast(server, Component.literal("Today's Pokémon is ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(pokemonAnswer.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(Component.literal("! (#" + pokemonAnswer.dex() + ")").withStyle(ChatFormatting.YELLOW)));
        } else {
            broadcast(server, Component.literal("Today's Wordle answer is ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(wordleAnswer).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        }
    }

    private void revealPreviousAnswer(MinecraftServer server) {
        if (activeType == GameType.POKEDEX && pokemonAnswer != null) {
            broadcast(server, Component.literal("Yesterday's Pokémon was ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(pokemonAnswer.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(Component.literal("! (#" + pokemonAnswer.dex() + ")").withStyle(ChatFormatting.YELLOW)));
        } else if (activeType == GameType.WORDLE && !wordleAnswer.isBlank()) {
            broadcast(server, Component.literal("Yesterday's Wordle answer was ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(wordleAnswer).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)));
        }
    }

    private void broadcastNewPuzzle(MinecraftServer server) {
        if (activeType == GameType.POKEDEX) {
            broadcast(server, Component.literal("★ NEW DAILY POKÉDEX ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            broadcast(server, Component.literal("A new mystery Pokémon is ready. Use /pokedexgame status, then /guess <pokemon>.").withStyle(ChatFormatting.YELLOW));
        } else {
            broadcast(server, Component.literal("★ NEW DAILY WORDLE ★").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            broadcast(server, Component.literal("A new 5-letter Pokémon-themed Wordle is ready. You have 6 attempts. Use /pokedexgame status, then /guess <word>.").withStyle(ChatFormatting.YELLOW));
        }
    }

    public boolean hasSolved(ServerPlayer player) { return solved.contains(player.getUUID()); }
    public boolean hasCompleted(ServerPlayer player) { return completed.contains(player.getUUID()); }

    public String nextPuzzleText() {
        ZonedDateTime now = ZonedDateTime.now(config.zoneId());
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(config.zoneId());
        Duration d = Duration.between(now, next);
        long hours = d.toHours();
        long minutes = d.minusHours(hours).toMinutes();
        GameType nextType = typeForDate(next.toLocalDate());
        return String.format("Next daily puzzle: %s — %s (%dh %dm from now, %s)", nextType == GameType.WORDLE ? "WORDLE" : "POKÉDEX", next.toLocalDate() + " 12:00 AM", hours, minutes, config.dailyTimezone);
    }

    public List<PlayerStats> leaderboard() {
        return stats.players.values().stream().sorted(Comparator.comparingInt((PlayerStats p) -> p.points).reversed()
                .thenComparing(Comparator.comparingInt((PlayerStats p) -> p.correctGuesses).reversed())).limit(10).toList();
    }

    public void syncAllScoreboards(MinecraftServer server) {
        ScoreboardSync.ensureObjectives(server);
        for (PlayerStats ps : stats.players.values()) if (ps.lastKnownName != null && !ps.lastKnownName.isBlank()) ScoreboardSync.sync(server, ps.lastKnownName, ps);
    }
    public void syncPlayerScoreboard(ServerPlayer player) {
        PlayerStats ps = stats.get(player.getUUID(), player.getGameProfile().getName());
        ScoreboardSync.sync(player.getServer(), player.getGameProfile().getName(), ps);
    }

    private List<String> buildHints(CobblemonSpeciesAccess.SpeciesInfo s) {
        int gen = generation(s.dex());
        String region = region(gen);
        String primary = nice(s.primaryType());
        String secondary = s.secondaryType() == null || s.secondaryType().equalsIgnoreCase("null") ? null : nice(s.secondaryType());
        int dexLow50 = ((s.dex() - 1) / 50) * 50 + 1;
        int dexHigh50 = dexLow50 + 49;
        int dexLow25 = ((s.dex() - 1) / 25) * 25 + 1;
        int dexHigh25 = dexLow25 + 24;
        List<String> out = new ArrayList<>(10);
        out.add("It was introduced in Generation " + roman(gen) + " and is associated with " + region + ".");
        out.add("Its National Pokédex number is between #" + dexLow50 + " and #" + dexHigh50 + ".");
        out.add(s.maleRatio() != null ? genderHint(s.maleRatio()) : "Its gender ratio is not a useful identifying clue.");
        out.add(!s.eggGroups().isEmpty() ? "One of its Egg Groups is " + nice(s.eggGroups().get(0)) + "." : "Its Egg Group is not available as a useful clue.");
        if (s.baseStatTotal() != null) { int low = (s.baseStatTotal() / 50) * 50; out.add("Its base stat total is between " + low + " and " + (low + 49) + "."); }
        else out.add("Its base stat total is not available as a useful clue.");
        out.add(s.weight() != null ? "Its weight category is " + weightBand(s.weight()) + "." : "Its weight is not available as a useful clue.");
        out.add(s.height() != null ? "Its height category is " + sizeBand(s.height()) + "." : "Its height is not available as a useful clue.");
        out.add("One of its types is " + primary + ".");
        out.add(secondary == null ? "It is a single-type " + primary + " Pokémon." : "Its full typing is " + primary + "/" + secondary + ".");
        String letters = s.name().replaceAll("[^A-Za-z]", "");
        out.add("Its name starts with '" + Character.toUpperCase(s.name().charAt(0)) + "', has " + letters.length() + " letters, and its Pokédex number is between #" + dexLow25 + " and #" + dexHigh25 + ".");
        return List.copyOf(out);
    }

    public List<String> adminPreviewHints() { return List.copyOf(hints); }
    public boolean adminRerollHints() { if (activeType != GameType.POKEDEX || !active()) return false; hints = buildHints(pokemonAnswer); return true; }
    public int wordleAnswerCount() { return wordleDictionary.answers().size(); }

    private static String roman(int gen) { return switch (gen) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII"; default -> "IX"; }; }
    private static int generation(int dex) { if (dex <= 151) return 1; if (dex <= 251) return 2; if (dex <= 386) return 3; if (dex <= 493) return 4; if (dex <= 649) return 5; if (dex <= 721) return 6; if (dex <= 809) return 7; if (dex <= 905) return 8; return 9; }
    private static String region(int gen) { return switch (gen) { case 1 -> "Kanto"; case 2 -> "Johto"; case 3 -> "Hoenn"; case 4 -> "Sinnoh"; case 5 -> "Unova"; case 6 -> "Kalos"; case 7 -> "Alola"; case 8 -> "Galar/Hisui"; default -> "Paldea"; }; }
    private static String sizeBand(double v) { if (v < 0.7) return "very small"; if (v < 1.3) return "small-to-medium"; if (v < 2.0) return "medium-to-large"; return "very tall/large"; }
    private static String weightBand(double v) { if (v < 100) return "very light"; if (v < 500) return "light-to-medium"; if (v < 1500) return "heavy"; return "very heavy"; }
    private static String genderHint(Double r) { if (r == null || r < 0) return "It is genderless."; if (r == 0) return "It is always female."; if (r >= 1) return "It is always male."; if (r > 0.7) return "It is more commonly male."; if (r < 0.3) return "It is more commonly female."; return "Its gender ratio is fairly balanced."; }
    private static String nice(String s) { if (s == null || s.isBlank()) return "Unknown"; s = s.replace('_',' '); return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT); }
    private static void broadcast(MinecraftServer server, Component msg) { server.getPlayerList().broadcastSystemMessage(msg, false); }

    public sealed interface GuessResult {
        GuessResult NO_ROUND = new Basic("no_round");
        GuessResult ALREADY_SOLVED = new Basic("already_solved");
        GuessResult TEST_ALREADY_COMPLETED = new Basic("test_already_completed");
        record Basic(String code) implements GuessResult {}
        record Wrong(int hintNumber, String hint, int pointsNow, boolean revealedNewHint) implements GuessResult {}
        record Correct(int points, int hintNumber, int totalPoints) implements GuessResult {}
        record Cooldown(long seconds) implements GuessResult {}
        record WordleInvalid(String message) implements GuessResult {}
        record WordleWrong(String guess, String pattern, int attempt, int attemptsLeft, int pointsNextAttempt) implements GuessResult {}
        record WordleCorrect(String guess, String pattern, int attempt, int points, int totalPoints) implements GuessResult {}
        record WordleFailed(String answer) implements GuessResult {}
        record WordleFailedAfterGuess(String guess, String pattern, String answer) implements GuessResult {}
        record TestWrong(int hintNumber, String hint, int pointsNow, boolean revealedNewHint) implements GuessResult {}
        record TestCorrect(int points, int hintNumber, String answer) implements GuessResult {}
        record TestWordleWrong(String guess, String pattern, int attempt, int attemptsLeft, int pointsNextAttempt) implements GuessResult {}
        record TestWordleCorrect(String guess, String pattern, int attempt, int points) implements GuessResult {}
        record TestWordleFailed(String answer) implements GuessResult {}
        record TestWordleFailedAfterGuess(String guess, String pattern, String answer) implements GuessResult {}
    }
}
