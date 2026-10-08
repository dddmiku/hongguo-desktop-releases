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

# ---- 本地维护: 看过的集自动清理缓存 ----
# 上游只写不清，缓存会无限增长（实测曾达 28.8GB）。
# 清理策略：一集播完就把它的两份缓存（解密源 + 转码产物）删掉。
CLEANUP_BLOCK = '''


# ---- 本地维护: 看过的集自动清理缓存 ----
# 注意：启动清扫 HLS 残留目录的逻辑内联在调用点（见 patch_server 的「启动清扫」步骤），
# 不在这里定义函数 —— 这里在文件末尾，定义在后、调用在前会 NameError。
# 曾经留过一个同名的 _hq_sweep_stale_sessions 定义但从未被调用（死代码），已删除。


def _hq_cleanup_episode(series_id, episode):
    """删掉这一集的本地缓存（解密源 + H.264 转码产物）。

    定位方式：先用章节接口把 episode 换成 vid，再按 vid 删。
    只删命名模式匹配的文件，绝不扫目录、绝不删目录本身。
    """
    import glob as _g
    try:
        _, episodes = H.get_episodes(series_id)
        target = next((it for it in episodes if it.get("index") == episode), None)
        if not target:
            return 0
        vid = str(target.get("vid", ""))
        if not re.fullmatch(r"[0-9]{8,24}", vid):
            return 0
        removed = 0
        for pattern in (f"{vid}_*.mp4", f"{vid}_*.desktop-h264-v1.mp4"):
            for path in _g.glob(os.path.join(STREAM_CACHE, pattern)):
                try:
                    if os.path.isfile(path):
                        os.remove(path)
                        removed += 1
                except OSError:
                    pass
        return removed
    except Exception:
        return 0


def _hq_cache_cap(max_bytes=0, keep_files=8):
    """控制本地缓存规模。

    1) 按最后修改时间保留最近 keep_files 个文件，其余删除。
       播放器一次只看一集，预取最多提前 2 集，
       所以被淘汰的文件必然是看过的集数。
    2) 若总量仍超过 max_bytes，继续从最旧的删。

    只删匹配 *.mp4 的普通文件，不扫目录、不删目录。
    """
    import glob as _g
    try:
        files = []
        total = 0
        for path in _g.glob(os.path.join(STREAM_CACHE, "*.mp4")):
            try:
                if os.path.isfile(path):
                    size = os.path.getsize(path)
                    files.append((os.path.getmtime(path), size, path))
                    total += size
            except OSError:
                pass
        if not files:
            return 0
        files.sort(key=lambda it: it[0], reverse=True)
        removed = 0
        for _, size, path in files[keep_files:]:
            try:
                os.remove(path)
                total -= size
                removed += 1
            except OSError:
                pass
        if max_bytes and total > max_bytes:
            target = int(max_bytes * 0.75)
            for _, size, path in files[:keep_files]:
                if total <= target:
                    break
                try:
                    if os.path.exists(path):
                        os.remove(path)
                        total -= size
                        removed += 1
                except OSError:
                    pass
        return removed
    except Exception:
        return 0



@app.get("/desktop/cleanup")
def desktop_cleanup(series_id: str, ep: int):
    """播放器告知某集已经看完，删掉它的本地缓存。"""
    if not re.fullmatch(r"[0-9]{8,24}", str(series_id)) or not 1 <= ep <= 100000:
        raise HTTPException(400, "Invalid episode identity")
    return {"removed": _hq_cleanup_episode(series_id, ep)}


def _hq_sweep_partial(max_age=900):
    """清掉转码/下载中途留下的孤儿文件。

    2026-10-07 实测：stream-cache 里积了 2 个 *.partial 共 71.5 MB。
    它们由 desktop_encode / desktop_remux 在异常退出时留下，
    而 _hq_cleanup_episode 只匹配 `*.mp4`、_hq_cache_cap 也只 glob `*.mp4`，
    所以这两个函数都碰不到它们 —— 属于永久泄漏。
    这里按 mtime 清理「超过 max_age 秒没被碰过」的 .partial / .part / .raw.mp4：
    正在写的文件 mtime 是新的，不会被误删。
    """
    import glob as _g
    removed = 0
    now = time.time()
    for pattern in ("*.partial", "*.part", "*.raw.mp4"):
        for path in _g.glob(os.path.join(STREAM_CACHE, pattern)):
            try:
                if not os.path.isfile(path):
                    continue
                if now - os.path.getmtime(path) < max_age:
                    continue
                os.remove(path)
                removed += 1
            except OSError:
                pass
    return removed


def _hq_prune_poster_cache(keep_files=400, max_age_days=14):
    """封面缓存（poster-cache-v1）没有上游清理逻辑，只写不清。

    2026-10-07 实测：624 个文件 / 113 MB，单日新增 333 个。
    目录由 Rust 侧写入（文件名形如 <seriesId>.cover），后端不参与写入，
    所以这里只做「按 mtime 淘汰」：先按年龄删，再按数量上限删。
    只删普通文件、只认 .cover 后缀，不碰目录。
    """
    import glob as _g
    root = os.environ.get("HONGGUO_POSTER_CACHE") or os.path.join(
        os.environ.get("HONGGUO_BACKEND_DATA_DIR") or "", "poster-cache-v1")
    if not root or not os.path.isdir(root):
        return 0
    removed = 0
    now = time.time()
    entries = []
    for path in _g.glob(os.path.join(root, "*.cover")):
        try:
            if not os.path.isfile(path):
                continue
            mtime = os.path.getmtime(path)
            entries.append((mtime, os.path.getsize(path), path))
        except OSError:
            pass
    if not entries:
        return 0
    entries.sort(reverse=True)
    cutoff = now - max_age_days * 86400
    for mtime, _size, path in entries:
        if mtime >= cutoff and len(entries) - removed <= keep_files:
            break
        try:
            os.remove(path)
            removed += 1
        except OSError:
            pass
    return removed
'''

