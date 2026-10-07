# -*- coding: utf-8 -*-
"""把 exe 里内嵌的 tauri.conf.json 作者信息换成本维护分支的仓库（等长覆盖）。

为什么不能用 tauri_assets 那套：
    前端资源是 Brotli 流，有 32 字节资源表（key_ptr/key_len/blob_ptr/blob_len）
    可以定位和回写。而 tauri.conf.json 是**编译进 .rdata 的普通字符串字面量**，
    既不在资源表里，也没有绝对指针引用 —— 编译器把它当成
        lea  rax, [rip+disp]          ; 字面量起始地址
        mov  [rbp+0x88], rax
        mov  qword [rbp+0x90], 0x31a  ; 长度 794，编译期立即数
    这种形态。长度是立即数，所以字面量后面紧跟的字符串（`main`/`hongguo-main`）
    没有任何指针指向，**不能移动任何字节**。

做法：把新 JSON 用纯空白（CRLF 空行）补齐到与原来**完全相同的字节数**，
原地覆盖。这样：
    * 长度立即数不用改；
    * 后面所有数据偏移不变；
    * 原作者的仓库/邮箱/B站地址在整个文件里一个字节都不剩。

JSON 尾部补空白是合法的（原本就带一个尾随 CRLF，解析器今天就在吃它）。

用法:
    python tools/rebrand_exe.py <exe> [--repo <仓库地址>] [--check]

    --check  只检查当前 exe 里还有没有原作者信息，不改写。
"""
import io
import json
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 原作者（上游）的标识，用于定位 + 事后校验。
OLD_OWNER_MARKERS = (
    b"waligoraamodio288-rgb",
    b"WaligoraAmodio288",
    b"space.bilibili.com/42310326",
)
# 内嵌 config 的锚点：根对象第一个成员就是 author。
ANCHOR = b'"author": {'
DEFAULT_REPO = "https://github.com/dddmiku/hongguo-desktop-releases"

# 内嵌 config 里另有一处上游标识：updater 的更新端点。
# 这条是**死配置**（前端更新桥已被 patch_frontend 第 15 步改成永远返回
# 「无更新」，后端另有兜底），但里面带着原作者的仓库名，要一并清掉。
# 它没有资源表、也没有找到指向它的指针/长度立即数，所以同样等长原地覆盖。
OLD_ENDPOINT = (b"https://github.com/waligoraamodio288-rgb/"
                b"hongguo-desktop-releases/releases/latest/download/latest.json")


def rewrite_endpoint(d, repo):
    """把内嵌 config 的更新端点换成自己的仓库地址（等长，用查询串补齐）。

    为什么用 `?` 补齐：URL 长度必须与原来完全一致（102 字节）才能原地覆盖
    而不动任何偏移。查询串是合法 URL 语法，且不会被发给服务器参与路径匹配。
    """
    fo = d.find(OLD_ENDPOINT)
    if fo < 0:
        return None
    length = len(OLD_ENDPOINT)
    want = repo.rstrip("/") + "/releases/latest/download/latest.json"
    body = want.encode("utf-8")
    if len(body) > length:
        raise SystemExit("[FAIL] 新更新端点 %d 字节 > 原 %d 字节" % (len(body), length))
    pad = length - len(body)
    if pad:
        body += (b"?hq=" + b"0" * (pad - 4)) if pad >= 4 else b"?" * pad
    assert len(body) == length
    d[fo:fo + length] = body
    return fo


