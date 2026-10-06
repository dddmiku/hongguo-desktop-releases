"""Incremental desktop H.264/AAC encoding into an isolated, caller-owned directory.

Only complete segments and atomically published playlists are exposed. No URLs,
session keys, or upstream protocol handling belong in this module.
"""
from pathlib import Path


class EncodingCancelled(Exception):
    """A caller stopped this encode; its fragments are not a complete episode."""


def hls_segment_options(directory):
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
                _copy_hls(reader, writer, directory, cancelled, max_output_bytes)
            else:
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
                    writer.mux(packet)
    playlist = (directory / "index.m3u8").read_text(encoding="utf-8")
    if "#EXT-X-ENDLIST" not in playlist or not (directory / "seg000000.m4s").is_file():
        raise ValueError("No complete desktop segments")
    if sum(p.stat().st_size for p in directory.iterdir() if p.is_file()) > max_output_bytes:
        raise ValueError("Desktop segment budget exceeded")
    # Closing the FFmpeg writer also emits ENDLIST on error/cancellation. Thus
    # ENDLIST alone is NEVER success evidence. The HTTP job owner must reject
    # failed jobs even if the partial playlist happens to contain ENDLIST.
    if not ready:
        on_ready()
    if cancelled():
        raise EncodingCancelled("Desktop encode cancelled")
    (directory / "complete.marker").write_text("desktop-hls-v1\n", encoding="ascii")
    return directory / "index.m3u8"