CLEANUP_CALL_OLD = "        return _ensure_decrypted(str(target[\"vid\"]), \"desktop-resolution-v1\")"

# 追加到 server.py 文件末尾的启动清扫调用。
# 必须放在文件末尾：它调用的两个函数都定义在 CLEANUP_BLOCK（同样在末尾）。
# 放在中间会 NameError —— 2026-10-07 就是这么把后端起不来的。
HQ_STARTUP_SWEEP = '''


# ---- 本地维护: 启动时清掉「其它清理函数碰不到的」残留 ----
# 放在文件末尾，因为上面两个函数定义在 CLEANUP_BLOCK（也在这里）。
def _hq_startup_cache_sweep():
    try:
        n_partial = _hq_sweep_partial()
    except Exception:
        n_partial = 0
    try:
        n_poster = _hq_prune_poster_cache()
    except Exception:
        n_poster = 0
    if n_partial or n_poster:
        print("[server] 启动清扫: partial=%d poster=%d" % (n_partial, n_poster))
    return n_partial + n_poster


_hq_startup_cache_sweep()


@app.post("/desktop/cache/prune")
def desktop_cache_prune():
    """播放器关闭/切集时调一次，兜住「没看完就退出」的缓存。

    原先只有 onEnded 会触发清理：中途关播放器、快速切集、直接关软件
    这三条路径都不会清，缓存就一路涨。这里提供一个显式的兜底入口，
    按数量上限回收（保留最近的 keep_files 个），并清掉过期的 .partial。
    """
    keep = int(os.environ.get("HONGGUO_CACHE_KEEP_FILES") or 8)
    removed = _hq_cache_cap(int(os.environ.get("HONGGUO_CACHE_MAX_BYTES") or 0), keep)
    removed += _hq_sweep_partial()
    return {"removed": removed, "keepFiles": keep}
'''

# 上游重复注册的 /img 死代码（第二个定义，永远不会生效）。
# 保留它没有意义，而且它的域名校验更弱（host.endswith(h) 缺前导点，
# 会把 evilfqnovelpic.com 当合法图床），删掉。
DUPLICATE_IMG_DEAD = '''@app.get("/img")
def img(url: str):
    """封面图代理: 拉取并把HEIC转JPEG(浏览器不支持HEIC)。仅限字节图片域名。"""
    from urllib.parse import urlparse
    host = urlparse(url).hostname or ""
    if not any(host.endswith(h) for h in _IMG_HOSTS):
        raise HTTPException(400, "host not allowed")
    if url in _img_cache:
        return Response(_img_cache[url], media_type="image/jpeg",
                        headers={"Cache-Control": "max-age=86400"})
    try:
        raw = requests.get(url, timeout=20, verify=True).content
        if _IMG_OK:
            im = Image.open(io.BytesIO(raw)).convert("RGB")
            buf = io.BytesIO(); im.save(buf, "JPEG", quality=82); raw = buf.getvalue()
        if len(_img_cache) < 1000:
            _img_cache[url] = raw
        return Response(raw, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})
    except Exception as e:
        raise HTTPException(404, str(e))


'''

CLEANUP_CALL_NEW = (
    "        return _ensure_decrypted(str(target[\"vid\"]), \"desktop-resolution-v1\")\n"
    "        # 本地维护: 控制缓存上限（默认 4GB）。\n"
    "        # 注意：这里只是「把整集预转码」那一步拿掉的过渡形态；\n"
    "        # 真正带 _hq_cache_cap 的版本由步骤 1 直接产出（见上面）。\n"
)



class Fail(SystemExit):
    pass


