package cn.hongguo.tv.account;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 红果账号登录态。
 *
 * 存的就是红果自己下发的 cookie + token（登录成功后 Set-Cookie 里的那一串）。
 * 内容接口在未登录时也能用（搜索/播放都不需要登录），所以登录是可选的：
 * 只有收藏、历史同步这些「属于某个账号」的功能才依赖它。
 *
 * 为什么不加密存储：这与桌面端一致（desktop-account.json 也是明文）。
 * 威胁模型是「拿到设备的人」—— 那种情况下应用私有目录本来就已经被攻破，
 * 再套一层本地密钥只是把钥匙放在同一把锁上，README 里已说明。
 */
public final class Account {

    private static final String PREFS = "hg_account";
    private static final String K_COOKIE = "cookie";
    private static final String K_TOKEN = "token";
    private static final String K_UID = "uid";
    private static final String K_NAME = "user_name";
    private static final String K_MOBILE = "mobile";
    private static final String K_SAVED_AT = "saved_at";

    public final String cookie;
    public final String token;
    public final String uid;
    public final String userName;
    public final String mobile;

    private Account(String cookie, String token, String uid, String userName, String mobile) {
        this.cookie = cookie == null ? "" : cookie;
        this.token = token == null ? "" : token;
        this.uid = uid == null ? "" : uid;
        this.userName = userName == null ? "" : userName;
        this.mobile = mobile == null ? "" : mobile;
    }

    public static final Account EMPTY = new Account("", "", "", "", "");

    /** 是否已登录。判据与桌面端一致：有 cookie 或 token 之一。 */
    public boolean loggedIn() {
        return !cookie.isEmpty() || !token.isEmpty();
    }

    /** 昵称；没有就用手机号掩码兜底，避免界面出现空白。 */
    public String displayName() {
        if (!userName.isEmpty()) {
            return userName;
        }
        String m = maskMobile(mobile);
        return m.isEmpty() ? "已登录" : m;
    }

    public String libraryKey() {
        if (!loggedIn()) return "local";
        if (!uid.isEmpty()) return uid;
        return "session-" + sessionIdentity();
    }

    public String sessionIdentity() {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((uid + "\n" + cookie + "\n" + token).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String maskMobile(String mobile) {
        String d = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (d.length() < 7) {
            return d;
        }
        return d.substring(0, 3) + "****" + d.substring(d.length() - 4);
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Account load(Context ctx) {
        SharedPreferences p = sp(ctx);
        return new Account(
                p.getString(K_COOKIE, ""),
                p.getString(K_TOKEN, ""),
                p.getString(K_UID, ""),
                p.getString(K_NAME, ""),
                p.getString(K_MOBILE, ""));
    }

    public static void save(Context ctx, String cookie, String token, String uid,
                           String userName, String mobile) {
        sp(ctx).edit()
                .putString(K_COOKIE, cookie == null ? "" : cookie)
                .putString(K_TOKEN, token == null ? "" : token)
                .putString(K_UID, uid == null ? "" : uid)
                .putString(K_NAME, userName == null ? "" : userName)
                .putString(K_MOBILE, mobile == null ? "" : mobile)
                .putLong(K_SAVED_AT, System.currentTimeMillis())
                .apply();
    }

    public static void clear(Context ctx) {
        sp(ctx).edit().clear().apply();
    }
}
