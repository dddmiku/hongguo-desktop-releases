# -*- coding: utf-8 -*-
"""红果桌面版后端补丁（版本无关）。

1) 清晰度透传: HLS 会话接受 quality, 白名单校验后交给选轨。
2) 切换提速: 把已转码的 H.264 缓存交给 HLS 编码器, 对 H.264 源走 stream-copy
   (实测 3.8s 转码 -> 0.16s 复制)。仅 start_seconds==0 时走快路径。

用法: python patches/patch_backend.py <上游 backend 目录> [输出目录]
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

QUALITY_BLOCK = '''

# ---- 本地维护: 播放器可选清晰度 ----
DESKTOP_QUALITY_CHOICES = ("auto", "1080p", "720p", "540p", "480p", "360p")


def normalize_desktop_quality(value):
    """把播放器传来的档位规范成选轨用的名称。

    auto 走上游原有的 desktop-resolution-v1 策略; 其余档位原样交给
    offline_dl._pick_track（不存在时回退到不超过请求的最高一档）。
    """
    text = (value or "auto").strip().lower()
    if text not in DESKTOP_QUALITY_CHOICES:
        raise HTTPException(400, "Unsupported desktop quality")
    return "desktop-resolution-v1" if text == "auto" else text
'''

COPY_HELPER = '''

# ---- 本地维护: 已转码 H.264 源的 stream-copy 快路径 ----
def _hq_hls_options(directory):
    return {
        "hls_time": "2", "hls_list_size": "0", "hls_playlist_type": "event",
        "hls_segment_type": "fmp4", "hls_fmp4_init_filename": "init.mp4",
        "hls_segment_filename": (directory / "seg%06d.m4s").as_posix(),
        "hls_flags": "temp_file+independent_segments",
    }


def _hq_copy_hls(source, directory, on_ready, cancelled, max_output_bytes):
    """把已是 H.264/AAC 的源直接复制成 fmp4 HLS 分片（不重新编码）。

    只用于本机缓存产物: 整集、从 0 开始、编码已符合播放器要求。
    """
    import av
    source, directory = Path(source), Path(directory)
    # 快路径由 encode_hls 提前返回进入, 原路径的 mkdir 不会执行, 这里自行创建。
    directory.mkdir(parents=False, exist_ok=False)
    ready = False
    with av.open(str(source)) as reader:
        if len(reader.streams.video) != 1:
            raise ValueError("Expected one video track")
        with av.open((directory / "index.m3u8").as_posix(), "w", format="hls",
                     options=_hq_hls_options(directory)) as writer:
            streams = {}
            for stream in reader.streams:
                if stream.type not in ("video", "audio"):
                    continue
                target = writer.add_stream_from_template(stream)
                if stream.codec_context.name == "aac":
                    target.codec_context.codec_tag = "mp4a"
                streams[stream.index] = target
            if not streams:
                raise ValueError("No audio/video streams to copy")
            seen = 0
            for packet in reader.demux():
                if cancelled():
                    raise EncodingCancelled("Desktop encode cancelled")
                seen += 1
                if seen % 64 == 0 and sum(
                        p.stat().st_size for p in directory.iterdir() if p.is_file()) > max_output_bytes:
                    raise ValueError("Desktop segment budget exceeded")
                if packet.dts is None or packet.stream.index not in streams:
                    continue
                packet.stream = streams[packet.stream.index]
                writer.mux(packet)
                if not ready and (directory / "index.m3u8").is_file():
                    ready = True
                    on_ready()
    playlist = (directory / "index.m3u8").read_text(encoding="utf-8")
    if "#EXT-X-ENDLIST" not in playlist or not (directory / "seg000000.m4s").is_file():
        raise ValueError("No complete desktop segments")
    if not ready:
        on_ready()
    if cancelled():
        raise EncodingCancelled("Desktop encode cancelled")
    (directory / "complete.marker").write_text("desktop-hls-v1\\n", encoding="ascii")
    return directory / "index.m3u8"
'''

INJECT = '''        # 本地维护: 已是 H.264 的整集缓存无需重编码（仅 start_seconds==0）。
        if not start_seconds:
            try:
                import av as _hq_av
                with _hq_av.open(str(source)) as _hq_probe:
                    _hq_codec = (_hq_probe.streams.video[0].codec_context.name
                                 if _hq_probe.streams.video else "")
            except Exception:
                _hq_codec = ""
            if _hq_codec == "h264":
                return _hq_copy_hls(source, directory, on_ready, cancelled, max_output_bytes)
'''

# ---- 本地维护: 编码池有界排队（切集不再瞬间 503） ----
POOL_OLD = '''        with self.guard:
            self._expire()
            active = sum(not job.done.is_set() for job in self.jobs.values())
            if len(self.jobs) >= self.max_jobs or active >= self.max_workers:
                raise HTTPException(503, "Desktop encoder is busy; retry shortly")
            identifier = uuid.uuid4().hex
            job = Job(identifier, self.root / identifier,
                      start_seconds=float(start_seconds), window_origin=None if start_seconds else 0.0)
            self.jobs[identifier] = job
            threading.Thread(target=self._run, args=(job, series_id, episode), daemon=True).start()
            return job
'''

POOL_NEW = '''        # 本地维护: 编码池改为「有界排队」。
        # 上游原来在槽位占满时立刻 503；而切集瞬间旧任务还在收尾，
        # 于是下一集必然报「媒体准备失败」。这里改为短暂等待槽位，
        # 并把「已取消、尚未收尾」的任务排除在占用之外。
        deadline = time.monotonic() + self.queue_wait
        while True:
            with self.guard:
                self._expire()
                live = sum(not job.cancelled.is_set() for job in self.jobs.values())
                active = sum(not job.done.is_set() and not job.cancelled.is_set()
                             for job in self.jobs.values())
                if live < self.max_jobs and active < self.max_workers:
                    identifier = uuid.uuid4().hex
                    job = Job(identifier, self.root / identifier,
                              start_seconds=float(start_seconds),
                              window_origin=None if start_seconds else 0.0)
                    self.jobs[identifier] = job
                    threading.Thread(target=self._run, args=(job, series_id, episode),
                                     daemon=True).start()
                    return job
            if time.monotonic() >= deadline:
                raise HTTPException(503, "Desktop encoder is busy; retry shortly")
            time.sleep(0.15)
'''

POOL_INIT_OLD = "encoder=encode_hls, max_jobs=4, max_workers=2, idle_seconds=300):"
POOL_INIT_NEW = "encoder=encode_hls, max_jobs=8, max_workers=3, idle_seconds=300, queue_wait=6.0):"

# 把 queue_wait 保存到实例上（上游只保存三个属性）。
POOL_ATTR_OLD = "self.max_jobs, self.max_workers, self.idle_seconds = max_jobs, max_workers, idle_seconds"
POOL_ATTR_NEW = ("self.max_jobs, self.max_workers, self.idle_seconds = max_jobs, max_workers, idle_seconds\n"
                 "        self.queue_wait = float(queue_wait)")

# ---- 本地维护: 取消信号透传（切集时旧任务立刻停） ----
SRC_LOADER_OLD = "            source = self.source_loader(series_id, episode)\n"
SRC_LOADER_NEW = '''            # 本地维护: 把取消信号透传给取源/转码, 切集时旧任务能立刻停下,
            # 不再长时间占着工作槽位。上游 source_loader 只收 3 个参数。
            try:
                source = self.source_loader(series_id, episode, quality,
                                            cancelled=job.cancelled.is_set)
            except TypeError:
                source = self.source_loader(series_id, episode, quality)
'''

ENCODE_SIG_OLD = "def encode_h264(source):"
ENCODE_SIG_NEW = "def encode_h264(source, cancelled=None):"

ENCODE_LOOP_OLD = "                for packet in reader.demux():\n"
ENCODE_LOOP_NEW = '''                for packet in reader.demux():
                    # 本地维护: 切集/关播放器时立刻停止转码, 不空占编码槽位。
                    if cancelled is not None and cancelled():
                        raise ValueError("Desktop encode cancelled")
'''

SRC_SIG_OLD = 'def _desktop_source(series_id, episode, quality="desktop-resolution-v1"):'
SRC_SIG_NEW = 'def _desktop_source(series_id, episode, quality="desktop-resolution-v1", cancelled=None):'

SRC_CALL_OLD = "        return encode_h264(decrypted)"
SRC_CALL_NEW = "        return encode_h264(decrypted, cancelled)"



class Fail(SystemExit):
    pass


def sub_once(text, pattern, repl, label, count=1):
    new, n = re.subn(pattern, repl, text)
    if n != count:
        raise Fail("[FAIL] %s: 命中 %d 次, 预期 %d 次" % (label, n, count))
    return new


def patch_hls(src_dir, out_dir):
    p_in = os.path.join(src_dir, "desktop_hls.py")
    p_out = os.path.join(out_dir, "desktop_hls.py")
    s = io.open(p_in, encoding="utf-8").read()
    if "_hq_copy_hls" in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   desktop_hls.py 已打过补丁（跳过）")
        return
    m = re.search(r"def encode_hls\(", s)
    if not m:
        raise Fail("[FAIL] 未找到 encode_hls")
    body = s.find("directory.mkdir(parents=False, exist_ok=False)", m.start())
    if body < 0:
        raise Fail("[FAIL] 未找到目录创建语句")
    # 注入点必须在 mkdir 之前: _hq_copy_hls 会自己建目录,
    # 若在其后再走原路径会因目录已存在而失败。
    line_start_pre = s.rfind("\n", 0, body) + 1
    # 按该语句的实际缩进注入，避免不同版本缩进层级不同导致语法错误。
    line_start = s.rfind("\n", 0, body) + 1
    indent = s[line_start:body]
    if indent.strip():
        raise Fail("[FAIL] 目录创建语句缩进异常")
    if "start_seconds" not in s[m.start():m.start() + 400]:
        # 该版本没有分段播放，直接整集快路径即可。
        inject = INJECT.replace("if not start_seconds:", "if True:")
    else:
        inject = INJECT
    inject = "".join(indent + ln[8:] + "\n" if ln.startswith("        ") else ln + "\n"
                     for ln in inject.rstrip("\n").split("\n"))
    s = s[:line_start_pre] + inject + s[line_start_pre:]
    s = s.rstrip("\n") + "\n" + COPY_HELPER
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   desktop_hls.py  (H.264 源 stream-copy)")


def patch_service(src_dir, out_dir):
    p_in = os.path.join(src_dir, "desktop_hls_service.py")
    p_out = os.path.join(out_dir, "desktop_hls_service.py")
    s = io.open(p_in, encoding="utf-8").read()
    if "normalize_desktop_quality" in s and "queue_wait" in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   desktop_hls_service.py 已打过补丁（跳过）")
        return
    if "queue_wait" not in s:
        s = sub_once(s, re.escape(POOL_INIT_OLD), POOL_INIT_NEW, "编码池默认值")
        s = sub_once(s, re.escape(POOL_ATTR_OLD), POOL_ATTR_NEW, "编码池 queue_wait 属性")
        s = sub_once(s, re.escape(POOL_OLD), POOL_NEW, "编码池有界排队")
    s = sub_once(s, r"def create\(self, series_id, episode(, \*)?(, start_seconds=0)?\):",
                 lambda m: m.group(0)[:-2] + ', quality="auto"):', "create 形参")
    s = sub_once(s, r"def _run\(self, job, series_id, episode\):",
                 'def _run(self, job, series_id, episode, quality="auto"):', "_run 形参")
    s = sub_once(s, r"args=\(job, series_id, episode\)",
                 "args=(job, series_id, episode, quality)", "线程参数")
    # 上游本身就含 "cancelled=job.cancelled.is_set"(编码器调用处),
    # 所以幂等标记必须用 source_loader 自己的签名。
    if "self.source_loader(series_id, episode, quality," not in s:
        # 注意: 模式连同行首缩进一起匹配, 否则替换后会多出一层缩进。
        s = sub_once(s, r"            source = self\.source_loader\(series_id, episode\)",
                     SRC_LOADER_NEW.rstrip("\n"), "source_loader 取消透传")
    s = sub_once(s, r"def prepare\((.*?)start_seconds: float = 0\):",
                 lambda m: 'def prepare(%sstart_seconds: float = 0, quality: str = "auto"):' % m.group(1),
                 "prepare 形参")
    s = sub_once(s, r'\{"series_id", "ep", "start_seconds"\}',
                 '{"series_id", "ep", "start_seconds", "quality"}', "prepare 白名单")
    s = sub_once(s, r"jobs\.create\(series_id, ep, start_seconds=start_seconds\)",
                 "jobs.create(series_id, ep, start_seconds=start_seconds, "
                 "quality=normalize_desktop_quality(quality))", "prepare 调用")
    s = s.rstrip("\n") + "\n" + QUALITY_BLOCK
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   desktop_hls_service.py  (quality 透传)")


def patch_server(src_dir, out_dir):
    p_in = os.path.join(src_dir, "server.py")
    p_out = os.path.join(out_dir, "server.py")
    s = io.open(p_in, encoding="utf-8").read()
    if "encode_h264(decrypted)" in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   server.py 已打过补丁（跳过）")
        return
    m = re.search(r"def _desktop_source\(series_id, episode\):\n(.*?)\n\n", s, re.S)
    if not m:
        raise Fail("[FAIL] 未找到 _desktop_source")
    old = m.group(1)
    if "_ensure_decrypted" not in old:
        raise Fail("[FAIL] _desktop_source 内未找到 _ensure_decrypted")
    new = old.replace(
        'return _ensure_decrypted(str(target["vid"]), "desktop-resolution-v1")',
        "# 清晰度由 HLS 路由校验后传到这里。\n"
        "        decrypted = _ensure_decrypted(str(target[\"vid\"]), quality or \"desktop-resolution-v1\")\n"
        "        # 交给 HLS 编码器已转码的 H.264 缓存:\n"
        "        # stream-copy 约 0.16s, 而重编码 HEVC 约 3.8s。\n"
        "        from desktop_encode import encode_h264\n"
        "        return encode_h264(decrypted, cancelled)")
    if new == old:
        raise Fail("[FAIL] _desktop_source 未匹配到返回语句")
    s = s[:m.start()] + (SRC_SIG_NEW + "\n") \
        + new + "\n\n" + s[m.end():]
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   server.py  (清晰度选轨 + H.264 缓存直通)")


def patch_encode(src_dir, out_dir):
    p_in = os.path.join(src_dir, "desktop_encode.py")
    p_out = os.path.join(out_dir, "desktop_encode.py")
    if not os.path.isfile(p_in):
        return
    s = io.open(p_in, encoding="utf-8").read()
    if "cancelled is not None and cancelled()" in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   desktop_encode.py 已打过补丁（跳过）")
        return
    s = sub_once(s, re.escape(ENCODE_SIG_OLD), ENCODE_SIG_NEW, "encode_h264 形参")
    s = sub_once(s, re.escape(ENCODE_LOOP_OLD.rstrip("\n")),
                 ENCODE_LOOP_NEW.rstrip("\n"), "encode_h264 取消检查")
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   desktop_encode.py  (取消透传)")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "_v109", "extracted", "backend")
    dst = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "src", "backend")
    os.makedirs(dst, exist_ok=True)
    for name in os.listdir(src):
        if name.endswith(".py") or name.endswith(".txt"):
            io.open(os.path.join(dst, name), "w", encoding="utf-8", newline="").write(
                io.open(os.path.join(src, name), encoding="utf-8").read())
    patch_hls(src, dst)
    patch_service(src, dst)
    patch_server(src, dst)
    patch_encode(src, dst)


if __name__ == "__main__":
    main()