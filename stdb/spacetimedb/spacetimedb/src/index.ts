// Builders — SpacetimeDB module (the shared world / "brain bus").
//
// Trust model: the ONLY writers are the trusted backends — the Minecraft Paper
// plugin (over the HTTP API, anonymous) and the AI worker (over the TS SDK).
// Players never connect to SpacetimeDB directly, so entities are keyed by
// explicit ids passed as arguments (Minecraft player UUID strings, Citizens NPC
// ids) rather than `ctx.sender`. All tables are public so the read-only dashboard
// and the worker can subscribe. snake_case everywhere for clean cross-language SQL.

import { schema, table, t } from 'spacetimedb/server';

// ----------------------------------------------------------------------------
// Tables
// ----------------------------------------------------------------------------

const player = table(
  { name: 'player', public: true },
  {
    uuid: t.string().primaryKey(), // Minecraft player UUID
    name: t.string(),
    online: t.bool(),
    difficulty: t.string(), // "easy" | "med" | "hard"
    party_id: t.u64(), // 0n = not in a party
    x: t.f64(),
    y: t.f64(),
    z: t.f64(),
    world: t.string(),
    updated_at: t.timestamp(),
  }
);

const builder = table(
  { name: 'builder', public: true },
  {
    npc_id: t.i32().primaryKey(), // Citizens NPC id, assigned by the plugin
    name: t.string(),
    persona: t.string(),
    town: t.string(),
    schedule: t.string(), // "work" | "wander" | "sleep"
    thought: t.string(), // current idle thought (for dashboard)
    party_id: t.u64(), // 0n = free
    x: t.f64(),
    y: t.f64(),
    z: t.f64(),
    world: t.string(),
    job: t.string(), // "" until assigned; mason/smith/farmer/miner/fisher/shepherd/lumberjack/guard
    home_x: t.f64(),
    home_y: t.f64(),
    home_z: t.f64(),
    home_world: t.string(),
    state: t.string(), // "idle"|"working"|"building"|"sleeping"|"fighting"|"recruiting"
    held_item: t.string(), // cosmetic tool material, e.g. "IRON_HOE"
    personality: t.string(), // "" until set; brave|timid|cheerful|gruff|scholarly|greedy|kind|paranoid
    backstory: t.string(), // one-line bio for the profile/dashboard
    updated_at: t.timestamp(),
  }
);

const quest = table(
  { name: 'quest', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    player_uuid: t.string().index('btree'),
    builder_id: t.i32().index('btree'),
    advancement_id: t.string(), // e.g. "minecraft:story/smelt_iron"
    title: t.string(),
    description: t.string(), // LLM invite line / quest text
    tier: t.i32(),
    state: t.string().index('btree'), // "offered" | "active" | "done" | "declined"
    created_at: t.timestamp(),
  }
);

const party = table(
  { name: 'party', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    player_uuid: t.string().index('btree'),
    builder_id: t.i32(),
    quest_id: t.u64(),
    created_at: t.timestamp(),
  }
);

// Brain -> body command queue. The worker/reducers write directives; the plugin
// polls `consumed = false`, acts, then calls consume_directive.
const storyDirective = table(
  { name: 'story_directive', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    kind: t.string(), // "approach_player" | "say"
    builder_id: t.i32().index('btree'),
    player_uuid: t.string(),
    text: t.string(),
    quest_id: t.u64(), // 0n if none
    consumed: t.bool().index('btree'),
    created_at: t.timestamp(),
  }
);

// Per-player recruitment budget for the current day/night phase.
const recruitBudget = table(
  { name: 'recruit_budget', public: true },
  {
    player_uuid: t.string().primaryKey(),
    phase_id: t.i64(), // identifies the current half-day window
    used: t.i32(),
  }
);

const worldClock = table(
  { name: 'world_clock', public: true },
  {
    id: t.i32().primaryKey(), // always 0 (singleton)
    time_of_day: t.i64(), // 0..24000
    full_time: t.i64(), // total world ticks
    phase: t.string(), // "day" | "night"
    phase_id: t.i64(), // floor(full_time / 12000)
    updated_at: t.timestamp(),
  }
);

const builderMemory = table(
  { name: 'builder_memory', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    builder_id: t.i32().index('btree'),
    kind: t.string(), // "event" | "chat" | "quest"
    text: t.string(),
    created_at: t.timestamp(),
  }
);

const chatMessage = table(
  { name: 'chat_message', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    builder_id: t.i32(),
    speaker: t.string(),
    text: t.string(),
    audience: t.string(), // "world" | "player:<uuid>" | "builder:<id>"
    created_at: t.timestamp(),
  }
);

