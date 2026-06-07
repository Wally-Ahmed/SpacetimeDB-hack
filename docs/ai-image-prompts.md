# Builders — Text-to-Image Prompts (for demo art)

Paste-ready prompts to recreate each scene with a text-to-image model (Midjourney, DALL·E 3, Stable Diffusion, Flux, etc.). Each scene has a **prompt** and a **negative prompt**. A shared style block is at the top — prepend it (or rely on the per-scene wording, which already folds it in).

> **Aspect ratio:** all scenes are framed 16:9. Midjourney: add `--ar 16:9 --style raw`. SD/Flux: 1344×768 or 1536×864.

## Global style block (optional prepend)
```
Minecraft voxel art, blocky cubic geometry, 16x16 pixel-art block textures, low-poly cubes, vibrant saturated colors, soft volumetric lighting, cinematic in-game screenshot, crisp and highly detailed
```

## Global negative prompt (use on all in-game scenes)
```
photorealistic humans, smooth realistic geometry, rounded shapes, non-voxel, text artifacts, watermark, signature, UI clutter, blurry, lowres, deformed limbs, extra fingers, oversaturated noise
```

---

## Scene 1 — The Builder Town (establishing)
**Prompt:**
```
A bustling medieval-fantasy village built entirely in Minecraft voxel style, viewed from a high three-quarter aerial angle in bright midday sunlight. Blocky cobblestone houses with oak-plank roofs and oak doors, a stone forge with a furnace and an anvil and a small cobblestone chimney, a 3x3 wheat farm with a central water source, a square log cabin, and a fenced animal pen — all made of cubic blocks. Several Minecraft player-character NPCs (dwarven artisans wearing varied player skins) walk among the buildings carrying iron tools. Lush green grass, scattered oak trees, glowing torches. Clear blue sky, soft shadows. Cinematic Minecraft screenshot, vibrant, highly detailed, 16:9.
```
**Negative:** global negative.

## Scene 2 — Builder Close-Up (character profile)
**Prompt:**
```
Close-up of a single Minecraft player-character NPC — a gruff dwarven mason with a rugged custom player skin — standing in a voxel village, holding an iron pickaxe, head turned three-quarters toward the camera. Blocky cubic Steve-proportioned body, 16x16 pixel-art skin texture, a small floating white name tag hovering above its head. Soft warm daylight, shallow depth of field with a blurred cobblestone-and-oak house behind it. Minecraft voxel art, vibrant, detailed game screenshot, portrait composition, 16:9.
```
**Negative:** global negative + `realistic human face, smooth skin`.

## Scene 3 — Builders at Work (the autonomous life)
**Prompt:**
```
Daytime Minecraft voxel village showing several player-character NPC artisans each doing a different job: one mason placing cobblestone blocks on a half-built house (blocks visibly stacking), one farmer tilling farmland with an iron hoe beside green wheat crops, one lumberjack chopping an oak tree with an iron axe, one carrying blocks. Iron tools, work-in-progress structures, a managed feeling of a living town. Bright sunlight, green grass, blue sky. Cinematic Minecraft screenshot, voxel art, lively, highly detailed, 16:9.
```
**Negative:** global negative.

## Scene 4 — Zombie Outbreak (the disaster)
**Prompt:**
```
Dramatic Minecraft scene at dusk: a swarm of dozens of blocky green zombies and sandy husks pouring through a voxel village under a blood-orange and deep-purple sky. Minecraft player-character NPC builders fight back with glowing iron swords amid the undead horde; one terrified builder flees toward a cobblestone house. Scattered torches throw warm light against the dim eerie gloom, trampled wheat crops, drifting particle effects, intense tension and chaos. Cinematic Minecraft screenshot, voxel art, vibrant yet ominous, highly detailed, 16:9.
```
**Negative:** global negative + `daytime, calm, empty`.

## Scene 5 — Builder Warning (area-scoped alert)
**Prompt:**
```
A single Minecraft player-character NPC builder mid-shout at the edge of a voxel village at twilight, one arm raised in alarm, iron sword in the other hand, brave heroic stance. Behind it, blocky zombies approach through the dim. Dramatic warm rim lighting from nearby torches against the cold blue dusk, a faint floating warning vibe. Cinematic Minecraft screenshot, voxel art, tense and heroic, 16:9.
```
**Negative:** global negative.

## Scene 6 — Talk to a Builder (proximity chat)
**Prompt:**
```
Over-the-shoulder Minecraft view of a player standing face-to-face with a friendly player-character NPC builder in a sunlit voxel village. The cheerful artisan NPC (with a floating name tag) gestures mid-conversation; a single line of chat text in Minecraft's pixel font sits at the lower-left of the frame. Warm daylight, cobblestone and oak-plank surroundings, inviting mood. Cinematic Minecraft screenshot, voxel art, friendly, 16:9.
```
**Negative:** global negative + `crowd, combat, monsters`.

## Scene 7 — Night & Storm (world control)
**Prompt:**
```
A Minecraft voxel village at night during a heavy thunderstorm: dark indigo sky, slanting rain streaks, a brilliant white-blue lightning bolt striking near cobblestone rooftops, torches flickering warm against the gloom. Silhouetted player-character NPC builders, a couple sleeping in red beds glimpsed through lit windows. Moody, atmospheric, high-contrast dramatic lighting. Cinematic Minecraft screenshot, voxel art, 16:9.
```
**Negative:** global negative + `daylight, sunny, bright sky`.

---

## Scene 8 — The Director Admin Console (UI)
*(This is the web dashboard, not in-game — use a UI/mockup style, drop the voxel keywords.)*
**Prompt:**
```
A sleek dark-mode web dashboard UI titled "The Director — Admin Console". Two-column layout: a left "Active Scenarios" panel and a right chat transcript between an admin and an AI "Director". The transcript shows an interrogation question and a highlighted gold-bordered "PROPOSAL — confirm to execute" card for a "zombie_outbreak" action, with green "executed" status badge, parameter pills (intensity=high, duration=2m, escalate=true), an area line with coordinates, and Confirm / Reject buttons. Navy-charcoal background, gold and blue accents, monospace data, clean modern typography, generous spacing. Crisp UI design mockup, 16:9.
```
**Negative:** `photo, 3d render, voxel, minecraft, blurry, lorem ipsum gibberish text`.

## Scene 9 — The World Dashboard (UI)
**Prompt:**
```
A dark-mode real-time game dashboard with multiple columns of data cards: "Adventurers", "Active Parties", "Builders" (each builder card showing a job tag, a colored state chip with an emoji, a gold build-progress bar, and an inventory of block tags), "Quests", and a live "Town Chatter" feed. Navy background, gold/green/blue accent tags, clean modern data UI, monospace numbers. Crisp UI design mockup, 16:9.
```
**Negative:** `photo, voxel, minecraft world, blurry, gibberish text`.

---

### Tips
- For consistency of the **same builder** across Scenes 2/5/6, reuse a character seed (Midjourney `--seed`, or an SD reference image / IP-Adapter).
- Want them to look like real Minecraft screenshots? Add `f1 hud-less first-person Minecraft, fast graphics` or a render style like `Chunky renderer, path-traced` for a glossier look.
- Keep one consistent **time of day** per scene group; the contrast between Scene 1 (day) and Scenes 4/7 (dusk/night) sells the story.