def sub_once(text, pattern, repl, label, count=1):
    # repl 传字符串时，里面出现 \w \s 这类反斜杠序列会被 re.subn 当模板解析并
    # 报 "bad escape"。补丁注入的是真实代码而非模板，所以字符串一律用 lambda
    # 原样返回，绕开模板解析。repl 本身就是函数时（需要读捕获组）直接用它。
    fn = repl if callable(repl) else (lambda m, _r=repl: _r)
    new, n = re.subn(pattern, fn, text)
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
    """对 server.py 逐步骤打补丁（每步各自幂等）。

    为什么不是「整文件已打过就跳过」：
      2026-10-07 加了「移除重复 /img」这一步，但安装目录里的 server.py
      早就含 `encode_h264(decrypted` 与 `_hq_cleanup_episode`，
      旧的整体跳过判据直接 return，新步骤永远套不上——
      auto_patch 也会因为 MARKERS 命中而整文件跳过，死代码永久留在线上。
      所以改为：每一步用「自己的标记」判断是否需要执行，互不牵连。
      这样以后再加新步骤，对已打过旧补丁的安装目录同样能生效。
    """
    p_in = os.path.join(src_dir, "server.py")
    p_out = os.path.join(out_dir, "server.py")
    s = io.open(p_in, encoding="utf-8").read()
    steps = []

    # 步骤 1: _desktop_source 改为接受 quality + 走 H.264 缓存直通。
    if "边转边播" in s:
        steps.append("清晰度/缓存直通[已存在]")
    else:
        m = re.search(r"def _desktop_source\(series_id, episode[^)]*\):\n(.*?)\n\n",
                      s, re.S)
        if not m:
            raise Fail("[FAIL] 未找到 _desktop_source")
        old = m.group(1)
        if "_ensure_decrypted" not in old:
            raise Fail("[FAIL] _desktop_source 内未找到 _ensure_decrypted")
        new = old.replace(
            'return _ensure_decrypted(str(target["vid"]), "desktop-resolution-v1")',
            "# 清晰度由 HLS 路由校验后传到这里。\n"
            "        decrypted = _ensure_decrypted(str(target[\"vid\"]), quality or \"desktop-resolution-v1\")\n"
            "        # 本地维护: 控制缓存上限（默认 4GB）。\n"
            "        # 首集提速的关键：**不要**在这里把整集预转码。\n"
            "        # 实测（2026-10-08，110~188s 的 1080p 集）：\n"
            "        #   encode_h264(整集 HEVC->H.264) 要 12.9~24s，而 HLS 编码器本身是\n"
            "        #   增量切片 —— 写完第一个 2s 分片就置 ready，用户马上能播。\n"
            "        #   先整集转码等于把「能边转边播」退化成「转完才给看」。\n"
            "        # 对比（同一集，同一台机器）：\n"
            "        #   先整集转码再切片 = 12.91s 才出首片\n"
            "        #   直接边转边播     =  0.57s 出首片（22.5x）\n"
            "        # 已转码过的 H.264 缓存仍走 desktop_hls 里的 stream-copy 快路径\n"
            "        # （encode_hls 内部会探测源编码），所以复看依旧快。\n"
            "        _hq_cache_cap(int(os.environ.get(\"HONGGUO_CACHE_MAX_BYTES\") or 0),\n"
            "                      int(os.environ.get(\"HONGGUO_CACHE_KEEP_FILES\") or 8))\n"
            "        return decrypted")
        if new == old:
            raise Fail("[FAIL] _desktop_source 未匹配到返回语句")
        s = s[:m.start()] + (SRC_SIG_NEW + "\n") + new + "\n\n" + s[m.end():]
        steps.append("清晰度/缓存直通（边转边播，不做整集预转码）")

    # 步骤 2: 每次取源后回收缓存上限。依赖步骤 1 产出的那一行。
    if "_hq_cache_cap" in s:
        steps.append("缓存上限回收[已存在]")
    else:
        s = sub_once(s, re.escape(CLEANUP_CALL_OLD), CLEANUP_CALL_NEW, "缓存上限回收")
        steps.append("缓存上限回收")

    # 步骤 3: 删掉上游重复注册的 /img 死代码（弱域名校验那份）。
    if s.count('@app.get("/img")') == 1:
        steps.append("移除重复 /img[已存在]")
    else:
        s = sub_once(s, re.escape(DUPLICATE_IMG_DEAD), "", "移除重复 /img 死代码")
        steps.append("移除重复 /img")

    # 步骤 4: 清理接口 + 帮助函数（追加到文件末尾，只能追加一次）。
    if "_hq_cleanup_episode" in s:
        steps.append("清理接口[已存在]")
    else:
        s = s.rstrip("\n") + "\n" + CLEANUP_BLOCK
        steps.append("清理接口")

    # 步骤 5: 启动时清扫上次遗留的 HLS 工作目录。
    # 注意：清扫必须内联在调用点，不能放 CLEANUP_BLOCK —— 那里在文件末尾，
    # 定义在后、调用在前会 NameError，后端直接起不来（2026-10-07 踩过）。
    if "_hq_parent" in s:
        steps.append("启动清扫[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('_desktop_jobs = HlsJobs(os.environ["HONGGUO_HLS_WORK_DIR"], _desktop_source)'),
            # HONGGUO_HLS_WORK_DIR 指向「本次会话」目录（形如 desktop-hls-XXXXXX），
            # 上次崩溃遗留的是它的**兄弟目录**，所以要扫父目录。
            'try:\n'
            '        _hq_cur = os.path.abspath(os.environ["HONGGUO_HLS_WORK_DIR"])\n'
            '        _hq_parent = os.path.dirname(_hq_cur)\n'
            '        import shutil as _hq_sh\n'
            '        for _n in os.listdir(_hq_parent):\n'
            '            if not _n.startswith("desktop-hls-"):\n'
            '                continue\n'
            '            _old = os.path.join(_hq_parent, _n)\n'
            '            if os.path.abspath(_old) == _hq_cur:\n'
            '                continue\n'
            '            if not os.path.isdir(_old) or os.path.islink(_old):\n'
            '                continue\n'
            '            if os.path.dirname(os.path.abspath(_old)) != _hq_parent:\n'
            '                continue\n'
            '            try:\n'
            '                _hq_sh.rmtree(_old)\n'
            '            except OSError:\n'
            '                pass\n'
            '    except Exception:\n'
            '        pass\n'
            '    _desktop_jobs = HlsJobs(os.environ["HONGGUO_HLS_WORK_DIR"], _desktop_source)',
            "启动清扫残留工作目录")
        steps.append("启动清扫")

    # 步骤 6: /search 输入校验。空串或超长查询会直接打到上游，
    # 被风控拦下后返回 500（实测 81 字符即触发）。这里在入口挡住。
    if "Invalid search query" in s:
        steps.append("search 校验[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('    try:\n        return {"query": q, "results": H.search(q, max_items=limit)}'),
            '    _q = (q or "").strip()\n'
            '    if not _q or len(_q) > 80:\n'
            '        raise HTTPException(400, "Invalid search query")\n'
            '    try:\n        return {"query": _q, "results": H.search(_q, max_items=limit)}',
            "search 输入校验")
        steps.append("search 校验")

    # 步骤 7: 缓存路径必须限制在缓存目录内。
    # /stream 直接接受调用方的 vid（HTTP 路由层不校验），而 vid 被原样拼进
    # os.path.join(STREAM_CACHE, f"{vid}_{quality}.mp4")。Windows 上 os.path.join
    # 遇到绝对路径会丢弃前缀，于是 vid="C:/.../任意.mp4 去后缀" 即可越出缓存目录。
    # 2026-10-07 实测：用该构造读到缓存目录之外的任意视频文件（HTTP 200）。
    # 这里加一道「解析后必须在 STREAM_CACHE 之内」的硬校验，且缓存命中与写入都走它。
    if "Cache path escapes" in s:
        steps.append("缓存路径包含校验[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('    safe_q = re.sub(r"[^\\w]", "", str(quality)) or "best"\n'
                      '    out = os.path.join(STREAM_CACHE, f"{vid}_{safe_q}.mp4")'),
            '    safe_q = re.sub(r"[^\\w]", "", str(quality)) or "best"\n'
            '    out = _hq_cache_path(vid, safe_q)',
            "缓存路径包含校验")
        steps.append("缓存路径包含校验")

    # 步骤 8: _dec_locks 长期只增不减（每集每档位一个锁）。
    # 改为有界 LRU，避免长跑后无界增长。只重命名调用点，不动别的标识符。
    if "_HQ_DEC_LOCKS_MAX" in s:
        steps.append("解码锁有界化[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('_dec_locks = {}; _dec_guard = threading.Lock()\n'
                      'def _dec_lock(key):\n'
                      '    with _dec_guard:\n'
                      '        return _dec_locks.setdefault(key, threading.Lock())'),
            '# 本地维护: 有界 LRU。原先每集每档位建一个锁且永不回收，\n'
            '# 长跑后无界增长。这里最多保留 256 个，超出即淘汰最久未用的。\n'
            '_dec_locks = {}; _dec_guard = threading.Lock()\n'
            '_HQ_DEC_LOCKS_MAX = 256\n'
            'def _dec_lock(key):\n'
            '    with _dec_guard:\n'
            '        lock = _dec_locks.pop(key, None)\n'
            '        if lock is None:\n'
            '            lock = threading.Lock()\n'
            '        _dec_locks[key] = lock          # 重新插入到末尾 = 最近使用\n'
            '        while len(_dec_locks) > _HQ_DEC_LOCKS_MAX:\n'
            '            _dec_locks.pop(next(iter(_dec_locks)))\n'
            '        return lock\n'
            '\n'
            'def _hq_cache_path(vid, safe_q):\n'
            '    """把 vid + 档位解析成缓存文件路径，并强制它落在 STREAM_CACHE 之内。\n'
            '\n'
            '    为什么必须有这一层：/stream 的 vid 完全来自调用方，而 os.path.join\n'
            '    在 Windows 上遇到绝对路径会丢弃前缀，\n'
            '    vid="C:/.../x" 就能让 out 指到缓存目录之外。\n'
            '    实测（2026-10-07）可读到本机任意 mp4 文件。\n'
            '    这里要求 vid 是纯数字集号，再对最终路径做 realpath 包含校验，双保险。\n'
            '    """\n'
            '    text = str(vid or "")\n'
            '    if not re.fullmatch(r"[0-9]{1,32}", text):\n'
            '        raise HTTPException(400, "Invalid media id")\n'
            '    root = os.path.realpath(STREAM_CACHE)\n'
            '    out = os.path.realpath(os.path.join(root, f"{text}_{safe_q}.mp4"))\n'
            '    if out != root and not out.startswith(root + os.sep):\n'
            '        raise HTTPException(400, "Cache path escapes stream cache")\n'
            '    return out',
            "解码锁有界化 + 缓存路径校验")
        steps.append("解码锁有界化")

    # 步骤 9: 输入边界与无界容器。
    #  - /episodes 的 series_id 完全不校验（parse_range 也无上界），
    #    单次请求能把上游拖成百万级列表；
    #  - _img_cache 无上限，HEIC 转码结果会无界累积在内存里。
    if "Invalid series id" in s:
        steps.append("输入边界[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('@app.get("/episodes")\n'
                      'def api_episodes(series_id: str):\n'
                      '    try:\n'),
            '@app.get("/episodes")\n'
            'def api_episodes(series_id: str):\n'
            '    # 本地维护: 与其它路由一致的剧号校验，避免把任意串透给上游。\n'
            '    if not re.fullmatch(r"[0-9]{8,24}", str(series_id or "")):\n'
            '        raise HTTPException(400, "Invalid series id")\n'
            '    try:\n',
            "episodes 剧号校验")
        s = sub_once(
            s,
            re.escape('def parse_range(ep, total):'),
            '# 本地维护: 单次展开的集数上限。上游 /episodes 返回的集数可被构造得很大，\n'
            '# 无上界时 parse_range 会一次性建出百万级列表并把上游拖死。\n'
            '_HQ_RANGE_MAX = 2000\n'
            '\n'
            '\n'
            'def parse_range(ep, total):',
            "parse_range 上界")
        s = sub_once(
            s,
            re.escape('    if not ep or ep == "all":\n'
                      '        return list(range(1, total + 1))'),
            '    total = min(int(total or 0), _HQ_RANGE_MAX)\n'
            '    if not ep or ep == "all":\n'
            '        return list(range(1, total + 1))',
            "parse_range 上界应用")
        # _img_cache 加上限（封面图按原始 URL 缓存，长跑会无界增长）
        s = sub_once(
            s,
            re.escape('_img_cache = {}'),
            '# 本地维护: 有界封面缓存。原先是无上限 dict，长跑只增不减。\n'
            '_img_cache = {}; _HQ_IMG_CACHE_MAX = 512\n'
            '\n'
            '\n'
            'def _hq_img_cache_put(url, data):\n'
            '    """写入封面缓存；超出上限就丢弃最旧的条目。"""\n'
            '    _img_cache[url] = data\n'
            '    while len(_img_cache) > _HQ_IMG_CACHE_MAX:\n'
            '        _img_cache.pop(next(iter(_img_cache)), None)',
            "封面缓存上限")
        s = sub_once(
            s,
            re.escape('            _img_cache[raw] = data'),
            '            _hq_img_cache_put(raw, data)',
            "封面缓存写入改走有界函数")
        steps.append("输入边界")

    # 步骤 10: 启动时清理「其它函数碰不到的」残留。
    # _hq_sweep_partial / _hq_prune_poster_cache 定义在 CLEANUP_BLOCK（文件末尾），
    # 所以调用必须也放在文件末尾 —— 否则就是 2026-10-07 踩过的那个 NameError
    # （定义在后、调用在前，后端直接起不来）。
    if "_hq_startup_cache_sweep" in s:
        steps.append("残留清扫[已存在]")
    else:
        s = s.rstrip("\n") + "\n" + HQ_STARTUP_SWEEP
        steps.append("残留清扫")

    # 步骤 11: 免鉴权路由的收敛。
    #  - /docs、/openapi.json、/redoc 免鉴权且把完整路由表与参数清单暴露给
    #    本机任意进程；前端一处都没用到，直接从免鉴权名单里去掉。
    #  - /img 免鉴权（<img> 标签带不了请求头，必须保留），
    #    但之前连限流也一起绕过了，本机任意进程可以拿它当无限代理。
    #    这里给免鉴权路径补一个独立限流桶。
    if "hq_exempt" in s:
        steps.append("免鉴权收敛[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('_EXEMPT = ("/", "/ui", "/img", "/docs", "/openapi.json", "/redoc", "/favicon.ico")'),
            '# 本地维护: 去掉 /docs、/openapi.json、/redoc ——\n'
            '# 它们免鉴权却泄漏完整路由与参数清单，而前端一处都没用到。\n'
            '_EXEMPT = ("/", "/ui", "/img", "/favicon.ico")',
            "免鉴权名单收敛")
        s = sub_once(
            s,
            re.escape('    if path == "/stats" or path.startswith(_ADMIN_PREFIX):\n'
                      '        # 管理/统计: 由各自处理器用 ADMIN_TOKEN 校验\n'
                      '        pass\n'
                      '    elif path not in _EXEMPT:'),
            '    if path == "/stats" or path.startswith(_ADMIN_PREFIX):\n'
            '        # 管理/统计: 由各自处理器用 ADMIN_TOKEN 校验\n'
            '        pass\n'
            '    elif path in _EXEMPT:\n'
            '        # 本地维护: 免鉴权路径也要限流。\n'
            '        # /img 必须免鉴权（<img> 标签带不了请求头），但之前连限流\n'
            '        # 也绕过了，本机任意进程能拿它当无限图片代理。\n'
            '        _now = time.time()\n'
            '        with _rl_lock:\n'
            '            _bucket = _rl.setdefault(("hq_exempt", path), [])\n'
            '            while _bucket and _bucket[0] < _now - 60:\n'
            '                _bucket.pop(0)\n'
            '            if len(_bucket) >= 120:\n'
            '                return JSONResponse({"detail": "超过限流 120/分钟"}, status_code=429)\n'
            '            _bucket.append(_now)\n'
            '    else:',
            "免鉴权路径限流")
        steps.append("免鉴权收敛")

    # 步骤 12: /img 的重定向必须逐跳重新校验域名。
    # requests 默认跟随 302；允许域名上的开放重定向就能把请求打到任意 host，
    # 绕过 _IMG_HOSTS 白名单（SSRF）。这里改为手动跟随，且每一跳都过白名单。
    if "_hq_img_fetch" in s:
        steps.append("封面重定向校验[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('        r = requests.get(raw, timeout=30, verify=True, headers={"User-Agent": "Mozilla/5.0"})\n'
                      '        r.raise_for_status()'),
            '        r = _hq_img_fetch(raw, _IMG_HOSTS)\n'
            '        r.raise_for_status()',
            "封面取图改走逐跳校验")
        s = sub_once(
            s,
            re.escape('@app.get("/img")\n'
                      'def api_img(url: str):'),
            'def _hq_host_allowed(host, hosts):\n'
            '    """域名白名单判定。必须带前导点，否则 evilfqnovelpic.com 会被放行。"""\n'
            '    host = (host or "").lower()\n'
            '    return any(host == h or host.endswith("." + h) for h in hosts)\n'
            '\n'
            '\n'
            'def _hq_img_fetch(url, hosts, max_hops=3):\n'
            '    """取封面图，手动跟随重定向，且每一跳都重新校验域名。\n'
            '\n'
            '    requests 默认自动跟随 302，若允许域名上存在开放重定向，\n'
            '    就能把请求打到任意 host —— 等于绕过 _IMG_HOSTS 白名单（SSRF）。\n'
            '    这里显式禁止自动跟随，逐跳校验 Location。\n'
            '    """\n'
            '    from urllib.parse import urlparse, urljoin\n'
            '    current = url\n'
            '    for _ in range(max_hops + 1):\n'
            '        u = urlparse(current)\n'
            '        if u.scheme not in ("http", "https") or not _hq_host_allowed(u.hostname, hosts):\n'
            '            raise HTTPException(400, "图片域名不允许")\n'
            '        r = requests.get(current, timeout=30, verify=True, allow_redirects=False,\n'
            '                         headers={"User-Agent": "Mozilla/5.0"})\n'
            '        if r.status_code not in (301, 302, 303, 307, 308):\n'
            '            return r\n'
            '        loc = r.headers.get("location") or ""\n'
            '        if not loc:\n'
            '            return r\n'
            '        current = urljoin(current, loc)\n'
            '    raise HTTPException(400, "图片重定向过多")\n'
            '\n'
            '\n'
            '@app.get("/img")\n'
            'def api_img(url: str):',
            "封面重定向逐跳校验")
        steps.append("封面重定向校验")

    # 步骤 13: /ui 与 /admin 在打包版里必定 500。
    # 它们从 backend/web/ 读静态页，而上游发行包里没有这个目录
    # （实测安装目录与源码树都没有 web/），于是 FileResponse 抛
    # RuntimeError -> 500。前端从不引用这两个路由，但一个「文件不存在」
    # 报 500 是错的，应该 404 且给一句能看懂的话。
    if "_hq_static_page" in s:
        steps.append("静态页缺省处理[已存在]")
    else:
        s = sub_once(
            s,
            re.escape('@app.get("/ui")\n'
                      'def ui():\n'
                      '    from fastapi.responses import FileResponse\n'
                      '    return FileResponse(os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", "index.html"))'),
            'def _hq_static_page(name):\n'
            '    """读 backend/web/<name>；打包版没有这个目录，缺就 404 而不是 500。"""\n'
            '    from fastapi.responses import FileResponse\n'
            '    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", name)\n'
            '    if not os.path.isfile(path):\n'
            '        raise HTTPException(404, "该页面未包含在当前安装包中")\n'
            '    return FileResponse(path)\n'
            '\n'
            '\n'
            '@app.get("/ui")\n'
            'def ui():\n'
            '    return _hq_static_page("index.html")',
            "ui 静态页缺省处理")
        s = sub_once(
            s,
            re.escape('@app.get("/admin")\n'
                      'def admin_page():\n'
                      '    from fastapi.responses import FileResponse\n'
                      '    return FileResponse(os.path.join(os.path.dirname(os.path.abspath(__file__)), "web", "admin.html"))'),
            '@app.get("/admin")\n'
            'def admin_page():\n'
            '    return _hq_static_page("admin.html")',
            "admin 静态页缺省处理")
        steps.append("静态页缺省处理")

    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   server.py  (%s)" % " + ".join(steps))


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



