package cn.hongguo.tv.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cn.hongguo.tv.net.Http;
import cn.hongguo.tv.sign.RequestSigner;

/**
 * 内容接口客户端。
 *
 * 关键事实（2026-10-20 实测确认）：
 *   1. video_model / search 这两个接口**必须签名**，免签返回空 body。
 *   2. video_model 不带 need_all_video_definition=1 时只返回 1 档
 *      （恒为 720p bytevc2，本地解不了 bv2C 盒 → 整集播不了）。
 *      带上才返回全部档位，其中 1080p 是 bytevc1(H.265)，可正常解码。
 *   3. 搜索接口要求 iid（且不能同时带 device_id），否则 100103 PARAM_INVALID。
 */
public final class Api {

    private static final String TAG = "HgApi";
    private static final String HOST = "https://api5-normal-sinfonlinea.fqnovel.com";
    private static final String PREFS = "hg_device";
    private static final String K_IID = "iid";
    private static final String K_CDID = "cdid";

    /** 兜底身份：随包的已注册设备（来自桌面端 device-bundled.json）。 */
    private static final String DEV_IID = "1483706410472489";
    private static final String DEV_CDID = "e6225c85-a5d9-420c-8a4f-44ae89f87f3e";
    private static final String DEV_DEVICE_ID = "1483706410468393";

    /** 设备身份配置文件（assets 内 JSON，可随 APK 更新替换而不改代码）。 */
    private static final String ASSET_DEVICE = "device.json";

    /** 与 PC 端一致的 UA：服务端会校验 UA 里的机型与 device_type 自洽。 */
    private static final String UA =
            "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; "
                    + "Build/V417IR;tt-ok/3.12.13.20)";

    private final RequestSigner signer;
    private final Http.Signer signerAdapter;
    private final String iid;
    private final String cdid;

    public Api(Context context) {
        this.signer = new RequestSigner(context);
        this.signerAdapter = this::signInto;
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String iid = sp.getString(K_IID, null);
        String cdid = sp.getString(K_CDID, null);
        if (iid == null || cdid == null) {
            // 设备身份必须用「已在服务端注册过」的那一组。
            //
            // 实测（MuMu / 2026-11-03）：随机 16 位 iid 会让 search 恒返回
            // 100103 PARAM_INVALID，同时 video_model 即使带
            // need_all_video_definition=1 也只回单档 360p（video_list 为空）。
            // 换成已注册身份后 search code=0（7 个 tab）、取流回 5 档。
            //
            // 身份从 assets/device.json 读，缺失才用内置常量兜底。
            String[] dev = readBundledDevice(context);
            iid = dev != null ? dev[0] : DEV_IID;
            cdid = dev != null ? dev[1] : DEV_CDID;
            sp.edit().putString(K_IID, iid).putString(K_CDID, cdid).apply();
        }
        this.iid = iid;
        this.cdid = cdid;
    }

