# -*- coding: utf-8 -*-
"""恢复 MuMu 抓包环境（幂等，可反复跑）。

2026-10-07 用户反馈「模拟器直接没网了」。根因不是 frida，
而是模拟器里残留了一个**已经没人监听的系统代理**：
    http_proxy = 10.0.2.2:8080
之前某次用 mitmproxy 抓包设的，代理进程早没了，于是模拟器所有 HTTP
走 10.0.2.2:8080 → 连不上 → 红果显示「网络错误」。

本脚本做四件事：
  1. 清掉模拟器里失效的系统代理（抓包靠 frida hook，不需要代理）
  2. 确保 adb 连上模拟器
  3. 确保 frida-server 在跑（不在就拉起）
  4. 实测：确认红果能建 TLS 连接、且 frida hook 能收到请求

用法: python tools/restore_mumu_capture.py [--keep-proxy]
"""
import io
import json
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ADB = os.environ.get("ADB", r"D:\Tools\adb\adb.exe")
# MuMu 12 的 adb 端口：16384 是当前实例用的，16448/7555 是历史值，都试
CANDIDATES = ["127.0.0.1:16384", "127.0.0.1:16448", "127.0.0.1:7555"]
PKG = "com.phoenix.read"
FRIDA_PORT = "27042"
BRIDGE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      "_txn", "bundle", "java-bridge.js")
MUMU_MANAGER = r"C:\Program Files\Netease\MuMu\nx_main\MuMuManager.exe"


def run(*args, timeout=60):
    # 必须显式 utf-8 + errors=replace：MuMuManager 会输出中文（网卡名等），
    # 默认按系统 GBK 解码会抛 UnicodeDecodeError，
    # 而且是在 subprocess 的读线程里抛，主流程只会看到空输出。
    try:
        return subprocess.run([ADB] + list(args), capture_output=True,
                              text=True, encoding="utf-8", errors="replace",
                              timeout=timeout)
    except Exception as exc:
        class R:
            stdout = ""
            stderr = "%s" % exc
            returncode = 1
        return R()


def run_raw(cmd, timeout=120):
    """跑非 adb 的命令（MuMuManager / netstat），同样显式 utf-8。"""
    try:
        return subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout)
    except Exception as exc:
        class R:
            stdout = ""
            stderr = "%s" % exc
            returncode = 1
        return R()


def sh(dev, *args, timeout=60):
    return run("-s", dev, *args, timeout=timeout).stdout.strip()


def pick_device():
    """挑一个能用的模拟器串口。"""
    out = run("devices").stdout
    connected = []
    for line in out.splitlines():
        if "\tdevice" not in line:
            continue
        name = line.split("\t")[0].strip()
        if name.startswith("emulator-") or name.startswith("127.0.0.1:"):
            connected.append(name)
    for cand in CANDIDATES:
        if cand in connected:
            return cand
    if connected:
        return connected[0]
    # 尝试主动连接
    for cand in CANDIDATES:
        run("connect", cand)
    time.sleep(2)
    out = run("devices").stdout
    for cand in CANDIDATES:
        if cand in out:
            return cand
    return None


def check_bridge():
    """桥接网卡模式是「模拟器起不来 / 反复掉线」的常见原因。

    2026-10-07 实测：实例设了 net_bridge_open=true，绑到
    "Realtek Gaming 2.5GbE Family Controller"，但这块网卡在系统里的
    显示名是中文「以太网」—— 名称对不上，桥接绑不上，
    于是 Android 网络栈起不来：VM 报 start_finished，
    但 adb 端口 16384 始终不监听，模拟器起来几十秒就挂。
    关掉桥接（改回 NAT）后端口 10 秒内就回来了。
    """
    if not os.path.isfile(MUMU_MANAGER):
        return None
    try:
        out = run_raw([MUMU_MANAGER, "setting", "-v", "0",
                             "-k", "net_bridge_open", "--info"]).stdout
        data = json.loads(out)
        cur = data.get("net_bridge_open")
        if isinstance(cur, dict):
            cur = cur.get("value") or cur.get("current_value")
        return str(cur).lower() == "true"
    except Exception:
        return None


