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
from tauri_assets import Assets          # noqa: E402
import patch_frontend                    # noqa: E402
import patch_backend                     # noqa: E402
from repack import align_index_html      # noqa: E402

APP_DIR = os.environ.get("HONGGUO_APP_DIR", r"d:\Users\dddmiku\AppData\Local\红果免费短剧")
EXE = os.path.join(APP_DIR, "hongguo-desktop-companion.exe")
BACKEND = os.path.join(APP_DIR, "backend")
STATE = os.path.join(ROOT, "state.json")
BACKUP = os.path.join(ROOT, "_backup")
TASK = "HongguoDesktopPatch"

# 补丁涉及的全部后端文件；部署与备份都必须覆盖它们，缺一个就会「媒体准备失败」。
PATCHED_FILES = ("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py")


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


def is_patched(exe):
    """exe 内嵌前端是否已含我们的补丁。"""
    try:
        a = Assets(exe)
        found = a.find()
        js = next((e for k, e in found.items() if k.endswith(".js")), None)
        if not js:
            return False
        return b"hqQuals" in js["raw"]
    except Exception:
        return False


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

    # 已打补丁的判据：这些标记任一存在即认为该文件已处理过
    MARKERS = {
        "desktop_hls.py": ("_hq_copy_hls",),
        "desktop_hls_service.py": ("normalize_desktop_quality",),
        "server.py": ("encode_h264(decrypted",),
        "desktop_encode.py": ("cancelled is not None and cancelled()",),
    }

    patched, skipped, failed = [], [], []
    for name in PATCHED_FILES:
        live = os.path.join(backend, name)
        if not os.path.isfile(live):
            failed.append((name, "文件不存在"))
            continue

        current = io.open(live, encoding="utf-8").read()
        if any(m in current for m in MARKERS[name]):
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
        except SystemExit as exc:
            failed.append((name, str(exc)))
            continue

        # 3) 只有确认产出真的变化了才写回，避免把空结果覆盖上去
        if sha(stage) == before:
            failed.append((name, "补丁未产生变化（可能上游结构已变）"))
            continue
        shutil.copy2(stage, live)
        patched.append(name)
    return patched, skipped, failed


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

    # 2) 套用前端补丁
    src_js = os.path.join(orig_dir, "app.js")
    dst_js = os.path.join(work, "app.js")
    text = io.open(src_js, encoding="utf-8").read()
    patcher = patch_frontend.patch(text)
    io.open(dst_js, "w", encoding="utf-8", newline="").write(patcher.s)
    for line in patcher.log:
        print("   ", line)

    # 3) 压回 exe
    a2 = Assets(EXE)
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
