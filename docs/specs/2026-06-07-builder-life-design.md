# Builder Life System — Design (2026-06-07)

## Goal
Turn idle Builders into autonomous AI villagers with real lives: each has a **job**, a
**linked bed** it sleeps in at **night**, a **day/night schedule**, **unlimited iron tools**
(cosmetic held item), a **managed block inventory**, performs **real Minecraft-mechanic work**
(farming, fishing, herding, mining, chopping), **builds job-themed structures** incrementally,
and **fights hostile mobs** — coexisting with the existing recruit→quest loop and with vanilla
villages (additive only).

## Constraints / non-goals
- Do NOT replace/override vanilla villages; all structures are additive in the Builder-town area.
- Do NOT break the recruit→quest loop; a recruited Builder pauses its job and resumes after.
- Keep the 3-tier architecture: **SpacetimeDB = shared state/queue**, **AI worker = brain**,
  **Paper + Citizens = body**.
- **Subsystems communicate ONLY through SpacetimeDB state** (no new direct code coupling). This is
  what makes parallel development safe: agents share a schema, not code.

## Architecture
```
Paper plugin + Citizens (body) ──HTTP──► SpacetimeDB (state + work queue) ◄──WS── AI worker (brain)
                                              ▲
                                              └── React dashboard (read-only)
```
Body executes the `state` each Builder is in; brain decides `state`/jobs/builds; DB is the bus.

## SpacetimeDB schema additions — THE SHARED CONTRACT (locked in Phase 0 before any parallel work)
### `builder` table — add columns
- `job: string`            // "" until assigned; one of the jobs below
- `home_x,home_y,home_z: f64`, `home_world: string`   // bed/home location
- `state: string`          // "idle"|"working"|"building"|"sleeping"|"fighting"|"recruiting"
- `held_item: string`      // cosmetic tool material name, e.g. "IRON_HOE"
(existing kept: `schedule`, `thought`, `party_id`, `town`, `persona`, `x/y/z/world`)

### new table `build_job` (work-stealing queue)
- `id: u64` primary, autoinc
- `builder_id: i32`        // 0 = unclaimed
- `kind: string`           // "farm"|"dock"|"pen"|"forge"|"house"|"wall"|"cabin"|"mine"|"tower"
- `site_x,site_y,site_z: f64`, `world: string`
- `step: u32`, `total: u32`
- `status: string`         // "queued"|"claimed"|"in_progress"|"done"
- `created_at: u64`

### new table `builder_inventory`
- `id: u64` primary, autoinc
- `builder_id: i32`, `item: string`, `count: i32`   // logical key (builder_id,item)

### new reducers
- `assign_job(builder_id:i32, job:string, home_x:f64, home_y:f64, home_z:f64, home_world:string, held_item:string)`
- `set_builder_state(builder_id:i32, state:string)`  // also mirrors into `schedule`
- `enqueue_build(builder_id:i32, kind:string, x:f64, y:f64, z:f64, world:string, total:u32)` // inserts status="queued"
- `claim_build_job(builder_id:i32, job_id:u64)`      // ATOMIC: only if status=="queued" && builder_id==0 → "claimed"
- `advance_build_job(job_id:u64, step:u32)`          // sets step; if step>=total → status="done"
- `set_inventory(builder_id:i32, item:string, count:i32)`   // upsert by (builder_id,item)
- `consume_inventory(builder_id:i32, item:string, n:i32)`   // decrement, floor 0

## Jobs (8) — Builder mapping, build, mechanic, tool
| Builder | Job | Builds (template) | Real mechanic | Held tool |
|---|---|---|---|---|
| Thrain | mason | stone house/wall | place stone/cobble | IRON_PICKAXE |
| Borin | smith | forge (furnace+anvil+blocks) | place forge | IRON_AXE |
| Mira | farmer | farm plot | till + plant + harvest wheat/carrots | IRON_HOE |
| Kael | miner | mine entrance | mine stone, deposit | IRON_PICKAXE |
| Vyssa | fisher | dock (planks over water) | fish at water | FISHING_ROD |
| Dorin | shepherd | fenced pen | herd/breed/shear sheep | SHEARS |
| Eldra | lumberjack | cabin | chop + replant trees | IRON_AXE |
| Lyra | guard | watchtower | patrol + fight hostiles | IRON_SWORD |
Combat is **universal** (all Builders fight nearby hostiles); guard prioritizes/patrols.

## Behavior state machine (priority high → low)
1. **fighting** — hostile within ~10 blocks → target + attack, then resume.
2. **recruiting** — `party_id != 0` → run existing quest loop; job paused.
3. **sleeping** — phase==night → go to home bed, sleep-pose, restock inventory.
4. **building** — has a claimed/queued `build_job` → go to site, place next template block, advance.
5. **working** — day & structure done → perform job mechanic.
6. **idle** — wander + LookClose.
Worker writes `state` each tick from clock+party+queue; plugin executes it (plugin may locally
override to **fighting** when a mob is near, for responsiveness).