# ---- 本地维护: 红果账号同步（验证码登录 / 观看进度 / 收藏） ----
ACCOUNT_FILES = ("desktop_account.py", "desktop_account_api.py",
                 "desktop_update.py", "desktop_update_download.py")

# 账号模块的附属数据文件（随包携带的设备身份）。
# 单独列出来：它不是 .py，但必须跟着进安装目录，否则全新机器上
# load_device() 拿不到合法设备身份，登录会被服务端 403。
ACCOUNT_DATA_FILES = ("device-bundled.json",)

ACCOUNT_REGISTER = """


# ---- 本地维护: 红果账号同步（验证码登录 / 观看进度 / 收藏）----
try:
    import desktop_account_api as _hq_account_api
    _hq_account_api.register(app)
    # 本地维护: 更新检测指向本分支自己的仓库（Tauri 自带的 updater 需要
    # 原作者私钥签名，我们用不了，所以走自己的检测 + 打开下载页）。
    _hq_account_api.register_update(app)
    _hq_account_api.register_update_download(app)
except Exception as _hq_account_error:  # 账号同步不可用时不影响播放
    print("[server] 账号同步未启用:", type(_hq_account_error).__name__)
"""


def patch_account_backend(src_dir, out_dir):
    """拷贝账号同步模块，并在 server.py 末尾注册路由。

    模块本体存放在 patches/account/（我们的新增文件，不属于上游基线），
    这样上游换版本时不会因为 base/ 里没有它们而漏掉。
    """
    account_src = os.path.join(ROOT, "patches", "account")
    for name in ACCOUNT_DATA_FILES:
        src = os.path.join(account_src, name)
        if os.path.isfile(src):
            io.open(os.path.join(out_dir, name), "w", encoding="utf-8",
                    newline="").write(io.open(src, encoding="utf-8").read())
            print("OK   %s  (设备身份)" % name)
    for name in ACCOUNT_FILES:
        src = os.path.join(account_src, name)
        if not os.path.isfile(src):
            src = os.path.join(src_dir, name)
        if not os.path.isfile(src):
            continue
        io.open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="").write(
            io.open(src, encoding="utf-8").read())
        print("OK   %s  (账号同步)" % name)
    p_in = os.path.join(src_dir, "server.py")
    p_out = os.path.join(out_dir, "server.py")
    if not os.path.isfile(p_out):
        return
    s = io.open(p_out, encoding="utf-8").read()
    if "desktop_account_api" in s:
        print("OK   server.py 账号路由已注册（跳过）")
        return
    io.open(p_out, "w", encoding="utf-8", newline="").write(s.rstrip("\n") + ACCOUNT_REGISTER)
    print("OK   server.py  (注册账号同步路由)")


