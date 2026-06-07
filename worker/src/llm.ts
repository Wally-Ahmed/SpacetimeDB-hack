// LLM layer — OpenAI-compatible (works with Kilo Gateway, OpenAI, Groq, local…).
// If LLM_API_KEY is blank, every helper falls back to canned, theme-appropriate
// MOCK lines, so the entire system runs fully offline (demo-safe).

import type { Adv } from './advancements.ts';

const BASE = process.env.LLM_BASE_URL || 'https://api.openai.com/v1';
const KEY = process.env.LLM_API_KEY || '';
const MODEL = process.env.LLM_MODEL || 'gpt-4o';
const PLANNER_MODEL = process.env.LLM_PLANNER_MODEL || MODEL;

export const LLM_LIVE = !!KEY;

export function llmStatus(): string {
  return LLM_LIVE ? `LIVE (${MODEL} @ ${BASE})` : 'MOCK (offline — no LLM_API_KEY)';
}

interface Builderish {
  name: string;
  persona: string;
}

async function chat(system: string, user: string, opts?: { model?: string; maxTokens?: number }): Promise<string> {
  if (!KEY) return '';
  try {
    const res = await fetch(`${BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
      body: JSON.stringify({
        model: opts?.model || MODEL,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user },
        ],
        max_tokens: opts?.maxTokens ?? 80,
        temperature: 0.9,
      }),
    });
    if (!res.ok) {
      console.error(`[llm] ${res.status}: ${(await res.text()).slice(0, 160)}`);
      return '';
    }
    const j: any = await res.json();
    return String(j?.choices?.[0]?.message?.content ?? '').trim().replace(/^["']|["']$/g, '');
  } catch (e) {
    console.error('[llm] fetch failed:', (e as Error).message);
    return '';
  }
}

const persona = (b: Builderish) =>
  `You are ${b.name}, ${b.persona}. You are a "Builder" — a mystical artisan-folk in a Minecraft world of stone-age craft and redstone magic. Speak in ONE short, in-character sentence, an earthy medieval-mystic tone. No emojis, no quotes, no stage directions.`;

function pick<T>(arr: T[]): T {
  return arr[Math.floor(Math.random() * arr.length)];
}

// ----------------------------------------------------------------------------
// Recruitment invitation
// ----------------------------------------------------------------------------

export async function inviteLine(b: Builderish, playerName: string, adv: Adv, phase: string): Promise<string> {
  const out = await chat(
    persona(b),
    `It is ${phase}. Invite the wandering adventurer ${playerName} to join you on a task: "${adv.title}" — to ${adv.hint}. Make it enticing in one sentence.`,
    { model: PLANNER_MODEL, maxTokens: 60 }
  );
  return out || mockInvite(b, playerName, adv);
}

function mockInvite(b: Builderish, playerName: string, adv: Adv): string {
  return pick([
    `Hail, ${playerName}! Lend me your hands and we shall ${adv.hint} together.`,
    `${playerName}, the runes whisper of one who could ${adv.hint} — walk with me?`,
    `Well met, ${playerName}. My craft calls for "${adv.title}". Will you aid old ${b.name}?`,
    `Adventurer ${playerName}! A task awaits: ${adv.hint}. Join my party and glory is ours.`,
  ]);
}

// ----------------------------------------------------------------------------
// Idle thoughts (for the dashboard)
// ----------------------------------------------------------------------------

export async function idleThought(b: Builderish, phase: string): Promise<string> {
  const out = await chat(persona(b), `It is ${phase}. Share a single passing thought as you go about your day.`, {
    maxTokens: 40,
  });
  return out || mockThought(b, phase);
}

function mockThought(b: Builderish, phase: string): string {
  const day = [
    `The forge runs hot today — good for shaping iron.`,
    `Redstone hums beneath the cobbles; I feel it in my boots.`,
    `If only an adventurer would pass through these gates.`,
    `Three more bricks and the east wall is mine to finish.`,
  ];
  const night = [
    `The torches gutter low; best I rest these old bones.`,
    `Stars wheel over the keep — the Builders' hour.`,
    `Something stirs beyond the walls. I'll bar the door.`,
    `Dreams of diamond veins, deep and blue.`,
  ];
  return pick(phase === 'night' ? night : day);
}

// ----------------------------------------------------------------------------
// Gossip between two nearby builders
// ----------------------------------------------------------------------------

export async function gossipLine(b: Builderish, other: Builderish, phase: string): Promise<string> {
  const out = await chat(
    persona(b),
    `You bump into your fellow Builder ${other.name} (${other.persona}). Say one short line of gossip or banter to them.`,
    { maxTokens: 40 }
  );
  return out || mockGossip(b, other);
}

function mockGossip(b: Builderish, other: Builderish): string {
  return pick([
    `${other.name}, did you hear? An outsider was seen near the old mine.`,
    `Mind the redstone lines, ${other.name} — they've been sparking since dawn.`,
    `Trade you two emeralds for that blaze rod, ${other.name}?`,
    `The elders say a hero comes. About time, eh ${other.name}?`,
  ]);
}

// ----------------------------------------------------------------------------
// Encouragement to a party member
// ----------------------------------------------------------------------------

export async function encourage(b: Builderish, playerName: string, adv: Adv): Promise<string> {
  const out = await chat(persona(b), `Encourage your party-mate ${playerName} who is working to ${adv.hint}. One short line.`, {
    maxTokens: 40,
  });
  return out || pick([
    `Keep at it, ${playerName} — ${adv.title} is within reach!`,
    `Steady hands, ${playerName}. We'll see this through.`,
    `I believe in you, ${playerName}. To ${adv.title}!`,
  ]);
}
