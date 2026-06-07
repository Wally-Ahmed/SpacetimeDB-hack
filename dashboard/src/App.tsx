import { useEffect, useRef, useState } from 'react';
import { DbConnection } from './module_bindings';

const URI = (import.meta as any).env?.VITE_STDB_URI || 'ws://127.0.0.1:3050';
const DB = (import.meta as any).env?.VITE_STDB_DB || 'builders-rpg';

type Snap = {
  players: any[];
  builders: any[];
  quests: any[];
  parties: any[];
  chat: any[];
  memories: any[];
  clock: any;
};

const EMPTY: Snap = { players: [], builders: [], quests: [], parties: [], chat: [], memories: [], clock: null };

export default function App() {
  const [status, setStatus] = useState<'connecting' | 'live' | 'error'>('connecting');
  const [err, setErr] = useState('');
  const [s, setS] = useState<Snap>(EMPTY);
  const connRef = useRef<any>(null);
  const timer = useRef<any>(null);

  useEffect(() => {
    let alive = true;
    const refresh = () => {
      if (timer.current) return;
      timer.current = setTimeout(() => {
        timer.current = null;
        const c = connRef.current;
        if (!c || !alive) return;
        try {
          setS({
            players: [...c.db.player.iter()],
            builders: [...c.db.builder.iter()],
            quests: [...c.db.quest.iter()],
            parties: [...c.db.party.iter()],
            chat: [...c.db.chatMessage.iter()],
            memories: [...c.db.builderMemory.iter()],
            clock: [...c.db.worldClock.iter()][0] ?? null,
          });
        } catch (e) { /* cache mid-update */ }
      }, 120);
    };

    const conn = DbConnection.builder()
      .withUri(URI)
      .withDatabaseName(DB)
      .onConnect((c: any) => {
        connRef.current = c;
        const tables = ['player', 'builder', 'quest', 'party', 'chatMessage', 'builderMemory', 'worldClock'];
        for (const t of tables) {
          c.db[t].onInsert(refresh);
          c.db[t].onUpdate(refresh);
          c.db[t].onDelete(refresh);
        }
        c.subscriptionBuilder().onApplied(() => { setStatus('live'); refresh(); }).subscribeToAllTables();
      })
      .onConnectError((_c: any, e: Error) => { setStatus('error'); setErr(e.message); })
      .onDisconnect(() => { if (alive) setStatus('error'); })
      .build();

    return () => { alive = false; try { conn.disconnect(); } catch {} };
  }, []);

  const builderName = (id: any) => s.builders.find((b) => b.npcId === id)?.name ?? `#${id}`;
  const playerName = (uuid: string) => s.players.find((p) => p.uuid === uuid)?.name ?? uuid.slice(0, 8);
  const activeQuests = s.quests.filter((q) => q.state === 'offered' || q.state === 'active');
  const phase = s.clock?.phase ?? '—';
  const tod = s.clock ? Number(s.clock.timeOfDay) : 0;

  const sortedChat = [...s.chat].sort((a, b) => Number(b.id) - Number(a.id)).slice(0, 40);
  const sortedMem = [...s.memories].sort((a, b) => Number(b.id) - Number(a.id)).slice(0, 20);

  return (
    <div className="app">
      <div className="topbar">
        <div>
          <div className="title">⚒ <span className="accent">Builders</span> — Director's View</div>
          <div className="subtitle">a generative story-mode RPG · powered by SpacetimeDB</div>
        </div>
        <div className="spacer" />
        <span className="pill">{phase === 'night' ? '☾ Night' : '☀ Day'} · <span className="mono">{tod}</span></span>
        <span className="pill">{s.players.filter((p) => p.online).length} online</span>
        <span className="pill">{s.builders.length} builders</span>
        <span className="pill">{activeQuests.length} quests</span>
        <span className="pill">
          <span className="dot" style={{ background: status === 'live' ? 'var(--green)' : status === 'error' ? 'var(--red)' : 'var(--amber)' }} />
          {status === 'live' ? 'LIVE' : status === 'error' ? 'offline' : 'connecting'}
        </span>
      </div>

      {status === 'error' && (
        <div className="panel" style={{ marginBottom: 16, padding: 12, color: 'var(--muted)' }}>
          Can't reach SpacetimeDB at <span className="mono">{URI}</span> / <span className="mono">{DB}</span>. {err}
        </div>
      )}

      <div className="grid">
        {/* Players + Parties */}
        <div className="col">
          <div className="panel">
            <h2><span>Adventurers</span><span>{s.players.length}</span></h2>
            <div className="body">
              {s.players.length === 0 && <div className="empty">No one has entered the world yet.</div>}
              {s.players.map((p) => (
                <div className="card" key={p.uuid}>
                  <div className="row">
                    <span className="dot" style={{ background: p.online ? 'var(--green)' : 'var(--gray)' }} />
                    <span className="name">{p.name}</span>
                    <span className={`tag ${p.difficulty}`}>{p.difficulty}</span>
                    {p.partyId !== 0n && <span className="tag" style={{ color: 'var(--blue)' }}>in party</span>}
                    <span className="spacer" />
                    <span className="muted small mono">{Math.round(Number(p.x))},{Math.round(Number(p.z))}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <h2><span>Active Parties</span><span>{s.parties.length}</span></h2>
            <div className="body">
              {s.parties.length === 0 && <div className="empty">No parties formed.</div>}
              {s.parties.map((pt) => {
                const q = s.quests.find((x) => x.id === pt.questId);
                return (
                  <div className="card" key={String(pt.id)}>
                    <div className="row">
                      <span className="name">{playerName(pt.playerUuid)}</span>
                      <span className="muted">+</span>
                      <span className="name" style={{ color: 'var(--gold)' }}>{builderName(pt.builderId)}</span>
                    </div>
                    {q && <div className="small muted">on “{q.title}”</div>}
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* Builders */}
        <div className="col">
          <div className="panel">
            <h2><span>Builders</span><span>the AI-minded folk</span></h2>
            <div className="body">
              {s.builders.length === 0 && <div className="empty">No Builders spawned. Try <span className="mono">/builders spawn 4</span></div>}
              {s.builders.map((b) => (
                <div className="card" key={b.npcId}>
                  <div className="row">
                    <span className="name" style={{ color: 'var(--gold)' }}>{b.name}</span>
                    <span className="tag">{b.schedule}</span>
                    {b.partyId !== 0n && <span className="tag" style={{ color: 'var(--blue)' }}>questing</span>}
                    <span className="spacer" />
                    <span className="muted small">{b.town}</span>
                  </div>
                  <div className="small muted">{b.persona}</div>
                  {b.thought && <div className="thought">“{b.thought}”</div>}
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Quests + Chat + Memories */}
        <div className="col">
          <div className="panel">
            <h2><span>Quests</span><span>{s.quests.length}</span></h2>
            <div className="body">
              {s.quests.length === 0 && <div className="empty">The story planner has not spoken yet.</div>}
              {[...s.quests].sort((a, b) => Number(b.id) - Number(a.id)).map((q) => (
                <div className={`card quest ${q.state}`} key={String(q.id)}>
                  <div className="row">
                    <span className="name">{q.title}</span>
                    <span className="spacer" />
                    <span className={`state ${q.state}`}>{q.state}</span>
                  </div>
                  <div className="small muted">
                    {builderName(q.builderId)} → {playerName(q.playerUuid)} · <span className="mono">{q.advancementId.replace('minecraft:', '')}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <h2><span>Town Chatter</span><span>live</span></h2>
            <div className="body">
              {sortedChat.length === 0 && <div className="empty">Quiet for now…</div>}
              {sortedChat.map((m) => (
                <div className="chat" key={String(m.id)}>
                  <span className="who">{m.speaker}:</span> {m.text}
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <h2><span>Builder Memories</span><span>{s.memories.length}</span></h2>
            <div className="body">
              {sortedMem.length === 0 && <div className="empty">No memories formed yet.</div>}
              {sortedMem.map((m) => (
                <div className="chat" key={String(m.id)}>
                  <span className="who">{builderName(m.builderId)}</span> <span className="muted small">[{m.kind}]</span> {m.text}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
