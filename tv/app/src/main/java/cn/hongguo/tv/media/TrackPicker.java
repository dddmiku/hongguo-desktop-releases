package cn.hongguo.tv.media;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 轨道选择。
 *
 * 两条硬约束（都是实测踩出来的）：
 *
 * 1. **bytevc2 解不了**。上游同一集常同时给 bytevc2（自研，mp4 里是 bv2C 盒）
 *    和 bytevc1（标准 H.265）。系统解码器认不出 bv2C，整集播不了。
 *    实测某剧 60 集里 29 集的主档是 bytevc2，只有 1080p 那档是 bytevc1。
 *
 * 2. **必须问系统能不能解**。不同电视支持的编码/分辨率差异很大，
 *    硬选 1080p 在老盒子上会直接失败。用 MediaCodecList 逐个问。
 *
 * 评分：可解码 > 分辨率档位 > 体积（同档取小的，省内存）。
 */
public final class TrackPicker {

    private static final String TAG = "HgTrack";

    /** 单集体积上限：超过这个大小的档位在低内存设备上直接跳过。 */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private static final String[] DECODABLE = {
            "bytevc1", "hevc", "h265", "hev1", "hvc1", "avc1", "h264", "avc"
    };

    private TrackPicker() {
    }

    public static final class Track {
        public final JSONObject raw;
        public final String definition;
        public final String codec;
        public final int width;
        public final int height;
        public final long size;
        public final String mainUrl;
        public final String backupUrl;
        public final String spadeA;
        public final boolean encrypted;

        Track(JSONObject raw, String definition, String codec, int width, int height,
              long size, String mainUrl, String backupUrl, String spadeA, boolean encrypted) {
            this.raw = raw;
            this.definition = definition;
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.size = size;
            this.mainUrl = mainUrl;
            this.backupUrl = backupUrl;
            this.spadeA = spadeA;
            this.encrypted = encrypted;
        }

        public boolean decodable() {
            return isDecodable(codec);
        }

        @Override
        public String toString() {
            return definition + "/" + codec + " " + width + "x" + height
                    + " " + (size / 1024) + "KB";
        }
    }

    private static boolean isDecodable(String codec) {
        if (codec == null || codec.isEmpty()) {
            // 没标编码的按可解处理：历史上只有 bv2C 会显式标注。
            return true;
        }
        String c = codec.toLowerCase(Locale.US);
        for (String d : DECODABLE) {
            if (d.equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 video_model 里挑一档。
     *
     * @param model video_model JSON（可能被上游包成字符串）
     * @param cap   分辨率上限（480/720/1080）；0 表示不限制
     */
    public static Track pick(JSONObject model, int cap) throws Exception {
        JSONArray list = tracksOf(model);
        Track best = null;
        long bestScore = Long.MAX_VALUE;

        for (int i = 0; i < list.length() && i < 32; i++) {
            JSONObject t = list.optJSONObject(i);
            if (t == null) {
                continue;
            }
            JSONObject meta = t.optJSONObject("video_meta");
            if (meta == null) {
                meta = t;
            }
            String codec = meta.optString("codec_type", t.optString("codec_type", ""));
            long size = meta.optLong("size", t.optLong("size", 0));
            int w = meta.optInt("vwidth", t.optInt("vwidth", 0));
            int h = meta.optInt("vheight", t.optInt("vheight", 0));
            String def = meta.optString("definition", "").toLowerCase(Locale.US);
            if (size <= 0 || size > MAX_BYTES || w <= 0 || h <= 0) {
                continue;
            }
            if (!isDecodable(codec)) {
                continue;   // bv2C 直接跳过，选它必崩
            }
            int shortEdge = Math.min(w, h);
            if (cap > 0 && shortEdge > cap) {
                continue;
            }
            if (!deviceSupports(codec, w, h)) {
                continue;
            }

            // 分档：1080=0 / 720=1 / 540=2 / 480=3 / 更低=4
            int tier = shortEdge >= 1080 ? 0 : shortEdge >= 720 ? 1
                    : shortEdge >= 540 ? 2 : shortEdge >= 480 ? 3 : 4;
            // 同档优先体积小的（省内存、起播快）
            long score = tier * 1000000L + size / 1024;
            if (score < bestScore) {
                bestScore = score;
                best = build(t, meta, def, codec, w, h, size);
            }
        }
        if (best == null) {
            throw new IllegalStateException("没有可播放的清晰度（可能是编码不支持）");
        }
        Log.i(TAG, "选中轨道 " + best);
        return best;
    }

    private static Track build(JSONObject t, JSONObject meta, String def, String codec,
                               int w, int h, long size) {
        JSONObject enc = t.optJSONObject("encrypt_info");
        String spade = enc == null ? "" : enc.optString("spade_a", "");
        boolean encrypted = enc != null && enc.optBoolean("encrypt", false);
        String main = decodeUrl(t.optString("main_url", ""));
        String backup = decodeUrl(t.optString("backup_url_1",
                t.optString("backup_url", "")));
        return new Track(t, def, codec, w, h, size, main, backup, spade, encrypted);
    }

    /** 上游 URL 字段可能是明文或 base64，统一还原。 */
    private static String decodeUrl(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.startsWith("http://") || v.startsWith("https://")) {
            return v;
        }
        try {
            String s = new String(android.util.Base64.decode(v, android.util.Base64.DEFAULT),
                    "UTF-8");
            return s.startsWith("http") ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONArray tracksOf(JSONObject model) throws Exception {
        Object o = model.opt("video_list");
        if (o instanceof JSONArray) {
            return (JSONArray) o;
        }
        if (o instanceof JSONObject) {
            // 少数版本是 {"video_1": {...}} 形式
            JSONObject obj = (JSONObject) o;
            JSONArray arr = new JSONArray();
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                JSONObject v = obj.optJSONObject(it.next());
                if (v != null) {
                    arr.put(v);
                }
            }
            return arr;
        }
        throw new IllegalStateException("video_list 结构异常");
    }

    private static Boolean sHevcSupported;
    private static Boolean sAvcSupported;

    /** 问系统解码器是否支持该编码与分辨率。结果缓存，避免每集都遍历。 */
    private static boolean deviceSupports(String codec, int w, int h) {
        String mime;
        String c = codec == null ? "" : codec.toLowerCase(Locale.US);
        if (c.equals("h264") || c.equals("avc") || c.equals("avc1")) {
            mime = "video/avc";
        } else {
            mime = "video/hevc";     // bytevc1/hevc/h265 都走 hevc 解码器
        }
        Boolean cached = mime.equals("video/avc") ? sAvcSupported : sHevcSupported;
        if (cached != null) {
            return cached;
        }
        boolean ok = probe(mime, w, h);
        if (mime.equals("video/avc")) {
            sAvcSupported = ok;
        } else {
            sHevcSupported = ok;
        }
        Log.i(TAG, "解码能力 " + mime + " -> " + ok);
        return ok;
    }

    private static boolean probe(String mime, int w, int h) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) {
                    for (String type : info.getSupportedTypes()) {
                        if (type.equalsIgnoreCase(mime)) {
                            return true;   // 有该 mime 的解码器即认为可用
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "查询解码器失败，按支持处理", e);
            return true;    // 查不到时不阻塞播放，让 MediaPlayer 自己报错
        }
        return false;
    }
}
