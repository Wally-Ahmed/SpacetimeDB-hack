package dev.builders;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a player talk to a nearby Builder just by chatting — proximity-gated.
 *
 * <p>When a player chats in-game, if the nearest spawned Builder is within
 * {@link #TALK_RADIUS} blocks (same world), we forward the message to the brain
 * via {@code player_say_to_builder(builder_id, player_uuid, player_name, text)}.
 * The worker chat loop generates an in-character reply and the Builder answers
 * through the existing "say" directive path ({@link DirectivePoller}) — this
 * listener does NOT handle the reply.
 *
 * <p>Normal chat is never cancelled: this is purely additive. Listening with
 * {@code ignoreCancelled = true} so we skip messages other plugins suppressed.
 *
 * <p>INTEGRATION: register in {@code BuildersPlugin.onEnable}:
 * <pre>
 *   getServer().getPluginManager().registerEvents(
 *       new BuilderChatListener(this, stdb, builders), this);
 * </pre>
 */
public class BuilderChatListener implements Listener {
    /** Max distance (blocks) between a player and a Builder for chat to reach it. */
    private static final double TALK_RADIUS = 12.0;
    /** Per-player cooldown (ms) so we don't spam the brain with rapid messages. */
    private static final long COOLDOWN_MS = 2500L;

    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    /** Last time (epoch ms) each player successfully talked to a Builder. */
    private final ConcurrentHashMap<UUID, Long> lastTalk = new ConcurrentHashMap<>();

    public BuilderChatListener(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    /**
     * AsyncChatEvent fires off the main thread, so we may only read the message
     * and player identity here. World/entity lookups (nearest Builder, distance)
     * are deferred to the main thread. We do NOT cancel the event.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final String name = player.getName();
        // Adventure Component -> plain text.
        final String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Skip empty or single-character messages — not worth a round-trip.
        if (text.length() < 2) return;

        // Rate-limit per player UUID.
        long now = System.currentTimeMillis();
        Long last = lastTalk.get(uuid);
        if (last != null && now - last < COOLDOWN_MS) return;

        // Hop to the main thread before touching any world/entity state.
        Bukkit.getScheduler().runTask(plugin, () -> {
            int id = builders.nearestBuilderId(player.getLocation());
            if (id < 0) return;

            NPC npc = builders.byId(id);
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return;

            Location npcLoc = npc.getEntity().getLocation();
            Location playerLoc = player.getLocation();
            // Must be in the same world to measure distance.
            if (npcLoc.getWorld() == null || !npcLoc.getWorld().equals(playerLoc.getWorld())) return;

            // Proximity gate.
            if (npcLoc.distanceSquared(playerLoc) > TALK_RADIUS * TALK_RADIUS) return;

            stdb.call("player_say_to_builder", id, uuid.toString(), name, text);
            lastTalk.put(uuid, System.currentTimeMillis());
        });
    }
}
