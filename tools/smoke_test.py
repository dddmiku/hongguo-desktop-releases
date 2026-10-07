#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""冒烟测试：在部署前抓住「补丁链改坏」这类回归。

为什么需要它：
  2026-10-07 一次改动把清扫函数放在文件末尾、调用放在中部，
  导致 server.py 一导入就 NameError，后端直接起不来，
  但补丁脚本自己「成功」退出，直到用户发现软件打不开。
  这个脚本把那次事故变成一条可自动检查的断言。

检查项（全部必须通过，任一失败即非零退出）：
  1. 后端每个 .py 都能通过语法编译
  2. server.py 能真正 import（带最小环境变量，抓 NameError/ImportError）
  3. 前端 app.js 能通过 node --check
  4. 关键注入点存在（清扫、search 校验、账号路由、前端钩子）
  5. 没有重复注册的路由
  6. 本地服务只绑 127.0.0.1

用法：
  python tools/smoke_test.py            # 检查 src/
  python tools/smoke_test.py --live     # 同时检查安装目录
"""
import hashlib
import importlib.util
import io
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BACKEND = os.path.join(ROOT, "src", "backend")
FRONTEND = os.path.join(ROOT, "src", "frontend")


def _live_app_dir():
    """安装目录：优先环境变量，其次从卸载注册项读，最后退回历史默认值。

    以前这里是写死的 D 盘路径；换成安装包之后默认位置变成
    %LOCALAPPDATA%\\Programs\\红果免费短剧，写死的路径会指向一个不存在的目录，
    于是 --live 永远报「未找到安装目录」，看着像通过其实什么都没检查。
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
                    loc = winreg.QueryValueEx(k, "InstallLocation")[0]
                loc = str(loc).strip().strip('"')
                if loc:
                    return loc
            except OSError:
                continue
    except ImportError:
        pass
    return os.path.join(os.environ.get("LOCALAPPDATA", ""), "Programs", "红果免费短剧")


LIVE_DIR = _live_app_dir()
LIVE = os.path.join(LIVE_DIR, "backend")
LIVE_PY = os.path.join(LIVE_DIR, "backend", "python", "python.exe")

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print("  %-4s %-42s %s" % ("[OK]" if ok else "[!!]", name, detail))
    return ok


def section(title):
    print()
    print("=== %s ===" % title)


def backend_files():
    out = []
    for name in sorted(os.listdir(BACKEND)):
        if name.endswith(".py"):
            out.append(os.path.join(BACKEND, name))
    return out


def test_syntax():
    section("1. 语法编译")
    bad = []
    for path in backend_files():
        try:
            src = io.open(path, encoding="utf-8").read()
            compile(src, path, "exec")
        except SyntaxError as e:
            bad.append("%s:%s %s" % (os.path.basename(path), e.lineno, e.msg))
    check("后端 %d 个 .py 语法" % len(backend_files()), not bad,
          "" if not bad else "失败: " + "; ".join(bad[:3]))


