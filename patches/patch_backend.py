# -*- coding: utf-8 -*-
"""重写 patches/patch_backend.py: 清晰度参数 + 切换提速。

提速原理(实测):
  解密片源是 HEVC, WebView2 不支持 HEVC, 必须转 H.264。
  转码一集 ~3.8s; 而已转码的 H.264 缓存 -> HLS 只需 remux ~0.16s (快 24x)。
  因此: 把 H.264 缓存交给 HLS 编码器, 并在编码器里对 H.264 源走 stream-copy。
"""
import io, os, hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, "base", "backend")
BE   = os.path.join(ROOT, "src", "backend")


def read(name, base=True):
    return io.open(os.path.join(BASE if base else BE, name), encoding="utf-8").read()


def write(name, text):
    io.open(os.path.join(BE, name), "w", encoding="utf-8", newline="").write(text)


def sub_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"[FAIL] {label}: expected 1, found {n}")
    return text.replace(old, new, 1)


# ============ desktop_hls.py: H.264 源走 stream-copy ============
hls = read("desktop_hls.py")

old_head = '''def encode_hls(source, directory, on_ready=lambda: None, *, cancelled=lambda: False,
               max_output_bytes=512 * 1024 * 1024):
    import av

    source, directory = Path(source), Path(directory)
    # Never overwrite an existing session or mix segments from different encodes.
    directory.mkdir(parents=False, exist_ok=False)
    ready = False
    packets_seen = 0
    with av.open(str(source)) as reader:
        if len(reader.streams.video) != 1:
            raise ValueError("Expected one video track")
        original = reader.streams.video[0]
        rate = original.average_rate or 30
        if not 0 < rate <= 120:
            raise ValueError("Unsupported frame rate")
        options = {
            "hls_time": "2", "hls_list_size": "0", "hls_playlist_type": "event",
            "hls_segment_type": "fmp4", "hls_fmp4_init_filename": "init.mp4",
            "hls_segment_filename": (directory / "seg%06d.m4s").as_posix(),
            "hls_flags": "temp_file+independent_segments",
        }
        with av.open((directory / "index.m3u8").as_posix(), "w", format="hls", options=options) as writer:'''

new_head = '''def hls_segment_options(directory):
    return {
        "hls_time": "2", "hls_list_size": "0", "hls_playlist_type": "event",
        "hls_segment_type": "fmp4", "hls_fmp4_init_filename": "init.mp4",
        "hls_segment_filename": (directory / "seg%06d.m4s").as_posix(),
        "hls_flags": "temp_file+independent_segments",
    }


def _copy_hls(reader, writer, directory, cancelled, max_output_bytes):
    """Stream-copy an already H.264/AAC source into fmp4 HLS segments.

    The desktop encoder cache holds H.264; re-encoding it costs seconds, while
    copying is two orders of magnitude cheaper and yields the same codec profile
    the player accepts. Sample-accurate structure is preserved: only the
    container is rewritten.
    """
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
        if seen % 64 == 0:
            if sum(p.stat().st_size for p in directory.iterdir() if p.is_file()) > max_output_bytes:
                raise ValueError("Desktop segment budget exceeded")
        if packet.dts is None or packet.stream.index not in streams:
            continue
        packet.stream = streams[packet.stream.index]
        writer.mux(packet)


def encode_hls(source, directory, on_ready=lambda: None, *, cancelled=lambda: False,
               max_output_bytes=512 * 1024 * 1024):
    import av

    source, directory = Path(source), Path(directory)
    # Never overwrite an existing session or mix segments from different encodes.
    directory.mkdir(parents=False, exist_ok=False)
    ready = False
    packets_seen = 0
    with av.open(str(source)) as reader:
        if len(reader.streams.video) != 1:
            raise ValueError("Expected one video track")
        original = reader.streams.video[0]
        rate = original.average_rate or 30
        if not 0 < rate <= 120:
            raise ValueError("Unsupported frame rate")
        # A pre-encoded H.264 source needs no transcoding. Keep the reviewed
        # transcode path for every other codec (the decrypted sources are HEVC).
        copy_video = original.codec_context.name == "h264"
        options = hls_segment_options(directory)
        with av.open((directory / "index.m3u8").as_posix(), "w", format="hls", options=options) as writer:
            if copy_video:
                _copy_hls(reader, writer, directory, cancelled, max_output_bytes)'''

hls = sub_once(hls, old_head, new_head, "hls copy-mode head")

