# -*- coding: utf-8 -*-
"""本地维护: 账号同步（验证码登录 / 观看进度 / 收藏）前端补丁（版本无关）。

上游用 esbuild 压缩，变量名每版都变，所以这里同样：
  * 结构固定处用带捕获组的正则回填本次构建的变量名；
  * 每处替换断言命中次数，任何一处不符预期就整体失败。

只做四件事：
  1. 注入账号同步辅助函数（记住本机 API 地址+密钥，调用 /desktop/account/*）
  2. 进度落盘时顺带上报账号（节流）
  3. 收藏切换成功后同步账号
  4. 账号页加一个「验证码登录」面板（扫码登录是上游的，保持不变）
"""
import re

# 辅助函数里要用的 React 变量名由探针回填（默认 P）。
HELPERS = r'''
function hqApiInfo(streamUrl){try{const u=new URL(streamUrl);if(u.hostname!=="127.0.0.1")return null;
const k=u.searchParams.get("api_key")||"";if(!/^[a-f0-9]{64}$/.test(k))return null;return{origin:u.origin,key:k}}catch(e){return null}}
function hqRememberApi(streamUrl){const v=hqApiInfo(streamUrl);if(!v)return;try{localStorage.setItem("guoban:api",JSON.stringify(v))}catch(e){}}
function hqApi(){try{const v=JSON.parse(localStorage.getItem("guoban:api")||"null");
return v&&typeof v.origin==="string"&&/^[a-f0-9]{64}$/.test(v.key||"")?v:null}catch(e){return null}}
function hqAcctCall(path,query,method,streamUrl){const v=hqApiInfo(streamUrl)||hqApi();if(!v)return Promise.resolve(null);
const q=new URLSearchParams(query||{});return fetch(v.origin+path+(q.toString()?"?"+q.toString():""),
{method:method||"POST",headers:{"x-api-key":v.key},credentials:"omit",redirect:"error",keepalive:!0})
.then(function(r){return r.ok?r.json().catch(function(){return null}):null}).catch(function(){return null})}
var hqAcctLast={key:"",at:0};
function hqSyncProgress(streamUrl,seriesId,ep,total,position,duration){
if(!/^\d{8,24}$/.test(String(seriesId||""))||!(Number(ep)>=1))return;
var key=String(seriesId)+":"+String(ep),now=Date.now();
if(hqAcctLast.key===key&&now-hqAcctLast.at<15000)return;
hqAcctLast.key=key;hqAcctLast.at=now;
return hqAcctCall("/desktop/account/progress",{series_id:String(seriesId),ep:String(ep),
total:String(total||0),position:String(Math.max(0,Math.floor(position||0))),
duration:String(Math.max(0,Math.floor(duration||0)))}, "POST",streamUrl)}
function hqSyncFavorite(seriesId,favorite){
if(!/^\d{8,24}$/.test(String(seriesId||"")))return Promise.resolve(null);
return hqAcctCall("/desktop/account/favorite",{series_id:String(seriesId),
favorite:favorite?"true":"false"},"POST",null)}
function hqForgetApi(){try{localStorage.removeItem("guoban:api")}catch(e){}}

// 主动取本机链路密钥，不用先播一集。
// 上游只在播放时把带 api_key 的播放地址交给前端，导致「要看过视频才能登录」。
// 实际上拿任意一部剧问 get_validation_playback 就能得到同样的地址，
// 里面就带着密钥 —— 所以这里自己问一次，避免这个离谱的前置条件。
// 剧号来源优先用应用自己的数据（本机片单 -> 榜单），不写死。
async function hqEnsureApi(){
if(hqApi())return hqApi();
try{
const T=window.__TAURI_INTERNALS__;if(!T||!T.invoke)return null;
let ids=[];
try{const h=await T.invoke("list_history");if(Array.isArray(h))ids=ids.concat(h.map(function(x){return String(x.seriesId)}))}catch(e){}
try{const f=await T.invoke("list_favorites");if(Array.isArray(f))ids=ids.concat(f.map(function(x){return String(x.seriesId)}))}catch(e){}
if(!ids.length){
// 每个 Tauri 命令都有自己的必填键，不能统一传 {kind:"hot"}。
// 之前三个命令的参数全错（CDP 实测报 missing required key rankType /
// tasteTags / genre），只是本机片单非空所以这条兜底从没被执行过。
// get_new_releases 实测对 hot/real 都返回「不支持的新剧频道」，故不用它。
for(const c of ([[ "get_rank",{rankType:"hot",refresh:!1,page:1}],
                 [ "get_recommendations",{tasteTags:[]}]])){
if(ids.length)break;
try{
const r=await T.invoke(c[0],c[1]);
const arr=r&&(r.items||r);if(Array.isArray(arr))ids=ids.concat(arr.map(function(x){return String(x&&x.seriesId||"")}))}
catch(e){}}
}
for(const id of ids){
if(!/^\d{8,24}$/.test(String(id||"")))continue;
try{
const p=await T.invoke("get_validation_playback",{seriesId:String(id),episode:1});
const u=p&&p.streamUrl;if(!u)continue;
const v=hqApiInfo(u);if(v){hqRememberApi(u);return v}
}catch(e){}}
}catch(e){}
return null}

// 登录态展示缓存。
// 本机链路密钥每次启动都会换（端口也换），要播一集才能拿到新的；
// 在那之前先把「上次确认过的登录状态」显示出来，避免重开就显示未登录。
function hqSaveStatus(s){try{if(s&&s.loggedIn)localStorage.setItem("guoban:acct",
JSON.stringify({userName:s.userName||"",uid:s.uid||"",at:Date.now()}))}catch(e){}}
function hqDropStatus(){try{localStorage.removeItem("guoban:acct")}catch(e){}}
function hqCachedStatus(){try{const v=JSON.parse(localStorage.getItem("guoban:acct")||"null");
return v&&v.userName?{loggedIn:!0,userName:v.userName,uid:v.uid||"",cached:!0}:{loggedIn:!1}}catch(e){return{loggedIn:!1}}}

// 账号隔离：缓存必须绑定账号身份。
// 之前用户名和「从手机合并进来的历史/收藏」都没有账号标记，
// 换号后会把上一个号的名字和记录继续显示出来（甚至可能同步过去）。
// 这里在拿到真实账号状态时对比 uid，一旦变化就清掉上一个号的痕迹。
//
// 两个之前写错的地方（2026-10-07 复核发现）：
//   1) 原来靠 `!x.fromPhone` 判断「这条是别的号带来的」——但 fromPhone 只表示
//      「来源是手机端」，不含账号身份；而且 `if(...&&prev)` 让「首次拿到 uid」
//      时整段清理被跳过。
//   2) 只改 React state，__hqLib 里的快照没跟着变，下一次合并又拿旧快照算。
// 现在改成条目级标记 fromUid：只有「不是本机原有、且 fromUid 与当前 uid 不符」
// 的条目才丢，并且同时更新 __hqLib 与 localStorage 的缓存身份。
function hqCachedUid(){try{return localStorage.getItem("guoban:acctUid")||""}catch(e){return ""}}
function hqOnAccount(s){
if(!s||!s.loggedIn)return;
const uid=String(s.uid||"");const prev=hqCachedUid();
if(uid&&uid!==prev){
try{localStorage.setItem("guoban:acctUid",uid)}catch(e){}
const w=window.__hqLib;
if(w&&w.setHist&&w.setFav){
// 只丢「上一个账号从手机端带进来的」。
// 判据必须用显式的 fromPhone，而不是「有没有 fromUid」：
// 从 sqlite 重新载入的条目可能缺 fromUid（旧版本落盘时没写），
// 用 !fromUid 判会把它误当「本机原有」而永久保留。
const drop=function(list){return (list||[]).filter(function(x){
if(!x||x.fromPhone!==!0)return !0;    // 本机原有的，保留
return String(x.fromUid||"")===uid})}; // 手机端来的，只留当前号的
w.setHist(function(list){const n=drop(list);w.hist=n;return n});
w.setFav(function(list){const n=drop(list);w.fav=n;return n});
hqMergedOnce=!1;
}
}
hqSaveStatus(s)}
var hqNeedPlayHint="请先播放任意一集，再回到这里登录（本机服务凭据只在播放时下发）。";
// 本机服务地址每次启动都会变，所以端口以 get_validation_status 的实时值为准；
// 密钥由后端在播放链接里下发，播放一次即可拿到。
async function hqApiAlive(){const v=hqApi();if(!v)return!1;
try{const r=await fetch(v.origin+"/desktop/account/status",
{method:"GET",headers:{"x-api-key":v.key},credentials:"omit",redirect:"error",
signal:AbortSignal.timeout(4000)});return r.ok}catch(e){return!1}}

// 手机端历史/收藏：拉回来后并入本机片单。
// 上游的「观看历史」页只读本机 localStorage，手机端记录一直没被读取，
// 所以这里主动拉 /desktop/account/remote 并合并（按 seriesId 去重）。
//
// 两个必须注意的点（2026-10-07 实测）：
//   1) 手机端历史有几百条，一次性塞进去会把界面压垮 —— 所以要分批追加。
//   2) 服务端返回的顺序不一定是最新的在前 —— 必须自己按 updatedAt 排，
//      否则截断后留下的反而是旧记录。
// 手机端历史有几百条（实测 574 条），一次性全塞进界面会把界面压垮
// （2026-10-07 用户实测过一次崩溃）。
// 所以每轮刷新只并进最新的一批（按 updatedAt 排序取前 HQ_PHONE_MAX）。
// 已在列表里的会被 seen 过滤掉，因此下一轮刷新自然轮到下一批 ——
// 多刷几次即全量收敛，且每轮界面增量可控。
// 配合 hqPersistMerged 落盘，重启后已合并的部分不会重来。
var HQ_PHONE_MAX=120, HQ_PHONE_STEP=40;
// 本机「手机端来源」条目的总量上限。HQ_PHONE_MAX 只管单次并入多少，
// 不封顶的话反复刷新会把整份云端历史搬进本机。
var HQ_PHONE_TOTAL=300;
function hqRemoteLibrary(){
return hqAcctCall("/desktop/account/remote",{limit:"200"},"GET",null)}

// 合并后的最终片单。
// 不再依赖 React 的 setState 回调去读结果：__hqLib.hist 是上游加载时的
// 合并前快照，拿它做落盘会一条都写不进去（2026-07-07 复核发现）。
// 这里在纯 JS 里算出最终数组，返回给调用方，落盘与 UI 用同一份数据。
async function hqMergeLibrary(localFav,localHist,setFav,setHist){
try{
const r=await hqRemoteLibrary();if(!r)return null;
const uid=hqCachedUid();
const favOut=(localFav||[]).slice();
const histOut=(localHist||[]).slice();
if(r.favoritesOk&&Array.isArray(r.favorites)&&r.favorites.length){
const seen=new Set(favOut.map(function(x){return String(x.seriesId)}));
r.favorites.forEach(function(f){const id=String(f.seriesId||"");
if(id&&!seen.has(id)){seen.add(id);favOut.push({seriesId:id,title:f.title||"",
cover:f.cover||"",tags:[],actors:[],intro:"",episodeCount:Number(f.episodeCount)||0,
hotText:"",fromPhone:!0,fromUid:uid})}});
if(favOut.length!==(localFav||[]).length)setFav(favOut)}
if(r.historyOk&&Array.isArray(r.history)&&r.history.length){
const cloud={};
r.history.forEach(function(h){if(h&&h.seriesId)cloud[String(h.seriesId)]=h});
// 本机已有的条目：手机端进度更靠前时把本机那条「就地抬高」。
// 之前只做「手机端有、本机没有」的追加，所以「手机看到 520 集、PC 本地 500 集」
// 合并完还是显示 500 —— 用户明确要求取更靠前的那一个。
let raised=0;
for(let i=0;i<histOut.length;i++){
const x=histOut[i];const c=cloud[String(x.seriesId)];
if(!c)continue;
const cep=Number(c.episode)||0,lep=Number(x.lastEpisode)||0;
if(cep>lep){histOut[i]=Object.assign({},x,{lastEpisode:cep,
episodeCount:Number(c.total)||x.episodeCount||0});raised++}}
if(raised)setHist(histOut.slice());
// 「取最新的一批」：先按 seen 过滤掉已有的，再排序截断。
// seen 用合并后的 histOut（含本机 + 刚抬高的），否则下一轮会把同一条又拉一遍。
//
// 总量封顶：HQ_PHONE_MAX 只限制「单次」，而合并会被反复触发
// （进历史页 / 切回前台 / 账号页连通），每轮再拉 120 条新的，
// 最终会把整份云端历史（实测 574 条）全搬进本机。
// 这里按「本机手机端来源条目总数」封顶，避免把片单撑爆。
const phoneCount=histOut.filter(function(x){return x&&x.fromPhone===!0}).length;
const room=Math.max(0,HQ_PHONE_TOTAL-phoneCount);
if(room<=0)return {fav:favOut,hist:histOut};
const seen=new Set(histOut.map(function(x){return String(x.seriesId)}));
const extra=r.history.filter(function(h){return h&&h.seriesId&&!seen.has(String(h.seriesId))})
.sort(function(a,b){return (Number(b.updatedAt)||0)-(Number(a.updatedAt)||0)})
.slice(0,Math.min(HQ_PHONE_MAX,room))
.map(function(h){return {seriesId:String(h.seriesId),title:h.title||"",cover:h.cover||"",
tags:[],actors:[],intro:"",hotText:"",
lastEpisode:Number(h.episode)||1,episodeCount:Number(h.total)||0,
fromPhone:!0,fromUid:uid}});
// 分批追加：每批 HQ_PHONE_STEP 条，让界面能喘口气。
for(let i=0;i<extra.length;i+=HQ_PHONE_STEP){
const part=extra.slice(i,i+HQ_PHONE_STEP);
for(const x of part)histOut.push(x);
setHist(histOut.slice())}
}
return {fav:favOut,hist:histOut};
}catch(e){return null}}

// 把合并结果写回本机片单（sqlite）。
// 之前合并只改 React 内存状态：窗口刷新/重开就回到本地那份，
// 手机端历史又得重新拉一遍，而且用户看不到「已合并」的稳定结果。
// 传进来的 merged 是 hqMergeLibrary 返回的最终数组（不是合并前快照）。
var hqPersisting=!1;
async function hqPersistMerged(merged){
const T=window.__TAURI_INTERNALS__;if(!T||!T.invoke)return 0;
if(hqPersisting)return 0;hqPersisting=!0;
try{
let local=[];
try{const h=await T.invoke("list_history");if(Array.isArray(h))local=h}catch(e){}
const have={};
local.forEach(function(x){have[String(x.seriesId)]=x});
const cur=(merged&&merged.hist)||[];
let wrote=0;
for(const x of cur){
const id=String(x.seriesId||"");
if(!id||!/^\d{8,24}$/.test(id))continue;
const cur_ep=Number(x.lastEpisode)||1;
const old=have[id];
// 已存在的条目：只有进度更靠前时才回写。
// 之前一律 continue，导致「手机端更靠前」的抬升只活在内存里，
// 重启就回退成 sqlite 里的旧值。
if(old){
if(cur_ep<=(Number(old.lastEpisode)||0))continue;
try{
await T.invoke("add_history",{series:{seriesId:id,title:x.title||old.title||"",
cover:x.cover||old.cover||"",intro:typeof x.intro==="string"?x.intro:(old.intro||""),
tags:Array.isArray(x.tags)?x.tags:(old.tags||[]),
actors:Array.isArray(x.actors)?x.actors:(old.actors||[]),
episodeCount:Number(x.episodeCount)||Number(old.episodeCount)||0},
lastEpisode:cur_ep});
wrote++}catch(e){}
continue}
try{
// 落盘时带上来源账号。丢掉它会让 hqOnAccount 的换号清理认不出这些条目
// （它靠 fromUid 区分「本机原有」与「手机端带进来的」），
// 重启后上个号的历史就永久留在本机了。
await T.invoke("add_history",{series:{seriesId:id,title:x.title||"",
cover:x.cover||"",intro:typeof x.intro==="string"?x.intro:"",
tags:Array.isArray(x.tags)?x.tags:[],actors:Array.isArray(x.actors)?x.actors:[],
episodeCount:Number(x.episodeCount)||0,
fromUid:x.fromUid||"",fromPhone:!!x.fromPhone},
lastEpisode:cur_ep});
have[id]={seriesId:id,lastEpisode:cur_ep};wrote++}catch(e){}}
return wrote;
}catch(e){return 0}finally{hqPersisting=!1}}

// 合并入口。
// 之前只在「首次加载」跑一次（hqMergedOnce 标志），
// 导致看完一集再进历史页看到的还是旧记录，必须手动点「历史」才刷新。
// 现在改成可重复调用：进历史页 / 切回前台 / 账号页确认连通后都会拉一次。
// 播放器关闭 / 切集时调一次缓存回收。
// 原先只有「看完一集（onEnded）」才清缓存，中途关播放器、快速切集、
// 直接关软件这三条路径都不清，缓存就一路涨（用户明确反馈过）。
// 这里在关闭播放器时兜一次：按数量上限回收 + 清过期 .partial。
function hqPruneCache(streamUrl){
const v=hqApiInfo(streamUrl)||hqApi();if(!v)return Promise.resolve(null);
return fetch(v.origin+"/desktop/cache/prune",{method:"POST",
headers:{"x-api-key":v.key},credentials:"omit",redirect:"error",keepalive:!0})
.then(function(r){return r.ok?r.json().catch(function(){return null}):null})
.catch(function(){return null})}

var hqMergedOnce=!1;
function hqMergeOnce(){if(hqMergedOnce)return;const w=window.__hqLib;
if(!w||!w.setFav||!w.setHist)return;hqMergedOnce=!0;
return hqMergeLibrary(w.fav||[],w.hist||[],w.setFav,w.setHist).then(function(m){
void hqPersistMerged(m)})}
// 强制刷新（忽略一次性标志），用于进入历史页 / 切回前台。
var hqRefreshing=!1;
async function hqRefreshLibrary(){
const w=window.__hqLib;if(!w||!w.setFav||!w.setHist)return;
if(hqRefreshing)return;hqRefreshing=!0;
try{
if(!hqApi())await hqEnsureApi();
// 凭据失效时（应用重启后端口/密钥会换）必须清掉，否则这里会永远
// 直接 return，刷新路径永久空转 —— 账号面板那条路径就是这么做的，
// 这里之前漏了，所以「切回前台自动刷新」实际不生效。
if(!(await hqApiAlive())){hqForgetApi();
if(!(await hqEnsureApi()))return;
if(!(await hqApiAlive()))return}
const merged=await hqMergeLibrary(w.fav||[],w.hist||[],w.setFav,w.setHist);
hqMergedOnce=!0;
void hqPersistMerged(merged);
}catch(e){}finally{hqRefreshing=!1}}
// 进入历史页时刷新一次（切页由下面的 hook 触发）。
// WebView2 里 window 的 focus 事件不可靠（窗口最小化/还原、点回窗口都不一定发），
// 所以再挂 Tauri 自己的窗口事件做兜底。实测运行中的页面支持
// __TAURI_INTERNALS__.invoke("plugin:event|listen", ...)（返回了 listener id）。
// 两条路都只是「触发一次刷新」，重复触发无副作用（hqRefreshing 已做去重）。
if(!window.__hqRefreshHooked){
window.__hqRefreshHooked=!0;
var hqOnWake=function(){void hqRefreshLibrary()};
window.addEventListener("focus",hqOnWake);
document.addEventListener("visibilitychange",function(){
if(!document.hidden)void hqRefreshLibrary()});
try{
var hqT=window.__TAURI_INTERNALS__;
if(hqT&&hqT.transformCallback&&hqT.invoke){
var hqCb=hqT.transformCallback(function(){hqOnWake();return 1});
hqT.invoke("plugin:event|listen",{event:"tauri://focus",target:{kind:"Any"},handler:hqCb}).catch(function(){});
}
}catch(e){}
}
'''

