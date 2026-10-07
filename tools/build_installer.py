# -*- coding: utf-8 -*-
"""打安装包（可复现：从上游原版 + 我们的补丁重建载荷）。

为什么不直接打包本机安装目录：
    安装目录里有运行时垃圾（.hq-bak-*、__pycache__、hls-work、*.orig-*），
    还可能混进本机特有的东西。分发包必须只含「上游原版 + 我们的补丁」。

载荷来源（两级）：
    1. 上游原版安装目录（`--pristine`，默认 `_v109/extracted`）
       —— 它含有本仓库不跟踪的大件：python/ jre/ sign/ capture/ frida/
          以及各类 *.json 配置。
    2. 本仓库 `src/backend/` 覆盖上去
       —— 补丁后的 server.py / desktop_hls*.py / downloader.py / safeguards.py，
          以及我们新增的 desktop_account*.py 与固定版本的 requirements。

    `src/backend/` 比上游多出的文件 = 我们新增的（直接拷）；
    两边都有的 = 我们改过的（用 src 覆盖）；
    上游独有的 = 原样保留（python/、jre/ 等）。

exe 来源：`dist/hongguo-desktop-companion.exe`（由 tools/repack.py +
tools/rebrand_exe.py 产出，已含补丁与本分支署名）。

用法:
    python tools/build_installer.py                  # 用默认路径
    python tools/build_installer.py --pristine <目录> --exe <exe> --out <安装包>
    python tools/build_installer.py --check          # 只校验载荷，不打包
"""
import argparse
import hashlib
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

DEFAULT_PRISTINE = os.path.join(ROOT, "_v109", "extracted")
DEFAULT_EXE = os.path.join(ROOT, "dist", "hongguo-desktop-companion.exe")
DEFAULT_OUT = os.path.join(ROOT, "dist", "hongguo-1.0.9-setup.exe")
INST = os.path.join(ROOT, "installer")
PAYLOAD = os.path.join(INST, "payload")
WEBVIEW2 = os.path.join(INST, "webview2")
STAGE = os.path.join(ROOT, "_work", "stage")

# 上游原版安装目录里，不属于程序本体的东西（分发包不带）。
#   注意：这里**不排除** desktop_account*.py —— 那是我们新增的，由 src 覆盖进来。
EXCLUDE_DIRS = (".hq-bak", "__pycache__", "hls-work")
EXCLUDE_FILES = (".orig-", ".blobcaps.json.bak")


def sha(path):
    h = hashlib.sha256()
    with io.open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ignored(name):
    return (any(name.startswith(d) for d in EXCLUDE_DIRS)
            or any(x in name for x in EXCLUDE_FILES))


