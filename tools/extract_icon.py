# -*- coding: utf-8 -*-
"""从 PE 里抽出图标，还原成标准 .ico。

NSIS 的 MUI_ICON 需要一个真正的 .ico 文件，makensis 自己不会从 exe 里取。
这里按 PE 资源目录把 RT_GROUP_ICON(RT 14) 与 RT_ICON(RT 3) 拼回 ICO。

用法: python tools/extract_icon.py <exe> <out.ico>
"""
import io
import os
import struct
import sys


def sections(d):
    pe = d.find(b"PE\x00\x00")
    nsec = struct.unpack_from("<H", d, pe + 6)[0]
    optsz = struct.unpack_from("<H", d, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", d, opt)[0]
    dd = opt + (112 if magic == 0x20b else 96)
    rva, size = struct.unpack_from("<II", d, dd + 16)
    sec0 = opt + optsz
    secs = []
    for i in range(nsec):
        o = sec0 + i * 40
        vs, va, rs, rp = struct.unpack_from("<IIII", d, o + 8)
        secs.append((va, vs, rp, rs))
    return rva, secs


def r2f(rva, secs):
    for va, vs, rp, rs in secs:
        if va <= rva < va + vs:
            off = rva - va
            if off < rs:
                return rp + off
    return None


def read_dir(d, base, off):
    """返回 [(id_or_name, is_dir, child_off)]；child_off 相对 base。"""
    fo = base + off
    n_named, n_id = struct.unpack_from("<HH", d, fo + 12)
    out = []
    for k in range(n_named + n_id):
        e = fo + 16 + k * 8
        name, child = struct.unpack_from("<II", d, e)
        out.append((name, bool(child & 0x80000000), child & 0x7FFFFFFF))
    return out


def main():
    exe, out = sys.argv[1], sys.argv[2]
    d = io.open(exe, "rb").read()
    rsrc_rva, secs = sections(d)
    base = r2f(rsrc_rva, secs)
    if base is None:
        raise SystemExit("[FAIL] 没有资源节")

    types = {name & 0x7FFFFFFF: child for name, isdir, child in read_dir(d, base, 0)}
    if 14 not in types or 3 not in types:
        raise SystemExit("[FAIL] exe 里没有 RT_GROUP_ICON / RT_ICON")

    # RT_ICON: id -> 数据
    icons = {}
    for name, isdir, child in read_dir(d, base, types[3]):
        lang, isdir2, child2 = read_dir(d, base, child)[0]
        entry = base + child2
        data_rva, size = struct.unpack_from("<II", d, entry)
        fo = r2f(data_rva, secs)
        icons[name & 0x7FFFFFFF] = d[fo:fo + size]

    # RT_GROUP_ICON: 取第一个组
    gname, isdir, gchild = read_dir(d, base, types[14])[0]
    lang, isdir2, child2 = read_dir(d, base, gchild)[0]
    gfo = base + child2
    grva, gsize = struct.unpack_from("<II", d, gfo)
    g = d[r2f(grva, secs):r2f(grva, secs) + gsize]
    _res, _type, count = struct.unpack_from("<HHH", g, 0)

    entries = []
    for i in range(count):
        o = 6 + i * 14
        (w, h, colors, _r, planes, bpp, size, iid) = struct.unpack_from("<BBBBHHIH", g, o)
        entries.append((w, h, colors, planes, bpp, size, iid))

    # 组里条目顺序 = ICO 目录顺序；数据按 id 取
    hdr = struct.pack("<HHH", 0, 1, count)
    dirs = b""
    blobs = b""
    offset = 6 + count * 16
    for w, h, colors, planes, bpp, size, iid in entries:
        blob = icons.get(iid)
        if blob is None:
            raise SystemExit("[FAIL] 组里引用了不存在的图标 id=%d" % iid)
        dirs += struct.pack("<BBBBHHII", w, h, colors, 0, planes, bpp,
                            len(blob), offset)
        blobs += blob
        offset += len(blob)

    ico = hdr + dirs + blobs
    io.open(out, "wb").write(ico)
    print("[OK] %s -> %s（%d 个尺寸，%d 字节）" % (
        os.path.basename(exe), out, count, len(ico)))
    for w, h, colors, planes, bpp, size, iid in entries:
        print("     %dx%d %dbpp %d bytes (id=%d)" % (w or 256, h or 256, bpp, size, iid))


if __name__ == "__main__":
    main()
