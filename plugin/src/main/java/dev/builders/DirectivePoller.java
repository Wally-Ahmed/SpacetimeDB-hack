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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Polls the brain's command queue (story_directive) once a second on an async
 * thread, then hops to the main thread to act (walk the NPC over, speak, offer
 * the quest). An 8-second teleport fallback guarantees the NPC arrives.
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
                final NPC fnpc = npc;
                final Player fp = player;
                fnpc.getNavigator().setTarget(fp.getLocation());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (fnpc.isSpawned() && fp.isOnline()
                            && fnpc.getEntity().getWorld().equals(fp.getWorld())
                            && fnpc.getEntity().getLocation().distanceSquared(fp.getLocation()) > 9.0) {
                        Location near = fp.getLocation().clone().add(fp.getLocation().getDirection().multiply(-2));
                        near.setY(fp.getLocation().getY());
                        fnpc.getEntity().teleport(near);
                    }
                }, 160L); // 8s
            }
            if (player != null) {
                player.sendMessage(Component.text("[" + npcName + "] ", NamedTextColor.GOLD)
                        .append(Component.text(text, NamedTextColor.WHITE)));
                offerButtons(player, builderId);
            }
        } else if ("say".equals(kind)) {
            if (player != null) {
                player.sendMessage(Component.text("[" + npcName + "] ", NamedTextColor.GOLD)
                        .append(Component.text(text, NamedTextColor.WHITE)));
            }
        }
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