def build_payload(pristine, exe, quiet=False):
    """重建 _work/stage：上游原版 + src/backend 覆盖。返回统计。"""
    if not os.path.isdir(pristine):
        raise SystemExit("[FAIL] 找不到上游原版目录: %s" % pristine)
    if not os.path.isfile(exe):
        raise SystemExit("[FAIL] 找不到 exe: %s" % exe)

    if os.path.isdir(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(STAGE)

    # 1) 上游原版：backend + licenses
    for name in ("backend", "licenses"):
        src = os.path.join(pristine, name)
        if not os.path.isdir(src):
            raise SystemExit("[FAIL] 上游原版缺少 %s" % name)
        dst = os.path.join(STAGE, name)
        for root, dirs, files in os.walk(src):
            dirs[:] = [d for d in dirs if not ignored(d)]
            rel = os.path.relpath(root, src)
            target = os.path.join(dst, rel) if rel != "." else dst
            os.makedirs(target, exist_ok=True)
            for f in files:
                if ignored(f):
                    continue
                shutil.copy2(os.path.join(root, f), os.path.join(target, f))

    # 2) src/backend 覆盖（补丁后的文件 + 我们新增的模块）
    srcb = os.path.join(ROOT, "src", "backend")
    added = replaced = 0
    for root, dirs, files in os.walk(srcb):
        dirs[:] = [d for d in dirs if d != "__pycache__"]
        rel = os.path.relpath(root, srcb)
        target = os.path.join(STAGE, "backend", rel) if rel != "." else \
            os.path.join(STAGE, "backend")
        os.makedirs(target, exist_ok=True)
        for f in files:
            dst = os.path.join(target, f)
            if os.path.isfile(dst):
                if sha(dst) != sha(os.path.join(root, f)):
                    replaced += 1
            else:
                added += 1
            shutil.copy2(os.path.join(root, f), dst)

    # 3) exe + 容量旁挂文件
    shutil.copy2(exe, os.path.join(STAGE, "hongguo-desktop-companion.exe"))
    caps = exe + ".blobcaps.json"
    if os.path.isfile(caps):
        shutil.copy2(caps, os.path.join(
            STAGE, "hongguo-desktop-companion.exe.blobcaps.json"))

    # 4) 卸载程序：**不**从上游原版拷（那是上游的卸载器，会去连它的更新地址）。
    #    由 NSIS 的 WriteUninstaller 生成我们自己的。
    stale = os.path.join(STAGE, "uninstall.exe")
    if os.path.isfile(stale):
        os.remove(stale)

    n = sum(len(fs) for _, _, fs in os.walk(STAGE))
    if not quiet:
        print("[OK] 载荷已重建: %s" % STAGE)
        print("     上游原版: %s" % pristine)
        print("     src 覆盖: 替换 %d 个, 新增 %d 个" % (replaced, added))
        print("     文件总数: %d" % n)
    return {"files": n, "replaced": replaced, "added": added}


def sync_to_installer():
    """把 _work/stage 同步到 installer/payload。"""
    if os.path.isdir(PAYLOAD):
        shutil.rmtree(PAYLOAD)
    shutil.copytree(STAGE, PAYLOAD)
    print("[OK] 已同步到 installer/payload")


def find_makensis():
    cands = [
        os.environ.get("MAKENSIS"),
        os.path.join(ROOT, "_work", "nsis", "nsis-3.13", "makensis.exe"),
        r"C:\Program Files (x86)\NSIS\makensis.exe",
        r"C:\Program Files\NSIS\makensis.exe",
        shutil.which("makensis"),
    ]
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pristine", default=DEFAULT_PRISTINE)
    ap.add_argument("--exe", default=DEFAULT_EXE)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--check", action="store_true",
                    help="只重建并校验载荷，不调用 makensis")
    a = ap.parse_args()

    stats = build_payload(a.pristine, a.exe)

    # 校验：补丁标记必须在载荷里
    checks = []
    for f, marks in (("server.py", ("_hq_cache_path", "encode_h264(decrypted",
                                   "Invalid search query")),
                     ("desktop_account.py", ("_history_all", "use_soft_delete")),
                     ("desktop_account_api.py", ("desktop",)),
                     ("desktop_hls.py", ("_hq_copy_hls",)),
                     ("safeguards.py", ("_HQ_SAFEGUARDS_CACHE_MAX",))):
        p = os.path.join(STAGE, "backend", f)
        if not os.path.isfile(p):
            checks.append((f, False, "缺失"))
            continue
        body = io.open(p, encoding="utf-8", errors="replace").read()
        miss = [m for m in marks if m not in body]
        checks.append((f, not miss, "缺: " + ", ".join(miss) if miss else "OK"))

    # 上游独有的文件必须还在（python/ jre/ 这些大件）
    for d in ("python", "jre", "sign"):
        p = os.path.join(STAGE, "backend", d)
        checks.append(("backend/" + d, os.path.isdir(p),
                       "OK" if os.path.isdir(p) else "缺失"))

    # exe 署名
    import rebrand_exe
    d = io.open(os.path.join(STAGE, "hongguo-desktop-companion.exe"), "rb").read()
    left = [m.decode() for m in rebrand_exe.OLD_OWNER_MARKERS if m in d]
    checks.append(("exe 署名", not left, "残留: %s" % left if left else "OK"))

    bad = [(n, det) for n, ok, det in checks if not ok]
    for n, ok, det in checks:
        print("  %s %-28s %s" % ("[OK]" if ok else "[!!]", n, det))
    if bad:
        raise SystemExit("[FAIL] 载荷校验未通过")

    if a.check:
        print("[OK] 校验通过（未打包）")
        return 0

    sync_to_installer()
    mk = find_makensis()
    if not mk:
        raise SystemExit("[FAIL] 找不到 makensis.exe（设 MAKENSIS 环境变量，"
                         "或把 NSIS 放到 _work/nsis/）")
    # MSYS 会把 /V2 之类的参数改写成 Windows 路径，用短横线形式并关掉路径转换。
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    r = subprocess.run([mk, "-V2", "hongguo.nsi"], cwd=INST, env=env)
    if r.returncode != 0:
        raise SystemExit("[FAIL] makensis 退出码 %d" % r.returncode)
    if not os.path.isfile(a.out):
        raise SystemExit("[FAIL] 未生成 %s" % a.out)
    size = os.path.getsize(a.out)
    print("[OK] 安装包: %s" % a.out)
    print("     大小: %.1f MB" % (size / 1048576.0))
    print("     sha256: %s" % sha(a.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