const playerAdvancement = table(
  { name: 'player_advancement', public: true },
  {
    key: t.string().primaryKey(), // `${uuid}:${advancement_id}`
    player_uuid: t.string().index('btree'),
    advancement_id: t.string(),
    done: t.bool(),
    updated_at: t.timestamp(),
  }
);

// Builder Life: the work-stealing build queue + per-builder block inventory.
const buildJob = table(
  { name: 'build_job', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    builder_id: t.i32().index('btree'), // 0 = unclaimed
    kind: t.string(), // farm|dock|pen|forge|house|wall|cabin|mine|tower
    site_x: t.f64(),
    site_y: t.f64(),
    site_z: t.f64(),
    world: t.string(),
    step: t.u32(),
    total: t.u32(),
    status: t.string().index('btree'), // queued|claimed|in_progress|done
    created_at: t.timestamp(),
  }
);

const builderInventory = table(
  { name: 'builder_inventory', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    builder_id: t.i32().index('btree'),
    item: t.string(),
    count: t.i32(),
  }
);

// ----------------------------------------------------------------------------
// The Director — admin server-control console (chat transcript + confirm-gated
// actions + live disaster scenarios). See docs/superpowers/specs/
// 2026-06-07-director-console-design.md.
// ----------------------------------------------------------------------------

const adminMessage = table(
  { name: 'admin_message', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    session_id: t.string().index('btree'), // "main" by default
    role: t.string(), // "admin" | "director" | "system"
    text: t.string(),
    kind: t.string(), // "chat" | "question" | "proposal" | "system"
    action_id: t.u64(), // links a proposal message to its admin_action (0n if none)
    created_at: t.timestamp(),
  }
);

// The confirm-gate object: a proposed/confirmed server action. The plugin
// executes ONLY rows at status='confirmed'. There is no path from pending->exec.
const adminAction = table(
  { name: 'admin_action', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    session_id: t.string().index('btree'),
    kind: t.string(), // zombie_outbreak|wildfire|storm_flood|meteor|set_time|set_weather|spawn_builders|despawn_builders
    summary: t.string(),
    params_json: t.string(),
    area_x: t.f64(),
    area_y: t.f64(),
    area_z: t.f64(),
    radius: t.f64(),
    world: t.string(),
    status: t.string().index('btree'), // pending|confirmed|executing|executed|rejected|failed
    created_at: t.timestamp(),
    decided_at: t.timestamp(),
  }
);

// A live disaster instance the ScenarioEngine ticks.
const scenario = table(
  { name: 'scenario', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    kind: t.string(), // zombie_outbreak|wildfire|storm_flood|meteor
    status: t.string().index('btree'), // active|ended
    area_x: t.f64(),
    area_y: t.f64(),
    area_z: t.f64(),
    radius: t.f64(),
    world: t.string(),
    intensity: t.i32(), // 0/1/2 = low/med/high
    wave: t.u32(),
    total_waves: t.u32(),
    ends_full_time: t.i64(), // world full_time tick at which it auto-ends
    params_json: t.string(),
    started_at: t.timestamp(),
  }
);

// Player ⇄ Builder proximity chat: a player's inbound line to a nearby Builder.
// The plugin only writes this when a Builder is within talk range of the player.
const playerMessage = table(
  { name: 'player_message', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    builder_id: t.i32().index('btree'),
    player_uuid: t.string(),
    player_name: t.string(),
    text: t.string(),
    responded: t.bool().index('btree'),
    created_at: t.timestamp(),
  }
);

const spacetimedb = schema({
  player,
  builder,
  quest,
  party,
  storyDirective,
  recruitBudget,
  worldClock,
  builderMemory,
  chatMessage,
  playerAdvancement,
  buildJob,
  builderInventory,
  adminMessage,
  adminAction,
  scenario,
  playerMessage,
});
export default spacetimedb;

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

function capForDifficulty(difficulty: string): number {
  switch (difficulty) {
    case 'easy':
      return 2;
    case 'hard':
      return 5;
    case 'med':
    default:
      return 4;
  }
}

// ----------------------------------------------------------------------------
// Lifecycle
// ----------------------------------------------------------------------------

export const init = spacetimedb.init((ctx) => {
  // Seed the singleton world clock so request_recruitment can always read it.
  if (!ctx.db.worldClock.id.find(0)) {
    ctx.db.worldClock.insert({
      id: 0,
      time_of_day: 0n,
      full_time: 0n,
      phase: 'day',
      phase_id: 0n,
      updated_at: ctx.timestamp,
    });
  }
});