# ---- 本地维护: 依赖版本固定 ----
# patch_backend.main() 会把 _v109/extracted/backend 下的 .txt 原样拷进 src，
# 所以「只改 src/backend/requirements-windows.txt」会被下一次重跑覆盖掉。
# 固定版本必须做成补丁步骤，否则等于没做（实测踩过）。
PINNED_REQUIREMENTS = """# Windows 本机脱机直连 · Python 依赖(下载/串流最小集,推荐)
# 装进便携 embeddable Python:  python\\python.exe -m pip install -r requirements-windows.txt
#
# 本地维护: 版本固定（==）以便复现构建。
# 2026-10-07 按本机实际安装版本锁定；升级前请先跑一遍 tools/smoke_test.py。
requests==2.34.2
fastapi==0.141.1
uvicorn[standard]==0.52.4
pycryptodome==3.23.0
av==18.1.0

# --- 可选:要在 /ui 里看封面缩略图(HEIC 转 JPEG)再放开下面两行 ---
# pillow==12.3.0
# pillow-heif==1.6.0

# 不需要: frida / mitmproxy / paramiko / redis (逆向/服务器阶段才用)
"""


# ---- 本地维护: downloader 的 TLS 校验 ----
# downloader.py 里有三处 verify=False，绕过仓库其它地方一致的证书校验。
# 红果 CDN 的证书链正常（实测 200），不需要关校验；关掉等于给中间人开门。
DOWNLOADER_VERIFY_BAD = "verify=False, timeout="
DOWNLOADER_VERIFY_OK = "verify=True, timeout="


