package ms.bd.c;

/**
 * metasec native 分发器的 Java 宿主类（类链的最底层）。
 *
 * 为什么需要这个类：
 *   libhongguo_signer.so（实为番茄海外 libmetasec_ml.so）在 JNI_OnLoad 里
 *   FindClass("com/bytedance/mobsec/metasec/ml/MS")，然后反复 GetSuperClass
 *   校验类链 MS ← ms.bd.c.a4$a ← ms.bd.c.m。链断了 FindClass 失败会留下
 *   未清异常，so 紧接着调 NewGlobalRef → ART 直接 abort（SIGABRT）。
 *
 * 为什么要声明 native 方法 a：
 *   so 自己会 RegisterNatives 到这个类的 a(...) 上，这是 native→Java 的
 *   唯一入口（init / getReportRaw / frameSign 全靠 op 分发）。
 *   方法名和签名必须与 so 里注册的完全一致，否则注册失败。
 */
public class m {

    /**
     * metasec 唯一 native 入口，由 so 在 JNI_OnLoad 后 RegisterNatives 绑定。
     *
     * @param op   操作码，决定这次调用要做什么（init / 签名 / 取环境）
     * @param a1   保留参数
     * @param a2   保留参数（常是时间戳）
     * @param a3   字符串参数（URL / header 串）
     * @param a4   对象参数
     * @return 视 op 而定
     */
    public static native Object a(int op, int a1, long a2, String a3, Object a4);
}
