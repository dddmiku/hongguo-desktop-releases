package cn.hongguo.tv.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.UUID;

/**
 * 设备身份：device_id / iid / cdid + 两个会话 id。
 *
 * 为什么不能用随机值：护照（登录）接口对 device_id 和 iid **两个字段一起**
 * 校验，随机值直接 403 且响应体为空（界面只能看到「非 JSON 响应」，
 * 完全看不出原因）。实测逐字段替换：两个都换成已注册的才 200，只换一个仍 403。
 *
 * 内容接口（搜索/取流/播放）不校验这些，所以未登录也能正常浏览 ——
 * 这也是为什么设备身份问题只在登录时暴露。
 *
 * 身份来源：随包的 assets/device.json（桌面包里的 device-bundled.json），
 * 首次启动写入 SharedPreferences 固定下来。会话 id 每次请求必须一致
 * （真机就是这样），所以也要持久化，不能每次现生成。
 */
final class DeviceIdentity {

    private static final String TAG = "HgDevice";
    private static final String PREFS = "hg_device";
    private static final String ASSET = "device.json";

    private static final String K_DEVICE_ID = "device_id";
    private static final String K_IID = "iid";
    private static final String K_CDID = "cdid";
    private static final String K_NORMAL_SID = "normal_session_id";
    private static final String K_COLD_SID = "cold_start_session_id";

    /** 与桌面端 device-bundled.json 一致的兜底（服务端已注册过）。 */
    private static final String FALLBACK_DEVICE_ID = "1483706410468393";
    private static final String FALLBACK_IID = "1483706410472489";
    private static final String FALLBACK_CDID = "e6225c85-a5d9-420c-8a4f-44ae89f87f3e";
    private static final String FALLBACK_NORMAL_SID =
            "ebace151-7172-44b2-a697-2d4b41920a2b#1";
    private static final String FALLBACK_COLD_SID =
            "c42e9173-aeb8-487e-bcfc-4b9f840a9ed4";

    private DeviceIdentity() {
    }

    /**
     * 返回 {device_id, iid, cdid, normalSessionId, coldStartSessionId}。
     * 首次调用会把随包身份落到 SharedPreferences，之后一直沿用。
     */
    static String[] load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String[] cached = read(sp);
        if (cached != null) {
            return cached;
        }
        String[] bundled = readAsset(ctx);
        String[] v = bundled != null ? bundled : new String[]{
                FALLBACK_DEVICE_ID, FALLBACK_IID, FALLBACK_CDID,
                FALLBACK_NORMAL_SID, FALLBACK_COLD_SID};
        // 会话 id 缺失时补一个并固定（同一台设备要稳定，不能每次新生成）
        if (v[3] == null || v[3].isEmpty()) {
            v[3] = UUID.randomUUID() + "#1";
        }
        if (v[4] == null || v[4].isEmpty()) {
            v[4] = UUID.randomUUID().toString();
        }
        sp.edit()
                .putString(K_DEVICE_ID, v[0])
                .putString(K_IID, v[1])
                .putString(K_CDID, v[2])
                .putString(K_NORMAL_SID, v[3])
                .putString(K_COLD_SID, v[4])
                .apply();
        Log.i(TAG, "设备身份已固定 iid=" + v[1]);
        return v;
    }

    /** 内容接口要用的 iid / cdid（{iid, cdid}）。 */
    static String[] contentIdentity(Context ctx) {
        String[] v = load(ctx);
        return new String[]{v[1], v[2]};
    }

    private static String[] read(SharedPreferences sp) {
        String dev = sp.getString(K_DEVICE_ID, "");
        String iid = sp.getString(K_IID, "");
        String cdid = sp.getString(K_CDID, "");
        if (dev.isEmpty() || iid.isEmpty() || cdid.isEmpty()) {
            return null;
        }
        return new String[]{dev, iid, cdid,
                sp.getString(K_NORMAL_SID, ""), sp.getString(K_COLD_SID, "")};
    }

    /** 读 assets/device.json；字段缺失返回 null。 */
    private static String[] readAsset(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            JSONObject j = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            String dev = j.optString("device_id", "");
            String iid = j.optString("iid", "");
            String cdid = j.optString("cdid", "");
            if (dev.isEmpty() || iid.isEmpty() || cdid.isEmpty()) {
                return null;
            }
            return new String[]{dev, iid, cdid,
                    j.optString("normal_session_id", ""),
                    j.optString("cold_start_session_id", "")};
        } catch (Exception e) {
            Log.w(TAG, "读取随包设备身份失败，用内置兜底", e);
            return null;
        }
    }
}
