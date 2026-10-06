const crypto=require("crypto"),net=require("net");
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
 const ws=await connect(process.argv[2]);let id=0;const pending=new Map();const ev=[];
 const mark=(n)=>ev.push({n,t:Date.now()});
 ws.onMessage((m)=>{try{const j=JSON.parse(m);
  if(j.id&&pending.has(j.id)){pending.get(j.id)(j);pending.delete(j.id);}
  else if(j.method==="Network.requestWillBeSent"){const u=j.params.request.url;
    if(u.includes("/desktop/hls?series_id")&&j.params.request.method==="POST") mark("POST "+decodeURIComponent(u.split("?")[1]));
    if(u.includes("/status")) mark("status poll");
    if(u.includes("init.mp4")) mark("init.mp4");
    if(u.includes("seg000000.m4s")) mark("seg0");
  }
  else if(j.method==="Network.responseReceived"){const u=j.params.response.url;
    if(u.includes("/desktop/hls?series_id")) mark("POST 200");
    if(u.includes("/status")) mark("status 200");
  }
 }catch(e){}});
 const cmd=(method,params={})=>new Promise((res)=>{const i=++id;pending.set(i,res);ws.send(JSON.stringify({id:i,method,params}));});
 await cmd("Network.enable"); await cmd("Runtime.enable");
 await cmd("Runtime.evaluate",{expression:`(()=>{const s=[...document.querySelectorAll('select')].find(x=>x.getAttribute('aria-label')==='清晰度');s.value='540p';s.dispatchEvent(new Event('change',{bubbles:true}));return 'switched 540p';})()`,returnByValue:true});
 const t0=Date.now();
 await new Promise(r=>setTimeout(r,60000));
 const st=await cmd("Runtime.evaluate",{expression:"JSON.stringify((()=>{const v=document.querySelector('video');const s=[...document.querySelectorAll('select')].find(x=>x.getAttribute('aria-label')==='清晰度');return {q:s.value,res:v?v.videoWidth+'x'+v.videoHeight:null,paused:v?v.paused:null};})())",returnByValue:true});
 console.log("FINAL:", st.result.result.value);
 console.log("TIMELINE (ms from switch):");
 for (const e of ev) console.log(`  +${String(e.t-t0).padStart(6)}  ${e.n}`);
 process.exit(0);
})();
