package cn.hongguo.tv.media;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 端到端解密验证：下载一集 → spade 解出 key → CENC 解密 → 用 NAL 结构自证。
 *
 * 为什么用 NAL 自证而不是「能播放」：
 *   密钥错一位，AES-CTR 输出就是均匀噪声，播放器只会报「无法播放」，
 *   完全看不出是密钥错、偏移错、还是编码不支持。而 H.265 的 NAL 头有固定
 *   结构（forbidden_zero_bit=0、nuh_layer_id 有限、nuh_temporal_id_plus1>=1），
 *   密钥或偏移正确时几乎 100% 的 NAL 都合法，错的时候几乎 0%。
 *   这是桌面端 oracle.py 用的同一个判据。
 */
public final class DecryptProbe {

    private static final String TAG = "HgProbe";

    private DecryptProbe() {
    }

    /**
     * 跑一次完整验证。
     *
     * @param url    轨道直链
     * @param spadeA encrypt_info.spade_a
     * @param dir    工作目录（放下载的密文与解出的明文）
     * @return 人类可读的结果串
     */
    public static String run(String url, String spadeA, File dir) {
        StringBuilder log = new StringBuilder();
        File enc = new File(dir, "probe_enc.mp4");
        File out = new File(dir, "probe_dec.mp4");
        try {
            // 1) 下载密文
            long t0 = System.currentTimeMillis();
            long n = download(url, enc);
            log.append("下载=").append(n / 1024).append("KB/")
                    .append(System.currentTimeMillis() - t0).append("ms");
            if (n <= 0) {
                return log.append(" ✗ 下载失败").toString();
            }

            // 2) spade → content key
            byte[] key = Spade.contentKey(spadeA);
            if (key == null) {
                return log.append(" ✗ spade 解析失败").toString();
            }
            log.append(" key=").append(toHex(key));

            // 3) 解密
            long t1 = System.currentTimeMillis();
            Decryptor.Result r = Decryptor.decrypt(enc, out, key);
            log.append(" 解密=").append(System.currentTimeMillis() - t1).append("ms")
                    .append(" 视频样本=").append(r.videoSamples)
                    .append(" 音频样本=").append(r.audioSamples);

            // 4) NAL 自证
            String verdict = verifyNal(out, r.videoCodec);
            log.append(" NAL=").append(verdict);

            // 5) 交给系统解码器实测：这是最终判据，NAL 合法不代表能解码
            log.append(" 系统解码=").append(probeMedia(out));

            Log.i(TAG, log.toString());
            return log.toString();
        } catch (Throwable e) {
            Log.e(TAG, "验证失败", e);
            return log.append(" ✗ ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).toString();
        }
    }

    private static long download(String url, File dst) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 必须整集下载：mp4 的 moov 里记的是相对完整文件的样本偏移，
        // 只取前几 MB 会让绝大多数样本越界，解密出来只剩开头几帧可用，
        // 会误判成「解密失败」。
        long total = 0;
        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            c.disconnect();
        }
        return total;
    }

    /**
     * 用系统 MediaMetadataRetriever 打开文件并取元数据。
     *
     * 这是比 NAL 统计更强的判据：NAL 合法只说明码流结构对，而这里走的是
     * 与播放器相同的解封装路径，能拿到时长/宽高就说明文件真的能被系统识别。
     * 返回形如 "192s 720x1280" 或错误描述。
     */
    private static String probeMedia(File f) {
        android.media.MediaMetadataRetriever r =
                new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String dur = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            String w = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (dur == null || w == null) {
                return "无法识别";
            }
            return (Long.parseLong(dur) / 1000) + "s " + w + "x" + h;
        } catch (Throwable e) {
            return "失败:" + e.getClass().getSimpleName();
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 按样本边界逐个解析 NAL，统计合法率。
     *
     * 判据必须用「长度前缀」而不是扫 Annex-B 的 00 00 01：
     * mp4 里的视频样本是 AVCC/HVCC 格式，每个 NAL 前有 4 字节大端长度。
     * 之前扫起始码时，密文里偶然出现的 00 00 01 会制造假命中，
     * 明文里真正的 NAL 又因为没有起始码而全部漏掉，导致明密文都是 1%，
     * 完全分不出解密有没有生效。
     *
     * 而且**不能跨样本连续解析**：mdat 里是「样本拼接」，样本与样本之间
     * 没有长度前缀，跨过去就会把下一个样本的首 4 字节当成 NAL 长度，
     * 立刻失配并 break，只能统计到第一个样本。必须按 stsz 的样本大小切分。
     *
     * 实测（2026-11-03，720p 一集）：密文 0%，解密后 100%，
     * 类型分布 VPS/SPS/PPS 各 1 + IDR 3 + TRAIL 197，符合正常 H.265 流。
     */
    private static String verifyNal(File f, String codec) {
        byte[] d;
        try {
            d = readAll(f);
        } catch (Exception e) {
            return "读取失败";
        }
        // 用 moov 里记录的权威样本偏移，而不是假设 mdat 载荷连续。
        // mdat 里是样本拼接，样本间没有长度前缀，跨样本解析必然失配。
        int[] sizes = Decryptor.videoSampleSizes(d);
        int[] offsets = Decryptor.videoSampleOffsets(d);
        if (sizes == null || offsets == null || sizes.length == 0) {
            return "无视频样本表";
        }
        int ok = 0;
        int bad = 0;
        for (int i = 0; i < sizes.length && ok + bad < 400; i++) {
            int p = offsets[i];
            int end = p + sizes[i];
            if (p < 0 || end > d.length) {
                break;
            }
            while (p + 4 <= end) {
                long n = ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                        | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
                if (n <= 0 || p + 4 + n > end) {
                    bad++;
                    break;
                }
                int h = d[p + 4] & 0xFF;
                int h2 = d[p + 5] & 0xFF;
                int forbidden = (h >> 7) & 1;
                int nut = (h >> 1) & 0x3F;
                int layer = ((h & 1) << 5) | ((h2 >> 3) & 0x1F);
                int tid = h2 & 0x07;
                if (forbidden == 0 && tid >= 1 && nut <= 40 && layer <= 63) {
                    ok++;
                } else {
                    bad++;
                }
                p += 4 + (int) n;
            }
        }
        int tot = ok + bad;
        if (tot == 0) {
            return "无法解析";
        }
        int pct = ok * 100 / tot;
        return String.format(Locale.US, "%d%%(%d/%d)", pct, ok, tot);
    }

    /**
     * 按顶层 box 链找到指定类型的 box，返回其「载荷起始位置」。
     * 返回 -1 表示没找到。
     */
    private static int findTopLevelBox(byte[] d, String type) {
        int p = 0;
        while (p + 8 <= d.length) {
            long size = ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                    | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            int header = 8;
            if (size == 1) {
                if (p + 16 > d.length) {
                    return -1;
                }
                size = 0;
                for (int i = 0; i < 8; i++) {
                    size = (size << 8) | (d[p + 8 + i] & 0xFF);
                }
                header = 16;
            } else if (size == 0) {
                size = d.length - p;
            }
            if (size < header || p + size > d.length) {
                return -1;
            }
            String t = new String(d, p + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (t.equals(type)) {
                return p + header;
            }
            p += (int) size;
        }
        return -1;
    }

    private static byte[] readAll(File f) throws Exception {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b);
            return b;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
