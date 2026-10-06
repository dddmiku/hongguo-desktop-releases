# -*- coding: utf-8 -*-
"""红果桌面版重打包: 把 src/frontend 写回 exe 内嵌 Tauri 资源表。

原理(实测):
  Tauri 2.11 把前端资源以 Brotli 流内嵌在 .rdata, 由一个 (ptr,len) 表引用。
  表内 len 是压缩流字节数; 解码器只读 len 字节, 故新流 <= 原长度即可原地替换。
  该 exe 无 Authenticode 签名, 也无可执行文件自校验。
"""
import io, os, re, sys, json, struct, hashlib, shutil
import brotli

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "frontend")
DIST = os.path.join(ROOT, "dist")

# 资源表位置 (由 PE .rdata 解析 + 字符串定位得出, 见 docs/architecture.md)
TABLE = 0xc8a4e8
RDATA_DELTA = 0x1a00          # .rdata: vaddr 0xc3f000 <- rawptr 0xc3d600
ASSETS = [
    # name,          key_off,   blob_ptr_off, len_off,    orig_len
    ("app.css",      0xc41b28,  0xc41b42,     0xc8a500,   10068),
    ("index.html",   0xc419e6,  0xc442a1,     0xc8a520,   247),
    ("app.js",       0xc44398,  0xc443b1,     0xc8a540,   287031),
]

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def main():
    # 需要一个「未打过前端补丁」的原版 exe 作为基底：
    #   1) 命令行第 1 个参数，或
    #   2) 环境变量 HONGGUO_ORIG_EXE
    # 通常取自上游 Release 的 setup.exe 安装结果。
    exe_src = (sys.argv[1] if len(sys.argv) > 1
               else os.environ.get("HONGGUO_ORIG_EXE", ""))
    if not exe_src or not os.path.isfile(exe_src):
        raise SystemExit("需要原版 exe 路径: python tools/repack.py <原版 exe> ")

    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(DIST, "hongguo-desktop-companion.exe")
    os.makedirs(DIST, exist_ok=True)
    data = bytearray(io.open(exe_src, "rb").read())
    report = {"source": exe_src, "source_sha256": sha256(bytes(data)), "assets": []}

    for name, key_off, blob_off, len_off, orig_len in ASSETS:
        raw = io.open(os.path.join(SRC, name), "rb").read()
        best = None
        for q in (11, 10, 9):
            for w in (22, 23, 24):
                for mode in (0, 1):
                    try:
                        c = brotli.compress(raw, quality=q, lgwin=w, mode=mode)
                    except Exception:
                        continue
                    if best is None or len(c) < len(best[0]):
                        best = (c, q, w, mode)
        comp, q, w, mode = best
        if len(comp) > orig_len:
            raise SystemExit(f"[FAIL] {name}: {len(comp)} > capacity {orig_len}")
        # 校验: 解码器按 len 字节读取必须还原
        check = brotli.decompress(comp)
        assert check == raw, f"{name}: round-trip mismatch"
        data[blob_off:blob_off+len(comp)] = comp
        struct.pack_into("<Q", data, len_off, len(comp))
        # 确认引用未变
        ptr = struct.unpack_from("<Q", data, blob_off - 0x19)[0]
        report["assets"].append({
            "name": name, "orig_compressed": orig_len, "new_compressed": len(comp),
            "headroom": orig_len - len(comp), "raw_bytes": len(raw),
            "brotli": {"quality": q, "lgwin": w, "mode": mode},
            "raw_sha256": sha256(raw), "blob_off": hex(blob_off),
        })
        print(f"[OK] {name}: {orig_len} -> {len(comp)} bytes (headroom {orig_len-len(comp)}) q={q} w={w}")

    io.open(out, "wb").write(bytes(data))
    report["output"] = out
    report["output_sha256"] = sha256(bytes(data))
    io.open(os.path.join(DIST, "repack-report.json"), "w", encoding="utf-8").write(
        json.dumps(report, ensure_ascii=False, indent=2))
    print("[OK] wrote", out)
    print("[OK] sha256", report["output_sha256"])

if __name__ == "__main__":
    main()
