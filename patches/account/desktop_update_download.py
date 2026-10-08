# -*- coding: utf-8 -*-
"""本地维护: 应用内下载新版安装包（国内走 Gitee，快）。

为什么不让后端直接返回一个「下载地址」了事：
    GitHub 在国内很慢；Gitee 快，但它的下载链接是**带时效 token 的跳转**
    （302 到 foruda.gitee.com/...?token=...&ts=...），直接交给浏览器打开
    会跳到 Gitee 页面而不是「立即开始下载」。用户要的是点一下就开始下。

做法：
    * 后端流式代理下载（跟随跳转），前端用 fetch 读进度条；
    * 下完把文件交给系统默认程序打开 —— 那就是安装包自己弹安装向导，
      不经过浏览器，也就不会被浏览器拦。
    * 下载源按优先级排：Gitee 在前（国内快），GitHub 兜底。

校验：下载完成后比对 SHA256。Gitee 和 GitHub 都提供不了可靠的摘要接口，
所以摘要由发布方写进 latest.json（`sha256` 字段），下载后核对；
对不上就报错，不把坏文件交给用户。
"""
import hashlib
import os
import threading
import time
import uuid

import hongguo as H

# 下载源优先级：Gitee 在前（国内快），GitHub 兜底。
# 两边的文件是同一份构建（逐字节相同，已核对 sha256）。
SOURCES = (
    ("gitee", "https://gitee.com/q24111/hongguo-desktop-releases"
              "/releases/download/v%s/hongguo-%s-setup.exe"),
    ("github", "https://github.com/dddmiku/hongguo-desktop-releases"
               "/releases/download/v%s/hongguo-%s-setup.exe"),
)
GITEE_API = ("https://gitee.com/api/v5/repos/q24111/hongguo-desktop-releases"
             "/releases/tags/v%s")

_tasks = {}
_lock = threading.RLock()
_MAX_TASKS = 4


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download_dir():
    """安装包下载到用户数据目录下的 downloads/（便于清理与排查）。"""
    base = os.environ.get("HONGGUO_DATA_DIR") or os.environ.get(
        "HONGGUO_BACKEND_DATA_DIR") or os.path.dirname(os.path.abspath(__file__))
    d = os.path.join(base, "downloads")
    os.makedirs(d, exist_ok=True)
    return d


def _prune():
    with _lock:
        if len(_tasks) <= _MAX_TASKS:
            return
        done = sorted((t for t in _tasks.values()
                       if t["phase"] in ("done", "error")),
                      key=lambda t: t["at"])
        for t in done[:len(_tasks) - _MAX_TASKS]:
            _tasks.pop(t["id"], None)


def _run(task, version, sha_expected):
    urls = [(name, tpl % (version, version)) for name, tpl in SOURCES]
    dest = os.path.join(download_dir(),
                        "hongguo-%s-setup.exe" % version)
    part = dest + ".part"
    last_err = ""
    for name, url in urls:
        task["source"] = name
        task["phase"] = "downloading"
        try:
            r = H.http_request("GET", url, stream=True, timeout=60,
                               allow_redirects=True)
            if r.status_code != 200:
                last_err = "%s: HTTP %s" % (name, r.status_code)
                continue
            total = int(r.headers.get("content-length") or 0)
            task["total"] = total
            got = 0
            with open(part, "wb") as f:
                for chunk in r.iter_content(1 << 18):
                    if task.get("cancelled"):
                        raise RuntimeError("cancelled")
                    if not chunk:
                        continue
                    f.write(chunk)
                    got += len(chunk)
                    task["downloaded"] = got
            if total and got != total:
                last_err = "%s: 下载不完整（%d/%d）" % (name, got, total)
                continue
            task["phase"] = "verifying"
            actual = _sha256(part)
            if sha_expected and actual != sha_expected:
                last_err = ("%s: 校验不一致（期望 %s…，实际 %s…）"
                            % (name, sha_expected[:12], actual[:12]))
                try:
                    os.remove(part)
                except OSError:
                    pass
                continue
            os.replace(part, dest)
            task.update(phase="done", path=dest, sha256=actual,
                        size=os.path.getsize(dest), at=time.time())
            return
        except Exception as e:
            last_err = "%s: %s" % (name, type(e).__name__)
            try:
                if os.path.isfile(part):
                    os.remove(part)
            except OSError:
                pass
    task.update(phase="error", error=last_err or "下载失败", at=time.time())


def start(version, sha256=""):
    version = "".join(c for c in str(version or "") if c.isdigit() or c == ".")
    if not version:
        return {"ok": False, "error": "版本号无效"}
    with _lock:
        for t in _tasks.values():
            if t["version"] == version and t["phase"] in ("downloading", "verifying"):
                return {"ok": True, "task": public(t)}
        task = {"id": uuid.uuid4().hex, "version": version, "phase": "starting",
                "downloaded": 0, "total": 0, "source": "", "at": time.time(),
                "path": "", "sha256": "", "size": 0, "error": ""}
        _tasks[task["id"]] = task
    threading.Thread(target=_run, args=(task, version, sha256),
                     name="hq-download", daemon=True).start()
    return {"ok": True, "task": public(task)}


def public(task):
    return {"id": task["id"], "version": task["version"],
            "phase": task["phase"], "downloaded": task["downloaded"],
            "total": task["total"], "source": task["source"],
            "size": task["size"], "error": task["error"]}


def status(task_id):
    with _lock:
        t = _tasks.get(str(task_id))
    if not t:
        return {"ok": False, "error": "下载任务不存在"}
    return {"ok": True, "task": public(t)}


def launch(task_id):
    """用系统默认程序打开下好的安装包（会弹安装向导）。"""
    with _lock:
        t = _tasks.get(str(task_id))
    if not t:
        return {"ok": False, "error": "下载任务不存在"}
    if t["phase"] != "done":
        return {"ok": False, "error": "还没下载完"}
    path = t["path"]
    if not os.path.isfile(path):
        return {"ok": False, "error": "安装包不见了，请重新下载"}
    try:
        os.startfile(path)          # noqa: S606  仅本机、仅我们自己下的文件
    except Exception as e:
        return {"ok": False, "error": "无法打开安装包：%s" % type(e).__name__}
    return {"ok": True, "path": path}


def cancel(task_id):
    with _lock:
        t = _tasks.get(str(task_id))
    if t:
        t["cancelled"] = True
    return {"ok": True}
