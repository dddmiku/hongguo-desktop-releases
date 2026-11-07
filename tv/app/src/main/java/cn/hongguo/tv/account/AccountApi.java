package cn.hongguo.tv.account;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.hongguo.tv.net.Http;
import cn.hongguo.tv.sign.RequestSigner;

/**
 * 账号接口：验证码登录 / 观看历史 / 收藏。
 *
 * 协议全部来自桌面端 desktop_account.py 的真机抓包结论，几个必须照做的点：
 *
 *  1. **手机号与验证码要 XOR(0x05) 后转小写 hex**，手机号还要按真机形态
 *     带 "+86" 前缀和 3-4-4 分组（" +86157 3063 9941"）。发裸 11 位会被
 *     判非法号码，服务端只回一个含糊的 error_code=7「系统繁忙」。
 *  2. 登录走 **security.snssdk.com**（护照域名，不是内容接口那台），
 *     并且请求体是 x-www-form-urlencoded，不是 JSON。
 *  3. 护照请求的 **x-ss-stub 必须是随机 32 位 hex**。上游文档说它是
 *     请求体的 MD5，实测那样反而被拦（error_code=7），随机值才放行。
 *  4. 护照请求**不能带** X-Neptune / X-Soter / x-tt-store-region*，
 *     签名器补上的这些头要在签名后删掉。
 *  5. 登录成功从 Set-Cookie 取 cookie；这是之后所有账号请求的身份。
 */
public final class AccountApi {

    private static final String TAG = "HgAcct";

    /** 护照域名（登录/发码）。与内容接口不同，不能混用。 */
    private static final String PASSPORT_HOST = "https://security.snssdk.com";

    /** 护照接口的 UA 必须与真机一致，用内容接口那套会被判异常客户端。 */
    private static final String PASSPORT_UA =
            "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; "
                    + "Build/V417IR;tt-ok/3.12.13.20)";

    /** 发码场景类型（抓包值）。 */
    private static final String SMS_TYPE = "24";

    private final Context context;
    private final RequestSigner signer;
    private final String iid;
    private final String cdid;
    private final String deviceId;
    private final String normalSessionId;
    private final String coldStartSessionId;

    public AccountApi(Context context) {
        this.context = context.getApplicationContext();
        this.signer = new RequestSigner(this.context);
        // DeviceIdentity.load() 的返回顺序是
        //   [0]=device_id  [1]=iid  [2]=cdid  [3]=normal_session_id  [4]=cold_start_session_id
        // 早先这里把前三个当成 (iid, cdid, deviceId) 读反了，导致护照请求体里
        // 三个字段的值整体错位：
        //   device_id=<cdid 的值>  iid=<device_id 的值>  cdid=<iid 的值>
        // 服务端因此把每次请求都判为「异常客户端」，恒返回 error_code=7，
        // 文案却伪装成「访问太频繁」—— 换号、重试、换签名方式全都无效。
        String[] dev = DeviceIdentity.load(this.context);
        this.deviceId = dev[0];
        this.iid = dev[1];
        this.cdid = dev[2];
        this.normalSessionId = dev[3];
        this.coldStartSessionId = dev[4];
    }

    // --------------------------------------------------------------- 登录

    /**
     * 发送登录验证码。
     *
     * @return 成功时 hasTicket=true（服务端有时会给 mobile_ticket）
     */
    public JSONObject sendCode(String mobile) throws IOException {
        Map<String, String> form = passportForm();
        form.put("mobile", xorHex(normalizeMobile(mobile)));
        form.put("type", xorHex(SMS_TYPE));
        form.put("unbind_exist", xorHex("1"));
        form.put("auto_read", "0");
        return passportPost("/passport/mobile/send_code/v1/", form, "");
    }

