package com.cobbleverse.cobblebounty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BountyState {
    public String date = "";
    public String species = "";
    /** Spawn rarity bucket selected for the active bounty. */
    public String bucket = "";
    /** Unique id for the currently active bounty. A reroll creates a new id even on the same date. */
    public String bountyId = "";
    public long bountyStartedAtEpochMillis = 0L;
    public Set<String> completedPlayers = new HashSet<>();
    public Map<String, Integer> totalCompleted = new HashMap<>();
    public Map<String, Integer> currentStreak = new HashMap<>();
    public Map<String, String> lastCompletionDate = new HashMap<>();
    public Map<String, Integer> bestStreak = new HashMap<>();
    public Map<String, Integer> firstCompletions = new HashMap<>();
    public Map<String, Map<String, Integer>> rarityCompletions = new HashMap<>();
    public Map<String, String> lastAnnouncementDate = new HashMap<>();
    public List<HistoryEntry> history = new ArrayList<>();
    public PastureLocation pasture = null;

    public static final class HistoryEntry {
        public String date;
        public String species;
        public String bucket;

        public HistoryEntry() {}

        public HistoryEntry(String date, String species, String bucket) {
            this.date = date;
            this.species = species;
            this.bucket = bucket;
        }
    }

    public static final class PastureLocation {
        public String dimension;
        public int x;
        public int y;
        public int z;

        public PastureLocation() {}

        public PastureLocation(String dimension, int x, int y, int z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
