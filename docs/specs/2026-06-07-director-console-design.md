# The Director — Admin Server-Control Console (design spec)

**Date:** 2026-06-07
**Status:** approved → building (phased, parallel subagents)
**Builds on:** the Builders RPG (Paper plugin ⇄ HTTP ⇄ SpacetimeDB ⇄ WS ⇄ TS worker; React dashboard) and the
Builder-Life feature (jobs/beds/build-queue/combat). **Additive only** — must not break the recruit→quest loop,
Builder-Life loops, or vanilla villages.

---

## 1. What we're building

An admin talks to **the Director** (the planner in *admin mode*) through a **chatbot web console**. The Director
**interrogates** the admin for the full scope of what they want, then **proposes** a concrete action. **Nothing
happens until the admin clicks Confirm.** On confirm, the plugin executes it: spawn a **zombie outbreak**, start a
**wildfire / storm-flood / meteor** disaster in an area, or run a **world op** (set time/weather, spawn/despawn
Builders). When a disaster starts in an area, **only the Builders inside that area warn nearby players** — flavored
by each Builder's **personality**.

Four coupled subsystems:
1. **Director loop** (worker) — interrogation + proposal + confirm-gate logic (LLM).
2. **Scenario engine** (plugin) — executes confirmed actions; runs disasters; area-scoped warnings.
3. **Personalities** (plugin + data) — each Builder gets a fixed personality type → voice + light behavior.
4. **Admin Console** (dashboard) — the chat UI with action-confirm cards + live scenario panel.

Coupling between subsystems is **SpacetimeDB state only** (plus one in-plugin `Personalities` helper). This is what
makes parallel development safe.

---

## 2. Architecture / data flow

```
Admin Console (new dashboard tab, React + STDB SDK)
   │  reducers: admin_send · confirm_action · reject_action · end_scenario
   ▼
SpacetimeDB ──(admin_message · admin_action · scenario · builder.personality)──► Worker Director loop (LLM)
   ▲ subscribe                                                                      │ interrogate → propose
   │                                                                                ▼ propose_action (status=pending)
   │                                                       (NOTHING runs until admin → confirm_action → status=confirmed)
   └──────── AdminExecutor + ScenarioEngine (plugin) ◄── poll admin_action WHERE status='confirmed'
                    executes world ops directly; for disasters → start_scenario (status=active)
                    ScenarioEngine ticks active scenarios: spawn waves / fire / storm / meteor
                    + finds in-radius Builders → they warn nearby players (personality-flavored)
                    → Minecraft world
```

**Confirm-gate is structural, not advisory:** the Director only ever writes `admin_action.status='pending'`. The
plugin executes **only** rows at `status='confirmed'`. There is no code path from `pending` to execution.

---

## 3. SpacetimeDB schema (Phase 0 — owned by integrator)

File: `stdb/spacetimedb/spacetimedb/src/index.ts`. snake_case names; all tables `public: true`.

### 3.1 New tables

```ts
// Admin Director console — the chat transcript.
const adminMessage = table(
  { name: 'admin_message', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    session_id: t.string().index('btree'), // "main" by default
    role: t.string(),     // "admin" | "director" | "system"
    text: t.string(),
    kind: t.string(),     // "chat" | "question" | "proposal" | "system"
    action_id: t.u64(),   // links a proposal message to its admin_action (0n if none)
    created_at: t.timestamp(),
  }
);

// The confirm-gate object: a proposed/confirmed server action.
const adminAction = table(
  { name: 'admin_action', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    session_id: t.string().index('btree'),
    kind: t.string(),        // zombie_outbreak|wildfire|storm_flood|meteor|set_time|set_weather|spawn_builders|despawn_builders
    summary: t.string(),     // human-readable "what I'm about to do"
    params_json: t.string(), // structured params (intensity, duration_minutes, etc.)
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

// A live scenario (disaster) instance the ScenarioEngine ticks.
const scenario = table(
  { name: 'scenario', public: true },
  {
    id: t.u64().primaryKey().autoInc(),
    kind: t.string(),        // zombie_outbreak|wildfire|storm_flood|meteor
    status: t.string().index('btree'), // active|ended
    area_x: t.f64(),
    area_y: t.f64(),
    area_z: t.f64(),
    radius: t.f64(),
    world: t.string(),
    intensity: t.i32(),      // 0/1/2 = low/med/high
    wave: t.u32(),           // waves spawned so far
    total_waves: t.u32(),
    ends_full_time: t.i64(), // world full_time tick at which it auto-ends
    params_json: t.string(),
    started_at: t.timestamp(),
  }
);
```