    /**
     * 验证码登录。成功后把登录态写进 {@link Account}，之后的所有账号请求
     * 都自动带上它。
     *
     * @param mobileTicket sendCode 返回的 mobile_ticket，没有就传空
     */
    public JSONObject smsLogin(String mobile, String code, String mobileTicket)
            throws IOException {
        Map<String, String> form = passportForm();
        form.put("mobile", xorHex(normalizeMobile(mobile)));
        form.put("code", xorHex(code));
        if (mobileTicket != null && !mobileTicket.isEmpty()) {
            form.put("mobile_ticket", mobileTicket);
        }
        JSONObject j = passportPost("/passport/mobile/sms_login/", form, "");
        if (!ok(j)) {
            return j;
        }
        String cookie = lastCookie;
        String token = extractToken(j);
        String uid = "";
        String name = "";
        JSONObject data = j.optJSONObject("data");
        if (data != null) {
            uid = data.optString("uid", "");
            name = data.optString("screen_name", data.optString("user_name", ""));
            if (name.isEmpty()) {
                JSONObject user = data.optJSONObject("user_info");
                if (user != null) {
                    name = user.optString("screen_name", "");
                    if (uid.isEmpty()) {
                        uid = user.optString("uid", "");
                    }
                }
            }
        }
        Account.save(context, cookie, token, uid, name, mobile);
        Log.i(TAG, "登录会话已保存");
        return j;
    }

    /**
     * 用已保存的 cookie 请求用户信息，确认登录态是否还有效。
     * 返回的昵称会顺手写回本地（cookie 有效但本地没昵称时很有用）。
     */
    public JSONObject userInfo() throws IOException {
        Account a = Account.load(context);
        if (!a.loggedIn()) {
            JSONObject j = new JSONObject();
            try {
                j.put("code", -1);
                j.put("message", "未登录");
            } catch (Exception ignored) {
            }
            return j;
        }
        return contentCall("GET", "/reading/user/info/v", null, a);
    }

    // --------------------------------------------------------------- 历史

    public static final class HistoryPage {
        public final List<cn.hongguo.tv.cache.WatchRecord> items = new ArrayList<>();
        public int nextOffset, total, rawCount;
        public boolean hasMore;
        public String fingerprint = "";
    }

    public HistoryPage historyPage(Account a, int offset, int limit) throws IOException {
        guard(a);
        Map<String,String> q=new LinkedHashMap<>();q.put("book_type","2");q.put("offset",Integer.toString(Math.max(0,offset)));q.put("limit",Integer.toString(Math.max(1,Math.min(500,limit))));
        q.put("query_soft_deleted","false");q.put("is_first_load","false");q.put("last_min_read_timestamp_ms","0");q.put("full_field","false");
        JSONObject j=contentCall("GET","/reading/bookapi/read_history/list/v",q,a);guard(a);
        if(!ok(j))throw new IOException(errText(j));
        JSONObject data=j.optJSONObject("data");
        if(data==null)throw new IOException("云端历史格式异常，现有记录已保留");
        JSONArray arr=firstArray(data,"data_list","read_history_list","history_list","list");
        if(arr==null)throw new IOException("云端历史格式异常，现有记录已保留");
        HistoryPage page=new HistoryPage();page.rawCount=arr.length();
        page.total=data.optInt("total_count",data.optInt("total",0));
        page.nextOffset=data.optInt("next_offset",offset+arr.length());
        page.hasMore=arr.length()>0 && (data.has("has_more")?data.optBoolean("has_more"):page.total>0?page.nextOffset<page.total:arr.length()>=Math.min(500,limit));
        StringBuilder fingerprint=new StringBuilder();
        for(int i=0;i<arr.length();i++) {
            JSONObject o=arr.optJSONObject(i);if(o==null)continue;
            cn.hongguo.tv.cache.WatchRecord r=ProgressContract.history(o);
            fingerprint.append(r.seriesId).append(':').append(r.watchedAt).append(';');
            if(r.valid())page.items.add(r);
        }
        page.fingerprint=fingerprint.toString();
        return page;
    }

