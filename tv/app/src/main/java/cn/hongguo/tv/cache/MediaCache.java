package cn.hongguo.tv.cache;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 解密后 mp4 的本地缓存。
 *
 * 为什么必须落盘而不是边下边解：
 *   片源是 CENC 加密的 fMP4，moov 里记的样本偏移是相对**完整文件**的。
 *   要解密就必须先拿到整集密文（实测 720p 一集 15MB），无法像普通 HLS
 *   那样按需取片段。所以流程是「整集下载 → 解密 → 播放明文 mp4」，
 *   解出来的明文存这里，同一集第二次播放直接命中。
 *
 * 为什么用「清晰度 + vid」做 key：
 *   同一集不同清晰度的密文与密钥都不同，混用会解出噪声。
 *
 * 淘汰策略：按最后访问时间清理，上限 2GB。电视盒子存储小，
 * 不设上限很快会把用户空间吃满。
 */
public final class MediaCache {

    private static final String TAG = "HgCache";

    /** 缓存上限。超了按最久未使用删除。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private final File dir;

    public MediaCache(Context ctx) {
        this.dir = new File(ctx.getCacheDir(), "media");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "缓存目录创建失败: " + dir);
        }
    }

    /** 某一集某清晰度的明文缓存文件。 */
    public File plainFile(String vid, String definition) {
        return new File(dir, key(vid, definition) + ".mp4");
    }

    /** 密文临时文件（解密完就删）。 */
    public File cipherFile(String vid, String definition) {
        return new File(dir, key(vid, definition) + ".enc");
    }

    /** 明文是否已就绪且非空。 */
    public boolean has(String vid, String definition) {
        File f = plainFile(vid, definition);
        if (!f.isFile() || f.length() <= 0) {
            return false;
        }
        // 触摸一下，让 LRU 淘汰知道它刚被用过
        f.setLastModified(System.currentTimeMillis());
        return true;
    }

    /** 清理：先删残留的 .enc，再按最后访问时间淘汰到上限以内。 */
    public void trim() {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        java.util.List<File> alive = new java.util.ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            // 密文是中间产物，任何时刻都可以删（下次播放重新下）
            if (f.getName().endsWith(".enc")) {
                if (f.delete()) {
                    Log.i(TAG, "清理残留密文 " + f.getName());
                }
                continue;
            }
            total += f.length();
            alive.add(f);
        }
        if (total <= MAX_BYTES) {
            return;
        }
        // 最久未访问的排前面
        java.util.Collections.sort(alive, (a, b) ->
                Long.compare(a.lastModified(), b.lastModified()));
        for (File f : alive) {
            if (total <= MAX_BYTES) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
                Log.i(TAG, "淘汰 " + f.getName() + " (" + len / 1024 / 1024 + "MB)");
            }
        }
    }

    /** 缓存占用（字节）。 */
    public long usedBytes() {
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
        }
        return total;
    }

    /** 清空全部缓存。 */
    public void clear() {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            f.delete();
        }
    }

    /**
     * 缓存键：vid + 清晰度 的短哈希。
     *
     * 不用原名拼接是因为 vid 很长、清晰度带斜杠之类字符，
     * 直接做文件名在不同文件系统上行为不一致。
     */
    private static String key(String vid, String definition) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest((vid + "|" + definition).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format(Locale.US, "%02x", h[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return vid.hashCode() + "-" + definition.hashCode();
        }
    }
}
