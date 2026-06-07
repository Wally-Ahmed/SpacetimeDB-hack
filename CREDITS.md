# Credits

Builders is a hackathon project. It depends on the following open-source software and is used
non-commercially with attribution.

## Core technology
- **[SpacetimeDB](https://spacetimedb.com)** by Clockwork Labs — the real-time database/server that
  is the shared world and message bus. Module + client SDKs (TypeScript).
- **[PaperMC](https://papermc.io)** — high-performance Minecraft server (Paper 1.21.10) that hosts
  the plugin. Players join with a vanilla client.
- **[Citizens](https://wiki.citizensnpcs.co)** by the CitizensDev team — the NPC framework that
  gives Builders their bodies, pathfinding, and names. Shipped as a server dependency
  (`Citizens-2.0.42-b4187.jar`), fetched via `scripts/fetch-deps.sh`.

## Assets
- Builder bodies use **Citizens'** default villager/player models — no custom textures or models
  were copied from other mods.
- Advancement IDs (`minecraft:story/...`, `minecraft:nether/...`, `minecraft:end/...`) are
  Mojang's vanilla Minecraft advancements.
- Builder names, personas, and all dialogue templates are original to this project.
- **Towns**: the demo uses vanilla villages / nether fortresses / ancient cities. If you add a
  village datapack (e.g. *Villages Revamped*, *Pozoj's Plains Villages*), credit its author here
  per its license (typically CC-BY-NC-SA).

## AI
- Builder dialogue + the story planner use an **OpenAI-compatible** API (e.g. Kilo Gateway →
  Gemini 2.5 Flash, or OpenAI). With no key, the system runs in offline MOCK mode.

## Built with
- [Claude Code](https://claude.com/claude-code).