// ----------------------------------------------------------------------------
// Reducers — called by the PLUGIN (HTTP)
// ----------------------------------------------------------------------------

export const register_player = spacetimedb.reducer(
  { uuid: t.string(), name: t.string(), difficulty: t.string() },
  (ctx, { uuid, name, difficulty }) => {
    const existing = ctx.db.player.uuid.find(uuid);
    if (existing) {
      ctx.db.player.uuid.update({
        ...existing,
        name,
        difficulty,
        online: true,
        updated_at: ctx.timestamp,
      });
    } else {
      ctx.db.player.insert({
        uuid,
        name,
        online: true,
        difficulty,
        party_id: 0n,
        x: 0,
        y: 0,
        z: 0,
        world: '',
        updated_at: ctx.timestamp,
      });
    }
  }
);

export const set_player_offline = spacetimedb.reducer(
  { uuid: t.string() },
  (ctx, { uuid }) => {
    const existing = ctx.db.player.uuid.find(uuid);
    if (existing) {
      ctx.db.player.uuid.update({ ...existing, online: false, updated_at: ctx.timestamp });
    }
  }
);

export const update_player_pos = spacetimedb.reducer(
  {
    uuid: t.string(),
    name: t.string(),
    x: t.f64(),
    y: t.f64(),
    z: t.f64(),
    world: t.string(),
    difficulty: t.string(),
  },
  (ctx, { uuid, name, x, y, z, world, difficulty }) => {
    const existing = ctx.db.player.uuid.find(uuid);
    if (existing) {
      ctx.db.player.uuid.update({
        ...existing,
        name,
        x,
        y,
        z,
        world,
        difficulty,
        online: true,
        updated_at: ctx.timestamp,
      });
    } else {
      ctx.db.player.insert({
        uuid,
        name,
        online: true,
        difficulty,
        party_id: 0n,
        x,
        y,
        z,
        world,
        updated_at: ctx.timestamp,
      });
    }
  }
);

export const upsert_builder = spacetimedb.reducer(
  {
    npc_id: t.i32(),
    name: t.string(),
    persona: t.string(),
    town: t.string(),
    x: t.f64(),
    y: t.f64(),
    z: t.f64(),
    world: t.string(),
  },
  (ctx, { npc_id, name, persona, town, x, y, z, world }) => {
    const existing = ctx.db.builder.npc_id.find(npc_id);
    if (existing) {
      ctx.db.builder.npc_id.update({
        ...existing,
        name,
        persona,
        town,
        x,
        y,
        z,
        world,
        updated_at: ctx.timestamp,
      });
    } else {
      ctx.db.builder.insert({
        npc_id,
        name,
        persona,
        town,
        schedule: 'wander',
        thought: '',
        party_id: 0n,
        x,
        y,
        z,
        world,
        job: '',
        home_x: 0,
        home_y: 0,
        home_z: 0,
        home_world: '',
        state: 'idle',
        held_item: '',
        personality: '',
        backstory: '',
        updated_at: ctx.timestamp,
      });
    }
  }
);

export const update_builder_pos = spacetimedb.reducer(
  { npc_id: t.i32(), x: t.f64(), y: t.f64(), z: t.f64(), world: t.string() },
  (ctx, { npc_id, x, y, z, world }) => {
    const existing = ctx.db.builder.npc_id.find(npc_id);
    if (existing) {
      ctx.db.builder.npc_id.update({ ...existing, x, y, z, world, updated_at: ctx.timestamp });
    }
  }
);

export const remove_builder = spacetimedb.reducer(
  { npc_id: t.i32() },
  (ctx, { npc_id }) => {
    if (ctx.db.builder.npc_id.find(npc_id)) ctx.db.builder.npc_id.delete(npc_id);
  }
);

