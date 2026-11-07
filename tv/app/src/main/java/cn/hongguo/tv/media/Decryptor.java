package cn.hongguo.tv.media;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * MP4 CENC 解密：把加密的 fMP4 还原成可直接播放的 mp4。
 *
 * 上游 mp4 是 CENC 加密的（encv 盒 + senc 盒）。解密步骤：
 *   1. 从 senc 盒取每个样本的 IV（首样本的高 8 字节即 base_iv）
 *   2. 用 base_iv + 样本序号构造 16 字节 IV，AES-128-CTR 逐样本解密
 *   3. 清除加密信令（encv→hvc1/avc1、删 senc/sinf/saiz/saio），
 *      否则严格播放器会拒绝打开
 *
 * IV 构造规则（与 PC 端 decutil.py 完全一致，已验证 1906/1906 NAL 合法）：
 *   iv = (base_iv_high64 + sample_index) << 64
 *   即低 64 位为 0，高 64 位随样本递增。
 *
 * 这个类只在内存/临时文件里操作，不修改源文件。
 */
public final class Decryptor {

    private static final String TAG = "HgDecrypt";

    private static final int BOX_HEADER = 8;
    private static final int MAX_SAMPLES = 200000;

    private Decryptor() {
    }

    public static final class Result {
        public final int videoSamples;
        public final int audioSamples;
        public final String videoCodec;

        Result(int v, int a, String codec) {
            this.videoSamples = v;
            this.audioSamples = a;
            this.videoCodec = codec;
        }
    }

    /**
     * 解密整集。
     *
     * @param encrypted 下载下来的加密 mp4
     * @param out       输出明文 mp4
     * @param key       16 字节 content key
     */
    public static Result decrypt(File encrypted, File out, byte[] key) throws Exception {
        byte[] data = readAll(encrypted);

        // 逐 trak 处理：视频与音频各用自身的 senc base_iv。
        List<Box> traks = findAll(data, 0, data.length, "trak");
        int videoSamples = 0;
        int audioSamples = 0;
        String videoCodec = "";

        for (Box trak : traks) {
            String handler = findHandler(data, trak);
            if (!"vide".equals(handler) && !"soun".equals(handler)) {
                continue;
            }
            Box senc = findFirst(data, trak.start, trak.end, "senc");
            if (senc == null) {
                continue;   // 该轨未加密
            }
            SampleTable st = parseSampleTable(data, trak, handler);
            if (st == null || st.sizes.length == 0) {
                continue;
            }
            long baseIv = readBaseIv(data, senc);
            int n = decryptTrack(data, key, baseIv, st);
            if ("vide".equals(handler)) {
                videoSamples = n;
                videoCodec = readVideoCodec(data, trak);
            } else {
                audioSamples = n;
            }
        }

        if (videoSamples == 0) {
            throw new IOException("未找到可解密的视频轨");
        }

        // 清信令：不删掉 encv/senc/saiz/saio 的话，严格播放器会判定为加密文件而拒绝播放。
        stripEncryptionSignaling(data, videoCodec);

        writeAll(out, data);
        Log.i(TAG, "解密完成 视频=" + videoSamples + " 音频=" + audioSamples
                + " codec=" + videoCodec
                + " 输入=" + encrypted.length() + " 输出=" + out.length());
        return new Result(videoSamples, audioSamples, videoCodec);
    }

    // ---------------------------------------------------------------- 解密核心

