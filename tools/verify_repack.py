# -*- coding: utf-8 -*-
"""回读 exe 内嵌前端资源，并与 src/frontend 比对（版本无关）。

用法: python tools/verify_repack.py <exe> [src/frontend 目录]
"""
import hashlib
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tauri_assets import Assets
from repack import align_index_html

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def pick(key):
    if key.endswith(".js"):
        return "app.js"
    if key.endswith(".css"):
        return "app.css"
    return "index.html"


def main():
    exe = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist", "hongguo-desktop-companion.exe")
    fe = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "src", "frontend")
    a = Assets(exe)
    ok = True
    found = a.find()
    keys = list(found.keys())
    for key, entry in found.items():
        raw = entry["raw"]
        name = pick(key)
        path = os.path.join(fe, name)
        want = io.open(path, "rb").read() if os.path.isfile(path) else None
        # index.html 的资源引用在打包时会对齐到 exe 内的真实键名，
        # 比对前要施加同一变换。
        if want is not None and key.endswith(".html"):
            want = align_index_html(want, keys)
        same = want is not None and want == raw
        ok = ok and same
        print(f"{key}: clen={entry['blob_len']} raw={len(raw)} "
              f"sha={hashlib.sha256(raw).hexdigest()[:16]} 与 src 一致={same}")
    print("[OK] 全部一致" if ok else "[FAIL] 存在不一致")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