def disable_bridge():
    try:
        run_raw([MUMU_MANAGER, "setting", "-v", "0",
                        "-k", "net_bridge_open", "-val", "false"])
        return True
    except Exception:
        return False


def restart_vm():
    try:
        run_raw([MUMU_MANAGER, "control", "-v", "0", "restart"])
        return True
    except Exception:
        return False


def wait_port(timeout=240):
    """等任一候选端口开始监听。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            out = run_raw(["netstat", "-ano"], timeout=30).stdout
        except Exception:
            out = ""
        for cand in CANDIDATES:
            port = cand.rsplit(":", 1)[1]
            if ("127.0.0.1:%s" % port) in out and "LISTENING" in out:
                return cand
        time.sleep(5)
    return None


def main():
    keep_proxy = "--keep-proxy" in sys.argv
    ok = True

    print("=== 0) 桥接网卡检查 ===")
    bridged = check_bridge()
    if bridged is True:
        print("  net_bridge_open = true —— 这正是「模拟器起不来/反复掉线」的常见原因")
        print("  （桥接绑定的网卡名与系统实际名称对不上时，Android 网络栈起不来，")
        print("    VM 报 start_finished 但 adb 端口不监听）")
        if disable_bridge():
            print("  已关闭桥接，重启实例…")
            restart_vm()
            dev = wait_port()
            print("  adb 端口:", dev or "仍未就绪")
        else:
            print("  [FAIL] 关闭桥接失败，请手动在 MuMu 设置里关掉「桥接模式」")
            ok = False
    elif bridged is False:
        print("  net_bridge_open = false（NAT，正常）")
    else:
        print("  （读不到设置，跳过）")

    print()
    print("=== 1) 连接模拟器 ===")
    dev = pick_device()
    if not dev:
        # 再等一次，可能实例刚重启完
        dev = wait_port(timeout=120)
        if dev:
            run("connect", dev)
            time.sleep(2)
            dev = pick_device()
    if not dev:
        print("  [FAIL] 没找到模拟器；请先启动 MuMu")
        return 1
    print("  设备:", dev)
    model = sh(dev, "shell", "getprop", "ro.product.model")
    print("  型号:", model)

    print()
    print("=== 2) 清理失效系统代理 ===")
    proxy = sh(dev, "shell", "settings", "get", "global", "http_proxy")
    host = sh(dev, "shell", "settings", "get", "global", "global_http_proxy_host")
    port = sh(dev, "shell", "settings", "get", "global", "global_http_proxy_port")
    print("  当前: http_proxy=%r host=%r port=%r" % (proxy, host, port))
    if keep_proxy:
        print("  (--keep-proxy，跳过)")
    elif (host and host != "null") or (proxy and proxy not in ("null", ":0")):
        # 代理指向宿主机时，检查那个端口到底有没有人在听
        alive = False
        if port and port != "null":
            try:
                import socket
                s = socket.socket()
                s.settimeout(1.5)
                s.connect(("127.0.0.1", int(port)))
                alive = True
                s.close()
            except Exception:
                alive = False
        if alive:
            print("  宿主机 %s 端口有人在听，保留代理" % port)
        else:
            sh(dev, "shell", "settings", "put", "global", "http_proxy", ":0")
            sh(dev, "shell", "settings", "delete", "global", "global_http_proxy_host")
            sh(dev, "shell", "settings", "delete", "global", "global_http_proxy_port")
            sh(dev, "shell", "settings", "delete", "global", "global_http_proxy_exclusion_list")
            print("  已清除失效代理（宿主机该端口无人监听）")
    else:
        print("  本来就是干净的")

    print()
    print("=== 3) frida-server ===")
    ps = sh(dev, "shell", "ps -A")
    if "frida-server" not in ps:
        print("  未运行，尝试拉起…")
        # 常见路径
        for path in ("/data/local/tmp/frida-server",):
            if sh(dev, "shell", "ls", path).strip().endswith("frida-server"):
                run("-s", dev, "shell",
                    "su -c 'nohup %s >/dev/null 2>&1 &' || nohup %s >/dev/null 2>&1 &"
                    % (path, path))
                time.sleep(3)
                break
        ps = sh(dev, "shell", "ps -A")
    if "frida-server" in ps:
        ver = sh(dev, "shell", "/data/local/tmp/frida-server --version")
        print("  运行中，版本:", ver or "(未知)")
    else:
        print("  [FAIL] frida-server 没起来")
        ok = False

    print()
    print("=== 4) 端口转发 + 红果进程 ===")
    run("-s", dev, "forward", "tcp:%s" % FRIDA_PORT, "tcp:%s" % FRIDA_PORT)
    pid = sh(dev, "shell", "pidof", PKG)
    print("  %s pid: %s" % (PKG, pid or "(未运行)"))
    if not pid:
        print("  启动红果…")
        sh(dev, "shell", "monkey", "-p", PKG, "-c",
           "android.intent.category.LAUNCHER", "1")
        time.sleep(10)
        pid = sh(dev, "shell", "pidof", PKG)
        print("  pid:", pid or "(仍失败)")

    print()
    print("=== 5) 实测 ===")
    # 5a) 网络：看有没有已建立的 443 连接
    ss = sh(dev, "shell", "ss -tn")
    est = [l for l in ss.splitlines() if "ESTAB" in l and ":443" in l]
    print("  红果已建立的外网 TLS 连接: %d 条" % len(est))
    if not est:
        print("  [!] 没有 443 连接 —— 可能仍无网，或刚启动还没发请求")
        ok = False

    # 5b) frida hook 能否收到请求
    if pid and os.path.isfile(BRIDGE):
        try:
            import frida
            bridge = io.open(BRIDGE, encoding="utf-8").read()
            devobj = frida.get_device_manager().add_remote_device(
                "127.0.0.1:%s" % FRIDA_PORT)
            sess = devobj.attach(int(pid.split()[0]))
            hook = r"""
