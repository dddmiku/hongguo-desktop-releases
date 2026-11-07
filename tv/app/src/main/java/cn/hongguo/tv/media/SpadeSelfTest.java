package cn.hongguo.tv.media;

import android.util.Log;

/**
 * Spade 移植的自检：用 5 组运行时真值验证 Java 实现与 Python 参考实现一致。
 *
 * 为什么值得单独写一个自检：
 *   spade 解出来的密钥一旦错一位，AES-CTR 会把整个视频解成噪声，播放器只报
 *   「无法播放」，看不出是密钥算错。这 5 组真值是 hook libttmplayer 抓到的
 *   运行时输入/输出对，能在不下载任何视频的情况下确认算法正确。
 */
public final class SpadeSelfTest {

    private static final String TAG = "HgSpadeTest";

    /** {spade hex, 期望的 32 hex 密钥}，来自 capture/unwrap_samples.json。 */
    private static final String[][] TRUTH = {
            {"93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282",
                    "287216bfa89e662a0f748120c305e199"},
            {"a1bc2ff164b91df04dbf00f17dba00f47cbb35c27b922aec67a628eb52972fdc7ea036a7a7",
                    "d742b28967e4e6c92b699f4375add27f"},
            {"9cbc12fb588721cd69b739f977b70be7429c08e074990ec847843dcc738638cf76b33ebcbc",
                    "113fb30d9767d80e3edbb905b052204f"},
            {"a3bc2df760ba2bc27b8b34c04a8907f557be19f249be35c748ba03f64fbc1ff161ba29a2a2",
                    "b5674a8384f25d757585dadf343274d5"},
            {"9cbc12f45aba11f76fb814f444bd15f459b225cb5e8c15dd459a0fd170ac0ad358aa108b8b",
                    "121846f9d5829130ebc0397023feaf8c"},
    };

    private SpadeSelfTest() {
    }

    /** 返回 "4/5" 这样的结果串，同时把每条明细写进日志。 */
    public static String run() {
        int ok = 0;
        for (int i = 0; i < TRUTH.length; i++) {
            byte[] spade = hex(TRUTH[i][0]);
            byte[] got = Spade.unwrapV1(spade, 0);
            String gotHex = got == null ? "null" : toHex(got);
            boolean hit = TRUTH[i][1].equals(gotHex);
            if (hit) {
                ok++;
            }
            Log.i(TAG, (hit ? "OK   " : "FAIL ") + gotHex + " == " + TRUTH[i][1]);
        }
        return ok + "/" + TRUTH.length;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
