package com.cobbleverse.cobbleboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Vector3f;
import com.cobbleverse.cobbleboard.mixin.DisplayEntityInvoker;
import com.cobbleverse.cobbleboard.mixin.TextDisplayEntityInvoker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class BoardManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("cobbleboard-displays.json");

    private final RankingManager rankings;
    private BoardData data = new BoardData();
    private final Map<String, List<Entity>> liveEntities = new HashMap<>();
    private final Map<String, Text> lastRenderedText = new HashMap<>();
    private int tickCounter;
    private boolean dirty;

    public BoardManager(RankingManager rankings) {
        this.rankings = rankings;
    }

    public void load() {
        if (!Files.exists(DATA_FILE)) return;
        try (Reader reader = Files.newBufferedReader(DATA_FILE)) {
            BoardData loaded = GSON.fromJson(reader, BoardData.class);
            if (loaded != null && loaded.boards != null) data = loaded;
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
        if (tickCounter < 100) return; // refresh holograms every 5 seconds
        tickCounter = 0;
        refreshAll(server);
        save();
    }

    public Collection<String> boardIds() {
        return Collections.unmodifiableSet(data.boards.keySet());
    }

    public BoardData.BoardDefinition get(String id) {
        return data.boards.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean create(MinecraftServer server, String id, String objective, String dimension,
                          double x, double y, double z, int limit) {
        String key = id.toLowerCase(Locale.ROOT);
        if (data.boards.containsKey(key)) return false;
        if (!rankings.trackObjective(server, objective)) return false;

        String title = objective;
        data.boards.put(key, new BoardData.BoardDefinition(objective, title, dimension, x, y, z, limit));
        dirty = true;
        save();
        refresh(server, key);
        return true;
    }

    public boolean delete(MinecraftServer server, String id) {
        String key = id.toLowerCase(Locale.ROOT);
        BoardData.BoardDefinition removed = data.boards.remove(key);
        if (removed == null) return false;
        clearLive(key);
        cleanupTagged(server, key);
        dirty = true;
        save();
        return true;
    }

    public boolean move(MinecraftServer server, String id, String dimension, double x, double y, double z) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.dimension = dimension;
        board.x = x;
        board.y = y;
        board.z = z;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setLimit(MinecraftServer server, String id, int limit) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.limit = limit;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setTitle(MinecraftServer server, String id, String title) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.title = title;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean refresh(MinecraftServer server, String id) {
        String key = id.toLowerCase(Locale.ROOT);
        BoardData.BoardDefinition board = data.boards.get(key);
        if (board == null) return false;

        ServerWorld world = resolveWorld(server, board.dimension);
        if (world == null) {
            CobbleBoardMod.LOGGER.warn("Cannot render board {} because dimension {} was not found", key, board.dimension);
            return false;
        }

        // Panel/status boards use a single TextDisplay. Keep that entity alive and
        // update its text only when the rendered contents actually change.
        if (!"stacked".equalsIgnoreCase(board.displayMode)) {
            Text boardText = "status".equalsIgnoreCase(board.displayMode)
                    ? buildStatusText(world, board)
                    : buildPanelText(board);

            List<Entity> entities = liveEntities.get(key);
            if (entities != null && entities.size() == 1
                    && entities.get(0) instanceof DisplayEntity.TextDisplayEntity display
                    && !display.isRemoved()
                    && display.getWorld() == world) {

                Text previous = lastRenderedText.get(key);
                if (!Objects.equals(previous, boardText)) {
                    ((TextDisplayEntityInvoker) display).cobbleboard$setText(boardText);
                    lastRenderedText.put(key, boardText);
                }
                return true;
            }

            // Missing/stale entity (for example after restart): clean once and respawn.
            clearLive(key);
            cleanupTagged(server, key);
            return spawnTextDisplay(world, key, board, boardText);
        }

        // Legacy stacked mode uses multiple armor stands, so keep its existing
        // rebuild behavior for now.
        clearLive(key);
        cleanupTagged(server, key);
        return refreshStacked(world, key, board);
    }

    private boolean spawnTextDisplay(ServerWorld world, String key,
                                     BoardData.BoardDefinition board, Text boardText) {
        List<Entity> entities = new ArrayList<>();

        DisplayEntity.TextDisplayEntity display =
                new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, world);
        display.refreshPositionAndAngles(board.x, board.y, board.z, 0.0F, 0.0F);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.addCommandTag("cobbleboard");
        display.addCommandTag("cobbleboard_" + sanitizeTag(key));

        ((TextDisplayEntityInvoker) display).cobbleboard$setText(boardText);
        ((TextDisplayEntityInvoker) display).cobbleboard$setLineWidth(Math.max(80, board.boardWidth));
        ((DisplayEntityInvoker) display).cobbleboard$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        ((DisplayEntityInvoker) display).cobbleboard$setViewRange(2.0F);

        float scale = (float) Math.max(0.25D, Math.min(5.0D, board.boardScale));
        ((DisplayEntityInvoker) display).cobbleboard$setTransformation(
                new AffineTransformation(null, null, new Vector3f(scale, scale, scale), null));

        world.spawnEntity(display);
        entities.add(display);
        liveEntities.put(key, entities);
        lastRenderedText.put(key, boardText);
        return true;
    }

    private boolean refreshStatus(ServerWorld world, String key, BoardData.BoardDefinition board) {
        return spawnTextDisplay(world, key, board, buildStatusText(world, board));
    }

    private Text buildStatusText(ServerWorld world, BoardData.BoardDefinition board) {
        var result = Text.empty()
                .append(Text.literal(board.title).formatted(parseColor(board.titleColor, Formatting.YELLOW), Formatting.BOLD))
                .append(Text.literal("\n"))
                .append(Text.literal("━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.DARK_GRAY));

        ScoreboardObjective objective = world.getServer().getScoreboard().getNullableObjective(board.objective);
        if (objective == null) {
            return result.append(Text.literal("\nStatus unavailable").formatted(Formatting.RED));
        }

        List<ScoreboardEntry> entries = new ArrayList<>();
        for (ScoreboardEntry entry : world.getServer().getScoreboard().getScoreboardEntries(objective)) {
            if (!entry.hidden()) entries.add(entry);
        }
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed()
                .thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER));

        int count = Math.min(board.limit, entries.size());
        if (count == 0) {
            return result.append(Text.literal("\nNo status yet").formatted(Formatting.GRAY, Formatting.ITALIC));
        }

        for (int i = 0; i < count; i++) {
            result = result.append(Text.literal("\n")).append(statusLine(entries.get(i).owner(), i, board));
        }
        return result;
    }

    private Text statusLine(String value, int index, BoardData.BoardDefinition board) {
        String lower = value.toLowerCase(Locale.ROOT);
        Formatting color;
        boolean bold = false;

        if (index == 0) {
            color = parseColor(board.nameColor, Formatting.AQUA);
            bold = true;
        } else if (lower.equals("common")) {
            color = Formatting.GRAY;
        } else if (lower.equals("uncommon")) {
            color = Formatting.GREEN;
        } else if (lower.equals("rare")) {
            color = Formatting.LIGHT_PURPLE;
        } else if (lower.equals("ultra-rare") || lower.equals("ultra rare")) {
            color = Formatting.GOLD;
            bold = true;
        } else if (lower.startsWith("reward:")) {
            color = Formatting.GOLD;
        } else {
            color = parseColor(board.nameColor, Formatting.AQUA);
        }

        return bold ? Text.literal(value).formatted(color, Formatting.BOLD) : Text.literal(value).formatted(color);
    }

    private boolean refreshPanel(ServerWorld world, String key, BoardData.BoardDefinition board) {
        return spawnTextDisplay(world, key, board, buildPanelText(board));
    }

    private boolean refreshStacked(ServerWorld world, String key, BoardData.BoardDefinition board) {
        List<Entity> entities = new ArrayList<>();
        double y = board.y;

        entities.add(spawnLine(world, board.x, y, board.z,
                Text.literal(board.title).formatted(parseColor(board.titleColor, Formatting.YELLOW), Formatting.BOLD), key));
        y -= board.titleSpacing;

        List<RankingManager.StandingView> standings = rankings.getRanked(board.objective);
        int count = Math.min(board.limit, standings.size());
        for (int i = 0; i < count; i++) {
            RankingManager.StandingView standing = standings.get(i);
            int rank = i + 1;
            Text line = rankingLine(rank, standing, board);
            entities.add(spawnLine(world, board.x, y, board.z, line, key));
            y -= board.lineSpacing;
        }

        if (count == 0) {
            entities.add(spawnLine(world, board.x, y, board.z,
                    Text.literal("No scores yet").formatted(Formatting.GRAY, Formatting.ITALIC), key));
        }

        liveEntities.put(key, entities);
        return true;
    }

    private Text buildPanelText(BoardData.BoardDefinition board) {
        var result = Text.empty()
                .append(Text.literal(board.title).formatted(parseColor(board.titleColor, Formatting.YELLOW), Formatting.BOLD))
                .append(Text.literal("\n"))
                .append(Text.literal("━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.DARK_GRAY));

        List<RankingManager.StandingView> standings = rankings.getRanked(board.objective);
        int count = Math.min(board.limit, standings.size());
        if (count == 0) {
            return result.append(Text.literal("\nNo scores yet").formatted(Formatting.GRAY, Formatting.ITALIC));
        }

        for (int i = 0; i < count; i++) {
            result = result.append(Text.literal("\n")).append(rankingLine(i + 1, standings.get(i), board));
        }
        return result;
    }

    public void refreshAll(MinecraftServer server) {
        for (String id : new ArrayList<>(data.boards.keySet())) refresh(server, id);
    }

    public void shutdown() {
        for (String id : new ArrayList<>(liveEntities.keySet())) clearLive(id);
        save();
    }

    private Text rankingLine(int rank, RankingManager.StandingView standing, BoardData.BoardDefinition board) {
        Formatting rankColor = switch (rank) {
            case 1 -> Formatting.GOLD;
            case 2 -> Formatting.LIGHT_PURPLE;
            case 3 -> Formatting.DARK_RED;
            default -> Formatting.GRAY;
        };

        // Manual overrides are intentionally invisible on the public hologram.
        return Text.empty()
                .append(Text.literal(rank + ". ").formatted(rankColor, rank <= 3 ? Formatting.BOLD : Formatting.RESET))
                .append(Text.literal(standing.player()).formatted(parseColor(board.nameColor, Formatting.AQUA)))
                .append(Text.literal("  " + standing.score()).formatted(parseColor(board.scoreColor, Formatting.RED)));
    }

    public boolean setSpacing(MinecraftServer server, String id, double spacing) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.lineSpacing = spacing;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setTitleSpacing(MinecraftServer server, String id, double spacing) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.titleSpacing = spacing;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setColor(MinecraftServer server, String id, String target, String color) {
        BoardData.BoardDefinition board = get(id);
        if (board == null || parseColor(color, null) == null) return false;
        switch (target.toLowerCase(Locale.ROOT)) {
            case "title" -> board.titleColor = color.toLowerCase(Locale.ROOT);
            case "name" -> board.nameColor = color.toLowerCase(Locale.ROOT);
            case "score" -> board.scoreColor = color.toLowerCase(Locale.ROOT);
            default -> { return false; }
        }
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setDisplayMode(MinecraftServer server, String id, String mode) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        String normalized = mode.toLowerCase(Locale.ROOT);
        if (!normalized.equals("panel") && !normalized.equals("stacked") && !normalized.equals("status")) return false;
        board.displayMode = normalized;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setBoardScale(MinecraftServer server, String id, double scale) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.boardScale = scale;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean setBoardWidth(MinecraftServer server, String id, int width) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.boardWidth = width;
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    public boolean resetStyle(MinecraftServer server, String id) {
        BoardData.BoardDefinition board = get(id);
        if (board == null) return false;
        board.resetStyle();
        dirty = true;
        save();
        refresh(server, id);
        return true;
    }

    private Formatting parseColor(String color, Formatting fallback) {
        if (color == null) return fallback;
        try {
            Formatting formatting = Formatting.byName(color.toLowerCase(Locale.ROOT));
            return formatting != null && formatting.isColor() ? formatting : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private ArmorStandEntity spawnLine(ServerWorld world, double x, double y, double z, Text text, String boardId) {
        // Invisible armor stands are intentionally used here instead of client-side-only rendering,
        // so this remains a fully server-side Fabric mod with no client dependency.
        ArmorStandEntity stand = new ArmorStandEntity(world, x, y - 1.45D, z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCustomName(text);
        stand.setCustomNameVisible(true);
        stand.addCommandTag("cobbleboard");
        stand.addCommandTag("cobbleboard_" + sanitizeTag(boardId));
        world.spawnEntity(stand);
        return stand;
    }

    private void clearLive(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        lastRenderedText.remove(key);
        List<Entity> entities = liveEntities.remove(key);
        if (entities == null) return;
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) entity.discard();
        }
    }

    /**
     * Removes every loaded entity belonging to one board by its persistent command tag.
     * Unlike liveEntities, this also catches entities that survived a server crash/restart.
     */
    private int cleanupTagged(MinecraftServer server, String boardId) {
        String tag = "cobbleboard_" + sanitizeTag(boardId);
        int removed = 0;
        for (ServerWorld world : server.getWorlds()) {
            try {
                server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withWorld(world),
                        "kill @e[tag=" + tag + "]");
            } catch (Exception e) {
                CobbleBoardMod.LOGGER.warn("Could not clean stale entities for board {} in {}",
                        boardId, world.getRegistryKey().getValue(), e);
            }
        }
        return removed;
    }

    /**
     * Purges all CobbleBoard display entities, including orphaned boards no longer
     * present in cobbleboard-displays.json. Configured boards can then be recreated
     * cleanly with refreshAll().
     */
    public int cleanupAll(MinecraftServer server) {
        liveEntities.clear();
        lastRenderedText.clear();
        int removed = 0;
        for (ServerWorld world : server.getWorlds()) {
            try {
                server.getCommandManager().executeWithPrefix(
                        server.getCommandSource().withWorld(world),
                        "kill @e[tag=cobbleboard]");
            } catch (Exception e) {
                CobbleBoardMod.LOGGER.warn("Could not clean CobbleBoard entities in {}",
                        world.getRegistryKey().getValue(), e);
            }
        }
        return removed;
    }

    private ServerWorld resolveWorld(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) return null;
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        return server.getWorld(key);
    }

    private String sanitizeTag(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