Register all three in `schema({...})`.

### 3.2 builder table additions

Add two columns to `builder`:
```ts
    personality: t.string(), // "" until set; brave|timid|cheerful|gruff|scholarly|greedy|kind|paranoid
    backstory: t.string(),   // one-line bio for the profile/dashboard
```
Add defaults to the `upsert_builder` insert branch: `personality: '', backstory: ''`.

### 3.3 New reducers (exact signatures + caller)

`t.timestamp()` can't be null; set `decided_at: ctx.timestamp` at creation and overwrite on decision.

| Reducer | Caller | Behavior |
|---|---|---|
| `admin_send(session_id, text)` | dashboard | insert admin_message(role='admin', kind='chat', action_id 0n) |
| `director_reply(session_id, text, kind)` | worker | insert admin_message(role='director', kind, action_id 0n) |
| `propose_action(session_id, kind, summary, params_json, area_x, area_y, area_z, radius, world)` | worker | insert admin_action(status='pending'); then insert admin_message(role='director', kind='proposal', text=summary, action_id=<new action id>) |
| `confirm_action(action_id)` | dashboard | if status='pending' → status='confirmed', decided_at=now |
| `reject_action(action_id, reason)` | dashboard | if status in (pending,confirmed) → 'rejected', decided_at=now; insert admin_message(role='system', kind='system', text="Rejected: "+reason) |
| `begin_action(action_id)` | plugin | if status='confirmed' → 'executing' (claim) |
| `finish_action(action_id, status)` | plugin | status='executed' or 'failed' |
| `start_scenario(kind, area_x, area_y, area_z, radius, world, intensity, total_waves, ends_full_time, params_json)` | plugin | insert scenario(status='active', wave 0, started_at=now) |
| `advance_scenario(scenario_id, wave)` | plugin | update scenario.wave (only if status='active') |
| `end_scenario(scenario_id)` | plugin + dashboard | status='ended' (idempotent) |
| `set_builder_personality(builder_id, personality, backstory)` | plugin | update builder.personality/backstory |
| `clear_admin_session(session_id)` | dashboard | delete admin_message + admin_action for that session (transcript reset; convenience) |

`propose_action` must insert the action **first**, capture the returned row's `id`, then insert the linked proposal
message with `action_id` = that id (SDK `insert()` returns the row with autoInc filled — see module CLAUDE.md).

---

## 4. Worker — the Director loop (Agent W)

**Owns:** `worker/src/director.ts` (new), `worker/src/llm.ts` (add a Director helper), `worker/src/index.ts`
(register the loop). **Reads:** module bindings (regenerated in Phase 0).

### 4.1 Loop
New `directorTick` on a ~4s interval (interactive — faster than the 20s planner). Track `lastSeenAdminMsgId` in
memory. Each tick: find `admin_message` rows where `role==='admin'` and `id > lastSeenAdminMsgId`; for the newest
unprocessed one, run a Director turn; advance the cursor. (Re-entrancy guard like `plannerRunning`.) Skip if no
admin messages exist. On startup, initialize the cursor to the current max admin-message id so it doesn't replay old
transcript.