# 转码分支缩进 + 关闭 encode 收尾只在转码路径
old_body = '''            video = writer.add_stream("libx264", rate=rate)
            video.width = original.codec_context.width
            video.height = original.codec_context.height
            video.pix_fmt = "yuv420p"
            video.codec_context.options = {
                "preset": "ultrafast", "crf": "20", "tune": "zerolatency",
                "g": str(max(1, round(float(rate) * 2))), "sc_threshold": "0",
            }
            audio = {}
            for stream in reader.streams.audio:
                if stream.codec_context.name != "aac":
                    raise ValueError("Desktop profile currently requires AAC audio")
                audio[stream.index] = writer.add_stream_from_template(stream)
                audio[stream.index].codec_context.codec_tag = "mp4a"
            for packet in reader.demux():
                if cancelled():
                    raise EncodingCancelled("Desktop encode cancelled")
                packets_seen += 1
                if packets_seen % 32 == 0:
                    # A bounded overshoot of at most 32 demux packets is possible.
                    # Include unfinished .tmp data; never silently drop frames.
                    if sum(p.stat().st_size for p in directory.iterdir() if p.is_file()) > max_output_bytes:
                        raise ValueError("Desktop segment budget exceeded")
                if packet.stream.index == original.index:
                    for frame in packet.decode():
                        for encoded in video.encode(frame):
                            writer.mux(encoded)
                elif packet.stream.index in audio and packet.dts is not None:
                    packet.stream = audio[packet.stream.index]
                    writer.mux(packet)
                if not ready and (directory / "index.m3u8").is_file():
                    ready = True
                    on_ready()
            for packet in video.encode():
                writer.mux(packet)'''

new_body = '''            else:
                video = writer.add_stream("libx264", rate=rate)
                video.width = original.codec_context.width
                video.height = original.codec_context.height
                video.pix_fmt = "yuv420p"
                video.codec_context.options = {
                    "preset": "ultrafast", "crf": "20", "tune": "zerolatency",
                    "g": str(max(1, round(float(rate) * 2))), "sc_threshold": "0",
                }
                audio = {}
                for stream in reader.streams.audio:
                    if stream.codec_context.name != "aac":
                        raise ValueError("Desktop profile currently requires AAC audio")
                    audio[stream.index] = writer.add_stream_from_template(stream)
                    audio[stream.index].codec_context.codec_tag = "mp4a"
                for packet in reader.demux():
                    if cancelled():
                        raise EncodingCancelled("Desktop encode cancelled")
                    packets_seen += 1
                    if packets_seen % 32 == 0:
                        # A bounded overshoot of at most 32 demux packets is possible.
                        # Include unfinished .tmp data; never silently drop frames.
                        if sum(p.stat().st_size for p in directory.iterdir() if p.is_file()) > max_output_bytes:
                            raise ValueError("Desktop segment budget exceeded")
                    if packet.stream.index == original.index:
                        for frame in packet.decode():
                            for encoded in video.encode(frame):
                                writer.mux(encoded)
                    elif packet.stream.index in audio and packet.dts is not None:
                        packet.stream = audio[packet.stream.index]
                        writer.mux(packet)
                    if not ready and (directory / "index.m3u8").is_file():
                        ready = True
                        on_ready()
                for packet in video.encode():
                    writer.mux(packet)'''
hls = sub_once(hls, old_body, new_body, "hls transcode branch")
write("desktop_hls.py", hls)
print("[OK] desktop_hls.py  (H.264 源 stream-copy)")

# ============ server.py: 把 H.264 缓存交给 HLS 编码器 ============
sv = read("server.py")
sv = sub_once(sv,
    '    def _desktop_source(series_id, episode):\n'
    '        _, episodes = H.get_episodes(series_id)\n'
    '        target = next((item for item in episodes if item.get("index") == episode), None)\n'
    '        if not target or not re.fullmatch(r"[0-9]{8,24}", str(target.get("vid", ""))):\n'
    '            raise ValueError("Episode media identity unavailable")\n'
    '        return _ensure_decrypted(str(target["vid"]), "desktop-resolution-v1")',
    '    def _desktop_source(series_id, episode, quality="desktop-resolution-v1"):\n'
    '        _, episodes = H.get_episodes(series_id)\n'
    '        target = next((item for item in episodes if item.get("index") == episode), None)\n'
    '        if not target or not re.fullmatch(r"[0-9]{8,24}", str(target.get("vid", ""))):\n'
    '            raise ValueError("Episode media identity unavailable")\n'
    '        # Quality is validated by the HLS router before it reaches here.\n'
    '        decrypted = _ensure_decrypted(str(target["vid"]), quality or "desktop-resolution-v1")\n'
    '        # Hand the HLS encoder a pre-encoded H.264 cache instead of the HEVC\n'
    '        # source: stream-copy then costs ~0.16s instead of a ~3.8s transcode.\n'
    '        # The cache is built once per (vid, quality) and reused afterwards.\n'
    '        from desktop_encode import encode_h264\n'
    '        return encode_h264(decrypted)',
    "server desktop source h264")
write("server.py", sv)
print("[OK] server.py        (HLS 用已转码 H.264 缓存)")

for f in ("desktop_hls.py", "server.py"):
    d = io.open(os.path.join(BE, f), "rb").read()
    print(f"  {f}: {len(d)} bytes sha256={hashlib.sha256(d).hexdigest()[:16]}")