;var Java = __fridaJavaBridge.default;
var n = 0;
Java.perform(function () {
  var RB = Java.use("okhttp3.Request$Builder");
  RB.build.implementation = function () {
    var r = this.build();
    try {
      var u = r.url().toString();
      if ((u.indexOf("fqnovel.com") >= 0 || u.indexOf("snssdk.com") >= 0) && n < 3) {
        n++; send(JSON.stringify({k:"REQ", m:r.method(), path:u.split("?")[0].replace(/^https?:\/\/[^\/]+/,"")}));
      }
    } catch (e) {}
    return r;
  };
  send(JSON.stringify({k:"READY"}));
});
"""
            got = []
            scr = sess.create_script(bridge + hook)
            scr.on("message", lambda m, d: got.append(m["payload"])
                   if m.get("type") == "send" else None)
            scr.load()
            # READY 是 load() 期间同步发出的，这里可能还没落到 got 里，
            # 所以先等一小会再判定。
            time.sleep(1.5)
            print("  frida hook 已加载:", any('"READY"' in g for g in got))
            print("  等 8 秒，看能否抓到请求（在模拟器里点几下）…")
            time.sleep(8)
            reqs = [g for g in got if '"REQ"' in g]
            print("  抓到请求: %d 条" % len(reqs))
            for g in reqs[:3]:
                print("    ", g[:150])
            if not reqs:
                print("  [!] 没抓到请求（可能没在操作，或 hook 未命中）")
            sess.detach()
        except Exception as exc:
            print("  [FAIL] frida 实测失败:", "%s: %s" % (type(exc).__name__, exc))
            ok = False
    else:
        print("  [!] 跳过 frida 实测（缺 bridge 或红果未运行）")

    print()
    print("=" * 56)
    print("恢复完成" if ok else "部分项未通过，见上面的 [!] / [FAIL]")
    print()
    print("抓包命令（另开窗口）：")
    print("  python -u _txn/capture.py 600      # 抓 600 秒 -> _txn/capture.json")
    print("  期间在模拟器里操作红果")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