def test_import_server():
    section("2. server.py 能否 import（抓 NameError 类事故）")
    py = LIVE_PY if os.path.isfile(LIVE_PY) else sys.executable
    if not os.path.isfile(py):
        check("跳过：找不到可用的 python", True, py)
        return
    env = dict(os.environ)
    env["HONGGUO_CONTENT_CONFIG"] = os.path.join(BACKEND, "guest-config.json")
    env["HONGGUO_SESSION_API_KEY"] = "a" * 64
    work = tempfile.mkdtemp(prefix="hqsmoke-")
    env["HONGGUO_HLS_WORK_DIR"] = work
    env["HONGGUO_STREAM_CACHE"] = os.path.join(work, "cache")
    cfg = env["HONGGUO_CONTENT_CONFIG"]
    if not os.path.isfile(cfg):
        # 仓库里没有 guest-config.json 时造一个最小可用的
        cfg = os.path.join(work, "guest-config.json")
        io.open(cfg, "w", encoding="utf-8").write(
            '{"api_host":"api5-normal-sinfonlinea.fqnovel.com",'
            '"base_query":{"aid":"8662","app_name":"novelread"},'
            '"session_headers":{"user-agent":"smoke/1.0"}}')
        env["HONGGUO_CONTENT_CONFIG"] = cfg
    code = (
        "import sys, traceback\n"
        "sys.path.insert(0, %r)\n"
        # 有些模块（hongguo / offline_dl 等）只在安装目录里，
        # 源码树没有副本；补进搜索路径才能复现真实启动。
        "sys.path.append(%r)\n"
        # server.py 自己会插 <自身目录>/frida 来找 offline_decrypt。
        # 源码树里没有 frida/（那是安装目录的一部分），所以从 src 导入时
        # 这一句会落到 src/backend/frida（不存在）→ ModuleNotFoundError。
        # 真实启动时 server.py 是从安装目录加载的，不会走到这个分支。
        # 这里把安装目录的 frida/ 也补进路径，才不会误报。
        "sys.path.append(%r)\n"
        "try:\n"
        "    import server\n"
        "    print('IMPORT_OK')\n"
        "except Exception:\n"
        "    traceback.print_exc()\n"
        "    print('IMPORT_FAIL')\n" % (BACKEND, LIVE, os.path.join(LIVE, "frida"))
    )
    proc = subprocess.run([py, "-c", code], capture_output=True, env=env, timeout=180)
    out = (proc.stdout or b"").decode("utf-8", "replace")
    err = (proc.stderr or b"").decode("utf-8", "replace")
    ok = "IMPORT_OK" in out
    tail = ""
    if not ok:
        text = (out + err).strip().splitlines()
        tail = " | ".join(text[-3:])[:220]
    check("server.py import", ok, tail)


def test_frontend_syntax():
    section("3. 前端语法")
    app = os.path.join(FRONTEND, "app.js")
    if not os.path.isfile(app):
        check("跳过：无 src/frontend/app.js", True)
        return
    node = None
    for cand in ("node", "node.exe"):
        try:
            subprocess.run([cand, "--version"], capture_output=True, timeout=20)
            node = cand
            break
        except Exception:
            pass
    if not node:
        check("跳过：未安装 node", True)
        return
    proc = subprocess.run([node, "--check", app], capture_output=True, timeout=120)
    tail = (proc.stderr or b"").decode("utf-8", "replace").strip().splitlines()
    check("app.js 语法", proc.returncode == 0, " | ".join(tail[-2:])[:200])


def test_markers():
    section("4. 关键注入点")
    s = io.open(os.path.join(BACKEND, "server.py"), encoding="utf-8").read()
    check("残留 HLS 目录清扫", "_hq_parent" in s and "desktop-hls-" in s)
    check("search 输入校验", "Invalid search query" in s)
    check("账号路由已注册", "desktop_account_api" in s or "desktop/account" in s)
    check("看过自动清理接口", "_hq_cleanup_episode" in s)
    # 缓存路径穿越防护：vid 来自调用方，必须落在 STREAM_CACHE 之内。
    check("缓存路径包含校验", "_hq_cache_path" in s and "Cache path escapes" in s)
    check("残留 .partial 清扫", "_hq_sweep_partial" in s)
    check("封面缓存淘汰", "_hq_prune_poster_cache" in s)
    check("免鉴权名单已收敛", '"/docs"' not in s.split("_EXEMPT")[1].split("\n")[0])
    check("封面重定向逐跳校验", "_hq_img_fetch" in s)
    dl = os.path.join(BACKEND, "downloader.py")
    if os.path.isfile(dl):
        check("downloader 无 verify=False", "verify=False" not in io.open(dl, encoding="utf-8").read())
    req = os.path.join(BACKEND, "requirements-windows.txt")
    if os.path.isfile(req):
        body = [l.strip() for l in io.open(req, encoding="utf-8")
                if l.strip() and not l.strip().startswith("#")]
        loose = [l for l in body if "==" not in l]
        check("依赖版本已固定", not loose, "" if not loose else "未固定: " + ", ".join(loose))
    app = os.path.join(FRONTEND, "app.js")
    if os.path.isfile(app):
        f = io.open(app, encoding="utf-8", errors="replace").read()
        check("前端 账号面板", "hqAccountPanel" in f)
        check("前端 历史自动刷新", "hqRefreshLibrary" in f)
        check("前端 换号隔离", "fromUid" in f or "guoban:acctUid" in f)
        check("前端 进度取最新", "lastEpisode:cep" in f or "lastEpisode:cep" in f.replace(" ", ""))
        check("前端 合并结果落盘", "hqPersistMerged" in f)
        check("前端 关播放器回收缓存", "hqPruneCache" in f)
        # 作者信息：本维护分支自己的仓库，原作者的仓库/邮箱/B站都不该出现。
        check("前端 署名已换成本仓库",
              "github.com/dddmiku/hongguo-desktop-releases" in f)
        check("前端 无原作者残留",
              not any(m in f for m in ("waligoraamodio288-rgb", "42310326",
                                       "WaligoraAmodio288", "渠道有数")))