// Records a player's advancement; if it satisfies an ACTIVE quest, completes it.
export const set_advancement = spacetimedb.reducer(
  { uuid: t.string(), advancement_id: t.string(), done: t.bool() },
  (ctx, { uuid, advancement_id, done }) => {
    const key = `${uuid}:${advancement_id}`;
    const existing = ctx.db.playerAdvancement.key.find(key);
    if (existing) {
      ctx.db.playerAdvancement.key.update({ ...existing, done, updated_at: ctx.timestamp });
    } else {
      ctx.db.playerAdvancement.insert({
        key,
        player_uuid: uuid,
        advancement_id,
        done,
        updated_at: ctx.timestamp,
      });
    }
    if (!done) return;

    const activeQuests = [...ctx.db.quest.player_uuid.filter(uuid)].filter(
      (q) => q.state === 'active' && q.advancement_id === advancement_id
    );
    for (const q of activeQuests) {
      ctx.db.quest.id.update({ ...q, state: 'done' });
      // disband the party for this quest
      for (const p of [...ctx.db.party.player_uuid.filter(uuid)].filter((p) => p.quest_id === q.id)) {
        ctx.db.party.id.delete(p.id);
      }
      const pl = ctx.db.player.uuid.find(uuid);
      if (pl) ctx.db.player.uuid.update({ ...pl, party_id: 0n });
      const b = ctx.db.builder.npc_id.find(q.builder_id);
      if (b) ctx.db.builder.npc_id.update({ ...b, party_id: 0n });
      const heroName = pl ? pl.name : 'A hero';
      ctx.db.builderMemory.insert({
        id: 0n,
        builder_id: q.builder_id,
        kind: 'quest',
        text: `${heroName} completed "${q.title}" with me.`,
        created_at: ctx.timestamp,
      });
      ctx.db.chatMessage.insert({
        id: 0n,
        builder_id: q.builder_id,
        speaker: b ? b.name : 'Builder',
        text: `Well done! "${q.title}" is complete.`,
        audience: `player:${uuid}`,
        created_at: ctx.timestamp,
      });
      ctx.db.storyDirective.insert({
        id: 0n,
        kind: 'say',
        builder_id: q.builder_id,
        player_uuid: uuid,
        text: `Well done, ${heroName}! You've completed ${q.title}.`,
        quest_id: q.id,
        consumed: false,
        created_at: ctx.timestamp,
      });
    }
  }
);

// Player accepts an offered quest (clicked the offer / right-clicked the NPC).
export const join_party = spacetimedb.reducer(
  { player_uuid: t.string(), builder_id: t.i32() },
  (ctx, { player_uuid, builder_id }) => {
    const offered = [...ctx.db.quest.player_uuid.filter(player_uuid)]
      .filter((q) => q.builder_id === builder_id && q.state === 'offered')
      .sort((a, b) => Number(b.id - a.id));
    if (offered.length === 0) throw new Error('no offered quest to accept');
    const q = offered[0];
    ctx.db.quest.id.update({ ...q, state: 'active' });

    const partyRow = ctx.db.party.insert({
      id: 0n,
      player_uuid,
      builder_id,
      quest_id: q.id,
      created_at: ctx.timestamp,
    });
    const pid = partyRow ? partyRow.id : 0n;

    const pl = ctx.db.player.uuid.find(player_uuid);
    if (pl) ctx.db.player.uuid.update({ ...pl, party_id: pid });
    const b = ctx.db.builder.npc_id.find(builder_id);
    if (b) ctx.db.builder.npc_id.update({ ...b, party_id: pid });

    ctx.db.chatMessage.insert({
      id: 0n,
      builder_id,
      speaker: b ? b.name : 'Builder',
      text: `Splendid — to it, then! ${q.title}.`,
      audience: `player:${player_uuid}`,
      created_at: ctx.timestamp,
    });
    ctx.db.storyDirective.insert({
      id: 0n,
      kind: 'say',
      builder_id,
      player_uuid,
      text: `Splendid! Our quest: ${q.title}.`,
      quest_id: q.id,
      consumed: false,
      created_at: ctx.timestamp,
    });
  }
);

// Accept the player's most-recent pending offer from ANY builder (robust /builders accept).
export const accept_any = spacetimedb.reducer(
  { player_uuid: t.string() },
  (ctx, { player_uuid }) => {
    const offered = [...ctx.db.quest.player_uuid.filter(player_uuid)]
      .filter((q) => q.state === 'offered')
      .sort((a, b) => Number(b.id - a.id));
    if (offered.length === 0) throw new Error('no offered quest to accept');
    const q = offered[0];
    ctx.db.quest.id.update({ ...q, state: 'active' });
    const partyRow = ctx.db.party.insert({
      id: 0n,
      player_uuid,
      builder_id: q.builder_id,
      quest_id: q.id,
      created_at: ctx.timestamp,
    });
    const pid = partyRow ? partyRow.id : 0n;
    const pl = ctx.db.player.uuid.find(player_uuid);
    if (pl) ctx.db.player.uuid.update({ ...pl, party_id: pid });
    const b = ctx.db.builder.npc_id.find(q.builder_id);
    if (b) ctx.db.builder.npc_id.update({ ...b, party_id: pid });
    ctx.db.chatMessage.insert({
      id: 0n,
      builder_id: q.builder_id,
      speaker: b ? b.name : 'Builder',
      text: `Splendid — to it, then! ${q.title}.`,
      audience: `player:${player_uuid}`,
      created_at: ctx.timestamp,
    });
    ctx.db.storyDirective.insert({
      id: 0n,
      kind: 'say',
      builder_id: q.builder_id,
      player_uuid,
      text: `Splendid! Our quest: ${q.title}.`,
      quest_id: q.id,
      consumed: false,
      created_at: ctx.timestamp,
    });
  }
);

