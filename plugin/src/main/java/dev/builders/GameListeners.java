package dev.builders;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;

/** Turns Minecraft events into SpacetimeDB writes. */
public class GameListeners implements Listener {
    private final Plugin plugin;
    private final Stdb stdb;

    public GameListeners(Plugin plugin, Stdb stdb) {
        this.plugin = plugin;
        this.stdb = stdb;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String uuid = p.getUniqueId().toString();
        stdb.call("register_player", uuid, p.getName(), BuildersPlugin.difficultyOf(p.getWorld()));
        // sync already-completed advancements so the planner knows the player's progress
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            String key = adv.getKey().toString();
            if (key.startsWith("minecraft:recipes/")) continue;
            if (p.getAdvancementProgress(adv).isDone()) {
                stdb.call("set_advancement", uuid, key, true);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stdb.call("set_player_offline", e.getPlayer().getUniqueId().toString());
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        String key = e.getAdvancement().getKey().toString();
        if (key.startsWith("minecraft:recipes/")) return;
        stdb.call("set_advancement", e.getPlayer().getUniqueId().toString(), key, true);
    }

    /** Right-click a Builder to accept any pending quest offer from them. */
    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent e) {
        Player p = e.getClicker();
        stdb.call("join_party", p.getUniqueId().toString(), e.getNPC().getId());
    }
}