PANEL = r"""
function hqAccountPanel(){const[n,a]=REACT.useState(null),[o,c]=REACT.useState(""),
[d,f]=REACT.useState(""),[g,m]=REACT.useState(!1),[p,v]=REACT.useState(""),[E,T]=REACT.useState(""),
[hqCd,hqSetCd]=REACT.useState(0),[hqTryAt,hqSetTryAt]=REACT.useState(0);
// 登录尝试节流：连续失败会被服务端升级成「为保证账号安全，暂不支持此操作」(2046)。
// 之前登录按钮没有任何节流，连点就会触发，所以这里失败后强制冷却。
// 注意：必须并进上面那条 const 声明（分号隔开会变成给未声明变量赋值，直接白屏）。
REACT.useEffect(()=>{let x=!0,stop=!1;
async function load(){
if(!hqApi())await hqEnsureApi();
const alive=await hqApiAlive();
if(!alive){hqForgetApi();if(x){a(hqCachedStatus());v(hqNeedPlayHint),T("")}return!1}
const A=await hqAcctCall("/desktop/account/status",null,"GET",null);
if(x&&A){a(A);hqOnAccount(A);v(""),T("");hqMergeOnce()}return!0}
(async()=>{
if(await load())return;
// 凭据只在播放时下发。这里自动等：窗口重新获得焦点或每 3 秒重试一次，
// 用户去播一集再回来就会自动接上，不用手动刷新。
const tick=async()=>{if(stop||!x)return;if(await load()){stop=!0;return}
window.setTimeout(tick,3000)};
const onFocus=()=>{stop||void load()};
window.addEventListener("focus",onFocus);
window.addEventListener("visibilitychange",onFocus);
window.setTimeout(tick,3000);
return()=>{stop=!0;window.removeEventListener("focus",onFocus);
window.removeEventListener("visibilitychange",onFocus)}})();
return()=>{x=!1;stop=!0}},[]);
REACT.useEffect(()=>{if(hqCd<=0)return;const x=window.setTimeout(()=>hqSetCd(A=>A-1),1000);return()=>window.clearTimeout(x)},[hqCd]);
REACT.useEffect(()=>{if(hqTryAt<=0)return;const x=window.setTimeout(()=>hqSetTryAt(A=>A-1),1000);return()=>window.clearTimeout(x)},[hqTryAt]);
function hqErr(e){return e&&e.message?String(e.message).slice(0,200):"请求失败"}
// 把服务端错误码翻译成人话，避免用户反复重试把风控点着。
function hqHint(msg){const s=String(msg||"");
if(s.indexOf("2046")>=0||s.indexOf("为保证账号安全")>=0)
return "服务端已暂时限制本机登录（多次失败后触发）。请等 10-30 分钟再试，期间不要重复提交。";
if(s.indexOf("访问太频繁")>=0||s.indexOf("系统繁忙")>=0)
return s+"（已自动冷却 30 秒，请稍后再试）";
if(s.indexOf("验证码")>=0)return s+"（请确认验证码是否正确、是否已过期）";
return s}
async function hqPost(path,q){const v=hqApi();if(!v)throw new Error(hqNeedPlayHint);
const u=new URL(v.origin+path);Object.entries(q).forEach(([k,x])=>u.searchParams.set(k,x));
let r;try{
r=await fetch(u.toString(),{method:"POST",headers:{"x-api-key":v.key},credentials:"omit",redirect:"error"})
}catch(e){
// 连不上 = 应用重启后端口/密钥已换。清掉失效凭据，让提示可操作。
hqForgetApi();throw new Error(hqNeedPlayHint)}
if(!r.ok){let d="";try{d=(await r.json()).detail||""}catch(x){}
if(r.status===401||r.status===403)hqForgetApi();
throw new Error(d||("HTTP "+r.status))}
return r.json().catch(()=>null)}
async function L(){m(!0),v("");try{const x=await hqPost("/desktop/account/send_code",{mobile:o});
hqSetCd(Number(x&&x.retryTime)>0?Number(x.retryTime):60);
v("验证码已发送，请查看手机短信。"),T("")}catch(e){v("发送失败："+hqErr(e)),T("error")}finally{m(!1)}}
async function R(){m(!0),v("");try{const x=await hqPost("/desktop/account/login",{mobile:o,code:d});
if(x&&x.loggedIn){a(x),hqSaveStatus(x),f(""),hqSetTryAt(0),v("登录成功，观看进度与收藏会同步到手机。"),T("")}
else{v("登录失败：响应异常"),T("error")}}
catch(e){const raw=hqErr(e);hqSetTryAt(120);v("登录失败："+hqHint(raw)),T("error")}
finally{m(!1)}}
async function D(){m(!0),v("");try{const x=await hqAcctCall("/desktop/account/logout",null,"POST",null);
a(x||{loggedIn:!1}),hqDropStatus(),v("已退出账号同步。"),T("")}finally{m(!1)}}
async function RS(){m(!0),v("");try{const x=await hqAcctCall("/desktop/account/restore",null,"POST",null);
if(x&&x.loggedIn){a(x),hqSaveStatus(x),v("已恢复上次登录。"),T("")}else{v("恢复失败：没有可用的登录态"),T("error")}}catch(e){v("恢复失败："+hqErr(e)),T("error")}finally{m(!1)}}
const A=!!(n&&n.loggedIn);
return JSX.jsxs("section",{className:"content account-page",children:[
JSX.jsxs("div",{className:"headline-row",children:[JSX.jsxs("div",{children:[
JSX.jsx("span",{className:"eyebrow",children:"红果账号同步"}),JSX.jsx("h1",{children:"验证码登录"})]}),
A?JSX.jsx("button",{className:"secondary",onClick:()=>void D(),disabled:g,children:"退出登录"}):
(n&&n.restorable?JSX.jsx("button",{className:"secondary",onClick:()=>void RS(),disabled:g,
children:g?"处理中…":"恢复上次登录"}):null)]}),
JSX.jsxs("div",{className:"account-status-card "+(A?"online":""),role:"status",children:[
JSX.jsxs("div",{children:[JSX.jsx("strong",{children:A?("已登录 · "+(n.userName||"红果账号")):"未登录"}),
JSX.jsx("p",{children:A?"桌面端看完的集数与收藏会同步到手机红果的历史/收藏里。"
:"登录后，桌面端看的进度与收藏会同步到手机；不登录也能正常看剧。"})]})]}),
A?null:JSX.jsxs("div",{className:"hq-acct-card",children:[
JSX.jsxs("div",{children:[JSX.jsx("h2",{children:"用手机号登录"}),
JSX.jsx("p",{className:"hq-acct-intro",children:"验证码由红果下发；桌面端只保存登录态，不上传任何账号密码。"})]}),
JSX.jsxs("label",{className:"hq-acct-row",children:[JSX.jsx("span",{children:"手机号"}),
JSX.jsx("input",{type:"tel",inputMode:"numeric",autoComplete:"off",value:o,placeholder:"11 位手机号",
onChange:x=>c(x.target.value.replace(/\D/g,"").slice(0,11))})]}),
JSX.jsxs("label",{className:"hq-acct-row",children:[JSX.jsx("span",{children:"验证码"}),
JSX.jsx("input",{type:"tel",inputMode:"numeric",autoComplete:"off",value:d,placeholder:"短信验证码",
onChange:x=>f(x.target.value.replace(/\D/g,"").slice(0,8))})]}),
JSX.jsxs("div",{className:"hq-acct-actions",children:[
JSX.jsx("button",{className:"secondary",onClick:()=>void L(),
disabled:g||o.length!==11||hqCd>0,children:hqCd>0?("重新发送（"+hqCd+"s）"):"发送验证码"}),
// 冷却必须真的禁用按钮。之前 hqTryAt 只写进了按钮文案，
// disabled 里没有它 —— 连点就会连续失败，把风控从「访问太频繁」
// 升级到「为保证账号安全，暂不支持此操作」(2046)。
// 实测 account-log.jsonl：39 次失败里有 6 次相邻间隔 < 10 秒。
JSX.jsx("button",{className:"primary",onClick:()=>void R(),
disabled:g||o.length!==11||d.length<4||hqTryAt>0,
children:g?"处理中…":(hqTryAt>0?("请稍候（"+hqTryAt+"s）"):"登录")})]})]}),
p?JSX.jsx("p",{className:"hq-acct-note"+(E?" error":""),role:"status",children:p}):null]})}
"""