def patch_downloader(src_dir, out_dir):
    name = "downloader.py"
    p_in = os.path.join(src_dir, name)
    p_out = os.path.join(out_dir, name)
    if not os.path.isfile(p_in):
        return
    s = io.open(p_in, encoding="utf-8").read()
    if DOWNLOADER_VERIFY_BAD not in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   downloader.py 已处理（跳过）")
        return
    n = s.count(DOWNLOADER_VERIFY_BAD)
    s = s.replace(DOWNLOADER_VERIFY_BAD, DOWNLOADER_VERIFY_OK)
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   downloader.py  (%d 处 verify=False -> True)" % n)


# ---- 本地维护: safeguards 的内存缓存加上限 ----
# _cache 只在 cache_get 命中时顺带清过期项：写入频率高于读取、
# 或写进去之后没人再读的 key 会永远留着。桌面端长跑（挂着不关）
# 会慢慢累积。这里在 cache_set 时按数量上限淘汰最旧的一批。
SAFEGUARDS_CACHE_BAD = '''    with _cache_lock:
        _cache[key] = (time.time() + ttl, val)'''
SAFEGUARDS_CACHE_OK = '''    with _cache_lock:
        _cache[key] = (time.time() + ttl, val)
        # 本地维护: 加数量上限。原实现只在 get 命中时清过期项，
        # 写入后没人再读的 key 会永远留着（桌面端长跑会累积）。
        if len(_cache) > _HQ_SAFEGUARDS_CACHE_MAX:
            for _k in sorted(_cache, key=lambda k: _cache[k][0])[:len(_cache) - _HQ_SAFEGUARDS_CACHE_MAX]:
                _cache.pop(_k, None)'''


