package cn.hongguo.tv.sign;

import android.content.Context;
import android.util.Log;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import com.bytedance.mobsec.metasec.ml.MS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 请求签名：JNA 直接调用 libhongguo_signer.so 里的签名函数。
 *
 * 为什么用固定偏移而不是符号名：
 *   so 被 strip 过，没有可用的导出符号表。签名函数在文件里的位置是固定的
 *   （arm64 0x168C80 / arm32 0x43A81），所以用「so 基址 + 偏移」直接取函数地址。
 *
 * 为什么要读 /proc/self/maps：
 *   偏移是**文件内**偏移，运行时必须换算成内存地址。取 offset=0 的那个映射段
 *   作为基址（PIE 的 .text 起点），加上偏移就是函数地址。
 *
 * 为什么校验 so 的 SHA256：
 *   偏移与具体 so 版本强绑定。上游换版本后偏移失效，硬调会跳到错误指令导致崩溃。
 *   先校验哈希，不匹配就明确报错，让用户更新 APK，而不是随机崩。
 */
public final class RequestSigner {

    private static final String TAG = "HgSigner";

    /** arm64 上签名函数的文件内偏移（已验证指向标准 AArch64 函数序言）。 */
    private static final long OFFSET_ARM64 = 0x168C80L;
    /** arm32 上对应偏移（来自同源实现）。 */
    private static final long OFFSET_ARM32 = 0x43A81L;

    /** 已知的 so 哈希。不匹配时拒绝初始化，避免跳到错误地址崩溃。 */
    private static final String SHA_ARM64 =
            "20d3f8dd8d0d47e4e3c0da0f377eaf69e72a72c8d214ca56e6e7493c65a8fc2d";
    private static final String SHA_ARM32 =
            "90a7a2b5e7f1f3c01100f2c1995b4de2371e9c90b8a2acff4fc2a8aa48764339";

    /** 只保留这几个签名头，其余（如 content-type）不是签名产物。 */
    private static final String HEADER_PATTERN =
            "X-(Argus|Gorgon|Helios|Khronos|Ladon|Medusa|Neptune|Soter)";

    private static Function sFunction;
    private static boolean sFailed;

    private final Context context;

