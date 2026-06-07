// Builders — AI worker ("the brain").
//
// Connects to SpacetimeDB with the official TS SDK, subscribes to the whole
// world, and runs two loops:
//   - plannerTick (~20s): decide whether to send a Builder to recruit a player,
//     pick a progression-appropriate quest, and call request_recruitment.
//   - builderTick (~12s): give each Builder near a player a schedule + the
//     occasional idle thought or line of gossip.
// All "thinking" is gated to Builders near an online player (loaded chunks).

import 'dotenv/config';
import { DbConnection, reducers as _r } from './module_bindings/index.ts';
import { pickFrontierQuest, TIERS } from './advancements.ts';
import { inviteLine, idleThought, gossipLine, encourage, llmStatus } from './llm.ts';

const URI = process.env.STDB_URI || 'ws://127.0.0.1:3050';
const DB = process.env.STDB_DB || 'builders-rpg';

const PLANNER_MS = 20_000;
const BUILDER_MS = 12_000;
const THINK_RADIUS = 64; // blocks; only think near players (active/loaded)
const GOSSIP_RADIUS = 24;

function capForDifficulty(d: string): number {
  return d === 'easy' ? 2 : d === 'hard' ? 5 : 4;
}

function dist(a: { x: number; z: number }, b: { x: number; z: number }): number {
  const dx = a.x - b.x;
  const dz = a.z - b.z;
  return Math.sqrt(dx * dx + dz * dz);
}

function clock(conn: any): { phase: string; phaseId: bigint } {
  const c = [...conn.db.worldClock.iter()][0];
  return { phase: c?.phase ?? 'day', phaseId: c?.phaseId ?? 0n };
}

let plannerRunning = false;
let builderRunning = false;

async function plannerTick(conn: any) {
  if (plannerRunning) return;
  plannerRunning = true;
  try {
    const { phase, phaseId } = clock(conn);
    const players = [...conn.db.player.iter()].filter((p: any) => p.online && p.partyId === 0n);
    if (players.length === 0) return;

    const allQuests = [...conn.db.quest.iter()];
    const allBuilders = [...conn.db.builder.iter()];
    const allAdv = [...conn.db.playerAdvancement.iter()];
    const budgets = [...conn.db.recruitBudget.iter()];

    for (const p of players) {
      // already has a live offer / active quest?
      if (allQuests.some((q: any) => q.playerUuid === p.uuid && (q.state === 'offered' || q.state === 'active'))) continue;

      // budget pre-check (mirrors the reducer) — avoids wasted LLM calls
      const b = budgets.find((x: any) => x.playerUuid === p.uuid);
      const used = b && b.phaseId === phaseId ? b.used : 0;
      if (used >= capForDifficulty(p.difficulty)) continue;

      // progression frontier
      const completed = new Set(
        allAdv.filter((a: any) => a.playerUuid === p.uuid && a.done).map((a: any) => a.advancementId)
      );
      const pick = pickFrontierQuest(completed, p.difficulty);
      if (!pick) continue;

      // choose a free Builder, nearest in the same world if possible
      const free = allBuilders.filter((x: any) => x.partyId === 0n);
      if (free.length === 0) continue;
      const sameWorld = free.filter((x: any) => x.world === p.world);
      const pool = sameWorld.length ? sameWorld : free;
      pool.sort((m: any, n: any) => dist(m, p) - dist(n, p));
      const builder = pool[0];

      const line = await inviteLine(builder, p.name, pick.adv, phase);
      try {
        await conn.reducers.requestRecruitment({
          playerUuid: p.uuid,
          builderId: builder.npcId,
          advancementId: pick.adv.id,
          title: pick.adv.title,
          description: line,
          tier: pick.tier,
        });
        console.log(`[planner] ${builder.name} → ${p.name}: "${pick.adv.title}" (${pick.tierName}) | ${phase}`);
      } catch (e) {
        // reducer aborted (budget / already partied) — expected sometimes
      }
    }
  } finally {
    plannerRunning = false;
  }
}

async function builderTick(conn: any) {
  if (builderRunning) return;
  builderRunning = true;
  try {
    const onlinePlayers = [...conn.db.player.iter()].filter((p: any) => p.online);
    if (onlinePlayers.length === 0) return;
    const { phase } = clock(conn);
    const builders = [...conn.db.builder.iter()];
    const parties = [...conn.db.party.iter()];
    const quests = [...conn.db.quest.iter()];

    for (const b of builders) {
      const near = onlinePlayers.some((p: any) => p.world === b.world && dist(b, p) < THINK_RADIUS);
      if (!near) continue;
      const schedule = phase === 'night' ? 'sleep' : Math.random() < 0.5 ? 'work' : 'wander';

      // party encouragement
      if (b.partyId !== 0n && Math.random() < 0.4) {
        const party = parties.find((pt: any) => pt.id === b.partyId);
        const player = party && onlinePlayers.find((p: any) => p.uuid === party.playerUuid);
        const q = party && quests.find((x: any) => x.id === party.questId);
        if (player && q) {
          const adv = TIERS.flatMap((t) => t.advs).find((a) => a.id === q.advancementId) ?? {
            id: q.advancementId,
            title: q.title,
            hint: q.title,
          };
          const line = await encourage(b, player.name, adv);
          try {
            await conn.reducers.builderSay({ builderId: b.npcId, playerUuid: player.uuid, text: line });
          } catch {}
        }
        continue;
      }

      // idle thought (for the dashboard)
      if (Math.random() < 0.4) {
        const thought = await idleThought(b, phase);
        try {
          await conn.reducers.submitThinkResult({ builderId: b.npcId, thought, schedule });
        } catch {}
      }

      // gossip with another nearby Builder
      if (Math.random() < 0.2) {
        const others = builders.filter(
          (o: any) => o.npcId !== b.npcId && o.world === b.world && dist(o, b) < GOSSIP_RADIUS
        );
        if (others.length) {
          const other = others[Math.floor(Math.random() * others.length)];
          const line = await gossipLine(b, other, phase);
          try {
            await conn.reducers.postChat({
              builderId: b.npcId,
              speaker: b.name,
              text: line,
              audience: `builder:${other.npcId}`,
            });
          } catch {}
        }
      }
    }
  } finally {
    builderRunning = false;
  }
}

function startLoops(conn: any) {
  console.log('[worker] loops started — planner every', PLANNER_MS / 1000, 's, builders every', BUILDER_MS / 1000, 's');
  setInterval(() => plannerTick(conn).catch((e) => console.error('[planner] error', e)), PLANNER_MS);
  setInterval(() => builderTick(conn).catch((e) => console.error('[builder] error', e)), BUILDER_MS);
  // kick off soon after the cache fills
  setTimeout(() => plannerTick(conn).catch(() => {}), 4000);
}

console.log(`[worker] connecting to ${URI} / ${DB}`);
console.log(`[worker] LLM: ${llmStatus()}`);

DbConnection.builder()
  .withUri(URI)
  .withDatabaseName(DB)
  .onConnect((conn: any) => {
    console.log('[worker] connected; subscribing to all tables…');
    conn
      .subscriptionBuilder()
      .onApplied(() => {
        const n = [...conn.db.builder.iter()].length;
        console.log(`[worker] subscription applied (${n} builders in world)`);
        startLoops(conn);
      })
      .onError((_ctx: any, err: any) => console.error('[worker] subscription error', err))
      .subscribeToAllTables();
  })
  .onConnectError((_ctx: any, err: Error) => console.error('[worker] connect error:', err.message))
  .onDisconnect(() => console.log('[worker] disconnected'))
  .build();
