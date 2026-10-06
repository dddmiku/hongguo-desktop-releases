# -*- coding: utf-8 -*-
"""三态行为验证: BASELINE / MODIFIED / ROLLBACK 用同一命令与输入。"""
import importlib.util
import io
import os
import shutil
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def run_case(service_dir, label):
    for name in list(sys.modules):
        if name.startswith("desktop_hls") or name.startswith("svc_under_test"):
            del sys.modules[name]
    sys.path.insert(0, service_dir)
    try:
        svc = load(os.path.join(service_dir, "desktop_hls_service.py"), "svc_under_test")
        release = threading.Event()

        def slow_encoder(source, directory, on_ready, cancelled=lambda: False, **kw):
            import pathlib
            d = pathlib.Path(directory)
            d.mkdir(parents=False, exist_ok=True)
            for _ in range(200):
                if cancelled():
                    raise svc.EncodingCancelled()
                time.sleep(0.02)
            (d / "index.m3u8").write_text("#EXTM3U\n#EXT-X-ENDLIST\n", encoding="utf-8")
            (d / "seg000000.m4s").write_bytes(b"x")
            (d / "complete.marker").write_text("desktop-hls-v1\n", encoding="ascii")
            on_ready()

        work = tempfile.mkdtemp(prefix="hq3-")
        jobs = svc.HlsJobs(work, lambda *a, **k: "fake.mp4", encoder=slow_encoder,
                           max_jobs=4, max_workers=2, idle_seconds=300)
        results = []

        def post(ep):
            try:
                jobs.create("7688646466708966462", ep)
                results.append((ep, "accepted"))
            except Exception as exc:
                results.append((ep, "rejected", str(getattr(exc, "status_code", ""))))

        threads = [threading.Thread(target=post, args=(ep,)) for ep in (1, 2, 3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=30)
        accepted = sum(1 for r in results if r[1] == "accepted")
        print("[%s]" % label)
        for r in sorted(results):
            print("   ep=%d -> %s" % (r[0], " ".join(r[1:])))
        print("   accepted = %d" % accepted)
        shutil.rmtree(work, ignore_errors=True)
        return accepted
    finally:
        sys.path.remove(service_dir)


cases = [
    ("BASELINE", os.path.join(ROOT, "base", "backend")),
    ("MODIFIED", os.path.join(ROOT, "src", "backend")),
    ("ROLLBACK", os.path.join(HERE, "_rbtest", "backend")),
]
counts = {}
for label, path in cases:
    if not os.path.isdir(path):
        print("[SKIP] %s: 目录不存在 %s" % (label, path))
        continue
    counts[label] = run_case(os.path.abspath(path), label)
    print("-" * 56)

ok = counts.get("BASELINE") == 2 and counts.get("MODIFIED") == 3 and counts.get("ROLLBACK") == 2
print("BASELINE accepted =", counts.get("BASELINE"), "(预期 2)")
print("MODIFIED accepted =", counts.get("MODIFIED"), "(预期 3)")
print("ROLLBACK accepted =", counts.get("ROLLBACK"), "(预期 2，与 BASELINE 一致)")
print("[PASS]" if ok else "[FAIL]")
sys.exit(0 if ok else 1)
