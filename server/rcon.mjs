// Minimal one-shot Source RCON client: `node rcon.mjs "<command>"`
import net from 'node:net';

const HOST = process.env.RCON_HOST || '127.0.0.1';
const PORT = Number(process.env.RCON_PORT || 25575);
const PW = process.env.RCON_PW || 'builders';
const cmd = process.argv.slice(2).join(' ') || 'list';

function pkt(id, type, body) {
  const b = Buffer.from(body, 'utf8');
  const len = 4 + 4 + b.length + 2;
  const buf = Buffer.alloc(4 + len);
  buf.writeInt32LE(len, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  b.copy(buf, 12);
  return buf; // last 2 bytes already zero (null term + pad)
}

const sock = net.connect(PORT, HOST, () => sock.write(pkt(1, 3, PW)));
let stage = 'auth';
let acc = Buffer.alloc(0);

sock.on('data', (d) => {
  acc = Buffer.concat([acc, d]);
  while (acc.length >= 4) {
    const len = acc.readInt32LE(0);
    if (acc.length < 4 + len) break;
    const id = acc.readInt32LE(4);
    const body = acc.toString('utf8', 12, 4 + len - 2);
    acc = acc.subarray(4 + len);
    if (stage === 'auth') {
      if (id === -1) { console.error('RCON auth failed'); process.exit(1); }
      stage = 'cmd';
      sock.write(pkt(2, 2, cmd));
    } else {
      if (body) process.stdout.write(body + '\n');
      sock.end();
      process.exit(0);
    }
  }
});
sock.on('error', (e) => { console.error('RCON error:', e.message); process.exit(1); });
setTimeout(() => { console.error('RCON timeout'); process.exit(1); }, 6000);
