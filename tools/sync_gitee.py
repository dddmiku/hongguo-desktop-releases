# -*- coding: utf-8 -*-
"""把安装包同步到 Gitee 仓库（国内下载加速）。

为什么：GitHub 在国内下载很慢，用户点「下载并更新」应该直接开始下载、
且走 Gitee 的下载地址。

做法：把安装包作为附件挂到 Gitee 的**发行版（Release）**上。
不用「仓库附件」接口（POST /repos/{owner}/{repo}/attach_files）—— 它要求
multipart 且需要浏览器签名，纯 token 调用不稳定；发行版附件是标准
multipart/form-data，用 token 即可。

token 从 ~/.gitee_token 读（不落盘到仓库、不打印）。

用法:
    python tools/sync_gitee.py --tag v1.1.0
    python tools/sync_gitee.py --tag v1.1.0 --asset dist/hongguo-1.1.0-setup.exe
    python tools/sync_gitee.py --list
"""
import argparse
import datetime
import io
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OWNER = "q24111"
REPO = "hongguo-desktop-releases"
API = "https://gitee.com/api/v5"
TOKEN_FILE = os.path.join(os.path.expanduser("~"), ".gitee_token")
GH_REPO = "dddmiku/hongguo-desktop-releases"


def token():
    tok = os.environ.get("GITEE_TOKEN")
    if not tok and os.path.isfile(TOKEN_FILE):
        tok = io.open(TOKEN_FILE).read().strip()
    if not tok:
        raise SystemExit("[FAIL] 没有 Gitee token（放 ~/.gitee_token 或设 GITEE_TOKEN）")
    return tok


def _req(url, method="GET", data=None, headers=None, timeout=600):
    req = urllib.request.Request(url, method=method, data=data,
                                 headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:600]
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e)


def api(path, params=None, method="GET", data=None, ctype=None):
    q = dict(params or {})
    q["access_token"] = token()
    url = "%s%s?%s" % (API, path, urllib.parse.urlencode(q))
    h = {"Content-Type": ctype} if ctype else {}
    return _req(url, method=method, data=data, headers=h)


def multipart(fields, files):
    """构造 multipart/form-data。files: [(name, filename, bytes)]"""
    boundary = "----hq" + uuid.uuid4().hex
    out = io.BytesIO()
    for k, v in fields.items():
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode())
        out.write(str(v).encode("utf-8"))
        out.write(b"\r\n")
    for name, filename, blob in files:
        out.write(("--%s\r\n" % boundary).encode())
        out.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
                   % (name, filename)).encode())
        out.write(b"Content-Type: application/octet-stream\r\n\r\n")
        out.write(blob)
        out.write(b"\r\n")
    out.write(("--%s--\r\n" % boundary).encode())
    return out.getvalue(), "multipart/form-data; boundary=%s" % boundary


def find_release(tag):
    s, d = api("/repos/%s/%s/releases/tags/%s" % (OWNER, REPO, tag))
    return (d if s == 200 else None), s


def create_release(tag, name, body):
    s, d = api("/repos/%s/%s/releases" % (OWNER, REPO), method="POST",
               data=urllib.parse.urlencode({
                   "tag_name": tag, "name": name, "body": body,
                   "target_commitish": "master", "prerelease": "false",
               }).encode(), ctype="application/x-www-form-urlencoded")
    return (d if s in (200, 201) else None), s, d


def upload_asset(release_id, filename, blob):
    fields = {"access_token": token()}
    payload, ctype = multipart(fields, [("file", filename, blob)])
    return _req("%s/repos/%s/%s/releases/%s/attach_files"
                % (API, OWNER, REPO, release_id),
                method="POST", data=payload, headers={"Content-Type": ctype})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="v1.1.0")
    ap.add_argument("--asset", default=os.path.join(
        ROOT, "dist", "hongguo-1.1.0-setup.exe"))
    ap.add_argument("--notes", default="")
    ap.add_argument("--notes-file")
    ap.add_argument("--latest-json", default=os.path.join(ROOT, "dist", "latest.json"))
    ap.add_argument("--force", action="store_true",
                    help="已有 Release 时先删掉重建（Gitee 的 API 不暴露附件 ID，"
                         "没法单独替换附件，只能整条重建）")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    if a.list:
        s, d = api("/repos/%s/%s/releases" % (OWNER, REPO))
        if s != 200:
            print("[FAIL]", s, d); return 1
        for r in d:
            print("%-10s %s" % (r.get("tag_name"), r.get("name")))
            for asset in (r.get("assets") or []):
                print("    %s  %s bytes" % (asset.get("name"),
                                            asset.get("size")))
        return 0

    notes = a.notes
    if a.notes_file:
        notes = io.open(a.notes_file, encoding="utf-8").read()
    if not notes:
        notes = ("红果免费短剧 桌面版 · 本地维护分支 %s\n\n"
                 "下载 hongguo-*-setup.exe 安装即可，不需要管理员权限。\n"
                 "GitHub 主仓库：https://github.com/%s" % (a.tag, GH_REPO))

    rel, status = find_release(a.tag)
    if rel and a.force:
        s, d = api("/repos/%s/%s/releases/%s" % (OWNER, REPO, rel.get("id")),
                   method="DELETE")
        if s not in (200, 204):
            print("[FAIL] 删除旧 Release 失败: %s %s" % (s, str(d)[:200]))
            return 1
        print("[OK] 已删除旧 Release %s（附件一并清掉）" % a.tag)
        rel = None
    if rel:
        print("[=] Gitee Release %s 已存在（id=%s）" % (a.tag, rel.get("id")))
    else:
        rel, status, raw = create_release(
            a.tag, "红果桌面版 %s · 本地维护分支" % a.tag.lstrip("v"), notes)
        if not rel:
            print("[FAIL] 建 Release: %s %s" % (status, raw)); return 1
        print("[OK] 已建 Gitee Release %s（id=%s）" % (a.tag, rel.get("id")))

    rid = rel.get("id")
    existing = {x.get("name") for x in (rel.get("assets") or [])}

    if not os.path.isfile(a.asset):
        print("[FAIL] 找不到安装包:", a.asset); return 1
    blob = io.open(a.asset, "rb").read()
    name = os.path.basename(a.asset)
    if name in existing:
        print("[=] 附件 %s 已存在，跳过（如需替换请先在网页上删掉）" % name)
    else:
        print("[*] 上传 %s（%.1f MB）…" % (name, len(blob) / 1048576.0))
        s, d = upload_asset(rid, name, blob)
        if s not in (200, 201):
            print("[FAIL] 上传失败: %s %s" % (s, str(d)[:300])); return 1
        url = (d or {}).get("browser_download_url") or (d or {}).get("url") or ""
        print("[OK] 已上传: %s" % url)
        print("     直链: https://gitee.com/%s/%s/releases/download/%s/%s"
              % (OWNER, REPO, a.tag, name))

    # latest.json：给「检查更新」用的静态文件（GitHub 那边一份，这里也放一份）
    if os.path.isfile(a.latest_json):
        lname = "latest.json"
        if lname in existing:
            print("[=] %s 已存在，跳过" % lname)
        else:
            lb = io.open(a.latest_json, "rb").read()
            s, d = upload_asset(rid, lname, lb)
            print("[OK] latest.json 已上传" if s in (200, 201)
                  else "[!] latest.json 上传失败: %s %s" % (s, str(d)[:200]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
