// The Director — admin server-control console (worker side; see docs §4).
//
// An admin chats with "the Director" (the planner in admin mode) through the
// dashboard. The Director INTERROGATES the admin for every required field of the
// action they want, ONE question at a time, then PROPOSES a concrete action.
// It NEVER executes: it only ever writes admin_action(status='pending') via
// propose_action; nothing runs until the human clicks Confirm in the UI.
//
// Coupling to the rest of the system is SpacetimeDB state only:
//   - reads:  admin_message (transcript), player/builder/scenario/world_clock (snapshot)
//   - writes: director_reply (questions/answers) and propose_action (proposals)
//
// Runs on a ~4s interval (interactive — faster than the 20s planner). We process
// the OLDEST unprocessed admin message per tick and advance a module-scope cursor
// so old transcript is never replayed (init it to the current max on startup).

import { directorTurn } from './llm.ts';

const SESSION = 'main';

// Last admin_message id we've acted on. initDirectorCursor() sets it to the
// current max so we don't replay history written before the worker connected.
let cursor = 0n;
let directorRunning = false;

// ---------------------------------------------------------------------------
// Capabilities + required-field map (docs §4.3). ALL listed fields are mandatory
// before a proposal is allowed. `area` is a special field meaning {x,y,z,radius,
// world}; everything else lives in action.params. Non-spatial kinds omit `area`.
// ---------------------------------------------------------------------------

interface Capability {
  /** Required entries in action.params (besides `area`). */
  params: string[];
  /** Whether a resolved spatial area {x,y,z,radius,world} is required. */
  area: boolean;
  /** Human hint injected into the prompt (allowed values etc.). */
  hint: string;
}

const CAPABILITIES: Record<string, Capability> = {
  zombie_outbreak: {
    params: ['intensity', 'duration_minutes', 'escalate'],
    area: true,
    hint: 'intensity=low|med|high, duration_minutes=int(1-15), escalate=bool',
  },
  wildfire: {
    params: ['severity', 'duration_minutes'],
    area: true,
    hint: 'severity=low|med|high, duration_minutes=int(1-15)',
  },
  storm_flood: {
    params: ['severity', 'duration_minutes', 'flood'],
    area: true,
    hint: 'severity=low|med|high, duration_minutes=int(1-15), flood=bool(raise water?)',
  },
  meteor: {
    params: ['rate', 'duration_minutes'],
    area: true,
    hint: 'rate=low|med|high, duration_minutes=int(1-15)',
  },
  set_time: {
    params: ['value'],
    area: false,
    hint: 'value=day|night|noon|midnight or a number 0-24000',
  },
  set_weather: {
    params: ['type', 'duration_minutes'],
    area: false,
    hint: 'type=clear|rain|thunder, duration_minutes=int(1-15)',
  },
  spawn_builders: {
    params: ['count', 'location'],
    area: false,
    hint: 'count=int, location=near-player|coords|spawn; optional job, personality',
  },
  despawn_builders: {
    params: ['scope'],
    area: false,
    hint: 'scope=all|in-area',
  },
};

/** Initialize the cursor to the current max admin_message id (no replay). */
export function initDirectorCursor(conn: any): void {
  try {
    let max = 0n;
    for (const m of conn.db.adminMessage.iter()) {
      if (m.id > max) max = m.id;
    }
    cursor = max;
    console.log(`[director] cursor initialized at admin_message id=${cursor}`);
  } catch (e) {
    console.error('[director] initDirectorCursor failed', e);
  }
}

// ---------------------------------------------------------------------------
// World snapshot — injected into each user turn so the Director can resolve
// "near <player>", echo concrete values, and stay aware of live scenarios/time.
// ---------------------------------------------------------------------------

