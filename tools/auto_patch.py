# -*- coding: utf-8 -*-
"""红果桌面版补丁自动重注入。

上游每次发新版（软件内更新或重装）都会把 exe 和后端换成原版，
我们的补丁会随之丢失。本工具负责检测并自动重新注入。

工作方式:
  1. 记录「已打补丁的 exe 哈希」到 state.json；
  2. 检测到 exe 哈希变化且不再是已打补丁版本时：
       a. 从 exe 内嵌资源取出上游原版前端（无需下载安装包）；
       b. 对原版前端套用补丁；
       c. 把补丁后的前端压回 exe；
       d. 对后端关键文件套用补丁；
  3. 全程原子替换，并保留 .orig 备份便于回滚。

用法:
    python tools/auto_patch.py status      # 只看状态
    python tools/auto_patch.py apply       # 检测并重新注入
    python tools/auto_patch.py install     # 注册为登录自启动（Windows 计划任务）
    python tools/auto_patch.py uninstall   # 取消自启动
"""
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "patches"))
from tauri_assets import (Assets, load_capacities, save_capacities,  # noqa: E402
                          capacity_path)
import patch_frontend                    # noqa: E402
import patch_backend                     # noqa: E402
import rebrand_exe                       # noqa: E402
from repack import align_index_html      # noqa: E402

def _app_dir():
    """安装目录：环境变量 > 卸载注册项 > 历史默认值。

    以前写死 d:\\Users\\...\\红果免费短剧。装到别的盘或换成安装包默认位置
    （%LOCALAPPDATA%\\Programs\\红果免费短剧）之后，写死的路径会让 auto_patch
    在错误的目录上工作（找不到 exe 就报失败，或更糟：去动一个已经没人用的旧目录）。
    """
    env = os.environ.get("HONGGUO_APP_DIR")
    if env:
        return env
    try:
        import winreg
        for root, path in (
            (winreg.HKEY_CURRENT_USER,
             r"Software\Microsoft\Windows\CurrentVersion\Uninstall\红果免费短剧"),
            (winreg.HKEY_LOCAL_MACHINE,
             r"Software\Microsoft\Windows\CurrentVersion\Uninstall\红果免费短剧"),
        ):
            try:
                with winreg.OpenKey(root, path) as k:
                    loc = str(winreg.QueryValueEx(k, "InstallLocation")[0]).strip()
                loc = loc.strip('"')
                if loc and os.path.isfile(
                        os.path.join(loc, "hongguo-desktop-companion.exe")):
                    return loc
            except OSError:
                continue
    except ImportError:
        pass
    return os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "红果免费短剧")


APP_DIR = _app_dir()
EXE = os.path.join(APP_DIR, "hongguo-desktop-companion.exe")
BACKEND = os.path.join(APP_DIR, "backend")
STATE = os.path.join(ROOT, "state.json")
BACKUP = os.path.join(ROOT, "_backup")
TASK = "HongguoDesktopPatch"

# 补丁涉及的全部后端文件；部署与备份都必须覆盖它们，缺一个就会「媒体准备失败」。
PATCHED_FILES = ("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py",
                 "desktop_account.py", "desktop_account_api.py", "downloader.py", "safeguards.py")

# 我们新增、上游基线里没有的模块（从 patches/account/ 重建）。
# 注意：safeguards.py 是上游自带文件（只是被补丁改过），不能列在这里 ——
# 列进来会让 auto_patch 从 patches/account/ 找它，找不到就跳过，等于漏打补丁。
ACCOUNT_FILES = ("desktop_account.py", "desktop_account_api.py",
                 "desktop_update.py")
# 附属数据（随包设备身份），部署时必须一起拷，否则登录不可用。
ACCOUNT_DATA_FILES = ("device-bundled.json",)


def sha(path):
    h = hashlib.sha256()
    with io.open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_state():
    if os.path.isfile(STATE):
        return json.load(io.open(STATE, encoding="utf-8"))
    return {}


def save_state(st):
    io.open(STATE, "w", encoding="utf-8").write(json.dumps(st, indent=2, ensure_ascii=False))