## Building (plugin)
Hardcoded templates: `Map<kind, List<RelBlock{dx,dy,dz,Material}>>`. Builder claims a `build_job`
(work-stealing via `claim_build_job`), navigates to site, each build-tick places the next block
(consume_inventory; restock if empty), `advance_build_job(step)`. On done → `working`.

## Beds & sleep
Town setup places a bed per Builder at `home_*` near the auto-spawn cluster (additive, not in a
village). Night → navigate to bed + sleeping pose (Citizens pose if supported; else stand/sit at bed).

## Combat
Plugin tick: nearest hostile `Monster` within 10 blocks → `npc.getNavigator().setTarget(mob, true)`
(aggressive) + cosmetic sword; on clear, resume prior state. Builders `setProtected(true)` + light
regen so they don't trivially die.

## Town setup / coexistence
"Found town": pick an area near world spawn that is NOT a vanilla village; place beds + reserve a
grid of job sites; enqueue initial builds. Additive only; vanilla villages/fortresses/cities
unaffected.

## Decomposition for PARALLEL agents (coupling = SpacetimeDB only)
**Phase 0 — serialized, OWNER = me:** implement schema + reducers in `stdb/.../index.ts`;
regenerate worker bindings; `spacetime publish`. LOCK THE CONTRACT.

**Phase 1 — parallel agents, each owns DISTINCT files:**
- **A. Worker brain** — `worker/src/jobs.ts` (new) + a section of `worker/src/index.ts` builderTick:
  assign jobs on first sight, set `state` from clock/party/queue, `enqueue_build`, restock inventory.
- **B. Plugin life/schedule** — `plugin/.../BuilderLife.java` (new): place beds, nav-to-bed+sleep at
  night, nav-to-worksite by day, equip `held_item`. Reads builder `state`/`home_*` via `Stdb.sql`.
- **C. Plugin building** — `plugin/.../BuildSystem.java` (new): claim build_jobs, place template
  blocks incrementally, consume inventory, advance. Hardcoded templates.
- **D. Plugin job mechanics + combat** — `plugin/.../JobMechanics.java` (new): farm/fish/herd/chop/
  mine actions + fight hostiles.
- **E. Dashboard** — `dashboard/src/App.tsx`: show job, state, build progress, inventory per Builder.

**Phase 2 — integration, OWNER = me:** wire BuilderLife/BuildSystem/JobMechanics timers into
`BuildersPlugin.java` behind a **state-priority arbiter**; set job/held_item on spawn in
`BuilderManager.java`; rebuild plugin (JDK21 toolchain), republish module, restart, end-to-end test,
fix integration.

## File ownership (no collisions)
- `stdb/.../index.ts` → me (Phase 0)
- `worker/src/jobs.ts` + `worker/src/index.ts` (builderTick) → Agent A
- `plugin/.../BuilderLife.java` → Agent B
- `plugin/.../BuildSystem.java` → Agent C
- `plugin/.../JobMechanics.java` → Agent D
- `dashboard/src/App.tsx` → Agent E
- `plugin/.../BuildersPlugin.java`, `BuilderManager.java` → me (Phase 2 wiring)
Agents MUST NOT edit files they don't own; if they need a plugin hook, they document the method
signature they expose and I wire it in Phase 2.

## Test plan
- **Module:** publish; `curl` `assign_job`/`enqueue_build`/`claim_build_job`/`advance_build_job`; assert rows.
- **In-game:** found town → 8 Builders get jobs+beds+tools → day: walk to sites, build structures
  incrementally, then do mechanics (crops grow/harvest, sheep herded, fish caught) → night: walk to
  beds/sleep → spawn a zombie near one → it fights → recruit a Builder → it pauses job, does quest,
  resumes. Dashboard reflects all.
- **Regression:** vanilla villages still generate; recruit→quest loop intact; player-skinned NPCs intact.

## Risks & mitigations
- Citizens sleep pose limited → fallback to standing/sitting at bed.
- NPCs "use" mechanics via plugin block/entity ops (not true entity tool-use) → acceptable; visible
  swing + world change.
- Pathfinding flaky → teleport fallbacks (as in the recruit flow).
- Parallel integration → mitigated by STDB-only coupling + strict file ownership + Phase-2 arbiter.

## Execution note (for resume after /compact)
Order: Phase 0 (me, schema+publish) → Phase 1 (spawn Agents A–E in parallel, each given this doc +
its owned files) → Phase 2 (me: integrate, build, deploy, test). All state lives in this doc + the
repo, so a compacted/fresh context can resume from here.
