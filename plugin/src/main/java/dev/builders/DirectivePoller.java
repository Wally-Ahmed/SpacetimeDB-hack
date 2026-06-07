package dev.builders;

import com.google.gson.JsonElement;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Polls the brain's command queue (story_directive) once a second on an async
 * thread, then hops to the main thread to act. For a recruitment, the Builder
 * physically WALKS to the player and only offers the quest once it ARRIVES
 * (an 8-second teleport fallback guarantees it gets there) — you never get a
 * quest out of thin air; a Builder always comes to you first.
 */
public class DirectivePoller implements Runnable {
    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;
    private final Set<Long> seen = Collections.synchronizedSet(new HashSet<>());

    public DirectivePoller(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    @Override
    public void run() {
      try {
        List<Map<String, JsonElement>> rows = stdb.sql(
                "SELECT id, kind, builder_id, player_uuid, text FROM story_directive WHERE consumed = false");
        if (!rows.isEmpty()) {
            plugin.getLogger().info("[poller] " + rows.size() + " new directive(s)");
        }
        for (Map<String, JsonElement> row : rows) {
            if (row.get("id") == null) continue;
            long id = row.get("id").getAsLong();
            if (!seen.add(id)) continue; // already handled this one
            String kind = str(row, "kind");
            plugin.getLogger().info("[poller] handling directive id=" + id + " kind=" + kind);
            int builderId = row.get("builder_id") != null ? row.get("builder_id").getAsInt() : -1;
            String uuid = str(row, "player_uuid");
            String text = str(row, "text");
            Bukkit.getScheduler().runTask(plugin, () -> handle(kind, builderId, uuid, text));
            stdb.call("consume_directive", id);
        }
      } catch (Throwable t) {
        plugin.getLogger().warning("[poller] cycle error: " + t);
      }
    }

    private void handle(String kind, int builderId, String uuidStr, String text) {
        Player player = null;
        try { player = Bukkit.getPlayer(UUID.fromString(uuidStr)); } catch (Exception ignored) {}
        NPC npc = builders.byId(builderId);
        String npcName = npc != null ? npc.getName() : "Builder";

        if ("approach_player".equals(kind)) {
            if (npc != null && npc.isSpawned() && player != null) {
                walkUpThenOffer(npc, player, builderId, npcName, text);
            } else if (player != null) {
                // No live NPC to send (rare) — still deliver the offer so the recruit isn't lost.
                sendOffer(player, builderId, npcName, text);
            }
        } else if ("say".equals(kind)) {
            if (player != null) {
                player.sendMessage(Component.text("[" + npcName + "] ", NamedTextColor.GOLD)
                        .append(Component.text(text, NamedTextColor.WHITE)));
            }
        }
    }

    /**
     * Walk the Builder to the player; present the quest offer only once it has
     * actually arrived (within ~3 blocks). If pathing can't make it within 8s,
     * teleport beside the player and then offer. The offer is never sent before
     * the Builder reaches you.
     */
    private void walkUpThenOffer(NPC npc, Player player, int builderId, String npcName, String text) {
        npc.getNavigator().setTarget(player, false); // follow the (moving) player
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!player.isOnline() || !npc.isSpawned()) { cancel(); return; }
                boolean sameWorld = npc.getEntity().getWorld().equals(player.getWorld());
                double d2 = sameWorld
                        ? npc.getEntity().getLocation().distanceSquared(player.getLocation())
                        : Double.MAX_VALUE;

                // Arrived → face the player and make the offer.
                if (sameWorld && d2 <= 9.0) {
                    npc.faceLocation(player.getLocation());
                    sendOffer(player, builderId, npcName, text);
                    cancel();
                    return;
                }

                ticks += 10;

                // 8s teleport fallback: arrive beside the player, then offer.
                if (ticks >= 160 && sameWorld) {
                    Location near = player.getLocation().clone()
                            .add(player.getLocation().getDirection().multiply(-2));
                    near.setY(player.getLocation().getY());
                    npc.getEntity().teleport(near);
                    npc.faceLocation(player.getLocation());
                    sendOffer(player, builderId, npcName, text);
                    cancel();
                    return;
                }

                // Absolute cap (e.g. different world): don't silently lose the recruit.
                if (ticks >= 240) {
                    sendOffer(player, builderId, npcName, text);
                    cancel();
                    return;
                }

                // Keep heading toward the player as they move.
                if (sameWorld) npc.getNavigator().setTarget(player, false);
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    private void sendOffer(Player player, int builderId, String npcName, String text) {
        player.sendMessage(Component.text("[" + npcName + "] ", NamedTextColor.GOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
        offerButtons(player, builderId);
    }

    private void offerButtons(Player player, int builderId) {
        Component c = Component.text("    ")
                .append(Component.text("[ Accept the quest ]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/builders accept " + builderId))
                        .hoverEvent(HoverEvent.showText(Component.text("Join this Builder's party"))))
                .append(Component.text("   "))
                .append(Component.text("[ Decline ]", NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/builders decline " + builderId)));
        player.sendMessage(c);
    }

    private static String str(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