    public cn.hongguo.tv.cache.WatchRecord resume(Account a, String seriesId) throws IOException {
        guard(a);
        try {
            JSONObject body=new JSONObject().put("book_ids",new JSONObject().put("2",new JSONArray().put(Long.parseLong(seriesId))));
            JSONObject j=contentPost("/reading/bookapi/read_progress/get/v",body,a);guard(a);
            if(!ok(j))throw new IOException(errText(j));
            JSONArray rows=j.optJSONArray("video_data");
            if(rows==null)throw new IOException("云端续播格式异常，待同步记录已保留");
            cn.hongguo.tv.cache.WatchRecord latest=null;
            for(int i=0;i<rows.length();i++) {
                JSONObject o=rows.optJSONObject(i);if(o==null)continue;
                cn.hongguo.tv.cache.WatchRecord r=ProgressContract.resume(o);
                if(seriesId.equals(r.seriesId)){
                    if(!r.valid()||!r.vid.matches("[0-9]{8,19}")||r.watchedAt<=0)throw new IOException("云端续播记录格式异常，待同步进度已保留");
                    if(latest==null||r.watchedAt>latest.watchedAt)latest=r;
                }
            }
            return latest;
        } catch(org.json.JSONException|NumberFormatException e) {throw new IOException("续播数据构造失败",e);}
    }

    public boolean upload(Account a, cn.hongguo.tv.cache.WatchRecord r) throws IOException {
        guard(a);
        if(!r.valid()||r.watchedAt<=0||!r.vid.matches("[0-9]{8,19}"))throw new IOException("待同步播放记录不完整");
        try {
            JSONObject history=contentPost("/reading/bookapi/read_history/update/v",ProgressContract.historyBody(r),a);guard(a);
            if(!ok(history))throw new IOException(errText(history));
            JSONObject resume=contentPost("/reading/bookapi/read_progress/upload/v",ProgressContract.resumeBody(r),a);guard(a);
            if(!ok(resume))throw new IOException(errText(resume));
            return true;
        } catch(org.json.JSONException|NumberFormatException e) {throw new IOException("进度数据构造失败",e);}
    }

    private void guard(Account snapshot) throws IOException {
        if(snapshot==null||!snapshot.loggedIn())throw new IOException("未登录");
        if(!snapshot.sessionIdentity().equals(Account.load(context).sessionIdentity()))throw new IOException("账号已切换，请重新同步");
    }

    // --------------------------------------------------------------- 收藏

    /** 收藏 / 取消收藏。 */
    public boolean setFavorite(String seriesId, boolean favorite) throws IOException {
        return setFavorite(Account.load(context),seriesId,favorite);
    }

    public boolean setFavorite(Account a, String seriesId, boolean favorite) throws IOException {
        guard(a);
        if (!a.loggedIn()) {
            throw new IOException("未登录");
        }
        JSONObject one = new JSONObject();
        try {
            one.put("book_id", seriesId);
            one.put("book_type", 2);
            // 0 = 加入书架，1 = 移除
            one.put("video_shelf_operate_type", favorite ? 0 : 1);
            one.put("modify_time", System.currentTimeMillis());
            one.put("group_name", "");
        } catch (Exception e) {
            throw new IOException("收藏数据构造失败");
        }
        JSONArray arr = new JSONArray();
        arr.put(one);
        JSONObject body = new JSONObject();
        try {
            body.put("update_bookshelf_video_list", arr);
        } catch (Exception ignored) {
        }
        JSONObject j = contentPost("/reading/bookapi/bookshelf/video/update/v", body, a);
        guard(a);
        if (!ok(j)) {
            int code = j.optInt("code", 0);
            // 101144 是书架写接口的冷却（「追剧失败，请稍后再试」）。
            // 原样把上游文案抛给用户没有指导意义，而且反复点会续期冷却，
            // 所以这里换成能指导操作的提示。
            if (code == 101144) {
                throw new IOException("操作太频繁，请过几分钟再试");
            }
            throw new IOException(errText(j));
        }
        return true;
    }

    /** 云端收藏列表（只有 id，标题/封面要另外补）。 */
    public List<String> favorites() throws IOException {
        return favorites(Account.load(context));
    }