# 专属样式：上游的 account-login-card 是「图标|文案|按钮」三列网格，
# settings-row 的 input 又是给 18px 复选框用的，套过来会被挤成窄条。
# 所以这里用自己的类名，只借用上游的设计变量。
CSS = """
.hq-acct-card{display:block;max-width:760px;padding:24px 26px;border:1px solid var(--line);
border-radius:19px;background:var(--paper);box-shadow:0 10px 34px #412b210b}
.hq-acct-card h2{margin:0 0 6px;font-size:20px;letter-spacing:-.025em}
.hq-acct-intro{margin:0 0 18px;color:var(--muted);font-size:13px;line-height:1.6}
.hq-acct-row{display:grid;grid-template-columns:88px minmax(0,1fr);align-items:center;
gap:16px;min-height:64px;border-bottom:1px solid var(--line);font-size:14px}
.hq-acct-row>span{color:var(--ink)}
.hq-acct-row input{width:100%;min-width:0;box-sizing:border-box;height:40px;padding:0 12px;
font-size:14px;color:var(--ink);background:#fff;border:1px solid var(--line);
border-radius:10px;outline:none;appearance:none}
.hq-acct-row input::placeholder{color:var(--muted)}
.hq-acct-row input:focus{border-color:var(--coral)}
.hq-acct-actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px;margin-top:20px}
.hq-acct-note{margin:16px 0 0;font-size:13px;line-height:1.6;color:var(--muted)}
.hq-acct-note.error{color:#c0392b}
"""


