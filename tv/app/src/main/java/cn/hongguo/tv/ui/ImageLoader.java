package cn.hongguo.tv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简图片加载器：内存 LRU + 后台下载 + ImageView 复用标记。
 *
 * 为什么不引 Glide / Picasso：
 *   包体约束（见 build.gradle）。这里只需要「按 URL 取图、缓存、贴到
 *   ImageView」，自己写不到 100 行，而 Glide 会带进 ~500KB 依赖。
 *
 * 为什么需要 tag 校验：
 *   ImageView 会被回收复用，异步回来时它可能已经绑定了别的 URL。
 *   不校验就会出现「封面错位」——列表滚动后图串了。
 */
final class ImageLoader {

    private static final String TAG = "HgImg";

    /** 内存缓存按 1/8 可用内存分配，够放几十张封面。 */
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap v) {
                    return v.getByteCount();
                }
            };

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    /** 把 url 对应的图贴到 view 上；加载中先显示占位色。 */
    static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            view.setTag(null);
            return;
        }
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            view.setTag(url);
            return;
        }
        view.setImageDrawable(null);
        view.setTag(url);
        POOL.execute(() -> {
            Bitmap bmp = fetch(url);
            if (bmp == null) {
                return;
            }
            CACHE.put(url, bmp);
            UI.post(() -> {
                // 复用校验：这期间 view 可能已经绑了别的图
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bmp);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = c.getInputStream()) {
                // 先读进内存再解码：inSampleSize 需要流长度，
                // 而封面 URL 常带签名参数，Content-Length 不一定给。
                byte[] data = readAll(in, 4 * 1024 * 1024);
                if (data == null) {
                    return null;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                // 封面显示宽度约 200dp，超过 400px 就没必要留原图
                o.inSampleSize = sample(o.outWidth, 400);
                o.inJustDecodeBounds = false;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
        } catch (Throwable e) {
            Log.d(TAG, "取图失败 " + url + " : " + e.getMessage());
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static int sample(int w, int target) {
        int s = 1;
        while (w / s > target * 2) {
            s *= 2;
        }
        return s;
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                return null;
            }
        }
        return out.toByteArray();
    }
}
