# -*- coding: utf-8 -*-
"""红果桌面版前端补丁（版本无关）。

上游每次发版后都能自动重新注入，不依赖压缩后的变量名：

  * 结构固定、变量名会变的地方 —— 用带捕获组的正则回填原变量名；
  * 需要跨多处共享同一标识符的地方 —— 先用「语义探针」在源码里反查
    （如用 `X.defaultPlaybackRate=Y,Y.playbackRate=Y` 反查倍速状态变量）；
  * 每处替换都断言命中次数，任何一处不符预期就整体失败，绝不产出半成品。

用法：
    python patches/patch_frontend.py <输入 app.js> [输出 app.js]
默认 base/frontend/app.js -> src/frontend/app.js。
"""
import io
import os
import re
import sys
import hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEED_OLD = "[.75,1,1.25,1.5,2]"
SPEED_NEW = "[.75,1,1.25,1.5,2,2.5,3]"

QUAL_HELPERS = (
    'function hqQuals(){return["auto","1080p","720p","540p","480p"]}'
    'function hqReadQual(){try{const r=localStorage.getItem("guoban:quality")||"auto";'
    'return hqQuals().includes(r)?r:"auto"}catch{return"auto"}}'
    'function hqWriteQual(r){try{localStorage.setItem("guoban:quality",r);return!0}catch{return!1}}'
    'function hqStep(r){const e=[.75,1,1.25,1.5,2,2.5,3];'
    'const t=document.querySelector(\'select[aria-label="播放速度"]\');'
    'const i=t?e.indexOf(Number(t.value)):-1;'
    'const n=(i<0?1:i)+r;return e[Math.max(0,Math.min(e.length-1,n))]}'
    'function hqWithQual(r,e){if(!e||e==="auto")return r;const t=new URL(r);'
    'if(t.pathname!=="/desktop/hls")return r;t.searchParams.set("quality",e);return t.toString()}'
    'async function hqPost(run,aborted){const stop=()=>!!(aborted&&aborted());'
    'for(let i=0;i<10;i++){let res;try{res=await run()}catch(e){'
    'if(stop())throw e;await new Promise(k=>setTimeout(k,200*(i+1)));continue}'
    'if(res.status!==503||stop())return res;'
    'await new Promise(k=>setTimeout(k,200*(i+1)))}return run()}'
)


POST_RETRY = re.compile(
    r'([\w$]+)=await ([\w$]+)\(([\w$().]+),\{method:"POST"\}\);'
    r'if\(\1\.status===503&&([^{]+)\)\{'
    r'if\(await new Promise\([^}]+?\),([^)]+)\)'
    r'(return(?: null)?;)'
    r'\1=await \2\(\3,\{method:"POST"\}\)\}'
)


# ---- 本地维护: 自动连播时不弹控制栏/鼠标 ----
# 切集时旧集 ended/pause 会触发延时隐藏，而新集 playing 会立即
# 无条件把控制栏变可见，所以每次自动连播都会弹一下。
# 这里用一个标志位：自动连播时置位，跳过新集首次 playing；
# 一旦进入正常隐藏周期（G）或用户主动操作，就清掉。
# 不用定时窗口，避免切集耗时超过窗口时失效。
CONTROLS_DECL_OLD = "it=P.useRef(null),ye=P.useRef(0),Se=P.useRef(null),He=P.useRef(null),"
CONTROLS_DECL_NEW = (
    "it=P.useRef(null),ye=P.useRef(0),Se=P.useRef(null),He=P.useRef(null),"
    "hqAutoAdvance=P.useRef(!1),"
)

# 自动连播：置位标志
AUTOARM_OLD = "ve&&C.episode<Pt&&Ri(C.episode+1)"
AUTOARM_NEW = "ve&&C.episode<Pt&&(hqAutoAdvance.current=!0,Ri(C.episode+1))"