def test_rebrand():
    section("4c. exe 署名重打标（内嵌 tauri.conf.json）")
    spec = importlib.util.spec_from_file_location(
        "rebrand_exe", os.path.join(ROOT, "tools", "rebrand_exe.py"))
    if spec is None:
        check("跳过：找不到 rebrand_exe.py", True)
        return
    rb = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(rb)
    except Exception as e:
        check("rebrand_exe 可导入", False, "%s: %s" % (type(e).__name__, e))
        return
    check("rebrand_exe 可导入", True)
    # 用一个「带原作者署名」的合成 exe 验证等长覆盖逻辑，不依赖真实 exe。
    import struct
    import tempfile
    old = rb.build_author_json("https://github.com/waligoraamodio288-rgb/"
                               "hongguo-desktop-releases", 794)
    work = tempfile.mkdtemp(prefix="hqrb-")
    fake = os.path.join(work, "fake.bin")
    # 最小 PE：DOS 头 + PE 签名 + 1 个 .rdata 节，把 JSON 放进节里。
    body = bytearray(b"\x00" * 0x400)
    body[0:2] = b"MZ"
    pe_off = 0x80
    body[pe_off:pe_off + 4] = b"PE\x00\x00"
    struct.pack_into("<H", body, pe_off + 6, 1)          # 1 个节
    struct.pack_into("<H", body, pe_off + 20, 0xF0)      # opt header 大小
    opt = pe_off + 24
    struct.pack_into("<H", body, opt, 0x20b)             # PE32+
    sec_fo = 0x200
    sec_va = 0x1000
    json_fo = 0x240
    body[json_fo:json_fo + len(old)] = old
    # 节头
    o = opt + 0xF0
    body[o:o + 8] = b".rdata\x00\x00"
    struct.pack_into("<IIII", body, o + 8, 0x2000, sec_va, 0x2000, sec_fo)
    struct.pack_into("<Q", body, opt + 24, 0x140000000)  # image base
    # 长度立即数：lea rax,[rip+d] ; mov [rbp+0x90], imm32
    lea = sec_fo + (json_fo - sec_fo) - 24
    ib = 0x140000000
    json_va = ib + sec_va + (json_fo - sec_fo)
    body[lea] = 0x48
    body[lea + 1] = 0x8D
    body[lea + 2] = 0x05
    lea_va = ib + sec_va + (lea - sec_fo)
    struct.pack_into("<i", body, lea + 3, json_va - (lea_va + 7))
    body[lea + 7] = 0x48
    body[lea + 8] = 0xC7
    body[lea + 9] = 0x85
    struct.pack_into("<i", body, lea + 10, 0x90)
    struct.pack_into("<i", body, lea + 14, len(old))
    io.open(fake, "wb").write(bytes(body))
    try:
        rc = rb.rebrand_file(fake, repo="https://github.com/dddmiku/hongguo-desktop-releases")
    except SystemExit as e:
        check("合成 exe 重打标", False, str(e))
        return
    got = io.open(fake, "rb").read()
    check("合成 exe 重打标返回 0", rc == 0, "rc=%d" % rc)
    check("合成 exe 无原作者残留",
          not any(m in got for m in rb.OLD_OWNER_MARKERS))
    check("合成 exe 长度不变", len(got) == len(body))


