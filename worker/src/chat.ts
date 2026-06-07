// Player ⇄ Builder proximity chat (worker side; see docs §10).
//
// A player talks to a Builder simply by chatting near it: the plugin finds the
// nearest in-range Builder and inserts a player_message(responded=false). This
// loop picks up unanswered messages, generates an in-character reply (LLM —
// Builder persona + personality + backstory + recent chat memory), answers via
// builder_say (→ a "say" directive the plugin prints to the player), records a
// builder_memory('chat') for continuity, then marks the message responded.
//
// Coupling is SpacetimeDB state only. Runs on a ~3s interval (see index.ts).

import { replyToPlayer } from './llm.ts';

let chatRunning = false;

/** How many recent 'chat' memories to feed back for conversational continuity. */
const RECENT_LIMIT = 4;

export async function chatTick(conn: any): Promise<void> {
  if (chatRunning) return;
  chatRunning = true;
  try {
    const pending = [...conn.db.playerMessage.iter()]
      .filter((m: any) => !m.responded)
      .sort((a: any, b: any) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    if (pending.length === 0) return;

    // Snapshot builders + memories once per tick.
    const builders = [...conn.db.builder.iter()];
    const memories = [...conn.db.builderMemory.iter()];

    for (const m of pending) {
      const builder = builders.find((b: any) => b.npcId === m.builderId);

      // No such Builder (despawned?) — still mark responded so the row isn't stuck.
      if (!builder) {
        try {
          await conn.reducers.markPlayerMessageResponded({ id: m.id });
        } catch (e) {
          console.error('[chat] failed to clear orphan message', m.id, e);
        }
        continue;
      }

      // Recent chat context for this Builder (oldest→newest, last few).
      const recent = memories
        .filter((mem: any) => mem.builderId === m.builderId && mem.kind === 'chat')
        .sort((a: any, b: any) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
        .slice(-RECENT_LIMIT)
        .map((mem: any) => mem.text);

      const reply = await replyToPlayer(builder, m.playerName, m.text, recent);

      try {
        await conn.reducers.builderSay({ builderId: m.builderId, playerUuid: m.playerUuid, text: reply });
      } catch (e) {
        console.error('[chat] builderSay failed', m.id, e);
      }

      try {
        await conn.reducers.addMemory({
          builderId: m.builderId,
          kind: 'chat',
          text: `${m.playerName}: ${m.text} | me: ${reply}`,
        });
      } catch (e) {
        console.error('[chat] addMemory failed', m.id, e);
      }

      try {
        await conn.reducers.markPlayerMessageResponded({ id: m.id });
      } catch (e) {
        console.error('[chat] markPlayerMessageResponded failed', m.id, e);
      }

      console.log(`[chat] ${builder.name} → ${m.playerName}: "${reply}"`);
    }
  } catch (e) {
    console.error('[chat] tick failed', e);
  } finally {
    chatRunning = false;
  }
}
