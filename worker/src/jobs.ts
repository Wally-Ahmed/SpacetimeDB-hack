// Builder Life — the "jobs brain".
//
// Called once per builderTick (~12s) for every Builder near an online player.
// This is the WORKER side of the Builder Life feature: it writes desired STDB
// state via reducers; the Paper plugin (other agents) reads that state and
// executes it. Subsystems are coupled ONLY through SpacetimeDB.
//
// Responsibilities (Phase 1, Agent A — see docs/superpowers/specs/2026-06-07-builder-life-design.md):
//   1. assign a job + home/bed + cosmetic held tool to any jobless Builder
//   2. drive the state machine (recruiting > sleeping > building > working)
//   3. enqueue one job-themed build per Builder near their home
//   4. restock the block inventory each Builder's craft needs while sleeping
//
// Claiming/advancing build jobs and the local "fighting" override are the
// plugin's job, NOT the worker's — we never touch those here.
//
// Everything is idempotent: each tick re-derives the desired state and only
// writes when something actually needs to change, so it is safe to call every
// tick for every Builder.

// ---------------------------------------------------------------------------
// Static job tables — keyed by Builder NAME (the 8 named Builders in the town).
// ---------------------------------------------------------------------------

interface JobDef {
  job: string;
  heldItem: string; // cosmetic tool material
  buildKind: string; // build_job.kind this builder constructs
  total: number; // template size (build steps)
  /** Blocks to keep stocked so building/working can proceed by day. */
  restock: { item: string; count: number };
}

/** name -> job definition. Anyone not listed gets no job (left as ""). */
// NOTE: `total` MUST match the block count of the matching template in the plugin's
// BuildSystem.java (it's the progress-bar denominator; BuildSystem is authoritative for
// actual completion, so a small drift only skews the bar, never stalls a build).
const JOBS: Record<string, JobDef> = {
  Thrain: { job: 'mason', heldItem: 'IRON_PICKAXE', buildKind: 'house', total: 60, restock: { item: 'COBBLESTONE', count: 64 } },
  Borin: { job: 'smith', heldItem: 'IRON_AXE', buildKind: 'forge', total: 14, restock: { item: 'STONE', count: 64 } },
  Mira: { job: 'farmer', heldItem: 'IRON_HOE', buildKind: 'farm', total: 17, restock: { item: 'WHEAT', count: 64 } },
  Kael: { job: 'miner', heldItem: 'IRON_PICKAXE', buildKind: 'mine', total: 13, restock: { item: 'STONE', count: 64 } },
  Vyssa: { job: 'fisher', heldItem: 'FISHING_ROD', buildKind: 'dock', total: 12, restock: { item: 'OAK_PLANKS', count: 64 } },
  Dorin: { job: 'shepherd', heldItem: 'SHEARS', buildKind: 'pen', total: 16, restock: { item: 'OAK_FENCE', count: 64 } },
  Eldra: { job: 'lumberjack', heldItem: 'IRON_AXE', buildKind: 'cabin', total: 43, restock: { item: 'OAK_LOG', count: 64 } },
  Lyra: { job: 'guard', heldItem: 'IRON_SWORD', buildKind: 'tower', total: 40, restock: { item: 'COBBLESTONE', count: 64 } },
};

// ---------------------------------------------------------------------------
// Deterministic per-builder offsets so beds / build sites never overlap.
// We hash npcId into a small grid cell; both home and site are derived from the
// Builder's current x/z so the town clusters around wherever they spawned.
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Town layout: every Builder clusters on a single grid anchored to ONE cached
// town center, so the structures form a tidy village instead of scattering to
// wherever each Builder happened to wander. Slots are 16-18 blocks apart so 5x5
// structures never overlap. (BuildSystem ground-snaps each structure's Y to the
// terrain surface, so uneven ground no longer leaves buildings floating/buried.)
// ---------------------------------------------------------------------------

/** Cached town center — set once from the first Builder seen near a player. */
let townAnchor: { x: number; z: number; world: string } | null = null;

/** Stable slot index per Builder name (independent of wander position / npcId). */
const NAME_INDEX: Record<string, number> = {
  Thrain: 0, Borin: 1, Mira: 2, Kael: 3, Vyssa: 4, Dorin: 5, Eldra: 6, Lyra: 7,
};

/** Grid cell (in blocks) for a Builder's home/bed, relative to the town anchor. */
function townSlot(name: string): { dx: number; dz: number } {
  const i = NAME_INDEX[name] ?? 0;
  const cols = 4;
  return { dx: (i % cols) * 16 - 24, dz: Math.floor(i / cols) * 18 - 9 };
}