def test_patch_idempotency():
    section("4b. 补丁链幂等（防止新步骤对已部署文件不生效）")
    # 对「已含全部步骤」的 server.py 再跑一次 patch_server，文件必须不变。
    import importlib.util
    import tempfile
    spec = importlib.util.spec_from_file_location(
        "patch_backend", os.path.join(ROOT, "patches", "patch_backend.py"))
    if spec is None:
        check("跳过：找不到 patch_backend.py", True)
        return
    pb = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(pb)
    except Exception as e:
        check("patch_backend 可导入", False, "%s: %s" % (type(e).__name__, e))
        return
    check("patch_backend 可导入", True)
    work = tempfile.mkdtemp(prefix="hqidem-")
    for name in ("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py"):
        src = os.path.join(BACKEND, name)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(work, name))
    before = {n: hashlib.sha256(io.open(os.path.join(work, n), "rb").read()).hexdigest()
              for n in os.listdir(work)}
    try:
        pb.patch_hls(work, work)
        pb.patch_service(work, work)
        pb.patch_server(work, work)
        pb.patch_encode(work, work)
    except SystemExit as e:
        check("补丁重跑不报错", False, str(e)[:160])
        return
    check("补丁重跑不报错", True)
    after = {n: hashlib.sha256(io.open(os.path.join(work, n), "rb").read()).hexdigest()
             for n in os.listdir(work)}
    changed = [n for n in before if before[n] != after.get(n)]
    check("对已打补丁的文件重跑后不变", not changed,
          "" if not changed else "被改动: " + ", ".join(changed))
    # auto_patch 的「全步骤就位」判据必须认为 src 是完整的
    spec2 = importlib.util.spec_from_file_location(
        "auto_patch", os.path.join(ROOT, "tools", "auto_patch.py"))
    if spec2 is not None:
        ap = importlib.util.module_from_spec(spec2)
        try:
            spec2.loader.exec_module(ap)
            body = io.open(os.path.join(BACKEND, "server.py"), encoding="utf-8").read()
            check("auto_patch 认可 src 为完整补丁版", ap._fully_patched("server.py", body))
        except Exception as e:
            check("auto_patch 可导入", False, "%s: %s" % (type(e).__name__, e))


def test_routes():
    section("5. 路由唯一性")
    s = io.open(os.path.join(BACKEND, "server.py"), encoding="utf-8").read()
    routes = {}
    for m in re.finditer(r'@app\.(get|post|put|delete)\("([^"]+)"', s):
        routes.setdefault((m.group(1), m.group(2)), []).append(
            s[:m.start()].count("\n") + 1)
    dup = {k: v for k, v in routes.items() if len(v) > 1}
    check("无重复路由", not dup,
          "" if not dup else "重复: " + ", ".join("%s %s @%s" % (k[0], k[1], v) for k, v in dup.items()))


def test_bind_scope():
    section("6. 本地服务绑定范围")
    s = io.open(os.path.join(BACKEND, "server.py"), encoding="utf-8").read()
    m = re.search(r'uvicorn\.run\(app,\s*host=os\.environ\.get\("BIND_HOST",\s*"([^"]+)"\)', s)
    host = m.group(1) if m else "?"
    check("默认绑定 127.0.0.1", host == "127.0.0.1", "实际默认=%s" % host)


