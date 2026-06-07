package dev.builders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticks every live disaster {@code scenario} and drives its world effects plus the
 * area-scoped, personality-flavored Builder warnings (design §5.2).
 *
 * <p>A {@link Runnable} the integrator registers on a repeating ASYNC timer (~1s; it
 * does blocking HTTP via {@link Stdb#sql}). Each cycle mirrors {@link BuildSystem}:
 * <ol>
 *   <li>Poll {@code scenario WHERE status = 'active'} and the full {@code builder}
 *       snapshot off the main thread, into plain value objects.</li>
 *   <li>Hop to the main thread for ALL world/NPC access. Per active scenario:
 *     <ul>
 *       <li><b>auto-end</b> when {@code world.getFullTime() >= ends_full_time}
 *           ({@code end_scenario} + light cleanup);</li>
 *       <li><b>effects</b>, paced by in-memory maps (one pulse/~2s; outbreak waves
 *           /~6s): zombie waves / wildfire ignitions / storm lightning / meteor
 *           falling blocks — all clamped (§5.3) and only in loaded chunks;</li>
 *       <li><b>warnings</b>: only Builders within {@code radius} of the centre warn
 *           nearby players (chat + title + soft sound) and post to the dashboard via
 *           {@code post_chat}; Builders outside the radius say nothing;</li>
 *       <li><b>light behavior</b>: {@code Personalities.behavior(personality)} →
 *           fight (set state) / flee (navigate away) / neutral (warn only).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Coupling is SpacetimeDB-only plus the static {@link Personalities} helper (owned
 * by Agent P2; coded against the design §5.4 contract). The only NPC collaborator
 * used is the existing {@link BuilderManager#byId(int)} — no BuilderManager edits.
 *
 * <p>All effect counts are clamped, every Bukkit call is null-/exception-guarded, and
 * the whole cycle is wrapped in {@code try/catch(Throwable)}.
 */
public class ScenarioEngine implements Runnable {

    // --- §5.3 clamps -------------------------------------------------------
    private static final double RADIUS_MIN = 4.0;
    private static final double RADIUS_MAX = 64.0;
    private static final int MOB_TOTAL_CAP = 60;        // hostiles per scenario, lifetime
    private static final int WILDFIRE_PER_PULSE = 6;    // FIRE blocks per pulse
    private static final int LIGHTNING_PER_PULSE = 3;   // strikes per pulse
    private static final int METEOR_PER_PULSE = 3;      // falling blocks per pulse
    private static final float METEOR_POWER = 2.0f;     // explosion power cap
    private static final int FLOOD_PER_PULSE_CAP = 8;   // water cells raised per pulse (guarded)

    // --- pacing (real-time, System.currentTimeMillis) ----------------------
    private static final long PULSE_MS = 2000L;         // generic effect pulse
    private static final long WAVE_MS = 6000L;          // outbreak wave interval
    private static final long WARN_MS = 12000L;         // per-builder warning throttle

    // --- ranges ------------------------------------------------------------
    private static final double WARN_PLAYER_RADIUS = 32.0;     // builder → players warned
    private static final double FLEE_DISTANCE = 16.0;          // how far a fleeing NPC runs
    private static final int METEOR_SPAWN_HEIGHT = 30;         // blocks above impact point

    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    /** scenarioId -> last generic effect pulse time (ms). */
    private final Map<Long, Long> lastPulse = Collections.synchronizedMap(new HashMap<>());
    /** scenarioId -> last zombie wave time (ms). */
    private final Map<Long, Long> lastWave = Collections.synchronizedMap(new HashMap<>());
    /** scenarioId -> total hostiles spawned so far (lifetime cap). */
    private final Map<Long, Integer> spawnedCount = Collections.synchronizedMap(new HashMap<>());
    /** "scenarioId|npcId" -> last warning time (ms), so a Builder warns ~every 12s. */
    private final Map<String, Long> lastWarn = Collections.synchronizedMap(new HashMap<>());

    public ScenarioEngine(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    // ------------------------------------------------------------------ poll

    @Override
    public void run() {
        try {
            List<Map<String, JsonElement>> scRows = stdb.sql(
                    "SELECT id, kind, status, area_x, area_y, area_z, radius, world, intensity, "
                            + "wave, total_waves, ends_full_time, params_json "
                            + "FROM scenario WHERE status = 'active'");
            if (scRows.isEmpty()) return;

            List<Scenario> scenarios = new ArrayList<>(scRows.size());
            for (Map<String, JsonElement> row : scRows) {
                if (row.get("id") == null) continue;
                Scenario s = new Scenario();
                s.id = asLong(row, "id");
                s.kind = asStr(row, "kind");
                s.x = asDouble(row, "area_x");
                s.y = asDouble(row, "area_y");
                s.z = asDouble(row, "area_z");
                s.radius = clampRadius(asDouble(row, "radius"));
                s.world = asStr(row, "world");
                s.intensity = asInt(row, "intensity");
                s.wave = asInt(row, "wave");
                s.totalWaves = asInt(row, "total_waves");
                s.endsFullTime = asLong(row, "ends_full_time");
                s.paramsJson = asStr(row, "params_json");
                scenarios.add(s);
            }
            if (scenarios.isEmpty()) return;

            // Builder snapshot for the area-scoped warnings (positions read from STDB).
            List<BuilderRow> roster = new ArrayList<>();
            for (Map<String, JsonElement> row : stdb.sql(
                    "SELECT npc_id, name, job, personality, x, y, z, world FROM builder")) {
                if (row.get("npc_id") == null) continue;
                BuilderRow b = new BuilderRow();
                b.npcId = asInt(row, "npc_id");
                b.name = asStr(row, "name");
                b.job = asStr(row, "job");
                b.personality = asStr(row, "personality");
                b.x = asDouble(row, "x");
                b.y = asDouble(row, "y");
                b.z = asDouble(row, "z");
                b.world = asStr(row, "world");
                roster.add(b);
            }

            Bukkit.getScheduler().runTask(plugin, () -> tickMain(scenarios, roster));
        } catch (Throwable t) {
            plugin.getLogger().warning("[scenario] cycle error: " + t);
        }
    }

    // -------------------------------------------------------------- main tick

    private void tickMain(List<Scenario> scenarios, List<BuilderRow> roster) {
        long now = System.currentTimeMillis();
        for (Scenario s : scenarios) {
            try {
                World world = Bukkit.getWorld(s.world);
                if (world == null) continue; // world not loaded — skip silently

                Location center = new Location(world, s.x, s.y, s.z);

                // Auto-end at duration: end the scenario and run light cleanup.
                if (world.getFullTime() >= s.endsFullTime) {
                    endScenario(s, world);
                    continue;
                }

                runEffects(s, world, center, now);
                runWarningsAndBehavior(s, world, center, roster, now);
            } catch (Throwable t) {
                plugin.getLogger().warning("[scenario] tick error (id " + s.id + "): " + t);
            }
        }
    }

    /** End + idempotent cleanup, and forget this scenario's pacing state. */
    private void endScenario(Scenario s, World world) {
        try {
            stdb.call("end_scenario", s.id);
            if ("storm_flood".equals(s.kind)) {
                world.setStorm(false);
                world.setThundering(false);
            }
            // wildfire/meteor leave no persistent toggle to undo; fire burns out on its own.
        } catch (Throwable t) {
            plugin.getLogger().warning("[scenario] end cleanup error (id " + s.id + "): " + t);
        }
        lastPulse.remove(s.id);
        lastWave.remove(s.id);
        spawnedCount.remove(s.id);
        plugin.getLogger().info("[scenario] " + s.kind + " " + s.id + " auto-ended.");
    }

    // ------------------------------------------------------------- effects

    private void runEffects(Scenario s, World world, Location center, long now) {
        switch (s.kind) {
            case "zombie_outbreak": effectZombies(s, world, center, now); break;
            case "wildfire":        effectWildfire(s, world, center, now); break;
            case "storm_flood":     effectStormFlood(s, world, center, now); break;
            case "meteor":          effectMeteor(s, world, center, now); break;
            default: /* unknown scenario kind: warnings still apply, no effects */ break;
        }
    }

    /** Spawn a wave of hostiles every ~6s while waves remain and under the lifetime cap. */
    private void effectZombies(Scenario s, World world, Location center, long now) {
        if (s.wave >= s.totalWaves) return;
        if (!elapsed(lastWave, s.id, now, WAVE_MS)) return;

        int already = spawnedCount.getOrDefault(s.id, 0);
        if (already >= MOB_TOTAL_CAP) return;

        int want = mobsPerWave(s.intensity);
        // escalate: each later wave brings a couple more (still under the global cap).
        if (boolParam(s.paramsJson, "escalate", false)) want += s.wave;
        want = Math.min(want, MOB_TOTAL_CAP - already);
        if (want <= 0) return;

        List<EntityType> palette = mobPalette(s.intensity);
        int spawned = 0;
        for (int i = 0; i < want; i++) {
            Location at = randomSurfaceInRadius(world, center, s.radius);
            if (at == null) continue; // not in a loaded chunk this try
            EntityType type = palette.get(ThreadLocalRandom.current().nextInt(palette.size()));
            try {
                world.spawnEntity(at, type);
                spawned++;
            } catch (Throwable t) {
                // bad spawn (e.g. peaceful difficulty) — skip this one
            }
        }

        if (spawned > 0) {
            spawnedCount.put(s.id, already + spawned);
            lastWave.put(s.id, now);
            stdb.call("advance_scenario", s.id, s.wave + 1);
            plugin.getLogger().info("[scenario] outbreak " + s.id + " wave " + (s.wave + 1)
                    + "/" + s.totalWaves + " (+" + spawned + " mobs)");
        }
    }

    /** Ignite a handful of random spots on top of solid ground each pulse. */
    private void effectWildfire(Scenario s, World world, Location center, long now) {
        if (!elapsed(lastPulse, s.id, now, PULSE_MS)) return;
        Material fire = Material.matchMaterial("FIRE");
        if (fire == null) return; // FIRE missing on this version — nothing to do

        int placed = 0;
        for (int i = 0; i < WILDFIRE_PER_PULSE * 2 && placed < WILDFIRE_PER_PULSE; i++) {
            Location top = randomSurfaceInRadius(world, center, s.radius);
            if (top == null) continue;
            Block ground = top.getBlock().getRelative(0, -1, 0); // surface block (top is the air above)
            try {
                if (ground.getType().isSolid() && top.getBlock().getType().isAir()) {
                    top.getBlock().setType(fire, true);
                    placed++;
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Keep the storm on, strike a few bolts per pulse, and optionally creep water up. */
    private void effectStormFlood(Scenario s, World world, Location center, long now) {
        try {
            world.setStorm(true);
            world.setThundering(true);
        } catch (Throwable ignored) {}

        if (!elapsed(lastPulse, s.id, now, PULSE_MS)) return;

        for (int i = 0; i < LIGHTNING_PER_PULSE; i++) {
            Location at = randomSurfaceInRadius(world, center, s.radius);
            if (at == null) continue;
            try {
                world.strikeLightning(at);
            } catch (Throwable ignored) {}
        }

        // Optional, heavily-guarded flood: raise water by 1 in a small capped patch.
        if (boolParam(s.paramsJson, "flood", false)) {
            raiseWater(world, center, s.radius);
        }
    }

    /**
     * Conservatively raise water level by one in a few random in-radius columns:
     * only convert an AIR block to WATER when the block directly below is already
     * WATER (i.e. extend an existing pool upward by one). Skips anything risky.
     */
    private void raiseWater(World world, Location center, double radius) {
        Material water = Material.matchMaterial("WATER");
        if (water == null) return;
        int raised = 0;
        for (int i = 0; i < FLOOD_PER_PULSE_CAP * 2 && raised < FLOOD_PER_PULSE_CAP; i++) {
            Location at = randomSurfaceInRadius(world, center, radius);
            if (at == null) continue;
            try {
                Block here = at.getBlock();           // first air column above the surface
                Block below = here.getRelative(0, -1, 0);
                if (here.getType().isAir() && below.getType() == water) {
                    here.setType(water, true);
                    raised++;
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Drop a few magma "meteors" high above random in-radius points; small impact boom. */
    private void effectMeteor(Scenario s, World world, Location center, long now) {
        if (!elapsed(lastPulse, s.id, now, PULSE_MS)) return;
        Material magma = firstMaterial("MAGMA_BLOCK", "MAGMA");
        if (magma == null) return;

        for (int i = 0; i < METEOR_PER_PULSE; i++) {
            Location impact = randomSurfaceInRadius(world, center, s.radius);
            if (impact == null) continue;
            Location high = impact.clone().add(0.5, METEOR_SPAWN_HEIGHT, 0.5);
            try {
                FallingBlock fb = world.spawnFallingBlock(high, magma.createBlockData());
                fb.setDropItem(false);
                try { fb.setHurtEntities(true); } catch (Throwable ignored) {}
                // Schedule a SMALL explosion roughly when it lands (no fire, no block break).
                final Location boom = impact.clone();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        if (boom.getWorld() != null && boom.getChunk().isLoaded()) {
                            boom.getWorld().createExplosion(boom, METEOR_POWER, false, false);
                        }
                    } catch (Throwable ignored) {}
                }, 45L); // ~2.25s fall for a 30-block drop
            } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------- warnings + light behavior

    private void runWarningsAndBehavior(Scenario s, World world, Location center,
                                        List<BuilderRow> roster, long now) {
        double r2 = s.radius * s.radius;
        for (BuilderRow b : roster) {
            try {
                if (b.world == null || !b.world.equals(s.world)) continue; // same world only
                double dx = b.x - center.getX();
                double dy = b.y - center.getY();
                double dz = b.z - center.getZ();
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2) continue; // OUTSIDE the area → this Builder says/does nothing

                String behavior = behaviorFor(b.personality);
                applyBehavior(b, behavior, center);
                maybeWarn(s, b, now);
            } catch (Throwable t) {
                plugin.getLogger().warning("[scenario] warn error (npc " + b.npcId + "): " + t);
            }
        }
    }

    /** Light behavior: fight → set state; flee → navigate away from centre; neutral → nothing. */
    private void applyBehavior(BuilderRow b, String behavior, Location center) {
        if ("fight".equals(behavior)) {
            // JobMechanics' combat already engages nearby hostiles; just flag the state.
            try { stdb.call("set_builder_state", b.npcId, "fighting"); } catch (Throwable ignored) {}
        } else if ("flee".equals(behavior)) {
            NPC npc = builders.byId(b.npcId);
            if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                try {
                    Location at = npc.getEntity().getLocation();
                    Vector away = at.toVector().subtract(center.toVector());
                    if (away.lengthSquared() < 1.0e-4) away = new Vector(1, 0, 0); // on top of centre
                    away.setY(0).normalize().multiply(FLEE_DISTANCE);
                    Location target = at.clone().add(away);
                    npc.getNavigator().setTarget(target);
                } catch (Throwable ignored) {}
            }
        }
        // "neutral" → warn only (handled by maybeWarn).
    }

    /** Throttled per Builder (~12s): warn nearby players in-game and post to the dashboard. */
    private void maybeWarn(Scenario s, BuilderRow b, long now) {
        String key = s.id + "|" + b.npcId;
        Long last = lastWarn.get(key);
        if (last != null && now - last < WARN_MS) return;

        String line = warnLineFor(b.personality, s.kind);
        if (line == null || line.isEmpty()) line = "Danger! Take cover!";

        NPC npc = builders.byId(b.npcId);
        // Warn players within ~32 blocks of the live NPC (fall back to the snapshot world).
        Location origin = null;
        if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
            origin = npc.getEntity().getLocation();
        } else {
            World w = Bukkit.getWorld(b.world);
            if (w != null) origin = new Location(w, b.x, b.y, b.z);
        }

        if (origin != null && origin.getWorld() != null) {
            double pr2 = WARN_PLAYER_RADIUS * WARN_PLAYER_RADIUS;
            String prefix = "[" + safeName(b.name) + "] ";
            Title title = buildTitle(s.kind, line);
            for (Player p : origin.getWorld().getPlayers()) {
                try {
                    if (p == null || !p.isOnline()) continue;
                    if (p.getLocation().distanceSquared(origin) > pr2) continue;
                    p.sendMessage(Component.text(prefix, NamedTextColor.RED)
                            .append(Component.text(line, NamedTextColor.YELLOW)));
                    if (title != null) p.showTitle(title);
                    playWarnSound(p);
                } catch (Throwable ignored) {}
            }
        }

        // Always mirror to the dashboard chat so the console reflects the warning,
        // even if no player happened to be in earshot.
        try {
            stdb.call("post_chat", b.npcId, safeName(b.name), line, "world");
        } catch (Throwable ignored) {}

        // Throttle uniformly: we emitted at least the dashboard line this cycle, so
        // hold this Builder quiet for ~WARN_MS before its next warning.
        lastWarn.put(key, now);
    }

    private Title buildTitle(String kind, String line) {
        try {
            Component main = Component.text(scenarioLabel(kind), NamedTextColor.RED);
            Component sub = Component.text(line, NamedTextColor.GOLD);
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(600));
            return Title.title(main, sub, times);
        } catch (Throwable t) {
            return null; // older API without Adventure titles — chat + sound still fire
        }
    }

    private void playWarnSound(Player p) {
        try {
            Sound snd = matchSound("block.note_block.bell", "entity.experience_orb.pickup", "block.anvil.land");
            if (snd != null) p.playSound(p.getLocation(), snd, 0.6f, 1.0f);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------- Personalities bridge
    // Coded against the design §5.4 static contract. Both calls are wrapped so that
    // if Personalities isn't on the classpath at runtime (it should be — P2 owns it),
    // the engine degrades to a generic line / neutral behavior instead of crashing.

    private String warnLineFor(String personality, String scenarioKind) {
        try {
            return Personalities.warnLine(personality, scenarioKind);
        } catch (Throwable t) {
            return null;
        }
    }

    private String behaviorFor(String personality) {
        try {
            String b = Personalities.behavior(personality);
            return b == null ? "neutral" : b;
        } catch (Throwable t) {
            return "neutral";
        }
    }

    // ----------------------------------------------------------- geometry

    /**
     * A random point within {@code radius} of {@code center}, snapped to the highest
     * block. Returns null if the chosen column's chunk isn't loaded — every effect
     * therefore acts ONLY in loaded chunks near players (design §5.3).
     */
    private Location randomSurfaceInRadius(World world, Location center, double radius) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double ang = rng.nextDouble() * Math.PI * 2.0;
        double dist = Math.sqrt(rng.nextDouble()) * radius; // uniform over the disc
        int bx = (int) Math.floor(center.getX() + Math.cos(ang) * dist);
        int bz = (int) Math.floor(center.getZ() + Math.sin(ang) * dist);
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        int topY = world.getHighestBlockYAt(bx, bz);
        // Place the returned Location at the first AIR block above the surface.
        return new Location(world, bx + 0.5, topY + 1, bz + 0.5);
    }

    // --------------------------------------------------- clamp / param utils

    private static double clampRadius(double r) {
        if (r < RADIUS_MIN) return RADIUS_MIN;
        if (r > RADIUS_MAX) return RADIUS_MAX;
        return r;
    }

    /** low 3 / med 5 / high 8 hostiles per wave (design §5.3). */
    private static int mobsPerWave(int intensity) {
        switch (intensity) {
            case 0:  return 3;
            case 2:  return 8;
            default: return 5;
        }
    }

    /** Mob variety scales with intensity: +SKELETON at med, +HUSK at high. */
    private List<EntityType> mobPalette(int intensity) {
        List<EntityType> out = new ArrayList<>();
        addType(out, "ZOMBIE");
        if (intensity >= 1) addType(out, "SKELETON");
        if (intensity >= 2) addType(out, "HUSK");
        if (out.isEmpty()) addType(out, "ZOMBIE"); // guaranteed non-empty
        return out;
    }

    private void addType(List<EntityType> list, String name) {
        try {
            list.add(EntityType.valueOf(name));
        } catch (Throwable t) {
            plugin.getLogger().warning("[scenario] unknown EntityType '" + name + "' — skipping.");
        }
    }

    /** True once at least {@code intervalMs} has passed since the map's last stamp for id. */
    private static boolean elapsed(Map<Long, Long> map, long id, long now, long intervalMs) {
        Long last = map.get(id);
        if (last == null || now - last >= intervalMs) {
            map.put(id, now);
            return true;
        }
        return false;
    }

    /** First Material that resolves on this server version, else null. */
    private static Material firstMaterial(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return null;
    }

    /**
     * First Sound that resolves on this server version, else null. Looked up via the
     * {@link Registry} by lowercase namespaced key (e.g. "block.note_block.bell") so we
     * avoid the removal-marked {@code Sound.valueOf}.
     */
    private static Sound matchSound(String... keys) {
        for (String k : keys) {
            try {
                Sound s = Registry.SOUNDS.get(NamespacedKey.minecraft(k));
                if (s != null) return s;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean boolParam(String json, String key, boolean def) {
        if (json == null || json.trim().isEmpty()) return def;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return def;
            JsonObject o = el.getAsJsonObject();
            if (!o.has(key) || o.get(key).isJsonNull()) return def;
            JsonElement v = o.get(key);
            if (!v.isJsonPrimitive()) return def;
            try { return v.getAsBoolean(); }
            catch (Throwable t) { return Boolean.parseBoolean(v.getAsString().trim()); }
        } catch (Throwable t) {
            return def;
        }
    }

    private static String scenarioLabel(String kind) {
        if (kind == null) return "Danger!";
        switch (kind) {
            case "zombie_outbreak": return "Zombie Outbreak!";
            case "wildfire":        return "Wildfire!";
            case "storm_flood":     return "Storm!";
            case "meteor":          return "Meteors!";
            default:                return "Danger!";
        }
    }

    private static String safeName(String name) {
        return (name == null || name.isEmpty()) ? "Builder" : name;
    }

    // ----------------------------------------------------------- value types

    /** Plain snapshot of one active scenario row (no JSON on the main thread). */
    private static final class Scenario {
        long id;
        String kind;
        double x, y, z, radius;
        String world;
        int intensity;
        int wave, totalWaves;
        long endsFullTime;
        String paramsJson;
    }

    /** Plain snapshot of one builder row used for area warnings. */
    private static final class BuilderRow {
        int npcId;
        String name;
        String job;
        String personality;
        double x, y, z;
        String world;
    }

    // ------------------------------------------------------------ row parsing
    // Null-guarded accessors mirroring DirectivePoller / BuildSystem.

    private static String asStr(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static int asInt(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
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
    // Constructor:  new ScenarioEngine(BuildersPlugin plugin, Stdb stdb, BuilderManager builders)
    //
    // run() does blocking Stdb.sql off-thread (scenario WHERE status='active' + the
    // builder snapshot), then hops to the main thread (Bukkit.getScheduler().runTask)
    // for ALL world/NPC access — the same async->main pattern as BuildSystem. Effects
    // are paced by in-memory maps (reset on restart, per design §9) and clamped per
    // §5.3; everything acts only in loaded chunks; the cycle is try/catch(Throwable).
    //
    // Register on a repeating ASYNC timer in BuildersPlugin#onEnable, after AdminExecutor
    // (design §5.6). Suggested cadence — 1s, after a 6s warmup:
    //
    //     getServer().getScheduler().runTaskTimerAsynchronously(
    //             this, new ScenarioEngine(this, stdb, builders), 120L, 20L);
    //
    // Depends on Personalities (Agent P2) for warnLine/behavior — both calls are
    // guarded so a missing class degrades gracefully. Uses only the existing
    // BuilderManager#byId(int); no BuilderManager edits.
}
