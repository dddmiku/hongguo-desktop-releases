# JNA 反射加载，不能被混淆
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
-keepclassmembers class * extends com.sun.jna.** { public *; }

# metasec 的 MS 类通过反射调用
-keep class com.bytedance.mobsec.** { *; }
# metasec 的 JNI_OnLoad 用 FindClass 按名字找这条类链
# （MS 继承 ms.bd.c.a4$a 继承 ms.bd.c.m）。混淆掉任一级都会 SIGABRT
# ClassNotFoundException，且崩溃发生在 so 加载阶段，堆栈里看不到原因。
-keep class ms.bd.c.** { *; }
