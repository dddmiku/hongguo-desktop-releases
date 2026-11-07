package cn.hongguo.tv.net;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * 最小 HTTP 客户端：只用 HttpsURLConnection。
 *
 * 为什么不用 OkHttp：
 *   APK 要尽量小（竞品 3.9MB 里 3.4MB 是签名 so）。OkHttp 会多出 ~1.5MB
 *   依赖，而这里只需要 GET/POST + 自定义头，系统 API 完全够用。
 *
 * 为什么单独处理 TLS：
 *   部分老电视的系统 TLS 栈只支持到 TLS 1.2，且默认不启用某些套件。
 *   这里显式构造一个宽松的 SSLSocketFactory，避免握手失败。
 */
public final class Http {

    private static final String TAG = "HgHttp";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private static SSLSocketFactory sFactory;

    private Http() {
    }

    public static final class Response {
        public final int code;
        public final String body;
        /**
         * 响应里所有 Set-Cookie，按出现顺序，已去掉 Path/Domain/Expires 等属性。
         *
         * 为什么必须留着：登录接口（passport）靠 Set-Cookie 下发登录态，
         * 而且一次可能下发好几个（passport_csrf_token / sessionid / sid_tt）。
         * HttpURLConnection 自己也有 CookieHandler，但我们没装，所以必须
         * 手动取；漏掉任何一个都会让后续请求掉登录态。
         */
        public final java.util.List<String> setCookies;

        Response(int code, String body, java.util.List<String> setCookies) {
            this.code = code;
            this.body = body;
            this.setCookies = setCookies == null
                    ? java.util.Collections.<String>emptyList() : setCookies;
        }

        public boolean ok() {
            return code >= 200 && code < 300 && body != null && !body.isEmpty();
        }

        /** 把 Set-Cookie 拼成可直接放 Cookie 头的 "a=1; b=2"。 */
        public String cookieHeader() {
            StringBuilder sb = new StringBuilder();
            for (String c : setCookies) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(c);
            }
            return sb.toString();
        }
    }

    /** 回调：拿到 URL 和已定头之后，由调用方补签名头。 */
    public interface Signer {
        void sign(String url, Map<String, String> headers) throws IOException;
    }

    public static Response get(String url, Map<String, String> headers, Signer signer)
            throws IOException {
        return request("GET", url, headers, null, signer);
    }

    public static Response post(String url, Map<String, String> headers, byte[] body,
                                Signer signer) throws IOException {
        return request("POST", url, headers, body, signer);
    }

    private static Response request(String method, String url, Map<String, String> baseHeaders,
                                    byte[] body, Signer signer) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>(baseHeaders);
        if (signer != null) {
            // 签名要在 body 与所有头都确定之后做：签名输入包含它们。
            signer.sign(url, headers);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(factory());
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setUseCaches(false);
            // 不跟随跳转：CDN 的 302 带时效 token，跟随会丢失 Range 语义。
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            // 只在调用方没指定时才补 identity。
            //
            // 为什么：护照请求的签名把 Accept-Encoding 算进了签名输入，
            // 若这里再强改一次，签名与实发的头就不一致，服务端判为异常
            // 客户端（error_code=7）。调用方设了就必须原样用。
            boolean hasAcceptEncoding = false;
            for (String k : headers.keySet()) {
                if ("accept-encoding".equalsIgnoreCase(k)) {
                    hasAcceptEncoding = true;
                    break;
                }
            }
            if (!hasAcceptEncoding) {
                conn.setRequestProperty("Accept-Encoding", "identity");
            }

            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (code >= 400) {
                Log.w(TAG, method + " " + url + " -> " + code + " " + head(text));
            }
            return new Response(code, text, readSetCookies(conn));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 取出 Set-Cookie 并剥掉属性，只留 "name=value"。
     *
     * Android 的 HttpURLConnection 对同一响应里的多个 Set-Cookie 会
     * 逐个放进 getHeaderFields，但不同实现可能把多个值合成一行或用
     * 逗号分隔 —— 而 Expires 里本身就有逗号，不能简单按逗号切。
     * 这里按「每个值单独出现」处理，同时处理合并行（重新按 "name=" 切）。
     */
    private static java.util.List<String> readSetCookies(HttpURLConnection conn) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Map<String, java.util.List<String>> fields = conn.getHeaderFields();
            for (Map.Entry<String, java.util.List<String>> e : fields.entrySet()) {
                if (e.getKey() == null
                        || !"set-cookie".equalsIgnoreCase(e.getKey())) {
                    continue;
                }
                for (String raw : e.getValue()) {
                    out.addAll(splitCookies(raw));
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "读取 Set-Cookie 失败: " + t.getMessage());
        }
        return out;
    }

    private static java.util.List<String> splitCookies(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        // 合并行的情况：", name=value" 才是新 cookie 起点
        // （Expires 的 "Wed, 21 Oct 2015" 逗号后面是空格+星期，不是 name=）
        String[] parts = raw.split(",(?=\\s*[A-Za-z0-9_\\-]+=)");
        for (String p : parts) {
            String one = p.trim();
            int semi = one.indexOf(';');
            if (semi >= 0) {
                one = one.substring(0, semi).trim();
            }
            if (one.contains("=") && !one.isEmpty()) {
                out.add(one);
            }
        }
        return out;
    }

    private static String readAll(InputStream in) throws IOException {        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > 8 * 1024 * 1024) {
                throw new IOException("响应过大");
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static synchronized SSLSocketFactory factory() throws IOException {
        if (sFactory != null) {
            return sFactory;
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            sFactory = ctx.getSocketFactory();
            return sFactory;
        } catch (Exception e) {
            throw new IOException("TLS 初始化失败", e);
        }
    }

    private static String head(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
