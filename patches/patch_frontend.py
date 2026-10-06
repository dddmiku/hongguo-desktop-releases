# -*- coding: utf-8 -*-
"""红果桌面版本地维护: 前端补丁 (3x 倍速 + 清晰度可选)。

- 纯文本替换, 不压缩; 打包见 tools/repack.py
- 注入标识符与原 bundle 无冲突 (hqQ/hqB/hqV/hqL/hqFq/hqQuals/... 均 0 次)
- 每处替换唯一命中, 否则直接失败
- 产出后由 tools/check_frontend.js 做 node --check 语法校验
"""
import io, os, sys, hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "frontend", "app.js")
ORIG = os.path.join(ROOT, "base", "frontend", "app.js")

SPEED_OLD = "[.75,1,1.25,1.5,2]"
SPEED_NEW = "[.75,1,1.25,1.5,2,2.5,3]"

QUAL = (
    'function hqQuals(){return["auto","1080p","720p","540p","480p"]}'
    'function hqReadQual(){try{const r=localStorage.getItem("guoban:quality")||"auto";'
    'return hqQuals().includes(r)?r:"auto"}catch{return"auto"}}'
    'function hqWriteQual(r){try{localStorage.setItem("guoban:quality",r);return!0}catch{return!1}}'
    'function hqWithQual(r,e){if(!e||e==="auto")return r;const t=new URL(r);'
    'if(t.pathname!=="/desktop/hls")return r;t.searchParams.set("quality",e);return t.toString()}'
)


def sub_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"[FAIL] {label}: expected 1 occurrence, found {n}")
    return text.replace(old, new, 1)


