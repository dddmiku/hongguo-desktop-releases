package ms.bd.c;

/**
 * metasec 类链的中间层（类链 MS ← ms.bd.c.a4$a ← ms.bd.c.m）。
 *
 * 为什么要有 $ 和继承：
 *   so 在 JNI_OnLoad 里从 MS 开始连续 GetSuperClass，期望拿到
 *   a4$a、再拿到 m。链上任何一环不是「类」都会让 GetSuperClass 返回空，
 *   随后 FindClass 的失败异常没被清除，NewGlobalRef 就 abort。
 *   所以这里的继承关系必须一字不差：a4$a 直接继承 m，中间不能再插一层。
 *
 * 为什么没有方法体：
 *   so 不调用这层的任何方法，只做类链校验；真正的逻辑全在 m.a(...)。
 */
public class a4 {

    /** 对应 so 里类链第二环 ms/bd/c/a4$a，必须直接 extends m。 */
    public static class a extends m {
    }
}
