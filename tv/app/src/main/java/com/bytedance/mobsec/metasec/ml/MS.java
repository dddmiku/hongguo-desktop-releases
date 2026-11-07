package com.bytedance.mobsec.metasec.ml;

/**
 * metasec 的 Java 侧回调类（native → Java 的环境索取入口）。
 *
 * 为什么要实现这个类：
 *   libhongguo_signer.so 的 JNI_OnLoad 第一件事就是 FindClass 这个类，
 *   拿不到就 abort。类链要求它继承 ms.bd.c.a4$a，所以继承关系不能改。
 *
 * 为什么 b(...) 用 switch 返回固定值：
 *   metasec 在初始化 / 签名过程中通过 b(op, ...) 向宿主索取环境
 *   （files 目录、证书字节、当前时间、版本号等）。这些值只影响设备指纹
 *   字段，不影响签名算法本身。返回值取自同族 fqnovel 实现的实测 opcode 表。
 */
public class MS extends ms.bd.c.a4.a {

    /** metasec 在初始化期用这个 op 要 files 目录下的 .msdata 路径。 */
    public static String sFilesDir = "";

    /** op 16777218 要的证书字节，由 RequestSigner 从 assets 灌进来。 */
    public static byte[] sCert;

    /** op 16777233 要的版本号字符串。 */
    public static String sVersion = "6.8.1.32";

    /**
     * metasec 的 native → Java 回调分发器。
     *
     * @param op 操作码，决定返回什么环境信息
     * @return 按 op 返回对应类型；未知 op 返回 null（metasec 能接受）
     */
    public static Object b(int op, int a1, long a2, String a3, Object a4) {
        switch (op) {
            case 65539:
                // files 目录下 metasec 自己的数据目录
                return sFilesDir + "/.msdata";
            case 33554433:
            case 33554434:
                return Boolean.TRUE;
            case 16777232:
                // 内部版本号（整数形式）
                return 68132;
            case 16777233:
                return sVersion;
            case 16777218:
                return sCert;
            case 268435470:
                return System.currentTimeMillis();
            default:
                return null;
        }
    }

    /** metasec 在 init 期会调这个无参方法，语义是「刷新环境」，空实现即可。 */
    public static void a() {
    }

    /** 与 a() 配套的静态字段读取（so 读 MS.a 当作 int 常量 0x40）。 */
    public static int a = 0x40;
}
