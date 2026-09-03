package com.cobbleverse.pokedexgame;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PokedexGameMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("cobbleverse-pokedex-game");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("cobbleverse-pokedex-game.json");
    private static GameConfig config;
    private static GameManager game;
    private static WordValidationService wordValidation;
    private static final Set<UUID> pendingWordValidation = ConcurrentHashMap.newKeySet();

    @Override
    public void onInitialize() {
        config = GameConfig.load(CONFIG_PATH);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("guess")
                    .then(Commands.argument("answer", StringArgumentType.greedyString())
                            .executes(ctx -> doGuess(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "answer")))));

            dispatcher.register(Commands.literal("pokedexgame")
                    .then(Commands.literal("leaderboard").executes(ctx -> leaderboard(ctx.getSource())))
                    .then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getPlayerOrException())))
                    .then(Commands.literal("next").executes(ctx -> next(ctx.getSource())))
                    .then(Commands.literal("admin").requires(s -> s.hasPermission(2))
                            .then(Commands.literal("new").executes(ctx -> adminNew(ctx.getSource().getServer(), ctx.getSource())))
                            .then(Commands.literal("resetday").executes(ctx -> adminResetDay(ctx.getSource().getServer(), ctx.getSource())))
                            .then(Commands.literal("force")
                                    .then(Commands.argument("pokemon", StringArgumentType.greedyString())
                                            .executes(ctx -> adminForcePokemon(ctx.getSource().getServer(), ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))))
                            .then(Commands.literal("forceword")
                                    .then(Commands.argument("word", StringArgumentType.word())
                                            .executes(ctx -> adminForceWordle(ctx.getSource().getServer(), ctx.getSource(), StringArgumentType.getString(ctx, "word")))))
                            .then(Commands.literal("test")
                                    .then(Commands.literal("pokemon")
                                            .then(Commands.argument("pokemon", StringArgumentType.greedyString())
                                                    .executes(ctx -> adminTestPokemon(ctx.getSource().getPlayerOrException(), ctx.getSource(), StringArgumentType.getString(ctx, "pokemon")))))
                                    .then(Commands.literal("word")
                                            .then(Commands.argument("word", StringArgumentType.word())
                                                    .executes(ctx -> adminTestWord(ctx.getSource().getPlayerOrException(), ctx.getSource(), StringArgumentType.getString(ctx, "word")))))
                                    .then(Commands.literal("random")
                                            .then(Commands.literal("pokemon").executes(ctx -> adminTestRandomPokemon(ctx.getSource().getPlayerOrException(), ctx.getSource())))
                                            .then(Commands.literal("word").executes(ctx -> adminTestRandomWord(ctx.getSource().getPlayerOrException(), ctx.getSource()))))
                                    .then(Commands.literal("current").executes(ctx -> adminTestCurrent(ctx.getSource().getPlayerOrException(), ctx.getSource())))
                                    .then(Commands.literal("status").executes(ctx -> adminTestStatus(ctx.getSource().getPlayerOrException())))
                                    .then(Commands.literal("answer").executes(ctx -> adminTestAnswer(ctx.getSource().getPlayerOrException(), ctx.getSource())))
                                    .then(Commands.literal("reset").executes(ctx -> adminTestReset(ctx.getSource().getPlayerOrException(), ctx.getSource())))
                                    .then(Commands.literal("stop").executes(ctx -> adminTestStop(ctx.getSource().getPlayerOrException(), ctx.getSource()))))
                            .then(Commands.literal("answer").executes(ctx -> { if (game != null) game.revealAnswer(ctx.getSource().getServer()); return 1; }))
                            .then(Commands.literal("preview").executes(ctx -> adminPreview(ctx.getSource())))
                            .then(Commands.literal("rerollhints").executes(ctx -> adminRerollHints(ctx.getSource())))
                            .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            config = GameConfig.load(CONFIG_PATH);
            Path statsPath = server.getServerDirectory().resolve("config").resolve("cobbleverse-pokedex-game-stats.json");
            game = new GameManager(statsPath, config);
            Path wordDataPath = server.getServerDirectory().resolve("config").resolve("cobbleverse-pokedex-game");
            wordValidation = new WordValidationService(wordDataPath, config);
            game.initializeDaily(server);
            LOGGER.info("Cobbleverse Daily Game v0.5.4 ready. Today: {}, Wordle answers: {}, timezone: {}", game.gameType(), game.wordleAnswerCount(), config.dailyTimezone);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> { if (game != null) game.tick(server); });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            if (game != null) {
                game.syncPlayerScoreboard(handler.getPlayer());
                if (!game.hasCompleted(handler.getPlayer())) sendJoinAnnouncement(handler.getPlayer());
            }
        }));
    }

    private static void sendJoinAnnouncement(ServerPlayer player) {
        if (game == null) return;
        player.sendSystemMessage(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_AQUA));
        if (game.isWordleDay()) {
            player.sendSystemMessage(Component.literal("★ DAILY POKÉMON WORDLE ★").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            player.sendSystemMessage(Component.literal("Guess today's 5-letter Pokémon-themed word!").withStyle(ChatFormatting.YELLOW));
            player.sendSystemMessage(Component.literal("• Every answer is Pokémon-related — Pokémon, moves, items, abilities, regions, and more.").withStyle(ChatFormatting.AQUA));
            player.sendSystemMessage(Component.literal("• You have 6 attempts.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("• Green = correct letter/place, Yellow = correct letter/wrong place, Gray = not in the answer.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("• Points by attempt: 10 / 8 / 6 / 4 / 2 / 1.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("Start: /pokedexgame status  |  Guess: /guess <word>").withStyle(ChatFormatting.GREEN));
        } else {
            player.sendSystemMessage(Component.literal("★ DAILY POKÉDEX CHALLENGE ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            player.sendSystemMessage(Component.literal("Guess today's mystery Pokémon and earn Daily Game Points!").withStyle(ChatFormatting.YELLOW));
            player.sendSystemMessage(Component.literal("• Everyone gets their own private hints.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("• You begin on Hint #1 for 10 points.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("• Every wrong guess reveals your next private hint and lowers the reward by 1 point.").withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.literal("Start: /pokedexgame status  |  Guess: /guess <pokemon>").withStyle(ChatFormatting.GREEN));
        }
        player.sendSystemMessage(Component.literal("Next puzzle: /pokedexgame next").withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Component.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━").withStyle(ChatFormatting.DARK_AQUA));
    }

    private static int doGuess(ServerPlayer player, String guess) {
        if (game == null) return 0;

        // Pokémon guesses stay fully local. Wordle guesses are checked against the local
        // override/answer dictionaries first, then asynchronously against Datamuse.
        if (game.isWordleContext(player)) {
            String normalized = WordleDictionary.normalize(guess);
            if (!normalized.matches("[A-Z]{5}")) {
                return handleGuessResult(player, new GameManager.GuessResult.WordleInvalid("Guess must be exactly 5 letters."));
            }

            if (!game.isKnownLocalWordleGuess(normalized)) {
                if (wordValidation == null || !config.wordValidationApiEnabled) {
                    return handleGuessResult(player, new GameManager.GuessResult.WordleInvalid(
                            "Word validation is unavailable. Ask an admin to add this word to wordle-valid-guesses.txt."));
                }

                UUID uuid = player.getUUID();
                if (!pendingWordValidation.add(uuid)) {
                    player.sendSystemMessage(Component.literal("Your previous word is still being checked. Please wait a moment.").withStyle(ChatFormatting.GRAY));
                    return 1;
                }

                player.sendSystemMessage(Component.literal("Checking word…").withStyle(ChatFormatting.GRAY));
                MinecraftServer server = player.getServer();
                wordValidation.validate(normalized).whenComplete((validation, error) -> server.execute(() -> {
                    pendingWordValidation.remove(uuid);
                    ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                    if (online == null || game == null) return;
                    if (!game.isWordleContext(online)) {
                        online.sendSystemMessage(Component.literal("The Wordle puzzle changed while your word was being checked. Your attempt was not used.").withStyle(ChatFormatting.GRAY));
                        return;
                    }

                    if (error != null || validation == WordValidationService.Result.UNAVAILABLE) {
                        // Local dictionaries were checked before the API request. If we reach this
                        // branch, the word is unknown locally and the remote validator could not
                        // give us a trustworthy answer. Never consume an attempt in that case.
                        online.sendSystemMessage(Component.literal(
                                "⚠ Word validation service unavailable and this word is not in the local dictionary. Your attempt was not used.")
                                .withStyle(ChatFormatting.RED));
                    } else if (validation == WordValidationService.Result.INVALID) {
                        online.sendSystemMessage(Component.literal(
                                "That does not appear to be a valid English word. Your attempt was not used.")
                                .withStyle(ChatFormatting.RED));
                    } else {
                        handleGuessResult(online, game.guess(online, normalized));
                    }
                }));
                return 1;
            }
        }

        return handleGuessResult(player, game.guess(player, guess));
    }

    private static int handleGuessResult(ServerPlayer player, GameManager.GuessResult result) {
        if (result == GameManager.GuessResult.NO_ROUND) {
            player.sendSystemMessage(Component.literal("Today's daily puzzle is not available yet.").withStyle(ChatFormatting.GRAY));
        } else if (result == GameManager.GuessResult.ALREADY_SOLVED) {
            player.sendSystemMessage(Component.literal("You already completed today's puzzle.").withStyle(ChatFormatting.YELLOW));
        } else if (result == GameManager.GuessResult.TEST_ALREADY_COMPLETED) {
            player.sendSystemMessage(Component.literal("This admin test is already complete. Use /pokedexgame admin test reset or stop.").withStyle(ChatFormatting.YELLOW));
        } else if (result instanceof GameManager.GuessResult.TestWrong w) {
            player.sendSystemMessage(Component.literal("[TEST] ✗ Incorrect.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (w.revealedNewHint()) {
                player.sendSystemMessage(Component.literal("[TEST] Hint #" + w.hintNumber() + " [" + w.pointsNow() + " simulated points]: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(w.hint()).withStyle(ChatFormatting.WHITE)));
            } else {
                player.sendSystemMessage(Component.literal("[TEST] Final hint. A simulated solve is worth " + w.pointsNow() + " point.").withStyle(ChatFormatting.GOLD));
            }
        } else if (result instanceof GameManager.GuessResult.TestCorrect c) {
            player.sendSystemMessage(Component.literal("[TEST] ✓ Correct: " + c.answer() + " — simulated " + c.points() + " points on Hint #" + c.hintNumber() + ". No stats or leaderboard were changed.").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else if (result instanceof GameManager.GuessResult.TestWordleWrong w) {
            player.sendSystemMessage(wordleLine(w.guess(), w.pattern()));
            player.sendSystemMessage(Component.literal("[TEST] Attempt " + w.attempt() + "/6 — " + w.attemptsLeft() + " left. Next simulated solve value: " + w.pointsNextAttempt() + ".").withStyle(ChatFormatting.YELLOW));
        } else if (result instanceof GameManager.GuessResult.TestWordleCorrect c) {
            player.sendSystemMessage(wordleLine(c.guess(), c.pattern()));
            player.sendSystemMessage(Component.literal("[TEST] ✓ WORDLE SOLVED in " + c.attempt() + "/6 — simulated " + c.points() + " points. No stats or leaderboard were changed.").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        } else if (result instanceof GameManager.GuessResult.TestWordleFailedAfterGuess f) {
            player.sendSystemMessage(wordleLine(f.guess(), f.pattern()));
            player.sendSystemMessage(Component.literal("[TEST] No attempts left. Test answer: " + f.answer() + ". No stats were changed.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else if (result instanceof GameManager.GuessResult.TestWordleFailed f) {
            player.sendSystemMessage(Component.literal("[TEST] No attempts left. Test answer: " + f.answer() + ". No stats were changed.").withStyle(ChatFormatting.RED));
        } else if (result instanceof GameManager.GuessResult.Wrong w) {
            player.sendSystemMessage(Component.literal("✗ Incorrect.").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (w.revealedNewHint()) {
                player.sendSystemMessage(Component.literal("Hint #" + w.hintNumber() + " [" + w.pointsNow() + " points]: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(w.hint()).withStyle(ChatFormatting.WHITE)));
            } else {
                player.sendSystemMessage(Component.literal("You are already on the final hint. A correct answer is worth " + w.pointsNow() + " point.").withStyle(ChatFormatting.GOLD));
            }
        } else if (result instanceof GameManager.GuessResult.Cooldown c) {
            player.sendSystemMessage(Component.literal("Wait " + c.seconds() + "s before guessing again.").withStyle(ChatFormatting.GRAY));
        } else if (result instanceof GameManager.GuessResult.Correct c) {
            player.sendSystemMessage(Component.literal("✓ Correct! +" + c.points() + " points (Hint #" + c.hintNumber() + "). Total: " + c.totalPoints()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            announceSolve(player, "Pokédex challenge");
        } else if (result instanceof GameManager.GuessResult.WordleInvalid invalid) {
            player.sendSystemMessage(Component.literal(invalid.message()).withStyle(ChatFormatting.RED));
        } else if (result instanceof GameManager.GuessResult.WordleWrong w) {
            player.sendSystemMessage(wordleLine(w.guess(), w.pattern()));
            player.sendSystemMessage(Component.literal("Attempt " + w.attempt() + "/6 — " + w.attemptsLeft() + " left. Solve on the next attempt for " + w.pointsNextAttempt() + " points.").withStyle(ChatFormatting.YELLOW));
        } else if (result instanceof GameManager.GuessResult.WordleCorrect c) {
            player.sendSystemMessage(wordleLine(c.guess(), c.pattern()));
            player.sendSystemMessage(Component.literal("✓ WORDLE SOLVED in " + c.attempt() + "/6! +" + c.points() + " points. Total: " + c.totalPoints()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            announceSolve(player, "Wordle");
        } else if (result instanceof GameManager.GuessResult.WordleFailedAfterGuess f) {
            player.sendSystemMessage(wordleLine(f.guess(), f.pattern()));
            player.sendSystemMessage(Component.literal("No attempts left. Today's answer was " + f.answer() + ".").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        } else if (result instanceof GameManager.GuessResult.WordleFailed f) {
            player.sendSystemMessage(Component.literal("No attempts left. Today's answer was " + f.answer() + ".").withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static Component wordleLine(String guess, String pattern) {
        Component line = Component.literal("");
        for (int i = 0; i < 5; i++) {
            ChatFormatting color = switch (pattern.charAt(i)) {
                case 'G' -> ChatFormatting.GREEN;
                case 'Y' -> ChatFormatting.YELLOW;
                default -> ChatFormatting.DARK_GRAY;
            };
            line = line.copy().append(Component.literal("[" + guess.charAt(i) + "] ").withStyle(color, ChatFormatting.BOLD));
        }
        return line;
    }

    private static void announceSolve(ServerPlayer player, String puzzleName) {
        if (config.announceCorrectGuesses) {
            player.getServer().getPlayerList().broadcastSystemMessage(Component.literal(player.getGameProfile().getName() + " solved today's " + puzzleName + "!").withStyle(ChatFormatting.AQUA), false);
        }
    }

    private static int adminTestPokemon(ServerPlayer player, net.minecraft.commands.CommandSourceStack source, String pokemon) {
        if (game != null && game.adminStartTestPokemon(player, pokemon)) {
            source.sendSuccess(() -> Component.literal("Admin test started: Pokédex puzzle for " + pokemon + ". Use /pokedexgame admin test status and /guess <pokemon>. Test mode does not affect daily progress or scores."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Pokémon not found or outside the configured Pokédex range."));
        return 0;
    }

    private static int adminTestWord(ServerPlayer player, net.minecraft.commands.CommandSourceStack source, String word) {
        if (game != null && game.adminStartTestWordle(player, word)) {
            source.sendSuccess(() -> Component.literal("Admin test started: Wordle puzzle for " + word.toUpperCase() + ". Use /pokedexgame admin test status and /guess <word>. Test mode does not affect daily progress or scores."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Word must be a 5-letter answer in wordle-answers.txt."));
        return 0;
    }

    private static int adminTestRandomPokemon(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminStartRandomTestPokemon(player)) { source.sendSuccess(() -> Component.literal("Random Pokédex admin test started. Use /pokedexgame admin test status."), false); return 1; }
        source.sendFailure(Component.literal("Could not select a Pokémon for testing.")); return 0;
    }

    private static int adminTestRandomWord(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminStartRandomTestWordle(player)) { source.sendSuccess(() -> Component.literal("Random Wordle admin test started. Use /pokedexgame admin test status."), false); return 1; }
        source.sendFailure(Component.literal("Could not select a Wordle answer for testing.")); return 0;
    }

    private static int adminTestCurrent(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminStartTestCurrent(player)) { source.sendSuccess(() -> Component.literal("Admin test copied from the current daily puzzle. Daily progress and scores are isolated."), false); return 1; }
        source.sendFailure(Component.literal("No active daily puzzle to copy.")); return 0;
    }

    private static int adminTestReset(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminResetTest(player)) { source.sendSuccess(() -> Component.literal("Admin test reset to attempt/hint 1. Daily progress was not changed."), false); return 1; }
        source.sendFailure(Component.literal("You do not have an active admin test.")); return 0;
    }

    private static int adminTestStop(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminStopTest(player)) { source.sendSuccess(() -> Component.literal("Admin test stopped. /guess now targets the normal daily puzzle again."), false); return 1; }
        source.sendFailure(Component.literal("You do not have an active admin test.")); return 0;
    }

    private static int adminTestAnswer(ServerPlayer player, net.minecraft.commands.CommandSourceStack source) {
        if (game == null) return 0;
        String answer = game.adminTestAnswer(player);
        if (answer == null) { source.sendFailure(Component.literal("You do not have an active admin test.")); return 0; }
        source.sendSuccess(() -> Component.literal("[TEST] Answer: " + answer).withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int adminTestStatus(ServerPlayer player) {
        if (game == null || !game.isTesting(player)) { player.sendSystemMessage(Component.literal("You do not have an active admin test.").withStyle(ChatFormatting.GRAY)); return 0; }
        player.sendSystemMessage(Component.literal("★ ADMIN TEST MODE ★").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        if (game.testGameType(player) == GameManager.GameType.POKEDEX) {
            int hint = game.testHintNumber(player);
            player.sendSystemMessage(Component.literal("Pokédex test — Hint #" + hint + " — simulated " + game.testPokemonPoints(player) + " points").withStyle(ChatFormatting.AQUA));
            player.sendSystemMessage(Component.literal(game.testCurrentHint(player)).withStyle(ChatFormatting.WHITE));
        } else {
            List<String> guesses = game.testWordleGuesses(player);
            List<String> patterns = game.testWordlePatterns(player);
            for (int i = 0; i < guesses.size(); i++) player.sendSystemMessage(wordleLine(guesses.get(i), patterns.get(i)));
            player.sendSystemMessage(Component.literal("Wordle test — Attempts: " + guesses.size() + "/6 | Next simulated solve value: " + game.testWordlePointsIfSolvedNow(player)).withStyle(ChatFormatting.GREEN));
        }
        if (game.testCompleted(player)) player.sendSystemMessage(Component.literal(game.testSolved(player) ? "Test solved. Use /pokedexgame admin test reset to run it again." : "Test complete. Use /pokedexgame admin test reset to run it again.").withStyle(ChatFormatting.YELLOW));
        else player.sendSystemMessage(Component.literal("Use /guess ... to test. No daily stats, points, streaks, or scoreboards are modified.").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int adminNew(MinecraftServer server, net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminResetRandom(server)) {
            source.sendSuccess(() -> Component.literal("Admin rerolled today's " + (game.isWordleDay() ? "Wordle" : "Pokédex") + " puzzle. Existing lifetime points were not changed."), false);
            return 1;
        }
        source.sendFailure(Component.literal("Could not select a new answer for today's puzzle.")); return 0;
    }

    private static int adminResetDay(MinecraftServer server, net.minecraft.commands.CommandSourceStack source) {
        if (game == null) { source.sendFailure(Component.literal("Daily game is not ready.")); return 0; }
        int reverted = game.adminResetDay(server);
        if (reverted < 0) { source.sendFailure(Component.literal("Scores were reverted, but a new daily puzzle could not be selected.")); return 0; }
        final int count = reverted;
        source.sendSuccess(() -> Component.literal("Reset today's daily game. Reverted score/stat credit for " + count + " solved player(s), cleared today's progress, and selected a new answer."), false);
        return 1;
    }

    private static int adminForcePokemon(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String pokemon) {
        if (game == null || game.isWordleDay()) { source.sendFailure(Component.literal("/force is only available on Pokédex days.")); return 0; }
        if (game.adminForcePokemon(server, pokemon)) { source.sendSuccess(() -> Component.literal("Admin forced today's Pokédex answer."), false); return 1; }
        source.sendFailure(Component.literal("Pokémon not found or outside the configured Pokédex range.")); return 0;
    }

    private static int adminForceWordle(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String word) {
        if (game == null || !game.isWordleDay()) { source.sendFailure(Component.literal("/forceword is only available on Wordle days.")); return 0; }
        if (game.adminForceWordle(server, word)) { source.sendSuccess(() -> Component.literal("Admin forced today's Wordle answer."), false); return 1; }
        source.sendFailure(Component.literal("Word must exist in wordle-answers.txt and be exactly 5 letters.")); return 0;
    }

    private static int adminPreview(net.minecraft.commands.CommandSourceStack source) {
        if (game == null || !game.active()) { source.sendFailure(Component.literal("No active daily puzzle.")); return 0; }
        if (game.isWordleDay()) { source.sendFailure(Component.literal("Hint preview is only available on Pokédex days. Use /pokedexgame admin answer to reveal the Wordle answer.")); return 0; }
        source.sendSuccess(() -> Component.literal("★ DAILY POKÉDEX HINT PREVIEW ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        List<String> hints = game.adminPreviewHints();
        for (int i = 0; i < hints.size(); i++) {
            final String line = "#" + (i + 1) + " [" + (10 - i) + " pts] " + hints.get(i);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int adminRerollHints(net.minecraft.commands.CommandSourceStack source) {
        if (game != null && game.adminRerollHints()) { source.sendSuccess(() -> Component.literal("Regenerated today's hint set. Answer and player progress were not changed."), false); return 1; }
        source.sendFailure(Component.literal("Hint reroll is only available during an active Pokédex puzzle.")); return 0;
    }

    private static int leaderboard(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("★ DAILY GAME MASTERS ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        if (game == null) return 0;
        List<PlayerStats> rows = game.leaderboard();
        for (int i = 0; i < rows.size(); i++) {
            PlayerStats p = rows.get(i);
            final String line = (i + 1) + ". " + p.lastKnownName + " — " + p.points + " pts | " + p.correctGuesses + " wins (Dex " + p.pokedexWins + " / Wordle " + p.wordleWins + ")";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int status(ServerPlayer player) {
        if (game != null && game.isTesting(player)) return adminTestStatus(player);
        if (game == null || !game.active()) {
            player.sendSystemMessage(Component.literal("Today's daily puzzle is not active.").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        if (game.isWordleDay()) {
            player.sendSystemMessage(Component.literal("★ DAILY POKÉMON WORDLE ★").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
            List<String> guesses = game.wordleGuesses(player);
            List<String> patterns = game.wordlePatterns(player);
            for (int i = 0; i < guesses.size(); i++) player.sendSystemMessage(wordleLine(guesses.get(i), patterns.get(i)));
            if (game.hasCompleted(player)) {
                player.sendSystemMessage(Component.literal(game.hasSolved(player) ? "You already solved today's Wordle." : "You already used all 6 attempts today.").withStyle(game.hasSolved(player) ? ChatFormatting.GREEN : ChatFormatting.RED));
            } else {
                player.sendSystemMessage(Component.literal("Attempts: " + guesses.size() + "/6 | Next solve value: " + game.wordlePointsIfSolvedNow(player) + " points").withStyle(ChatFormatting.YELLOW));
                player.sendSystemMessage(Component.literal("Guess with /guess <5-letter-word>.").withStyle(ChatFormatting.WHITE));
            }
        } else {
            if (game.hasSolved(player)) {
                player.sendSystemMessage(Component.literal("★ DAILY POKÉDEX ★ You already solved today's puzzle.").withStyle(ChatFormatting.GREEN));
            } else {
                int hint = game.playerHintNumber(player);
                int points = game.playerPoints(player);
                player.sendSystemMessage(Component.literal("★ DAILY POKÉDEX — Hint #" + hint + " — " + points + " points ★").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                player.sendSystemMessage(Component.literal(game.playerCurrentHint(player)).withStyle(ChatFormatting.WHITE));
                player.sendSystemMessage(Component.literal("Guess with /guess <pokemon>. A wrong guess reveals your next private hint.").withStyle(ChatFormatting.YELLOW));
            }
        }
        player.sendSystemMessage(Component.literal(game.nextPuzzleText()).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int next(net.minecraft.commands.CommandSourceStack source) {
        if (game == null) return 0;
        source.sendSuccess(() -> Component.literal(game.nextPuzzleText()).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int reload(net.minecraft.commands.CommandSourceStack source) {
        config = GameConfig.load(CONFIG_PATH);
        if (game != null) game.reload(config);
        if (wordValidation != null) wordValidation.updateConfig(config);
        source.sendSuccess(() -> Component.literal("Daily game config, Wordle dictionaries, and word-validation settings reloaded."), false);
        return 1;
    }
}
