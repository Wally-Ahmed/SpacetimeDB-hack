# ⚒ Builders

**A generative, story-mode Minecraft RPG whose NPCs' minds live _outside_ the game — with SpacetimeDB as the shared real-time brain.**

Builders adds a new kind of inhabitant to Minecraft: **Builders** — Citizens NPCs that wear real player skins and live, work, and build in towns that coexist with vanilla villages. Their intelligence isn't in the Minecraft server at all. A relational database that is _also_ the server — [SpacetimeDB](https://spacetimedb.com) — sits in the middle, and several independent programs (a Java plugin, a TypeScript AI worker, and a React dashboard) read and write it at once. None of them ever calls another directly; they meet only in shared database state.

The centerpiece is an autonomous **recruit → quest → completion** loop: an external AI planner watches each online player's real advancement progress, sends a free Builder to _physically walk up_ and offer a progression-appropriate quest, forms a party when the player accepts, and closes the quest the instant the player earns the matching vanilla advancement.

> For the full, diagram-rich architecture brief (flowchart, ER diagram, and four end-to-end sequence diagrams), open **[`architecture.html`](architecture.html)** in a browser.

---

## Table of contents

1. [Highlight features](#highlight-features)
2. [Architecture in one minute](#architecture-in-one-minute)
3. [Repository layout](#repository-layout)
4. [Tech stack & versions](#tech-stack--versions)
5. [Prerequisites](#prerequisites)
6. [Setup & run](#setup--run)
7. [How to play](#how-to-play)
8. [The Director (admin console)](#the-director-admin-console)
9. [LLM configuration](#llm-configuration)
10. [Why SpacetimeDB](#why-spacetimedb)
11. [Troubleshooting](#troubleshooting)
12. [Credits & license](#credits--license)

---

## Highlight features

- **The recruit → quest → completion loop (the centerpiece).** The story planner picks a quest from the lowest progression tier you haven't cleared, sends a free Builder to **walk over to you** (with an 8-second teleport fallback so pathing can never fail the demo), and offers it with a clickable **[ Accept the quest ]**. Accept → you **join a party**; complete the real vanilla advancement → the quest closes, the party disbands, and the Builder **writes a durable memory** about you. The per-phase recruit caps (easy 2 / med 4 / hard 5, per day _and_ per night) are enforced **atomically** inside a single reducer, so two simultaneous recruit attempts can never both win. **Hard difficulty** skips straight to the boss tier (Ender Dragon / Wither).

- **Autonomous Builder Life.** Idle Builders become living AI villagers: each gets a **job**, a linked **bed** it sleeps in at night, a **day/night schedule**, a cosmetic iron **held tool**, and a managed **block inventory**. They perform real Minecraft-mechanic work (farming, fishing, herding, mining, chopping), **build job-themed structures block-by-block** off a work-stealing build queue, and **fight hostile mobs** — all additive to, and coexisting with, vanilla villages. The eight jobs:

  | Builder | Job | Builds | Real mechanic | Held tool |
  |---|---|---|---|---|
  | Thrain | mason | stone house | place stone/cobble | `IRON_PICKAXE` |
  | Borin | smith | forge | place forge (furnace+anvil) | `IRON_AXE` |
  | Mira | farmer | farm plot | till + plant + harvest | `IRON_HOE` |
  | Kael | miner | mine entrance | mine stone, deposit | `IRON_PICKAXE` |
  | Vyssa | fisher | dock | fish at water | `FISHING_ROD` |
  | Dorin | shepherd | fenced pen | herd / breed / shear sheep | `SHEARS` |
  | Eldra | lumberjack | cabin | chop + replant trees | `IRON_AXE` |
  | Lyra | guard | watchtower | patrol + fight hostiles | `IRON_SWORD` |

  Combat is **universal** (every Builder fights nearby hostiles); the guard prioritizes and patrols.

- **Builder personalities (8 types).** Each Builder is assigned a fixed personality — **brave, timid, cheerful, gruff, scholarly, greedy, kind,** or **paranoid** — that flavors its dialogue and drives light behavior (brave/gruff **fight**, timid/paranoid **flee**, the rest **warn only**).

- **The Director — admin web console.** Chat with "the Director" (the planner in admin mode) to control the server. It **interrogates** you for every required field of what you want, then **proposes** a concrete action. **Nothing happens until you click Confirm** — the confirm-gate is structural, not advisory. On confirm, the plugin executes it: a **zombie outbreak**, **wildfire**, **storm-flood**, or **meteor** disaster scoped to an area, or a **world op** (set time/weather, spawn/despawn Builders).

- **Area-scoped disaster warnings.** When a disaster starts in an area, **only the Builders inside that area** warn nearby players — flavored by each Builder's personality. Builders outside the radius say nothing.

- **Player ⇄ Builder proximity chat.** Talk to a Builder by simply **chatting near it** (no command). The nearest Builder within ~12 blocks replies in character via the LLM, using its persona, personality, backstory, and recent memory.

- **The live dashboard ("Director's View").** A real-time, read-only mirror of the whole world — adventurers, parties, Builders (job / state / build progress / inventory / current thought), quests, town chatter, and memories — plus the **Admin Console** tab that drives the Director. Rendered purely from SpacetimeDB subscriptions.

- **Offline-safe.** With no LLM key the worker runs in deterministic **MOCK mode** (canned, theme-appropriate lines), so the entire system runs fully offline and demo-safe.

---

## Architecture in one minute

Builders is **"the brain outside the body."** Several programs orbit one hub and **never talk to each other directly** — the database is the only wire.

```
   Minecraft (Paper plugin)  ──HTTP──►   SpacetimeDB   ◄──WS (SDK)──►   AI worker (TS)
   "the body" (Java)                  "shared world / brain-bus"        "the mind"
                                              ▲
                                              │ WS (read + Admin-Console writes)
                                         React dashboard  ← "the window" (Director's View)
```

- **The body — Paper plugin (Java).** Holds _no intelligence_. It streams game state to SpacetimeDB and executes whatever the brain (or a confirmed admin action) writes back. Game events become reducer calls; background pollers read command queues and act on the main thread.
- **The shared world / brain-bus — SpacetimeDB (TypeScript WASM module).** A relational database that **is also the server**. It defines the public tables and the **reducers that are the only way to write them**. Reducers run **transactionally and deterministically**.
- **The mind — AI worker (Node + TS SDK).** Subscribes to _all_ tables and runs reactive loops (planner, Builder Life, Director, chat), calling the LLM and writing decisions back through reducers.
- **The window — React + Vite dashboard.** A live read-only mirror, plus the Admin Console — the one place the dashboard writes.

Two **forcing constraints** shaped this design (and both turned out to be features):

1. **SpacetimeDB has no Java SDK** → the plugin talks to the database over the plain **HTTP API** (`POST …/call/<reducer>` for fire-and-forget writes, `POST …/sql` for blocking reads). The whole DB connection is one file, `Stdb.java`.
2. **Reducers can't do I/O** (no network, files, clocks, or randomness) → the LLM **cannot** live in a reducer. It lives in an external **worker** that subscribes to the database and reacts to state changes. This is what makes "the AI reacts to the database" literally true.

See **[`architecture.html`](architecture.html)** for the detailed diagrams, the full 16-table data model, the reducer rule engine, and four end-to-end flows.

---

## Repository layout

```
SpacetimeDB-hack/
├── architecture.html      # Standalone, diagram-rich architecture brief (open in a browser)
├── README.md              # You are here
├── CREDITS.md             # Open-source attributions
├── plugin/                # "The body" — Paper plugin (Java, JDK 21, Gradle)
│   ├── build.gradle
│   └── src/main/
│       ├── java/dev/builders/   # Stdb.java, BuildersPlugin.java, GameListeners.java,
│       │                        # BuilderManager.java, DirectivePoller.java, BuildSystem.java,
│       │                        # JobMechanics.java, BuilderLife.java, AdminExecutor.java,
│       │                        # ScenarioEngine.java, Personalities.java, BuilderChatListener.java
│       └── resources/           # plugin.yml, config.yml, skins.txt (Mojang-verified usernames)
├── worker/                # "The mind" — AI worker (Node + TypeScript, SpacetimeDB TS SDK)
│   ├── package.json
│   ├── .env.example       # env template (copy to .env; .env is gitignored)
│   └── src/               # index.ts, advancements.ts, jobs.ts, llm.ts, director.ts, chat.ts,
│                          # module_bindings/ (generated)
├── stdb/                  # "The shared world / brain-bus" — SpacetimeDB module (TypeScript)
│   └── spacetimedb/spacetimedb/src/index.ts   # all tables + reducers
├── dashboard/             # "The window" — React + Vite (Director's View)
│   ├── package.json
│   └── src/               # App.tsx, AdminConsole.tsx, index.css, main.tsx, module_bindings/
├── docs/                  # Design specs
│   └── superpowers/specs/ # builder-life-design.md, director-console-design.md
├── scripts/               # publish.sh, server.sh, run-all.sh, reset.sh, e2e.sh, fetch-deps.sh
└── server/                # Paper server runtime (jars/worlds are gitignored; fetch with scripts)
```

---

## Tech stack & versions

| Layer | Tech | Notes |
|---|---|---|
| **Body** | **Paper 1.21.10** (build 129), JDK **21** | Minecraft server hosting the plugin. Port `25566`, RCON `25575`. Offline-mode. |
| | **Citizens** 2.0.42 (b4187) | NPC framework — Builders are **PLAYER-type** NPCs with real Mojang skins. **Required plugin** (hard dependency). |
| | **ViaVersion** 5.9.1 | Lets clients **newer** than the server (e.g. a 1.21.11 client) join the 1.21.10 server. **Required server plugin** for newer clients. |
| | Gson + `java.net.http` | SATS-JSON parsing + the HTTP bridge to SpacetimeDB. |
| **Brain-bus** | **SpacetimeDB 2.4.1** | TypeScript WASM module. Local `dev` server on `127.0.0.1:3050`, database `builders-rpg`. |
| **Mind** | **Node + TypeScript** | SpacetimeDB TS SDK over WebSocket. Run via `tsx`. |
| | **Kilo Gateway → Gemini 3.5 Flash** | OpenAI-compatible. **Reasoning effort high** for the planner/Director, **low** for dialogue. Deterministic **MOCK fallback** when no key is set. |
| **Window** | **React 18 + Vite 5** | Dashboard on port `5173`. Shares generated module bindings with the worker. |

> **Why 1.21.10?** The current Citizens build only ships NMS adapters for 1.21.x. Advancement IDs are unchanged, so the progression system is identical. A 1.21.11 client connects to the 1.21.10 server transparently thanks to ViaVersion.

---

## Prerequisites

- **JDK 21** (e.g. `/opt/homebrew/opt/openjdk@21`) — required to build the plugin and run Paper.
- **Node** (18+ recommended) and **npm**.
- **Gradle** (the plugin uses the Gradle `java` toolchain; point it at JDK 21 — see [Setup & run](#setup--run)).
- The **`spacetime` CLI** (2.4.x) with a local `dev` server. Start it with `spacetime start` if it isn't already running on `127.0.0.1:3050`.
- Internet access at runtime for: the SpacetimeDB module publish, Citizens fetching player skins from Mojang, and (optionally) the LLM gateway. The architecture brief loads Mermaid from a CDN.
- A **Minecraft Java client** (1.21.10, or 1.21.11 via ViaVersion) to actually play.

---

## Setup & run

There are **four services**: the SpacetimeDB module, the AI worker, the Paper server, and the dashboard. The quick path is `scripts/run-all.sh`; the detailed, ordered steps follow.

> **Quick start:** with the local SpacetimeDB `dev` server running, `bash scripts/run-all.sh` publishes the module and starts the worker + Paper server + dashboard together (Ctrl+C stops them all).

### 0. Fetch server binaries (first time only)

The Paper and Citizens jars are gitignored. Download them, then drop the built plugin jar in (step 3). **ViaVersion** is also a required server plugin — place its jar in `server/plugins/` as well.

```bash
bash scripts/fetch-deps.sh    # downloads Paper 1.21.10 #129 + Citizens 2.0.42 into server/
# then add ViaVersion 5.9.1 to server/plugins/ (from Modrinth)
```

### 1. Publish the SpacetimeDB module + generate bindings

The module (schema + reducers) lives in `stdb/spacetimedb/spacetimedb`. Publishing to the local `dev` server creates/updates the `builders-rpg` database.

```bash
# Publish (schema + reducers)
bash scripts/publish.sh
#   equivalent to:
#   spacetime publish builders-rpg --server dev -p stdb/spacetimedb/spacetimedb -y
```

When you change the **schema** (add/alter tables or columns), republish with a data wipe so the migration applies cleanly:

```bash
spacetime publish builders-rpg --server dev -p stdb/spacetimedb/spacetimedb --delete-data=always -y
# or: bash scripts/reset.sh   (wipes + re-seeds builders-rpg)
```

After a schema change, **regenerate the TypeScript bindings** into both the worker and the dashboard:

```bash
spacetime generate --lang typescript \
  --out-dir worker/src/module_bindings \
  -p stdb/spacetimedb/spacetimedb -y

spacetime generate --lang typescript \
  --out-dir dashboard/src/module_bindings \
  -p stdb/spacetimedb/spacetimedb -y
```

### 2. Configure & run the AI worker (the mind)

Copy the env template and (optionally) add an LLM key. **Leave `LLM_API_KEY` blank to run in offline MOCK mode** — the whole system still works.

```bash
cd worker
cp .env.example .env     # edit .env (see "LLM configuration" below)
npm install
npm start                # = tsx src/index.ts   (use `npm run dev` for watch mode)
```

On boot the worker prints its mode, e.g. `[worker] LLM: LIVE (...)` or `[worker] LLM: MOCK (offline — no LLM_API_KEY)`.

### 3. Build the plugin (JDK 21) & run the Paper server

Build the plugin against JDK 21 (the system Gradle may default to a newer JDK, so point it at JDK 21 explicitly), then copy the jar into the server's `plugins/` directory. **Citizens and ViaVersion must also be present in `plugins/`.**

```bash
cd plugin
J21="$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)"
JAVA_HOME="$J21" gradle jar -Dorg.gradle.java.installations.paths="$J21"

cp build/libs/BuildersPlugin-1.0.0.jar ../server/plugins/
# If you changed config.yml, remove the stale deployed copy so new keys apply:
# rm -f ../server/plugins/BuildersPlugin/config.yml
```

Start the server (Paper on `:25566`, RCON on `:25575`, offline-mode):

```bash
bash scripts/server.sh        # launches Paper 1.21.10 with JDK 21
```

A **starter town of 4 Builders auto-spawns at world spawn** on boot (set `builders.auto-spawn` in the plugin `config.yml`; `0` to disable). The plugin also exposes `builders.entity-type` (default `PLAYER`; `VILLAGER` for a skinless fallback).

You can drive the server console over RCON: `node server/rcon.mjs "<command>"` (password is set in `server/server.properties`).

### 4. Run the dashboard (the window)

```bash
cd dashboard
npm install
npm run dev                   # Vite on http://localhost:5173
```

The dashboard connects to `ws://127.0.0.1:3050` / `builders-rpg` by default (override with `VITE_STDB_URI` / `VITE_STDB_DB`).

### Headless end-to-end test (no client needed)

With the Paper server and worker running, `bash scripts/e2e.sh` spawns Builders via RCON, registers a fake player over HTTP, and drives the full **recruit → accept → complete** loop, asserting each step in SpacetimeDB.

---

## How to play

1. **Connect** a Minecraft Java client to **`localhost:25566`** (offline mode — any username works). A **1.21.11** client joins the **1.21.10** server transparently via ViaVersion.
2. **Get recruited.** Wait ~20 seconds — the planner sends a Builder to walk over and offer a progression-appropriate quest. Click **[ Accept the quest ]** (or right-click the Builder). Spawn more Builders with `/builders spawn <n>`.
3. **Complete the quest.** Do the real task (e.g. mine stone). The instant you earn the matching vanilla advancement, the quest closes, the party disbands, and the Builder remembers you.
4. **Talk to a Builder** by **chatting near it** — the nearest Builder within ~12 blocks replies in character (no command needed).
5. **Go boss-mode.** `/difficulty hard` → the next recruit becomes an Ender Dragon / Wither hunt.
6. **Watch the Director's View** at `http://localhost:5173` update live the whole time.

### Commands

| Command | Effect |
|---|---|
| `/builders spawn <n>` | Spawn `n` Builders near you. |
| `/builders accept [id]` | Accept a Builder's quest offer (id optional; accepts any pending offer). |
| `/builders decline [id]` | Decline a Builder's quest offer. |
| `/builders reset` | Remove all spawned Builders. |

---

## The Director (admin console)

The Director lets an admin control the server by chatting with the planner in admin mode. It **interrogates** you for every required field, then **proposes** — and **nothing runs until you confirm**.

**To use it:** open the dashboard → the **Admin Console** tab.

**Example session:**

1. Type: **"start a zombie outbreak near &lt;player&gt;"**.
2. The Director asks for each missing field, **one at a time** — area (it can resolve `near <playerName>` from the live world snapshot, or take explicit coords / "spawn"), **intensity** (low/med/high), **duration** (minutes), and **escalate** (yes/no). It refuses to propose until all are answered.
3. A **proposal card** appears summarizing the action (kind, area, params) with a status badge. Click **Confirm** to execute (or **Reject** to cancel — rejecting executes nothing).
4. The outbreak spawns waves near the player. **Only Builders inside the area warn players** (chat + on-screen title), flavored by personality — brave/gruff fight, timid/paranoid flee, others warn only.
5. The scenario **auto-ends** at its duration, or you can click **End** on the active-scenarios panel.

**World ops** work the same way (interrogate → propose → confirm): "**make it night**", "**clear weather**", "**spawn 4 builders at spawn**", etc.

**Capabilities & required fields** (all fields are mandatory before a proposal is allowed):

| Action | Required fields |
|---|---|
| `zombie_outbreak` | area, intensity (low/med/high), duration_minutes, escalate (bool) |
| `wildfire` | area, severity (low/med/high), duration_minutes |
| `storm_flood` | area, severity (low/med/high), duration_minutes, flood (bool — raise water?) |
| `meteor` | area, rate (low/med/high), duration_minutes |
| `set_time` | value (day/night/noon/midnight or `0–24000`) |
| `set_weather` | type (clear/rain/thunder), duration_minutes |
| `spawn_builders` | count, location (near-player/coords/spawn); optional job, personality |
| `despawn_builders` | scope (all/in-area) |

All disasters are **safety-clamped** (radius 4–64, capped mobs/waves, duration 1–15 minutes, small meteor explosions that don't break blocks by default, effects only in loaded chunks).

> **Security note (local/demo):** anyone who can open the dashboard can drive the server through the console. That's fine for the hackathon's local setup; a public deployment would add auth on the Admin Console.

---

## LLM configuration

The worker uses **one OpenAI-compatible client** (`worker/src/llm.ts`) for every loop, configured entirely through environment variables in `worker/.env`. **Never commit real keys** — `worker/.env` is gitignored; commit only the template.

| Variable | Meaning |
|---|---|
| `LLM_BASE_URL` | OpenAI-compatible base URL (e.g. the Kilo Gateway, OpenAI, Groq, or a local server). |
| `LLM_API_KEY` | Your API key. **Leave blank to run in offline MOCK mode.** |
| `LLM_MODEL` | Model for dialogue (idle thoughts, gossip, encouragement, proximity replies). |
| `LLM_PLANNER_MODEL` | Stronger model for the planner + Director (falls back to `LLM_MODEL` if unset). |
| `STDB_URI` / `STDB_DB` | SpacetimeDB connection (default `ws://127.0.0.1:3050` / `builders-rpg`). |

**`.env` template** (placeholders only — see `worker/.env.example`):

```bash
# SpacetimeDB connection (the local dev server)
STDB_URI=ws://127.0.0.1:3050
STDB_DB=builders-rpg

# LLM — OpenAI-compatible. Leave LLM_API_KEY blank for offline MOCK mode.
# Recommended: Kilo Gateway → Gemini 3.5 Flash (confirm the exact base URL in your dashboard).
LLM_BASE_URL=https://api.kilo.ai/...      # e.g. the Kilo Gateway base, or https://api.openai.com/v1
LLM_API_KEY=                              # <-- your key here, or leave blank for MOCK
LLM_MODEL=google/gemini-3.5-flash         # dialogue model (or gpt-4o, etc.)
LLM_PLANNER_MODEL=google/gemini-3.5-flash # planner/Director model (defaults to LLM_MODEL)
```

**Reasoning effort** is the key dial: **high** where a real decision is made (the planner, the Director — generous 2048-token budget) and **low** where snappy one-liners matter (dialogue — 512 tokens). Reasoning models spend completion tokens on hidden _thinking_ before the visible answer, so the planner/Director get a large budget to avoid an empty (silently mocked) response. With no key, every helper returns a deterministic, theme-appropriate **mock** so the system runs fully offline.

---

## Why SpacetimeDB

SpacetimeDB didn't just store data — it shaped the whole architecture, and most of the design's elegance falls directly out of its properties.

- **The database _is_ the server.** Application logic is uploaded into the database as a WebAssembly module — there's no separate game-server tier to write, deploy, or scale. Modules are hot-swappable: republish without disconnecting clients.
- **Reducers are a transactional rule engine.** The recruit **atomic cap** and the Director **confirm-gate** mean **no half-states and no race conditions**: a recruit either fully lands or fully aborts; an action either is confirmed or cannot run. The rules live in one place every client shares, so they can't drift between plugin, worker, and dashboard.
- **Real-time subscriptions are the backbone.** The worker and dashboard simply **react** to state via `subscribeToAllTables` + `onInsert/onUpdate/onDelete`. That makes "the AI reacts to the database" literal — and hands you a live, judge-facing dashboard essentially for free.
- **One shared world + durable memory.** A single shared state gives multiplayer for free, and Builder memory **survives server restarts** because it lives in the database, not the Minecraft world save.
- **Radical decoupling → parallel, multi-language development.** The Java plugin, TS worker, and React dashboard coordinate **only** through SpacetimeDB state. That's exactly what let entire subsystems (Builder Life, the Director, personalities, proximity chat) be built by **independent parallel agents that never touched each other's code** — they agreed on tables and reducers, then went heads-down.

See **[`architecture.html`](architecture.html)** for the full rationale, a traditional-stack comparison, and the patterns SpacetimeDB made trivial (the work-stealing build-job queue, the brain→body directive queue, and the confirm-gate state machine).

---

## Troubleshooting

- **Dashboard says "offline"** → is the module published and the `dev` SpacetimeDB server up? Check with `spacetime server ping dev`; start it with `spacetime start` if needed.
- **Server won't bind `:25566`** → the port is in use; change `server-port` in `server/server.properties` (or stop the other process).
- **No quests appear** → the worker must be running; check its log for `[planner] … → …` lines. Quests only generate while a player is online.
- **Builders don't move / don't act** → Citizens NPCs only behave in **loaded chunks**, which need a real player nearby. The recruit flow has an 8-second teleport fallback for pathing; for headless testing use `forceload add` plus a simulated player (see `scripts/e2e.sh`).
- **Gradle can't find JDK 21** → the system Gradle may run on a newer JDK; pass `-Dorg.gradle.java.installations.paths=<JDK21 home>` and set `JAVA_HOME` to JDK 21 (see [Setup & run](#setup--run)).
- **Config changes don't take effect** → remove the stale deployed copy at `server/plugins/BuildersPlugin/config.yml` and restart, so new keys (like `entity-type`) are picked up.
- **Builders have no skins** → skins are fetched from Mojang at runtime (needs internet); the pool is `plugin/src/main/resources/skins.txt`.

---

## Credits & license

Builders is a **non-commercial hackathon project**, used with attribution. See [`CREDITS.md`](CREDITS.md) for full details.

- **[SpacetimeDB](https://spacetimedb.com)** by Clockwork Labs — the real-time database/server that is the shared world and message bus (module + TypeScript SDKs).
- **[PaperMC](https://papermc.io)** — the high-performance Minecraft server (Paper 1.21.10) hosting the plugin.
- **[Citizens](https://wiki.citizensnpcs.co)** by the CitizensDev team — the NPC framework that gives Builders their bodies, pathfinding, and skins.
- **[ViaVersion](https://github.com/ViaVersion/ViaVersion)** — protocol translation so newer clients can join the 1.21.10 server.
- **Skins** are pulled from a pool of Mojang-verified usernames (`plugin/src/main/resources/skins.txt`); they are real Minecraft player skins fetched at runtime.
- **Advancement IDs** (`minecraft:story/…`, `minecraft:nether/…`, `minecraft:end/…`) are Mojang's vanilla Minecraft advancements. Builder names, personas, personalities, and dialogue templates are original to this project.
- **Towns vs villages:** Builder towns are _distinct_ settlements that **coexist with** vanilla villages — villages, nether fortresses, and ancient cities generate normally; nothing is overridden.
- **AI** dialogue and planning use an **OpenAI-compatible** API (e.g. Kilo Gateway → Gemini 3.5 Flash); with no key, the system runs in offline MOCK mode.
- Built with [Claude Code](https://claude.com/claude-code).