def frontend_names(keys):
    out = {}
    for k in keys:
        if k.endswith(".js"):
            out[k] = "app.js"
        elif k.endswith(".css"):
            out[k] = "app.css"
        else:
            out[k] = "index.html"
    return out


# exe 内嵌前端「全部前端补丁都已就位」的判据。
# 只查 hqQuals 是不够的：任何一版打过补丁的前端都含它，
# 于是上游更新后、或者我们自己加了新前端补丁后，
# is_patched() 一律返回 True，exe 永远不会被重新注入。
# 这里逐个列出当前补丁链产出的关键标记。
EXE_FULL_MARKERS = (
    b"hqQuals",              # 清晰度档位
    b"hqAccountPanel",       # 账号面板
    b"hqRefreshLibrary",     # 历史自动刷新
    b"hqPersistMerged",      # 合并结果落盘
    b"hqPruneCache",         # 关播放器回收缓存
    b"fromUid",              # 换号隔离
    b"hqAutoAdvance",        # 自动连播不弹控制栏
)

# exe 内嵌 tauri.conf.json 是否已换成本维护分支的署名。
# 上游发版会把 author 段和更新端点换回它自己的仓库/邮箱/B站地址，
# 这里单独判一次：前端标记全中就跳过重注入时，仍要把署名补回来。
REBRAND_MARKER = b"dddmiku/hongguo-desktop-releases"


def is_patched(exe):
    """exe 内嵌前端是否已含我们的全部补丁。"""
    try:
        a = Assets(exe)
        found = a.find()
        js = next((e for k, e in found.items() if k.endswith(".js")), None)
        if not js:
            return False
        raw = js["raw"]
        return all(m in raw for m in EXE_FULL_MARKERS)
    except Exception:
        return False


def is_rebranded(exe):
    """exe 内嵌 tauri.conf.json 是否已是本维护分支的署名（无原作者残留）。"""
    try:
        d = io.open(exe, "rb").read()
    except OSError:
        return False
    if any(m in d for m in rebrand_exe.OLD_OWNER_MARKERS):
        return False
    return REBRAND_MARKER in d


def backup_once(exe, backend):
    if os.path.isdir(BACKUP):
        return
    os.makedirs(os.path.join(BACKUP, "backend"), exist_ok=True)
    shutil.copy2(exe, os.path.join(BACKUP, "hongguo-desktop-companion.exe"))
    for name in PATCHED_FILES:
        src = os.path.join(backend, name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(BACKUP, "backend", name))
    print("[OK] 已保存原始备份到", BACKUP)


# 每个后端文件「全部补丁步骤都已完成」的判据。
# 只有全部命中才允许跳过；否则走一遍幂等补丁流程，把缺的步骤补上。
FULL_MARKERS = {
    "server.py": ("_hq_cache_cap", "_hq_cleanup_episode",
                  "_hq_parent", "Invalid search query", "边转边播"),
    "desktop_hls.py": ("_hq_copy_hls",),
    "desktop_hls_service.py": ("normalize_desktop_quality",),
    "desktop_encode.py": ("cancelled is not None and cancelled()",),
    "desktop_account.py": ("def sms_login",),
    "desktop_account_api.py": ("def register",),
    "safeguards.py": ("_HQ_SAFEGUARDS_CACHE_MAX",),
    "downloader.py": ("verify=True",),
}


def _fully_patched(name, text):
    """该文件的补丁是否已全部就位（缺任何一步都返回 False）。"""
    marks = FULL_MARKERS.get(name)
    if not marks:
        return False
    if not all(m in text for m in marks):
        return False
    # server.py 额外判「重复 /img 死代码已删」：只认定义次数，不认字符串，
    # 因为弱校验那行在删掉前后都存在同名片段。
    if name == "server.py" and text.count('@app.get("/img")') != 1:
        return False
    return True


