package com.cobbleverse.pokedexgame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class PersistentStats {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public Map<String, PlayerStats> players = new HashMap<>();

    public String dailyDate = "";
    public String dailyGameType = "POKEDEX";
    public String dailyAnswer = "";
    public Set<String> dailySolvedPlayers = new HashSet<>();
    public Set<String> dailyCompletedPlayers = new HashSet<>();
    public Map<String, Integer> dailyPlayerHints = new HashMap<>();
    public Map<String, List<String>> dailyWordleGuesses = new HashMap<>();
    public List<String> recentDailyAnswers = new ArrayList<>();
    public List<String> recentWordleAnswers = new ArrayList<>();

    public static PersistentStats load(Path path) {
        try {
            if (Files.exists(path)) {
                PersistentStats loaded = GSON.fromJson(Files.readString(path), PersistentStats.class);
                if (loaded != null) {
                    if (loaded.players == null) loaded.players = new HashMap<>();
                    if (loaded.dailySolvedPlayers == null) loaded.dailySolvedPlayers = new HashSet<>();
                    if (loaded.dailyCompletedPlayers == null) loaded.dailyCompletedPlayers = new HashSet<>();
                    if (loaded.dailyPlayerHints == null) loaded.dailyPlayerHints = new HashMap<>();
                    if (loaded.dailyWordleGuesses == null) loaded.dailyWordleGuesses = new HashMap<>();
                    if (loaded.dailyDate == null) loaded.dailyDate = "";
                    if (loaded.dailyGameType == null || loaded.dailyGameType.isBlank()) loaded.dailyGameType = "POKEDEX";
                    if (loaded.dailyAnswer == null) loaded.dailyAnswer = "";
                    if (loaded.recentDailyAnswers == null) loaded.recentDailyAnswers = new ArrayList<>();
                    if (loaded.recentWordleAnswers == null) loaded.recentWordleAnswers = new ArrayList<>();
                    loaded.dailyCompletedPlayers.addAll(loaded.dailySolvedPlayers);

                    // v0.4.x migration: all historical correctGuesses were Pokédex wins.
                    for (PlayerStats ps : loaded.players.values()) {
                        if (ps.pokedexWins == 0 && ps.wordleWins == 0 && ps.correctGuesses > 0) {
                            ps.pokedexWins = ps.correctGuesses;
                        }
                    }
                    return loaded;
                }
            }
        } catch (Exception e) {
            PokedexGameMod.LOGGER.warn("Could not read stats {}, starting fresh", path, e);
        }
        return new PersistentStats();
    }

    public PlayerStats get(UUID uuid, String name) {
        PlayerStats stats = players.computeIfAbsent(uuid.toString(), key -> new PlayerStats());
        stats.lastKnownName = name;
        return stats;
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            PokedexGameMod.LOGGER.error("Could not save stats {}", path, e);
        }
    }
}