function buildSnapshot(conn: any): string {
  const players = [...conn.db.player.iter()].filter((p: any) => p.online);
  const playerLines = players.length
    ? players
        .map(
          (p: any) =>
            `- ${p.name} @ (${Math.round(p.x)}, ${Math.round(p.y)}, ${Math.round(p.z)}) in '${p.world}'`
        )
        .join('\n')
    : '- (none online)';

  const builders = [...conn.db.builder.iter()];
  const builderLines = builders.length
    ? builders
        .map(
          (b: any) =>
            `- ${b.name} [${b.job || 'no job'}${b.personality ? ', ' + b.personality : ''}] @ (${Math.round(
              b.x
            )}, ${Math.round(b.z)})`
        )
        .join('\n')
    : '- (none)';

  const scenarios = [...conn.db.scenario.iter()].filter((s: any) => s.status === 'active');
  const scenarioLines = scenarios.length
    ? scenarios
        .map(
          (s: any) =>
            `- ${s.kind} r${s.radius} @ (${Math.round(s.areaX)}, ${Math.round(s.areaY)}, ${Math.round(
              s.areaZ
            )}) in '${s.world}' wave ${s.wave}/${s.totalWaves}`
        )
        .join('\n')
    : '- (none active)';

  const wc = [...conn.db.worldClock.iter()][0];
  const clockLine = wc ? `phase=${wc.phase}, full_time=${wc.fullTime}` : 'unknown';

  const transcript = [...conn.db.adminMessage.iter()]
    .sort((a: any, b: any) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
    .slice(-10)
    .map((m: any) => `${m.role}: ${m.text}`)
    .join('\n');

  return [
    'WORLD SNAPSHOT',
    'Online players:',
    playerLines,
    'Builders:',
    builderLines,
    'Active scenarios:',
    scenarioLines,
    `World clock: ${clockLine}`,
    '',
    'Recent conversation (oldest→newest):',
    transcript || '(empty)',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// System prompt — establishes the Director's role, capabilities, and the strict
// JSON protocol. We list every capability with its required fields so the model
// knows exactly what to interrogate for.
// ---------------------------------------------------------------------------

function buildSystemPrompt(): string {
  const caps = Object.entries(CAPABILITIES)
    .map(([kind, c]) => {
      const fields = [...(c.area ? ['area'] : []), ...c.params].join(', ');
      return `- ${kind}: required = ${fields}  (${c.hint})`;
    })
    .join('\n');

  return [
    'You are the Director: the admin\'s interface for controlling a Minecraft RPG server through the planner.',
    'You can trigger disasters and world operations, but you NEVER execute anything yourself — a human must',
    'confirm every action in the UI. Your job is to interrogate the admin for the full scope of what they want,',
    'then emit a single concrete proposal.',
    '',
    'CAPABILITIES (each with its REQUIRED fields — ALL are mandatory before you may propose):',
    caps,
    '',
    'RULES:',
    '1. Interrogate, do not assume. For the action the admin wants, collect EVERY required field. Ask for missing',
    '   fields ONE at a time, in plain language. Never invent values the admin did not give.',
    '2. Resolve `area` (x, y, z, radius, world) from: explicit coordinates, "near <playerName>" (use the live',
    '   snapshot for that player\'s position + world), or "spawn". If the area is ambiguous, ask. Always ask for a',
    '   radius if one was not given (spatial kinds need it).',
    '3. NEVER execute. Only when EVERY required field is known, return a "propose". The human confirms it.',
    '4. Keep replies to one or two sentences. Be concrete: echo back the values you have gathered so far.',
    '',
    'OUTPUT — respond with STRICT JSON only (no prose, no code fences), exactly one of:',
    '{"type":"reply","text":"<one question or answer>","kind":"question"|"chat"}',
    '{"type":"propose","action":{"kind":"<capability>","summary":"<human-readable what-I-will-do>",',
    '  "params":{...required params...},',
    '  "area":{"x":<n>,"y":<n>,"z":<n>,"radius":<n>,"world":"<name>"}}}',
    'For non-spatial kinds (set_time, set_weather) you may omit area or set its fields to 0.',
    'Use kind "question" when you are still gathering required fields, "chat" for general remarks.',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Defensive JSON parse — strip ```json fences / stray backticks, then try/catch.
// ---------------------------------------------------------------------------

function parseDirectorJson(raw: string): any | null {
  if (!raw) return null;
  let s = raw.trim();
  // strip ``` or ```json fences
  s = s.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
  // if there's surrounding prose, grab the outermost JSON object
  if (s[0] !== '{') {
    const start = s.indexOf('{');
    const end = s.lastIndexOf('}');
    if (start !== -1 && end !== -1 && end > start) s = s.slice(start, end + 1);
  }
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

/** First required field still missing from a proposed action, or null if complete. */
function firstMissingField(kind: string, action: any): string | null {
  const cap = CAPABILITIES[kind];
  if (!cap) return 'a valid action kind';
  if (cap.area) {
    const a = action?.area;
    if (!a || typeof a.world !== 'string' || a.world === '' || !(Number(a.radius) > 0)) {
      return 'the target area (coords or "near <player>", plus a radius and world)';
    }
  }
  const params = action?.params || {};
  for (const f of cap.params) {
    const v = params[f];
    if (v === undefined || v === null || v === '') return f;
  }
  return null;
}

async function askForField(conn: any, field: string): Promise<void> {
  await conn.reducers.directorReply({
    sessionId: SESSION,
    text: `Before I can set that up I need to know: ${field}. What should it be?`,
    kind: 'question',
  });
}

// ---------------------------------------------------------------------------
// The tick.
// ---------------------------------------------------------------------------

export async function directorTick(conn: any): Promise<void> {
  if (directorRunning) return;
  directorRunning = true;
  try {
    // 1. Oldest unprocessed admin message.
    const pending = [...conn.db.adminMessage.iter()]
      .filter((m: any) => m.role === 'admin' && m.id > cursor)
      .sort((a: any, b: any) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    if (pending.length === 0) return;

    const msg = pending[0];
    cursor = msg.id; // advance now — one message per tick, never reprocessed.

    // 2 + 3. Snapshot + prompts.
    const snapshot = buildSnapshot(conn);
    const system = buildSystemPrompt();
    const user = `${snapshot}\n\nThe admin just said: "${msg.text}"\n\nRespond with the strict JSON described above.`;

    // 4. LLM turn + defensive parse.
    const raw = await directorTurn(system, user);
    const parsed = parseDirectorJson(raw);

    if (!parsed || typeof parsed !== 'object') {
      await conn.reducers.directorReply({
        sessionId: SESSION,
        text: 'Could you tell me more about what you want to do, and where?',
        kind: 'question',
      });
      return;
    }

    // 5. Reply.
    if (parsed.type === 'reply') {
      const text = String(parsed.text || '').trim();
      if (!text) {
        await conn.reducers.directorReply({
          sessionId: SESSION,
          text: 'Could you tell me more about what you want to do, and where?',
          kind: 'question',
        });
        return;
      }
      const kind = parsed.kind === 'question' || parsed.kind === 'chat' ? parsed.kind : 'chat';
      await conn.reducers.directorReply({ sessionId: SESSION, text, kind });
      return;
    }

    // 6. Propose — validate required fields (defense in depth) before writing.
    if (parsed.type === 'propose' && parsed.action && typeof parsed.action === 'object') {
      const action = parsed.action;
      const kind = String(action.kind || '');
      if (!CAPABILITIES[kind]) {
        await conn.reducers.directorReply({
          sessionId: SESSION,
          text: `I can't do "${kind || 'that'}". I can run: ${Object.keys(CAPABILITIES).join(', ')}. Which would you like?`,
          kind: 'question',
        });
        return;
      }
      const missing = firstMissingField(kind, action);
      if (missing) {
        await askForField(conn, missing);
        return;
      }
      const area = action.area || {};
      await conn.reducers.proposeAction({
        sessionId: SESSION,
        kind,
        summary: String(action.summary || kind),
        paramsJson: JSON.stringify(action.params || {}),
        areaX: Number(area.x) || 0,
        areaY: Number(area.y) || 0,
        areaZ: Number(area.z) || 0,
        radius: Number(area.radius) || 0,
        world: String(area.world || ''),
      });
      console.log(`[director] proposed ${kind}: ${action.summary || ''}`);
      return;
    }

    // Unknown shape — fall back to a safe question.
    await conn.reducers.directorReply({
      sessionId: SESSION,
      text: 'Could you tell me more about what you want to do, and where?',
      kind: 'question',
    });
  } catch (e) {
    console.error('[director] tick failed', e);
  } finally {
    directorRunning = false;
  }
}