### 4.2 LLM turn — JSON protocol
Add to `llm.ts` an exported helper (W's choice of name, e.g. `directorTurn(system, user, opts)`) that calls the
existing private `chat()` with `{ model: PLANNER_MODEL, maxTokens: 2048, reasoning: 'high' }` and returns the raw
string (so `director.ts` can `JSON.parse` it). Reuse the existing mock-fallback discipline: if the key is absent or
content is empty, return a safe canned question (never crash).

**System prompt** establishes the Director: the admin's interface to control the server through the planner. It
lists the capabilities (§4.3) with each one's **required fields**, and these rules:
1. **Interrogate, don't assume.** For the action the admin wants, you MUST collect EVERY required field. Ask for
   missing fields **one at a time**, in plain language. (All of the §4.3 fields are mandatory.)
2. **Resolve the area** from: explicit coords, `near <playerName>` (use the live snapshot), or "spawn". If the area
   is ambiguous, ask.
3. **Never execute.** When (and only when) every required field is known, return a `propose`. The human confirms in
   the UI.
4. Keep replies to one or two sentences. Be concrete (echo back the values you've gathered).

**World snapshot** injected into the user message each turn: online players (name + x/y/z + world), Builders
(name/job/personality/x/z), active scenarios, world clock (phase + full_time), and the recent transcript (last ~10
messages).

**Output (strict JSON in message content):**
```json
{ "type": "reply",   "text": "one question or answer", "kind": "question" | "chat" }
{ "type": "propose",
  "action": { "kind": "...", "summary": "...", "params": { ... },
              "area": { "x": .., "y": .., "z": .., "radius": .., "world": ".." } } }
```
`director.ts` parses defensively (strip code fences; try/catch). On `reply` → `director_reply`. On `propose` →
**validate required fields are present** for that kind (§4.3); if any are missing, convert to a `director_reply`
asking for the first missing field (defense in depth), else call `propose_action`.

### 4.3 Capabilities + required fields (the interrogation schema)

Encode this as a const map in `director.ts` and inject the field lists into the system prompt. **All listed fields
are required before a proposal is allowed.**

| kind | required fields (params unless noted) |
|---|---|
| `zombie_outbreak` | area, `intensity` (low\|med\|high), `duration_minutes` (int), `escalate` (bool) |
| `wildfire` | area, `severity` (low\|med\|high), `duration_minutes` |
| `storm_flood` | area, `severity` (low\|med\|high), `duration_minutes`, `flood` (bool — raise water?) |
| `meteor` | area, `rate` (low\|med\|high), `duration_minutes` |
| `set_time` | `value` (day\|night\|noon\|midnight\|`<0-24000>`) |
| `set_weather` | `type` (clear\|rain\|thunder), `duration_minutes` |
| `spawn_builders` | `count` (int), `location` (near-player\|coords\|spawn); optional `job`, `personality` |
| `despawn_builders` | `scope` (all\|in-area) |

`area` = `{x,y,z,radius,world}`. For non-spatial kinds (`set_time`, `set_weather`), area may be zeroed.

---

## 5. Plugin — execution (Agents P1 + P2)

### 5.1 AdminExecutor (P1) — `plugin/.../AdminExecutor.java`
A `Runnable` registered on an **async** timer (mirror DirectivePoller/BuildSystem). Each cycle:
1. `stdb.sql("SELECT id, kind, summary, params_json, area_x, area_y, area_z, radius, world FROM admin_action WHERE status = 'confirmed'")`.
2. Track a `seen` set of ids (like DirectivePoller) so an action executes once even before STDB reflects the status flip.
3. For each new confirmed action: `stdb.call("begin_action", id)` then hop to the main thread to execute by kind:
   - **disaster kinds** (`zombie_outbreak|wildfire|storm_flood|meteor`): compute `total_waves`/`ends_full_time`
     from params (§5.3 clamps), then `stdb.call("start_scenario", kind, x, y, z, radius, world, intensity, total_waves, ends_full_time, params_json)`.
   - **set_time**: parse `value` → `world.setTime(...)` (`day`=1000, `noon`=6000, `night`=13000, `midnight`=18000, or raw ticks).
   - **set_weather**: `clear`→`setStorm(false)`; `rain`→`setStorm(true),setThundering(false)`; `thunder`→both true; set `setWeatherDuration`.
   - **spawn_builders**: resolve location → `builders.spawnNear(loc, count)` (existing method).
   - **despawn_builders**: `scope=all` → `builders.despawnAll()`. (`in-area` → optional; may no-op for v1.)
4. On success `stdb.call("finish_action", id, "executed")`; on exception `finish_action(id, "failed")` +
   `stdb.call("director_reply", session, "⚠ Couldn't execute: <msg>", "system")` is not available to the plugin
   directly — instead post via a generic system note: insert through `post_chat`? **Simpler:** on failure just call
   `finish_action(id,"failed")`; the dashboard renders failed actions. (Keep plugin→console messaging minimal.)
5. Parse `params_json` with Gson (already a dependency).

Clamp/guard everything (§5.3). All world/NPC access on the main thread. Wrap the cycle in `try/catch(Throwable)`.

### 5.2 ScenarioEngine (P1) — `plugin/.../ScenarioEngine.java`
A `Runnable` on an async timer (e.g. 20L/1s). Each cycle:
1. `stdb.sql("SELECT id, kind, status, area_x, area_y, area_z, radius, world, intensity, wave, total_waves, ends_full_time, params_json FROM scenario WHERE status = 'active'")`.
2. Also pull builders once: `SELECT npc_id, name, job, personality, x, y, z, world FROM builder` (for area warnings).
3. Hop to main thread. For each active scenario:
   - Resolve `World`; if missing, skip.
   - **Auto-end:** if `world.getFullTime() >= ends_full_time` → `stdb.call("end_scenario", id)` then run any
     cleanup (extinguish remaining fire / `setStorm(false)`); continue.
   - **Effect tick** (keep an in-memory `Map<Long,Long> lastEffectFullTime` to pace effects, e.g. one pulse/2s):
     - `zombie_outbreak`: if `wave < total_waves` and it's time for the next wave (pace ~every 6s), spawn
       `mobsPerWave(intensity)` hostiles (ZOMBIE; add HUSK/SKELETON at higher intensity) at random points within
       `radius` of center, **only in loaded chunks**, y snapped to surface; `stdb.call("advance_scenario", id, wave+1)`.
       `escalate` (from params) bumps mob count per wave.
     - `wildfire`: each pulse, ignite a handful of random flammable/empty-above blocks within radius (place `FIRE`
       on top of solid ground); bounded count per pulse.
     - `storm_flood`: `world.setStorm(true); world.setThundering(true)`; each pulse strike lightning at a few random
       in-radius points (`world.strikeLightning` — use `strikeLightningEffect` if you want no fire); if `flood`,
       optionally raise water by 1 within radius up to a small cap (guarded; skip if risky).
     - `meteor`: each pulse spawn a few `FallingBlock` (MAGMA_BLOCK/MAGMA) high above random in-radius points; on a
       short delay create a **small** explosion (`world.createExplosion(loc, power<=2f, setFire=false, breakBlocks=configurable)`).
   - **Area-scoped warnings** (every scenario, throttled per Builder ~every 12s via an in-memory map): for each
     Builder within `radius` of the scenario center (use the builder snapshot positions), make it warn players
     within ~32 blocks: `player.sendMessage` + a `Title`/actionbar + a soft sound; also `stdb.call("post_chat",
     npcId, name, warnLine, "world")` so the dashboard shows it. **Use `Personalities.warnLine(personality, kind)`**
     for the text. Builders **outside** the radius say nothing. ✅ requirement.
   - **Light personality behavior** for in-area Builders: `Personalities.behavior(personality)` →
     `"fight"` → `stdb.call("set_builder_state", npcId, "fighting")` (JobMechanics already drives combat);
     `"flee"` → navigate the NPC away from center (`builders.byId(npcId).getNavigator().setTarget(awayLoc)`);
     `"neutral"` → warn only.
4. Clamp counts; only act in loaded chunks; `try/catch(Throwable)`.

**P1 reads `Personalities` (owned by P2) — code against the §5.4 contract.** P1 uses only existing
`BuilderManager.byId(int)` (no new BuilderManager methods).

### 5.3 Safety clamps (P1, non-negotiable)
- `radius`: clamp to `[4, 64]`.
- `mobsPerWave`: low 3 / med 5 / high 8; `total mobs` across a scenario ≤ 60.
- `total_waves`: low 2 / med 3 / high 5.
- `duration_minutes`: clamp to `[1, 15]` → `ends_full_time = world.getFullTime() + minutes*60*20`.
- meteor explosion power ≤ 2f; default **don't** break blocks (or make it a guarded param).
- wildfire ignitions ≤ ~6 per pulse; storm lightning ≤ ~3 per pulse.
- Everything only spawns/acts in **loaded chunks** near players.

### 5.4 Personalities (P2) — `plugin/.../Personalities.java`
Owned by P2; **used by P1**. Static helper, no Bukkit state. Contract P1 compiles against:
```java
public final class Personalities {
    // 8 fixed types: brave, timid, cheerful, gruff, scholarly, greedy, kind, paranoid
    public static String forRosterSlot(int slotIndex);            // deterministic type per roster slot
    public static String backstory(String type, String name, String job); // one-line bio
    public static String warnLine(String type, String scenarioKind);      // flavored warning
    public static String behavior(String type);                   // "fight" | "flee" | "neutral"
}
```
Suggested mapping (P2 may refine): brave/gruff → "fight"; timid/paranoid → "flee"; cheerful/scholarly/greedy/kind →
"neutral". `warnLine` returns short, in-character lines per (type × scenarioKind).

### 5.5 BuilderManager personality assignment (P2) — edit `BuilderManager.java`
In `spawnNear`, after `upsert_builder`, assign a personality deterministically from the roster slot:
```java
String type = Personalities.forRosterSlot((nameCursor - 1) % ROSTER.length);
String back = Personalities.backstory(type, who[0], /* job unknown yet */ "");
stdb.call("set_builder_personality", id, type, back);
```
(Also call it in `resyncFromCitizens` so restored Builders get a personality.) P2 owns all edits to
`BuilderManager.java`; **P1 must not edit it.**

### 5.6 Wiring (integrator, Phase 2) — `BuildersPlugin.java`
Register the two new async timers in `onEnable` after the Builder-Life timers:
```java
getServer().getScheduler().runTaskTimerAsynchronously(this, new AdminExecutor(this, stdb, builders), 100L, 20L);
getServer().getScheduler().runTaskTimerAsynchronously(this, new ScenarioEngine(this, stdb, builders), 120L, 20L);
```
P1/P2 do **not** edit `BuildersPlugin.java`.

---

## 6. Dashboard — Admin Console (Agent D)

**Owns:** `dashboard/src/App.tsx` (add a tab shell) + new components (e.g. `AdminConsole.tsx`) + `index.css` as
needed. **Reads:** regenerated module bindings.

- Turn the current view into a tabbed shell: **`[ World | Admin Console ]`**. "World" = the existing dashboard
  unchanged.
- **Admin Console** subscribes to `admin_message`, `admin_action`, `scenario` (already covered by
  `subscribeToAllTables`). It must **call reducers** (the dashboard currently only reads): wire
  `conn.reducers.adminSend`, `confirmAction`, `rejectAction`, `endScenario`, `clearAdminSession`. (Anonymous reducer
  calls are fine — all tables public, reducers don't check `ctx.sender`.)
- **Transcript:** render `admin_message` for session "main" in order; admin right-aligned, Director left-aligned,
  system centered/muted. A message with `kind='proposal'` renders as an **action card** (look up its `admin_action`
  by `action_id`): summary + params + area + **[Confirm] [Reject]** buttons (disabled once status≠pending; show a
  status badge: pending/confirmed/executing/executed/rejected/failed).
- **Input box** → `adminSend('main', text)`. Quick-suggestion chips (e.g. "Start a zombie outbreak near <player>",
  "Make it night", "Clear weather").
- **Active scenarios panel:** list `scenario WHERE status='active'` → kind, area, `wave/total_waves`, time-left
  (`(ends_full_time - world_clock.full_time)/20`s), with an **[End]** button → `endScenario(id)`.
- Defensive `safeIter` over possibly-missing bindings (same guard already used in App.tsx).

Layout target:
```
┌─ Builders ───────────────────────[ World | Admin Console ]─┐
│ Active:  🧟 zombie_outbreak · r30 @ (123,64,-45) · wave 2/5 · 1:30  [End] │
│────────────────────────────────────────────────────────────│
│  Director: What area should the outbreak cover?             │
│                                 You: near Wally, radius 30  │
│  Director: How intense — low, medium, or high?              │
│                                              You: high      │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⚠ PROPOSAL — confirm to execute     [status: pending] │  │
│  │ zombie_outbreak                                        │  │
│  │ • area (123,64,-45) r=30 'world'                       │  │
│  │ • intensity=high  duration=3m  escalate=true           │  │
│  │            [ Confirm ]      [ Reject ]                 │  │
│  └──────────────────────────────────────────────────────┘  │
│────────────────────────────────────────────────────────────│
│ > make it night and start a wildfire at spawn____________    │
│   [zombie outbreak]  [make night]  [clear weather]           │
└──────────────────────────────────────────────────────────────┘
```

**Security note (documented, accepted for local/demo):** anyone who can open the dashboard can drive the server via
the console. Fine for the hackathon's local setup; would need auth before any public deployment.

---

## 7. Build phases & file ownership

**Phase 0 — integrator (serialized, GATE):** schema (§3) → `spacetime publish builders-rpg --server dev -p
stdb/spacetimedb/spacetimedb -y --delete-data=always` → regenerate bindings into BOTH `worker/src/module_bindings`
and `dashboard/src/module_bindings` → smoke-test new reducers via curl. Do not start Phase 1 until reducers verify.

**Phase 1 — parallel subagents (distinct files):**
- **W** — worker Director: `worker/src/director.ts` (new), `worker/src/llm.ts` (add Director helper),
  `worker/src/index.ts` (register loop).
- **P1** — plugin execution: `AdminExecutor.java` (new), `ScenarioEngine.java` (new). Reads `Personalities` (§5.4
  contract) + `BuilderManager.byId`. Does NOT edit BuilderManager or BuildersPlugin.
- **P2** — personalities: `Personalities.java` (new), edits `BuilderManager.java` only.
- **D** — dashboard: `App.tsx` + new `AdminConsole.tsx` + `index.css`.

Agents **write code only** — no build/deploy, no editing files they don't own.

**Phase 2 — integrator:** wire `BuildersPlugin.java` (§5.6); rebuild plugin (JDK21 toolchain); republish module if
needed; restart; end-to-end + regression test (§8); fix any contract drift.

---

## 8. Test plan

**Phase 0 smoke (curl):** `admin_send` → row appears; `propose_action` → action(pending) + proposal message;
`confirm_action` → confirmed; `start_scenario` → scenario(active); `end_scenario` → ended;
`set_builder_personality` → builder updated.

**End-to-end (in-game, real player loads chunks):**
1. Open dashboard → Admin Console. Type "start a zombie outbreak near <player>".
2. Director interrogates: area? intensity? duration? escalate? — confirm it refuses to propose until all answered.
3. Proposal card appears → **Confirm**. Outbreak spawns waves near the player; **only in-area Builders warn**
   (chat + title), flavored by personality; brave Builders fight, timid flee.
4. Outbreak auto-ends at duration (or **[End]**).
5. World ops: "make it night" → confirm → time changes. "clear weather" → confirm → clears.
6. Personalities: each Builder's lines/backstory reflect its type; visible on the dashboard profile.

**Regression:** recruit→quest loop still works; Builder-Life (jobs/beds/build/combat) intact; vanilla villages
untouched. Reject path: rejecting a proposal executes nothing.

**Headless caveat (from Builder-Life):** Citizens NPCs only act in loaded chunks — for headless testing use
`forceload add` + a simulated player; real players load chunks naturally.

---

## 9. Out of scope (v1)
- Auth on the console. In-game `/director` chat (web only for now). Persisting scenarios across server restart
  (in-memory effect pacing is fine; rows survive but pacing maps reset). `despawn_builders in-area` may no-op.
- Deep per-personality behavior trees (we do light behavior: fight/flee/neutral + dialogue flavor only).

---

## 10. Addendum — Player ⇄ Builder proximity chat (added during build)

Players talk to a Builder by **chatting near it** — proximity-gated, no command needed. Additive; does not cancel
normal chat.

**Flow:** player chats in-game → plugin `BuilderChatListener` finds the nearest Builder within `TALK_RADIUS` (~12
blocks, same world). If one is in range it calls `player_say_to_builder(builder_id, uuid, name, text)`. The worker
chat loop sees the unanswered `player_message`, generates an in-character reply (LLM — Builder persona + personality
+ backstory + recent `builder_memory`), answers via the existing `builder_say(builder_id, uuid, reply)` (→ `say`
directive → DirectivePoller prints `[Name] reply` to the player), writes a `builder_memory('chat')`, then
`mark_player_message_responded(id)`.

**Schema (added to Phase 0):**
- table `player_message`: `id u64 PK autoInc, builder_id i32 btree, player_uuid, player_name, text, responded bool
  btree, created_at`.
- `player_say_to_builder(builder_id, player_uuid, player_name, text)` (plugin) — insert player_message(responded=
  false) + mirror a `chat_message(speaker=player_name, audience='builder:<id>')`.
- `mark_player_message_responded(id)` (worker).

**Ownership:**
- Worker chat loop → **Agent W** (owns ALL worker files): `worker/src/chat.ts` (new), a `replyToPlayer(...)` helper
  in `worker/src/llm.ts`, and register the loop in `worker/src/index.ts`. (W already owns these files for the
  Director, so a single worker owner avoids index.ts/llm.ts collisions.)
- Plugin listener → **Agent P3** (new): `plugin/.../BuilderChatListener.java`, a Bukkit `Listener` on
  `AsyncChatEvent`. Capture text+player on the async event; hop to main thread to find the nearest in-range Builder
  via existing `BuilderManager.nearestBuilderId` + `byId` (NO BuilderManager edits); rate-limit replies per player
  (~2.5s); `stdb.call("player_say_to_builder", ...)`. Do not cancel the event.
- Integrator wires the listener in `BuildersPlugin.onEnable` (Phase 2):
  `getServer().getPluginManager().registerEvents(new BuilderChatListener(this, stdb, builders), this);`
