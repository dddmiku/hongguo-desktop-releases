package cn.hongguo.tv.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * APK 下载与安装。
 *
 * 为什么不走 Google Play 的应用内更新：
 *   这个 APK 是自己构建的，不在商店里，只能用「下载 APK + 系统安装器」。
 *
 * 为什么必须校验 sha256：
 *   下载走的是第三方 GitHub 加速镜像，内容虽然仍是 GitHub 上那份，
 *   但多一层传输通道就多一个可能被篡改的环节。latest.json 里的 sha256
 *   是发布时算的，装之前必须核对，不匹配就丢弃。
 *
 * 电视上的安装限制：
 *   大多数电视/盒子需要用户手动允许「未知来源安装」。这个权限没法
 *   静默申请，所以下载完调用系统安装器，让用户点一下确认。
 */
public final class ApkInstaller {

    private static final String TAG = "HgApk";

    /** GitHub 加速镜像。顺序按实测速度排（v4 最快）。 */
    private static final String[] PROXIES = {
            "https://v4.gh-proxy.org/",
            "https://gh-proxy.org/",
            "https://v6.gh-proxy.org/",
            "https://gh-proxy.com/",
            "",                      // 直连
    };

    /** 下载进度回调。 */
    public interface Progress {
        void onProgress(int pct, long doneBytes, long totalBytes);

        void onError(String message);

        void onDone(File apk);
    }

    private ApkInstaller() {
    }

    /**
     * 下载 APK。在后台线程调用。
     *
     * @param rawUrl GitHub 原始下载地址（latest.json 里的 url）
     * @param sha256 期望的 sha256，空串表示跳过校验
     * @param dst    保存路径
     */
    public static void download(String rawUrl, String sha256, File dst, Progress cb) {
        // 逐个镜像尝试：某个镜像挂了就换下一个，全部失败才报错
        for (String proxy : PROXIES) {
            String url = proxy.isEmpty() ? rawUrl : proxy + rawUrl;
            try {
                Log.i(TAG, "尝试下载 " + url);
                long n = fetch(url, dst, cb);
                if (n <= 0) {
                    continue;
                }
                if (!sha256.isEmpty()) {
                    String got = sha256(dst);
                    if (!sha256.equalsIgnoreCase(got)) {
                        Log.w(TAG, "sha256 不匹配，丢弃：" + got);
                        dst.delete();
                        continue;   // 换镜像重试，可能是传输截断
                    }
                }
                cb.onDone(dst);
                return;
            } catch (Throwable e) {
                Log.w(TAG, "镜像失败 " + proxy + " : " + e.getMessage());
                dst.delete();
            }
        }
        cb.onError("下载失败（所有镜像均不可用）");
    }

    private static long fetch(String url, File dst, Progress cb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        // 跟随跳转：GitHub 的 release 资源会 302 到对象存储
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "HongguoTV");
        long total = c.getContentLength();
        long done = 0;
        int lastPct = -1;
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct, done, total);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        return done;
    }

    /** 计算文件 sha256。 */
    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 清理已下载的安装包。
     *
     * 为什么要清：一个 APK 2.4MB，留在 cache/updates 里没有任何用处 ——
     * 装完就过期了（新版本又会有新文件）。而且不清的话，用户每次升级
     * 都会留下一个文件，长期没人管。
     *
     * 时机（见 MainActivity / AccountActivity）：
     *   1. 安装成功后回到前台；
     *   2. 启动时；
     *   3. 检查到「已是最新版本」时。
     * 不在这里「下载前清空」—— 那是「授权完回来要重新下载」的根因。
     *
     * @param keep 保留哪个文件（正在等待安装的那个），可传 null
     */
    public static void cleanOld(Context ctx, File keep) {
        try {
            File dir = new File(ctx.getCacheDir(), "updates");
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (keep != null && f.getAbsolutePath().equals(keep.getAbsolutePath())) {
                    continue;
                }
                if (f.delete()) {
                    Log.i(TAG, "清理安装包 " + f.getName());
                }
            }
            // 目录空着也留着：下次下载直接复用，不用再 mkdir。
        } catch (Throwable e) {
            Log.d(TAG, "清理安装包失败（忽略）: " + e.getMessage());
        }
    }

    /**
     * 找一个「还没装、且版本比当前新」的已下载安装包。
     *
     * 为什么需要：进程可能被杀（点「下载并安装」→ 跳授权页 → 系统低内存
     * 杀掉应用 → 回来进程重建，pendingApk 字段已经丢了）。文件在磁盘上
     * 就能用，不该让用户重新下载。
     */
    public static File findPending(Context ctx, String latestVersion) {
        try {
            File dir = new File(ctx.getCacheDir(), "updates");
            File f = new File(dir, "hongguo-tv-" + latestVersion + ".apk");
            if (f.isFile() && f.length() > 0) {
                return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 调起系统安装器。
     *
     * Android 8+ 需要 REQUEST_INSTALL_PACKAGES 权限，且用户要手动允许
     * 「安装未知应用」。没权限时跳到设置页引导，而不是静默失败。
     */
    public static void install(Context ctx, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !ctx.getPackageManager().canRequestPackageInstalls()) {
                // 没有安装权限：跳到本应用的「安装未知应用」设置页
                Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri uri = uriFor(ctx, apk);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 必须显式授权：Provider 是 exported=false 的，
            // 不 grant 的话安装器读不到文件（表现为「解析包错误」）。
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Throwable e) {
            Log.e(TAG, "调起安装器失败", e);
        }
    }

    /** 生成可被系统安装器读取的 URI。 */
    private static Uri uriFor(Context ctx, File f) {
        // Android 7+ 禁止 file:// 跨应用传文件（FileUriExposedException），
        // 必须走 content://。
        //
        // 这里不用 androidx.core 的 FileProvider：项目刻意不引 AndroidX
        // （包体约束，见 build.gradle）。改用一个自带的极简 Provider
        // （ApkProvider），只做「按路径读文件」这一件事。
        return Uri.parse("content://" + ctx.getPackageName() + ".apk/"
                + Uri.encode(f.getName()));
    }
}
