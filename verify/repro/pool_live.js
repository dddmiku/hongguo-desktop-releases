const BASE=process.argv[2], KEY=process.argv[3];
const SERIES=process.argv[4];
const H={"x-api-key":KEY,"Origin":"http://tauri.localhost"};
const post=async(ep,extra="")=>{
  const r=await fetch(`${BASE}/desktop/hls?series_id=${SERIES}&ep=${ep}&quality=1080p${extra}`,{method:"POST",headers:H});
  let b=null; try{b=await r.text()}catch{}
  return {s:r.status,b};
};
const status=async(id)=>{const r=await fetch(`${BASE}/desktop/hls/${id}/status`,{headers:H});return r.ok?await r.json():null;};
const del=async(id)=>{await fetch(`${BASE}/desktop/hls/${id}`,{method:"DELETE",headers:H}).catch(()=>{});};
(async()=>{
  const t0=Date.now();
  const rel=()=>((Date.now()-t0)/1000).toFixed(2)+"s";
  // 模拟切集: 连续 5 次「POST 下一集，不释放上一集」—— 正是白屏那一刻的时序
  const ids=[];
  for(let i=0;i<5;i++){
    const r=await post(100+i);
    console.log(`${rel()}  POST ep=${100+i} -> ${r.s} ${r.b?r.b.slice(0,70):""}`);
    if(r.s===200){ try{ids.push(JSON.parse(r.b).id)}catch{} }
    await new Promise(r=>setTimeout(r,120));
  }
  console.log(`\n结果: 5 次连续切集, 成功 ${ids.length} 次, 503 ${5-ids.length} 次`);
  for(const id of ids){
    for(let i=0;i<120;i++){
      await new Promise(r=>setTimeout(r,500));
      const j=await status(id);
      if(j&&j.state!=="preparing"){ console.log(`  ${id.slice(0,8)} -> ${j.state} failed=${j.failed||false} code=${j.failureCode||"-"} dur=${j.duration?j.duration.toFixed(1):"-"}`); break; }
    }
    await del(id);
  }
  process.exit(0);
})();