export const decline_quest = spacetimedb.reducer(
  { player_uuid: t.string(), builder_id: t.i32() },
  (ctx, { player_uuid, builder_id }) => {
    for (const q of [...ctx.db.quest.player_uuid.filter(player_uuid)].filter(
      (q) => q.builder_id === builder_id && q.state === 'offered'
    )) {
      ctx.db.quest.id.update({ ...q, state: 'declined' });
    }
  }
);

export const set_world_clock = spacetimedb.reducer(
  { time_of_day: t.i64(), full_time: t.i64() },
  (ctx, { time_of_day, full_time }) => {
    const tod = ((time_of_day % 24000n) + 24000n) % 24000n;
    const phase = tod < 12000n ? 'day' : 'night';
    const phase_id = full_time / 12000n;
    const existing = ctx.db.worldClock.id.find(0);
    if (existing) {
      ctx.db.worldClock.id.update({
        ...existing,
        time_of_day,
        full_time,
        phase,
        phase_id,
        updated_at: ctx.timestamp,
      });
    } else {
      ctx.db.worldClock.insert({
        id: 0,
        time_of_day,
        full_time,
        phase,
        phase_id,
        updated_at: ctx.timestamp,
      });
    }
  }
);

export const consume_directive = spacetimedb.reducer(
  { directive_id: t.u64() },
  (ctx, { directive_id }) => {
    const d = ctx.db.storyDirective.id.find(directive_id);
    if (d) ctx.db.storyDirective.id.update({ ...d, consumed: true });
  }
);

// ----------------------------------------------------------------------------
// Reducers — called by the WORKER (SDK)
// ----------------------------------------------------------------------------

// The story planner asks a builder to recruit a player. Caps are enforced HERE,
// atomically: if the player is over budget or already partied, the whole
// transaction aborts and nothing is written.
export const request_recruitment = spacetimedb.reducer(
  {
    player_uuid: t.string(),
    builder_id: t.i32(),
    advancement_id: t.string(),
    title: t.string(),
    description: t.string(),
    tier: t.i32(),
  },
  (ctx, { player_uuid, builder_id, advancement_id, title, description, tier }) => {
    const pl = ctx.db.player.uuid.find(player_uuid);
    if (!pl) throw new Error('unknown player');
    if (pl.party_id !== 0n) throw new Error('player already in a party');

    const clock = ctx.db.worldClock.id.find(0);
    const phase_id = clock ? clock.phase_id : 0n;

    const budget = ctx.db.recruitBudget.player_uuid.find(player_uuid);
    const used = budget && budget.phase_id === phase_id ? budget.used : 0;
    const cap = capForDifficulty(pl.difficulty);
    if (used >= cap) throw new Error('recruit budget exhausted for this phase');

    // avoid stacking duplicate offers from the same builder
    const dupes = [...ctx.db.quest.player_uuid.filter(player_uuid)].filter(
      (q) => q.builder_id === builder_id && q.state === 'offered'
    );
    if (dupes.length > 0) throw new Error('offer already pending from this builder');

    if (budget) {
      ctx.db.recruitBudget.player_uuid.update({ ...budget, phase_id, used: used + 1 });
    } else {
      ctx.db.recruitBudget.insert({ player_uuid, phase_id, used: 1 });
    }

    const q = ctx.db.quest.insert({
      id: 0n,
      player_uuid,
      builder_id,
      advancement_id,
      title,
      description,
      tier,
      state: 'offered',
      created_at: ctx.timestamp,
    });
    const qid = q ? q.id : 0n;

    ctx.db.storyDirective.insert({
      id: 0n,
      kind: 'approach_player',
      builder_id,
      player_uuid,
      text: description,
      quest_id: qid,
      consumed: false,
      created_at: ctx.timestamp,
    });
  }
);

export const submit_think_result = spacetimedb.reducer(
  { builder_id: t.i32(), thought: t.string(), schedule: t.string() },
  (ctx, { builder_id, thought, schedule }) => {
    const b = ctx.db.builder.npc_id.find(builder_id);
    if (b) ctx.db.builder.npc_id.update({ ...b, thought, schedule, updated_at: ctx.timestamp });
  }
);

