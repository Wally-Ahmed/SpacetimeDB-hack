# ⚒ Builders — a generative story-mode RPG for Minecraft

Builders adds a new species to Minecraft: **Builders** — mystical artisan NPCs whose *minds live
outside the game*. A story-planner AI sends them to walk up to players and recruit them for
**progression-appropriate quests** (real Minecraft advancements). Each Builder has memory, a
day/night routine, gossips with other Builders, and remembers what you did together.

The twist: **the brain isn't in Minecraft.** A real-time database — [SpacetimeDB](https://spacetimedb.com) —
sits in the middle, and three independent programs read/write it at once.

```
   Minecraft (Paper plugin)  ──HTTP──►   SpacetimeDB   ◄──WS (SDK)──►   AI worker (TS)
   "the body" (Java)                     "shared world"                 "the brain"
                                              ▲
                                              │ WS (read-only, SDK)
                                         Web dashboard (React)   ← the judge-facing "Director's View"
```

Nobody talks to anybody directly — the database is the only meeting point. That decoupling is
forced by two facts, and both turn out to be features:

- **SpacetimeDB has no Java SDK** → the Minecraft plugin talks to it over the plain **HTTP API**
  (reducer calls + SQL reads). Anonymous access to public tables, no client library needed.
- **SpacetimeDB reducers can't touch the network** (deterministic, sandboxed) → the LLM lives in a
  separate **worker** that watches the DB and writes results back. This is the clean "AI reacts to
  database changes" pattern that makes SpacetimeDB shine.

## What works

- **Builders** spawn as [Citizens](https://wiki.citizensnpcs.co) NPCs and register themselves in SpacetimeDB.
- **Story planner** (in the worker) picks a quest from the lowest progression tier you haven't
  cleared and tells a Builder to recruit you — enforced by an **atomic reducer** that respects the
  per-phase caps (easy 2 / med 4 / hard 5, per day *and* per night) and "not already in a party".
- A Builder **walks to you** (with an 8-second teleport fallback so pathing can't fail the demo) and
  offers the quest with a clickable **[ Accept the quest ]**.
- Accept → you **join a party**; complete the real advancement → the quest closes, the party
  disbands, and the Builder **writes a memory** about you.
- **Hard difficulty** skips straight to the boss tier — recruiting you to slay the **Ender Dragon**
  or summon the **Wither**. Change `/difficulty hard` mid-game and the next recruit becomes a boss hunt.
- **Day/night**: at night Builders switch to a `sleep` schedule with night-themed thoughts.
- **Inter-Builder gossip** flows through SpacetimeDB and shows up live on the dashboard.
- **Offline-safe**: with no LLM key the worker runs in **MOCK mode** (canned, theme-appropriate
  lines) so the entire demo works with no internet.

## Components & ports

| Component | Tech | Address |
|-----------|------|---------|
| SpacetimeDB (server `dev`) | already running locally | `127.0.0.1:3050` (db `builders-rpg`) |
| STDB module | TypeScript | `stdb/spacetimedb/` |
| AI worker (the brain) | Node + TS + STDB SDK | `worker/` |
| Minecraft server | Paper **1.21.10** + Citizens, Java 21 | `127.0.0.1:25566` (RCON `25575`, pw `builders`) |
| Dashboard (Director's View) | React + Vite + STDB SDK | `http://localhost:5173` |

> Note: targeting **1.21.10** (not 1.20.1 as originally planned) because the current Citizens build
> only ships NMS adapters for 1.21.x. Advancement IDs are identical, so nothing else changed.

## Run it

Prereqs already installed on this machine: `spacetime` CLI 2.4.x, Node, Gradle, JDK 21
(`/opt/homebrew/opt/openjdk@21`). The local SpacetimeDB `dev` server runs on `:3050`.

```bash
# 1. Publish the module (schema + reducers) to the local dev server
bash scripts/publish.sh

# 2. Start the AI worker (the brain)         — leave running
cd worker && npm install && npm start

# 3. Start the Minecraft server               — leave running
bash scripts/server.sh        # Paper 1.21.10 on :25566

# 4. Start the dashboard                       — leave running
cd dashboard && npm install && npm run dev    # http://localhost:5173
```

(Or just `bash scripts/run-all.sh` to publish + start the worker, server, and dashboard together.)

A **starter town of 4 Builders auto-spawns at world spawn** on boot (configurable via
`builders.auto-spawn` in the plugin config), so there's life in the world immediately.

Then **connect a Minecraft Java 1.21.10 client to `localhost:25566`** (offline mode — any
username works) and:

1. Wait ~20s — a Builder walks over and offers a quest. Click **[ Accept the quest ]** (or
   right-click the Builder). (Spawn more with `/builders spawn 4`.)
2. Do the task (e.g. mine stone). The quest completes; the Builder remembers you.
3. Watch the **Director's View** at `http://localhost:5173` update live the whole time.
4. `/difficulty hard` → the next recruit is a dragon/wither hunt.

> The full in-game flow (join → recruit → click Accept → advancement completes the quest) is
> verified end-to-end with a headless [mineflayer](https://github.com/PrismarineJS/mineflayer)
> client — see the verification notes in the build log.

## Going live with the LLM

By default the worker runs in **MOCK mode**. To use a real model, edit `worker/.env`:

```bash
# Kilo Gateway (recommended: Gemini 2.5 Flash — fast, smart, 0% markup)
LLM_BASE_URL=https://api.kilo.ai/v1     # confirm the exact base URL in your Kilo dashboard
LLM_API_KEY=sk-kilo-...
LLM_MODEL=gemini-2.5-flash
# or OpenAI:  LLM_BASE_URL=https://api.openai.com/v1  LLM_MODEL=gpt-4o
```

Restart the worker. It's OpenAI-compatible, so Groq / OpenAI / Gemini / local all work by changing
the base URL + model. (The story planner can use a separate `LLM_PLANNER_MODEL`.)

## Headless test (no Minecraft client needed)

`scripts/e2e.sh` spawns Builders via RCON, registers a fake player over HTTP, and drives the full
recruit → accept → complete loop, asserting each step in SpacetimeDB.

## Reset

`bash scripts/reset.sh` wipes all runtime data (re-publishes the module with `--delete-data`) and
re-seeds the world clock. `/builders reset` in-game removes spawned Builders.

## Troubleshooting

- **Dashboard says "offline"** → is the worker/module published and is `dev` STDB up
  (`spacetime server ping dev`)? Start it with `spacetime start` if needed.
- **Server won't bind** → port `25566` in use; change `server-port` in `server/server.properties`.
- **No quests appear** → the worker must be running; check its log for `[planner] … →`.
- **Builders don't move** → Citizens needs the chunk loaded; the 8s teleport fallback covers pathing.

## Credits

See [CREDITS.md](CREDITS.md). Built on Paper, Citizens, and SpacetimeDB. Assets used under their
respective licenses for this non-commercial hackathon project.