// ---------------------------------------------------------------------------
// State machine — pure function so it's trivially testable / inspectable.
// Priority (high -> low): recruiting > sleeping > building > working.
// We deliberately do NOT emit "fighting" or "idle": the plugin owns the local
// "fighting" override, and every Builder here is near a player so has a job.
// ---------------------------------------------------------------------------

function desiredState(b: any, phase: string, hasUnfinishedBuild: boolean, hasDoneBuild: boolean): string {
  if (b.partyId !== 0n) return 'recruiting';
  if (phase === 'night') return 'sleeping';
  if (hasUnfinishedBuild || !hasDoneBuild) return 'building';
  return 'working';
}

// ---------------------------------------------------------------------------
// Main entry — call once per builderTick for the Builders that are "awake".
//
//   builders: the Builder rows to drive (already filtered to "near a player"
//             by the caller, so we don't burn reducers on dormant chunks).
//   phase:    world clock phase ('day' | 'night').
//
// All reducer calls mirror index.ts style: conn.reducers.xxx({ camelCaseArgs }).
// builder_id / npc_id are i32 (plain numbers); party_id / build_job.id are u64
// (bigint, compared against 0n). total/step/count are i32/u32 (plain numbers).
// ---------------------------------------------------------------------------

export async function runBuilderJobs(conn: any, builders: any[], phase: string): Promise<void> {
  // Snapshot the queue once per tick (cheap; avoids re-iterating per builder).
  const buildJobs = [...conn.db.buildJob.iter()];

  // Establish the town center ONCE, from the lowest-npcId Builder near a player.
  if (!townAnchor && builders.length) {
    const lead = [...builders].sort((a, b) => a.npcId - b.npcId)[0];
    townAnchor = { x: lead.x, z: lead.z, world: lead.world };
  }

  for (const b of builders) {
    const def = JOBS[b.name];
    if (!def) continue; // unnamed / non-life Builder — leave it to the legacy loop.

    // --- 1. Job assignment (first sight) ----------------------------------
    if (!b.job || b.job === '') {
      const anchor = townAnchor ?? { x: b.x, z: b.z, world: b.world };
      const { dx, dz } = townSlot(b.name);
      try {
        await conn.reducers.assignJob({
          builderId: b.npcId,
          job: def.job,
          homeX: anchor.x + dx,
          homeY: b.y,
          homeZ: anchor.z + dz,
          homeWorld: anchor.world || b.world,
          heldItem: def.heldItem,
        });
        console.log(`[jobs] ${b.name} assigned ${def.job} (held ${def.heldItem})`);
      } catch {
        // already assigned / reducer aborted — fine, next tick reflects it.
      }
      // The local row won't show job/home until the next subscription update,
      // so defer the rest of the pipeline for this builder to the next tick.
      continue;
    }

    // --- this builder's build jobs ----------------------------------------
    const myJobs = buildJobs.filter((j: any) => j.builderId === b.npcId && j.kind === def.buildKind);
    const hasAnyOfKind = myJobs.length > 0;
    const hasDoneBuild = myJobs.some((j: any) => j.status === 'done');
    const hasUnfinishedBuild = myJobs.some((j: any) => j.status !== 'done');

    // --- 2. State machine -------------------------------------------------
    const want = desiredState(b, phase, hasUnfinishedBuild, hasDoneBuild);
    if (b.state !== want) {
      try {
        await conn.reducers.setBuilderState({ builderId: b.npcId, state: want });
        console.log(`[jobs] ${b.name} ${b.state || '∅'} -> ${want}`);
      } catch {}
    }

    // --- 3. Enqueue one build of this builder's kind ----------------------
    if (!hasAnyOfKind) {
      // Structure sits a few blocks beside the bed, inside this Builder's town slot.
      const dx = 4, dz = 4;
      const baseX = typeof b.homeX === 'number' ? b.homeX : b.x;
      const baseY = typeof b.homeY === 'number' ? b.homeY : b.y;
      const baseZ = typeof b.homeZ === 'number' ? b.homeZ : b.z;
      const world = b.homeWorld || b.world;
      try {
        await conn.reducers.enqueueBuild({
          builderId: b.npcId,
          kind: def.buildKind,
          x: baseX + dx,
          y: baseY,
          z: baseZ + dz,
          world,
          total: def.total,
        });
        console.log(`[jobs] ${b.name} enqueued ${def.buildKind} build (${def.total} steps)`);
      } catch {}
    }

    // --- 4. Restock blocks while sleeping (so day-work can proceed) --------
    if (want === 'sleeping') {
      try {
        await conn.reducers.setInventory({
          builderId: b.npcId,
          item: def.restock.item,
          count: def.restock.count,
        });
      } catch {}
    }
  }
}
