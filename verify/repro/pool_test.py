# -*- coding: utf-8 -*-
"""\u7f16\u7801\u6c60\u884c\u4e3a\u9a8c\u6536: \u5bf9\u6bd4\u4e0a\u6e38\u539f\u7248 vs \u8865\u4e01\u540e\u3002"""
import importlib.util, io, os, shutil, sys, tempfile, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def load(module_path, name):
    spec = importlib.util.spec_from_file_location(name, module_path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def run_case(service_dir, label):
    # \u6a21\u62df\u4e00\u4e2a\u6162\u8f6c\u7801: \u5360\u7528\u69fd\u4f4d 2 \u79d2\u3002
    for name in list(sys.modules):
        if name.startswith("desktop_hls"):
            del sys.modules[name]
    sys.path.insert(0, service_dir)
    try:
        svc = load(os.path.join(service_dir, "desktop_hls_service.py"), "svc_under_test")

        started = threading.Event()
        release = threading.Event()

        def slow_encoder(source, directory, on_ready, cancelled=lambda: False, **kw):
            import pathlib
            d = pathlib.Path(directory)
            d.mkdir(parents=False, exist_ok=True)
            started.set()
            for _ in range(200):
                if cancelled():
                    raise svc.EncodingCancelled()
                time.sleep(0.02)
            (d / "index.m3u8").write_text("#EXTM3U\n#EXT-X-ENDLIST\n", encoding="utf-8")
            (d / "seg000000.m4s").write_bytes(b"x")
            (d / "complete.marker").write_text("desktop-hls-v1\n", encoding="ascii")
            on_ready()

        work = tempfile.mkdtemp(prefix="hqpool-")
        jobs = svc.HlsJobs(work, lambda *a, **k: "fake.mp4", encoder=slow_encoder,
                           max_jobs=4, max_workers=2, idle_seconds=300)
        results = []

        def post(ep):
            try:
                job = jobs.create("7688646466708966462", ep)
                results.append((ep, "accepted", job.id))
            except Exception as exc:
                code = getattr(exc, "status_code", None) or getattr(exc, "detail", None)
                results.append((ep, "rejected", str(code)))

        t0 = time.monotonic()
        threads = [threading.Thread(target=post, args=(ep,)) for ep in (1, 2, 3)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=30)
        elapsed = time.monotonic() - t0

        print("[%s] max_jobs=%s max_workers=%s queue_wait=%s" % (
            label, jobs.max_jobs, jobs.max_workers, getattr(jobs, "queue_wait", "N/A")))
        for ep, state, info in sorted(results):
            print("   ep=%d -> %-9s %s" % (ep, state, info))
        print("   \u8017\u65f6 %.2fs" % elapsed)
        shutil.rmtree(work, ignore_errors=True)
        return results
    finally:
        sys.path.remove(service_dir)


base = os.path.join(HERE, "..", "base", "backend")
patched = os.path.join(HERE, "out_backend")
print("=" * 62)
a = run_case(os.path.abspath(base), "\u57fa\u7ebf \u4e0a\u6e38\u539f\u7248")
print("=" * 62)
b = run_case(os.path.abspath(patched), "\u4fee\u6539\u540e")
print("=" * 62)
ok = (sum(1 for r in a if r[1] == "accepted") == 2 and
      sum(1 for r in b if r[1] == "accepted") == 3)
print("BASELINE accepted =", sum(1 for r in a if r[1] == "accepted"), "(\u9884\u671f 2)")
print("MODIFIED accepted =", sum(1 for r in b if r[1] == "accepted"), "(\u9884\u671f 3)")
print("[PASS]" if ok else "[FAIL]")
sys.exit(0 if ok else 1)