def patch_live_backend(backend):
    """在安装目录的后端上直接打补丁（而不是拿仓库里的旧基线覆盖）。

    上游发新版时后端文件会一起更新。若用 base/backend（旧版本）去覆盖，
    等于把后端降级，新版改动会丢失。所以这里：
      1. 先给当前文件留一份 .orig-<哈希> 备份，便于回滚；
      2. 用「上游原版」身份对现有文件套补丁。

    返回 (patched, skipped, failed) 三个列表。
    """
    staging = os.path.join(ROOT, "_work", "live")
    shutil.rmtree(staging, ignore_errors=True)
    os.makedirs(staging, exist_ok=True)

    # 已打补丁的判据。
    # 这里只用于「判断是否需要走补丁流程」，不再等于「整文件跳过」：
    # patch_backend 的每一步各自幂等，重复执行不会重复注入。
    # 之前是整文件跳过，导致新加的补丁步骤对已打过旧补丁的安装目录永不生效
    # （实测：安装目录 server.py 一直留着重复 /img 死代码，auto_patch 报「已打补丁」）。
    MARKERS = {
        "desktop_hls.py": ("_hq_copy_hls",),
        "desktop_hls_service.py": ("normalize_desktop_quality",),
        "server.py": ("边转边播",),
        "desktop_encode.py": ("cancelled is not None and cancelled()",),
        "desktop_account.py": ("def sms_login",),
        "desktop_account_api.py": ("def register",),
        "downloader.py": ("verify=True",),
        "safeguards.py": ("_HQ_SAFEGUARDS_CACHE_MAX",),
    }

    patched, skipped, failed = [], [], []
    for name in PATCHED_FILES:
        live = os.path.join(backend, name)
        if not os.path.isfile(live):
            # 账号模块是我们新增的文件（不属于上游基线），上游发版后
            # 安装目录里可能压根没有。它们由下面的 account 步骤从
            # patches/account/ 重建，所以这里不算失败。
            if name in ACCOUNT_FILES:
                continue
            failed.append((name, "文件不存在"))
            continue

        current = io.open(live, encoding="utf-8").read()
        # 只跳过「补丁函数自己声明已完成全部步骤」的文件。
        # 其它情况一律走一遍补丁流程（幂等），保证新步骤能补上。
        if any(m in current for m in MARKERS[name]) and _fully_patched(name, current):
            skipped.append(name)
            continue

        # 1) 备份当前（上游新版）内容，名字带哈希，避免覆盖旧备份
        keep = live + ".orig-" + sha(live)[:12]
        if not os.path.isfile(keep):
            shutil.copy2(live, keep)

        # 2) 把当前内容放到暂存目录，按「上游原版」套补丁
        stage = os.path.join(staging, name)
        shutil.copy2(live, stage)
        before = sha(stage)

        try:
            if name == "desktop_hls.py":
                patch_backend.patch_hls(staging, staging)
            elif name == "desktop_hls_service.py":
                patch_backend.patch_service(staging, staging)
            elif name == "server.py":
                patch_backend.patch_server(staging, staging)
            elif name == "desktop_encode.py":
                patch_backend.patch_encode(staging, staging)
            elif name == "downloader.py":
                patch_backend.patch_downloader(staging, staging)
            elif name == "safeguards.py":
                patch_backend.patch_safeguards(staging, staging)
        except SystemExit as exc:
            failed.append((name, str(exc)))
            continue

        # 3) 产出没变化有两种可能：
        #    a) 补丁已全部就位（幂等重跑）→ 正常跳过，不是失败；
        #    b) 上游结构变了，锚点全都没命中 → 真失败，必须报出来。
        if sha(stage) == before:
            if _fully_patched(name, io.open(stage, encoding="utf-8").read()):
                skipped.append(name)
            else:
                failed.append((name, "补丁未产生变化（可能上游结构已变）"))
            continue
        shutil.copy2(stage, live)
        patched.append(name)

    # 账号同步模块（desktop_account.py / desktop_account_api.py）不在上游基线里，
    # 是我们新增的文件，patch_live_backend 的逐文件循环覆盖不到它们。
    # 之前这里漏了这一步，导致上游一发版、安装目录换回原版后，
    # 「账号/历史同步」整块功能永久消失，而且 auto_patch 还报「已打补丁」。
    # 这里补上：从 patches/account/ 重建，并在 server.py 末尾注册路由（幂等）。
    try:
        acct_patched = _patch_account_modules(backend)
        # 逐文件循环会把「内容一致所以跳过」记进 skipped；这里重建时可能又写了一遍，
        # 同一个文件不能同时出现在两个列表里，否则日志自相矛盾。
        for name in acct_patched:
            if name in skipped:
                skipped.remove(name)
        patched.extend(acct_patched)
    except SystemExit as exc:
        failed.append(("desktop_account*.py", str(exc)))
    except Exception as exc:                       # 账号模块异常不能拖垮播放补丁
        failed.append(("desktop_account*.py", "%s: %s" % (type(exc).__name__, exc)))

    return patched, skipped, failed


