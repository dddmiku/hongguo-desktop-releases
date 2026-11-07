package cn.hongguo.tv.media;

import android.util.Base64;
import android.util.Log;

/**
 * spade_a → content key 的纯离线还原。
 *
 * 上游每条轨道都带 encrypt_info.spade_a（base64）。它不是密钥本身，而是
 * 一段经过字节变换的「包装数据」；解开后得到 16 字节 AES content key，
 * 再配合 mp4 里 senc 盒的 base_iv 做 AES-128-CTR 解密。
 *
 * 算法来源：逆自 libttmplayer.so FUN_001c4550（ver1 路径，flag=0）。
 * 变换只用 XOR / POPCOUNT / 位置相关运算，**没有 KEK、没有 AES**，
 * 所以完全可以在设备本地算，不需要账号或密钥服务。
 *
 * 移植自桌面端 frida/unwrap_spade.py，逻辑逐行对应，含 5 组运行时真值。
 *
 * 注意 ver2：spade 里嵌的 type 字符串若是 app_v2 / web_v2，走的是另一条
 * AES-GCM-256 路径（libttmplayer FUN_00271a50），本类不实现，直接返回 null
 * 让上层报错，而不是算出错误密钥导致解密出一堆垃圾字节。
 */
public final class Spade {

    private static final String TAG = "HgSpade";

    private Spade() {
    }

    /**
     * 从 video_model 的 encrypt_info.spade_a 算出 content key。
     *
     * @param spadeA base64 编码的 spade 数据
     * @return 16 字节 AES 密钥；解析失败或是 ver2 返回 null
     */
    public static byte[] contentKey(String spadeA) {
        if (spadeA == null || spadeA.isEmpty()) {
            return null;
        }
        byte[] spade;
        try {
            spade = Base64.decode(spadeA, Base64.DEFAULT);
        } catch (Exception e) {
            Log.w(TAG, "spade base64 解码失败", e);
            return null;
        }
        // flag=0 是实测值（播放器 option 0x97 = 0）。取不到就换 1 再试，
        // 两个方向只差一个符号，试错成本远低于让整集播放失败。
        byte[] key = unwrapV1(spade, 0);
        if (key == null) {
            key = unwrapV1(spade, 1);
        }
        if (key == null) {
            Log.w(TAG, "spade 解析失败（可能是 ver2 / AES-GCM 或格式异常）");
        }
        return key;
    }

    /**
     * ver1 字节变换。与 unwrap_spade.py::unwrap_v1 一一对应。
     *
     * @param spade 原始字节（非 base64）
     * @param flag  播放器 option 0x97 的值；0 与 1 是互逆的两个方向
     * @return 16 字节 content key 或 null
     */
    static byte[] unwrapV1(byte[] spade, int flag) {
        int len = spade.length;
        if (len < 3) {
            return null;
        }
        // 前 3 字节异或得到「type 字符串长度 + 0x30」这类编码值
        int bVar5 = (spade[0] ^ spade[1] ^ spade[2]) & 0xFF;
        int typeLen = bVar5 - 0x30;
        if (typeLen < 1) {
            return null;
        }
        int workLen = (len - bVar5) + 0x2f;
        if (workLen < 1 || 1 + workLen > len) {
            return null;
        }

        // 工作缓冲 = spade[1 .. 1+workLen)
        byte[] dest = new byte[workLen];
        System.arraycopy(spade, 1, dest, 0, workLen);

        // 从尾部解出 type 字符串，判断是 ver1 还是 ver2
        int tBase = len - typeLen;
        int b16 = spade[len - typeLen - 2] & 0xFF;
        int b14 = spade[len - typeLen - 1] & 0xFF;
        byte[] type = new byte[typeLen];
        for (int i = 0; i < typeLen; i++) {
            type[i] = (byte) (b14 ^ b16 ^ (spade[i + tBase] & 0xFF));
        }
        if (matches(type, "app_v2") || matches(type, "web_v2")) {
            // ver2 走 AES-GCM-256，本类不实现
            return null;
        }

        // ver1 主变换
        int r14 = 0x55;
        int r16 = 0xfa;
        for (int i = 0; i < workLen; i++) {
            int b6 = dest[i] & 0xFF;
            int pop = Integer.bitCount(i);
            int b3 = b6;
            int b7 = r14;
            if ((i & 1) != 0) {
                b3 = r16;
                b7 = b6;
                r16 = r14;
            }
            // flag=0 时取 -0x15-pop 的有符号字节，flag!=0 时取 +0x15+pop
            int cVar4 = flag != 0 ? (pop + 0x15) : s8(-0x15 - pop);
            dest[i] = (byte) ((cVar4 + (r16 ^ b6)) & 0xFF);
            r14 = b7;
            r16 = b3;
        }

        // dest[0] 编码了密钥的起始偏移
        int b0 = dest[0] & 0xFF;
        int u11;
        if (b0 >= 0x30 && b0 <= 0x39) {
            u11 = b0 - 0x30;
        } else if (b0 >= 0x61 && b0 <= 0x7a) {
            u11 = b0 - 0x57;
        } else {
            return null;
        }
        int iv9 = workLen - (u11 & 0xFF);
        if (iv9 < 2) {
            return null;
        }

        // 切出 32 个 hex 字符，转成 16 字节密钥
        int n = iv9 - 1;
        if (n <= 0) {
            return null;
        }
        String hex = new String(dest, 1, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        return hexToBytes(hex);
    }

    /** 有符号 8 位取值的等价形式（Python 的 _s8）。 */
    private static int s8(int v) {
        v &= 0xFF;
        return v >= 128 ? v - 256 : v;
    }

    /** Python 的 _strncmp0：逐字节比较，遇到 0 也视为相等。 */
    private static boolean matches(byte[] a, String b) {
        byte[] t = b.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int n = Math.min(a.length, t.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != t[i]) {
                return false;
            }
            if (a[i] == 0) {
                return true;
            }
        }
        return true;
    }

    /** 32 个 hex 字符 → 16 字节。长度或字符非法返回 null。 */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() < 32) {
            return null;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = hexVal(hex.charAt(i * 2));
            int lo = hexVal(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