# 播放器组件接收标志引用
JR_PROPS_OLD = "ignoreButtonClickDuringAcceptancePlayback:D=!1,onAcceptanceButtonClickIgnored:M}){"
JR_PROPS_NEW = (
    "ignoreButtonClickDuringAcceptancePlayback:D=!1,onAcceptanceButtonClickIgnored:M,"
    "autoAdvanceRef:hqAutoAdvance=null}){"
)

JR_CALL_OLD = (
    'ignoreButtonClickDuringAcceptancePlayback:p,onAcceptanceButtonClickIgnored:E?ae=>ci({kind:"buttonClickIgnored",'
    'inputSource:"button",inputTrusted:ae}):void 0})]'
)
JR_CALL_NEW = (
    'ignoreButtonClickDuringAcceptancePlayback:p,onAcceptanceButtonClickIgnored:E?ae=>ci({kind:"buttonClickIgnored",'
    'inputSource:"button",inputTrusted:ae}):void 0,autoAdvanceRef:hqAutoAdvance})]'
)

# 正常隐藏周期开始时清掉标志（用户主动操作会路过这里）
JR_HIDE_OLD = "function G(){clearTimeout(V.current),"
JR_HIDE_NEW = "function G(){hqAutoAdvance&&(hqAutoAdvance.current=!1),clearTimeout(V.current),"

# 自动连播时，旧集的 pause/ended 也会把控制栏变可见（第二条路径）。
# 实测时序：ended -> 此处 z(!0) 弹出 -> emptied 后新集首次 playing 再次 z(!0)。
# 两条路径都要挡。
JR_PAUSE_OLD = (
    '(Le?.type==="pause"||Le?.type==="ended")&&!k.current.disabled&&'
    '!(ye.ended&&k.current.autoContinue)&&z(!0)'
)
JR_PAUSE_NEW = (
    '(Le?.type==="pause"||Le?.type==="ended")&&!k.current.disabled&&'
    '!(ye.ended&&k.current.autoContinue)&&'
    '!(hqAutoAdvance&&hqAutoAdvance.current)&&z(!0)'
)

# 自动连播后的首次 playing 不把控制栏变可见
JR_PLAYING_OLD = "q(\"\"),$(\"播放中\"),z(!0),W(!1),Ft&&vt.current?.media!==De&&or(De)"
JR_PLAYING_NEW = (
    'q(""),$("播放中"),'
    '(hqAutoAdvance&&hqAutoAdvance.current)||z(!0),'
    'W(!1),Ft&&vt.current?.media!==De&&or(De)'
)


# ---- 本地维护: 预取提前到 2 集 ----
# 上游只提前 1 集。一集在 3 倍速下只播 ~20s，
# 而一集转码约 27s（解密+下载+HEVC->H.264），所以预取经常来不及，
# 切集时只能现场等（实测 8.37s / 11.84s 黑屏）。
# 改成用 Map 同时预取 下一集 + 下两集，把预取窗口拉长到 ~40s。
PREFETCH_REF_OLD = "et=P.useRef(null),Ot=P.useRef(void 0),"
PREFETCH_REF_NEW = "et=P.useRef(new Map),Ot=P.useRef(void 0),"

# 预取主体：一次预取两集，结果存进 Map
PREFETCH_BODY_OLD = (
    "let De=!0,Fe=!1,tt;"
)
PREFETCH_BODY_NEW = (
    "let De=!0,Fe=!1;"
)