def test_auto_patch_ready():
    """自动重新注入的前置条件必须成立（否则上游发版后补丁会永久停在旧版）。

    2026-10-07 实测：apply 这条路其实走不通，三处阻塞：
      a) is_patched 只查 hqQuals -> 任何旧补丁版都算「已打补丁」，永不升级；
      b) 前端补丁探针假定输入是未打补丁的 js -> 对旧补丁版直接失败；
      c) apply 没加载 blobcaps 容量记录 -> 压缩后超容量直接报错。
    这里把 a 和 c 变成断言。
    """
    section("6b. 自动重新注入的前置条件")
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "auto_patch", os.path.join(ROOT, "tools", "auto_patch.py"))
    if spec is None:
        check("跳过：找不到 auto_patch.py", True)
        return
    ap = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(ap)
    except Exception as e:
        check("auto_patch 可导入", False, "%s: %s" % (type(e).__name__, e))
        return
    check("auto_patch 可导入", True)
    # a) 判据必须能区分「旧补丁版」与「当前补丁版」
    marks = getattr(ap, "EXE_FULL_MARKERS", ())
    check("已打补丁判据不止一个标记", len(marks) >= 5, "标记数=%d" % len(marks))
    body = io.open(os.path.join(FRONTEND, "app.js"), encoding="utf-8").read()
    missing = [m.decode() for m in marks if m.decode() not in body]
    check("当前前端含全部补丁标记", not missing,
          "" if not missing else "缺: " + ", ".join(missing))
    # b) 兜底：exe 内嵌前端已是旧补丁时，必须有可用的上游原版
    base_js = os.path.join(ROOT, "base", "frontend", "app.js")
    ok_base = os.path.isfile(base_js) and "hqQuals" not in io.open(
        base_js, encoding="utf-8").read()
    check("base/frontend 是未打补丁的上游原版（重新注入的输入）", ok_base)
    # c) apply 必须加载容量记录，否则压缩超容量
    src = io.open(os.path.join(ROOT, "tools", "auto_patch.py"), encoding="utf-8").read()
    check("apply 加载 blobcaps 容量记录", "load_capacities(EXE)" in src)
    check("apply 会写回新容量", "save_capacities(tmp_exe" in src)
    check("apply 也套账号补丁", "patch_account.patch" in src)
    # d) 上游发版后必须把署名一并补回来（前端标记全中也不能跳过）
    check("apply 会重打作者署名", "rebrand_exe.rebrand_file(tmp_exe)" in src)
    check("apply 检测署名缺失", "is_rebranded(EXE)" in src)


def test_live():
    section("7. 安装目录一致性")
    if not os.path.isdir(LIVE):
        check("跳过：未找到安装目录", True, LIVE)
        return
    for name in ("server.py", "desktop_account.py", "desktop_hls.py", "desktop_hls_service.py"):
        a = os.path.join(BACKEND, name)
        b = os.path.join(LIVE, name)
        if not os.path.isfile(b):
            check("%s 已部署" % name, False, "安装目录缺失")
            continue
        same = io.open(a, "rb").read() == io.open(b, "rb").read()
        check("%s 与源码一致" % name, same, "" if same else "不一致（需重新部署）")


def main():
    print("红果桌面版 · 冒烟测试")
    print("源码: %s" % BACKEND)
    test_syntax()
    test_import_server()
    test_frontend_syntax()
    test_markers()
    test_rebrand()
    test_patch_idempotency()
    test_auto_patch_ready()
    test_routes()
    test_bind_scope()
    if "--live" in sys.argv:
        test_live()
    failed = [r for r in RESULTS if not r[1]]
    print()
    print("=" * 60)
    print("通过 %d / %d" % (len(RESULTS) - len(failed), len(RESULTS)))
    if failed:
        print("失败项:")
        for name, _, detail in failed:
            print("  - %s %s" % (name, detail))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
