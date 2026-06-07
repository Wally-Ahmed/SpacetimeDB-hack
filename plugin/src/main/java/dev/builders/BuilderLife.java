package dev.builders;

import com.google.gson.JsonElement;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Each Builder's daily LIFE: it places (once) a bed at its home, equips its
 * cosmetic iron tool, walks home and sleeps at night, and walks to its worksite
 * by day. Pure "body" — it only reads the Builder's {@code state}/{@code home_*}
 * from SpacetimeDB and acts via Citizens; the brain decides the state and other
 * plugin classes (BuildSystem / JobMechanics) handle building & job mechanics.
 *
 * <p>Polled on a timer (registered by BuildersPlugin) just like {@link DirectivePoller}:
 * the SQL read runs on an async thread, then every world action hops to the main
 * thread. The whole cycle is wrapped in {@code try/catch(Throwable)} so a stray
 * exception never cancels the repeating task.
 */
public class BuilderLife implements Runnable {
    private final BuildersPlugin plugin;
    private final Stdb stdb;
    private final BuilderManager builders;

    /** Homes we've already placed a bed at, keyed by world+rounded coords (never re-bed). */
    private final Set<String> bedded = Collections.synchronizedSet(new HashSet<>());
    /** NPC ids currently being walked to bed, so we don't spawn a new watcher every tick. */
    private final Set<Integer> headingToBed = Collections.synchronizedSet(new HashSet<>());

    public BuilderLife(BuildersPlugin plugin, Stdb stdb, BuilderManager builders) {
        this.plugin = plugin;
        this.stdb = stdb;
        this.builders = builders;
    }

    @Override
    public void run() {
      try {
        List<Map<String, JsonElement>> rows = stdb.sql(
                "SELECT npc_id, name, job, state, home_x, home_y, home_z, home_world, held_item, party_id FROM builder");
        for (Map<String, JsonElement> row : rows) {
            if (row.get("npc_id") == null) continue;
            int npcId = row.get("npc_id").getAsInt();
            long partyId = row.get("party_id") != null && !row.get("party_id").isJsonNull()
                    ? row.get("party_id").getAsLong() : 0L;
            String state = str(row, "state");
            String heldItem = str(row, "held_item");
            String homeWorld = str(row, "home_world");
            double hx = dbl(row, "home_x");
            double hy = dbl(row, "home_y");
            double hz = dbl(row, "home_z");
            // Everything below touches Citizens/the world → main thread only.
            Bukkit.getScheduler().runTask(plugin,
                    () -> tickBuilder(npcId, partyId, state, heldItem, homeWorld, hx, hy, hz));
        }
      } catch (Throwable t) {
        plugin.getLogger().warning("[life] cycle error: " + t);
      }
    }

    /** Main-thread per-Builder step. */
    private void tickBuilder(int npcId, long partyId, String state, String heldItem,
                             String homeWorld, double hx, double hy, double hz) {
        NPC npc = builders.byId(npcId);
        if (npc == null || !npc.isSpawned()) return;
        // Recruited → it's running the quest loop (DirectivePoller drives it); leave it be.
        if (partyId != 0) {
            headingToBed.remove(npcId);
            wake(npc);
            return;
        }

        equip(npc, heldItem);

        Location home = homeLocation(npc, homeWorld, hx, hy, hz);
        if (home != null) placeBedOnce(home);

        switch (state == null ? "" : state) {
            case "sleeping":
                if (home != null) goToBed(npc, home);
                break;
            case "working":
                headingToBed.remove(npcId);
                wake(npc);                              // clear any leftover sleep pose
                if (home != null) walkTo(npc, home);   // anchor only; mechanics handled elsewhere
                break;
            default:
                // "building" | "idle" | "fighting" | "recruiting" → handled by other classes/arbiter.
                headingToBed.remove(npcId);
                wake(npc);                              // ensure it's upright, not stuck lying down
                break;
        }
    }