PREFETCH_LOOP_OLD = (
    'const Ut=ye.current;try{const Vt=await zs(C.seriesId,C.episode+1);'
    'if(!De||D.current||Ut!==ye.current||Vt.source!==C.source||Vt.streamFormat!=="hls"||'
    'Vt.seriesId!==C.seriesId||Vt.episode!==C.episode+1||!Number.isSafeInteger(Vt.total)||'
    'Vt.total<Vt.episode)return;tt=Kw(hqWithQual(Vt.streamUrl,hqQ)),'
    'et.current={session:Vt,preparation:tt}}catch{}}'
)
PREFETCH_LOOP_NEW = (
    'const Ut=ye.current;'
    'for(const hs of [1,2]){'
    'const Vt=C.episode+hs;'
    'if(Vt>Pt)break;'
    'if(et.current.has(Vt))continue;'
    'try{'
    'const Ae=await zs(C.seriesId,Vt);'
    'if(!De||D.current||Ut!==ye.current||Ae.source!==C.source||Ae.streamFormat!=="hls"||'
    'Ae.seriesId!==C.seriesId||Ae.episode!==Vt||!Number.isSafeInteger(Ae.total)||'
    'Ae.total<Ae.episode){continue}'
    'const Ye=Kw(hqWithQual(Ae.streamUrl,hqQ));'
    'if(!De||D.current||Ut!==ye.current){Ye.dispose();continue}'
    'et.current.set(Vt,{session:Ae,preparation:Ye})'
    '}catch{}}'
    '}'
)

# 清理：只清超过当前+2 的，保留下一集的预取
PREFETCH_CLEAN_OLD = (
    "tt&&et.current?.preparation===tt&&(et.current=null,tt.dispose())"
)
PREFETCH_CLEAN_NEW = (
    "et.current.forEach((Vt,hs)=>{if(hs>C.episode+2){Vt.preparation.dispose(),et.current.delete(hs)}})"
)

# 消费点：从 Map 取对应集的预取
PREFETCH_USE_OLD = (
    "const Fe=et.current?.session.episode===ae?et.current:null;"
    "Fe||et.current?.preparation.dispose(),Fe||(et.current=null),Fe&&(et.current=null),"
)
PREFETCH_USE_NEW = (
    "const Fe=et.current.get(ae)??null;Fe&&et.current.delete(ae),"
)

# 换集时清空预取集合
PREFETCH_RESET_OLD = "et.current?.preparation.dispose(),et.current=null,"
PREFETCH_RESET_NEW = (
    "et.current.forEach(Vt=>Vt.preparation.dispose()),et.current.clear(),"
)


# ---- 本地维护: 看完一集就让后端清掉它的缓存 ----
# 后端只写不清会让 stream-cache 无限增长（实测 45GB/903 个文件）。
# 在 onEnded 里发一个 DELETE，失败也不影响播放。

CLEANUP_CALL_OLD = "C.episode===Pt&&W(!0),ve&&C.episode<Pt&&"
# 自包含：从 C.streamUrl 取 origin 与 api_key，不依赖外部标识符。
CLEANUP_CALL_NEW = (
    "C.episode===Pt&&W(!0),"
    "(()=>{try{const u=new URL(C.streamUrl);"
    "fetch(`${u.origin}/desktop/cleanup?series_id=${encodeURIComponent(C.seriesId)}&ep=${C.episode}`,"
    "{method:\"GET\",headers:{\"x-api-key\":u.searchParams.get(\"api_key\")||\"\"},"
    "credentials:\"omit\",redirect:\"error\",keepalive:!0}).catch(()=>{})}catch(e){}})(),"
    "ve&&C.episode<Pt&&"
)

class Fail(SystemExit):
    pass


class Patcher:
    def __init__(self, text):
        self.s = text
        self.log = []

    def sub(self, pattern, repl, label, count=1):
        new, n = re.subn(pattern, repl, self.s)
        if n != count:
            raise Fail(f"[FAIL] {label}: 命中 {n} 次，预期 {count} 次\n  pattern={pattern}")
        self.s = new
        self.log.append(f"OK   {label}")

    def probe(self, pattern, label, group=1):
        m = re.search(pattern, self.s)
        if not m:
            raise Fail(f"[FAIL] 探针未命中: {label}\n  pattern={pattern}")
        val = m.group(group)
        self.log.append(f"OK   探针 {label} -> {val}")
        return val

    def insert(self, index, text, label):
        self.s = self.s[:index] + text + self.s[index:]
        self.log.append(f"OK   {label}")


