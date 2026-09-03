package com.cobbleverse.cobblebounty;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class BountyCommands {
    private BountyCommands() {}

    public static void register(BountyManager manager) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("bounty")
                        .executes(ctx -> status(ctx.getSource(), manager))
                        .then(literal("submit").executes(ctx -> submit(ctx.getSource(), manager)))
                        .then(literal("leaderboard").executes(ctx -> leaderboard(ctx.getSource(), manager)))
                        .then(literal("admin")
                                .requires(src -> src.hasPermissionLevel(2))
                                .then(literal("reroll").executes(ctx -> {
                                    manager.reroll(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("Bounty rerolled to " + manager.getDisplaySpecies()), true);
                                    return 1;
                                }))
                                .then(literal("set")
                                        .then(argument("species", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String species = StringArgumentType.getString(ctx, "species");
                                                    manager.setSpecies(ctx.getSource().getServer(), species);
                                                    ctx.getSource().sendFeedback(() -> Text.literal("Today's bounty is now " + manager.getDisplaySpecies() + " [" + manager.getDisplayBucket() + "]"), true);
                                                    return 1;
                                                })
                                                .then(argument("bucket", StringArgumentType.word()).executes(ctx -> {
                                                    String species = StringArgumentType.getString(ctx, "species");
                                                    String bucket = StringArgumentType.getString(ctx, "bucket");
                                                    manager.setSpecies(ctx.getSource().getServer(), species, bucket);
                                                    ctx.getSource().sendFeedback(() -> Text.literal("Today's bounty is now " + manager.getDisplaySpecies() + " [" + manager.getDisplayBucket() + "]"), true);
                                                    return 1;
                                                }))))
                                .then(literal("pools").executes(ctx -> {
                                    ctx.getSource().sendFeedback(() -> Text.literal("Bounty pool counts — common: " + manager.getPoolSize("common")
                                            + ", uncommon: " + manager.getPoolSize("uncommon")
                                            + ", rare: " + manager.getPoolSize("rare")
                                            + ", ultra-rare: " + manager.getPoolSize("ultra-rare")), false);
                                    return 1;
                                }))
                                .then(literal("setpasture")
                                        .then(argument("pos", BlockPosArgumentType.blockPos()).executes(ctx -> {
                                            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                                            BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
                                            manager.setPasture(player, pos);
                                            ctx.getSource().sendFeedback(() -> Text.literal("Bounty Pasture set to " + pos.toShortString()), true);
                                            return 1;
                                        })))
                                .then(literal("reload").executes(ctx -> {
                                    manager.reload(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal("CobbleBounty reloaded."), false);
                                    return 1;
                                }))
                        )
        ));
    }

    private static int status(ServerCommandSource source, BountyManager manager) throws CommandSyntaxException {
        manager.ensureToday(source.getServer());
        ServerPlayerEntity player = source.getPlayerOrThrow();
        source.sendFeedback(() -> Text.literal("★ DAILY POKÉMON BOUNTY ★").formatted(Formatting.GOLD, Formatting.BOLD), false);
        source.sendFeedback(() -> Text.literal("Target: ").formatted(Formatting.GRAY)
                .append(Text.literal(manager.getDisplaySpecies()).formatted(Formatting.AQUA, Formatting.BOLD)), false);
        source.sendFeedback(() -> Text.literal("Rarity: ").formatted(Formatting.GRAY)
                .append(Text.literal(manager.getDisplayBucket()).formatted(Formatting.YELLOW)), false);
        source.sendFeedback(() -> Text.literal("Reward: ").formatted(Formatting.GRAY)
                .append(Text.literal(manager.getRewardDescription()).formatted(Formatting.LIGHT_PURPLE)), false);
        source.sendFeedback(() -> Text.literal("Your status: ").formatted(Formatting.GRAY)
                .append(Text.literal(manager.hasCompleted(player) ? "COMPLETED" : "NOT COMPLETED")
                        .formatted(manager.hasCompleted(player) ? Formatting.GREEN : Formatting.YELLOW)), false);
        source.sendFeedback(() -> Text.literal("Lifetime: " + manager.getTotal(player) + "  |  Streak: " + manager.getStreak(player)), false);
        if (manager.getPasture() == null) {
            source.sendFeedback(() -> Text.literal("Bounty Pasture: not configured").formatted(Formatting.RED), false);
        } else {
            BountyState.PastureLocation p = manager.getPasture();
            source.sendFeedback(() -> Text.literal("Submit at pasture: " + p.x + " " + p.y + " " + p.z + " (" + p.dimension + ")").formatted(Formatting.DARK_GRAY), false);
        }
        return 1;
    }

    private static int submit(ServerCommandSource source, BountyManager manager) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BountyManager.SubmitResult result = manager.submit(source.getServer(), player);
        source.sendFeedback(() -> Text.literal(result.message()).formatted(result.success() ? Formatting.GREEN : Formatting.RED), false);
        return result.success() ? 1 : 0;
    }

    private static int leaderboard(ServerCommandSource source, BountyManager manager) {
        source.sendFeedback(() -> Text.literal("★ BOUNTY HUNTERS ★").formatted(Formatting.GOLD, Formatting.BOLD), false);
        int[] rank = {1};
        for (Map.Entry<String, Integer> entry : manager.leaderboard().stream().limit(10).toList()) {
            String name = entry.getKey();
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                ServerPlayerEntity online = source.getServer().getPlayerManager().getPlayer(uuid);
                if (online != null) name = online.getGameProfile().getName();
                else name = entry.getKey().substring(0, 8);
            } catch (Exception ignored) {}
            String line = rank[0]++ + ". " + name + " — " + entry.getValue();
            source.sendFeedback(() -> Text.literal(line), false);
        }
        if (rank[0] == 1) source.sendFeedback(() -> Text.literal("No completed bounties yet.").formatted(Formatting.GRAY), false);
        return 1;
    }
}
