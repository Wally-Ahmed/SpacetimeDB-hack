package dev.builders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes the admin "Director" actions that have cleared the confirm-gate.
 *
 * <p>This is the EXECUTION side of the Director feature (design §5.1). The Director
 * loop in the worker only ever writes {@code admin_action.status='pending'}; the
 * dashboard flips it to {@code 'confirmed'}. There is no code path from pending to
 * execution — this poller acts <b>only</b> on rows already at {@code 'confirmed'},
 * which is what makes the confirm-gate structural rather than advisory.
 *
 * <p>A {@link Runnable} the integrator registers on a repeating ASYNC timer (it does
 * blocking HTTP via {@link Stdb#sql}). Each cycle mirrors {@link DirectivePoller} /
 * {@link BuildSystem} exactly:
 * <ol>
 *   <li>Poll {@code admin_action WHERE status = 'confirmed'} off the main thread,
 *       parsing SATS-JSON with the same null-guarded accessors.</li>
 *   <li>A {@link #seen} set of ids so each action executes once even before STDB
 *       reflects the {@code begin_action} status flip (same trick as DirectivePoller).</li>
 *   <li>For each new action: claim it with {@code begin_action(id)}, then hop to the
 *       main thread to execute by {@code kind}; on success
 *       {@code finish_action(id,"executed")}, on any exception
 *       {@code finish_action(id,"failed")}.</li>
 * </ol>
 *
 * <p>Disaster kinds don't run effects here — they call {@code start_scenario}, and
 * {@link ScenarioEngine} ticks the live scenario. Everything is clamped (design §5.3)
 * and every Bukkit/NPC touch happens on the main thread, wrapped in
 * {@code try/catch(Throwable)}.
 */
public class AdminExecutor implements Runnable {

    /** The four disaster kinds that spin up a live {@code scenario} for ScenarioEngine. */
    private static final Set<String> DISASTER_KINDS = new HashSet<>();
    static {
        DISASTER_KINDS.add("zombie_outbreak");
        DISASTER_KINDS.add("wildfire");
        DISASTER_KINDS.add("storm_flood");
        DISASTER_KINDS.add("meteor");
    }

    // --- §5.3 clamps -------------------------------------------------------
    private static final double RADIUS_MIN = 4.0;
    private static final double RADIUS_MAX = 64.0;
    private static final int DURATION_MIN = 1;   // minutes
    private static final int DURATION_MAX = 15;  // minutes
    private static final long TICKS_PER_MINUTE = 1200L; // 60s * 20 ticks
    private static final int SPAWN_MIN = 1;
    private static final int SPAWN_MAX = 8;

    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    /** Action ids already dispatched, so a confirmed row only fires once. */
    private final Set<Long> seen = Collections.synchronizedSet(new HashSet<>());

    public AdminExecutor(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    // ------------------------------------------------------------------ poll

    @Override
    public void run() {
        try {
            List<Map<String, JsonElement>> rows = stdb.sql(
                    "SELECT id, kind, summary, params_json, area_x, area_y, area_z, radius, world "
                            + "FROM admin_action WHERE status = 'confirmed'");
            if (rows.isEmpty()) return;

            // Snapshot each new confirmed action into a plain value object so the
            // main-thread task never touches JSON (mirrors BuildSystem).
            List<Action> fresh = new ArrayList<>();
            for (Map<String, JsonElement> row : rows) {
                if (row.get("id") == null) continue;
                long id = asLong(row, "id");
                if (!seen.add(id)) continue; // already dispatched this one
                Action a = new Action();
                a.id = id;
                a.kind = asStr(row, "kind");
                a.summary = asStr(row, "summary");
                a.paramsJson = asStr(row, "params_json");
                a.areaX = asDouble(row, "area_x");
                a.areaY = asDouble(row, "area_y");
                a.areaZ = asDouble(row, "area_z");
                a.radius = asDouble(row, "radius");
                a.world = asStr(row, "world");
                fresh.add(a);
            }
            if (fresh.isEmpty()) return;

            for (Action a : fresh) {
                plugin.getLogger().info("[director] executing action " + a.id + " (" + a.kind + ")");
                // Claim the row off-thread (fire-and-forget), then execute on the main thread.
                stdb.call("begin_action", a.id);
                Bukkit.getScheduler().runTask(plugin, () -> execute(a));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[director] cycle error: " + t);
        }
    }

    // -------------------------------------------------------------- main exec

    /** Main thread: dispatch one confirmed action by kind, reporting the result back. */
    private void execute(Action a) {
        try {
            JsonObject params = parseParams(a.paramsJson);

            if (DISASTER_KINDS.contains(a.kind)) {
                startScenario(a, params);
            } else if ("set_time".equals(a.kind)) {
                doSetTime(a, params);
            } else if ("set_weather".equals(a.kind)) {
                doSetWeather(a, params);
            } else if ("spawn_builders".equals(a.kind)) {
                doSpawnBuilders(a, params);
            } else if ("despawn_builders".equals(a.kind)) {
                doDespawnBuilders(a, params);
            } else {
                plugin.getLogger().warning("[director] unknown action kind '" + a.kind + "' (id " + a.id + ")");
                stdb.call("finish_action", a.id, "failed");
                return;
            }

            stdb.call("finish_action", a.id, "executed");
        } catch (Throwable t) {
            plugin.getLogger().warning("[director] action " + a.id + " (" + a.kind + ") failed: " + t);
            try { stdb.call("finish_action", a.id, "failed"); } catch (Throwable ignored) {}
        }
    }

    // ---- disaster: hand off to ScenarioEngine via start_scenario ----------

    /**
     * Compute clamped {@code total_waves}/{@code ends_full_time}/intensity from params
     * and start a live scenario. ScenarioEngine does the actual world effects.
     */
    private void startScenario(Action a, JsonObject params) {
        World world = resolveWorld(a.world);
        if (world == null) {
            throw new IllegalStateException("no world available for scenario");
        }

        double radius = clampRadius(a.radius);

        // Disasters use different param names for the same "how strong" idea.
        // zombie_outbreak/wildfire/storm_flood → intensity|severity; meteor → rate.
        String level = firstNonEmpty(
                str(params, "intensity", ""),
                str(params, "severity", ""),
                str(params, "rate", ""));
        int intensityInt = levelToIntensity(level);   // 0/1/2
        int totalWaves = levelToWaves(level);          // 2/3/5

        int minutes = clampDuration((int) Math.round(num(params, "duration_minutes", 3)));
        long endsFullTime = world.getFullTime() + (long) minutes * TICKS_PER_MINUTE;

        stdb.call("start_scenario", a.kind, a.areaX, a.areaY, a.areaZ, radius,
                world.getName(), intensityInt, totalWaves, endsFullTime, a.paramsJson);
        plugin.getLogger().info("[director] scenario " + a.kind + " r=" + radius
                + " intensity=" + intensityInt + " waves=" + totalWaves + " for " + minutes + "m");
    }

    // ---- world op: set_time ----------------------------------------------

    private void doSetTime(Action a, JsonObject params) {
        World world = resolveWorld(a.world);
        if (world == null) throw new IllegalStateException("no world for set_time");

        String value = str(params, "value", "day").trim().toLowerCase();
        long ticks;
        switch (value) {
            case "day":      ticks = 1000L;  break;
            case "noon":     ticks = 6000L;  break;
            case "night":    ticks = 13000L; break;
            case "midnight": ticks = 18000L; break;
            default:
                // raw 0..24000 — accept either the string param or a numeric param.
                long raw = (long) num(params, "value", 1000);
                try { raw = Long.parseLong(value); } catch (NumberFormatException ignored) {}
                ticks = ((raw % 24000L) + 24000L) % 24000L; // wrap into [0,24000)
        }
        world.setTime(ticks);
    }

    // ---- world op: set_weather -------------------------------------------

    private void doSetWeather(Action a, JsonObject params) {
        World world = resolveWorld(a.world);
        if (world == null) throw new IllegalStateException("no world for set_weather");

        String type = str(params, "type", "clear").trim().toLowerCase();
        switch (type) {
            case "rain":
                world.setStorm(true);
                world.setThundering(false);
                break;
            case "thunder":
                world.setStorm(true);
                world.setThundering(true);
                break;
            case "clear":
            default:
                world.setStorm(false);
                world.setThundering(false);
                break;
        }
        int minutes = clampDuration((int) Math.round(num(params, "duration_minutes", 5)));
        int durTicks = (int) Math.min(Integer.MAX_VALUE, (long) minutes * TICKS_PER_MINUTE);
        world.setWeatherDuration(durTicks);
        // Keep thunder duration in step when we asked for a storm.
        if ("thunder".equals(type) || "rain".equals(type)) {
            try { world.setThunderDuration(durTicks); } catch (Throwable ignored) {}
        }
    }

    // ---- world op: spawn_builders ----------------------------------------

    private void doSpawnBuilders(Action a, JsonObject params) {
        int count = clamp((int) Math.round(num(params, "count", 1)), SPAWN_MIN, SPAWN_MAX);
        Location loc = resolveSpawnLocation(a, params);
        if (loc == null) throw new IllegalStateException("could not resolve a spawn location");
        builders.spawnNear(loc, count);
        plugin.getLogger().info("[director] spawned " + count + " builders near " + describe(loc));
    }

    // ---- world op: despawn_builders --------------------------------------

    private void doDespawnBuilders(Action a, JsonObject params) {
        String scope = str(params, "scope", "all").trim().toLowerCase();
        if ("all".equals(scope)) {
            builders.despawnAll();
            plugin.getLogger().info("[director] despawned all builders");
        } else {
            // "in-area" is an explicit v1 no-op (design §9); BuilderManager has no
            // selective despawn and P1 must not add one. Report success regardless.
            plugin.getLogger().info("[director] despawn scope '" + scope + "' is a no-op for v1");
        }
    }

    // ---- location resolution --------------------------------------------

    /**
     * Resolve where to drop new Builders, in priority order: a named player from
     * params → the action's area coords (if non-zero) → the first online player →
     * the world spawn. Always returns a Location in a valid world, or null only if
     * the server has no loaded world at all.
     */
    private Location resolveSpawnLocation(Action a, JsonObject params) {
        // 1) near a named player.
        String playerName = firstNonEmpty(
                str(params, "near", ""),
                str(params, "player", ""),
                str(params, "near_player", ""),
                str(params, "location", ""));
        if (!playerName.isEmpty() && !isLocationKeyword(playerName)) {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) p = Bukkit.getPlayer(playerName); // fuzzy fallback
            if (p != null && p.isOnline()) return p.getLocation();
        }

        // 2) explicit area coordinates (only if they actually carry a position).
        if (a.areaX != 0 || a.areaY != 0 || a.areaZ != 0) {
            World w = resolveWorld(a.world);
            if (w != null) return new Location(w, a.areaX, a.areaY, a.areaZ);
        }

        // 3) first online player.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) return p.getLocation();
        }

        // 4) world spawn as the last resort.
        World w = resolveWorld(a.world);
        return w != null ? w.getSpawnLocation() : null;
    }

    private static boolean isLocationKeyword(String s) {
        String v = s.trim().toLowerCase();
        return v.equals("spawn") || v.equals("coords") || v.equals("here") || v.equals("near-player");
    }

    // ---- shared helpers --------------------------------------------------

    /** Resolve a world by name, falling back to the first loaded world. */
    private World resolveWorld(String name) {
        if (name != null && !name.isEmpty()) {
            World w = Bukkit.getWorld(name);
            if (w != null) return w;
        }
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }

    private static double clampRadius(double r) {
        if (r < RADIUS_MIN) return RADIUS_MIN;
        if (r > RADIUS_MAX) return RADIUS_MAX;
        return r;
    }

    private static int clampDuration(int minutes) {
        return clamp(minutes, DURATION_MIN, DURATION_MAX);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** low|med|high → 0/1/2; anything else (incl. "medium") defaults sensibly to med=1. */
    private static int levelToIntensity(String level) {
        String v = level == null ? "" : level.trim().toLowerCase();
        if (v.startsWith("low")) return 0;
        if (v.startsWith("high")) return 2;
        return 1; // med / medium / unknown
    }

    /** low 2 / med 3 / high 5 waves (design §5.3). */
    private static int levelToWaves(String level) {
        switch (levelToIntensity(level)) {
            case 0:  return 2;
            case 2:  return 5;
            default: return 3;
        }
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String describe(Location l) {
        if (l == null) return "<null>";
        return "(" + (int) l.getX() + "," + (int) l.getY() + "," + (int) l.getZ() + ") "
                + (l.getWorld() != null ? l.getWorld().getName() : "?");
    }

    // ---- params parsing (Gson) ------------------------------------------
    // params_json is author-controlled free-form JSON; everything is defensive so a
    // malformed/missing field can never crash an execution.

    private JsonObject parseParams(String json) {
        if (json == null || json.trim().isEmpty()) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Throwable t) {
            plugin.getLogger().warning("[director] bad params_json, using defaults: " + t);
            return new JsonObject();
        }
    }

    /** Read a string param with a default; tolerates numbers/bools too. */
    private static String str(JsonObject o, String key, String def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            JsonElement e = o.get(key);
            if (e.isJsonPrimitive()) return e.getAsString();
            return def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** Read a numeric param with a default; tolerates numeric strings. */
    private static double num(JsonObject o, String key, double def) {
        try {
            if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
            JsonElement e = o.get(key);
            if (!e.isJsonPrimitive()) return def;
            try { return e.getAsDouble(); }
            catch (NumberFormatException nfe) {
                return Double.parseDouble(e.getAsString().trim());
            }
        } catch (Throwable t) {
            return def;
        }
    }

    // ---- value type ------------------------------------------------------

    /** Plain snapshot of one admin_action row (no JSON on the main thread). */
    private static final class Action {
        long id;
        String kind;
        String summary;
        String paramsJson;
        double areaX, areaY, areaZ, radius;
        String world;
    }

    // ---- SATS row accessors (mirror DirectivePoller/BuildSystem) ----------

    private static String asStr(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long asLong(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    private static double asDouble(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? 0.0 : e.getAsDouble();
    }

    // ===========================================================================
    // INTEGRATION (for the Phase-2 owner — nothing here is auto-wired)
    // ===========================================================================
    //
    // Constructor:  new AdminExecutor(BuildersPlugin plugin, Stdb stdb, BuilderManager builders)
    //
    // run() does blocking Stdb.sql off-thread (admin_action WHERE status='confirmed'),
    // then hops to the main thread (Bukkit.getScheduler().runTask) for all world/NPC
    // access — the same async->main pattern as DirectivePoller / BuildSystem. The whole
    // poll body is wrapped in try/catch(Throwable); each action is claimed with
    // begin_action and reported with finish_action(executed|failed).
    //
    // Register on a repeating ASYNC timer in BuildersPlugin#onEnable, after the
    // Builder-Life timers (design §5.6). Suggested cadence — 1s, after a 5s warmup:
    //
    //     getServer().getScheduler().runTaskTimerAsynchronously(
    //             this, new AdminExecutor(this, stdb, builders), 100L, 20L);
    //
    // Pair it with ScenarioEngine (which ticks the live disasters this class starts):
    //
    //     getServer().getScheduler().runTaskTimerAsynchronously(
    //             this, new ScenarioEngine(this, stdb, builders), 120L, 20L);
    //
    // No new BuilderManager methods are required — this class only calls existing
    // BuilderManager#spawnNear(Location,int) and BuilderManager#despawnAll().
}