def _patch_account_modules(backend):
    """重建账号模块并注册路由；返回本次真正写过的文件名列表。

    幂等：模块内容一致、server.py 已注册过时不会重复写。
    """
    written = []
    for name in ACCOUNT_FILES:
        src = os.path.join(ROOT, "patches", "account", name)
        if not os.path.isfile(src):
            continue
        want = io.open(src, encoding="utf-8").read()
        live = os.path.join(backend, name)
        have = io.open(live, encoding="utf-8").read() if os.path.isfile(live) else None
        if have == want:
            continue
        if have is not None:
            keep = live + ".orig-" + sha(live)[:12]
            if not os.path.isfile(keep):
                shutil.copy2(live, keep)
        io.open(live, "w", encoding="utf-8", newline="").write(want)
        written.append(name)

    # 附属数据（随包设备身份）：全新机器上没它就登录不了，必须一起部署。
    # 不覆盖用户已有的 desktop-device.json —— 那是设备身份文件本身，
    # 不是这个模板。
    for name in ACCOUNT_DATA_FILES:
        src = os.path.join(ROOT, "patches", "account", name)
        if not os.path.isfile(src):
            continue
        live = os.path.join(backend, name)
        want = io.open(src, encoding="utf-8").read()
        have = io.open(live, encoding="utf-8").read() if os.path.isfile(live) else None
        if have == want:
            continue
        io.open(live, "w", encoding="utf-8", newline="").write(want)
        written.append(name)

    # server.py 末尾注册路由（patch_account_backend 自带幂等判断）
    live_server = os.path.join(backend, "server.py")
    if os.path.isfile(live_server):
        before = sha(live_server)
        patch_backend.patch_account_backend(backend, backend)
        if sha(live_server) != before:
            written.append("server.py(注册账号路由)")
    return written


def repair_backend():
    """校验并修复安装目录的后端补丁（幂等，随时可跑）。"""
    if not os.path.isdir(BACKEND):
        print("[FAIL] 找不到后端目录:", BACKEND)
        return 1
    patched, skipped, failed = patch_live_backend(BACKEND)
    for name in patched:
        print("   [OK] 后端已注入", name)
    for name in skipped:
        print("   [=] 后端已含补丁，跳过", name)
    for name, why in failed:
        print("   [!] 后端未注入", name, "-", why)

    # 清掉 pyc，避免加载到旧字节码
    import glob as _glob
    for pyc in _glob.glob(os.path.join(BACKEND, "__pycache__", "*.pyc")):
        try:
            os.remove(pyc)
        except OSError:
            pass
    return 0 if not failed else 1


