# -*- coding: utf-8 -*-
"""把安装包发布到 GitHub Releases（供分发）。

token 从 git 凭据管理器取（不落盘、不进命令行、不打印）。

用法:
    python tools/release_installer.py --tag v1.1.0
    python tools/release_installer.py --tag v1.1.0 --notes-file notes.md
    python tools/release_installer.py --list
"""
import argparse
import datetime
import io
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "dddmiku/hongguo-desktop-releases"
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def token():
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    p = subprocess.run(["git", "credential", "fill"],
                       input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True, env=env, timeout=30)
    if p.returncode != 0:
        raise SystemExit("git credential fill 失败: " + p.stderr.strip()[:200])
    for line in p.stdout.splitlines():
        if line.startswith("password="):
            v = line.split("=", 1)[1].strip()
            if v:
                return v
    raise SystemExit("凭据管理器里没有 github token")


def call(method, url, secret, payload=None, raw=None, ctype=None):
    data = raw if raw is not None else (
        json.dumps(payload).encode("utf-8") if payload is not None else None)
    req = urllib.request.Request(url, method=method, data=data, headers={
        "Authorization": "Bearer " + secret,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "hongguo-release"})
    if ctype:
        req.add_header("Content-Type", ctype)
    elif data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:600]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="v1.1.0")
    ap.add_argument("--name")
    ap.add_argument("--notes")
    ap.add_argument("--notes-file")
    ap.add_argument("--asset", default=os.path.join(
        ROOT, "dist", "hongguo-1.1.0-setup.exe"))
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    tok = token()

    if a.list:
        s, d = call("GET", "%s/repos/%s/releases" % (API, REPO), tok)
        if s != 200:
            raise SystemExit("[FAIL] %s %s" % (s, d))
        for r in d:
            print("%-10s %s" % (r["tag_name"], r["name"]))
            for asset in r.get("assets", []):
                print("    %s  %d bytes" % (asset["name"], asset["size"]))
        return 0

    notes = a.notes
    if a.notes_file:
        notes = io.open(a.notes_file, encoding="utf-8").read()
    if not notes:
        notes = ("红果免费短剧 桌面版 · 本地维护分支\n\n"
                 "下载 `hongguo-1.1.0-setup.exe` 安装即可。安装不需要管理员权限。\n\n"
                 "本安装包为**上游 1.0.9 的本地二次维护**，已包含：\n"
                 "- 3 倍速（0.75–3）、清晰度可选（自动/1080p/720p/540p/480p）\n"
                 "- 完整键盘快捷键；点过按钮后按空格不再误触发该按钮\n"
                 "- 跳集不再「媒体准备失败」；已转码档位再切约 1.5 秒\n"
                 "- 自动连播不弹控制栏、不闪鼠标\n"
                 "- 缓存自动清理，不再无限膨胀\n"
                 "- 观看进度与收藏同步到手机账号\n"
                 "- 已关闭官方在线更新通道\n\n"
                 "安装位置默认 `%LOCALAPPDATA%\\Programs\\红果免费短剧`，"
                 "装过旧版会自动沿用旧路径。\n\n"
                 "安装包未做 Windows 发布者代码签名，可能出现「未知发布者」提示，"
                 "请核对下方 SHA256。")

    # 已存在就先删掉重发（避免同名 tag 冲突）
    s, d = call("GET", "%s/repos/%s/releases/tags/%s" % (API, REPO, a.tag), tok)
    if s == 200:
        print("[=] Release %s 已存在，先删除" % a.tag)
        call("DELETE", "%s/repos/%s/releases/%d" % (API, REPO, d["id"]), tok)

    s, d = call("POST", "%s/repos/%s/releases" % (API, REPO), tok, {
        "tag_name": a.tag,
        "name": a.name or ("红果桌面版 %s · 本地维护分支" % a.tag.lstrip("v")),
        "body": notes,
        "draft": False,
        "prerelease": False,
    })
    if s not in (200, 201):
        raise SystemExit("[FAIL] 建 Release: %s %s" % (s, d))
    print("[OK] Release %s 已创建" % a.tag)
    upload_url = d["upload_url"].split("{")[0]

    if not os.path.isfile(a.asset):
        raise SystemExit("[FAIL] 找不到安装包: %s" % a.asset)
    raw = io.open(a.asset, "rb").read()
    name = os.path.basename(a.asset)
    print("[*] 上传 %s（%.1f MB）…" % (name, len(raw) / 1048576.0))
    s, d = call("POST", "%s?name=%s" % (upload_url, name), tok,
                raw=raw, ctype="application/octet-stream")
    if s not in (200, 201):
        raise SystemExit("[FAIL] 上传: %s %s" % (s, d))
    print("[OK] 已上传: %s" % d.get("browser_download_url"))

    # latest.json：更新检测用的静态文件（不限流、不需要 token）。
    # 客户端读 https://github.com/<repo>/releases/latest/download/latest.json
    version = a.tag.lstrip("v")
    latest = {
        "version": version,
        "notes": notes,
        "pub_date": datetime.datetime.now(datetime.timezone.utc)
                    .strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
        "platforms": {
            "windows-x86_64": {
                "url": d.get("browser_download_url") or "",
                "size": len(raw),
            }
        },
    }
    blob = json.dumps(latest, ensure_ascii=False, indent=2).encode("utf-8")
    s, d2 = call("POST", "%s?name=latest.json" % upload_url, tok,
                 raw=blob, ctype="application/json")
    if s not in (200, 201):
        print("[!] latest.json 上传失败: %s %s" % (s, d2))
        print("    更新检测会退回 GitHub API（有速率限制）")
    else:
        print("[OK] 已上传 latest.json（version=%s）" % version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