    /** 读 assets/device.json，返回 {iid, cdid}；读不到或字段缺失返回 null。 */
    private static String[] readBundledDevice(Context ctx) {
        try (java.io.InputStream in = ctx.getAssets().open(ASSET_DEVICE)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 16384) {
                    return null;
                }
            }
            org.json.JSONObject j = new org.json.JSONObject(
                    new String(out.toByteArray(), "UTF-8"));
            String iid = j.optString("iid", "");
            String cdid = j.optString("cdid", "");
            if (iid.isEmpty() || cdid.isEmpty()) {
                return null;
            }
            return new String[]{iid, cdid};
        } catch (Exception e) {
            Log.w(TAG, "读取随包设备身份失败，用内置兜底", e);
            return null;
        }
    }

    private void signInto(String url, Map<String, String> headers) throws IOException {
        headers.putAll(signer.sign(url, headers));
    }

    private static String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('0' + (int) (Math.random() * 10)));
        }
        return sb.toString();
    }

    /** 基础 query 参数。iid 必带（搜索/取流都依赖它），device_id 刻意不带。 */
    private Map<String, String> baseQuery() {
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
        return q;
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("user-agent", UA);
        h.put("accept", "application/json");
        if (json) {
            h.put("content-type", "application/json; charset=utf-8");
        }
        return h;
    }

    private String url(String path, Map<String, String> extra) {
        Map<String, String> q = baseQuery();
        if (extra != null) {
            q.putAll(extra);
        }
        q.put("_rticket", String.valueOf(System.currentTimeMillis()));
        StringBuilder sb = new StringBuilder(HOST).append(path).append('?');
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

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private JSONObject get(String path, Map<String, String> extra) throws IOException {
        Http.Response r = Http.get(url(path, extra), headers(false), signerAdapter);
        if (!r.ok()) {
            throw new IOException("接口无响应（HTTP " + r.code + "）");
        }
        // 诊断用：上游异常时 body 往往是简短错误 JSON，打出来才能判断是
        // 参数问题还是签名问题（签名错时 body 为空，参数错时带 message）。
        Log.d(TAG, path + " raw=" + (r.body == null ? "null"
                : r.body.length() > 600 ? r.body.substring(0, 600) + "..." : r.body));
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("响应格式异常");
        }
    }

    private JSONObject post(String path, String jsonBody) throws IOException {
        Http.Response r = Http.post(url(path, null), headers(true),
                jsonBody.getBytes("UTF-8"), signerAdapter);
        if (!r.ok()) {
            throw new IOException("接口无响应（HTTP " + r.code + "）");
        }
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new IOException("响应格式异常");
        }
    }

    /** 首页推荐流（列表类接口，理论上可免签，这里仍走签名保持一致性）。 */
    public JSONObject feed() throws IOException {
        return get("/reading/bookapi/bookmall/tab/v", null);
    }

    /**
     * 首页推荐流（分页）。
     *
     * 实测（桌面端逐字段确认）：一页固定 6 条，stream_count 调多大都一样；
     * 游标是 tab_item[0].next_offset，不是 cell_data[0].next_offset（后者恒为 6）。
     * 不带 session_id 直接给 offset>0 会报 101116 SERVICE_ERROR，
     * 所以必须把上次返回的 session 带回来。
     *
     * @param session 上次返回的 session，首次传 ""
     * @param offset  上次返回的 offset，首次传 0
     */
    public FeedPage feed(String session, int offset) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tab_type", "26");
        q.put("tab_index", "0");
        q.put("offset", String.valueOf(Math.max(0, offset)));
        q.put("stream_count", "20");
        q.put("client_req_type", "2");
        q.put("client_template", "2");
        q.put("screen_width_px", "1350");
        if (session != null && !session.isEmpty()) {
            q.put("session_id", session);
        }
        JSONObject j = get("/reading/bookapi/bookmall/tab/v", q);

        FeedPage p = new FeedPage();
        p.session = session == null ? "" : session;
        p.offset = offset;
        JSONObject data = j.optJSONObject("data");
        JSONArray tabs = data == null ? null : data.optJSONArray("tab_item");
        JSONObject tab = tabs != null && tabs.length() > 0 ? tabs.optJSONObject(0) : null;
        if (tab == null) {
            return p;
        }
        // 游标在 tab 上，不在 cell 上
        p.session = tab.optString("session_id", p.session);
        p.offset = tab.optInt("next_offset", offset + 1);
        p.hasMore = tab.optBoolean("has_more", false);

        // 条目在 tab.cell_data[0].cell_data[*].video_data[*]
        JSONArray cells = tab.optJSONArray("cell_data");
        JSONObject first = cells != null && cells.length() > 0 ? cells.optJSONObject(0) : null;
        JSONArray inner = first == null ? null : first.optJSONArray("cell_data");
        if (inner != null) {
            for (int i = 0; i < inner.length(); i++) {
                JSONObject c = inner.optJSONObject(i);
                JSONArray vids = c == null ? null : c.optJSONArray("video_data");
                if (vids == null) {
                    continue;
                }
                for (int k = 0; k < vids.length(); k++) {
                    Series s = Series.from(vids.optJSONObject(k));
                    if (s != null) {
                        p.items.add(s);
                    }
                }
            }
        }
        return p;
    }

    /**
     * 榜单。
     *
     * board 取值：recommend / hot / new（对应上游的 sub_selected_items）。
     * 榜单接口和推荐流是两个不同端点：这个走 bookmall/cell/change/v。
     */
    public List<Series> rank(String board, int limit) throws IOException {
        Map<String, String> subs = new LinkedHashMap<>();
        subs.put("recommend", "comic_series_hot_rank");
        subs.put("hot", "comic_series_hot_play");
        subs.put("new", "comic_series_new_rank");
        String sub = subs.containsKey(board) ? subs.get(board) : board;

        List<Series> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int offset = 0;
        String sess = UUID.randomUUID().toString();
        // 榜单同样要翻页；上限 6 页兜底，避免上游异常时死循环
        for (int page = 0; page < 6 && out.size() < limit; page++) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("cell_id", COMIC_RANK_CELL);
            q.put("tab_type", "26");
            q.put("client_req_type", "2");
            q.put("client_template", "2");
            q.put("screen_width_px", "1350");
            q.put("selected_items", "comic_series_rank");
            q.put("sub_selected_items", sub);
            q.put("session_uuid", sess);
            if (offset > 0) {
                q.put("offset", String.valueOf(offset));
            }
            JSONObject j = get("/reading/bookapi/bookmall/cell/change/v", q);
            JSONObject data = j.optJSONObject("data");
            JSONObject cv = data == null ? null : data.optJSONObject("cell_view");
            JSONArray cells = cv == null ? null : cv.optJSONArray("cell_data");
            if (cells == null || cells.length() == 0) {
                break;
            }
            for (int i = 0; i < cells.length(); i++) {
                JSONObject item = cells.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Object raw = item.opt("video_data");
                JSONObject v = null;
                if (raw instanceof JSONArray) {
                    JSONArray arr = (JSONArray) raw;
                    v = arr.length() > 0 ? arr.optJSONObject(0) : null;
                } else if (raw instanceof JSONObject) {
                    v = (JSONObject) raw;
                }
                Series s = Series.from(v);
                if (s != null && seen.add(s.seriesId)) {
                    s.rank = out.size() + 1;
                    out.add(s);
                }
            }
            if (!cv.optBoolean("has_more", false)) {
                break;
            }
            offset = cv.optInt("next_offset", offset + cells.length());
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** 榜单 cell id（上游固定值）。 */
    private static final String COMIC_RANK_CELL = "7470092475068071998";

    /** 体裁 → 上游 req_scene / genre 值。 */
    public static final String[][] GENRES = {
            {"短剧", "default", "short_play"},
            {"漫剧", "comic_series", "comic_series"},
            {"AI短剧", "ai_series", "ai_series"},
    };

    /**
     * 取某体裁的实时筛选面板，返回主题列表 [[名称, id], ...]。
     *
     * 为什么必须动态取：
     *   主题 id（cate_xxx）是上游分配的，会随运营调整变化。
     *   硬编码一套 id 在短期内能用，但上游一改就全失效 ——
     *   实测硬编码的「玄幻 cate_7」在当前版本返回空列表，
     *   而面板里真实存在的主题是另一批 id。
     *
     * @param genreIndex GENRES 下标
     * @return 主题列表，首项固定是「全部」；失败时返回只含「全部」的列表
     */
    public List<String[]> themes(int genreIndex) throws IOException {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"全部", ""});
        String[] g = GENRES[Math.max(0, Math.min(genreIndex, GENRES.length - 1))];
        try {
            JSONObject sel = new JSONObject();
            sel.put("category_dim_epoch", new JSONArray());
            sel.put("online_time", new JSONArray());
            sel.put("gender", new JSONArray());
            sel.put("category_dim_role", new JSONArray());
            sel.put("genre", new JSONArray().put(g[2]));
            sel.put("sort", new JSONArray());
            sel.put("category_dim_theme", new JSONArray());

            JSONObject body = new JSONObject();
            body.put("filter_ids", "");
            body.put("req_scene", g[1]);
            body.put("offset", 0);
            body.put("limit", 1);
            body.put("need_selector_panel", true);
            body.put("req_type", "default");
            body.put("client_req_type", 3);
            body.put("select_items", sel);
            body.put("session_id", "");

            JSONObject j = post("/reading/distribution/category/landpage/v", body.toString());
            JSONObject data = j.optJSONObject("data");
            JSONArray rows = data == null ? null : data.optJSONArray("selector_rows");
            Log.d(TAG, "themes: selector_rows=" + (rows == null ? "null" : rows.length()));
            if (rows == null) {
                return out;
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) {
                    continue;
                }
                String type = row.optString("type");
                String rn = row.optString("row_name");
                JSONArray items = row.optJSONArray("items");
                Log.d(TAG, "  row[" + i + "] type=" + type + " name=" + rn
                        + " items=" + (items == null ? 0 : items.length()));
                // 只取主题那一行
                if (!"category_dim_theme".equals(type)) {
                    continue;
                }
                if (items == null) {
                    continue;
                }
                for (int k = 0; k < items.length() && out.size() < 24; k++) {
                    JSONObject it = items.optJSONObject(k);
                    if (it == null) {
                        continue;
                    }
                    String name = it.optString("show_name", "");
                    String id = it.optString("selector_item_id", "");
                    if (!name.isEmpty() && !id.isEmpty()) {
                        out.add(new String[]{name, id});
                    }
                }
                break;
            }
        } catch (org.json.JSONException e) {
            throw new IOException("筛选面板解析失败", e);
        }
        return out;
    }

    /**
     * 分类浏览（首页内容流的主力接口）。
     *
     * 比推荐流强的地方：能按体裁/主题/设定/背景/排序/受众/时间筛选，
     * 返回里带分类标签、评分、播放量、上线时间，能做出「按题材找片」的体验。
     *
     * @param genreIndex GENRES 的下标
     * @param theme      主题 id（如 cate_755），空表示不限
     * @param sort       online_time / hot_score / hot_collect
     * @param offset     偏移
     * @param limit      一页条数（上游一般最多 18）
     */
    public List<Series> browse(int genreIndex, String theme, String sort,
                               int offset, int limit) throws IOException {
        String[] g = GENRES[Math.max(0, Math.min(genreIndex, GENRES.length - 1))];
        try {
            JSONObject sel = new JSONObject();
            sel.put("category_dim_epoch", new JSONArray());
            sel.put("online_time", new JSONArray());
            sel.put("gender", new JSONArray());
            sel.put("category_dim_role", new JSONArray());
            sel.put("genre", new JSONArray().put(g[2]));
            sel.put("sort", new JSONArray().put(sort == null ? "online_time" : sort));
            sel.put("category_dim_theme",
                    theme == null || theme.isEmpty()
                            ? new JSONArray() : new JSONArray().put(theme));

            JSONObject body = new JSONObject();
            body.put("filter_ids", "");
            body.put("req_scene", g[1]);
            body.put("offset", offset);
            body.put("need_selector_panel", false);
            body.put("limit", limit);
            body.put("select_items", sel);
            body.put("session_id", "");
            body.put("req_type", "only_content");
            body.put("client_req_type", 3);

            JSONObject j = post("/reading/distribution/category/landpage/v", body.toString());
            List<Series> out = new ArrayList<>();
            JSONObject data = j.optJSONObject("data");
            JSONArray items = data == null ? null : data.optJSONArray("video_data");
            if (items == null) {
                return out;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                Series s = Series.from(o);
                if (s == null) {
                    continue;
                }
                // 分类标签藏在 category_schema（JSON 字符串）里
                s.category = categoriesOf(o);
                s.premiere = tagText(o);
                if (s.episodeCount == 0) {
                    s.episodeCount = o.optInt("episode_cnt", 0);
                }
                out.add(s);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("请求构造失败", e);
        }
    }

    /** 从 category_schema 里抽分类名（形如 [{"name":"玄幻"},...]）。 */
    private static String categoriesOf(JSONObject o) {
        String raw = o.optString("category_schema", "");
        if (raw.isEmpty()) {
            // 兜底：sub_title_list 里的纯文本标签
            JSONArray subs = o.optJSONArray("sub_title_list");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject s = subs.optJSONObject(i);
                    String c = s == null ? "" : s.optString("content", "");
                    if (!c.isEmpty() && !c.contains("万") && !c.endsWith("集")
                            && !c.contains("播放") && !c.equals("今日上新")) {
                        return c;
                    }
                }
            }
            return "";
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(raw);
        while (m.find() && names.size() < 3) {
            names.add(m.group(1));
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(n);
        }
        return sb.toString();
    }

    private static String tagText(JSONObject o) {
        JSONObject tag = o.optJSONObject("tag_info");
        return tag == null ? "" : tag.optString("text", "");
    }

    /** 一页推荐流。 */
    public static final class FeedPage {
        public String session = "";
        public int offset;
        public boolean hasMore;
        public final List<Series> items = new ArrayList<>();
    }

    /** 剧集条目（推荐流 / 榜单 / 搜索 / 分类浏览共用）。 */
    public static final class Series {
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public String intro = "";
        public int episodeCount;
        public int rank;
        public String hot = "";
        /** 分类标签，如「玄幻 · 逆袭」。 */
        public String category = "";
        /** 角标文字，如「今日上新」。 */
        public String premiere = "";

        /** 从上游条目构造；没有 series_id 的返回 null（取不了流）。 */
        public static Series from(JSONObject o) {
            if (o == null) {
                return null;
            }
            String sid = o.optString("series_id", "");
            if (sid.isEmpty()) {
                sid = o.optString("book_id", "");
            }
            String t = o.optString("title", "");
            if (t.isEmpty()) {
                t = o.optString("series_title", "");
            }
            if (sid.isEmpty() || t.isEmpty()) {
                return null;
            }
            Series s = new Series();
            s.seriesId = sid;
            s.title = t;
            s.cover = o.optString("cover", "");
            s.intro = o.optString("video_desc", "");
            if (s.intro.isEmpty()) {
                s.intro = o.optString("series_intro", "");
            }
            s.episodeCount = o.optInt("episode_cnt", 0);
            s.hot = o.optString("rec_text", "");
            return s;
        }
    }

    /** 搜索。实测必须带已注册的 iid，否则 100103 PARAM_INVALID。 */
    public JSONObject search(String query) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("query", query);
        q.put("tab_name", "feed");
        q.put("search_source", "1");
        q.put("offset", "0");
        q.put("count", "0");
        q.put("use_correct", "true");
        return get("/reading/bookapi/search/tab/v", q);
    }
    /**
     * 剧集列表。
     *
     * 接口是 POST /novel/player/multi_video_detail/v1/，不是 episodes/v1/
     * （后者 404）。biz_param 的字段与桌面端一致，少一个都可能拿不到 video_list。
     */
    public JSONObject episodes(String seriesId) throws IOException {
        return post("/novel/player/multi_video_detail/v1/", episodesBody(seriesId));
    }

    /**
     * 构造 multi_video_detail 的请求体。
     *
     * series_id 支持**英文逗号拼接批量**：一次最多 20 个，30 个会被判
     * PARAM_INVALID（与桌面端 get_episodes_batch 的实测结论一致）。
     */
    private static String episodesBody(String seriesIds) throws IOException {
        org.json.JSONObject biz = new org.json.JSONObject();
        try {
            biz.put("detail_page_version", 0);
            biz.put("disable_digg_stat", false);
            biz.put("disable_video_relate_book", false);
            biz.put("need_all_video_definition", false);
            biz.put("need_mp4_align", false);
            biz.put("screen_width_px", "900");
            biz.put("source", 7);
            biz.put("use_os_player", false);
            biz.put("use_server_dns", false);
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("biz_param", biz);
            body.put("series_id", seriesIds);
            return body.toString();
        } catch (org.json.JSONException e) {
            throw new IOException("请求构造失败", e);
        }
    }

    /** 一批最多拼多少个 series_id。30 个会被上游判参数错误。 */
    private static final int META_BATCH = 20;

    /** 剧集元数据（收藏列表补标题/封面用）。 */
    public static final class SeriesMeta {
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public int episodeCount;
    }

    /**
     * 批量取剧集元数据：标题 / 封面 / 集数。
     *
     * 为什么需要：云端书架接口（bookshelf/video/list/v）**只回 series_id**，
     * 没有标题和封面。不补齐的话收藏页只能显示一串数字。
     *
     * 取不到的系列不出现在返回 map 里（上游对部分 id 会回空 video_data），
     * 调用方按「没有元数据」处理即可，不影响其它条目。
     */
    public Map<String, SeriesMeta> seriesMeta(List<String> seriesIds) throws IOException {
        Map<String, SeriesMeta> out = new LinkedHashMap<>();
        if (seriesIds == null || seriesIds.isEmpty()) {
            return out;
        }
        // 去重 + 过滤明显非法 id，避免一个坏 id 让整批失败
        List<String> ids = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : seriesIds) {
            if (s == null || !s.matches("[0-9]{8,24}") || !seen.add(s)) {
                continue;
            }
            ids.add(s);
        }
        for (int from = 0; from < ids.size(); from += META_BATCH) {
            List<String> batch = ids.subList(from, Math.min(from + META_BATCH, ids.size()));
            JSONObject j;
            try {
                j = post("/novel/player/multi_video_detail/v1/", episodesBody(join(batch)));
            } catch (IOException e) {
                Log.w(TAG, "批量取元数据失败（跳过这一批）: " + e.getMessage());
                continue;
            }
            JSONObject data = j.optJSONObject("data");
            if (data == null) {
                continue;
            }
            for (String sid : batch) {
                JSONObject node = data.optJSONObject(sid);
                if (node == null) {
                    continue;
                }
                JSONObject vd = node.optJSONObject("video_data");
                if (vd == null) {
                    vd = node;
                }
                SeriesMeta m = new SeriesMeta();
                m.seriesId = sid;
                m.title = vd.optString("series_title", "");
                m.cover = vd.optString("series_cover", "");
                m.episodeCount = vd.optInt("episode_cnt", 0);
                // 系列封面偶尔为空，退到第一集的封面（竖版海报）
                if (m.cover.isEmpty()) {
                    JSONArray vl = vd.optJSONArray("video_list");
                    if (vl != null) {
                        for (int i = 0; i < vl.length(); i++) {
                            JSONObject e = vl.optJSONObject(i);
                            String c = e == null ? "" : e.optString("episode_cover",
                                    e.optString("cover", ""));
                            if (!c.isEmpty()) {
                                m.cover = c;
                                break;
                            }
                        }
                    }
                }
                if (!m.title.isEmpty() || !m.cover.isEmpty()) {
                    out.put(sid, m);
                }
            }
        }
        return out;
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String s : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 解析集列表，返回 [{vid, num, title}, ...]，按集号升序。
     *
     * 响应结构（实测 2026-11-03）：
     *   data[series_id].video_data.video_list[] → {vid, vid_index, title}
     * 注意是 data 以 **series_id 为键**再套 video_data，不是 data.video_list。
     * 拿不到 vid 的条目直接丢掉（没有 vid 就取不了流）。
     */
    public static JSONArray parseEpisodes(JSONObject resp, String seriesId) {
        JSONArray out = new JSONArray();
        JSONObject data = resp == null ? null : resp.optJSONObject("data");
        if (data == null) {
            return out;
        }
        JSONObject node = data.optJSONObject(seriesId);
        if (node == null && data.length() > 0) {
            // 兼容：偶尔键不是 series_id 本身
            java.util.Iterator<String> it = data.keys();
            if (it.hasNext()) {
                node = data.optJSONObject(it.next());
            }
        }
        if (node == null) {
            return out;
        }
        JSONObject vd = node.optJSONObject("video_data");
        if (vd == null) {
            vd = node;
        }
        JSONArray list = vd.optJSONArray("video_list");
        if (list == null) {
            return out;
        }
        java.util.List<JSONObject> tmp = new java.util.ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String vid = o.optString("vid", "");
            if (vid.isEmpty()) {
                vid = o.optString("video_id", "");
            }
            if (vid.isEmpty()) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("vid", vid);
                item.put("num", o.optInt("vid_index", i + 1));
                item.put("title", o.optString("title", ""));
                tmp.add(item);
            } catch (Exception ignored) {
            }
        }
        // 按集号排序：上游返回顺序不保证
        java.util.Collections.sort(tmp, (a, b) ->
                Integer.compare(a.optInt("num"), b.optInt("num")));
        for (JSONObject o : tmp) {
            out.put(o);
        }
        return out;
    }

    /**
     * 取流。**必须带 need_all_video_definition=1**，否则只回一档 360p。
     *
     * 实测（MuMu / 2026-11-03）：参数生效的前提是 iid 为服务端注册过的值。
     * 用随机 iid 时同一个请求返回 video_list=[] 且 auto_definition=360p，
     * 换成已注册身份后立刻回 5 档（360p~1080p，全 bytevc1）。
     */
    public JSONObject videoModel(String vid) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("video_id", vid);
        q.put("need_all_video_definition", "1");
        JSONObject j = get("/reading/bookapi/video_tab/video_model/v1/", q);
        Log.d(TAG, "videoModel " + vid + " code=" + j.optInt("code")
                + " tracks=" + trackCount(j));
        if (trackCount(j) >= 2) {
            return j;
        }
        // 只回一档时试批量接口：它对 need_all_video_definition 的处理路径不同，
        // 单档接口被降级时批量接口有时仍给全档位。
        JSONObject alt = multiVideoModel(vid);
        return trackCount(alt) > trackCount(j) ? alt : j;
    }

    /** 批量取流接口。biz_param 的字段与桌面端一致，少一个都可能只回单档。 */
    private JSONObject multiVideoModel(String vid) throws IOException {
        org.json.JSONObject biz = new org.json.JSONObject();
        try {
            biz.put("detail_page_version", 0);
            biz.put("device_level", 3);
            biz.put("disable_digg_stat", false);
            biz.put("disable_video_relate_book", false);
            biz.put("need_all_video_definition", true);
            biz.put("need_mp4_align", false);
            biz.put("use_os_player", false);
            biz.put("use_server_dns", false);
            biz.put("video_platform", 1024);
            org.json.JSONObject map = new org.json.JSONObject();
            map.put("1", new org.json.JSONArray().put(vid));
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("biz_param", biz);
            body.put("mixed_video_id_map", map);
            JSONObject j = post("/novel/player/multi_video_model/v1/", body.toString());
            Log.d(TAG, "multiVideoModel " + vid + " code=" + j.optInt("code")
                    + " tracks=" + trackCount(j));
            return j;
        } catch (Exception e) {
            Log.w(TAG, "multiVideoModel 失败，保留 GET 结果", e);
            return new JSONObject();
        }
    }

    /**
     * 数出响应里有多少档清晰度。
     *
     * 两个接口的外层结构不同：GET 是 data.video_model（JSON 字符串），
     * POST 是 data.<vid>.video_model（同样是字符串）。所以先按 vid 找，
     * 再按固定字段名找。
     */
    static int trackCount(JSONObject resp) {
        JSONObject vm = findVideoModel(resp, null);
        if (vm == null) {
            return 0;
        }
        Object list = vm.opt("video_list");
        return list instanceof org.json.JSONArray ? ((org.json.JSONArray) list).length() : 0;
    }

    /** 在响应里定位 video_model 对象，兼容「字符串包裹」和「按 vid 分桶」两种形态。 */
    public static JSONObject findVideoModel(JSONObject resp, String vid) {
        if (resp == null) {
            return null;
        }
        JSONObject data = resp.optJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONObject node = data;
        if (vid != null && data.optJSONObject(vid) != null) {
            node = data.optJSONObject(vid);
        }
        Object raw = node.opt("video_model");
        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }
        if (raw instanceof String && !((String) raw).isEmpty()) {
            try {
                return new JSONObject((String) raw);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
