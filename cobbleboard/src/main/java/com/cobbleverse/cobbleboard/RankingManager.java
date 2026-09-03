package com.cobbleverse.cobbleboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class RankingManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("cobbleboard-rankings.json");

    private RankingData data = new RankingData();
    private int tickCounter = 0;
    private boolean dirty = false;

    public void load() {
        if (!Files.exists(DATA_FILE)) return;
        try (Reader reader = Files.newBufferedReader(DATA_FILE)) {
            RankingData loaded = GSON.fromJson(reader, RankingData.class);
            if (loaded != null) data = loaded;
        } catch (Exception e) {
            CobbleBoardMod.LOGGER.error("Could not load {}", DATA_FILE, e);
        }
    }

    public void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(DATA_FILE)) {
                GSON.toJson(data, writer);
            }
            dirty = false;
        } catch (IOException e) {
            CobbleBoardMod.LOGGER.error("Could not save {}", DATA_FILE, e);
        }
    }

    public void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < 20) return; // scan once per second
        tickCounter = 0;

        // Only track objectives that have already been registered through /cobbleboard track.
        for (String objectiveName : new ArrayList<>(data.objectives.keySet())) {
            syncObjective(server, objectiveName);
        }
        save();
    }

    public boolean trackObjective(MinecraftServer server, String objectiveName) {
        ScoreboardObjective objective = server.getScoreboard().getNullableObjective(objectiveName);
        if (objective == null) return false;
        data.objectives.computeIfAbsent(objectiveName, ignored -> new HashMap<>());
        syncObjective(server, objectiveName);
        dirty = true;
        save();
        return true;
    }

    private void syncObjective(MinecraftServer server, String objectiveName) {
        ScoreboardObjective objective = server.getScoreboard().getNullableObjective(objectiveName);
        if (objective == null) return;

        Map<String, RankingData.PlayerStanding> standings =
                data.objectives.computeIfAbsent(objectiveName, ignored -> new HashMap<>());

        for (ScoreboardEntry entry : server.getScoreboard().getScoreboardEntries(objective)) {
            if (entry.hidden()) continue;

            String player = entry.owner();
            int score = entry.value();
            RankingData.PlayerStanding existing = standings.get(player);

            if (existing == null) {
                standings.put(player, new RankingData.PlayerStanding(score, data.nextSequence++));
                dirty = true;
            } else if (existing.score != score) {
                // A new score is a new milestone. Whoever reaches that exact score earlier
                // receives the lower achievedOrder and wins ties at that score.
                existing.score = score;
                existing.achievedOrder = data.nextSequence++;
                dirty = true;
            }
        }
    }

    public List<StandingView> getRanked(String objectiveName) {
        Map<String, RankingData.PlayerStanding> standings = data.objectives.get(objectiveName);
        if (standings == null) return List.of();

        List<StandingView> result = new ArrayList<>();
        standings.forEach((name, standing) -> result.add(new StandingView(
                name, standing.score, standing.achievedOrder, standing.manualRank
        )));

        // Start with the pure automatic ranking.
        result.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.score(), a.score());
            if (scoreCompare != 0) return scoreCompare;

            int timeCompare = Long.compare(a.achievedOrder(), b.achievedOrder());
            if (timeCompare != 0) return timeCompare;

            return a.player().compareToIgnoreCase(b.player());
        });

        // Manual ranks behave as real positions, not as a separate group.
        // Example: /cobbleboard rank ... Kenneth 2 inserts Kenneth at #2 while
        // everyone else keeps their automatic relative order around that slot.
        List<StandingView> manual = result.stream()
                .filter(s -> s.manualRank() != null)
                .sorted(Comparator
                        .comparingInt((StandingView s) -> s.manualRank())
                        .thenComparingLong(StandingView::achievedOrder)
                        .thenComparing(StandingView::player, String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (!manual.isEmpty()) {
            result.removeIf(s -> s.manualRank() != null);
            for (StandingView standing : manual) {
                int index = Math.max(0, Math.min(standing.manualRank() - 1, result.size()));
                result.add(index, standing);
            }
        }

        return result;
    }

    public boolean setManualRank(String objectiveName, String player, Integer rank) {
        RankingData.PlayerStanding standing = findStanding(objectiveName, player);
        if (standing == null) return false;
        standing.manualRank = rank;
        dirty = true;
        save();
        return true;
    }

    public boolean swap(String objectiveName, String playerA, String playerB) {
        List<StandingView> ranked = getRanked(objectiveName);
        int rankA = -1;
        int rankB = -1;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).player().equalsIgnoreCase(playerA)) rankA = i + 1;
            if (ranked.get(i).player().equalsIgnoreCase(playerB)) rankB = i + 1;
        }
        if (rankA < 0 || rankB < 0) return false;

        RankingData.PlayerStanding a = findStanding(objectiveName, playerA);
        RankingData.PlayerStanding b = findStanding(objectiveName, playerB);
        if (a == null || b == null) return false;
        a.manualRank = rankB;
        b.manualRank = rankA;
        dirty = true;
        save();
        return true;
    }

    public boolean resetAllOverrides(String objectiveName) {
        Map<String, RankingData.PlayerStanding> standings = data.objectives.get(objectiveName);
        if (standings == null) return false;
        standings.values().forEach(s -> s.manualRank = null);
        dirty = true;
        save();
        return true;
    }

    private RankingData.PlayerStanding findStanding(String objectiveName, String player) {
        Map<String, RankingData.PlayerStanding> standings = data.objectives.get(objectiveName);
        if (standings == null) return null;
        for (Map.Entry<String, RankingData.PlayerStanding> entry : standings.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(player)) return entry.getValue();
        }
        return null;
    }

    public record StandingView(String player, int score, long achievedOrder, Integer manualRank) {}
}