def main():
    src = ORIG
    s = io.open(src, encoding="utf-8").read()
    orig_len = len(s)

    # 1) 倍速: 工具栏 / 设置页 / 偏好校验共用同一数组
    s = s.replace(SPEED_OLD, SPEED_NEW)
    if s.count(SPEED_NEW) != 3:
        raise SystemExit("[FAIL] speed array: expected 3 sites")

    # 2) 清晰度工具函数 (定义在 Rx 之前)
    s = sub_once(s,
        'function Rx(r){const e=new URL(r),t=e.searchParams.get("api_key")||""',
        QUAL + 'function Rx(r){const e=new URL(r),t=e.searchParams.get("api_key")||""',
        "inject helpers")

    # 3) Rx 白名单放行 quality
    s = sub_once(s,
        'some(a=>!["api_key","series_id","ep"].includes(a))',
        'some(a=>!["api_key","series_id","ep","quality"].includes(a))',
        "Rx allow quality")

    # 4) 工具栏清晰度下拉
    s = sub_once(s,
        'x.jsx("span",{className:"player-toolbar-spacer"}),x.jsx("select",{"aria-label":"播放速度",',
        'x.jsx("span",{className:"player-toolbar-spacer"}),'
        'x.jsx("select",{"aria-label":"清晰度",title:"清晰度",value:hqQ,'
        'onChange:ue=>{const ve=ue.target.value;hqL(ve)},disabled:i,'
        'children:hqQuals().map(ue=>x.jsx("option",{value:ue,'
        'children:ue==="auto"?"清晰度":ue},ue))}),'
        'x.jsx("select",{"aria-label":"播放速度",',
        "toolbar quality select")

    # 5) DR 组件形参
    s = sub_once(s, "rate:g,onRate:m,", "rate:g,onRate:m,quality:hqQ,onQuality:hqL,", "DR params")

    # 6) DR 使用处传参
    s = sub_once(s, "rate:pe,onRate:ye,onAcceptancePause:",
                 "rate:pe,onRate:ye,quality:hqQ,onQuality:hqL,onAcceptancePause:", "DR usage")

    # 7) R0 状态 (替换锚点本身不带尾部逗号; 原代码逗号保留)
    s = sub_once(s, "[pe,ye]=O.useState(f)",
        '[pe,ye]=O.useState(f),'
        '[hqQ,hqB]=O.useState(()=>hqQuals().includes(hqFq)?hqFq:hqReadQual()),'
        '[hqV,hqW]=O.useState(0),'
        'hqL=O.useCallback(ce=>{const Le=hqQuals().includes(ce)?ce:"auto";'
        'hqWriteQual(Le),hqB(Le),hqW(ue=>ue+1)},[])',
        "R0 quality state")

    # 8) R0 的 HLS 会话 URL + 依赖
    s = sub_once(s, "const ut=Nw(ce,C.streamUrl,Be,", "const ut=Nw(ce,hqWithQual(C.streamUrl,hqQ),Be,", "HLS start")
    s = sub_once(s, "},[C,ve,ft,o,v,E]),", "},[C,ve,ft,o,v,E,hqQ,hqV]),", "HLS deps")

    # 9) 预取下一集
    s = sub_once(s, "je=Ow(Zt.streamUrl),", "je=Ow(hqWithQual(Zt.streamUrl,hqQ)),", "prefetch")
    s = sub_once(s, ",[C,ve,ft,dt,Rt,Ee]);", ",[C,ve,ft,dt,Rt,Ee,hqQ,hqV]);", "prefetch deps")

    # 10) 设置页默认清晰度
    s = sub_once(s,
        'x.jsxs("label",{className:"settings-row",children:[x.jsx("span",{children:"默认播放速度"}),',
        'x.jsxs("label",{className:"settings-row",children:['
        'x.jsx("span",{children:"默认清晰度"}),'
        'x.jsx("select",{value:hqReadQual(),'
        'onChange:o=>{hqWriteQual(o.target.value),e({...r})},'
        'children:hqQuals().map(o=>x.jsx("option",{value:o,'
        'children:o==="auto"?"自动（最高）":o},o))})]}),'
        'x.jsxs("label",{className:"settings-row",children:['
        'x.jsx("span",{children:"默认播放速度"}),',
        "settings quality row")

    # 11) 快捷键: 在原有基础上补齐常用键
    #     [ ] 调倍速  |  < > 跳转 10s  |  PageUp/PageDown 跳转 60s
    #     k/j 播放暂停/下一集  |  0-9 按百分比跳转  |  s 循环播放  |  d 静音
    KEY_OLD = (
        '![" ","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","f","F","m","M","n","N","t","T"].includes(Oe.key)'
    )
    KEY_NEW = (
        '![" ","ArrowLeft","ArrowRight","ArrowUp","ArrowDown","f","F","m","M","n","N","t","T",'
        '"[","]","{","}","<",">",",",".","PageUp","PageDown","Home","End","k","K","j","J","d","D","s","S",'
        '"0","1","2","3","4","5","6","7","8","9"].includes(Oe.key)'
    )
    s = sub_once(s, KEY_OLD, KEY_NEW, "keydown allowlist")

    HANDLERS_OLD = (
        'Oe.key.toLowerCase()==="n"&&V.current.canNext&&!V.current.disabled&&V.current.onNext(),te())};'
    )
    HANDLERS_NEW = (
        'Oe.key.toLowerCase()==="n"&&V.current.canNext&&!V.current.disabled&&V.current.onNext(),'
        # --- 倍速: [ / ] 在档位间切换 (与工具栏下拉共用同一状态) ---
        '(Oe.key==="["||Oe.key==="{")&&m(hqStep(-1)),'
        '(Oe.key==="]"||Oe.key==="}")&&m(hqStep(1)),'
        # --- 跳转: < > 10s, PageUp/PageDown 60s, Home/End 首尾 ---
        '(Oe.key==="<"||Oe.key===",")&&N(Math.max(0,(V.current.seekTarget??ue.currentTime)-10)),'
        '(Oe.key===">"||Oe.key===".")&&N((V.current.seekTarget??ue.currentTime)+10),'
        'Oe.key==="PageDown"&&N((V.current.seekTarget??ue.currentTime)+60),'
        'Oe.key==="PageUp"&&N(Math.max(0,(V.current.seekTarget??ue.currentTime)-60)),'
        'Oe.key==="Home"&&N(0),'
        'Oe.key==="End"&&N(Number.isFinite(ue.duration)?ue.duration:0),'
        # --- 播放: k 播放/暂停, j 下一集 (n 已有), d 静音, s 循环 ---
        'Oe.key.toLowerCase()==="k"&&W({inputSource:"keyboard",inputTrusted:Oe.isTrusted}),'
        'Oe.key.toLowerCase()==="j"&&V.current.canNext&&!V.current.disabled&&V.current.onNext(),'
        'Oe.key.toLowerCase()==="d"&&(ue.muted=!ue.muted),'
        'Oe.key.toLowerCase()==="s"&&(ue.loop=!ue.loop,j(ue.loop?"循环播放已开启":"循环播放已关闭")),'
        # --- 数字键: 按百分比跳转 ---
        '/^[0-9]$/.test(Oe.key)&&Number.isFinite(ue.duration)&&ue.duration>0'
        '&&N(ue.duration*(Number(Oe.key)/10)),'
        'te())};'
    )
    s = sub_once(s, HANDLERS_OLD, HANDLERS_NEW, "keydown handlers")

    # hqStep: 在清晰度/倍速共用档位之外, 供 [ ] 调倍速
    STEP_OLD = 'function hqWithQual(r,e){if(!e||e==="auto")return r;const t=new URL(r);'
    STEP_NEW = (
        'function hqStep(r){const e=[.75,1,1.25,1.5,2,2.5,3];'
        'const t=document.querySelector(\'select[aria-label="播放速度"]\');'
        'const i=t?e.indexOf(Number(t.value)):-1;'
        'const n=(i<0?1:i)+r;return e[Math.max(0,Math.min(e.length-1,n))]}'
        'function hqWithQual(r,e){if(!e||e==="auto")return r;const t=new URL(r);'
    )
    s = sub_once(s, STEP_OLD, STEP_NEW, "hqStep helper")

    # 11) 打开播放器时传入已存偏好
    s = sub_once(s,
        "autoNextInitially:e.autoNext,playbackRateInitially:e.playbackRate,resolve:je.source===",
        "autoNextInitially:e.autoNext,playbackRateInitially:e.playbackRate,qualityInitially:hqReadQual(),resolve:je.source===",
        "pass qualityInitially")
    s = sub_once(s, "playbackRateInitially:f=1,observeMedia:d",
                 'playbackRateInitially:f=1,qualityInitially:hqFq="auto",observeMedia:d', "R0 prop")

    io.open(SRC, "w", encoding="utf-8", newline="").write(s)
    print(f"[OK] app.js: {orig_len} -> {len(s)} bytes (+{len(s)-orig_len})")
    print("[OK] sha256:", hashlib.sha256(s.encode("utf-8")).hexdigest())


if __name__ == "__main__":
    main()