    public RequestSigner(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 签名是否可用（so 加载成功）。UI 用它决定是否提示「内容服务不可用」。 */
    public static synchronized boolean isReady() {
        return sFunction != null;
    }

    public static synchronized String failureReason() {
        return sFailed ? "本机组件不可用" : "";
    }

    /**
     * 签名函数的参数「时间戳」与「计数器」。
     *
     * 反编译桌面端 unidbg 签名器（com.hongguo.sign.FqTrace / MetasecSign）
     * 得到两个不同的入口，对应 so 里两个不同的函数：
     *
     *   FqTrace.sign      → callFunction(base+0x168C80, url, headers)      2 参数
     *   MetasecSign.sign  → callFunction(base+0x27D2C8, url, headers, 24,
     *                                    tick, 0xFFFFFFFFFFFFFF, counter)  6 参数
     *
     * 0x168C80 正是本 App 一直用的偏移，也是**实际能签出被服务端接受的
     * 头**的那条路径（桌面端日常登录/播放全走 FqTrace）。所以这里保持
     * 2 参数调用，不做扩展 —— 曾经误改成 6 参数，实测护照接口仍返回
     * error_code=7（而那时签名根本没崩，说明那 4 个额外参数不是判据）。
     *
     * 保留下面两个常量只为记录 unidbg 的取值，避免以后又去猜。
     */
    @SuppressWarnings("unused")
    private static final int SIGN_ARG3 = 24;
    @SuppressWarnings("unused")
    private static final long SIGN_ARG5 = 72057594037927935L;   // 0xFFFFFFFFFFFFFF

    /**
     * 对 URL + 请求头签名，返回需要追加的签名头。
     *
     * 只用本机 so。签名只依赖 url + headers，与内容接口/护照接口无关；
     * 两种请求都走这一条路径（实测都能通过服务端校验）。
     *
     * @param url     完整请求 URL（含 query）
     * @param headers 已确定的请求头（签名需要读 user-agent 等）
     * @return 形如 {"X-Argus": "...", "X-Gorgon": "..."} 的头集合
     */
    public Map<String, String> sign(String url, Map<String, String> headers) throws IOException {
        synchronized(RequestSigner.class) {
        final Function fn;
        synchronized (RequestSigner.class) {
            initialize();
            fn = sFunction;
        }
        if (fn == null) {
            throw new IOException(failureReason());
        }

        // 签名函数吃的是「键\r\n值\r\n键\r\n值...」这种扁平串。
        // 用 TreeMap 保证顺序稳定：同样的输入必须产生同样的串，否则签名不可复现。
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(headers).entrySet()) {
            if (sb.length() > 0) {
                sb.append("\r\n");
            }
            sb.append(e.getKey()).append("\r\n").append(e.getValue());
        }

        Pointer result = fn.invokePointer(new Object[]{url, sb.toString()});
        if (result == null) {
            throw new IOException("内容验证暂不可用");
        }

        // 返回的是 C 字符串：读到 NUL 为止。上限 64KB，防止异常指针读飞。
        int len = 0;
        while (len < 65536 && result.getByte(len) != 0) {
            len++;
        }
        if (len >= 65536) {
            throw new IOException("内容验证返回异常");
        }

        String[] lines = new String(result.getByteArray(0, len), "UTF-8").split("\n");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < lines.length; i += 2) {
            String name = lines[i].trim();
            String value = lines[i + 1].trim();
            if (name.matches(HEADER_PATTERN) && !value.contains("\r")) {
                out.put(name, value);
            }
        }
        return out;
        }
    }

    private static synchronized void initialize() throws IOException {
        if (sFunction != null) {
            return;
        }
        if (sFailed) {
            throw new IOException(failureReason());
        }
        // 上下文判空要放在置失败位**之前**。
        //
        // sFailed 是粘性的（置位后不再重试），如果「尚未绑定」也走这条路，
        // 等 Application 稍后绑定好，这个组件就永久不可用了。绑定缺失是
        // 时序问题不是组件问题，必须保留重试机会。
        Context ctx = Holder.context;
        if (ctx == null) {
            throw new IOException("签名组件尚未绑定上下文");
        }
        sFailed = true;   // 先置位：初始化中途抛异常也不会被反复重试拖死

        File libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File soFile = new File(libDir, "libhongguo_signer.so");
        if (!soFile.isFile()) {
            throw new IOException("缺少本机组件");
        }

        boolean is32 = isArm32();
        String expected = is32 ? SHA_ARM32 : SHA_ARM64;
        if (!expected.equals(sha256(soFile))) {
            throw new IOException("本机组件校验失败（版本不匹配，请更新应用）");
        }

        // JNA 默认会尝试从 classpath 解包 dispatch 库；这里强制只用随包的 so，
        // 避免在受限文件系统上解包失败。
        System.setProperty("jna.boot.library.path", libDir.getAbsolutePath());
        System.setProperty("jna.boot.library.name", "hongguo_jnidispatch");
        System.setProperty("jna.nounpack", "true");
        System.setProperty("jna.noclasspath", "true");

        if (Native.POINTER_SIZE != (is32 ? 4 : 8)) {
            throw new IOException("安装版本与进程架构不匹配");
        }

        // 把 metasec 回调要的环境值先灌进 Java stub，再加载 so。
        //
        // 顺序不能反：JNI_OnLoad 里会立刻 FindClass(MS) 并开始用 MS.b(op,...)
        // 索取 files 目录 / 证书 / 版本号。这些字段必须先就位。
        MS.sFilesDir = ctx.getFilesDir().getAbsolutePath();
        if (!is32) {
            // arm32 分支不需要证书；arm64 的 op 16777218 会来要这段字节。
            MS.sCert = readAsset(ctx, "signer-cert.bin", 8192);
        }

        System.load(soFile.getAbsolutePath());

        long base = findLoadBase(soFile.getAbsolutePath());
        if (base == 0) {
            throw new IOException("无法初始化本机播放组件");
        }

        sFunction = Function.getFunction(new Pointer(base + (is32 ? OFFSET_ARM32 : OFFSET_ARM64)));
        sFailed = false;
        Log.i(TAG, "签名组件就绪 base=" + Long.toHexString(base));
    }

    /**
     * 从 /proc/self/maps 找 so 的加载基址。
     *
     * 只认 offset=0 的那一段：那是 ELF 的第一个 PT_LOAD（.text 所在），
     * 其余段（.data/.bss）的 offset 不是 0，加上文件偏移会算错。
     */
    private static long findLoadBase(String soPath) {
        try (java.io.BufferedReader r =
                     new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 6 && f[5].equals(soPath) && f[2].equals("00000000")) {
                    return Long.parseLong(f[0].split("-")[0], 16);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取 maps 失败", e);
        }
        return 0;
    }

    private static boolean isArm32() {
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "";
        return "armeabi-v7a".equals(abi) || "armeabi".equals(abi);
    }

    private static String sha256(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAsset(Context ctx, String name, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = ctx.getAssets().open(name)) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > limit) {
                    throw new IOException("证书过大");
                }
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    /** 静态上下文持有者：initialize() 是静态方法，需要拿到 Context。 */
    private static final class Holder {
        static Context context;
    }

    /** 在 Application.onCreate 里调用一次（见 cn.hongguo.tv.App）。 */
    public static synchronized void bind(Context ctx) {
        Holder.context = ctx.getApplicationContext();
    }
}