// Ambient gossip / world chatter (dashboard only).
export const post_chat = spacetimedb.reducer(
  { builder_id: t.i32(), speaker: t.string(), text: t.string(), audience: t.string() },
  (ctx, { builder_id, speaker, text, audience }) => {
    ctx.db.chatMessage.insert({
      id: 0n,
      builder_id,
      speaker,
      text,
      audience,
      created_at: ctx.timestamp,
    });
  }
);

// Builder speaks directly to a player: shows in-game (via directive) AND on dashboard.
export const builder_say = spacetimedb.reducer(
  { builder_id: t.i32(), player_uuid: t.string(), text: t.string() },
  (ctx, { builder_id, player_uuid, text }) => {
    const b = ctx.db.builder.npc_id.find(builder_id);
    ctx.db.chatMessage.insert({
      id: 0n,
      builder_id,
      speaker: b ? b.name : 'Builder',
      text,
      audience: `player:${player_uuid}`,
      created_at: ctx.timestamp,
    });
    ctx.db.storyDirective.insert({
      id: 0n,
      kind: 'say',
      builder_id,
      player_uuid,
      text,
      quest_id: 0n,
      consumed: false,
      created_at: ctx.timestamp,
    });
  }
);

export const add_memory = spacetimedb.reducer(
  { builder_id: t.i32(), kind: t.string(), text: t.string() },
  (ctx, { builder_id, kind, text }) => {
    ctx.db.builderMemory.insert({
      id: 0n,
      builder_id,
      kind,
      text,
      created_at: ctx.timestamp,
    });
  }
);

// ----------------------------------------------------------------------------
// Reducers — Builder Life (jobs, beds, build-job queue, inventory)
// ----------------------------------------------------------------------------

// Worker assigns a profession + a home/bed location + cosmetic tool to a Builder.
export const assign_job = spacetimedb.reducer(
  {
    builder_id: t.i32(),
    job: t.string(),
    home_x: t.f64(),
    home_y: t.f64(),
    home_z: t.f64(),
    home_world: t.string(),
    held_item: t.string(),
  },
  (ctx, { builder_id, job, home_x, home_y, home_z, home_world, held_item }) => {
    const b = ctx.db.builder.npc_id.find(builder_id);
    if (b) {
      ctx.db.builder.npc_id.update({
        ...b, job, home_x, home_y, home_z, home_world, held_item, updated_at: ctx.timestamp,
      });
    }
  }
);

// Worker drives the day/night/behaviour state; mirrored into `schedule` for back-compat.
export const set_builder_state = spacetimedb.reducer(
  { builder_id: t.i32(), state: t.string() },
  (ctx, { builder_id, state }) => {
    const b = ctx.db.builder.npc_id.find(builder_id);
    if (b) ctx.db.builder.npc_id.update({ ...b, state, schedule: state, updated_at: ctx.timestamp });
  }
);

// Queue a build job for a Builder (skips if one of the same kind is already active).
export const enqueue_build = spacetimedb.reducer(
  {
    builder_id: t.i32(),
    kind: t.string(),
    x: t.f64(),
    y: t.f64(),
    z: t.f64(),
    world: t.string(),
    total: t.u32(),
  },
  (ctx, { builder_id, kind, x, y, z, world, total }) => {
    const dupes = [...ctx.db.buildJob.builder_id.filter(builder_id)].filter(
      (j) => j.kind === kind && j.status !== 'done'
    );
    if (dupes.length > 0) return;
    ctx.db.buildJob.insert({
      id: 0n,
      builder_id,
      kind,
      site_x: x,
      site_y: y,
      site_z: z,
      world,
      step: 0,
      total,
      status: 'queued',
      created_at: ctx.timestamp,
    });
  }
);

// Work-stealing claim: a free Builder takes an unclaimed queued job.
export const claim_build_job = spacetimedb.reducer(
  { builder_id: t.i32(), job_id: t.u64() },
  (ctx, { builder_id, job_id }) => {
    const j = ctx.db.buildJob.id.find(job_id);
    if (j && j.status === 'queued' && j.builder_id === 0) {
      ctx.db.buildJob.id.update({ ...j, builder_id, status: 'claimed' });
    }
  }
);

// Plugin reports build progress; auto-marks done at total.
export const advance_build_job = spacetimedb.reducer(
  { job_id: t.u64(), step: t.u32() },
  (ctx, { job_id, step }) => {
    const j = ctx.db.buildJob.id.find(job_id);
    if (j) {
      const status = step >= j.total ? 'done' : 'in_progress';
      ctx.db.buildJob.id.update({ ...j, step, status });
    }
  }
);

