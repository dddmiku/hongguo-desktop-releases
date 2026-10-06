const crypto=require("crypto"),net=require("net"),fs=require("fs");
function connect(url){return new Promise((resolve,reject)=>{const u=new URL(url);const key=crypto.randomBytes(16).toString("base64");
const sock=net.connect(Number(u.port),u.hostname,()=>{sock.write(`GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);});
let buf=Buffer.alloc(0),hs=false;const handlers=[];let acc=Buffer.alloc(0);
sock.on("data",(d)=>{buf=Buffer.concat([buf,d]);
 if(!hs){const i=buf.indexOf("\r\n\r\n");if(i<0)return;hs=true;buf=buf.subarray(i+4);resolve({sock,send,onMessage:(f)=>handlers.push(f)});}
 while(hs&&buf.length>=2){const b0=buf[0],b1=buf[1];let len=b1&0x7f,off=2;
  if(len===126){if(buf.length<4)return;len=buf.readUInt16BE(2);off=4;}else if(len===127){if(buf.length<10)return;len=Number(buf.readBigUInt64BE(2));off=10;}
  if(buf.length<off+len)return;const payload=buf.subarray(off,off+len);buf=buf.subarray(off+len);
  const op=b0&0x0f; if(op===0x0)acc=Buffer.concat([acc,payload]); else if(op===1||op===2)acc=Buffer.from(payload); else if(op===8){sock.end();return;} else continue;
  if((b0&0x80)!==0){const s=acc.toString("utf8");acc=Buffer.alloc(0);for(const h of handlers)h(s);}}});sock.on("error",reject);
 function send(str){const p=Buffer.from(str,"utf8");const mask=crypto.randomBytes(4);let header;
  if(p.length<126){header=Buffer.alloc(6);header[0]=0x81;header[1]=0x80|p.length;mask.copy(header,2);}
  else if(p.length<65536){header=Buffer.alloc(8);header[0]=0x81;header[1]=0x80|126;header.writeUInt16BE(p.length,2);mask.copy(header,4);}
  else{header=Buffer.alloc(14);header[0]=0x81;header[1]=0x80|127;header.writeBigUInt64BE(BigInt(p.length),2);mask.copy(header,10);}
  const m=Buffer.from(p);for(let i=0;i<m.length;i++)m[i]^=mask[i%4];sock.write(Buffer.concat([header,m]));}});}
(async()=>{
 const ws=await connect(process.argv[2]);let id=0;const pending=new Map();
 ws.onMessage((m)=>{try{const j=JSON.parse(m);if(j.id&&pending.has(j.id)){pending.get(j.id)(j);pending.delete(j.id);}}catch(e){}});
 const cmd=(method,params={})=>new Promise((res)=>{const i=++id;pending.set(i,res);ws.send(JSON.stringify({id:i,method,params}));});
 const ev=async(e)=>JSON.parse((await cmd("Runtime.evaluate",{expression:e,returnByValue:true,awaitPromise:true})).result.result.value);
 await cmd("Runtime.enable");
 const out={};
 out.qualityOptions = await ev("JSON.stringify([...document.querySelectorAll('select')].find(s=>s.getAttribute('aria-label')==='清晰度').options.length ? [...[...document.querySelectorAll('select')].find(s=>s.getAttribute('aria-label')==='清晰度').options].map(o=>o.value) : null)");
 out.speedOptions   = await ev("JSON.stringify([...document.querySelectorAll('select')].find(s=>s.getAttribute('aria-label')==='播放速度').options.length ? [...[...document.querySelectorAll('select')].find(s=>s.getAttribute('aria-label')==='播放速度').options].map(o=>o.value) : null)");
 // 快捷键
 await ev("document.dispatchEvent(new KeyboardEvent('keydown',{key:']',bubbles:true,cancelable:true}))");
 await new Promise(r=>setTimeout(r,1500));
 out.speedAfterBracket = await ev("JSON.stringify({rate:document.querySelector('video').playbackRate, sel:[...document.querySelectorAll('select')].find(s=>s.getAttribute('aria-label')==='播放速度').value})");
 await ev("document.dispatchEvent(new KeyboardEvent('keydown',{key:'[',bubbles:true,cancelable:true}))");
 await new Promise(r=>setTimeout(r,1500));
 out.speedBack = await ev("JSON.stringify({rate:document.querySelector('video').playbackRate})");
 // 跳转
 const before = await ev("document.querySelector('video').currentTime");
 await ev("document.dispatchEvent(new KeyboardEvent('keydown',{key:'>',bubbles:true,cancelable:true}))");
 await new Promise(r=>setTimeout(r,800));
 const after = await ev("document.querySelector('video').currentTime");
 out.seek10 = {before:Math.round(before), after:Math.round(after), delta:Math.round(after-before)};
 // 循环 / 静音
 await ev("document.dispatchEvent(new KeyboardEvent('keydown',{key:'s',bubbles:true,cancelable:true}))");
 await new Promise(r=>setTimeout(r,500));
 out.loop = await ev("document.querySelector('video').loop");
 await ev("document.dispatchEvent(new KeyboardEvent('keydown',{key:'s',bubbles:true,cancelable:true}))");
 console.log(JSON.stringify(out,null,1));
 process.exit(0);
})();
