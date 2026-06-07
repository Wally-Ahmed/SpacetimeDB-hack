package dev.builders;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The body. Holds no intelligence — it streams game state to SpacetimeDB and
 * executes directives the brain writes back.
 */
public class BuildersPlugin extends JavaPlugin {
    private Stdb stdb;
    private BuilderManager builders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Merge any new config keys into a pre-existing config.yml (e.g. auto-spawn).
        getConfig().addDefault("builders.auto-spawn", 4);
        getConfig().options().copyDefaults(true);
        saveConfig();
        String base = getConfig().getString("stdb.base-url", "http://127.0.0.1:3050");
        String db = getConfig().getString("stdb.database", "builders-rpg");
        stdb = new Stdb(this, base, db);
        builders = new BuilderManager(this, stdb);
        getServer().getPluginManager().registerEvents(new GameListeners(this, stdb), this);

        // push player positions + difficulty every 1s
        getServer().getScheduler().runTaskTimer(this, this::pushPlayers, 20L, 20L);
        // push world clock every 5s
        getServer().getScheduler().runTaskTimer(this, this::pushClock, 40L, 100L);
        // poll the brain's directive queue every 1s (async HTTP; actions hop to main thread)
        getServer().getScheduler().runTaskTimerAsynchronously(this, new DirectivePoller(this, stdb, builders), 60L, 20L);

        // Re-adopt NPCs Citizens restored after a restart, then seed a starter town if empty.
        getServer().getScheduler().runTaskLater(this, () -> {
            builders.resyncFromCitizens();
            int auto = getConfig().getInt("builders.auto-spawn", 0);
            if (auto > 0 && builders.count() == 0 && !Bukkit.getWorlds().isEmpty()) {
                builders.spawnNear(Bukkit.getWorlds().get(0).getSpawnLocation(), auto);
                getLogger().info("Auto-spawned " + auto + " Builders at world spawn.");
            }
        }, 200L);

        getLogger().info("BuildersPlugin enabled -> " + base + " / " + db);
    }

    @Override
    public void onDisable() {
        if (builders != null) builders.despawnAll();
    }

    private void pushPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            stdb.call("update_player_pos", p.getUniqueId().toString(), p.getName(),
                    l.getX(), l.getY(), l.getZ(), p.getWorld().getName(), difficultyOf(p.getWorld()));
        }
        builders.pushPositions();
    }

    private void pushClock() {
        if (Bukkit.getWorlds().isEmpty()) return;
        World w = Bukkit.getWorlds().get(0);
        stdb.call("set_world_clock", w.getTime(), w.getFullTime());
    }

    public static String difficultyOf(World w) {
        switch (w.getDifficulty()) {
            case PEACEFUL:
            case EASY:
                return "easy";
            case HARD:
                return "hard";
            default:
                return "med";
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("builders")) return false;
        if (args.length == 0) {
            sender.sendMessage("§e/builders spawn <n> | accept [id] | decline [id] | reset");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn": {
                int n = args.length > 1 ? parseInt(args[1], 1) : 1;
                Location at = (sender instanceof Player) ? ((Player) sender).getLocation()
                        : Bukkit.getWorlds().get(0).getSpawnLocation();
                int spawned = builders.spawnNear(at, n);
                sender.sendMessage("§aSpawned " + spawned + " Builders.");
                return true;
            }
            case "accept": {
                if (!(sender instanceof Player)) { sender.sendMessage("players only"); return true; }
                Player p = (Player) sender;
                if (args.length > 1) {
                    stdb.call("join_party", p.getUniqueId().toString(), parseInt(args[1], -1));
                } else {
                    stdb.call("accept_any", p.getUniqueId().toString());
                }
                sender.sendMessage("§aYou answered the call!");
                return true;
            }
            case "decline": {
                if (!(sender instanceof Player)) return true;
                Player p = (Player) sender;
                int id = args.length > 1 ? parseInt(args[1], -1) : builders.nearestBuilderId(p.getLocation());
                if (id >= 0) stdb.call("decline_quest", p.getUniqueId().toString(), id);
                sender.sendMessage("§7Maybe another time.");
                return true;
            }
            case "reset": {
                builders.despawnAll();
                sender.sendMessage("§eRemoved all Builders.");
                return true;
            }
            default:
                sender.sendMessage("§e/builders spawn <n> | accept [id] | decline [id] | reset");
                return true;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
