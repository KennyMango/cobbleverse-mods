package com.cobbleverse.pokedexgame;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** Mirrors persistent Daily Game statistics into vanilla scoreboard objectives. */
public final class ScoreboardSync {
    // Kept unchanged so existing CobbleBoard setups continue working after the v0.5 update.
    public static final String POINTS = "pokedex_points";
    public static final String CORRECT = "pokedex_correct";
    public static final String AVG_HINTS_X10 = "pokedex_avg10";
    public static final String POKEDEX_WINS = "pokedex_wins";
    public static final String WORDLE_WINS = "wordle_wins";

    private ScoreboardSync() {}

    public static void ensureObjectives(MinecraftServer server) {
        Scoreboard board = server.getScoreboard();
        ensureObjective(board, POINTS, "Daily Game Points");
        ensureObjective(board, CORRECT, "Daily Puzzle Wins");
        ensureObjective(board, AVG_HINTS_X10, "Avg Pokédex Hints x10");
        ensureObjective(board, POKEDEX_WINS, "Pokédex Wins");
        ensureObjective(board, WORDLE_WINS, "Wordle Wins");
    }

    public static void sync(MinecraftServer server, String playerName, PlayerStats stats) {
        if (server == null || playerName == null || playerName.isBlank() || stats == null) return;
        ensureObjectives(server);
        Scoreboard board = server.getScoreboard();
        ScoreHolder holder = ScoreHolder.forNameOnly(playerName);
        set(board, holder, POINTS, stats.points);
        set(board, holder, CORRECT, stats.correctGuesses);
        set(board, holder, AVG_HINTS_X10, (int) Math.round(stats.averageHints() * 10.0));
        set(board, holder, POKEDEX_WINS, stats.pokedexWins);
        set(board, holder, WORDLE_WINS, stats.wordleWins);
    }

    private static Objective ensureObjective(Scoreboard board, String name, String displayName) {
        Objective objective = board.getObjective(name);
        if (objective == null) {
            objective = board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(displayName), ObjectiveCriteria.RenderType.INTEGER, false, null);
        }
        return objective;
    }

    private static void set(Scoreboard board, ScoreHolder holder, String objectiveName, int value) {
        Objective objective = board.getObjective(objectiveName);
        if (objective != null) board.getOrCreatePlayerScore(holder, objective).set(value);
    }
}