// Set/replace a Builder's count of a block item (restock).
export const set_inventory = spacetimedb.reducer(
  { builder_id: t.i32(), item: t.string(), count: t.i32() },
  (ctx, { builder_id, item, count }) => {
    const row = [...ctx.db.builderInventory.builder_id.filter(builder_id)].find((r) => r.item === item);
    if (row) ctx.db.builderInventory.id.update({ ...row, count });
    else ctx.db.builderInventory.insert({ id: 0n, builder_id, item, count });
  }
);

// Plugin consumes blocks as it places them (floors at 0).
export const consume_inventory = spacetimedb.reducer(
  { builder_id: t.i32(), item: t.string(), n: t.i32() },
  (ctx, { builder_id, item, n }) => {
    const row = [...ctx.db.builderInventory.builder_id.filter(builder_id)].find((r) => r.item === item);
    if (row) {
      const left = row.count - n < 0 ? 0 : row.count - n;
      ctx.db.builderInventory.id.update({ ...row, count: left });
    }
  }
);

// ----------------------------------------------------------------------------
// Reducers — The Director (admin server-control console)
// Confirm-gate: propose_action writes status='pending'; only confirm_action can
// move it to 'confirmed'; only then does the plugin execute it.
// ----------------------------------------------------------------------------

// Admin types a line in the console (dashboard).
export const admin_send = spacetimedb.reducer(
  { session_id: t.string(), text: t.string() },
  (ctx, { session_id, text }) => {
    ctx.db.adminMessage.insert({
      id: 0n,
      session_id,
      role: 'admin',
      text,
      kind: 'chat',
      action_id: 0n,
      created_at: ctx.timestamp,
    });
  }
);

// The Director answers / asks a scoping question (worker).
export const director_reply = spacetimedb.reducer(
  { session_id: t.string(), text: t.string(), kind: t.string() },
  (ctx, { session_id, text, kind }) => {
    ctx.db.adminMessage.insert({
      id: 0n,
      session_id,
      role: kind === 'system' ? 'system' : 'director',
      text,
      kind: kind || 'chat',
      action_id: 0n,
      created_at: ctx.timestamp,
    });
  }
);

// The Director proposes a concrete action (worker): writes the action (pending)
// then a linked proposal message so the console can render a confirm card.
export const propose_action = spacetimedb.reducer(
  {
    session_id: t.string(),
    kind: t.string(),
    summary: t.string(),
    params_json: t.string(),
    area_x: t.f64(),
    area_y: t.f64(),
    area_z: t.f64(),
    radius: t.f64(),
    world: t.string(),
  },
  (ctx, { session_id, kind, summary, params_json, area_x, area_y, area_z, radius, world }) => {
    const a = ctx.db.adminAction.insert({
      id: 0n,
      session_id,
      kind,
      summary,
      params_json,
      area_x,
      area_y,
      area_z,
      radius,
      world,
      status: 'pending',
      created_at: ctx.timestamp,
      decided_at: ctx.timestamp,
    });
    const aid = a ? a.id : 0n;
    ctx.db.adminMessage.insert({
      id: 0n,
      session_id,
      role: 'director',
      text: summary,
      kind: 'proposal',
      action_id: aid,
      created_at: ctx.timestamp,
    });
  }
);

// Admin confirms a pending action (dashboard) — the ONLY path to execution.
export const confirm_action = spacetimedb.reducer(
  { action_id: t.u64() },
  (ctx, { action_id }) => {
    const a = ctx.db.adminAction.id.find(action_id);
    if (a && a.status === 'pending') {
      ctx.db.adminAction.id.update({ ...a, status: 'confirmed', decided_at: ctx.timestamp });
    }
  }
);

// Admin rejects a proposal (dashboard).
export const reject_action = spacetimedb.reducer(
  { action_id: t.u64(), reason: t.string() },
  (ctx, { action_id, reason }) => {
    const a = ctx.db.adminAction.id.find(action_id);
    if (a && (a.status === 'pending' || a.status === 'confirmed')) {
      ctx.db.adminAction.id.update({ ...a, status: 'rejected', decided_at: ctx.timestamp });
      ctx.db.adminMessage.insert({
        id: 0n,
        session_id: a.session_id,
        role: 'system',
        text: `Rejected${reason ? ': ' + reason : ''}.`,
        kind: 'system',
        action_id,
        created_at: ctx.timestamp,
      });
    }
  }
);

