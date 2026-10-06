// 在一条新集上验证 > / PageDown / Home / End 的跳转
const crypto = require("crypto"), net = require("net");
function connect(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url), key = crypto.randomBytes(16).toString("base64");
    const sock = net.connect(Number(u.port), u.hostname, () => {
      sock.write(`GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
    });
    let buf = Buffer.alloc(0), hs = false, acc = Buffer.alloc(0);
    const handlers = [];
    sock.on("data", (d) => {
      buf = Buffer.concat([buf, d]);
      if (!hs) { const i = buf.indexOf("\r\n\r\n"); if (i < 0) return; hs = true; buf = buf.subarray(i + 4); resolve({ sock, send, onMessage: (f) => handlers.push(f) }); }
      while (hs && buf.length >= 2) {
        const b0 = buf[0], b1 = buf[1]; let len = b1 & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const payload = buf.subarray(off, off + len); buf = buf.subarray(off + len);
        const op = b0 & 0x0f;
        if (op === 0x0) acc = Buffer.concat([acc, payload]);
        else if (op === 1 || op === 2) acc = Buffer.from(payload);
        else if (op === 8) { sock.end(); return; } else continue;
        if ((b0 & 0x80) !== 0) { const s = acc.toString("utf8"); acc = Buffer.alloc(0); for (const h of handlers) h(s); }
      }
    });
    sock.on("error", reject);
    function send(str) {
      const p = Buffer.from(str, "utf8"), mask = crypto.randomBytes(4); let header;
      if (p.length < 126) { header = Buffer.alloc(6); header[0] = 0x81; header[1] = 0x80 | p.length; mask.copy(header, 2); }
      else if (p.length < 65536) { header = Buffer.alloc(8); header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(p.length, 2); mask.copy(header, 4); }
      else { header = Buffer.alloc(14); header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(p.length), 2); mask.copy(header, 10); }
      const m = Buffer.from(p); for (let i = 0; i < m.length; i++) m[i] ^= mask[i % 4];
      sock.write(Buffer.concat([header, m]));
    }
  });
}
const KEYS = {
  ">":        { code: "Period", key: ">", vk: 190 },
  "PageDown": { code: "PageDown", key: "PageDown", vk: 34 },
  "Home":     { code: "Home", key: "Home", vk: 36 },
  "End":      { code: "End", key: "End", vk: 35 },
};
(async () => {
  const ws = await connect(process.argv[2]);
  let id = 0; const pending = new Map();
  ws.onMessage((m) => { try { const j = JSON.parse(m); if (j.id && pending.has(j.id)) { pending.get(j.id)(j); pending.delete(j.id); } } catch (e) {} });
  const cmd = (m, p = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method: m, params: p })); });
  const ev = async (e) => { const r = await cmd("Runtime.evaluate", { expression: e, returnByValue: true, awaitPromise: true }); return r.result && r.result.result && r.result.result.value; };
  await cmd("Runtime.enable");
  async function press(n) {
    const k = KEYS[n];
    const b = { key: k.key, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk };
    await cmd("Input.dispatchKeyEvent", { type: "keyDown", ...b });
    await cmd("Input.dispatchKeyEvent", { type: "keyUp", ...b });
    await new Promise((r) => setTimeout(r, 1200));
  }
  const st = () => ev('JSON.stringify({ep:(document.body.innerText.match(/第 (\\d+)\\/\\d+ 集/)||[null,null])[1],ct:(()=>{const v=document.querySelector("video");return v?+v.currentTime.toFixed(1):null})(),dur:(()=>{const v=document.querySelector("video");return v&&isFinite(v.duration)?+v.duration.toFixed(1):null})()})');
  // 先把倍速调回 1x，避免测跳转时又播完
  await ev('(()=>{const s=document.querySelector("select[aria-label=\\"播放速度\\"]");s.value="1";s.dispatchEvent(new Event("change",{bubbles:true}));const v=document.querySelector("video");v.playbackRate=1;return 1})()');
  console.log("起始 :", await st());
  await press(">");
  console.log(">    :", await st(), "(预期 +10s)");
  await press("PageDown");
  console.log("PgDn :", await st(), "(预期 +60s)");
  await press("Home");
  console.log("Home :", await st(), "(预期 0s)");
  await press("End");
  console.log("End  :", await st(), "(预期接近片长)");
  process.exit(0);
})();
