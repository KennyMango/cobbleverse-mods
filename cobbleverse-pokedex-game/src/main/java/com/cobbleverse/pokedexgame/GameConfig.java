package com.cobbleverse.pokedexgame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

public final class GameConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int minPokedex = 1;
    public int maxPokedex = 493;
    public int guessCooldownSeconds = 10;
    public String dailyTimezone = "America/Vancouver";

    // This date is a Wordle day; every neighboring calendar day alternates game type.
    public String alternatingWordleAnchorDate = "2026-08-29";

    public int[] pointsByHint = {10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    public int[] wordlePointsByAttempt = {10, 8, 6, 4, 2, 1};
    public int wordleNoRepeatAnswers = 60;

    // Wordle guess validation. Answers remain local/curated; this API only decides whether a guess is a real word.
    public boolean wordValidationApiEnabled = true;
    public String wordValidationApiBaseUrl = "https://api.datamuse.com/words?sp=";
    public int wordValidationTimeoutSeconds = 5;

    public boolean announceCorrectGuesses = false;
    public boolean createVanillaScoreboard = true;
    public String scoreboardObjective = "pokedex_points";
    public String scoreboardDisplayName = "§b★ DAILY GAME MASTERS ★";

    public static GameConfig load(Path path) {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                GameConfig loaded = GSON.fromJson(Files.readString(path), GameConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    loaded.save(path); // writes any new v0.5 fields into older configs
                    return loaded;
                }
            }
        } catch (Exception e) {
            PokedexGameMod.LOGGER.warn("Could not read config {}, using defaults", path, e);
        }

        GameConfig config = new GameConfig();
        config.save(path);
        return config;
    }

    public void save(Path path) {
        sanitize();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            PokedexGameMod.LOGGER.error("Could not save config {}", path, e);
        }
    }

    public ZoneId zoneId() {
        try { return ZoneId.of(dailyTimezone); }
        catch (Exception ignored) { return ZoneId.systemDefault(); }
    }

    public LocalDate wordleAnchorDate() {
        try { return LocalDate.parse(alternatingWordleAnchorDate); }
        catch (Exception ignored) { return LocalDate.of(2026, 8, 29); }
    }

    private void sanitize() {
        minPokedex = Math.max(1, minPokedex);
        maxPokedex = Math.max(minPokedex, maxPokedex);
        guessCooldownSeconds = Math.max(0, guessCooldownSeconds);
        wordleNoRepeatAnswers = Math.max(0, wordleNoRepeatAnswers);
        wordValidationTimeoutSeconds = Math.max(1, Math.min(15, wordValidationTimeoutSeconds));
        if (wordValidationApiBaseUrl == null || wordValidationApiBaseUrl.isBlank()
                || wordValidationApiBaseUrl.contains("api.dictionaryapi.dev"))
            wordValidationApiBaseUrl = "https://api.datamuse.com/words?sp=";
        if (dailyTimezone == null || dailyTimezone.isBlank()) dailyTimezone = "America/Vancouver";
        try { ZoneId.of(dailyTimezone); } catch (Exception e) { dailyTimezone = ZoneId.systemDefault().getId(); }
        try { LocalDate.parse(alternatingWordleAnchorDate); } catch (Exception e) { alternatingWordleAnchorDate = "2026-08-29"; }
        if (pointsByHint == null || pointsByHint.length != 10) pointsByHint = new int[]{10,9,8,7,6,5,4,3,2,1};
        if (wordlePointsByAttempt == null || wordlePointsByAttempt.length != 6) wordlePointsByAttempt = new int[]{10,8,6,4,2,1};
        pointsByHint = Arrays.stream(pointsByHint).map(v -> Math.max(0, v)).toArray();
        wordlePointsByAttempt = Arrays.stream(wordlePointsByAttempt).map(v -> Math.max(0, v)).toArray();
        if (scoreboardObjective == null || scoreboardObjective.isBlank()) scoreboardObjective = "pokedex_points";
        if (scoreboardDisplayName == null || scoreboardDisplayName.isBlank()) scoreboardDisplayName = "§b★ DAILY GAME MASTERS ★";
    }
}