def _image_base_and_sections(d):
    pe = d.find(b"PE\x00\x00")
    if pe < 0:
        raise SystemExit("[FAIL] 不是 PE 文件")
    nsec = struct.unpack_from("<H", d, pe + 6)[0]
    optsz = struct.unpack_from("<H", d, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", d, opt)[0]
    ib = (struct.unpack_from("<Q", d, opt + 24)[0] if magic == 0x20b
          else struct.unpack_from("<I", d, opt + 28)[0])
    sec0 = opt + optsz
    secs = []
    for i in range(nsec):
        o = sec0 + i * 40
        nm = d[o:o + 8].rstrip(b"\x00").decode(errors="replace")
        vs, va, rs, rp = struct.unpack_from("<IIII", d, o + 8)
        secs.append((nm, va, vs, rp, rs))
    return ib, secs


def _fo_to_rva(secs, fo):
    for nm, va, vs, rp, rs in secs:
        if rp <= fo < rp + rs:
            return va + (fo - rp)
    return None


def _rva_to_fo(secs, rva):
    for nm, va, vs, rp, rs in secs:
        if va <= rva < va + vs:
            off = rva - va
            if off < rs:
                return rp + off
    return None


def locate(d):
    """定位内嵌作者 JSON：返回 (起始偏移, 字节长度)。

    长度从引用它的 `mov qword [..], imm32` 里读，而不是靠数大括号 ——
    上游改格式也不会错。
    """
    ib, secs = _image_base_and_sections(d)
    i = d.find(ANCHOR)
    if i < 0:
        raise SystemExit("[FAIL] exe 里找不到内嵌 config 的 author 段")
    # 回退到根对象的 '{'（author 是根对象第一个成员）
    start = d.rfind(b"{", 0, i)
    if start < 0:
        raise SystemExit("[FAIL] author 段前面找不到根对象起始 '{'")
    rva = _fo_to_rva(secs, start)
    if rva is None:
        raise SystemExit("[FAIL] author 段不在任何节里（偏移 %#x）" % start)
    target = ib + rva
    # 找指向该地址的 rip 相对 lea，再往后找长度立即数
    for fo in range(len(d) - 10):
        if d[fo] != 0x48 or d[fo + 1] != 0x8D:
            continue
        if (d[fo + 2] & 0xC7) != 0x05:
            continue
        disp = struct.unpack_from("<i", d, fo + 3)[0]
        frva = _fo_to_rva(secs, fo)
        if frva is None:
            continue
        if ib + frva + 7 + disp != target:
            continue
        # lea 后面 32 字节内找 `48 c7 8? imm32`（mov qword [reg+disp8], imm32）
        for k in range(fo + 7, fo + 7 + 40):
            if d[k] == 0x48 and d[k + 1] == 0xC7 and (d[k + 2] & 0xF8) == 0x80:
                imm = struct.unpack_from("<i", d, k + 7)[0]
                if 16 <= imm <= 65535:
                    return start, imm, fo, k + 7
    raise SystemExit("[FAIL] 没找到引用 author 段的长度立即数（上游改写法了，需人工确认）")


def build_author_json(repo, length):
    """生成新的 author 段，用 CRLF 空行补齐到指定字节数（纯空白，不改语义）。"""
    issues = repo.rstrip("/") + "/issues/new/choose"
    obj = {
        "author": {"url": repo, "copyValue": repo},
        "feedback": {"url": issues, "copyValue": issues},
        "request": {"url": issues, "copyValue": issues},
    }
    text = json.dumps(obj, ensure_ascii=False, indent=2).replace("\n", "\r\n")
    body = text.encode("utf-8")
    if len(body) > length:
        raise SystemExit(
            "[FAIL] 新 author 段 %d 字节 > 原 %d 字节，无法等长覆盖；"
            "请缩短仓库地址" % (len(body), length))
    pad = length - len(body)
    if pad % 2:
        raise SystemExit("[FAIL] 补齐量 %d 不是偶数，无法用 CRLF 填满" % pad)
    body += b"\r\n" * (pad // 2)
    assert len(body) == length
    # 必须仍是合法 JSON：尾随空白是允许的（原本就带一个尾随 CRLF）
    assert json.loads(body.decode("utf-8")) == obj
    return body


def rebrand_file(exe, repo=DEFAULT_REPO, check=False, quiet=False):
    """原地重打标一个 exe；返回 0 成功 / 1 有原作者残留或失败。

    供 tools/auto_patch.py 在上游发版后自动重注入时调用，也可命令行直接用。
    """
    def say(*a):
        if not quiet:
            print(*a)

    d = bytearray(io.open(exe, "rb").read())
    start, length, lea_fo, imm_fo = locate(d)

    if check:
        left = [m.decode() for m in OLD_OWNER_MARKERS if m in d]
        say("author 段   : %#x..%#x (%d 字节)" % (start, start + length, length))
        say("引用点      : lea @%#x, 长度立即数 @%#x" % (lea_fo, imm_fo))
        say("剩余原作者串: %s" % (left or "无"))
        return 0 if not left else 1

    old = bytes(d[start:start + length])
    body = build_author_json(repo, length)
    if len(body) != length:
        raise SystemExit("[FAIL] 长度不符")
    d[start:start + length] = body
    ep_fo = rewrite_endpoint(d, repo)
    io.open(exe, "wb").write(bytes(d))

    left = [m.decode() for m in OLD_OWNER_MARKERS if m in bytes(d)]
    say("[OK] author 段 %#x 原地覆盖 %d 字节" % (start, length))
    say("     仓库 -> %s" % repo)
    say("     原内容前 60 字节: %r" % old[:60])
    if ep_fo is None:
        say("[!] 未找到内嵌更新端点（可能已被上游移除）")
    else:
        say("[OK] 更新端点 %#x 原地覆盖 %d 字节" % (ep_fo, len(OLD_ENDPOINT)))
    if left:
        say("[!] 仍有原作者串残留: %s" % left)
        return 1
    say("[OK] 原作者信息（仓库 / 邮箱 / B站 / 更新端点）已全部清除")
    return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    repo = DEFAULT_REPO
    if "--repo" in sys.argv:
        repo = sys.argv[sys.argv.index("--repo") + 1]
        args = [a for a in args if a != repo]
    if not args:
        raise SystemExit(__doc__)
    return rebrand_file(args[0], repo=repo, check=check)


if __name__ == "__main__":
    sys.exit(main())
