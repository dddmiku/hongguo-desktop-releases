package cn.hongguo.tv;

import android.app.Application;

import cn.hongguo.tv.sign.RequestSigner;

/**
 * 进程入口：在这里绑一次签名组件的 Context。
 *
 * 为什么必须在 Application 而不是各 Activity：
 *   RequestSigner 的 Context 是静态的，只在 MainActivity.onCreate 里绑。
 *   而账号页 / 播放页都可以**不经过首页**被启动 —— 点通知、进程被回收后
 *   从最近任务恢复、adb 直接拉起 —— 那时静态 Context 还是 null，
 *   任何签名请求都抛「签名组件尚未绑定上下文」，表现是「收藏和历史上
 *   全空、还提示同步失败」，但首页一切正常，很难联想到是启动路径问题。
 *
 *   实测（MuMu / 2026-11-04）：直接 am start 账号页，收藏与历史各报一次
 *   IOException，堆栈顶是 RequestSigner.initialize 的 Context 判空。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RequestSigner.bind(this);
    }
}
