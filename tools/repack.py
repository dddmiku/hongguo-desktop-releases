# -*- coding: utf-8 -*-
"""把补丁写回 exe 的内嵌前端资源（版本无关）。

用法:
    python tools/repack.py <未打过补丁的原版 exe> <输出 exe> [src/frontend 目录]
"""
import io
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tauri_assets import Assets, load_capacities, save_capacities

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 资源键 -> src/frontend 下的文件名
MAP = {
    "index.html": "index.html",
    "/index.html": "index.html",
}


def pick(key):
    if key.endswith(".js"):
        return "app.js"
    if key.endswith(".css"):
        return "app.css"
    return MAP.get(key)


def align_index_html(html, keys):
    """把 index.html 里的资源引用改成 exe 里真实存在的键名。

    上游每次发版都会换哈希文件名；从安装包抽出来的 index.html 与
    内嵌资源可能不同批，直接照抄会导致脚本 404、界面全白。
    这里按扩展名把 src/href 重写到当前 exe 实际包含的键。
    """
    js = next((k for k in keys if k.endswith(".js")), None)
    css = next((k for k in keys if k.endswith(".css")), None)
    text = html.decode("utf-8")
    if js:
        text, n = re.subn(r'(<script[^>]*\bsrc=")/assets/[^"]+(")', r"\g<1>" + js + r"\g<2>", text)
        if n != 1:
            raise SystemExit(f"[FAIL] index.html 里的脚本引用改写命中 {n} 次")
    if css:
        text, n = re.subn(r'(<link[^>]*\bhref=")/assets/[^"]+(")', r"\g<1>" + css + r"\g<2>", text)
        if n != 1:
            raise SystemExit(f"[FAIL] index.html 里的样式引用改写命中 {n} 次")
    return text.encode("utf-8")


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src_exe, out_exe = sys.argv[1], sys.argv[2]
    fe = sys.argv[3] if len(sys.argv) > 3 else os.path.join(ROOT, "src", "frontend")

    a = Assets(src_exe, load_capacities(src_exe))
    found = a.find()
    if not found:
        raise SystemExit("[FAIL] 未在 exe 内找到前端资源")

    keys = list(found.keys())
    for key, entry in found.items():
        name = pick(key)
        path = os.path.join(fe, name)
        if not name or not os.path.isfile(path):
            print(f"跳过 {key}（无对应文件）")
            continue
        raw = io.open(path, "rb").read()
        if key.endswith(".html"):
            raw = align_index_html(raw, keys)
        old_len, new_len, enc = a.replace(entry, raw)
        print(f"[OK] {key}: {old_len} -> {new_len} bytes "
              f"(余量 {old_len - new_len}) q={enc['quality']} w={enc['lgwin']}")

    a.save(out_exe)
    save_capacities(out_exe, a.capacities)
    print("[OK] wrote", out_exe)


if __name__ == "__main__":
    main()
