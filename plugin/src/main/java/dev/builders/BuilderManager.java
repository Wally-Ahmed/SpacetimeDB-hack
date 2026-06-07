package dev.builders;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Spawns + tracks the Citizens NPCs that embody Builders. Main-thread only. */
public class BuilderManager {
    private final Plugin plugin;
    private final Stdb stdb;
    private final Set<Integer> spawnedIds = Collections.synchronizedSet(new HashSet<>());
    private int nameCursor = 0;

    // Themed roster (mystical stone-age / redstone artisans).
    private static final String[][] ROSTER = {
            {"Thrain", "a gruff dwarven mason who loves redstone"},
            {"Eldra", "a wise rune-keeper who reads the deep stone"},
            {"Borin", "a boisterous smith forging iron dreams"},
            {"Mira", "a curious tinkerer of redstone contraptions"},
            {"Kael", "a quiet stoneshaper who speaks in proverbs"},
            {"Vyssa", "an adventurous cartographer of caverns"},
            {"Dorin", "a hearty brewer and keeper of the hearth"},
            {"Lyra", "a stargazing enchantress of old magics"},
    };

    public BuilderManager(Plugin plugin, Stdb stdb) {
        this.plugin = plugin;
        this.stdb = stdb;
    }

    private EntityType entityType() {
        try {
            return EntityType.valueOf(plugin.getConfig().getString("builders.entity-type", "VILLAGER").toUpperCase());
        } catch (Exception e) {
            return EntityType.VILLAGER;
        }
    }

    public int spawnNear(Location center, int n) {
        int count = 0;
        for (int i = 0; i < n; i++) {
            String[] who = ROSTER[nameCursor % ROSTER.length];
            nameCursor++;
            Location loc = center.clone().add(Math.random() * 8 - 4, 0, Math.random() * 8 - 4);
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(entityType(), who[0]);
            if (!npc.spawn(loc)) {
                plugin.getLogger().warning("failed to spawn NPC " + who[0]);
                continue;
            }
            npc.setProtected(true);
            int id = npc.getId();
            spawnedIds.add(id);
            stdb.call("upsert_builder", id, who[0], who[1], townFor(loc.getWorld()),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getWorld().getName());
            count++;
        }
        return count;
    }

    private static String townFor(World w) {
        if (w.getEnvironment() == World.Environment.NETHER) return "Emberhold";
        if (w.getEnvironment() == World.Environment.THE_END) return "Voidreach";
        return "Stonehollow";
    }

    public void pushPositions() {
        synchronized (spawnedIds) {
            for (int id : spawnedIds) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                if (npc != null && npc.isSpawned()) {
                    Location l = npc.getEntity().getLocation();
                    stdb.call("update_builder_pos", id, l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
                }
            }
        }
    }

    public NPC byId(int id) {
        return CitizensAPI.getNPCRegistry().getById(id);
    }

    public int nearestBuilderId(Location loc) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        synchronized (spawnedIds) {
            for (int id : spawnedIds) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                if (npc != null && npc.isSpawned() && npc.getEntity().getWorld().equals(loc.getWorld())) {
                    double d = npc.getEntity().getLocation().distanceSquared(loc);
                    if (d < bd) { bd = d; best = id; }
                }
            }
        }
        return best;
    }

    /** After a restart, re-adopt any NPCs Citizens restored and re-register them in STDB. */
    public void resyncFromCitizens() {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (npc == null) continue;
            spawnedIds.add(npc.getId());
            Location l = npc.isSpawned() ? npc.getEntity().getLocation() : npc.getStoredLocation();
            if (l == null || l.getWorld() == null) continue;
            stdb.call("upsert_builder", npc.getId(), npc.getName(), personaFor(npc.getName()),
                    townFor(l.getWorld()), l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
        }
    }

    public int count() {
        return spawnedIds.size();
    }

    private static String personaFor(String name) {
        for (String[] w : ROSTER) if (w[0].equalsIgnoreCase(name)) return w[1];
        return "a Builder of the old craft";
    }

    public void despawnAll() {
        synchronized (spawnedIds) {
            for (int id : spawnedIds) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(id);
                if (npc != null) npc.destroy();
                stdb.call("remove_builder", id);
            }
            spawnedIds.clear();
        }
    }
}
