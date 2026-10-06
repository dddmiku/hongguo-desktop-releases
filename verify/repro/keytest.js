// 真实按键事件回归: 空格 / [ ] / 方向键 / S 循环
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
  " ":      { code: "Space",     key: " ",         vk: 32 },
  "[":      { code: "BracketLeft", key: "[",       vk: 219 },
  "]":      { code: "BracketRight", key: "]",      vk: 221 },
  ">":      { code: "Period",    key: ">",         vk: 190 },
  "PageDown": { code: "PageDown", key: "PageDown", vk: 34 },
  "s":      { code: "KeyS",      key: "s",         vk: 83 },
};

(async () => {
  const ws = await connect(process.argv[2]);
  let id = 0; const pending = new Map();
  ws.onMessage((m) => { try { const j = JSON.parse(m); if (j.id && pending.has(j.id)) { pending.get(j.id)(j); pending.delete(j.id); } } catch (e) {} });
  const cmd = (method, params = {}) => new Promise((res) => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (e) => { const r = await cmd("Runtime.evaluate", { expression: e, returnByValue: true, awaitPromise: true }); return r.result && r.result.result && r.result.result.value; };
  await cmd("Runtime.enable");

  async function press(name) {
    const k = KEYS[name];
    const base = { key: k.key, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk };
    await cmd("Input.dispatchKeyEvent", { type: "keyDown", ...base });
    await cmd("Input.dispatchKeyEvent", { type: "keyUp", ...base });
    await new Promise((r) => setTimeout(r, 900));
  }
  const state = () => ev('JSON.stringify({speed:document.querySelector("select[aria-label=\\"播放速度\\"]")?.value,rate:(()=>{const v=document.querySelector("video");return v?v.playbackRate:null})(),paused:(()=>{const v=document.querySelector("video");return v?v.paused:null})(),loop:(()=>{const v=document.querySelector("video");return v?v.loop:null})(),ct:(()=>{const v=document.querySelector("video");return v?+v.currentTime.toFixed(1):null})()})');

  // 记录按钮点击次数，验证空格不再激活焦点按钮
  await ev('(()=>{window.__clicks=0;document.addEventListener("click",e=>{if(e.target.closest&&e.target.closest("button"))window.__clicks++},true);return 1})()');
  // 先点一下工具栏按钮（让它拿到焦点），再按空格
  await ev('(()=>{const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="静音");if(b)b.focus();return document.activeElement.getAttribute("aria-label")})()');

  console.log("初始 :", await state());
  await press(" ");
  console.log("空格 :", await state(), "按钮点击次数=", await ev("window.__clicks"));
  await press(" ");
  console.log("空格 :", await state(), "按钮点击次数=", await ev("window.__clicks"));
  await press("[");
  console.log("[    :", await state());
  await press("]");
  console.log("]    :", await state());
  await press(">");
  console.log(">    :", await state());
  await press("PageDown");
  console.log("PgDn :", await state());
  await press("s");
  console.log("S    :", await state());
  await press("s");
  console.log("S    :", await state());
  process.exit(0);
})();
