import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';

// session is fixed to "main" for the single shared admin console.
// Row data arrives via props from App (built with App's safeIter guard over
// possibly-missing bindings), so the console never reads conn.db directly.
const SESSION = 'main';

// status badge color per admin_action / scenario status
const STATUS_COLOR: Record<string, string> = {
  pending: 'var(--amber)',
  confirmed: 'var(--blue)',
  executing: 'var(--blue)',
  executed: 'var(--green)',
  rejected: 'var(--gray)',
  failed: 'var(--red)',
  active: 'var(--green)',
  ended: 'var(--gray)',
};
const statusColor = (s: string) => STATUS_COLOR[s] ?? 'var(--muted)';

// emoji per scenario / proposal kind
const KIND_EMOJI: Record<string, string> = {
  zombie_outbreak: '🧟',
  wildfire: '🔥',
  storm_flood: '⛈',
  meteor: '☄',
  set_time: '🕑',
  set_weather: '🌦',
  spawn_builders: '🧱',
  despawn_builders: '✖',
};
const kindEmoji = (k: string) => KIND_EMOJI[k] ?? '⚙';

// quick-suggestion chips that prefill the input box
const SUGGESTIONS = [
  'Start a zombie outbreak near ',
  'Make it night',
  'Clear weather',
  'Spawn 4 builders at spawn',
  'Start a wildfire at spawn',
];

// best-effort pretty-print of params_json
function parseParams(raw: string): Record<string, any> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? v : { value: v };
  } catch {
    return {};
  }
}

const fmtCoord = (n: any) => Math.round(Number(n));

function timeLeftSeconds(endsFullTime: any, fullTime: any): number {
  try {
    const left = Number(BigInt(endsFullTime) - BigInt(fullTime)) / 20;
    return Math.max(0, left);
  } catch {
    return 0;
  }
}

