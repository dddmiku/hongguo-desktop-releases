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


def apply_patches():
    if not os.path.isfile(EXE):
        print("[FAIL] 找不到 exe:", EXE)
        return 1

    st = load_state()
    cur = sha(EXE)
    if st.get("patched_exe_sha256") == cur and is_patched(EXE):
        print("[=] 当前已是补丁版本，无需处理")
        return 0

    if is_patched(EXE):
        st["patched_exe_sha256"] = cur
        save_state(st)
        print("[=] exe 已含补丁，仅更新记录")
        return 0

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

    # 4) 后端补丁
    # 后端补丁以上游原始副本为输入（仓库内 base/backend，可随版本更新）。
    up_backend = os.path.join(ROOT, "base", "backend")
    if not os.path.isdir(up_backend):
        print("   [!] 缺少 base/backend 上游副本，跳过后端补丁")
    else:
        for name in PATCHED_FILES:
            patch_backend.patch_hls(up_backend, BACKEND) if name == "desktop_hls.py" else None
            patch_backend.patch_service(up_backend, BACKEND) if name == "desktop_hls_service.py" else None
            patch_backend.patch_server(up_backend, BACKEND) if name == "server.py" else None
            patch_backend.patch_encode(up_backend, BACKEND) if name == "desktop_encode.py" else None
        # 其余后端文件（未打补丁的）从上游副本补齐，避免版本混杂
        for name in os.listdir(up_backend):
            if name.endswith(".py") or name.endswith(".txt"):
                dst = os.path.join(BACKEND, name)
                if not os.path.isfile(dst):
                    io.open(dst, "w", encoding="utf-8", newline="").write(
                        io.open(os.path.join(up_backend, name), encoding="utf-8").read())
        import glob as _glob
        for pyc in _glob.glob(os.path.join(BACKEND, "__pycache__", "*.pyc")):
            try:
                os.remove(pyc)
            except OSError:
                pass

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
