package com.cobbleverse.cobblebounty;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.resource.Resource;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;

public final class BountyManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblebounty.json");
    private static final Path STATE_PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblebounty-state.json");

    private BountyConfig config = new BountyConfig();
    private BountyState state = new BountyState();
    private final Random random = new Random();
    private final Map<String, List<String>> bucketCatalog = new LinkedHashMap<>();
    private boolean catalogBuilt = false;

    public void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                BountyConfig loaded = GSON.fromJson(Files.readString(CONFIG_PATH), BountyConfig.class);
                if (loaded != null) config = loaded;
                // Re-save so older configs are automatically migrated to the current schema.
                saveConfig();
            } else {
                saveConfig();
            }
        } catch (Exception e) {
            CobbleBountyMod.LOGGER.error("Failed to load CobbleBounty config; using defaults", e);
            config = new BountyConfig();
        }

        try {
            if (Files.exists(STATE_PATH)) {
                BountyState loaded = GSON.fromJson(Files.readString(STATE_PATH), BountyState.class);
                if (loaded != null) state = loaded;
                // v0.1.x migration: keep the current bounty, but give it an identity.
                if (state.bountyId == null || state.bountyId.isBlank()) {
                    state.bountyId = UUID.randomUUID().toString();
                    state.bountyStartedAtEpochMillis = System.currentTimeMillis();
                    saveState();
                }
                migrateStateCollections();
            }
        } catch (Exception e) {
            CobbleBountyMod.LOGGER.error("Failed to load CobbleBounty state; using empty state", e);
            state = new BountyState();
        }
    }

    private void migrateStateCollections() {
        if (state.completedPlayers == null) state.completedPlayers = new HashSet<>();
        if (state.totalCompleted == null) state.totalCompleted = new HashMap<>();
        if (state.currentStreak == null) state.currentStreak = new HashMap<>();
        if (state.lastCompletionDate == null) state.lastCompletionDate = new HashMap<>();
        if (state.bestStreak == null) state.bestStreak = new HashMap<>();
        if (state.firstCompletions == null) state.firstCompletions = new HashMap<>();
        if (state.rarityCompletions == null) state.rarityCompletions = new HashMap<>();
        if (state.lastAnnouncementDate == null) state.lastAnnouncementDate = new HashMap<>();
        if (state.history == null) state.history = new ArrayList<>();
    }

    public void reload(MinecraftServer server) {
        load();
        catalogBuilt = false;
        rebuildCatalog(server);
        ensureToday(server);
    }

    private void saveConfig() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        Files.writeString(CONFIG_PATH, GSON.toJson(config), StandardCharsets.UTF_8);
    }

    public synchronized void saveState() {
        try {
            Files.createDirectories(STATE_PATH.getParent());
            Files.writeString(STATE_PATH, GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (IOException e) {
            CobbleBountyMod.LOGGER.error("Failed to save CobbleBounty state", e);
        }
    }

    public LocalDate today() {
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        return LocalDate.now(zone);
    }

    public synchronized void ensureToday(MinecraftServer server) {
        ensureCatalog(server);
        String today = today().toString();
        if (!today.equals(state.date) || state.species == null || state.species.isBlank()) {
            rollNewBounty(today);
            syncAllScoreboards(server);
            broadcastNewBounty(server);
            return;
        }

        // v0.2 -> v0.3 migration: preserve today's species and infer its bucket.
        if (state.bucket == null || state.bucket.isBlank()) {
            state.bucket = findBucketForSpecies(state.species);
            saveState();
        }
    }

    private void ensureCatalog(MinecraftServer server) {
        if (!catalogBuilt) rebuildCatalog(server);
    }

    public synchronized void rebuildCatalog(MinecraftServer server) {
        bucketCatalog.clear();
        for (String bucket : List.of("common", "uncommon", "rare", "ultra-rare")) {
            bucketCatalog.put(bucket, new ArrayList<>());
        }

        Map<String, String> speciesToBucket = new HashMap<>();

        if (config.autoBuildPoolFromSpawnData && server != null) {
            try {
                Map<Identifier, Resource> resources = server.getResourceManager().findResources(
                        "spawn_pool_world",
                        id -> id.getPath().endsWith(".json")
                );

                for (Map.Entry<Identifier, Resource> entry : resources.entrySet()) {
                    try (Reader reader = new InputStreamReader(entry.getValue().getInputStream(), StandardCharsets.UTF_8)) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (!parsed.isJsonObject()) continue;
                        JsonObject root = parsed.getAsJsonObject();
                        if (root.has("enabled") && !root.get("enabled").getAsBoolean()) continue;
                        if (!root.has("spawns") || !root.get("spawns").isJsonArray()) continue;

                        JsonArray spawns = root.getAsJsonArray("spawns");
                        for (JsonElement element : spawns) {
                            if (!element.isJsonObject()) continue;
                            JsonObject spawn = element.getAsJsonObject();
                            if (!spawn.has("pokemon") || !spawn.has("bucket")) continue;
                            if (spawn.has("type") && !"pokemon".equalsIgnoreCase(spawn.get("type").getAsString())) continue;

                            String pokemonProperty = spawn.get("pokemon").getAsString().trim();
                            if (pokemonProperty.isBlank()) continue;
                            String species = normalizeSpecies(pokemonProperty.split("\\s+")[0]);
                            String bucket = normalizeBucket(spawn.get("bucket").getAsString());
                            if (species.isBlank() || !isKnownBucket(bucket)) continue;

                            // If a species has multiple spawn details, assign it to the easiest bucket it can use.
                            String old = speciesToBucket.get(species);
                            if (old == null || bucketRank(bucket) < bucketRank(old)) speciesToBucket.put(species, bucket);
                        }
                    } catch (Exception e) {
                        CobbleBountyMod.LOGGER.debug("Could not parse spawn resource {}", entry.getKey(), e);
                    }
                }
            } catch (Throwable t) {
                CobbleBountyMod.LOGGER.warn("Could not auto-build bounty pools from spawn data; using manual pools", t);
            }
        }

        // Manual entries fill gaps and support custom species whose spawn data uses a nonstandard format.
        if (config.manualBucketPools != null) {
            for (Map.Entry<String, List<String>> entry : config.manualBucketPools.entrySet()) {
                String bucket = normalizeBucket(entry.getKey());
                if (!isKnownBucket(bucket) || entry.getValue() == null) continue;
                for (String raw : entry.getValue()) {
                    String species = normalizeSpecies(raw);
                    if (!species.isBlank()) speciesToBucket.putIfAbsent(species, bucket);
                }
            }
        }

        Set<String> excluded = new HashSet<>();
        if (config.excludedSpecies != null) {
            for (String raw : config.excludedSpecies) excluded.add(normalizeSpecies(raw));
        }

        for (Map.Entry<String, String> entry : speciesToBucket.entrySet()) {
            if (!excluded.contains(entry.getKey())) bucketCatalog.get(entry.getValue()).add(entry.getKey());
        }
        for (List<String> list : bucketCatalog.values()) list.sort(String::compareTo);
        catalogBuilt = true;

        CobbleBountyMod.LOGGER.info(
                "Bounty pools loaded: common={}, uncommon={}, rare={}, ultra-rare={}",
                getPoolSize("common"), getPoolSize("uncommon"), getPoolSize("rare"), getPoolSize("ultra-rare")
        );
    }

    private void rollNewBounty(String date) {
        String bucket = chooseBucket();
        List<String> pool = bucketCatalog.getOrDefault(bucket, List.of());
        if (pool.isEmpty()) throw new IllegalStateException("No eligible Pokemon in enabled bounty buckets");

        state.date = date;
        state.bucket = bucket;
        state.species = normalizeSpecies(pool.get(random.nextInt(pool.size())));
        state.bountyId = UUID.randomUUID().toString();
        state.bountyStartedAtEpochMillis = System.currentTimeMillis();
        state.completedPlayers.clear();
        recordHistoryEntry();
        saveState();
    }

    private String chooseBucket() {
        List<String> candidates = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0.0;

        List<String> enabled = config.enabledBuckets == null ? List.of() : config.enabledBuckets;
        for (String raw : enabled) {
            String bucket = normalizeBucket(raw);
            if (!isKnownBucket(bucket) || getPoolSize(bucket) == 0) continue;
            double weight = 1.0;
            if (config.bucketWeights != null) weight = Math.max(0.0, config.bucketWeights.getOrDefault(bucket, 1.0));
            if (weight <= 0.0) continue;
            candidates.add(bucket);
            weights.add(weight);
            total += weight;
        }

        if (candidates.isEmpty()) {
            for (String bucket : List.of("common", "uncommon", "rare", "ultra-rare")) {
                if (getPoolSize(bucket) > 0) return bucket;
            }
            throw new IllegalStateException("CobbleBounty found no eligible species in any rarity bucket.");
        }

        double roll = random.nextDouble() * total;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0.0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    private void recordHistoryEntry() {
        migrateStateCollections();
        if (state.date == null || state.date.isBlank() || state.species == null || state.species.isBlank()) return;

        // Keep one final entry per calendar date. Admin rerolls/sets update today's row instead of
        // creating several duplicate history rows.
        state.history.removeIf(entry -> entry != null && state.date.equals(entry.date));
        state.history.add(new BountyState.HistoryEntry(state.date, state.species, getBucket()));

        // Keep the state file small while still giving plenty of history for future features.
        while (state.history.size() > 90) state.history.remove(0);
    }

    public synchronized void reroll(MinecraftServer server) {
        ensureCatalog(server);
        rollNewBounty(today().toString());
        syncTodayBoard(server);
        broadcastNewBounty(server);
    }

    public synchronized void setSpecies(MinecraftServer server, String species) {
        ensureCatalog(server);
        setSpecies(server, species, findBucketForSpecies(species));
    }

    public synchronized void setSpecies(MinecraftServer server, String species, String bucket) {
        String normalizedBucket = normalizeBucket(bucket);
        if (!isKnownBucket(normalizedBucket)) normalizedBucket = findBucketForSpecies(species);
        state.date = today().toString();
        state.species = normalizeSpecies(species);
        state.bucket = normalizedBucket;
        state.bountyId = UUID.randomUUID().toString();
        state.bountyStartedAtEpochMillis = System.currentTimeMillis();
        state.completedPlayers.clear();
        recordHistoryEntry();
        saveState();
        syncTodayBoard(server);
        broadcastNewBounty(server);
    }

    public String getSpecies() { return state.species; }
    public String getDisplaySpecies() { return prettify(state.species); }
    public String getBucket() { return normalizeBucket(state.bucket); }
    public String getDisplayBucket() { return prettify(getBucket()); }
    public int getPoolSize(String bucket) { return bucketCatalog.getOrDefault(normalizeBucket(bucket), List.of()).size(); }

    public String getRewardDescription() {
        BountyConfig.Reward reward = getCurrentReward();
        String itemName = reward.item == null ? "reward" : reward.item;
        int colon = itemName.indexOf(':');
        if (colon >= 0 && colon + 1 < itemName.length()) itemName = itemName.substring(colon + 1);
        return Math.max(1, reward.count) + "x " + prettify(itemName);
    }

    private BountyConfig.Reward getCurrentReward() {
        if (config.bucketRewards != null) {
            BountyConfig.Reward reward = config.bucketRewards.get(getBucket());
            if (reward != null && reward.item != null && !reward.item.isBlank()) return reward;
        }
        return new BountyConfig.Reward("minecraft:enchanted_golden_apple", 1);
    }

    private String findBucketForSpecies(String species) {
        String normalized = normalizeSpecies(species);
        for (String bucket : List.of("common", "uncommon", "rare", "ultra-rare")) {
            if (bucketCatalog.getOrDefault(bucket, List.of()).contains(normalized)) return bucket;
        }
        return "common";
    }

    private static int bucketRank(String bucket) {
        return switch (normalizeBucket(bucket)) {
            case "common" -> 0;
            case "uncommon" -> 1;
            case "rare" -> 2;
            case "ultra-rare" -> 3;
            default -> 99;
        };
    }

    private static boolean isKnownBucket(String bucket) {
        return bucketRank(bucket) < 99;
    }

    public static String normalizeBucket(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(Locale.ROOT).trim().replace('_', '-').replace(" ", "-");
        if ("ultrarare".equals(s) || "ultra--rare".equals(s)) return "ultra-rare";
        return s;
    }

    public String getBountyId() { return state.bountyId; }
    public long getBountyStartedAtEpochMillis() { return state.bountyStartedAtEpochMillis; }

    /**
     * Called from Cobblemon's POKEMON_CAPTURED event. Only a Pokemon captured while it is
     * the active bounty gets the token. Because the token contains the unique bounty id,
     * Pokemon caught before an admin reroll (even on the same day) cannot be turned in.
     */
    public synchronized void recordCapture(Pokemon pokemon) {
        if (pokemon == null || state.species == null || state.species.isBlank()) return;
        String capturedSpecies = normalizeSpecies(pokemon.getSpecies().getName());
        if (!capturedSpecies.equals(normalizeSpecies(state.species))) return;

        try {
            pokemon.getPersistentData().putString("cobblebounty_bounty_id", state.bountyId);
            pokemon.getPersistentData().putString("cobblebounty_capture_date", state.date);
            pokemon.getPersistentData().putLong("cobblebounty_capture_time", System.currentTimeMillis());
            CobbleBountyMod.LOGGER.info("Marked captured {} as eligible for bounty {}", capturedSpecies, state.bountyId);
        } catch (Throwable t) {
            CobbleBountyMod.LOGGER.error("Could not mark captured Pokemon for bounty submission", t);
        }
    }

    public boolean wasCaughtForCurrentBounty(Pokemon pokemon) {
        if (pokemon == null || state.bountyId == null || state.bountyId.isBlank()) return false;
        try {
            String id = pokemon.getPersistentData().getString("cobblebounty_bounty_id");
            return state.bountyId.equals(id);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean hasCompleted(ServerPlayerEntity player) {
        return state.completedPlayers.contains(player.getUuidAsString());
    }

    public int getTotal(ServerPlayerEntity player) {
        return state.totalCompleted.getOrDefault(player.getUuidAsString(), 0);
    }

    public int getStreak(ServerPlayerEntity player) {
        return state.currentStreak.getOrDefault(player.getUuidAsString(), 0);
    }

    public int getBestStreak(ServerPlayerEntity player) {
        return state.bestStreak.getOrDefault(player.getUuidAsString(), getStreak(player));
    }

    public int getFirstCompletions(ServerPlayerEntity player) {
        return state.firstCompletions.getOrDefault(player.getUuidAsString(), 0);
    }

    public int getRarityCompletions(ServerPlayerEntity player, String bucket) {
        Map<String, Integer> counts = state.rarityCompletions.get(player.getUuidAsString());
        if (counts == null) return 0;
        return counts.getOrDefault(normalizeBucket(bucket), 0);
    }

    public List<BountyState.HistoryEntry> history(int limit) {
        migrateStateCollections();
        int safeLimit = Math.max(1, limit);
        List<BountyState.HistoryEntry> copy = new ArrayList<>(state.history);
        copy.sort((a, b) -> {
            String ad = a == null || a.date == null ? "" : a.date;
            String bd = b == null || b.date == null ? "" : b.date;
            return bd.compareTo(ad);
        });
        if (copy.size() > safeLimit) return new ArrayList<>(copy.subList(0, safeLimit));
        return copy;
    }

    public boolean shouldSendDailyAnnouncement(ServerPlayerEntity player) {
        migrateStateCollections();
        String today = today().toString();
        return !today.equals(state.lastAnnouncementDate.get(player.getUuidAsString()));
    }

    public void markDailyAnnouncementSent(ServerPlayerEntity player) {
        migrateStateCollections();
        state.lastAnnouncementDate.put(player.getUuidAsString(), today().toString());
        saveState();
    }

    public boolean isDailyAnnouncementEnabled() {
        return config.dailyAnnouncementEnabled;
    }

    public int completedTodayCount() { return state.completedPlayers.size(); }

    public List<Map.Entry<String, Integer>> leaderboard() {
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(state.totalCompleted.entrySet());
        rows.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        return rows;
    }

    public synchronized void setPasture(ServerPlayerEntity player, BlockPos pos) {
        String dim = player.getWorld().getRegistryKey().getValue().toString();
        state.pasture = new BountyState.PastureLocation(dim, pos.getX(), pos.getY(), pos.getZ());
        saveState();
    }

    public BountyState.PastureLocation getPasture() { return state.pasture; }

    public SubmitResult submit(MinecraftServer server, ServerPlayerEntity player) {
        ensureToday(server);
        if (hasCompleted(player)) return SubmitResult.fail("You already completed today's bounty.");
        if (state.pasture == null) return SubmitResult.fail("The bounty pasture has not been configured yet.");

        ServerWorld world = resolveWorld(server, state.pasture.dimension);
        if (world == null) return SubmitResult.fail("The configured bounty pasture dimension is unavailable.");

        BlockPos pasturePos = new BlockPos(state.pasture.x, state.pasture.y, state.pasture.z);
        if (player.getWorld() != world || player.squaredDistanceTo(Vec3d.ofCenter(pasturePos)) > 18 * 18) {
            return SubmitResult.fail("Stand near the Bounty Pasture before submitting.");
        }

        int r = Math.max(6, config.pastureSearchRadius);
        Box box = new Box(pasturePos).expand(r, Math.max(8, r / 2.0), r);
        List<PokemonEntity> candidates = world.getEntitiesByClass(
                PokemonEntity.class,
                box,
                entity -> matchesSubmission(entity, player)
        );

        if (candidates.isEmpty()) {
            if (config.requireCaughtAfterBountyStart) {
                return SubmitResult.fail("No eligible " + getDisplaySpecies() + " was found. It must be caught after this bounty began, belong to you, and be placed in the Bounty Pasture.");
            }
            return SubmitResult.fail("No matching " + getDisplaySpecies() + " belonging to you was found in the Bounty Pasture.");
        }

        PokemonEntity entity = candidates.get(0);
        Pokemon pokemon = entity.getPokemon();
        if (!removePokemonFromStorage(player, pokemon)) {
            return SubmitResult.fail("Found the Pokémon, but could not remove it from storage. No reward was given.");
        }

        if (!entity.isRemoved()) entity.discard();
        complete(server, player);
        return SubmitResult.ok("Bounty complete! " + getDisplaySpecies() + " was submitted.");
    }

    private boolean matchesSubmission(PokemonEntity entity, ServerPlayerEntity player) {
        try {
            Pokemon pokemon = entity.getPokemon();
            if (pokemon == null) return false;
            if (pokemon.getOwnerPlayer() != player) return false;
            String species = normalizeSpecies(pokemon.getSpecies().getName());
            if (!species.equals(normalizeSpecies(state.species))) return false;
            if (config.requireCaughtAfterBountyStart && !wasCaughtForCurrentBounty(pokemon)) return false;
            return !config.requirePastureTether || entity.getTethering() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean removePokemonFromStorage(ServerPlayerEntity player, Pokemon pokemon) {
        try {
            // Party fallback. Normally a pastured Pokémon is in the PC, but this keeps the remover generic.
            Object party = Cobblemon.INSTANCE.getStorage().getParty(player);
            if (invokeRemove(party, pokemon)) return true;
        } catch (Throwable ignored) {}

        // Cobblemon storage APIs have changed names/signatures across releases. Reflection keeps this
        // small server-side mod resilient while still using the live 1.7.3 storage manager.
        Object manager = Cobblemon.INSTANCE.getStorage();
        for (String methodName : List.of("getPC", "getPc")) {
            for (Object arg : List.of(player, player.getUuid())) {
                try {
                    Method method = findCompatibleMethod(manager.getClass(), methodName, arg.getClass());
                    if (method == null) continue;
                    Object pc = method.invoke(manager, arg);
                    if (pc != null && invokeRemove(pc, pokemon)) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private boolean invokeRemove(Object store, Pokemon pokemon) {
        if (store == null) return false;
        try {
            for (Method m : store.getClass().getMethods()) {
                if (!m.getName().equals("remove") || m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isAssignableFrom(pokemon.getClass()) && !p.getName().contains("Pokemon")) continue;
                Object result = m.invoke(store, pokemon);
                if (result instanceof Boolean b) return b;
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Method findCompatibleMethod(Class<?> type, String name, Class<?> argType) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0].isAssignableFrom(argType)) return m;
        }
        return null;
    }

    private synchronized void complete(MinecraftServer server, ServerPlayerEntity player) {
        migrateStateCollections();

        String uuid = player.getUuidAsString();
        LocalDate today = today();
        String previous = state.lastCompletionDate.get(uuid);
        int streak = 1;
        if (previous != null) {
            try {
                if (LocalDate.parse(previous).plusDays(1).equals(today)) {
                    streak = state.currentStreak.getOrDefault(uuid, 0) + 1;
                }
            } catch (Exception ignored) {}
        }

        boolean firstToday = state.completedPlayers.isEmpty();

        state.completedPlayers.add(uuid);
        state.totalCompleted.put(uuid, state.totalCompleted.getOrDefault(uuid, 0) + 1);
        state.currentStreak.put(uuid, streak);
        state.bestStreak.put(uuid, Math.max(state.bestStreak.getOrDefault(uuid, 0), streak));
        state.lastCompletionDate.put(uuid, today.toString());

        Map<String, Integer> rarityCounts = state.rarityCompletions.computeIfAbsent(uuid, k -> new HashMap<>());
        String bucket = getBucket();
        rarityCounts.put(bucket, rarityCounts.getOrDefault(bucket, 0) + 1);

        if (firstToday) {
            state.firstCompletions.put(uuid, state.firstCompletions.getOrDefault(uuid, 0) + 1);
        }

        saveState();

        giveReward(server, player);
        syncScoreboard(server, player);

        // Streak milestone infrastructure is intentionally disabled by default. No milestone reward
        // is given unless an administrator explicitly enables it in a future config.
        if (config.enableStreakMilestones && config.streakMilestoneRewards != null) {
            BountyConfig.Reward milestone = config.streakMilestoneRewards.get(streak);
            if (milestone != null && milestone.item != null && !milestone.item.isBlank()) {
                giveSpecificReward(server, player, milestone);
            }
        }

        if (config.broadcastCompletion) {
            String message = "✓ " + player.getName().getString()
                    + " completed today's " + getDisplaySpecies() + " bounty!"
                    + (streak > 1 ? " 🔥 " + streak + " day streak!" : "");
            server.getPlayerManager().broadcast(Text.literal(message), false);
        }

        if (firstToday && config.firstCompletionAnnouncementEnabled) {
            server.getPlayerManager().broadcast(
                    Text.literal("★ " + player.getName().getString() + " was the first Bounty Hunter today!"),
                    false
            );
        }
    }

    private void giveReward(MinecraftServer server, ServerPlayerEntity player) {
        BountyConfig.Reward reward = getCurrentReward();
        String cmd = "give " + player.getGameProfile().getName() + " " + reward.item + " " + Math.max(1, reward.count);
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd);
    }

    private void giveSpecificReward(MinecraftServer server, ServerPlayerEntity player, BountyConfig.Reward reward) {
        String cmd = "give " + player.getGameProfile().getName() + " " + reward.item + " " + Math.max(1, reward.count);
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), cmd);
    }

    public void ensureScoreboards(MinecraftServer server) {
        executeSilently(server, "scoreboard objectives add bounty_total dummy \"Bounties Completed\"");
        executeSilently(server, "scoreboard objectives add bounty_streak dummy \"Bounty Streak\"");
        executeSilently(server, "scoreboard objectives add bounty_today dummy \"Today's Bounty\"");
    }

    public void syncAllScoreboards(MinecraftServer server) {
        ensureScoreboards(server);
        syncTodayBoard(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) syncScoreboard(server, player);
    }

    public void syncTodayBoard(MinecraftServer server) {
        ensureScoreboards(server);
        executeSilently(server, "scoreboard players reset * bounty_today");

        // Scores are ordering keys for CobbleBoard status mode; the numbers are hidden there.
        executeSilently(server, "scoreboard players set " + quoteScoreHolder(getDisplaySpecies()) + " bounty_today 30");
        executeSilently(server, "scoreboard players set " + quoteScoreHolder(getDisplayBucket()) + " bounty_today 20");
        executeSilently(server, "scoreboard players set " + quoteScoreHolder("Reward: " + getRewardDescription()) + " bounty_today 10");
    }

    private String quoteScoreHolder(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        return "\"" + safe + "\"";
    }

    public void syncScoreboard(MinecraftServer server, ServerPlayerEntity player) {
        ensureScoreboards(server);
        String name = player.getGameProfile().getName();
        executeSilently(server, "scoreboard players set " + name + " bounty_total " + getTotal(player));
        executeSilently(server, "scoreboard players set " + name + " bounty_streak " + getStreak(player));
    }

    private void executeSilently(MinecraftServer server, String command) {
        try {
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command);
        } catch (Exception ignored) {
            // Objective may already exist; vanilla command failure is harmless here.
        }
    }

    private void broadcastNewBounty(MinecraftServer server) {
        if (server == null || !config.dailyAnnouncementEnabled) return;
        server.getPlayerManager().broadcast(
                Text.literal("★ A new Pokémon Bounty is available! Use /bounty for details."),
                false
        );
    }

    private ServerWorld resolveWorld(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) return null;
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        return server.getWorld(key);
    }

    public static String normalizeSpecies(String value) {
        if (value == null) return "";
        String s = value.toLowerCase(Locale.ROOT).trim();
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.replace(" ", "").replace("-", "").replace("_", "");
    }

    public static String prettify(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String s = value.replace('_', ' ').replace('-', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : s.split("\\s+")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    public record SubmitResult(boolean success, String message) {
        public static SubmitResult ok(String message) { return new SubmitResult(true, message); }
        public static SubmitResult fail(String message) { return new SubmitResult(false, message); }
    }
}