def patch_css(text):
    """把专属样式追加到 app.css（幂等）。"""
    if ".hq-acct-card" in text:
        return text, ["[=] 账号样式已存在，跳过"]
    return text.rstrip("\n") + "\n" + CSS.strip() + "\n", ["OK   追加账号面板样式"]

class Fail(SystemExit):
    pass


def _sub_once(text, pattern, repl, label):
    new, n = re.subn(pattern, repl, text)
    if n != 1:
        raise Fail("[FAIL] %s: 命中 %d 次，预期 1 次\n  pattern=%s" % (label, n, pattern))
    return new


def patch(text):
    if "hqAccountPanel" in text:
        return text, ["[=] 账号同步补丁已存在，跳过"]

    log = []
    s = text

    # 探针：本次构建的 jsx 工厂 与 React 命名空间
    m = re.search(r'(\w+)\.jsx\("span",\{className:"player-toolbar-spacer"\}\)', s)
    if not m:
        raise Fail("[FAIL] 未找到 jsx 工厂探针")
    jsx = m.group(1)
    log.append("OK   探针 jsx -> %s" % jsx)

    m = re.search(r'(\w+)\.useState\(!1\),\w+=e\?\.verificationUriComplete', s)
    if not m:
        raise Fail("[FAIL] 未找到 React 命名空间探针")
    react = m.group(1)
    log.append("OK   探针 react -> %s" % react)

    helpers = HELPERS
    panel = PANEL.replace("REACT", react).replace("JSX", jsx)

    # 1) 注入辅助函数（放在既有清晰度辅助函数之前）
    anchor = 'function hqQuals(){return["auto","1080p","720p","540p","480p"]}'
    if s.count(anchor) != 1:
        raise Fail("[FAIL] 清晰度辅助函数锚点命中 %d 次" % s.count(anchor))
    s = s.replace(anchor, helpers + "\n" + anchor, 1)
    log.append("OK   注入账号同步辅助函数")

    # 2) 播放时记住本机 API 地址+密钥
    s = _sub_once(
        s,
        re.escape("const xt=Ot.current;Ot.current=void 0;const ot=qw("),
        "const xt=Ot.current;Ot.current=void 0;hqRememberApi(C.streamUrl);const ot=qw(",
        "播放时记住本机 API")

    # 3) 进度落盘时上报账号（节流 15s/集）
    s = _sub_once(
        s,
        re.escape("localStorage.setItem(um(C,o),String(tt)),rt.current=tt"),
        "localStorage.setItem(um(C,o),String(tt)),rt.current=tt,"
        "hqRememberApi(C.streamUrl),hqSyncProgress(C.streamUrl,C.seriesId,C.episode,Pt,tt,$i.duration)",
        "进度上报账号")

    # 3b) 关闭播放器时兜一次缓存回收（覆盖「没看完就退出」）
    s = _sub_once(
        s,
        re.escape('qi(),di(),ye.current+=1,ci({kind:"playerClosePause"})'),
        'qi(),void hqPruneCache(C.streamUrl),di(),ye.current+=1,'
        'ci({kind:"playerClosePause"})',
        "关闭播放器回收缓存")

    # 4) 收藏切换成功后同步账号
    s = _sub_once(
        s,
        re.escape('he.isDesktop()&&await Ws(ni.current,Yt.current),Gi(me?"已加入收藏"'),
        'he.isDesktop()&&await Ws(ni.current,Yt.current),hqSyncFavorite(j.seriesId,me),'
        'Gi(me?"已加入收藏"',
        "收藏同步账号")

    # 5) 账号页追加验证码登录面板（保留上游扫码面板）
    s = _sub_once(
        s,
        re.escape('a==="account"?b.jsx(aM,{status:Ti,qrLogin:on,busy:Cs,message:Dn,onRefresh:yr,'
                  'onStartLogin:()=>void uo(),onLogout:()=>void co()}):'),
        # 上游那个账号面板是「接入你们自己的 OAuth 设备码服务」的占位实现，
        # 未配置时整页都在提示 HONGGUO_DESKTOP_AUTH_* 环境变量，对红果账号没有任何用处。
        # 这里直接换成我们自己的验证码登录面板。
        'a==="account"?b.jsx(hqAccountPanel,{}):',
        "账号页换成验证码面板")

    # 6) 侧栏「账号」入口：始终可见。
    # 之前依赖「已配置 or 有本机凭据」，而凭据会被清掉（应用重启后失效），
    # 于是入口整个消失、用户连面板都进不去。这里改为常驻。
    s = _sub_once(
        s,
        re.escape('Ti?.configured&&b.jsx(Na,{active:a==="account"'),
        '(true)&&b.jsx(Na,{active:a==="account"',
        "侧栏账号入口常驻")

    # 7) 面板组件本体（放在账号面板组件之前）
    m = re.search(r'function aM\(\{status:r,qrLogin:e,busy:t,message:i,onRefresh:n,onStartLogin:a,onLogout:o\}\)\{',
                  s)
    if not m:
        raise Fail("[FAIL] 未找到账号面板组件")
    s = s[:m.start()] + panel + "\n" + s[m.start():]
    log.append("OK   注入验证码登录面板")

    # 8) 手机端历史/收藏并入本机片单
    # 上游那行是「加载完本地片单后 setFavorites/setHistory/setReady/setError」，
    # 变量名每次都变，所以用探针抓出「setter(值)」这四对，再在末尾插合并调用。
    s = _sub_once(
        s,
        r'([\w$]+)\((\w+)\),([\w$]+)\((\w+)\),([\w$]+)\(!0\),([\w$]+)\(""\),!0\)\}catch\(',
        r'\g<1>(\g<2>),\g<3>(\g<4>),\g<5>(!0),\g<6>(""),'
        # 把片单的 setter 挂到 window，供账号页在拿到凭据后重新触发合并。
        r'window.__hqLib={fav:\g<2>,hist:\g<4>,setFav:\g<1>,setHist:\g<3>},'
        r'hqMergeLibrary(\g<2>,\g<4>,\g<1>,\g<3>),!0)}catch(',
        "手机端历史/收藏合并")

    # 9) 切到「历史」页时自动刷新
    # 上游侧栏的历史入口是 onClick:()=>o("history")，变量名会变，所以用探针。
    s = _sub_once(
        s,
        r'onClick:\(\)=>([\w$]+)\("history"\)',
        r'onClick:()=>{\g<1>("history");void hqRefreshLibrary()}',
        "历史页自动刷新")

    return s, log