    private static int decryptTrack(byte[] data, byte[] key, long baseIv, SampleTable st) {
        SecretKeySpec k = new SecretKeySpec(key, "AES");
        int ok = 0;
        for (int i = 0; i < st.sizes.length; i++) {
            int off = st.offsets[i];
            int size = st.sizes[i];
            if (off < 0 || size <= 0 || off + size > data.length) {
                continue;
            }
            // iv = (baseIv + index) << 64，低 64 位为 0
            long high = baseIv + i;
            byte[] iv = new byte[16];
            for (int b = 0; b < 8; b++) {
                iv[b] = (byte) (high >>> (56 - b * 8));
            }
            try {
                Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(iv));
                byte[] plain = c.doFinal(data, off, size);
                System.arraycopy(plain, 0, data, off, size);
                ok++;
            } catch (Exception e) {
                Log.w(TAG, "样本 " + i + " 解密失败", e);
            }
        }
        return ok;
    }

    /**
     * senc 盒里首样本 IV 的高 8 字节就是 base_iv。
     *
     * 盒结构（version=0, flags=0 时，实测红果就是这种）：
     *   size(4) 'senc'(4) version/flags(4) sample_count(4) [iv(8) subsample...]
     *   即 BOX_HEADER(8) + 4(version/flags) + 4(sample_count) = start + 16
     *
     * 少跳过 sample_count 那 4 字节的话，读到的会是「count 低 2 字节 + IV 前 6 字节」
     * 这种错位值，解出来的视频是噪声而播放器只报「无法播放」——极难定位。
     * 实测（2026-11-03 720p 一集）这个 bug 让 NAL 合法率从 100% 掉到 0%。
     */
    private static long readBaseIv(byte[] data, Box senc) {
        int p = senc.start + BOX_HEADER + 8;
        if (p + 8 > data.length) {
            return 0;
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[p + i] & 0xFF);
        }
        return v;
    }

    // ---------------------------------------------------------------- MP4 解析

    /** 一个 box 在 byte[] 里的范围。 */
    static final class Box {
        final int start;
        final int end;
        final String type;

        Box(int start, int end, String type) {
            this.start = start;
            this.end = end;
            this.type = type;
        }
    }

    static final class SampleTable {
        int[] sizes;
        int[] offsets;
    }

    /**
     * 诊断用：返回明文文件里视频轨的样本大小表。
     *
     * 供 DecryptProbe 按真实样本边界校验 NAL —— mdat 里是样本拼接，
     * 样本之间没有长度前缀，跨样本连续解析必然失配。必须用 moov 里
     * 记录的权威偏移，而不是假设 mdat 载荷从第一个样本开始。
     *
     * @return 样本大小数组；找不到视频轨返回 null
     */
    public static int[] videoSampleSizes(byte[] d) {
        for (Box trak : findAll(d, 0, d.length, "trak")) {
            if (!"vide".equals(findHandler(d, trak))) {
                continue;
            }
            SampleTable st = parseSampleTable(d, trak, "vide");
            if (st != null) {
                return st.sizes;
            }
        }
        return null;
    }

    /**
     * 诊断用：返回明文文件里视频轨的样本文件偏移表。
     *
     * @return 样本偏移数组；找不到视频轨返回 null
     */
    public static int[] videoSampleOffsets(byte[] d) {
        for (Box trak : findAll(d, 0, d.length, "trak")) {
            if (!"vide".equals(findHandler(d, trak))) {
                continue;
            }
            SampleTable st = parseSampleTable(d, trak, "vide");
            if (st != null) {
                return st.offsets;
            }
        }
        return null;
    }

    private static List<Box> findAll(byte[] d, int from, int to, String type) {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + BOX_HEADER <= to) {
            long size = u32(d, p);
            String t = str(d, p + 4);
            int header = BOX_HEADER;
            if (size == 1) {
                if (p + 16 > to) {
                    break;
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                break;
            }
            if (t.equals(type)) {
                out.add(new Box(p, (int) (p + size), t));
            }
            // 递归进容器盒
            if (isContainer(t)) {
                out.addAll(findAll(d, p + header, (int) (p + size), type));
            }
            p += (int) size;
        }
        return out;
    }

    private static Box findFirst(byte[] d, int from, int to, String type) {
        List<Box> l = findAll(d, from, to, type);
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean isContainer(String t) {
        switch (t) {
            case "moov":
            case "trak":
            case "mdia":
            case "minf":
            case "stbl":
            case "edts":
            case "dinf":
            case "stsd":      // sample entry（encv/mp4a）在它的载荷里
                return true;
            default:
                return false;
        }
    }

    /** 视觉 sample entry 的固定头部长度，其子盒（hvcC/sinf/...）从这之后开始。 */
    private static final int VISUAL_ENTRY_HEADER = 78;
    /** 音频 sample entry 的固定头部长度。 */
    private static final int AUDIO_ENTRY_HEADER = 28;

    private static String findHandler(byte[] d, Box trak) {
        Box mdia = findFirst(d, trak.start, trak.end, "mdia");
        if (mdia == null) {
            return "";
        }
        Box hdlr = findFirst(d, mdia.start, mdia.end, "hdlr");
        if (hdlr == null) {
            return "";
        }
        // hdlr: size(4) type(4) version/flags(4) pre_defined(4) handler_type(4)
        int p = hdlr.start + BOX_HEADER + 8;
        return p + 4 <= d.length ? str(d, p) : "";
    }

    private static String readVideoCodec(byte[] d, Box trak) {
        Box stsd = findFirst(d, trak.start, trak.end, "stsd");
        if (stsd == null) {
            return "";
        }
        // stsd: size(4) type(4) ver/flags(4) entry_count(4) entry...
        int p = stsd.start + BOX_HEADER + 8;
        if (p + 8 > d.length) {
            return "";
        }
        return str(d, p + 4);   // sample entry 的 fourcc
    }

    /**
     * 解析 stsz / stco(co64) / stsc 得到每个样本的文件偏移与大小。
     * 这是解密必须的信息：AES-CTR 要按样本边界逐块解密。
     *
     * 必须用 stsc（sample-to-chunk）做精确映射，不能按「样本数 ÷ chunk 数」均分。
     * 上游 fMP4 的 chunk 分布并不均匀（首尾常有不满的 chunk），均分会让中后段
     * 所有样本偏移错位，解出来的视频是噪声而播放器只报「无法播放」，
     * 极难定位。桌面端 oracle.py::parse_track_samples 用的就是这套精确映射。
     */
    private static SampleTable parseSampleTable(byte[] d, Box trak, String handler) {
        Box stsz = findFirst(d, trak.start, trak.end, "stsz");
        Box stco = findFirst(d, trak.start, trak.end, "stco");
        Box co64 = findFirst(d, trak.start, trak.end, "co64");
        Box stsc = findFirst(d, trak.start, trak.end, "stsc");
        if (stsz == null || (stco == null && co64 == null)) {
            return null;
        }

        int p = stsz.start + BOX_HEADER + 4;
        if (p + 8 > d.length) {
            return null;
        }
        long sampleSize = u32(d, p);
        long count = u32(d, p + 4);
        if (count <= 0 || count > MAX_SAMPLES) {
            return null;
        }
        int[] sizes = new int[(int) count];
        if (sampleSize > 0) {
            for (int i = 0; i < count; i++) {
                sizes[i] = (int) sampleSize;
            }
        } else {
            int q = p + 8;
            for (int i = 0; i < count; i++) {
                if (q + 4 > d.length) {
                    return null;
                }
                sizes[i] = (int) u32(d, q);
                q += 4;
            }
        }

        Box chunkOff = stco != null ? stco : co64;
        int cp = chunkOff.start + BOX_HEADER + 4;
        if (cp + 4 > d.length) {
            return null;
        }
        long chunks = u32(d, cp);
        if (chunks <= 0 || chunks > MAX_SAMPLES) {
            return null;
        }
        long[] chunkOffsets = new long[(int) chunks];
        int cq = cp + 4;
        boolean wide = co64 != null;
        for (int i = 0; i < chunks; i++) {
            if (cq + (wide ? 8 : 4) > d.length) {
                return null;
            }
            chunkOffsets[i] = wide ? u64(d, cq) : u32(d, cq);
            cq += wide ? 8 : 4;
        }

        // 每个 chunk 装几个样本：由 stsc 的 run 表决定。
        // stsc 每条记录是 (first_chunk, samples_per_chunk, sample_desc_index)，
        // 表示从 first_chunk 起、直到下一条记录的 first_chunk 之前，都按这个
        // samples_per_chunk 计。没有 stsc 时退化为 1 样本/chunk（fMP4 常见形态）。
        int[] perChunk = new int[(int) chunks];
        if (stsc != null) {
            int sp = stsc.start + BOX_HEADER + 4;
            if (sp + 4 > d.length) {
                return null;
            }
            long entries = u32(d, sp);
            // 上限按「每条记录 12 字节、整张表不超过 64KB」算，而不是拍一个
            // 小的魔数：音频轨的 stsc 常有一两千到五千条记录（实测 5018 条），
            // 早先卡在 4096 会让音频轨整条被跳过（表现为「音频样本=0」，
            // 视频能解但没声音）。
            if (entries <= 0 || entries > 16384) {
                return null;
            }
            int eq = sp + 4;
            long[] firstChunk = new long[(int) entries];
            long[] samplesPer = new long[(int) entries];
            for (int i = 0; i < entries; i++) {
                if (eq + 12 > d.length) {
                    return null;
                }
                firstChunk[i] = u32(d, eq);
                samplesPer[i] = u32(d, eq + 4);
                eq += 12;   // 跳过 sample_description_index
            }
            for (int i = 0; i < entries; i++) {
                long from = firstChunk[i];
                long to = i + 1 < entries ? firstChunk[i + 1] - 1 : chunks;
                for (long c = from; c <= to; c++) {
                    if (c >= 1 && c <= chunks) {
                        perChunk[(int) (c - 1)] = (int) samplesPer[i];
                    }
                }
            }
        } else {
            for (int i = 0; i < chunks; i++) {
                perChunk[i] = 1;
            }
        }

        int[] offsets = new int[(int) count];
        int idx = 0;
        for (int c = 0; c < chunks && idx < count; c++) {
            long off = chunkOffsets[c];
            for (int k = 0; k < perChunk[c] && idx < count; k++) {
                offsets[idx] = (int) off;
                off += sizes[idx];
                idx++;
            }
        }
        // stsc 覆盖不到的尾部样本：按前一个样本顺延（只在上游元数据不全时发生）
        while (idx < count && idx > 0) {
            offsets[idx] = offsets[idx - 1] + sizes[idx - 1];
            idx++;
        }
        if (idx < count) {
            return null;
        }

        SampleTable st = new SampleTable();
        st.sizes = sizes;
        st.offsets = offsets;
        return st;
    }

    // ---------------------------------------------------------------- 清信令

    /**
     * 把加密音频 sample entry（enca）改回原始编码。
     *
     * enca 的结构里，sinf/frma 记录了原始 fourcc（如 mp4a）。
     * 优先读它；读不到就退回 mp4a（红果目前所有片源音频都是 AAC）。
     *
     * 为什么单独一个函数而不是复用 rewriteBoxTypes：
     *   目标 fourcc 是逐个 entry 动态决定的，不是全文件统一常量。
     *
     * 递归范围要包含 stsd —— enca 是 stsd 的载荷，不在 trak 的直接子级。
     */
    private static void rewriteAudioEntry(byte[] d, int from, int to) {
        rewriteAudioEntryIn(d, from, to);
    }

    private static void rewriteAudioEntryIn(byte[] d, int from, int to) {
        int p = from;
        while (p + BOX_HEADER <= to) {
            long size = u32(d, p);
            int header = BOX_HEADER;
            if (size == 1) {
                if (p + 16 > to) {
                    return;
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                return;
            }
            String t = str(d, p + 4);
            if ("stsd".equals(t)) {
                Log.i(TAG, "发现 stsd @" + p + " size=" + size);
                // stsd: size(4) 'stsd'(4) version/flags(4) entry_count(4) entry...
                // entry 是 sample entry（enca / mp4a / encv ...）
                int q = p + header + 8;
                int end = (int) (p + size);
                while (q + BOX_HEADER <= end) {
                    long esz = u32(d, q);
                    int ehdr = BOX_HEADER;
                    if (esz == 1) {
                        if (q + 16 > end) {
                            break;
                        }
                        esz = u64(d, q + 8);
                        ehdr = 16;
                    } else if (esz == 0) {
                        esz = end - q;
                    }
                    if (esz < ehdr || q + esz > end) {
                        break;
                    }
                    String fcc = str(d, q + 4);
                    Log.i(TAG, "  entry fourcc=" + fcc + " @" + q);
                    if ("enca".equals(fcc)) {
                        String orig = originalFormat(d, q + ehdr, (int) (q + esz));
                        String target = orig != null && orig.length() == 4 ? orig : "mp4a";
                        System.arraycopy(target.getBytes(StandardCharsets.US_ASCII),
                                0, d, q + 4, 4);
                        Log.i(TAG, "音频 entry enca -> " + target);
                    }
                    q += (int) esz;
                }
            } else if ("trak".equals(t) || "mdia".equals(t) || "minf".equals(t)
                    || "stbl".equals(t)) {
                rewriteAudioEntryIn(d, p + header, (int) (p + size));
            }
            p += (int) size;
        }
    }

    /** 在 sample entry 里找 sinf/frma，返回原始 fourcc。 */
    private static String originalFormat(byte[] d, int from, int to) {
        // 音频 sample entry 固定 28 字节头之后是子盒
        int p = from + AUDIO_ENTRY_HEADER;
        while (p + BOX_HEADER <= to) {
            long size = u32(d, p);
            int header = BOX_HEADER;
            if (size == 1) {
                if (p + 16 > to) {
                    return null;
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                return null;
            }
            String t = str(d, p + 4);
            if ("sinf".equals(t)) {
                // sinf 载荷：frma(12) + schm(...) + schi(...)
                int q = p + header;
                while (q + BOX_HEADER <= p + size) {
                    long s2 = u32(d, q);
                    if (s2 < BOX_HEADER || q + s2 > p + size) {
                        break;
                    }
                    if ("frma".equals(str(d, q + 4))) {
                        // frma: size(4) 'frma'(4) data_format(4)
                        return str(d, q + 8);
                    }
                    q += (int) s2;
                }
            }
            p += (int) size;
        }
        return null;
    }

    /**
     * 剥掉加密信令，让文件变成「普通明文 mp4」。
     *
     * 关键：**只能在 moov 内部、且只按 box 类型字段替换**。
     *
     * 早先的实现是在整个文件里扫四字节序列做全局替换，这是错的：
     * mdat 里是 H.265 码流，`encv`/`senc`/`sinf` 这四个字节会以一定概率
     * 随机出现在压缩数据中，替换掉就会破坏视频数据，解出来的文件
     * box 结构损坏、播放器直接打不开（实测文件还因此变大了 60KB）。
     *
     * 正确做法：遍历 moov 内的 box，只把「box 的 type 字段」从
     * encv 改成 hvc1/avc1、把 senc/saiz/saio/sinf 改成 free。
     * box 的 type 字段位置是 box 起始 + 4，不是任意位置。
     *
     * @param videoCodec 原始视频编码 fourcc（encv 时按实际编码决定改成什么）
     */
    private static void stripEncryptionSignaling(byte[] d, String videoCodec) {
        Box moov = findFirst(d, 0, d.length, "moov");
        if (moov == null) {
            return;
        }
        // 视频轨的 sample entry 是 encv，要改成真实编码的 fourcc，
        // 否则播放器仍按「加密视频」处理。
        String target = "hvc1";
        if (videoCodec != null) {
            String c = videoCodec.toLowerCase(java.util.Locale.US);
            if (c.equals("avc1") || c.equals("avc3") || c.equals("h264")) {
                target = "avc1";
            }
        }
        rewriteBoxTypes(d, moov.start, moov.end, "encv", target);
        // 音频轨的 sample entry 是 enca（加密音频）。
        //
        // 之前只处理 encv 漏了 enca，后果是**视频能播但完全没声音**：
        // 播放器看到 enca 就按加密音轨处理，直接跳过不送解码器。
        // 而日志里「音频样本=8274」说明数据其实已经解出来了，
        // 只是容器还标着加密 —— 这个 bug 极难从日志看出来。
        //
        // 目标 fourcc 从 enca 里的 sinf/frma 读（红果目前都是 mp4a），
        // 读不到就退回 mp4a。
        rewriteAudioEntry(d, moov.start + BOX_HEADER, moov.end);
        for (String t : new String[]{"senc", "saiz", "saio", "sinf"}) {
            rewriteBoxTypes(d, moov.start, moov.end, t, "free");
        }
    }

    /**
     * 在 [from,to) 范围内按 box 结构遍历，只替换 type 字段。
     *
     * 用 box 长度链跳转而不是逐字节扫，保证不会碰到 box 的载荷
     * （载荷里可能有同样的四字节）。
     */
    private static void rewriteBoxTypes(byte[] d, int from, int to, String type, String toType) {
        byte[] want = type.getBytes(StandardCharsets.US_ASCII);
        byte[] repl = toType.getBytes(StandardCharsets.US_ASCII);
        int p = from;
        while (p + BOX_HEADER <= to) {
            long size = u32(d, p);
            int header = BOX_HEADER;
            if (size == 1) {
                if (p + 16 > to) {
                    return;
                }
                size = u64(d, p + 8);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                return;
            }
            // 当前 box 的 type 字段在 p+4
            boolean hit = true;
            for (int k = 0; k < 4; k++) {
                if (d[p + 4 + k] != want[k]) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                System.arraycopy(repl, 0, d, p + 4, 4);
            }
            // 容器盒要递归进去，否则 trak/stbl 里的 senc 找不到。
            // 注意 type 要取「替换后」的值：encv 换成 hvc1 后仍要往下钻，
            // 因为 sinf/tenc 是它的子盒，不处理播放器会继续当加密流。
            String t = new String(d, p + 4, 4, StandardCharsets.US_ASCII);
            if (isContainer(t)) {
                int childStart = p + header;
                if ("stsd".equals(t)) {
                    // stsd 载荷：version/flags(4) + entry_count(4) + sample entry
                    childStart = p + header + 8;
                }
                rewriteBoxTypes(d, childStart, (int) (p + size), type, toType);
            } else if ("hvc1".equals(t) || "hev1".equals(t) || "avc1".equals(t)
                    || "avc3".equals(t) || "encv".equals(t)) {
                // 视觉 sample entry：固定 78 字节头之后才是子盒
                rewriteBoxTypes(d, p + header + VISUAL_ENTRY_HEADER,
                        (int) (p + size), type, toType);
            } else if ("mp4a".equals(t) || "enca".equals(t)) {
                // 音频 sample entry：固定 28 字节头之后才是子盒
                rewriteBoxTypes(d, p + header + AUDIO_ENTRY_HEADER,
                        (int) (p + size), type, toType);
            }            p += (int) size;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static long u64(byte[] d, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (d[p + i] & 0xFF);
        }
        return v;
    }

    private static String str(byte[] d, int p) {
        if (p + 4 > d.length) {
            return "";
        }
        return new String(d, p, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > 512L * 1024 * 1024) {
            throw new IOException("文件大小异常");
        }
        byte[] d = new byte[(int) len];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(d);
        }
        return d;
    }

    private static void writeAll(File f, byte[] d) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.write(d);
        }
    }
}
