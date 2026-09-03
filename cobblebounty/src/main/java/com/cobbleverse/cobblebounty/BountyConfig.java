package com.cobbleverse.cobblebounty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BountyConfig {
    public String timezone = "America/Vancouver";
    public int pastureSearchRadius = 16;
    public boolean requirePastureTether = true;
    public boolean requireCaughtAfterBountyStart = true;
    public boolean broadcastCompletion = true;
    public boolean dailyAnnouncementEnabled = true;
    public boolean firstCompletionAnnouncementEnabled = true;

    /**
     * Reserved for future streak rewards. The data/config is ready, but rewards remain disabled
     * until enableStreakMilestones is explicitly turned on.
     */
    public boolean enableStreakMilestones = false;
    public Map<Integer, Reward> streakMilestoneRewards = defaultStreakMilestones();

    /**
     * Reserved for richer rarity-specific chat presentation. Disabled by default so current
     * presentation stays unchanged until explicitly enabled.
     */
    public boolean enableRarityPresentation = false;
    public Map<String, String> rarityPresentation = defaultRarityPresentation();

    /**
     * Rarity buckets that are allowed to be selected for the daily bounty.
     * Valid values: common, uncommon, rare, ultra-rare.
     */
    public List<String> enabledBuckets = new ArrayList<>(List.of(
            "common", "uncommon", "rare", "ultra-rare"
    ));

    /** Relative chance of choosing each enabled bucket before a species is chosen. */
    public Map<String, Double> bucketWeights = defaultBucketWeights();

    /** Reward is selected from the rarity bucket attached to the active bounty. */
    public Map<String, Reward> bucketRewards = defaultBucketRewards();

    /** Species that should never become an automatic bounty even if they have natural spawn data. */
    public List<String> excludedSpecies = new ArrayList<>();

    /**
     * If true, CobbleBounty scans loaded spawn_pool_world JSON resources and builds the species pool
     * automatically. A species with entries in multiple buckets is assigned to its least-rare bucket,
     * preventing a common spawn route from paying an ultra-rare reward.
     */
    public boolean autoBuildPoolFromSpawnData = true;

    /** Optional fallback/manual pools. Also useful for species supplied by unusual addons. */
    public Map<String, List<String>> manualBucketPools = defaultManualPools();

    public static final class Reward {
        public String item = "minecraft:enchanted_golden_apple";
        public int count = 1;

        public Reward() {}
        public Reward(String item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    private static Map<String, Double> defaultBucketWeights() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("common", 45.0);
        map.put("uncommon", 30.0);
        map.put("rare", 18.0);
        map.put("ultra-rare", 7.0);
        return map;
    }

    private static Map<String, Reward> defaultBucketRewards() {
        Map<String, Reward> map = new LinkedHashMap<>();
        map.put("common", new Reward("minecraft:enchanted_golden_apple", 1));
        map.put("uncommon", new Reward("minecraft:enchanted_golden_apple", 1));
        map.put("rare", new Reward("minecraft:enchanted_golden_apple", 2));
        map.put("ultra-rare", new Reward("minecraft:enchanted_golden_apple", 3));
        return map;
    }

    private static Map<Integer, Reward> defaultStreakMilestones() {
        Map<Integer, Reward> map = new LinkedHashMap<>();
        // Intentionally empty/disabled for now. Add rewards later, e.g.:
        // map.put(7, new Reward("minecraft:diamond", 1));
        return map;
    }

    private static Map<String, String> defaultRarityPresentation() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("common", "gray");
        map.put("uncommon", "green");
        map.put("rare", "light_purple");
        map.put("ultra-rare", "gold");
        return map;
    }

    private static Map<String, List<String>> defaultManualPools() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("common", new ArrayList<>());
        map.put("uncommon", new ArrayList<>());
        map.put("rare", new ArrayList<>());
        map.put("ultra-rare", new ArrayList<>());
        return map;
    }
}