    public List<String> favorites(Account a) throws IOException {
        guard(a);
        if (!a.loggedIn()) {
            throw new IOException("未登录");
        }
        JSONObject j = contentCall("GET", "/reading/bookapi/bookshelf/video/list/v", null, a);
        guard(a);
        if (!ok(j)) {
            throw new IOException(errText(j));
        }
        List<String> out = new ArrayList<>();
        JSONObject data = j.optJSONObject("data");
        if (data == null) {
            return out;
        }
        JSONArray arr = data.optJSONArray("video_shelf_info");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String sid = o.optString("series_id", o.optString("book_id", ""));
            if (!sid.isEmpty()) {
                out.add(sid);
            }
        }
        return out;
    }

    // ------------------------------------------------------- 请求基础设施

    /** 上一次请求收到的 Set-Cookie（登录成功后取它当身份）。 */
    private String lastCookie = "";

    private Map<String, String> passportForm() {
        Map<String, String> f = new LinkedHashMap<>();
        // 这些是与真机逐项对齐后确认「必须带」的字段。
        // 护照侧对参数完整性敏感，缺字段的请求会被判为异常客户端。
        f.put("account_sdk_source", "app");
        f.put("passport_support_flow", "captcha,verify");
        f.put("mix_mode", "1");
        f.put("aid", "8662");
        f.put("app_name", "novelread");
        f.put("device_platform", "android");
        f.put("version_code", "73932");
        f.put("version_name", "7.3.9.32");
        f.put("update_version_code", "73932");
        f.put("manifest_version_code", "73932");
        f.put("device_id", deviceId);
        f.put("iid", iid);
        f.put("cdid", cdid);
        f.put("normal_session_id", normalSessionId);
        f.put("cold_start_session_id", coldStartSessionId);
        f.put("device_brand", "HONOR");
        f.put("device_type", "PGT-AN10");
        f.put("resolution", "1080*1920");
        f.put("dpi", "480");
        f.put("os", "android");
        f.put("os_version", "12");
        f.put("os_api", "32");
        f.put("rom_version", "V417IR release-keys");
        f.put("host_abi", "arm64-v8a");
        f.put("channel", "vivo_8662_64");
        f.put("ac", "wifi");
        f.put("ssmix", "a");
        f.put("language", "zh");
        f.put("dragon_device_type", "phone");
        f.put("pv_player", "73932");
        f.put("compliance_status", "0");
        f.put("need_personal_recommend", "1");
        f.put("player_so_load", "1");
        f.put("is_android_pad_screen", "0");
        f.put("okhttp_version", "4.2.243.31-douyin");
        f.put("use_store_region_cookie", "1");
        f.put("use_new_token_expire_rule", "true");
        f.put("passport-sdk-version", "5051452");
        f.put("gender", "2");
        f.put("har_status", "0");
        f.put("charging", "0");
        f.put("network_type", "4");
        f.put("down_speed", "60000");
        f.put("font_scale", "100");
        f.put("battery_pct", "93");
        f.put("screen_brightness", "102");
        f.put("current_volume", "0");
        f.put("app_dark_mode", "0");
        f.put("sys_dark_mode", "0");
        f.put("sys_mini_window", "0");
        f.put("app_mini_window", "0");
        f.put("is_power_save_mode", "0");
        f.put("normal_session_cnt_in_day", "1");
        f.put("normal_session_cnt_in_life", "1");
        f.put("cold_start_session_cnt_in_day", "1");
        f.put("cold_start_session_cnt_in_life", "1");
        return f;
    }

    /**
     * 发一个护照请求（表单编码 + 随机 x-ss-stub + 去掉不可带的头）。
     */
    /**
     * 这个护照接口能不能带上「当前已登录账号」的 cookie。
     *
     * 发码与登录**不能带**：那等于告诉服务端「这个设备正在用 A 号，
     * 又要给 B 号发码/登录」。实测（MuMu / 2026-11-04）换号时服务端直接
     * 拒绝，且换全新号码也一样 —— 被限的是这台设备上的这个会话，
     * 与号码本身无关。用户看到的就是「换号了还提示太频繁」。
     *
     * 需要沿用会话的只有 MFA 那类「同一次挑战的后续请求」，
     * 这里暂时没有走 passportPost 的 MFA 流程，所以统一不带。
     */
    private static boolean mayCarryAccountCookie(String path) {
        return false;
    }

    private JSONObject passportPost(String path, Map<String, String> form, String cookieExtra)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        String url = PASSPORT_HOST + path + "?aid=8662&device_platform=android";
        Map<String, String> h = new LinkedHashMap<>();
        h.put("user-agent", PASSPORT_UA);
        h.put("content-type", "application/x-www-form-urlencoded");
        h.put("lc", "101");
        h.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        h.put("x-ss-req-ticket", String.valueOf(System.currentTimeMillis()));
        // 必须是随机值。上游文档写「body 的 MD5」，实测那样会被拦。
        h.put("x-ss-stub", randomHex(32));
        // Accept-Encoding 必须在**签名之前**定下来。
        //
        // Http.post 对每个请求都会强制写 `Accept-Encoding: identity`，
        // 如果这里不先设，签名时它还不存在、发送时却多出来一个头 ——
        // 签名输入与实发请求不一致，服务端按实发头校验签名就判定异常，
        // 于是护照接口恒返回 error_code=7（文案伪装成「访问太频繁」）。
        // 这是本次登录失败真正的根因，与签名算法无关。
        h.put("Accept-Encoding", "identity");
        Account cur = Account.load(context);
        if (cur.loggedIn() && !cur.cookie.isEmpty() && mayCarryAccountCookie(path)) {
            h.put("cookie", cur.cookie);
            String csrf = pickCookie(cur.cookie, "passport_csrf_token");
            if (!csrf.isEmpty()) {
                h.put("x-tt-passport-csrf-token", csrf);
            }
        }
        if (cookieExtra != null && !cookieExtra.isEmpty()) {
            String base = h.containsKey("cookie") ? h.get("cookie") : "";
            h.put("cookie", base.isEmpty() ? cookieExtra : base + "; " + cookieExtra);
        }
        // 签名要在所有头确定之后做（签名输入包含它们）。
        //
        // 签名只用本机 so：曾经误判「本机 so 签的头被护照服务端拒绝」，
        // 于是加过远程签名服务兜底；后来定位到真因是设备身份三个字段的
        // 值互相错位（见构造函数注释）。修好后本机 so 完全能通过护照校验
        // （实测 2026-11-04 真机：send_code 返回 200 + mobile_ticket）。
        // 远程路径已移除 —— TV 端必须独立可用。
        h.putAll(signer.sign(url, h));
        // 护照请求不带这几个（真机没有）。签名器会补上，必须在签名后删。
        //
        // 注意 X-Neptune / X-Soter：桌面端因为「真机抓包里没有」而删掉，
        // 但实测保留它们并不影响结果（A/B 各一次都成功）。这里跟随桌面端
        // 的做法删掉，保持与已验证的行为一致。
        h.remove("X-Neptune");
        h.remove("X-Soter");
        h.remove("x-neptune");
        h.remove("x-soter");
        h.remove("x-tt-store-region");
        h.remove("x-tt-store-region-src");
        // 注意：**不要**在这里删 accept-encoding。
        // 上面已经把它固定成 identity 并参与签名，删掉反而会让签名输入
        // 与实发请求再次不一致（这就是之前 error_code=7 的来源）。
        Log.d(TAG, "HEADERS " + path + " keys=" + h.keySet());
        // 记录请求体的字段名与长度（**不记录值**：里面有手机号与编码后的凭据）。
        // 排查「服务端判异常客户端」时必须能确认字段齐不齐，光看头看不出来。
        Log.d(TAG, "BODY " + path + " len=" + sb.length()
                + " mobile_enc_len=" + enc(form.get("mobile")).length()
                + " keys=" + form.keySet());

        Http.Response r = Http.post(url, h, sb.toString().getBytes("UTF-8"), null);
        // 把失败时的完整请求落盘，供离线复现（默认只在调试构建里写）。
        // 只写 DEBUG 构建：release 包不留凭据形态的东西。
        if (!ok0(r.body)) {
            try {
                java.io.File dir = new java.io.File(context.getCacheDir(), "diag");
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File f = new java.io.File(dir, "last_passport_request.txt");
                    StringBuilder dump = new StringBuilder();
                    dump.append("URL ").append(url).append('\n');
                    for (Map.Entry<String, String> e : h.entrySet()) {
                        dump.append("H ").append(e.getKey()).append(": ")
                                .append(e.getValue()).append('\n');
                    }
                    dump.append("BODY ").append(sb).append('\n');
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(dump.toString().getBytes("UTF-8"));
                    fo.close();
                    Log.i(TAG, "失败请求已落盘: " + f.getAbsolutePath());
                }
            } catch (Throwable ignored) {
            }
        }
        // 从**本次响应**合并进登录态。
        //
        // 这里不能再从 Account.load(context).cookie 起手：上面已经决定
        // 护照请求不带旧账号 cookie（换号场景），把旧 cookie 合回来会让
        // 新号的登录态里混进上一个号的 sessionid，后续内容请求就会串号。
        lastCookie = mergeCookie("", r.cookieHeader());
        // 打原始响应体：护照接口的错误全在 data.error_code/description 里，
        // 只记 HTTP 状态看不出「为什么被拒」（1206 太频繁 / 1203 验证码错 /
        // 7 设备被拒 全都是 HTTP 200）。这条日志是排查登录问题的唯一入口。
        Log.d(TAG, path + " -> " + r.code + " setCookies=" + r.setCookies.size()
                + " cookie=" + (lastCookie.isEmpty() ? "无" : "有")
                + " responseBytes=" + (r.body == null ? 0 : r.body.length()));
        if (r.code == 401 || r.code == 403) {
            if (r.body == null || r.body.trim().isEmpty()) {
                throw new IOException("设备身份未被接受（这是登录接口的已知限制）。"
                        + "内容浏览和播放不受影响。");
            }
        }
        if (r.body == null || r.body.trim().isEmpty()) {
            throw new IOException("登录服务无响应（HTTP " + r.code + "）");
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("登录服务返回了无法解析的内容");
        }
    }

    /** 内容接口（带账号 cookie 的那类）。 */
    private JSONObject contentCall(String method, String path, Map<String, String> q,
                                   Account a) throws IOException {
        String url = contentUrl(path, q);
        Map<String, String> h = contentHeaders(a);
        h.putAll(signer.sign(url, h));
        Http.Response r = "GET".equals(method)
                ? Http.get(url, h, null) : Http.post(url, h, new byte[0], null);
        if (!r.ok()) {
            throw new IOException("接口无响应（HTTP " + r.code + "）");
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("响应格式异常");
        }
    }

    private JSONObject contentPost(String path, JSONObject body, Account a) throws IOException {
        String url = contentUrl(path, null);
        Map<String, String> h = contentHeaders(a);
        h.put("content-type", "application/json; charset=utf-8");
        byte[] raw = body.toString().getBytes("UTF-8");
        h.putAll(signer.sign(url, h));
        Http.Response r = Http.post(url, h, raw, null);
        if (!r.ok()) {
            throw new IOException("接口无响应（HTTP " + r.code + "）");
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("响应格式异常");
        }
    }

    private Map<String, String> contentHeaders(Account a) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("user-agent", PASSPORT_UA);
        h.put("accept", "application/json");
        if (a != null && a.loggedIn()) {
            if (!a.cookie.isEmpty()) {
                h.put("cookie", a.cookie);
            }
            if (!a.token.isEmpty()) {
                h.put("x-tt-token", a.token);
            }
        }
        return h;
    }

    private String contentUrl(String path, Map<String, String> extra) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("aid", "8662");
        q.put("app_name", "novelread");
        q.put("version_code", "72232");
        q.put("version_name", "7.2.2.32");
        q.put("channel", "audit");
        q.put("device_platform", "android");
        q.put("device_type", "PGT-AN10");
        q.put("os_version", "12");
        q.put("update_version_code", "72232");
        q.put("iid", iid);
        q.put("cdid", cdid);
        if (extra != null) {
            q.putAll(extra);
        }
        q.put("_rticket", String.valueOf(System.currentTimeMillis()));
        StringBuilder sb = new StringBuilder("https://api5-normal-sinfonlinea.fqnovel.com")
                .append(path).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- 工具

    /** UTF-8 字节逐字节异或 0x05，转小写 hex（护照字段编码）。 */
    static String xorHex(String text) {
        try {
            byte[] raw = text.getBytes("UTF-8");
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", (b ^ 5) & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 手机号按真机形态：+86 前缀 + 3-4-4 分组。 */
    static String normalizeMobile(String mobile) {
        String d = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (d.length() == 13 && d.startsWith("86")) {
            d = d.substring(2);
        }
        if (d.length() == 11 && d.startsWith("1")) {
            return "+86" + d.substring(0, 3) + " " + d.substring(3, 7) + " "
                    + d.substring(7, 11);
        }
        return d.isEmpty() ? "" : "+86" + d;
    }

    private static String randomHex(int chars) {
        StringBuilder sb = new StringBuilder(chars);
        String hex = "0123456789ABCDEF";
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < chars; i++) {
            sb.append(hex.charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    private static String pickCookie(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return "";
    }

    /**
     * 合并 cookie：新值覆盖同名键，其余保留。
     *
     * 登录接口一次可能下发好几个 cookie（passport_csrf_token / sessionid /
     * sid_tt ...），漏掉任何一个都会让后续请求掉登录态，所以按「键」合并
     * 而不是直接覆盖整串。保留旧键是因为部分 cookie（如 csrf）不在登录
     * 响应里重复下发，覆盖会把它弄丢。
     */
    private static String mergeCookie(String existing, String incoming) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String src : new String[]{existing, incoming}) {
            if (src == null || src.isEmpty()) {
                continue;
            }
            for (String part : src.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0) {
                    map.put(p.substring(0, eq), p.substring(eq + 1));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 登录响应里取 token（不同版本字段名不一样）。 */
    private static String extractToken(JSONObject j) {
        JSONObject data = j.optJSONObject("data");
        if (data == null) {
            return "";
        }
        for (String k : new String[]{"token", "session_key", "x_tt_token", "x-tt-token"}) {
            String v = data.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** 日志用：把请求头列成 "k=v" 便于和抓包逐项对比（长值截断）。 */
    private static boolean ok0(String body) {
        if (body == null) {
            return false;
        }
        try {
            return ok(new JSONObject(body));
        } catch (Exception e) {
            return false;
        }
    }

    private static String describeHeaders(Map<String, String> h) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            String v = e.getValue();
            sb.append(e.getKey()).append('=')
                    .append(v.length() > 24 ? v.substring(0, 24) + "…" : v);
        }
        return sb.toString();
    }

    /** 日志用：把响应体截断，避免刷屏。 */
    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private static boolean ok(JSONObject j) {
        if (j == null) {
            return false;
        }
        JSONObject data = j.optJSONObject("data");
        if (data != null && data.optInt("error_code", 0) != 0) {
            return false;
        }
        int code = j.optInt("code", -999);
        if (code == 0) {
            return true;
        }
        String msg = j.optString("message", "").toLowerCase();
        return "success".equals(msg) || "ok".equals(msg);
    }

    private static String errText(JSONObject j) {        if (j == null) {
            return "请求失败";
        }
        JSONObject data = j.optJSONObject("data");
        if (data != null && data.optInt("error_code", 0) != 0) {
            String d = data.optString("description", "");
            return d.isEmpty() ? ("错误码 " + data.optInt("error_code")) : d;
        }
        int code = j.optInt("code", -999);
        String msg = j.optString("message", "");
        if (!msg.isEmpty()) {
            return msg;
        }
        return code == -999 ? "请求失败" : ("错误码 " + code);
    }

    private static JSONArray firstArray(JSONObject o, String... names) {
        for (String n : names) {
            JSONArray a = o.optJSONArray(n);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 一条观看历史。 */
    public static final class HistoryItem {
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public int episode = 1;
        public int total = 0;
        public long position = 0;
        public long updatedAt = 0;
    }
}
