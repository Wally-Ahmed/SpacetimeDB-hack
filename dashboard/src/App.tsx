import { useEffect, useRef, useState } from 'react';
import { DbConnection } from './module_bindings';
import AdminConsole from './AdminConsole';

const URI = (import.meta as any).env?.VITE_STDB_URI || 'ws://127.0.0.1:3050';
const DB = (import.meta as any).env?.VITE_STDB_DB || 'builders-rpg';

type Snap = {
  players: any[];
  builders: any[];
  quests: any[];
  parties: any[];
  chat: any[];
  memories: any[];
  buildJobs: any[];
  inventory: any[];
  clock: any;
  adminMessages: any[];
  adminActions: any[];
  scenarios: any[];
};

const EMPTY: Snap = { players: [], builders: [], quests: [], parties: [], chat: [], memories: [], buildJobs: [], inventory: [], clock: null, adminMessages: [], adminActions: [], scenarios: [] };

// safe iterate: new tables may not exist in bindings yet while schema lands concurrently
const safeIter = (c: any, table: string): any[] => {
  try { return [...c.db[table].iter()]; } catch { return []; }
};

// per-state visual treatment (color + emoji)
const STATE_META: Record<string, { emoji: string; color: string }> = {
  sleeping: { emoji: '😴', color: 'var(--blue)' },
  working: { emoji: '⛏', color: 'var(--green)' },
  building: { emoji: '🧱', color: 'var(--gold)' },
  fighting: { emoji: '⚔', color: 'var(--red)' },
  recruiting: { emoji: '🗺', color: 'var(--amber)' },
  idle: { emoji: '💤', color: 'var(--gray)' },
};
const stateMeta = (st: string) => STATE_META[st] ?? { emoji: '•', color: 'var(--muted)' };

