# -*- coding: utf-8 -*-
"""通用 Tauri 资源定位/提取/回写（不依赖固定偏移）。

Tauri 2 把前端资源以 Brotli 流放在 .rdata，资源表每项 32 字节:
    u64 key_ptr, u64 key_len, u64 blob_ptr, u64 blob_len
其中 *_ptr 是「镜像基址 + RVA」形式的绝对地址。
"""
import io, os, re, struct, hashlib, json, brotli

IMAGE_MAGIC_PE32P = 0x20b


def capacity_path(exe):
    """容量旁挂文件：记录每个资源在原始安装包里的 blob_len。"""
    return exe + ".blobcaps.json"


def load_capacities(exe):
    try:
        return json.load(io.open(capacity_path(exe), encoding="utf-8"))
    except Exception:
        return {}


def save_capacities(exe, caps):
    try:
        io.open(capacity_path(exe), "w", encoding="utf-8").write(
            json.dumps(caps, indent=2, ensure_ascii=False))
    except OSError:
        pass


class Assets:
    def __init__(self, path, capacities=None):
        self.path = path
        self.data = bytearray(io.open(path, "rb").read())
        self.image_base, self.secs = self._sections()
        # 容量只增不减：既取旁挂文件里的历史最大值，也取本次读到的 blob_len。
        self.capacities = dict(capacities or {})

    def _sections(self):
        d = self.data
        pe = d.find(b"PE\x00\x00")
        if pe < 0:
            raise ValueError("not a PE file")
        nsec = struct.unpack_from("<H", d, pe + 6)[0]
        optsz = struct.unpack_from("<H", d, pe + 20)[0]
        opt = pe + 24
        magic = struct.unpack_from("<H", d, opt)[0]
        ib = struct.unpack_from("<Q", d, opt + 24)[0] if magic == IMAGE_MAGIC_PE32P \
             else struct.unpack_from("<I", d, opt + 28)[0]
        sec0 = opt + optsz
        secs = []
        for i in range(nsec):
            o = sec0 + i * 40
            nm = d[o:o + 8].rstrip(b"\x00").decode(errors="replace")
            vs, va, rs, rp = struct.unpack_from("<IIII", d, o + 8)
            secs.append((nm, va, vs, rp, rs))
        return ib, secs

    def rva_to_fo(self, rva):
        for nm, va, vs, rp, rs in self.secs:
            if va <= rva < va + vs:
                off = rva - va
                if off < rs:
                    return rp + off
        return None

    def fo_to_rva(self, fo):
        for nm, va, vs, rp, rs in self.secs:
            if rp <= fo < rp + rs:
                return va + (fo - rp)
        return None

    def find(self):
        """返回 {key: {key_fo, key_len, entry_fo, blob_fo, blob_len, raw}}"""
        d = self.data
        out = {}
        for pat in (rb"/assets/index-[A-Za-z0-9_\-]+\.js",
                    rb"/assets/index-[A-Za-z0-9_\-]+\.css",
                    rb"/index\.html"):
            for m in re.finditer(pat, d):
                key_fo = m.start()
                key = m.group(0).decode()
                rva = self.fo_to_rva(key_fo)
                if rva is None:
                    continue
                va = self.image_base + rva
                for h in (x.start() for x in re.finditer(re.escape(struct.pack("<Q", va)), d)):
                    entry_fo = h
                    kptr, klen, bptr, blen = struct.unpack_from("<QQQQ", d, entry_fo)
                    if kptr != va or klen != len(key) or not (200 <= blen <= 5_000_000):
                        continue
                    brva = bptr - self.image_base
                    bfo = self.rva_to_fo(brva)
                    if bfo is None:
                        continue
                    try:
                        raw = brotli.decompress(bytes(d[bfo:bfo + blen]))
                    except Exception:
                        continue
                    cap = max(int(self.capacities.get(key, 0) or 0), blen)
                    self.capacities[key] = cap
                    out[key] = {"key_fo": key_fo, "key_len": len(key), "entry_fo": entry_fo,
                                "blob_fo": bfo, "blob_len": cap, "raw": raw,
                                "declared_len": blen}
                    break
        return out

    def replace(self, entry, raw, quality=11, lgwin=22):
        """原地替换一个资源；返回 (旧压缩长度, 新压缩长度)。"""
        best = None
        for q in (quality, 10, 9):
            for w in (lgwin, 23, 24):
                for mode in (0, 1):
                    c = brotli.compress(raw, quality=q, lgwin=w, mode=mode)
                    if best is None or len(c) < len(best[0]):
                        best = (c, q, w, mode)
        comp, q, w, mode = best
        cap = int(self.capacities.get(entry.get("key") or "", 0) or 0) or entry["blob_len"]
        if len(comp) > cap:
            raise SystemExit(f"[FAIL] 压缩后 {len(comp)} > 容量 {cap}（需扩容，当前不支持）")
        assert brotli.decompress(comp) == raw
        fo = entry["blob_fo"]
        self.data[fo:fo + len(comp)] = comp
        struct.pack_into("<Q", self.data, entry["entry_fo"] + 24, len(comp))
        return cap, len(comp), {"quality": q, "lgwin": w, "mode": mode}

    def save(self, path):
        io.open(path, "wb").write(bytes(self.data))
        save_capacities(path, self.capacities)


if __name__ == "__main__":
    import sys
    a = Assets(sys.argv[1])
    for k, e in a.find().items():
        print(f"{k}\n  key_fo={e['key_fo']:#x} entry_fo={e['entry_fo']:#x} "
              f"blob_fo={e['blob_fo']:#x} clen={e['blob_len']} raw={len(e['raw'])} "
              f"sha={hashlib.sha256(e['raw']).hexdigest()[:16]}")
        if len(sys.argv) > 2:
            name = k.strip("/").replace("/", "_")
            io.open(os.path.join(sys.argv[2], name), "wb").write(e["raw"])