def apply_patches():
    if not os.path.isfile(EXE):
        print("[FAIL] 找不到 exe:", EXE)
        return 1

    st = load_state()
    cur = sha(EXE)

    # 作者信息单独判一次：上游发版会把内嵌 tauri.conf.json 的署名换回它自己的，
    # 而前端标记可能仍然是全的（我们只改了 .rdata 字面量，没动资源）。
    # 这种情况不能走「无需处理」，否则署名永远补不回来。
    if is_patched(EXE) and not is_rebranded(EXE):
        print("[*] 检测到内嵌署名仍是上游原版，重打标…")
        if rebrand_exe.rebrand_file(EXE) != 0:
            print("[FAIL] 作者信息重打标失败")
            return 1
        st["patched_exe_sha256"] = sha(EXE)
        save_state(st)
        print("[=] 署名已更新；检查后端…")
        return repair_backend()

    # exe 已是补丁版且记录一致：仍然校验后端（上游更新可能只换后端）
    if st.get("patched_exe_sha256") == cur and is_patched(EXE):
        print("[=] exe 已是补丁版，检查后端…")
        rc = repair_backend()
        print("[OK] 无需处理" if rc == 0 else "[!] 后端存在未注入项")
        return rc

    if is_patched(EXE):
        st["patched_exe_sha256"] = cur
        save_state(st)
        print("[=] exe 已含补丁，仅更新记录；检查后端…")
        return repair_backend()

    print("[*] 检测到未打补丁的 exe，开始重新注入…")
    backup_once(EXE, BACKEND)

    # 1) 从 exe 里取出上游原版前端
    a = Assets(EXE)
    found = a.find()
    if not found:
        print("[FAIL] exe 内未找到前端资源")
        return 1
    work = os.path.join(ROOT, "_work")
    os.makedirs(work, exist_ok=True)
    names = frontend_names(found.keys())
    orig_dir = os.path.join(work, "orig")
    os.makedirs(orig_dir, exist_ok=True)
    for key, entry in found.items():
        io.open(os.path.join(orig_dir, names[key]), "wb").write(entry["raw"])

    # 1b) 兜底：exe 里的前端可能已经是「旧版补丁」而不是上游原版
    #     （例如我们自己加了新前端补丁、上游版本没变）。
    #     前端补丁的探针假定输入是未打补丁的 js，对已打补丁的 js 会直接失败，
    #     于是整条 apply 路径卡死、永远升不上去。
    #     这时改用仓库里保存的上游原版（base/frontend）当输入。
    src_js = os.path.join(orig_dir, "app.js")
    raw_js = io.open(src_js, encoding="utf-8").read()
    if "hqQuals" in raw_js or "hqAccountPanel" in raw_js:
        base_js = os.path.join(ROOT, "base", "frontend", "app.js")
        if not os.path.isfile(base_js):
            print("[FAIL] exe 内嵌前端已是旧版补丁，且找不到 base/frontend/app.js 作为原版")
            print("       请先手工准备上游原版（base/frontend），再重跑 apply")
            return 1
        print("[*] exe 内嵌前端已是旧版补丁，改用 base/frontend 作为上游原版输入")
        shutil.copy2(base_js, src_js)
        for extra in ("app.css", "index.html"):
            cand = os.path.join(ROOT, "base", "frontend", extra)
            if os.path.isfile(cand):
                shutil.copy2(cand, os.path.join(orig_dir, extra))
        raw_js = io.open(src_js, encoding="utf-8").read()

    # 2) 套用前端补丁
    dst_js = os.path.join(work, "app.js")
    text = raw_js
    patcher = patch_frontend.patch(text)
    # 账号同步补丁（验证码登录 / 进度 / 收藏 / 换号隔离）
    try:
        import patch_account
        acc_text, acc_log = patch_account.patch(patcher.s)
        patcher.s = acc_text
        patcher.log.extend(acc_log)
        # 账号面板样式也要跟着走，否则面板没有专属样式
        css_path = os.path.join(orig_dir, "app.css")
        if os.path.isfile(css_path):
            body = io.open(css_path, encoding="utf-8").read()
            body, css_log = patch_account.patch_css(body)
            io.open(css_path, "w", encoding="utf-8", newline="").write(body)
            patcher.log.extend(css_log)
    except ImportError:
        pass
    io.open(dst_js, "w", encoding="utf-8", newline="").write(patcher.s)
    for line in patcher.log:
        print("   ", line)

    # 3) 压回 exe
    # 必须带上容量记录：前端补丁会让 js 变大（本轮从 273729 涨到 276690），
    # 而 exe 里每个资源的压缩容量是固定的。repack.py 会把扩容后的容量写进
    # <exe>.blobcaps.json，这里不加载它就会直接失败：
    #   [FAIL] 压缩后 276690 > 容量 273729（需扩容，当前不支持）
    a2 = Assets(EXE, load_capacities(EXE))
    found2 = a2.find()
    keys2 = list(found2.keys())
    for key, entry in found2.items():
        path = dst_js if names[key] == "app.js" else os.path.join(orig_dir, names[key])
        raw = io.open(path, "rb").read()
        if key.endswith(".html"):
            # 上游发版会换哈希文件名；必须把引用对齐到 exe 内真实键名，
            # 否则脚本 404、界面全白。
            raw = align_index_html(raw, keys2)
        old_len, new_len, _ = a2.replace(entry, raw)
        print(f"   [OK] {key}: {old_len} -> {new_len}")
    tmp_exe = EXE + ".patched"
    a2.save(tmp_exe)
    # 容量记录要跟着新 exe 一起落地，否则下次替换又会被旧容量卡住。
    save_capacities(tmp_exe, a2.capacities)
    if os.path.isfile(capacity_path(EXE)):
        shutil.copy2(capacity_path(EXE), EXE + ".blobcaps.json.bak")

    # 3b) 作者信息重打标（内嵌 tauri.conf.json 里的 author 段与更新端点）。
    #     上游发版会把这两处换回它自己的仓库/邮箱/B站地址，前端资源压回后
    #     这里再统一覆盖一遍，保证「重新注入」出来的也是本维护分支的署名。
    #     必须在 save 之后做：它改的是 exe 的 .rdata 字面量，与资源无关。
    if rebrand_exe.rebrand_file(tmp_exe) != 0:
        print("[FAIL] 作者信息重打标失败")
        return 1

    # 4) 后端补丁：在安装目录的现有文件上直接打
    repair_backend()

    # 5) 原子替换 exe（需要应用已退出）
    try:
        os.replace(tmp_exe, EXE)
    except OSError as exc:
        print("[FAIL] 替换 exe 失败（应用可能正在运行）:", exc)
        return 1

    st["patched_exe_sha256"] = sha(EXE)
    save_state(st)
    print("[OK] 补丁已重新注入")
    return 0


