package com.cobbleverse.cobblebounty;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobbleBountyMod implements ModInitializer {
    public static final String MOD_ID = "cobblebounty";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final BountyManager MANAGER = new BountyManager();
    private long ticks = 0;

    @Override
    public void onInitialize() {
        MANAGER.load();
        BountyCommands.register(MANAGER);

        // Mark a caught Pokemon with the unique id of the bounty that was active at capture time.
        // This is server-side persistent Pokemon data and survives PC/pasture moves and restarts.
        CobblemonEvents.POKEMON_CAPTURED.subscribe(
                Priority.NORMAL,
                (java.util.function.Consumer<com.cobblemon.mod.common.api.events.pokemon.PokemonCapturedEvent>) event ->
                        MANAGER.recordCapture(event.getPokemon())
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MANAGER.ensureToday(server);
            MANAGER.syncAllScoreboards(server);
            LOGGER.info("CobbleBounty ready. Today's bounty: {} [{}]", MANAGER.getDisplaySpecies(), MANAGER.getDisplayBucket());
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            MANAGER.ensureToday(server);
            MANAGER.syncScoreboard(server, handler.player);

            if (MANAGER.isDailyAnnouncementEnabled() && MANAGER.shouldSendDailyAnnouncement(handler.player)) {
                handler.player.sendMessage(
                        Text.literal("★ A new Pokémon Bounty is available! Use /bounty for details.")
                                .formatted(Formatting.GOLD),
                        false
                );
                MANAGER.markDailyAnnouncementSent(handler.player);
            }
        });

        // Date rollover check once per minute, not every tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            if (ticks % 1200L == 0L) MANAGER.ensureToday(server);
        });
    }
}