def patch_safeguards(src_dir, out_dir):
    name = "safeguards.py"
    p_in = os.path.join(src_dir, name)
    p_out = os.path.join(out_dir, name)
    if not os.path.isfile(p_in):
        return
    s = io.open(p_in, encoding="utf-8").read()
    if "_HQ_SAFEGUARDS_CACHE_MAX" in s:
        io.open(p_out, "w", encoding="utf-8", newline="").write(s)
        print("OK   safeguards.py 已处理（跳过）")
        return
    s = sub_once(s, re.escape("_cache = {}\n_cache_lock = threading.Lock()"),
                 "_cache = {}\n_cache_lock = threading.Lock()\n"
                 "# 本地维护: 内存缓存的条目上限（Redis 模式不受影响）。\n"
                 "_HQ_SAFEGUARDS_CACHE_MAX = 2048",
                 "safeguards 缓存上限常量")
    s = sub_once(s, re.escape(SAFEGUARDS_CACHE_BAD), SAFEGUARDS_CACHE_OK,
                 "safeguards 缓存上限应用")
    io.open(p_out, "w", encoding="utf-8", newline="").write(s)
    print("OK   safeguards.py  (内存缓存加上限)")


def patch_requirements(src_dir, out_dir):
    """把依赖文件固定到已验证的版本。"""
    name = "requirements-windows.txt"
    dst = os.path.join(out_dir, name)
    src = os.path.join(src_dir, name)
    if not os.path.isfile(src) and not os.path.isfile(dst):
        return
    io.open(dst, "w", encoding="utf-8", newline="").write(PINNED_REQUIREMENTS)
    print("OK   %s  (版本固定)" % name)


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
    patch_downloader(src, dst)
    patch_safeguards(src, dst)
    patch_account_backend(src, dst)
    patch_requirements(src, dst)


if __name__ == "__main__":
    main()
