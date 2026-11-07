package cn.hongguo.tv.media;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import cn.hongguo.tv.cache.MediaCache;

/**
 * 一集的准备流程：下载密文 → spade 出 key → 解密 → 得到可播放明文。
 *
 * 为什么要单独一个类：
 *   播放器 UI 只关心「准备好了没 / 百分比」，不该知道 spade、CENC、senc
 *   这些细节。所有失败模式（下载断、key 算不出、解密无视频轨）都在这里
 *   收敛成一句话，UI 直接显示。
 *
 * 线程模型：run() 是阻塞的，调用方放到后台线程；进度通过回调回到调用线程
 * （由调用方保证回到 UI 线程）。
 */
public final class EpisodePreparer {

    private static final String TAG = "HgPrep";

    /** 进度回调。stage 是给人看的短句，pct 是 0..100。 */
    public interface Listener {
        void onProgress(String stage, int pct);

        void onDone(File plain);

        void onError(String message);
    }

    private final MediaCache cache;

    public EpisodePreparer(MediaCache cache) {
        this.cache = cache;
    }

    /**
     * 准备一集。
     *
     * @param vid        集 id
     * @param definition 清晰度名（用于缓存分桶）
     * @param url        密文直链
     * @param spadeA     encrypt_info.spade_a
     * @param encrypted  是否需要解密
     */
    public void prepare(String vid, String definition, String url, String spadeA,
                        boolean encrypted, Listener l) {
        File plain = cache.plainFile(vid, definition);
        if (plain.isFile() && plain.length() > 0) {
            Log.i(TAG, "命中缓存 " + plain.getName());
            l.onProgress("已缓存", 100);
            l.onDone(plain);
            return;
        }

        File cipher = cache.cipherFile(vid, definition);
        try {
            l.onProgress("下载中", 0);
            long total = download(url, cipher, pct -> l.onProgress("下载中", pct));
            Log.i(TAG, "下载完成 " + total / 1024 + "KB");
            if (total <= 0) {
                l.onError("下载失败");
                return;
            }

            if (!encrypted) {
                if (!cipher.renameTo(plain)) {
                    copy(cipher, plain);
                }
                cipher.delete();
                l.onDone(plain);
                return;
            }

            l.onProgress("解密中", 90);
            byte[] key = Spade.contentKey(spadeA);
            if (key == null) {
                l.onError("密钥解析失败");
                return;
            }
            Decryptor.Result r = Decryptor.decrypt(cipher, plain, key);
            cipher.delete();
            Log.i(TAG, "解密完成 视频=" + r.videoSamples + " 音频=" + r.audioSamples);
            if (r.videoSamples <= 0) {
                l.onError("解密后无视频");
                return;
            }
            l.onProgress("就绪", 100);
            l.onDone(plain);
        } catch (Throwable e) {
            Log.e(TAG, "准备失败", e);
            cipher.delete();
            l.onError(friendly(e));
        }
    }

    /** 把异常翻译成用户能看懂的一句话。 */
    private static String friendly(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            return "加载失败";
        }
        return m;
    }

    private interface Pct {
        void set(int pct);
    }

    /** 下载到文件，按字节数回报百分比（总长未知时只报 0）。 */
    private static long download(String url, File dst, Pct pct) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        // 必须整集下载：moov 里的样本偏移是相对完整文件的，
        // 只取前面一部分会让大量样本越界、解出来只剩开头几帧。
        long total = c.getContentLength();
        long done = 0;
        int last = -1;
        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int p = (int) (done * 85 / total);   // 下载占进度条的 85%
                    if (p != last) {
                        last = p;
                        pct.set(p);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        return done;
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }
}