    /** Cosmetically set the NPC's main hand to the material named by held_item. */
    private void equip(NPC npc, String heldItem) {
        if (heldItem == null || heldItem.isEmpty()) return;
        try {
            Material mat = Material.matchMaterial(heldItem);
            if (mat == null) mat = Material.valueOf(heldItem.toUpperCase());
            Equipment eq = npc.getOrAddTrait(Equipment.class);
            ItemStack current = eq.get(Equipment.EquipmentSlot.HAND);
            if (current == null || current.getType() != mat) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(mat));
            }
        } catch (Throwable t) {
            // Bad material name or trait hiccup — cosmetic only, ignore.
        }
    }

    /** Resolve the home Location, falling back to the NPC's current world if home_world is blank. */
    private Location homeLocation(NPC npc, String homeWorld, double hx, double hy, double hz) {
        if (hx == 0 && hy == 0 && hz == 0) return null; // home not set yet
        World w = null;
        if (homeWorld != null && !homeWorld.isEmpty()) w = Bukkit.getWorld(homeWorld);
        if (w == null && npc.getEntity() != null) w = npc.getEntity().getWorld();
        if (w == null) return null;
        return new Location(w, hx, hy, hz);
    }

    /**
     * Place a bed at the home location exactly once (additive — never overwrites a
     * village). We skip if a bed already sits there, and remember placed homes in
     * {@link #bedded} so we don't keep re-placing it every tick.
     */
    private void placeBedOnce(Location home) {
        String key = bedKey(home);
        if (bedded.contains(key)) return;
        try {
            Block foot = home.getBlock();
            // Already a bed there (ours or a village's)? Mark done, leave it alone.
            if (isBed(foot.getType()) || isBed(foot.getRelative(BlockFace.NORTH).getType())
                    || isBed(foot.getRelative(BlockFace.SOUTH).getType())
                    || isBed(foot.getRelative(BlockFace.EAST).getType())
                    || isBed(foot.getRelative(BlockFace.WEST).getType())) {
                bedded.add(key);
                return;
            }
            // Need two free, supported cells (foot + head) to lay a bed.
            Block head = foot.getRelative(BlockFace.EAST);
            if (!isReplaceable(foot) || !isReplaceable(head)) {
                // Don't force it into solid ground / a structure; try again later only if it clears.
                return;
            }
            placeBedPart(foot, BlockFace.EAST, Bed.Part.FOOT);
            placeBedPart(head, BlockFace.EAST, Bed.Part.HEAD);
            bedded.add(key);
        } catch (Throwable t) {
            // World op failed (chunk unloaded, etc.) — try again next tick.
        }
    }

    private void placeBedPart(Block block, BlockFace facing, Bed.Part part) {
        block.setType(Material.RED_BED, false);
        try {
            Bed data = (Bed) block.getBlockData();
            data.setFacing(facing);
            data.setPart(part);
            block.setBlockData(data, false);
        } catch (Throwable ignored) {
            // If block data shaping fails the bed material is still placed; good enough.
        }
    }

    /**
     * Walk to the home bed; once within ~2 blocks, put the Builder into a sleeping
     * pose (SleepTrait → SitTrait → EntityPoseTrait, whichever the build supports;
     * else just stop at the bed). An 8s teleport fallback guarantees arrival, mirroring
     * {@link DirectivePoller}. A per-NPC guard stops us re-launching the watcher each tick.
     */
    private void goToBed(NPC npc, Location bed) {
        if (alreadyAtBed(npc, bed)) {
            applySleepPose(npc, bed);
            return;
        }
        if (!headingToBed.add(npc.getId())) return; // a watcher is already running for this NPC

        walkTo(npc, bed);
        final int id = npc.getId();
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                NPC live = builders.byId(id);
                if (live == null || !live.isSpawned()) { headingToBed.remove(id); cancel(); return; }

                if (alreadyAtBed(live, bed)) {
                    applySleepPose(live, bed);
                    headingToBed.remove(id);
                    cancel();
                    return;
                }

                ticks += 10;
                if (ticks >= 160) { // 8s teleport fallback to the bed
                    if (live.getEntity() != null) live.getEntity().teleport(bedStand(bed));
                    applySleepPose(live, bed);
                    headingToBed.remove(id);
                    cancel();
                    return;
                }
                walkTo(live, bed);
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    private boolean alreadyAtBed(NPC npc, Location bed) {
        if (npc.getEntity() == null) return false;
        Location at = npc.getEntity().getLocation();
        if (at.getWorld() == null || !at.getWorld().equals(bed.getWorld())) return false;
        return at.distanceSquared(bed) <= 4.0; // within ~2 blocks
    }

    /** Best-effort sleep/sit pose at the bed; degrade gracefully across Citizens versions. */
    private void applySleepPose(NPC npc, Location bed) {
        Location stand = bedStand(bed);
        // 1) Proper sleeping pose (lays the NPC in the bed) if SleepTrait is present.
        try {
            net.citizensnpcs.trait.SleepTrait sleep =
                    npc.getOrAddTrait(net.citizensnpcs.trait.SleepTrait.class);
            sleep.setSleeping(bed);
            return;
        } catch (Throwable ignored) {}
        // 2) Otherwise sit at the bed.
        try {
            net.citizensnpcs.trait.SitTrait sit =
                    npc.getOrAddTrait(net.citizensnpcs.trait.SitTrait.class);
            sit.setSitting(stand);
            return;
        } catch (Throwable ignored) {}
        // 3) Otherwise nudge the entity pose to SLEEPING if that trait exists.
        try {
            net.citizensnpcs.trait.EntityPoseTrait pose =
                    npc.getOrAddTrait(net.citizensnpcs.trait.EntityPoseTrait.class);
            pose.setPose(net.citizensnpcs.trait.EntityPoseTrait.EntityPose.SLEEPING);
            return;
        } catch (Throwable ignored) {}
        // 4) Last resort: just make sure it's standing at the bed and stop pathing.
        try {
            npc.getNavigator().cancelNavigation();
            if (npc.getEntity() != null) npc.getEntity().teleport(stand);
        } catch (Throwable ignored) {}
    }

    /**
     * Clear any sleep/sit pose so a Builder stands back up when it stops sleeping.
     * Without this, a Builder that slept at night stays flat on its back forever
     * once day returns (the SleepTrait persists). Idempotent + version-tolerant.
     */
    private void wake(NPC npc) {
        removeTraitSafe(npc, net.citizensnpcs.trait.SleepTrait.class);
        removeTraitSafe(npc, net.citizensnpcs.trait.SitTrait.class);
        try {
            if (npc.hasTrait(net.citizensnpcs.trait.EntityPoseTrait.class)) {
                npc.getOrAddTrait(net.citizensnpcs.trait.EntityPoseTrait.class)
                        .setPose(net.citizensnpcs.trait.EntityPoseTrait.EntityPose.STANDING);
            }
        } catch (Throwable ignored) {}
    }

    private void removeTraitSafe(NPC npc, Class<? extends net.citizensnpcs.api.trait.Trait> t) {
        try { if (npc.hasTrait(t)) npc.removeTrait(t); } catch (Throwable ignored) {}
    }

    /** Navigate toward a target; teleport-to-world fallback if Citizens nav refuses cross-world. */
    private void walkTo(NPC npc, Location target) {
        try {
            if (npc.getEntity() != null && target.getWorld() != null
                    && !npc.getEntity().getWorld().equals(target.getWorld())) {
                npc.getEntity().teleport(target); // nav can't cross worlds; jump there
                return;
            }
            npc.getNavigator().setTarget(target);
        } catch (Throwable t) {
            // Pathfinding occasionally throws on unloaded chunks; the timer retries.
        }
    }

    private static Location bedStand(Location bed) {
        // Stand on top of the bed block, centered, facing along the bed.
        return new Location(bed.getWorld(), Math.floor(bed.getX()) + 0.5, bed.getY(),
                Math.floor(bed.getZ()) + 0.5);
    }

    private static boolean isBed(Material m) {
        return m != null && m.name().endsWith("_BED");
    }

    private static boolean isReplaceable(Block b) {
        Material m = b.getType();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
                || m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                || m == Material.SNOW || m == Material.WATER || isBed(m);
    }

    private static String bedKey(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName())
                + ":" + (int) Math.floor(l.getX())
                + ":" + (int) Math.floor(l.getY())
                + ":" + (int) Math.floor(l.getZ());
    }

    private static String str(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double dbl(Map<String, JsonElement> row, String key) {
        JsonElement e = row.get(key);
        return e == null || e.isJsonNull() ? 0.0 : e.getAsDouble();
    }
}
