import brotli, struct, hashlib, io, os
exe = r"C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\dist\hongguo-desktop-companion.exe"
data = io.open(exe,"rb").read()
ASSETS = [("app.css",0xc41b42,0xc8a500),("index.html",0xc442a1,0xc8a520),("app.js",0xc443b1,0xc8a540)]
for name, off, len_off in ASSETS:
    n = struct.unpack_from("<Q", data, len_off)[0]
    raw = brotli.decompress(data[off:off+n])
    print(f"{name}: clen={n} raw={len(raw)} sha={hashlib.sha256(raw).hexdigest()[:16]}")
    io.open(rf"C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_extract\verify_{name}.bin","wb").write(raw)
# compare with src
for name in ("app.css","index.html","app.js"):
    a = io.open(rf"C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\src\frontend\{name}","rb").read()
    b = io.open(rf"C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_extract\verify_{name}.bin","rb").read()
    print(f"  {name}: embedded==src -> {a==b}")
print()
s = io.open(r"C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_extract\verify_app.js.bin", encoding="utf-8").read()
for k in ["[.75,1,1.25,1.5,2,2.5,3]","hqQuals","hqWithQual","清晰度","默认清晰度","quality"]:
    print(f"  {k}: {s.count(k)}")
