package dev.builders;

import com.google.gson.JsonElement;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the BUILD-JOB work-stealing queue: Builders claim {@code build_job}s and
 * construct job-themed structures block-by-block, incrementally and visibly.
 *
 * <p>This is a {@link Runnable} the integrator registers on a repeating timer
 * (async — it does blocking HTTP via {@link Stdb#sql}). Each cycle:
 * <ol>
 *   <li>Poll {@code build_job WHERE status != 'done'} (plus {@code builder} and
 *       {@code builder_inventory}) off the main thread, parsing SATS-JSON exactly
 *       like {@link DirectivePoller}.</li>
 *   <li>Hop to the main thread to act on each job:
 *     <ul>
 *       <li><b>claim</b> — an unclaimed ("queued"/builder_id 0) job whose {@code kind}
 *           matches a free Builder (right job, same world, spawned, {@code party_id==0})
 *           → {@code claim_build_job(builderId, jobId)}.</li>
 *       <li><b>build</b> — a job already owned by a Builder whose state is "building"
 *           (or "working") → walk the NPC to the site, then place the next 1-2 template
 *           blocks (index == {@code step}), {@code consume_inventory(builderId, item, 1)}
 *           per block, and {@code advance_build_job(jobId, step+placed)}. When
 *           {@code step >= total} the reducer flips status to "done".</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Coupling is SpacetimeDB-only — the only collaborator method used from another
 * file is {@link BuilderManager#byId(int)} (already present); everything else this
 * class needs about a Builder (job/state/party_id) and its inventory is read straight
 * from STDB. That keeps it compiling against the current tree with NO edits elsewhere,
 * honoring the design's strict file-ownership / parallel-development contract.
 *
 * <p>The only local state is {@link #lastAdvanced}, which remembers the step we already
 * issued an {@code advance_build_job} for, so we don't re-place the same block while the
 * fire-and-forget reducer round-trips and the DB's {@code step} lags behind our view.
 */
public class BuildSystem implements Runnable {

    /** How many blocks to place per tick per active job — keeps construction visibly incremental. */
    private static final int BLOCKS_PER_TICK = 2;

    /** Place blocks only once the Builder is within this many blocks (squared) of the site. */
    private static final double BUILD_RANGE_SQ = 6.0 * 6.0;

    /** One relative block of a template: an offset from the site plus the Material to place. */
    public static final class RelBlock {
        final int dx, dy, dz;
        final Material mat;
        RelBlock(int dx, int dy, int dz, Material mat) {
            this.dx = dx; this.dy = dy; this.dz = dz; this.mat = mat;
        }
    }

    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    /** kind -> ordered template block list (placed in order, index == build step). */
    private final Map<String, List<RelBlock>> templates;

    /** jobId -> highest step we have already issued advance_build_job for (local de-dupe). */
    private final Map<Long, Integer> lastAdvanced = Collections.synchronizedMap(new HashMap<>());

    /** jobId -> consecutive ticks spent walking to the site (drives a teleport fallback). */
    private final Map<Long, Integer> navWait = Collections.synchronizedMap(new HashMap<>());

    public BuildSystem(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
        this.templates = buildTemplates();
    }

    // ------------------------------------------------------------------ poll

    @Override
    public void run() {
        try {
            List<Map<String, JsonElement>> jobRows = stdb.sql(
                    "SELECT id, builder_id, kind, site_x, site_y, site_z, world, step, total, status "
                            + "FROM build_job WHERE status != 'done'");
            if (jobRows.isEmpty()) return;

            // Snapshot jobs into plain values so the main-thread task touches no JSON.
            List<Job> jobs = new ArrayList<>(jobRows.size());
            for (Map<String, JsonElement> row : jobRows) {
                if (row.get("id") == null) continue;
                Job j = new Job();
                j.id = asLong(row, "id");
                j.builderId = asInt(row, "builder_id");
                j.kind = asStr(row, "kind");
                j.x = asDouble(row, "site_x");
                j.y = asDouble(row, "site_y");
                j.z = asDouble(row, "site_z");
                j.world = asStr(row, "world");
                j.step = asInt(row, "step");
                j.total = asInt(row, "total");
                j.status = asStr(row, "status");
                jobs.add(j);
            }
            if (jobs.isEmpty()) return;

            // Builder roster: npc_id -> {job, state, party_id}. Used to match jobs to workers.
            Map<Integer, BuilderRow> roster = new HashMap<>();
            for (Map<String, JsonElement> row : stdb.sql(
                    "SELECT npc_id, job, state, party_id FROM builder")) {
                if (row.get("npc_id") == null) continue;
                roster.put(asInt(row, "npc_id"),
                        new BuilderRow(asStr(row, "job"), asStr(row, "state"), asInt(row, "party_id")));
            }

            // Inventory: "builderId|ITEM" -> count, for the pre-placement block gate.
            Map<String, Integer> inventory = new HashMap<>();
            for (Map<String, JsonElement> row : stdb.sql(
                    "SELECT builder_id, item, count FROM builder_inventory")) {
                if (row.get("builder_id") == null) continue;
                inventory.put(invKey(asInt(row, "builder_id"), asStr(row, "item")), asInt(row, "count"));
            }

            Bukkit.getScheduler().runTask(plugin, () -> tickMain(jobs, roster, inventory));
        } catch (Throwable t) {
            plugin.getLogger().warning("[build] cycle error: " + t);
        }
    }

    // -------------------------------------------------------------- main tick

    /** Main thread: claim unowned jobs and advance owned ones. Touches the Bukkit world. */
    private void tickMain(List<Job> jobs, Map<Integer, BuilderRow> roster, Map<String, Integer> inventory) {
        try {
            // Builders already on a job this cycle, so two jobs don't grab the same worker.
            Set<Integer> busy = new HashSet<>();
            for (Job j : jobs) {
                if (j.builderId != 0) busy.add(j.builderId);
            }

            for (Job j : jobs) {
                if (j.builderId == 0) {
                    tryClaim(j, roster, busy); // unassigned → work-stealing claim
                } else {
                    // Pre-assigned by the worker (each Builder builds its own). Build it even if
                    // still "queued" — tryBuild flips status via advance_build_job once it starts.
                    tryBuild(j, roster, inventory);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[build] main-tick error: " + t);
        }
    }

    /** Work-stealing claim: find a matching free Builder for an unclaimed job and claim it. */
    private void tryClaim(Job job, Map<Integer, BuilderRow> roster, Set<Integer> busy) {
        String wantJob = JOB_FOR_KIND.get(job.kind);
        if (wantJob == null) return; // unknown kind — leave it for someone who understands it

        World world = Bukkit.getWorld(job.world);
        if (world == null) return;

        int chosen = -1;
        for (Map.Entry<Integer, BuilderRow> e : roster.entrySet()) {
            int id = e.getKey();
            BuilderRow br = e.getValue();
            if (busy.contains(id)) continue;
            if (br.partyId != 0) continue;             // recruited Builders pause their job
            if (!wantJob.equals(br.job)) continue;     // must be the right profession
            NPC npc = builders.byId(id);
            if (npc == null || !npc.isSpawned()) continue;
            if (!npc.getEntity().getWorld().equals(world)) continue;
            chosen = id;
            break;
        }
        if (chosen < 0) return;

        busy.add(chosen);
        lastAdvanced.remove(job.id); // fresh claim — reset our local step bookkeeping
        stdb.call("claim_build_job", chosen, job.id);
        plugin.getLogger().info("[build] Builder " + chosen + " claims " + job.kind + " job " + job.id);
    }

    /** Advance an owned job by placing the next template block(s) at the site. */
    private void tryBuild(Job job, Map<Integer, BuilderRow> roster, Map<String, Integer> inventory) {
        List<RelBlock> tmpl = templates.get(job.kind);
        if (tmpl == null) return;

        NPC npc = builders.byId(job.builderId);
        if (npc == null || !npc.isSpawned()) return;

        // The brain drives state; only build while it has us in a build-appropriate state.
        BuilderRow br = roster.get(job.builderId);
        if (br != null) {
            if (br.partyId != 0) return; // recruited → quest takes priority
            String st = br.state;
            if (st != null && !st.isEmpty() && !"building".equals(st) && !"working".equals(st)) {
                return; // sleeping / fighting / recruiting / idle → don't build this tick
            }
        }

        World world = Bukkit.getWorld(job.world);
        if (world == null) return;

        // Ground-snap: anchor the structure to the terrain surface at the site so it
        // never floats above or sinks into uneven ground (was: the fixed job.y the
        // worker recorded at job-assignment time).
        int baseY = world.getHighestBlockYAt((int) Math.floor(job.x), (int) Math.floor(job.z));
        Location site = new Location(world, job.x, baseY, job.z);
        Location stand = site.clone().add(-1.5, 0, -1.5);

        // Stand next to the build (offset so the NPC isn't inside the structure).
        if (!npc.getEntity().getWorld().equals(world)
                || npc.getEntity().getLocation().distanceSquared(site) > BUILD_RANGE_SQ) {
            int waited = navWait.merge(job.id, 1, Integer::sum);
            if (waited >= 8) {
                // ~8s of walking without arriving (flaky pathing / odd terrain) → teleport so
                // construction never stalls, mirroring the recruit flow's teleport fallback.
                navWait.remove(job.id);
                try { npc.getEntity().teleport(stand); } catch (Throwable ignored) {}
            } else if (!npc.getNavigator().isNavigating()) {
                npc.getNavigator().setTarget(stand);
            }
            return; // not there yet — keep walking, build next tick
        }
        navWait.remove(job.id);

        // Arrived: don't re-place blocks the DB hasn't caught up to yet. The TEMPLATE size is
        // authoritative for completion — the job's nominal total is only a progress-bar hint.
        int target = tmpl.size();
        int start = job.step;
        Integer already = lastAdvanced.get(job.id);
        if (already != null && already > start) start = already;
        if (start >= target) {
            // Whole template placed — make sure it flips to done even if total != template size.
            if (!"done".equals(job.status)) stdb.call("advance_build_job", job.id, Math.max(job.total, target));
            lastAdvanced.remove(job.id);
            return;
        }

        npc.faceLocation(site);

        int placed = 0;
        for (int idx = start; idx < target && placed < BLOCKS_PER_TICK; idx++) {
            RelBlock rb = tmpl.get(idx);
            String item = rb.mat.name();

            // Inventory gate: skip placing if the Builder has none of this block (restocks at night).
            if (!hasBlock(inventory, job.builderId, item)) {
                break; // stall here until restocked; re-checked next cycle
            }

            Block b = world.getBlockAt(site.getBlockX() + rb.dx,
                    site.getBlockY() + rb.dy,
                    site.getBlockZ() + rb.dz);
            try {
                b.setType(rb.mat, true);
            } catch (Throwable t) {
                plugin.getLogger().warning("[build] place " + item + " failed @ job " + job.id + ": " + t);
            }
            stdb.call("consume_inventory", job.builderId, item, 1);
            decLocal(inventory, job.builderId, item); // keep our snapshot honest within this cycle
            placed++;
        }

        if (placed > 0) {
            int newStep = start + placed;
            lastAdvanced.put(job.id, newStep);
            if (newStep >= target) {
                stdb.call("advance_build_job", job.id, Math.max(job.total, target)); // force "done"
                lastAdvanced.remove(job.id);
                plugin.getLogger().info("[build] job " + job.id + " (" + job.kind + ") complete.");
            } else {
                // Report progress without tripping the reducer's done-check before the template finishes.
                int report = (job.total > 0 && newStep >= job.total) ? job.total - 1 : newStep;
                stdb.call("advance_build_job", job.id, report);
            }
        }
    }

    /**
     * True if the Builder has at least one of {@code item}. Robust default: if the
     * inventory snapshot has no row for (builder,item) we ASSUME stocked, so a missing
     * or not-yet-populated inventory subsystem never silently halts all building.
     */
    private static boolean hasBlock(Map<String, Integer> inventory, int builderId, String item) {
        Integer n = inventory.get(invKey(builderId, item));
        return n == null || n > 0;
    }

    /** Decrement our in-cycle inventory snapshot so placing N of the same block respects stock. */
    private static void decLocal(Map<String, Integer> inventory, int builderId, String item) {
        String k = invKey(builderId, item);
        Integer n = inventory.get(k);
        if (n != null) inventory.put(k, Math.max(0, n - 1));
    }

    private static String invKey(int builderId, String item) {
        return builderId + "|" + item;
    }

    // ----------------------------------------------------------- value types

    /** Plain snapshot of one build_job row (no JSON on the main thread). */
    private static final class Job {
        long id;
        int builderId;
        String kind;
        double x, y, z;
        String world;
        int step, total;
        String status;
    }

    /** Minimal view of a builder row the claim/build logic gates on. */
    private static final class BuilderRow {
        final String job;
        final String state;
        final int partyId;
        BuilderRow(String job, String state, int partyId) {
            this.job = job; this.state = state; this.partyId = partyId;
        }
    }

    // ----------------------------------------------------------- job mapping

    /** build kind -> the profession whose Builders construct it (design §Jobs table). */
    private static final Map<String, String> JOB_FOR_KIND = new HashMap<>();
    static {
        JOB_FOR_KIND.put("house", "mason");
        JOB_FOR_KIND.put("forge", "smith");
        JOB_FOR_KIND.put("farm", "farmer");
        JOB_FOR_KIND.put("mine", "miner");
        JOB_FOR_KIND.put("dock", "fisher");
        JOB_FOR_KIND.put("pen", "shepherd");
        JOB_FOR_KIND.put("cabin", "lumberjack");
        JOB_FOR_KIND.put("tower", "guard");
        // "wall" is also masonry; mason handles both house and wall.
        JOB_FOR_KIND.put("wall", "mason");
    }

    // ------------------------------------------------------------- templates

    /**
     * Hardcoded, recognizable structures keyed by build kind. Offsets are (dx,dy,dz)
     * from the job site; y==0 is ground level. Lists are placed in order, so the
     * structure rises foundation-first and reads as it grows. Every Material is
     * resolved through {@link #mat} ({@code Material.matchMaterial}) so a name that
     * doesn't exist on this server version is skipped rather than crashing init.
     */
    private Map<String, List<RelBlock>> buildTemplates() {
        Map<String, List<RelBlock>> t = new LinkedHashMap<>();
        t.put("farm", farm());
        t.put("dock", dock());
        t.put("pen", pen());
        t.put("forge", forge());
        t.put("house", house());
        t.put("wall", wall());
        t.put("cabin", cabin());
        t.put("mine", mine());
        t.put("tower", tower());
        return t;
    }

    // ---- template builders -------------------------------------------------

    /** farm: 3x3 tilled FARMLAND with a central WATER source, planted with WHEAT. ~19 blocks. */
    private List<RelBlock> farm() {
        List<RelBlock> l = new ArrayList<>();
        Material farmland = mat("FARMLAND");
        Material water = mat("WATER");
        Material wheat = mat("WHEAT");
        // Tilled ground (y=0): 3x3 grid, centre reserved for water.
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                if (dx == 1 && dz == 1) continue;
                add(l, dx, 0, dz, farmland);
            }
        }
        add(l, 1, 0, 1, water); // irrigation source in the middle
        // Crops planted one block above the tilled soil.
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                if (dx == 1 && dz == 1) continue;
                add(l, dx, 1, dz, wheat);
            }
        }
        return l;
    }

    /** dock: 2x4 OAK_PLANKS platform with OAK_FENCE mooring posts at the far end. ~12 blocks. */
    private List<RelBlock> dock() {
        List<RelBlock> l = new ArrayList<>();
        Material planks = mat("OAK_PLANKS");
        Material fence = mat("OAK_FENCE");
        for (int dz = 0; dz <= 3; dz++) {  // length running out over the water
            add(l, 0, 0, dz, planks);
            add(l, 1, 0, dz, planks);
        }
        // Mooring posts at the seaward end.
        add(l, 0, 1, 3, fence);
        add(l, 1, 1, 3, fence);
        add(l, 0, 2, 3, fence);
        add(l, 1, 2, 3, fence);
        return l;
    }

    /** pen: 5x5 OAK_FENCE perimeter with a one-block gate opening. ~16 blocks. */
    private List<RelBlock> pen() {
        List<RelBlock> l = new ArrayList<>();
        Material fence = mat("OAK_FENCE");
        Material gate = mat("OAK_FENCE_GATE");
        int n = 4; // 0..4 → 5x5
        for (int i = 0; i <= n; i++) {
            if (i != 2) {            // leave the middle of the front rail open for a gate
                add(l, i, 0, 0, fence);
            }
            add(l, i, 0, n, fence);  // back rail
            if (i != 0 && i != n) {  // side rails (corners already placed by the front/back loops)
                add(l, 0, 0, i, fence);
                add(l, n, 0, i, fence);
            }
        }
        add(l, 2, 0, 0, gate); // the gate
        return l;
    }

    /** forge: 3x3 STONE base, a FURNACE, an ANVIL, and a short COBBLESTONE chimney. ~15 blocks. */
    private List<RelBlock> forge() {
        List<RelBlock> l = new ArrayList<>();
        Material stone = mat("STONE");
        Material cobble = mat("COBBLESTONE");
        Material furnace = mat("FURNACE");
        Material anvil = mat("ANVIL");
        // Stone working floor.
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                add(l, dx, 0, dz, stone);
            }
        }
        add(l, 0, 1, 0, furnace); // the forge furnace
        add(l, 2, 1, 0, anvil);   // the anvil
        // Cobblestone chimney rising in a back corner.
        add(l, 2, 1, 2, cobble);
        add(l, 2, 2, 2, cobble);
        add(l, 2, 3, 2, cobble);
        return l;
    }

    /** house: 5x5 COBBLESTONE walls (2 high) with an OAK_DOOR and a flat OAK_PLANKS roof. ~40 blocks. */
    private List<RelBlock> house() {
        List<RelBlock> l = new ArrayList<>();
        Material cobble = mat("COBBLESTONE");
        Material door = mat("OAK_DOOR");
        Material planks = mat("OAK_PLANKS");
        int n = 4; // 5x5 footprint
        // Two courses of walls around the perimeter.
        for (int y = 1; y <= 2; y++) {
            for (int i = 0; i <= n; i++) {
                add(l, i, y, 0, cobble);
                add(l, i, y, n, cobble);
                if (i != 0 && i != n) {
                    add(l, 0, y, i, cobble);
                    add(l, n, y, i, cobble);
                }
            }
        }
        // Doorway: clear two blocks and seat a door at the front-centre.
        add(l, 2, 1, 0, mat("AIR"));
        add(l, 2, 2, 0, mat("AIR"));
        add(l, 2, 1, 0, door);
        // Flat plank roof at y=3.
        for (int dx = 0; dx <= n; dx++) {
            for (int dz = 0; dz <= n; dz++) {
                add(l, dx, 3, dz, planks);
            }
        }
        return l;
    }

    /** wall: a 5-long, 3-high STONE_BRICKS rampart capped with STONE_BRICK_SLAB. ~20 blocks. */
    private List<RelBlock> wall() {
        List<RelBlock> l = new ArrayList<>();
        Material brick = mat("STONE_BRICKS");
        Material slab = mat("STONE_BRICK_SLAB");
        for (int dx = 0; dx <= 4; dx++) {
            for (int y = 1; y <= 3; y++) {
                add(l, dx, y, 0, brick);
            }
            add(l, dx, 4, 0, slab); // crenellation cap
        }
        return l;
    }

    /** cabin: 4x4 OAK_LOG walls (2 high) with a doorway and an OAK_PLANKS roof. ~36 blocks. */
    private List<RelBlock> cabin() {
        List<RelBlock> l = new ArrayList<>();
        Material log = mat("OAK_LOG");
        Material planks = mat("OAK_PLANKS");
        Material door = mat("OAK_DOOR");
        int n = 3; // 4x4 footprint
        for (int y = 1; y <= 2; y++) {
            for (int i = 0; i <= n; i++) {
                add(l, i, y, 0, log);
                add(l, i, y, n, log);
                if (i != 0 && i != n) {
                    add(l, 0, y, i, log);
                    add(l, n, y, i, log);
                }
            }
        }
        // Doorway on the front wall.
        add(l, 1, 1, 0, mat("AIR"));
        add(l, 1, 2, 0, mat("AIR"));
        add(l, 1, 1, 0, door);
        // Plank roof.
        for (int dx = 0; dx <= n; dx++) {
            for (int dz = 0; dz <= n; dz++) {
                add(l, dx, 3, dz, planks);
            }
        }
        return l;
    }

    /** mine: a STONE portal frame around a 1x2 entrance, lit with TORCHes, LADDER inside. ~16 blocks. */
    private List<RelBlock> mine() {
        List<RelBlock> l = new ArrayList<>();
        Material stone = mat("STONE");
        Material torch = mat("TORCH");
        Material ladder = mat("LADDER");
        // Frame: 3 wide, 3 tall, entrance is the centre column (x=1, y=1..2).
        for (int y = 1; y <= 3; y++) {
            add(l, 0, y, 0, stone); // left jamb
            add(l, 2, y, 0, stone); // right jamb
        }
        add(l, 1, 3, 0, stone);   // lintel over the doorway
        // Clear the doorway then light it and drop a ladder.
        add(l, 1, 1, 0, mat("AIR"));
        add(l, 1, 2, 0, mat("AIR"));
        add(l, 0, 3, 0, torch);
        add(l, 2, 3, 0, torch);
        add(l, 1, 1, 0, ladder);
        add(l, 1, 2, 0, ladder);
        return l;
    }

    /** tower: a 3x3 COBBLESTONE watchtower 4 high, with a fenced lookout ring on top. ~36 blocks. */
    private List<RelBlock> tower() {
        List<RelBlock> l = new ArrayList<>();
        Material cobble = mat("COBBLESTONE");
        Material fence = mat("OAK_FENCE");
        int n = 2; // 3x3 footprint
        // Hollow walls, 4 courses tall.
        for (int y = 1; y <= 4; y++) {
            for (int i = 0; i <= n; i++) {
                add(l, i, y, 0, cobble);
                add(l, i, y, n, cobble);
                if (i != 0 && i != n) {
                    add(l, 0, y, i, cobble);
                    add(l, n, y, i, cobble);
                }
            }
        }
        // Fence railing around the lookout platform (y=5 perimeter).
        for (int i = 0; i <= n; i++) {
            add(l, i, 5, 0, fence);
            add(l, i, 5, n, fence);
            if (i != 0 && i != n) {
                add(l, 0, 5, i, fence);
                add(l, n, 5, i, fence);
            }
        }
        return l;
    }

    // ---- template helpers --------------------------------------------------

    /** Append a relative block, skipping it if its Material didn't resolve on this version. */
    private static void add(List<RelBlock> l, int dx, int dy, int dz, Material m) {
        if (m != null) l.add(new RelBlock(dx, dy, dz, m));
    }

    /** Resolve a Material by name, tolerating version differences; logs once if missing. */
    private Material mat(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null) {
            plugin.getLogger().warning("[build] unknown Material '" + name + "' — skipping those blocks.");
        }
        return m;
    }

    // ------------------------------------------------------------ row parsing
    // Null-guarded accessors mirroring DirectivePoller's SATS-JSON handling.

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
    // Constructor:  new BuildSystem(BuildersPlugin plugin, Stdb stdb, BuilderManager builders)
    //
    // run() does blocking Stdb.sql off-thread (build_job + builder + builder_inventory),
    // then hops to the main thread (Bukkit.getScheduler().runTask) for all world/NPC
    // access — the same async->main pattern as DirectivePoller. The whole poll body is
    // wrapped in try/catch(Throwable).
    //
    // Register on a repeating ASYNC timer in BuildersPlugin#onEnable, right after the
    // DirectivePoller line. Suggested cadence: 1s, after a 4s warmup:
    //
    //     getServer().getScheduler().runTaskTimerAsynchronously(
    //             this, new BuildSystem(this, stdb, builders), 80L, 20L);
    //
    // 20L (1s) cadence + BLOCKS_PER_TICK=2 makes each structure rise visibly over
    // several seconds. No new BuilderManager methods are required: this class only
    // calls the existing BuilderManager#byId(int).
}
