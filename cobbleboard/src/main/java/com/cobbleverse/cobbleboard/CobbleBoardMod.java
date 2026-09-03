package com.cobbleverse.cobbleboard;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class CobbleBoardMod implements ModInitializer {
    public static final String MOD_ID = "cobbleboard";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final RankingManager RANKINGS = new RankingManager();
    public static final BoardManager BOARDS = new BoardManager(RANKINGS);

    @Override
    public void onInitialize() {
        RANKINGS.load();
        BOARDS.load();

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            RANKINGS.tick(server);
            BOARDS.tick(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // World entities persist across restarts, but liveEntities does not.
            // Purge stale/orphaned CobbleBoard entities first, then rebuild each
            // configured board exactly once.
            int cleanedWorlds = BOARDS.cleanupAll(server);
            if (cleanedWorlds > 0) LOGGER.info("Ran stale CobbleBoard entity cleanup in {} loaded world(s) on startup.", cleanedWorlds);
            BOARDS.refreshAll(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BOARDS.shutdown();
            RANKINGS.save();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("cobbleboard")
                .requires(source -> source.hasPermissionLevel(2))

                .then(CommandManager.literal("track")
                    .then(CommandManager.argument("objective", StringArgumentType.word())
                        .executes(ctx -> {
                            String objective = StringArgumentType.getString(ctx, "objective");
                            boolean ok = RANKINGS.trackObjective(ctx.getSource().getServer(), objective);
                            if (!ok) {
                                ctx.getSource().sendError(Text.literal("Unknown scoreboard objective: " + objective));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(() -> Text.literal("CobbleBoard is now tracking " + objective), false);
                            return 1;
                        })))

                .then(CommandManager.literal("standings")
                    .then(CommandManager.argument("objective", StringArgumentType.word())
                        .executes(ctx -> {
                            String objective = StringArgumentType.getString(ctx, "objective");
                            List<RankingManager.StandingView> standings = RANKINGS.getRanked(objective);
                            if (standings.isEmpty()) {
                                ctx.getSource().sendError(Text.literal("No tracked standings for " + objective));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(() -> Text.literal("--- " + objective + " standings ---"), false);
                            for (int i = 0; i < standings.size(); i++) {
                                int rank = i + 1;
                                RankingManager.StandingView s = standings.get(i);
                                String override = s.manualRank() == null ? "" : " [manual]";
                                ctx.getSource().sendFeedback(() -> Text.literal(rank + ". " + s.player() + " — " + s.score() + override), false);
                            }
                            return standings.size();
                        })))

                .then(CommandManager.literal("rank")
                    .then(CommandManager.argument("objective", StringArgumentType.word())
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .then(CommandManager.argument("position", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    String objective = StringArgumentType.getString(ctx, "objective");
                                    String player = StringArgumentType.getString(ctx, "player");
                                    int position = IntegerArgumentType.getInteger(ctx, "position");
                                    if (!RANKINGS.setManualRank(objective, player, position)) {
                                        ctx.getSource().sendError(Text.literal("Player is not recorded on that board."));
                                        return 0;
                                    }
                                    BOARDS.refreshAll(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + player + " to manual rank #" + position + " on " + objective), true);
                                    return 1;
                                }))
                            .then(CommandManager.literal("auto")
                                .executes(ctx -> {
                                    String objective = StringArgumentType.getString(ctx, "objective");
                                    String player = StringArgumentType.getString(ctx, "player");
                                    if (!RANKINGS.setManualRank(objective, player, null)) {
                                        ctx.getSource().sendError(Text.literal("Player is not recorded on that board."));
                                        return 0;
                                    }
                                    BOARDS.refreshAll(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Returned " + player + " to automatic ranking on " + objective), true);
                                    return 1;
                                })))))

                .then(CommandManager.literal("swap")
                    .then(CommandManager.argument("objective", StringArgumentType.word())
                        .then(CommandManager.argument("playerA", StringArgumentType.word())
                            .then(CommandManager.argument("playerB", StringArgumentType.word())
                                .executes(ctx -> {
                                    String objective = StringArgumentType.getString(ctx, "objective");
                                    String a = StringArgumentType.getString(ctx, "playerA");
                                    String b = StringArgumentType.getString(ctx, "playerB");
                                    if (!RANKINGS.swap(objective, a, b)) {
                                        ctx.getSource().sendError(Text.literal("Could not find both players on that board."));
                                        return 0;
                                    }
                                    BOARDS.refreshAll(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Swapped " + a + " and " + b + " on " + objective), true);
                                    return 1;
                                })))))

                .then(CommandManager.literal("resetoverrides")
                    .then(CommandManager.argument("objective", StringArgumentType.word())
                        .executes(ctx -> {
                            String objective = StringArgumentType.getString(ctx, "objective");
                            if (!RANKINGS.resetAllOverrides(objective)) {
                                ctx.getSource().sendError(Text.literal("Unknown tracked objective: " + objective));
                                return 0;
                            }
                            BOARDS.refreshAll(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.literal("Cleared manual ranking overrides for " + objective), true);
                            return 1;
                        })))

                .then(CommandManager.literal("board")
                    .then(CommandManager.literal("create")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("objective", StringArgumentType.word())
                                .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                    .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                            .executes(ctx -> createBoard(ctx, 10))
                                            .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> createBoard(ctx, IntegerArgumentType.getInteger(ctx, "limit"))))))))))

                    .then(CommandManager.literal("delete")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                if (!BOARDS.delete(ctx.getSource().getServer(), id)) {
                                    ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                    return 0;
                                }
                                ctx.getSource().sendFeedback(() -> Text.literal("Deleted board " + id), true);
                                return 1;
                            })))

                    .then(CommandManager.literal("refresh")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                if (!BOARDS.refresh(ctx.getSource().getServer(), id)) {
                                    ctx.getSource().sendError(Text.literal("Could not refresh board " + id));
                                    return 0;
                                }
                                ctx.getSource().sendFeedback(() -> Text.literal("Refreshed board " + id), false);
                                return 1;
                            })))

                    .then(CommandManager.literal("limit")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    int limit = IntegerArgumentType.getInteger(ctx, "limit");
                                    if (!BOARDS.setLimit(ctx.getSource().getServer(), id, limit)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " limit to " + limit), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("title")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("title", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    String title = StringArgumentType.getString(ctx, "title");
                                    if (!BOARDS.setTitle(ctx.getSource().getServer(), id, title)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Updated title for " + id), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("move")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            String dimension = ctx.getSource().getWorld().getRegistryKey().getValue().toString();
                                            double x = DoubleArgumentType.getDouble(ctx, "x");
                                            double y = DoubleArgumentType.getDouble(ctx, "y");
                                            double z = DoubleArgumentType.getDouble(ctx, "z");
                                            if (!BOARDS.move(ctx.getSource().getServer(), id, dimension, x, y, z)) {
                                                ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                                return 0;
                                            }
                                            ctx.getSource().sendFeedback(() -> Text.literal("Moved board " + id), true);
                                            return 1;
                                        }))))))


                    .then(CommandManager.literal("mode")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("mode", StringArgumentType.word())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    String mode = StringArgumentType.getString(ctx, "mode");
                                    if (!BOARDS.setDisplayMode(ctx.getSource().getServer(), id, mode)) {
                                        ctx.getSource().sendError(Text.literal("Invalid board or mode. Use: panel or stacked."));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " display mode to " + mode), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("scale")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("scale", DoubleArgumentType.doubleArg(0.25D, 5.0D))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    double scale = DoubleArgumentType.getDouble(ctx, "scale");
                                    if (!BOARDS.setBoardScale(ctx.getSource().getServer(), id, scale)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " panel scale to " + scale), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("width")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("width", IntegerArgumentType.integer(80, 1000))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    int width = IntegerArgumentType.getInteger(ctx, "width");
                                    if (!BOARDS.setBoardWidth(ctx.getSource().getServer(), id, width)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " panel width to " + width), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("spacing")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("spacing", DoubleArgumentType.doubleArg(0.05D, 2.0D))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    double spacing = DoubleArgumentType.getDouble(ctx, "spacing");
                                    if (!BOARDS.setSpacing(ctx.getSource().getServer(), id, spacing)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " line spacing to " + spacing), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("titlespacing")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("spacing", DoubleArgumentType.doubleArg(0.05D, 2.0D))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    double spacing = DoubleArgumentType.getDouble(ctx, "spacing");
                                    if (!BOARDS.setTitleSpacing(ctx.getSource().getServer(), id, spacing)) {
                                        ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " title spacing to " + spacing), true);
                                    return 1;
                                }))))

                    .then(CommandManager.literal("color")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .then(CommandManager.argument("target", StringArgumentType.word())
                                .then(CommandManager.argument("color", StringArgumentType.word())
                                    .executes(ctx -> {
                                        String id = StringArgumentType.getString(ctx, "id");
                                        String target = StringArgumentType.getString(ctx, "target");
                                        String color = StringArgumentType.getString(ctx, "color");
                                        if (!BOARDS.setColor(ctx.getSource().getServer(), id, target, color)) {
                                            ctx.getSource().sendError(Text.literal("Invalid board, target, or color. Targets: title, name, score."));
                                            return 0;
                                        }
                                        ctx.getSource().sendFeedback(() -> Text.literal("Set " + id + " " + target + " color to " + color), true);
                                        return 1;
                                    })))))

                    .then(CommandManager.literal("info")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                BoardData.BoardDefinition b = BOARDS.get(id);
                                if (b == null) {
                                    ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                    return 0;
                                }
                                ctx.getSource().sendFeedback(() -> Text.literal(
                                    id + ": objective=" + b.objective +
                                    ", limit=" + b.limit +
                                    ", pos=" + b.x + " " + b.y + " " + b.z +
                                    ", spacing=" + b.lineSpacing +
                                    ", titleSpacing=" + b.titleSpacing +
                                    ", mode=" + b.displayMode +
                                    ", scale=" + b.boardScale +
                                    ", width=" + b.boardWidth +
                                    ", colors(title/name/score)=" + b.titleColor + "/" + b.nameColor + "/" + b.scoreColor
                                ), false);
                                return 1;
                            })))

                    .then(CommandManager.literal("resetstyle")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                            .executes(ctx -> {
                                String id = StringArgumentType.getString(ctx, "id");
                                if (!BOARDS.resetStyle(ctx.getSource().getServer(), id)) {
                                    ctx.getSource().sendError(Text.literal("Unknown board: " + id));
                                    return 0;
                                }
                                ctx.getSource().sendFeedback(() -> Text.literal("Reset style for " + id), true);
                                return 1;
                            })))

                    .then(CommandManager.literal("cleanup")
                        .executes(ctx -> {
                            int cleanedWorlds = BOARDS.cleanupAll(ctx.getSource().getServer());
                            BOARDS.refreshAll(ctx.getSource().getServer());
                            ctx.getSource().sendFeedback(() -> Text.literal(
                                    "Cleaned CobbleBoard display entities in " + cleanedWorlds + " loaded world(s) and rebuilt configured boards."), true);
                            return Math.max(1, cleanedWorlds);
                        }))

                    .then(CommandManager.literal("list")
                        .executes(ctx -> {
                            if (BOARDS.boardIds().isEmpty()) {
                                ctx.getSource().sendFeedback(() -> Text.literal("No CobbleBoard holograms configured."), false);
                                return 0;
                            }
                            ctx.getSource().sendFeedback(() -> Text.literal("CobbleBoard holograms: " + String.join(", ", BOARDS.boardIds())), false);
                            return BOARDS.boardIds().size();
                        }))
                )
            )
        );

        LOGGER.info("CobbleBoard ranking + floating panel engine initialized.");
    }

    private static int createBoard(com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> ctx,
                                   int limit) {
        String id = StringArgumentType.getString(ctx, "id");
        String objective = StringArgumentType.getString(ctx, "objective");
        double x = DoubleArgumentType.getDouble(ctx, "x");
        double y = DoubleArgumentType.getDouble(ctx, "y");
        double z = DoubleArgumentType.getDouble(ctx, "z");
        String dimension = ctx.getSource().getWorld().getRegistryKey().getValue().toString();
        if (!BOARDS.create(ctx.getSource().getServer(), id, objective, dimension, x, y, z, limit)) {
            ctx.getSource().sendError(Text.literal("Could not create board. Check that the ID is unused and objective exists."));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal("Created board " + id + " for " + objective + " (Top " + limit + ")"), true);
        return 1;
    }
}
