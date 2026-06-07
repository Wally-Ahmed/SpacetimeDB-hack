package dev.builders;

import com.google.gson.JsonElement;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase-1 Agent D: makes Builders USE real Minecraft mechanics for their job and
 * fight hostile mobs. Coupling to the rest of the system is ONLY via SpacetimeDB
 * state (the {@code builder} table), exactly like the other subsystems.
 *
 * <p>Two responsibilities, kept independently callable so the Phase-2 arbiter can
 * order them with combat first:
 * <ol>
 *   <li>{@link #handleCombat(NPC)} — UNIVERSAL, highest priority. Targets the
 *       nearest hostile {@link Monster} within {@link #COMBAT_RANGE} blocks and
 *       returns {@code true} if this Builder is now fighting (so the arbiter skips
 *       its other behavior this tick).</li>
 *   <li>{@link #runJob(Builder, NPC)} — only when {@code state == "working"} and
 *       {@code party_id == 0}; performs one small, lightweight job action.</li>
 * </ol>
 *
 * <p>{@link #tick()} is the all-in-one timer entry point: it reads the builder
 * table off the main thread (Stdb.sql is blocking) then hops to the main thread
 * to run combat-then-job for every spawned Builder. An integrator that already
 * has a state-priority arbiter can instead call {@link #handleCombat(NPC)} and
 * {@link #runJob(Builder, NPC)} directly and skip {@link #tick()}.
 *
 * <p>NOTE TO INTEGRATOR: this class needs to enumerate the live Builders. To
 * avoid editing {@code BuilderManager}, it uses {@code BuilderManager.byId(int)}
 * keyed off the ids returned by the STDB {@code builder} table. If you prefer to
 * iterate Citizens directly, a one-line {@code public Collection<Integer> ids()}
 * accessor on BuilderManager would let {@link #tick()} skip the SQL round-trip —
 * but as written it needs no edits to any other file.
 */
public class JobMechanics {
    /** Hostiles within this many blocks of a Builder trigger combat. */
    public static final double COMBAT_RANGE = 10.0;
    /** Job actions only consider blocks/entities within this radius of the Builder. */
    private static final int WORK_RADIUS = 4;
    /** Vertical span to scan around the Builder's feet for work blocks. */
    private static final int WORK_VSPAN = 2;

    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    public JobMechanics(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    // ---------------------------------------------------------------------
    // Timer entry point (self-contained: SQL off-thread, act on main thread)
    // ---------------------------------------------------------------------

    /**
     * Register this with {@code runTaskTimerAsynchronously} (it does its own
     * main-thread hop). Reads the builder table, then for each spawned Builder
     * runs combat first; if not fighting and {@code state=="working"} with no
     * party, runs the job mechanic.
     */
    public void tick() {
        final List<Builder> snapshot;
        try {
            snapshot = readBuilders();
        } catch (Throwable t) {
            plugin.getLogger().warning("[jobs] read error: " + t);
            return;
        }
        if (snapshot.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Builder b : snapshot) {
                try {
                    NPC npc = builders.byId(b.npcId);
                    if (npc == null || !npc.isSpawned() || npc.getEntity() == null) continue;
                    // Combat is universal and pre-empts everything else.
                    if (handleCombat(npc)) {
                        // Optional: mirror combat state into STDB for the dashboard/brain.
                        if (!"fighting".equals(b.state)) setState(b.npcId, "fighting");
                        continue;
                    }
                    // Job mechanics only while actively "working" and not on a quest.
                    if (b.partyId != 0) continue;
                    if (!"working".equals(b.state)) continue;
                    runJob(b, npc);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[jobs] tick error for npc " + b.npcId + ": " + t);
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // COMBAT (universal, highest priority) — safe to call on the main thread
    // ---------------------------------------------------------------------

    /**
     * If a hostile {@link Monster} is within {@link #COMBAT_RANGE} of the NPC,
     * aggressively target it (and equip a sword) and return {@code true}. Returns
     * {@code false} if there is no nearby hostile. Main-thread only.
     *
     * <p>The arbiter should call this FIRST for every Builder, before any
     * schedule/work behavior, so combat always wins.
     */
    public boolean handleCombat(NPC npc) {
        try {
            if (npc == null || !npc.isSpawned() || npc.getEntity() == null) return false;
            Location at = npc.getEntity().getLocation();

            Monster nearest = null;
            double best = COMBAT_RANGE * COMBAT_RANGE;
            // getNearbyLivingEntities is cheaper than scanning the whole world.
            for (LivingEntity le : at.getNearbyLivingEntities(COMBAT_RANGE)) {
                if (!(le instanceof Monster)) continue;
                if (le.isDead() || !le.isValid()) continue;
                double d2 = le.getLocation().distanceSquared(at);
                if (d2 < best) { best = d2; nearest = (Monster) le; }
            }
            if (nearest == null) return false;

            equipSword(npc);
            // Aggressive target: Citizens walks up and melee-attacks the mob.
            // Avoid re-issuing every tick if we're already chasing this same mob.
            if (!isAlreadyTargeting(npc, nearest)) {
                npc.getNavigator().setTarget(nearest, true);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[jobs] combat error: " + t);
            return false;
        }
    }

    private boolean isAlreadyTargeting(NPC npc, Entity target) {
        try {
            if (!npc.getNavigator().isNavigating()) return false;
            net.citizensnpcs.api.ai.EntityTarget et = npc.getNavigator().getEntityTarget();
            return et != null && et.getTarget() != null && et.getTarget().equals(target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void equipSword(NPC npc) {
        try {
            Equipment eq = npc.getOrAddTrait(Equipment.class);
            ItemStack hand = eq.get(Equipment.EquipmentSlot.HAND);
            if (hand == null || hand.getType() != Material.IRON_SWORD) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.IRON_SWORD));
            }
        } catch (Throwable ignored) {
            // Equipment trait is cosmetic; combat works without it.
        }
    }

    // ---------------------------------------------------------------------
    // JOB MECHANICS — one lightweight action per tick, main-thread only
    // ---------------------------------------------------------------------

    /**
     * Run one job action for a Builder. Caller guarantees {@code state=="working"}
     * and {@code party_id==0} (the arbiter does this); we still no-op safely for
     * jobs without a working action. Main-thread only.
     */
    public void runJob(Builder b, NPC npc) {
        String job = b.job == null ? "" : b.job.toLowerCase(Locale.ROOT);
        Location at = npc.getEntity().getLocation();
        Location home = b.homeWorld != null && !b.homeWorld.isEmpty()
                ? new Location(npc.getEntity().getWorld(), b.homeX, b.homeY, b.homeZ)
                : at;
        try {
            switch (job) {
                case "farmer":     doFarmer(npc, at); break;
                case "fisher":     doFisher(npc, at); break;
                case "shepherd":   doShepherd(npc, at, home); break;
                case "lumberjack": doLumberjack(npc, at); break;
                case "miner":      doMiner(npc, at); break;
                // mason / smith / guard: their value is building + combat → no working action.
                default: break;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[jobs] job '" + job + "' error for npc " + b.npcId + ": " + t);
        }
    }

    /** Farmer: harvest a ripe crop+replant, else till dirt/grass and plant a crop. One action/tick. */
    private void doFarmer(NPC npc, Location at) {
        // 1) Prefer harvesting a fully-grown crop, then replant.
        for (Block crop : nearbyBlocks(at)) {
            Material m = crop.getType();
            if (m != Material.WHEAT && m != Material.CARROTS) continue;
            BlockData data = crop.getBlockData();
            if (data instanceof Ageable age && age.getAge() >= age.getMaximumAge()) {
                Material yield = (m == Material.WHEAT) ? Material.WHEAT : Material.CARROT;
                crop.getWorld().dropItemNaturally(crop.getLocation().add(0.5, 0.2, 0.5), new ItemStack(yield, 1 + (int) (Math.random() * 2)));
                // Replant the same crop at age 0.
                crop.setType(m, false);
                BlockData fresh = crop.getBlockData();
                if (fresh instanceof Ageable fa) { fa.setAge(0); crop.setBlockData(fa, false); }
                swing(npc);
                return;
            }
        }
        // 2) Otherwise till a patch of dirt/grass and plant a crop on top.
        for (Block ground : nearbyBlocks(at)) {
            Material gm = ground.getType();
            if (gm != Material.DIRT && gm != Material.GRASS_BLOCK && gm != Material.DIRT_PATH) continue;
            Block above = ground.getRelative(0, 1, 0);
            if (!above.getType().isAir()) continue; // need open space for the crop
            ground.setType(Material.FARMLAND, false);
            Material plant = Math.random() < 0.5 ? Material.WHEAT : Material.CARROTS;
            above.setType(plant, false);
            swing(npc);
            return;
        }
    }

    /** Fisher: if next to water, occasionally "land a catch" (drop a fish) and swing. Kept simple. */
    private void doFisher(NPC npc, Location at) {
        if (!nearWater(at)) return;
        swing(npc);
        // Don't spew items: only land a fish on roughly 1 in 5 ticks.
        if (Math.random() < 0.2) {
            Material fish = Math.random() < 0.5 ? Material.COD : Material.SALMON;
            at.getWorld().dropItemNaturally(at.clone().add(0, 1, 0), new ItemStack(fish, 1));
        }
    }

    /** Shepherd: shear an unsheared sheep, else breed a pair, else gently herd one home. One action/tick. */
    private void doShepherd(NPC npc, Location at, Location home) {
        List<Sheep> sheep = new ArrayList<>(at.getNearbyEntitiesByType(Sheep.class, WORK_RADIUS + 4));
        if (sheep.isEmpty()) return;

        // 1) Shear an unsheared adult sheep (drops wool).
        for (Sheep s : sheep) {
            if (s.isDead() || !s.isValid()) continue;
            if (!s.isSheared() && s.isAdult()) {
                s.setSheared(true);
                s.getWorld().dropItemNaturally(s.getLocation(), new ItemStack(Material.WHITE_WOOL, 1 + (int) (Math.random() * 3)));
                swing(npc);
                return;
            }
        }
        // 2) If 2+ breedable adults, breed them (spawn a lamb between two of them).
        List<Sheep> adults = new ArrayList<>();
        for (Sheep s : sheep) {
            if (!s.isDead() && s.isValid() && s.isAdult() && (s instanceof Breedable br) && br.canBreed()) adults.add(s);
        }
        if (adults.size() >= 2) {
            Sheep a = adults.get(0), b = adults.get(1);
            Location mid = a.getLocation().clone().add(b.getLocation()).multiply(0.5);
            Sheep lamb = (Sheep) a.getWorld().spawnEntity(mid, a.getType());
            try { lamb.setBaby(); } catch (Throwable ignored) {}
            // Put the parents on breeding cooldown so we don't spam lambs.
            try { ((Breedable) a).setBreed(false); ((Breedable) b).setBreed(false); } catch (Throwable ignored) {}
            swing(npc);
            return;
        }
        // 3) Otherwise gently herd one stray sheep toward home (non-destructive).
        if (home != null) {
            Sheep stray = sheep.get(0);
            if (stray.getLocation().distanceSquared(home) > 9.0) {
                npc.getNavigator().setTarget(stray.getLocation());
            }
        }
    }

    /** Lumberjack: break a nearby log (drop it) and plant a sapling on dirt. One action/tick. */
    private void doLumberjack(NPC npc, Location at) {
        for (Block b : nearbyBlocks(at)) {
            if (!Tag.LOGS.isTagged(b.getType())) continue;
            Material logType = b.getType();
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(logType, 1));
            b.setType(Material.AIR, false);
            swing(npc);
            // Try to replant a sapling on nearby dirt/grass (best-effort, same tick is fine).
            for (Block g : nearbyBlocks(at)) {
                if ((g.getType() == Material.DIRT || g.getType() == Material.GRASS_BLOCK)
                        && g.getRelative(0, 1, 0).getType().isAir()) {
                    g.getRelative(0, 1, 0).setType(Material.OAK_SAPLING, false);
                    break;
                }
            }
            return;
        }
    }

    /** Miner: break one exposed stone block near the Builder (simulates ore gathering). One action/tick. */
    private void doMiner(NPC npc, Location at) {
        for (Block b : nearbyBlocks(at)) {
            Material m = b.getType();
            if (m != Material.STONE && m != Material.COBBLESTONE) continue;
            // Only mine "exposed" stone (a face open to air) so we don't bore into terrain blindly.
            if (!hasAirFace(b)) continue;
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(Material.COBBLESTONE, 1));
            b.setType(Material.AIR, false);
            swing(npc);
            return;
        }
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** Blocks in a small box around the Builder's feet (radius {@link #WORK_RADIUS}). */
    private List<Block> nearbyBlocks(Location at) {
        List<Block> out = new ArrayList<>();
        Block center = at.getBlock();
        for (int dx = -WORK_RADIUS; dx <= WORK_RADIUS; dx++) {
            for (int dz = -WORK_RADIUS; dz <= WORK_RADIUS; dz++) {
                for (int dy = -WORK_VSPAN; dy <= WORK_VSPAN; dy++) {
                    out.add(center.getRelative(dx, dy, dz));
                }
            }
        }
        return out;
    }

    private boolean nearWater(Location at) {
        for (Block b : nearbyBlocks(at)) if (b.getType() == Material.WATER) return true;
        return false;
    }

    private boolean hasAirFace(Block b) {
        return b.getRelative(0, 1, 0).getType().isAir()
                || b.getRelative(0, -1, 0).getType().isAir()
                || b.getRelative(1, 0, 0).getType().isAir()
                || b.getRelative(-1, 0, 0).getType().isAir()
                || b.getRelative(0, 0, 1).getType().isAir()
                || b.getRelative(0, 0, -1).getType().isAir();
    }

    /** Visible "I'm working" cue: swing the main hand. Best-effort across API variants. */
    private void swing(NPC npc) {
        try {
            Entity e = npc.getEntity();
            if (e instanceof org.bukkit.entity.LivingEntity le) {
                le.swingMainHand();
            }
        } catch (Throwable ignored) {}
    }

    private void setState(int npcId, String state) {
        // Optional reducer per the contract; fire-and-forget, main-thread safe.
        try { stdb.call("set_builder_state", npcId, state); } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------------
    // STDB read of the builder table (off the main thread)
    // ---------------------------------------------------------------------

    private List<Builder> readBuilders() {
        List<Map<String, JsonElement>> rows = stdb.sql(
                "SELECT npc_id, name, job, state, x, y, z, world, home_x, home_y, home_z, party_id FROM builder");
        List<Builder> out = new ArrayList<>(rows.size());
        for (Map<String, JsonElement> r : rows) {
            if (r.get("npc_id") == null) continue;
            Builder b = new Builder();
            b.npcId = r.get("npc_id").getAsInt();
            b.name = str(r, "name");
            b.job = str(r, "job");
            b.state = str(r, "state");
            b.x = d(r, "x");
            b.y = d(r, "y");
            b.z = d(r, "z");
            b.world = str(r, "world");
            b.homeX = d(r, "home_x");
            b.homeY = d(r, "home_y");
            b.homeZ = d(r, "home_z");
            b.partyId = r.get("party_id") != null && !r.get("party_id").isJsonNull() ? r.get("party_id").getAsInt() : 0;
            b.homeWorld = b.world; // contract has no home_world column; home is in the Builder's world.
            out.add(b);
        }
        return out;
    }

    private static String str(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double d(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? 0.0 : e.getAsDouble();
    }

    /** A snapshot of one {@code builder} row (the shared SpacetimeDB contract). */
    public static final class Builder {
        public int npcId;
        public String name = "";
        public String job = "";
        public String state = "";
        public double x, y, z;
        public String world = "";
        public double homeX, homeY, homeZ;
        public String homeWorld = "";
        public int partyId;
    }
}
