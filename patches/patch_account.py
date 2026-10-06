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
'''

PANEL = r'''
function hqAccountPanel({initialStatus}){const[n,a]=REACT.useState(initialStatus||null),
[o,c]=REACT.useState(""),[d,f]=REACT.useState(""),[g,m]=REACT.useState(!1),[p,v]=REACT.useState(""),[E,T]=REACT.useState("");
REACT.useEffect(()=>{let x=!0;hqAcctCall("/desktop/account/status",null,"GET",null).then(A=>{x&&A&&a(A)});return()=>{x=!1}},[]);
async function L(){if(!hqApi()){v("请先播放任意一集（本机服务地址会随后记录），再回到这里登录。");return}
m(!0),v("");try{const x=await hqAcctCall("/desktop/account/send_code",{mobile:o},"POST",null);
x?v("验证码已发送，请查看手机短信。"):v("验证码发送失败，请检查手机号。")}finally{m(!1)}}
async function R(){if(!hqApi()){v("请先播放任意一集，再回到这里登录。");return}
m(!0),v("");try{const x=await hqAcctCall("/desktop/account/login",{mobile:o,code:d},"POST",null);
if(x&&x.loggedIn){a(x),T(""),v("登录成功，观看进度与收藏会同步到手机。")}else v("登录失败，请检查验证码。")}finally{m(!1)}}
async function D(){m(!0),v("");try{const x=await hqAcctCall("/desktop/account/logout",null,"POST",null);a(x||{loggedIn:!1}),v("已退出账号同步。")}finally{m(!1)}}
return JSX.jsxs("section",{className:"content account-page",children:[
JSX.jsxs("div",{className:"headline-row",children:[JSX.jsxs("div",{children:[
JSX.jsx("span",{className:"eyebrow",children:"红果账号同步"}),JSX.jsx("h1",{children:"验证码登录"})]}),
n&&n.loggedIn?JSX.jsxs("button",{className:"secondary",onClick:()=>void D(),disabled:g,children:["退出登录"]}):null]}),
JSX.jsxs("div",{className:"account-status-card "+(n&&n.loggedIn?"online":""),role:"status",children:[
JSX.jsxs("div",{children:[JSX.jsx("strong",{children:n&&n.loggedIn?("已登录 · "+(n.userName||"红果账号")):"未登录"}),
JSX.jsx("p",{children:n&&n.loggedIn?"桌面端看完的集数与收藏会同步到手机红果的历史/收藏里。"
:"登录后，桌面端看的进度与收藏会同步到手机；不登录也能正常看剧。"})]})]}),
!n||!n.loggedIn?JSX.jsxs("div",{className:"account-login-card",children:[
JSX.jsxs("div",{children:[JSX.jsx("h2",{children:"用手机号登录"}),JSX.jsx("p",{children:"验证码由红果下发；桌面端只保存登录态，不上传任何账号密码。"})]}),
JSX.jsxs("label",{className:"settings-row",children:[JSX.jsx("span",{children:"手机号"}),JSX.jsx("input",{type:"tel",inputMode:"numeric",value:o,
placeholder:"11 位手机号",onChange:x=>c(x.target.value.replace(/\D/g,"").slice(0,11))})]}),
JSX.jsxs("label",{className:"settings-row",children:[JSX.jsx("span",{children:"验证码"}),JSX.jsx("input",{type:"tel",inputMode:"numeric",value:d,
placeholder:"短信验证码",onChange:x=>f(x.target.value.replace(/\D/g,"").slice(0,8))})]}),
JSX.jsxs("div",{className:"qr-login-actions",children:[
JSX.jsx("button",{className:"secondary",onClick:()=>void L(),disabled:g||o.length!==11,children:"发送验证码"}),
JSX.jsx("button",{className:"primary",onClick:()=>void R(),disabled:g||o.length!==11||d.length<4,children:g?"处理中…":"登录"})]})]}):null,
p?JSX.jsx("p",{role:"status",className:"account-boundary",children:p}):null]})}
'''

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
        'a==="account"?b.jsxs(b.Fragment,{children:[b.jsx(aM,{status:Ti,qrLogin:on,busy:Cs,'
        'message:Dn,onRefresh:yr,onStartLogin:()=>void uo(),onLogout:()=>void co()}),'
        'b.jsx(hqAccountPanel,{})]}):',
        "账号页面板")

    # 6) 侧栏「账号」入口：本机服务可用时也显示
    s = _sub_once(
        s,
        re.escape('Ti?.configured&&b.jsx(Na,{active:a==="account"'),
        '(Ti?.configured||!!hqApi())&&b.jsx(Na,{active:a==="account"',
        "侧栏账号入口")

    # 7) 面板组件本体（放在账号面板组件之前）
    m = re.search(r'function aM\(\{status:r,qrLogin:e,busy:t,message:i,onRefresh:n,onStartLogin:a,onLogout:o\}\)\{',
                  s)
    if not m:
        raise Fail("[FAIL] 未找到账号面板组件")
    s = s[:m.start()] + panel + "\n" + s[m.start():]
    log.append("OK   注入验证码登录面板")

    return s, log