def status():
    st = load_state()
    print("exe        :", EXE)
    print("存在       :", os.path.isfile(EXE))
    if os.path.isfile(EXE):
        cur = sha(EXE)
        print("当前哈希   :", cur)
        print("已打补丁   :", is_patched(EXE))
        print("记录哈希   :", st.get("patched_exe_sha256"))
    print("应用目录   :", APP_DIR)
    return 0


RUN_KEY = r"Software\\Microsoft\\Windows\\CurrentVersion\\Run"
RUN_VALUE = "HongguoDesktopPatch"


def _launcher_cmd():
    script = os.path.join(ROOT, "tools", "auto_patch.py")
    log = os.path.join(ROOT, "auto_patch.log")
    # 用 pythonw 避免登录时闪出控制台窗口
    pyw = sys.executable.replace("python.exe", "pythonw.exe")
    if not os.path.isfile(pyw):
        pyw = sys.executable
    # Run 键不经过 shell，重定向需交给 cmd /c 执行
    return f'cmd /c ""{pyw}" "{script}" apply >> "{log}" 2>&1"'


def install_task():
    """注册登录自启动：写 HKCU\\...\\Run（当前用户，无需管理员权限）。"""
    import winreg
    cmd = _launcher_cmd()
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
        winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, cmd)
    print("[OK] 已注册登录自启动（HKCU Run）")
    print("     命令:", cmd)
    print("     日志:", os.path.join(ROOT, "auto_patch.log"))
    return 0


def uninstall_task():
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            winreg.DeleteValue(k, RUN_VALUE)
        print("[OK] 已取消登录自启动")
    except FileNotFoundError:
        print("[=] 未发现自启动项")
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        return status()
    if cmd == "apply":
        return apply_patches()
    if cmd == "install":
        return install_task()
    if cmd == "uninstall":
        return uninstall_task()
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
