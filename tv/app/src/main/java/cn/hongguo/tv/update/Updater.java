package cn.hongguo.tv.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 更新检查。
 *
 * 为什么不走 Android 那套「静默下载安装」：
 *   1. 需要 REQUEST_INSTALL_PACKAGES 权限，用户还得手动允许「未知来源」；
 *   2. 电视盒子上这条路径经常被厂商 ROM 拦掉；
 *   3. 我们的安装包是自己构建的，走系统浏览器/下载器交给用户点一下
 *      更透明，也避免了「提示正在安装然后失败」。
 *   所以这里只做「检查 + 打开下载页」。
 *
 * 数据源与桌面端一致：Release 里的静态 latest.json。多镜像按速度排序，
 * 每个 6 秒超时——国内直连 GitHub 实测要 19 秒，镜像 1.5~3 秒。
 * 镜像只是传输通道，内容仍是 GitHub 上那份；安装包另有 sha256，
 * 多挂几个镜像不引入信任问题。
 */
public final class Updater {

    private static final String TAG = "HgUpdate";

    private static final String REPO = "dddmiku/hongguo-desktop-releases";
    private static final String GH_LATEST =
            "https://github.com/" + REPO + "/releases/latest/download/latest.json";

    /** 镜像顺序按实测速度排（v4 最快）。 */
    private static final String[] LATEST_URLS = {
            "https://v4.gh-proxy.org/" + GH_LATEST,
            "https://gh-proxy.org/" + GH_LATEST,
            "https://v6.gh-proxy.org/" + GH_LATEST,
            "https://gh-proxy.com/" + GH_LATEST,
            GH_LATEST,
    };

    private static final String PAGE_URL =
            "https://github.com/" + REPO + "/releases/latest";

    private static final int TIMEOUT = 6000;

    private Updater() {
    }

    /** 检查结果。 */
    public static final class Result {
        public boolean ok;
        public String current;
        public String latest;
        public boolean updateAvailable;
        public String notes = "";
        public String pageUrl = PAGE_URL;
        /** APK 直链（GitHub 原始地址，下载时会套加速镜像）。 */
        public String apkUrl = "";
        /** 发布时算的 sha256，下载完必须核对。 */
        public String sha256 = "";
        public long size;
        public String error = "";

        /** 给人看的一行话。 */
        public String message() {
            if (!ok) {
                return "检查更新失败：" + error;
            }
            if (!updateAvailable) {
                return "已是最新版本 " + current;
            }
            return "发现新版本 " + latest + "（当前 " + current + "）";
        }
    }

    /** 在后台线程调用。 */
    public static Result check(Context ctx) {
        Result r = new Result();
        r.current = localVersion(ctx);
        JSONObject j = fetchLatest();
        if (j == null) {
            r.error = "无法连接更新服务";
            return r;
        }

        // 关键：TV 端必须读自己的平台 key，不能拿桌面端的顶层 version 去比。
        // 桌面端已经 1.9.x，TV 端是 1.0.x，两条版本线完全不同；
        // 混用会让 TV 端永远提示「有新版本」，点进去下载到的还是 exe。
        JSONObject platforms = j.optJSONObject("platforms");
        JSONObject tv = platforms == null ? null : platforms.optJSONObject(TV_KEY);
        if (tv == null) {
            r.ok = true;
            r.latest = r.current;
            r.updateAvailable = false;
            r.notes = "";
            r.error = "";
            // 没有 TV 资产 = 还没发过 TV 版，按「已是最新」处理，不误报
            return r;
        }

        String latest = tv.optString("version", "").replaceFirst("^v", "");
        if (latest.isEmpty()) {
            latest = j.optString("version", "").replaceFirst("^v", "");
        }
        if (latest.isEmpty()) {
            r.error = "更新信息格式异常";
            return r;
        }
        r.ok = true;
        r.latest = latest;
        r.notes = tv.optString("notes", j.optString("notes", ""));
        r.updateAvailable = compare(latest, r.current) > 0;
        String u = tv.optString("url", "");
        if (!u.isEmpty()) {
            r.pageUrl = u;
            r.apkUrl = u;
        }
        r.sha256 = tv.optString("sha256", "");
        r.size = tv.optLong("size", 0);
        return r;
    }

    /** TV 端在 latest.json 里的平台 key。 */
    private static final String TV_KEY = "android-tv";

    /** 打开下载页。电视上通常没有浏览器，会由系统给出可用的应用列表。 */
    public static void openDownloadPage(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable e) {
            Log.w(TAG, "无法打开下载页", e);
        }
    }

    /** 当前版本号，来自 build.gradle 的 versionName。 */
    public static String currentVersion(Context ctx) {
        return localVersion(ctx);
    }

    /** 当前版本号，来自 build.gradle 的 versionName。 */
    private static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "0.0.0";
        }
    }

    /** 按段数值比较：1.10.0 > 1.9.0（不能字符串比）。 */
    public static int compare(String a, String b) {
        String[] x = a.split("[.\\-+]");
        String[] y = b.split("[.\\-+]");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? num(x[i]) : 0;
            int yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) {
                return xi > yi ? 1 : -1;
            }
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 依次试各镜像，第一个成功的就用。 */
    private static JSONObject fetchLatest() {
        for (String u : LATEST_URLS) {
            try {
                String body = httpGet(u);
                if (body != null && !body.isEmpty()) {
                    JSONObject j = new JSONObject(body);
                    Log.i(TAG, "更新信息来自 " + u);
                    return j;
                }
            } catch (Throwable e) {
                Log.d(TAG, "镜像失败 " + u + " : " + e.getMessage());
            }
        }
        return null;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "HongguoTV");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 256 * 1024) {
                    return null;
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