export default function App() {
  const [status, setStatus] = useState<'connecting' | 'live' | 'error'>('connecting');
  const [err, setErr] = useState('');
  const [s, setS] = useState<Snap>(EMPTY);
  const [tab, setTab] = useState<'world' | 'admin'>('world');
  // expose the live connection to children (AdminConsole calls reducers); set in onConnect
  const [conn, setConn] = useState<any>(null);
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
            buildJobs: safeIter(c, 'buildJob'),
            inventory: safeIter(c, 'builderInventory'),
            clock: [...c.db.worldClock.iter()][0] ?? null,
            adminMessages: safeIter(c, 'adminMessage'),
            adminActions: safeIter(c, 'adminAction'),
            scenarios: safeIter(c, 'scenario'),
          });
        } catch (e) { /* cache mid-update */ }
      }, 120);
    };

    const connection = DbConnection.builder()
      .withUri(URI)
      .withDatabaseName(DB)
      .onConnect((c: any) => {
        connRef.current = c;
        setConn(c);
        const tables = ['player', 'builder', 'quest', 'party', 'chatMessage', 'builderMemory', 'worldClock', 'buildJob', 'builderInventory', 'adminMessage', 'adminAction', 'scenario'];
        for (const t of tables) {
          // guard: buildJob/builderInventory may not exist in bindings until the schema lands
          try {
            c.db[t].onInsert(refresh);
            c.db[t].onUpdate(refresh);
            c.db[t].onDelete(refresh);
          } catch { /* table not in bindings yet */ }
        }
        c.subscriptionBuilder().onApplied(() => { setStatus('live'); refresh(); }).subscribeToAllTables();
      })
      .onConnectError((_c: any, e: Error) => { setStatus('error'); setErr(e.message); })
      .onDisconnect(() => { if (alive) setStatus('error'); })
      .build();

    return () => { alive = false; try { connection.disconnect(); } catch {} };
  }, []);

  const builderName = (id: any) => s.builders.find((b) => b.npcId === id)?.name ?? `#${id}`;
  // active build job for a builder: prefer an unfinished one, newest first
  const buildJobFor = (npcId: any) =>
    [...s.buildJobs]
      .filter((j) => j.builderId === npcId)
      .sort((a, b) => Number(b.id) - Number(a.id))
      .find((j) => j.status !== 'done') ??
    [...s.buildJobs].filter((j) => j.builderId === npcId).sort((a, b) => Number(b.id) - Number(a.id))[0] ??
    null;
  const inventoryFor = (npcId: any) =>
    s.inventory.filter((it) => it.builderId === npcId && Number(it.count) > 0).sort((a, b) => Number(b.count) - Number(a.count));
  const playerName = (uuid: string) => s.players.find((p) => p.uuid === uuid)?.name ?? uuid.slice(0, 8);
  const activeQuests = s.quests.filter((q) => q.state === 'offered' || q.state === 'active');
  const activeScenarios = s.scenarios.filter((sc) => sc.status === 'active');
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
        <div className="tabs">
          <button className={`tab ${tab === 'world' ? 'on' : ''}`} onClick={() => setTab('world')}>World</button>
          <button className={`tab ${tab === 'admin' ? 'on' : ''}`} onClick={() => setTab('admin')}>
            Admin Console
            {activeScenarios.length > 0 && <span className="tab-badge">{activeScenarios.length}</span>}
          </button>
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

      {tab === 'admin' ? (
        <AdminConsole
          conn={conn}
          messages={s.adminMessages}
          actions={s.adminActions}
          scenarios={s.scenarios}
          clock={s.clock}
          players={s.players}
        />
      ) : (
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
              {s.builders.map((b) => {
                const st = b.state ?? '';
                const sm = stateMeta(st);
                const job = buildJobFor(b.npcId);
                const inv = inventoryFor(b.npcId);
                const total = job ? Number(job.total) : 0;
                const step = job ? Number(job.step) : 0;
                const pct = total > 0 ? Math.min(100, Math.round((step / total) * 100)) : 0;
                return (
                  <div className="card" key={b.npcId}>
                    <div className="row">
                      <span className="name" style={{ color: 'var(--gold)' }}>{b.name}</span>
                      {b.job ? <span className="tag" style={{ color: 'var(--blue)' }}>{b.job}</span> : <span className="tag muted">no job</span>}
                      {st && <span className="tag" style={{ color: sm.color, borderColor: sm.color }}>{sm.emoji} {st}</span>}
                      {b.partyId !== 0n && <span className="tag" style={{ color: 'var(--blue)' }}>questing</span>}
                      <span className="spacer" />
                      <span className="muted small">{b.town}</span>
                    </div>
                    <div className="small muted">{b.persona}</div>
                    <div className="row small" style={{ marginTop: 6 }}>
                      {b.heldItem
                        ? <span className="muted">holding <span className="mono" style={{ color: 'var(--text)' }}>{b.heldItem}</span></span>
                        : <span className="muted">empty-handed</span>}
                    </div>
                    {job && (
                      <div style={{ marginTop: 8 }}>
                        <div className="row small">
                          <span className="muted">{job.status === 'done' ? 'built' : 'building'}</span>
                          <span className="name" style={{ color: 'var(--gold)' }}>{job.kind}</span>
                          <span className="spacer" />
                          <span className={`state ${job.status === 'done' ? 'done' : 'active'}`}>{job.status}</span>
                          <span className="muted mono">{step}/{total || '?'}</span>
                        </div>
                        <div style={{ height: 6, background: 'var(--line)', borderRadius: 999, overflow: 'hidden', marginTop: 4 }}>
                          <div style={{ height: '100%', width: `${pct}%`, background: job.status === 'done' ? 'var(--green)' : 'var(--gold)', transition: 'width 0.3s' }} />
                        </div>
                      </div>
                    )}
                    {inv.length > 0 && (
                      <div className="row small" style={{ marginTop: 8, flexWrap: 'wrap', gap: 6 }}>
                        {inv.map((it) => (
                          <span className="tag muted" key={`${b.npcId}-${it.item}`}>
                            <span style={{ color: 'var(--text)' }}>{it.item}</span> ×{Number(it.count)}
                          </span>
                        ))}
                      </div>
                    )}
                    {b.thought && <div className="thought">“{b.thought}”</div>}
                  </div>
                );
              })}
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
      )}
    </div>
  );
}