// Plugin claims a confirmed action for execution.
export const begin_action = spacetimedb.reducer(
  { action_id: t.u64() },
  (ctx, { action_id }) => {
    const a = ctx.db.adminAction.id.find(action_id);
    if (a && a.status === 'confirmed') {
      ctx.db.adminAction.id.update({ ...a, status: 'executing' });
    }
  }
);

// Plugin reports execution result ('executed' | 'failed').
export const finish_action = spacetimedb.reducer(
  { action_id: t.u64(), status: t.string() },
  (ctx, { action_id, status }) => {
    const a = ctx.db.adminAction.id.find(action_id);
    if (a) ctx.db.adminAction.id.update({ ...a, status });
  }
);

// Plugin starts a live disaster scenario.
export const start_scenario = spacetimedb.reducer(
  {
    kind: t.string(),
    area_x: t.f64(),
    area_y: t.f64(),
    area_z: t.f64(),
    radius: t.f64(),
    world: t.string(),
    intensity: t.i32(),
    total_waves: t.u32(),
    ends_full_time: t.i64(),
    params_json: t.string(),
  },
  (ctx, { kind, area_x, area_y, area_z, radius, world, intensity, total_waves, ends_full_time, params_json }) => {
    ctx.db.scenario.insert({
      id: 0n,
      kind,
      status: 'active',
      area_x,
      area_y,
      area_z,
      radius,
      world,
      intensity,
      wave: 0,
      total_waves,
      ends_full_time,
      params_json,
      started_at: ctx.timestamp,
    });
  }
);

// Plugin reports a new wave spawned.
export const advance_scenario = spacetimedb.reducer(
  { scenario_id: t.u64(), wave: t.u32() },
  (ctx, { scenario_id, wave }) => {
    const s = ctx.db.scenario.id.find(scenario_id);
    if (s && s.status === 'active') ctx.db.scenario.id.update({ ...s, wave });
  }
);

// Auto-end (plugin) or manual End (dashboard). Idempotent.
export const end_scenario = spacetimedb.reducer(
  { scenario_id: t.u64() },
  (ctx, { scenario_id }) => {
    const s = ctx.db.scenario.id.find(scenario_id);
    if (s && s.status !== 'ended') ctx.db.scenario.id.update({ ...s, status: 'ended' });
  }
);

// Plugin assigns a Builder's personality + backstory (on spawn / resync).
export const set_builder_personality = spacetimedb.reducer(
  { builder_id: t.i32(), personality: t.string(), backstory: t.string() },
  (ctx, { builder_id, personality, backstory }) => {
    const b = ctx.db.builder.npc_id.find(builder_id);
    if (b) ctx.db.builder.npc_id.update({ ...b, personality, backstory, updated_at: ctx.timestamp });
  }
);

// Dashboard convenience: wipe a console session's transcript + actions.
export const clear_admin_session = spacetimedb.reducer(
  { session_id: t.string() },
  (ctx, { session_id }) => {
    for (const m of [...ctx.db.adminMessage.session_id.filter(session_id)]) {
      ctx.db.adminMessage.id.delete(m.id);
    }
    for (const a of [...ctx.db.adminAction.session_id.filter(session_id)]) {
      ctx.db.adminAction.id.delete(a.id);
    }
  }
);

// ----------------------------------------------------------------------------
// Reducers — Player ⇄ Builder proximity chat
// The plugin gates on proximity (only writes when a Builder is in talk range);
// the worker reads unanswered messages, asks the LLM for an in-character reply,
// answers via builder_say, then marks the message responded.
// ----------------------------------------------------------------------------

// Plugin: a nearby player spoke to a Builder.
export const player_say_to_builder = spacetimedb.reducer(
  { builder_id: t.i32(), player_uuid: t.string(), player_name: t.string(), text: t.string() },
  (ctx, { builder_id, player_uuid, player_name, text }) => {
    ctx.db.playerMessage.insert({
      id: 0n,
      builder_id,
      player_uuid,
      player_name,
      text,
      responded: false,
      created_at: ctx.timestamp,
    });
    // Mirror to the world chat log so the dashboard shows the conversation.
    ctx.db.chatMessage.insert({
      id: 0n,
      builder_id,
      speaker: player_name,
      text,
      audience: `builder:${builder_id}`,
      created_at: ctx.timestamp,
    });
  }
);

// Worker: mark an inbound message handled (after replying via builder_say).
export const mark_player_message_responded = spacetimedb.reducer(
  { id: t.u64() },
  (ctx, { id }) => {
    const m = ctx.db.playerMessage.id.find(id);
    if (m) ctx.db.playerMessage.id.update({ ...m, responded: true });
  }
);