function fmtClock(seconds: number): string {
  const s = Math.floor(seconds);
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, '0')}`;
}

type Props = {
  conn: any;
  messages: any[];
  actions: any[];
  scenarios: any[];
  clock: any;
  players: any[];
};

function AreaLine({ a }: { a: any }) {
  const hasArea = Number(a.radius) > 0 || Number(a.areaX) !== 0 || Number(a.areaZ) !== 0;
  if (!hasArea) return <span className="muted">no area (world-wide)</span>;
  return (
    <span className="mono" style={{ color: 'var(--text)' }}>
      ({fmtCoord(a.areaX)},{fmtCoord(a.areaY)},{fmtCoord(a.areaZ)}) r={fmtCoord(a.radius)} ·{' '}
      <span className="muted">{a.world || 'world'}</span>
    </span>
  );
}

function ParamPills({ params }: { params: Record<string, any> }) {
  const entries = Object.entries(params);
  if (entries.length === 0) return null;
  return (
    <div className="row small" style={{ flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
      {entries.map(([k, v]) => (
        <span className="tag muted" key={k}>
          {k}=<span style={{ color: 'var(--text)' }}>{String(typeof v === 'object' ? JSON.stringify(v) : v)}</span>
        </span>
      ))}
    </div>
  );
}

function ActionCard({ action, conn }: { action: any; conn: any }) {
  const status = action.status ?? 'pending';
  const pending = status === 'pending';
  const params = parseParams(action.paramsJson);

  const confirm = () => {
    try { conn?.reducers.confirmAction({ actionId: action.id }); } catch (e) { console.error('confirmAction failed', e); }
  };
  const reject = () => {
    try { conn?.reducers.rejectAction({ actionId: action.id, reason: 'rejected from console' }); } catch (e) { console.error('rejectAction failed', e); }
  };

  return (
    <div className="action-card">
      <div className="row">
        <span className="name">⚠ PROPOSAL <span className="muted small">— confirm to execute</span></span>
        <span className="spacer" />
        <span className="status-badge" style={{ color: statusColor(status), borderColor: statusColor(status) }}>{status}</span>
      </div>
      <div className="row" style={{ marginTop: 6 }}>
        <span className="name" style={{ color: 'var(--gold)' }}>{kindEmoji(action.kind)} {action.kind}</span>
      </div>
      {action.summary && <div className="small" style={{ marginTop: 4 }}>{action.summary}</div>}
      <div className="small" style={{ marginTop: 6 }}>
        <span className="muted">area:</span> <AreaLine a={action} />
      </div>
      <ParamPills params={params} />
      <div className="row" style={{ marginTop: 10, gap: 10 }}>
        <button className="btn confirm" disabled={!pending} onClick={confirm}>Confirm</button>
        <button className="btn reject" disabled={!pending} onClick={reject}>Reject</button>
      </div>
    </div>
  );
}

function MessageRow({ m, action, conn }: { m: any; action: any; conn: any }) {
  const role = m.role ?? 'system';
  // a proposal message renders as an action card (look up its admin_action by action_id)
  if (m.kind === 'proposal') {
    if (action) return <ActionCard action={action} conn={conn} />;
    // proposal whose action row hasn't synced yet — show summary text as a fallback bubble
    return (
      <div className="msg-wrap director">
        <div className="bubble director">⚠ {m.text}</div>
      </div>
    );
  }
  if (role === 'system') {
    return <div className="msg-system">{m.text}</div>;
  }
  return (
    <div className={`msg-wrap ${role === 'admin' ? 'admin' : 'director'}`}>
      <div className={`bubble ${role === 'admin' ? 'admin' : 'director'}`}>
        {role === 'director' && <span className="who">Director</span>}
        {m.text}
      </div>
    </div>
  );
}

export default function AdminConsole({ conn, messages, actions, scenarios, clock, players }: Props) {
  const [text, setText] = useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  // index actions by id for quick proposal lookup (ids are bigint → key by String)
  const actionById = useMemo(() => {
    const map = new Map<string, any>();
    for (const a of actions) map.set(String(a.id), a);
    return map;
  }, [actions]);

  // transcript: this session, sorted by id asc
  const transcript = useMemo(
    () =>
      messages
        .filter((m) => m.sessionId === SESSION)
        .sort((a, b) => Number(a.id) - Number(b.id)),
    [messages]
  );

  const activeScenarios = useMemo(
    () =>
      scenarios
        .filter((sc) => sc.status === 'active')
        .sort((a, b) => Number(a.id) - Number(b.id)),
    [scenarios]
  );

  // keep transcript pinned to the newest message
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [transcript.length]);

  const send = () => {
    const t = text.trim();
    if (!t || !conn) return;
    try { conn.reducers.adminSend({ sessionId: SESSION, text: t }); } catch (e) { console.error('adminSend failed', e); }
    setText('');
  };

  const endScenario = (id: any) => {
    try { conn?.reducers.endScenario({ scenarioId: id }); } catch (e) { console.error('endScenario failed', e); }
  };

  const clearChat = () => {
    try { conn?.reducers.clearAdminSession({ sessionId: SESSION }); } catch (e) { console.error('clearAdminSession failed', e); }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  };

  // pick a sensible default player name for the "near <player>" chip
  const samplePlayer = players.find((p) => p.online)?.name ?? players[0]?.name ?? 'a player';
  const fullTime = clock?.fullTime ?? 0n;

  return (
    <div className="admin">
      <div className="admin-grid">
        {/* Active scenarios panel */}
        <div className="panel admin-scenarios">
          <h2><span>Active Scenarios</span><span>{activeScenarios.length}</span></h2>
          <div className="body">
            {activeScenarios.length === 0 && <div className="empty">No active scenarios. Propose one below.</div>}
            {activeScenarios.map((sc) => {
              const secs = timeLeftSeconds(sc.endsFullTime, fullTime);
              return (
                <div className="card" key={String(sc.id)}>
                  <div className="row">
                    <span className="name" style={{ color: 'var(--gold)' }}>{kindEmoji(sc.kind)} {sc.kind}</span>
                    <span className="spacer" />
                    <button className="btn end" onClick={() => endScenario(sc.id)}>End</button>
                  </div>
                  <div className="small" style={{ marginTop: 6 }}>
                    <AreaLine a={sc} />
                  </div>
                  <div className="row small" style={{ marginTop: 6 }}>
                    <span className="tag muted">wave <span style={{ color: 'var(--text)' }}>{Number(sc.wave)}/{Number(sc.totalWaves)}</span></span>
                    <span className="spacer" />
                    <span className="mono" style={{ color: secs > 0 ? 'var(--amber)' : 'var(--gray)' }}>⏳ {fmtClock(secs)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Director chat console */}
        <div className="panel admin-chat">
          <h2>
            <span>The Director — Admin Console</span>
            <button className="btn ghost" onClick={clearChat}>Clear chat</button>
          </h2>
          <div className="transcript" ref={scrollRef}>
            {transcript.length === 0 && (
              <div className="empty">
                Talk to the Director. It will interrogate you for scope, then propose an action you confirm here.
              </div>
            )}
            {transcript.map((m) => (
              <MessageRow key={String(m.id)} m={m} action={actionById.get(String(m.actionId))} conn={conn} />
            ))}
          </div>

          <div className="composer">
            <div className="chips">
              {SUGGESTIONS.map((s) => {
                const filled = s.endsWith('near ') ? `${s}${samplePlayer}` : s;
                return (
                  <button className="chip" key={s} onClick={() => setText(filled)}>{filled}</button>
                );
              })}
            </div>
            <div className="row" style={{ gap: 8 }}>
              <input
                className="admin-input"
                placeholder="Tell the Director what to do…  (Enter to send)"
                value={text}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={onKeyDown}
              />
              <button className="btn send" disabled={!text.trim()} onClick={send}>Send</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