def patch(text):
    p = Patcher(text)

    # ===== 0) 先探测本次构建的变量名（上游每次发版都会变） =====
    # 媒体元素（<video>）与播放器 refs 对象
    media = p.probe(r'(\w+)\.volume=Math\.max\(0,Math\.min\(1,\1\.volume', "媒体元素")
    refs = p.probe(r'(\w+)\.current\.seekTarget\?\?', "播放器 refs")
    jsx = p.probe(r'(\w+)\.jsx\("span",\{className:"player-toolbar-spacer"\}\)', "JSX 工厂")
    # 播放器组件里的函数：seek / 播放暂停 / 提示 / 倍速设置
    seek = p.probe(r'&&(\w+)\(\(?' + re.escape(refs) + r'\.current\.seekTarget\?\?', "seek 函数")
    toggle = p.probe(r'\w+\.key===" "&&(\w+)\(\{inputSource:"space"', "播放暂停函数")
    # 倍速设置函数必须取「播放器组件形参」里的 onRate（组件内部作用域），
    # 不能取调用处（那是外层 R0 的变量，组件内不可见）。
    rate_set = p.probe(r'rate:\w+,onRate:(\w+),duration:', "倍速设置函数")
    msg = p.probe(r'(\w+)\("播放中断，请重试当前集。?"\)', "提示函数")
    # 倍速状态（供 [ ] 调档时读取当前值）
    rate_var = p.probe(r'\w+\.defaultPlaybackRate=(\w+),\w+\.playbackRate=\1', "倍速状态变量")
    # 键盘事件变量名（形如 `XX=Le=>{Le.altKey||...`）
    keyev = p.probe(r'(\w+)=(\w+)=>\{\2\.altKey\|\|\2\.ctrlKey', "键盘事件参数", group=2)

    # ===== 1) 倍速档位（工具栏 / 设置页 / 偏好校验 共 3 处） =====
    p.sub(re.escape(SPEED_OLD), SPEED_NEW, "倍速档位", count=3)

    # ===== 2) 注入清晰度辅助函数（放在 HLS URL 解析器之前） =====
    m = re.search(r'function \w+\(\w+\)\{const \w+=new URL\(\w+\),\w+=\w+\.searchParams\.get\("api_key"\)', p.s)
    if not m:
        raise Fail("[FAIL] 未找到 HLS URL 解析器")
    p.insert(m.start(), QUAL_HELPERS, "注入清晰度辅助函数")

    # ===== 3) URL 参数白名单放行 quality =====
    p.sub(r'\["api_key","series_id","ep"\]\.includes',
          '["api_key","series_id","ep","quality"].includes',
          "URL 白名单放行 quality")

    # ===== 4) 工具栏清晰度下拉（放在倍速下拉之前） =====
    p.sub(
        r'(' + re.escape(jsx) + r'\.jsx\("span",\{className:"player-toolbar-spacer"\}\),)'
        r'(' + re.escape(jsx) + r'\.jsx\("select",\{"aria-label":"播放速度",)',
        r'\1'
        + jsx + '.jsx("select",{"aria-label":"清晰度",title:"清晰度",value:hqQ,'
        + 'onChange:ue=>{const ve=ue.target.value;hqL(ve)},'
        + 'children:hqQuals().map(ue=>' + jsx + '.jsx("option",{value:ue,'
        + 'children:ue==="auto"?"清晰度":ue},ue))}),'
        + r'\2',
        "工具栏清晰度下拉")

    # ===== 5) 播放器组件接收清晰度 =====
    p.sub(r'rate:(\w+),onRate:(\w+),duration:',
          r'rate:\1,onRate:\2,quality:hqQ,onQuality:hqL,duration:',
          "播放器组件形参")

    # ===== 6) 播放器组件使用处传参 =====
    p.sub(r'rate:(\w+),onRate:(\w+),onAcceptancePause:',
          r'rate:\1,onRate:\2,quality:hqQ,onQuality:hqL,onAcceptancePause:',
          "播放器组件传参")

    # ===== 7) 播放器页：清晰度状态 + 切换回调 =====
    p.sub(r'playbackRateInitially:(\w+)=1',
          r'playbackRateInitially:\1=1,qualityInitially:hqFq="auto"',
          "新增 qualityInitially 形参")
    m = re.search(r'\[(' + re.escape(rate_var) + r'),(\w+)\]=(\w+)\.useState\((\w+)\)', p.s)
    if not m:
        raise Fail("[FAIL] 未找到倍速 useState")
    hook, prop = m.group(3), m.group(4)
    p.sub(r'(\[' + re.escape(rate_var) + r',\w+\]=' + re.escape(hook) + r'\.useState\(' + re.escape(prop) + r'\))',
          r'\1,'
          r'[hqQ,hqB]=' + hook + '.useState(()=>hqQuals().includes(hqFq)?hqFq:hqReadQual()),'
          r'[hqV,hqW]=' + hook + '.useState(0),'
          r'hqL=' + hook + '.useCallback(ce=>{const Le=hqQuals().includes(ce)?ce:"auto";'
          r'hqWriteQual(Le),hqB(Le),hqW(ue=>ue+1)},[])',
          "清晰度状态与切换回调")

    # ===== 8) HLS 会话用带清晰度的 URL，并在切换时重建 =====
    m = re.search(r'(\w+\([\w.$]+,)([\w.$]+\.streamUrl)(,\w+,\{onReady:)', p.s)
    if not m:
        raise Fail("[FAIL] 未找到 HLS 会话启动点")
    p.sub(r'(\w+\([\w.$]+,)([\w.$]+\.streamUrl)(,\w+,\{onReady:)',
          r'\1hqWithQual(\2,hqQ)\3', "HLS 会话 URL 带清晰度")
    # 该 effect 的依赖数组补上清晰度
    start = p.s.find("},[", m.start())
    end = p.s.find("]", start)
    p.s = p.s[:end] + ",hqQ,hqV" + p.s[end:]
    p.log.append("OK   HLS 效果依赖 +hqQ,hqV")

    # ===== 9) 预取下一集也用同一清晰度 =====
    m = re.search(r'(\w+)=(\w+)\(([\w.$]+\.streamUrl)\),', p.s)
    if not m:
        raise Fail("[FAIL] 未找到预取点")
    p.sub(r'(\w+)=(\w+)\(([\w.$]+\.streamUrl)\),',
          r'\1=\2(hqWithQual(\3,hqQ)),', "预取带清晰度")
    start = p.s.find("},[", m.start())
    end = p.s.find("]", start)
    p.s = p.s[:end] + ",hqQ,hqV" + p.s[end:]
    p.log.append("OK   预取效果依赖 +hqQ,hqV")

    # ===== 10) 设置页新增默认清晰度 =====
    p.sub(r'(' + re.escape(jsx) + r'\.jsxs\("label",\{className:"settings-row",children:\[)'
          r'(' + re.escape(jsx) + r'\.jsx\("span",\{children:"默认播放速度"\}\),)',
          r'\1'
          + jsx + '.jsx("span",{children:"默认清晰度"}),'
          + jsx + '.jsx("select",{value:hqReadQual(),'
          + 'onChange:o=>{hqWriteQual(o.target.value),e({...r})},'
          + 'children:hqQuals().map(o=>' + jsx + '.jsx("option",{value:o,'
          + 'children:o==="auto"?"自动（最高）":o},o))}),'
          + r'\2',
          "设置页默认清晰度")

    # ===== 11) 打开播放器时传入已存偏好 =====
    p.sub(r'(autoNextInitially:\w+\.autoNext,playbackRateInitially:\w+\.playbackRate,)',
          r'\1qualityInitially:hqReadQual(),',
          "传入 qualityInitially")

    # ===== 12) 修复：点过按钮后按空格会重复激活该按钮 =====
    p.sub(r'\.closest\("input,select,textarea,button,\[contenteditable=true\]"\)',
          '.closest("input,select,textarea,[contenteditable=true]")',
          "焦点守卫不再排除按钮")
    p.sub(r'(' + re.escape(keyev) + r'\.key===" "&&)(\w+)\(\{inputSource:"space",inputTrusted:' + re.escape(keyev) + r'\.isTrusted\}\)',
          r'\1(' + keyev + r'.target instanceof HTMLElement&&' + keyev + r'.target.closest("button")&&'
          + keyev + r'.target.blur(),\2({inputSource:"space",inputTrusted:' + keyev + r'.isTrusted}))',
          "空格键先清焦点")

    # ===== 12.5) 503 重试改为指数退避多次重试 =====
    # 上游只在 200ms 后重试一次; 切集瞬间编码池还在收尾,
    # 一次重试很容易跌进「媒体准备失败」。
    if ".status===503" in p.s:
        p.sub(POST_RETRY.pattern,
              r'\1=await hqPost(()=>\2(\3,{method:"POST"}),()=>\5);if(\5)\6',
              "开场 POST 指数退避重试", count=2)

    # ===== 12.7) 自动连播时不弹控制栏/鼠标 ====
    # 动画循环：旧集 ended/pause -> G()（延时隐藏）-> 新集 playing -> z(!0)
    # 把控制栏变可见。在自动连播路径上打开一个短窗口，
    # 跳过新集的首次 playing，使控制栏保持隐藏、鼠标保持隐藏。
    p.sub(re.escape(CONTROLS_DECL_OLD), CONTROLS_DECL_NEW, "自动连播标志位")
    p.sub(re.escape(AUTOARM_OLD), AUTOARM_NEW, "自动连播置位标志")
    p.sub(re.escape(JR_PROPS_OLD), JR_PROPS_NEW, "播放器组件接收标志引用")
    p.sub(re.escape(JR_CALL_OLD), JR_CALL_NEW, "播放器组件传参")
    p.sub(re.escape(JR_PAUSE_OLD), JR_PAUSE_NEW, "自动连播时 pause/ended 不弹控制栏")
    p.sub(re.escape(JR_HIDE_OLD), JR_HIDE_NEW, "隐藏周期清掉标志")
    p.sub(re.escape(JR_PLAYING_OLD), JR_PLAYING_NEW, "首次 playing 不弹控制栏")

    # ===== 12.8) 预取提前到 2 集（消除切集黑屏）====
    # 上游只预取下一集；一集在 3 倍速下只播 ~20s，
    # 而转码约 27s，预取来不及就会出现 8~12s 黑屏。
    # 改成 Map 同时预取下 1 / 下 2 集，预取窗口拉长到 ~40s。
    p.sub(re.escape(PREFETCH_REF_OLD), PREFETCH_REF_NEW, "预取改为 Map")
    p.sub(re.escape(PREFETCH_BODY_OLD), PREFETCH_BODY_NEW, "移除单集预取变量")
    p.sub(re.escape(PREFETCH_LOOP_OLD), PREFETCH_LOOP_NEW, "预取下 1 / 下 2 集")
    p.sub(re.escape(PREFETCH_CLEAN_OLD), PREFETCH_CLEAN_NEW, "预取清理保留下一集")
    p.sub(re.escape(PREFETCH_USE_OLD), PREFETCH_USE_NEW, "从 Map 取预取")
    p.sub(re.escape(PREFETCH_RESET_OLD), PREFETCH_RESET_NEW, "换集时清空预取")

    # ===== 12.9) 看完一集后通知后端清理该集缓存 =====
    p.sub(re.escape(CLEANUP_CALL_OLD), CLEANUP_CALL_NEW, "看完自动清理缓存")
    # ===== 13) 键盘白名单放行新按键 =====
    p.sub(r'!\[" ","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","f","F","m","M","n","N","t","T"\]',
          '![" ","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","f","F","m","M","n","N","t","T",'
          '"[","]","{","}","<",">",",",".","PageUp","PageDown","Home","End",'
          '"k","K","j","J","d","D","s","S","0","1","2","3","4","5","6","7","8","9"]',
          "键盘白名单")

    # ===== 14) 键盘快捷键分支（插在原有 "下一集" 分支之后，不碰收尾括号） =====
    E, R, M, S, T, G = keyev, refs, media, seek, toggle, msg
    anchor = (E + '.key.toLowerCase()==="n"&&' + R + '.current.canNext&&!'
              + R + '.current.disabled&&' + R + '.current.onNext(),')
    if p.s.count(anchor) != 1:
        raise Fail(f"[FAIL] 键盘分支锚点命中 {p.s.count(anchor)} 次")
    extra = (
        '(' + E + '.key==="["||' + E + '.key==="{")&&' + rate_set + '(hqStep(-1)),'
        + '(' + E + '.key==="]"||' + E + '.key==="}")&&' + rate_set + '(hqStep(1)),'
        + '(' + E + '.key==="<"||' + E + '.key===",")&&' + S
        + '(Math.max(0,(' + R + '.current.seekTarget??' + M + '.currentTime)-10)),'
        + '(' + E + '.key===">"||' + E + '.key===".")&&' + S
        + '((' + R + '.current.seekTarget??' + M + '.currentTime)+10),'
        + E + '.key==="PageUp"&&' + S
        + '(Math.max(0,(' + R + '.current.seekTarget??' + M + '.currentTime)-60)),'
        + E + '.key==="PageDown"&&' + S
        + '((' + R + '.current.seekTarget??' + M + '.currentTime)+60),'
        + E + '.key==="Home"&&' + S + '(0),'
        + E + '.key==="End"&&' + S + '(Number.isFinite(' + M + '.duration)?' + M + '.duration:0),'
        + E + '.key.toLowerCase()==="k"&&' + T
        + '({inputSource:"keyboard",inputTrusted:' + E + '.isTrusted}),'
        + E + '.key.toLowerCase()==="j"&&' + R + '.current.canNext&&!'
        + R + '.current.disabled&&' + R + '.current.onNext(),'
        + E + '.key.toLowerCase()==="d"&&(' + M + '.muted=!' + M + '.muted),'
        + E + '.key.toLowerCase()==="s"&&(' + M + '.loop=!' + M + '.loop,'
        + G + '(' + M + '.loop?"循环播放已开启":"循环播放已关闭")),'
        + '/^[0-9]$/.test(' + E + '.key)&&Number.isFinite(' + M + '.duration)&&'
        + M + '.duration>0&&' + S + '(' + M + '.duration*(Number(' + E + '.key)/10)),'
    )
    p.s = p.s.replace(anchor, anchor + extra, 1)
    p.log.append("OK   键盘快捷键分支")

    return p


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "base", "frontend", "app.js")
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "src", "frontend", "app.js")
    s = io.open(src, encoding="utf-8").read()
    p = patch(s)
    io.open(dst, "w", encoding="utf-8", newline="").write(p.s)
    # app.css / index.html 不需要改内容，但要跟随基线一起同步（上游发版会变）。
    src_dir = os.path.dirname(src)
    dst_dir = os.path.dirname(dst)
    for extra in ("app.css", "index.html"):
        cand = os.path.join(src_dir, extra)
        if os.path.isfile(cand):
            io.open(os.path.join(dst_dir, extra), "w", encoding="utf-8", newline="").write(
                io.open(cand, encoding="utf-8").read())
            print("OK   同步", extra)
    for line in p.log:
        print(line)
    print(f"[OK] {os.path.basename(dst)}: {len(p.s)} bytes "
          f"sha256={hashlib.sha256(p.s.encode()).hexdigest()[:16]}")


if __name__ == "__main__":
    main()
