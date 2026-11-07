package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;

import org.json.JSONArray;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.HistoryStore;
import cn.hongguo.tv.cache.WatchRecord;
import cn.hongguo.tv.net.Http;

/** Exercises the real episode-load worker with a delayed, failing transport in the .qa app. */
public class PlayerLifecycleDeviceTest extends InstrumentationTestCase {
    private static final String SERIES = "7699859403758914622";
    private static JSONArray episodes;
    private final List<Activity> opened = new ArrayList<>();
    private final List<LoadFailure> loads = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        Account.clear(getInstrumentation().getTargetContext());
        if (episodes == null) {
            Api api = new Api(getInstrumentation().getTargetContext());
            episodes = Api.parseEpisodes(api.episodes(SERIES), SERIES);
            assertTrue(episodes.length() > 1);
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            for (LoadFailure load : loads) {
                load.release.countDown();
                if (load.thread.get() != null) load.thread.get().join(3000);
            }
            main(() -> {
                for (int i = opened.size() - 1; i >= 0; i--) {
                    if (!opened.get(i).isDestroyed()) opened.get(i).finish();
                }
                return null;
            });
            getInstrumentation().waitForIdleSync();
        } finally {
            super.tearDown();
        }
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }

    private Activity open(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity result = getInstrumentation().startActivitySync(intent);
        opened.add(result);
        getInstrumentation().waitForIdleSync();
        return result;
    }

    private PlayerActivity player() {
        return (PlayerActivity) open(PlayerActivity.intent(
                getInstrumentation().getTargetContext(), SERIES, "生命周期验收", "", episodes.toString())
                .putExtra(PlayerActivity.EXTRA_START_INDEX, 0).putExtra("start_position_ms", 0L));
    }

    private Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private LoadFailure load(PlayerActivity player, String message, int index) throws Exception {
        LoadFailure fault = new LoadFailure(message);
        loads.add(fault);
        Api api = new Api(getInstrumentation().getTargetContext());
        set(api, "signerAdapter", fault);
        main(() -> {
            set(player, "api", api);
            try {
                Method play = PlayerActivity.class.getDeclaredMethod("play", int.class);
                play.setAccessible(true);
                play.invoke(player, index);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        assertTrue("episode-load 未进入受控取流失败路径", fault.entered.await(3, TimeUnit.SECONDS));
        return fault;
    }

    private void complete(LoadFailure load) throws Exception {
        load.release.countDown();
        load.thread.get().join(3000);
        assertFalse("取流线程未结束", load.thread.get().isAlive());
        getInstrumentation().waitForIdleSync();
        assertNull("后台取流失败导致未捕获异常：" + load.uncaught.get(), load.uncaught.get());
    }

    private String status(PlayerActivity player) {
        return main(() -> ((TextView) field(player, "status")).getText().toString());
    }

    public void testEmptyEpisodeInputClosesWithoutCrashing() throws Exception {
        AtomicReference<Throwable> crashed = new AtomicReference<>();
        CountDownLatch crash = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if ("cache-trim".equals(thread.getName())) {
                crashed.set(error);
                crash.countDown();
            } else if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
        try {
            Activity empty = open(PlayerActivity.intent(getInstrumentation().getTargetContext(),
                    SERIES, "空剧集验收", "", "[]"));
            getInstrumentation().waitForIdleSync();
            crash.await(1, TimeUnit.SECONDS);
            assertTrue(empty.isFinishing());
            assertNull("没有可播集数时退出导致闪退：" + crashed.get(), crashed.get());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    public void testLoadFailureUpdatesUiWithoutCrashing() throws Exception {
        PlayerActivity player = player();
        LoadFailure fault = load(player, "受控离线失败", 0);
        AtomicReference<Looper> updatedOn = new AtomicReference<>();
        main(() -> {
            ((TextView) field(player, "status")).addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updatedOn.set(Looper.myLooper());
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            return null;
        });
        complete(fault);
        assertSame("取流错误在后台线程更新了界面", Looper.getMainLooper(), updatedOn.get());
        assertEquals("✗ 受控离线失败", status(player));
        assertFalse(player.isFinishing());
    }

    public void testReturnHomeBeforeLoadFinishes() throws Exception {
        returnWhileLoading(new Intent(getInstrumentation().getTargetContext(), MainActivity.class));
    }

    public void testReturnHistoryBeforeLoadFinishes() throws Exception {
        returnWhileLoading(LibraryActivity.intent(getInstrumentation().getTargetContext(), false));
    }

    public void testReturnFavoritesBeforeLoadFinishes() throws Exception {
        returnWhileLoading(LibraryActivity.intent(getInstrumentation().getTargetContext(), true));
    }

    private void returnWhileLoading(Intent destination) throws Exception {
        Activity origin = open(destination);
        PlayerActivity player = player();
        LoadFailure fault = load(player, "返回后不应显示的旧错误", 0);
        String before = status(player);
        // Pin the finish/onDestroy gap so a stale background completion is deterministic.
        main(() -> {
            player.finish();
            fault.release.countDown();
            try {
                fault.thread.get().join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return null;
        });
        complete(fault);
        assertEquals("退出后的旧请求仍然修改播放器", before, status(player));
        assertTrue(player.isFinishing());
        assertFalse("返回目标页面也被关闭", origin.isFinishing());
        assertEquals("退出后仍有播放器", null, main(() -> field(player, "player")));
    }

    public void testOlderLoadDoesNotReplaceCurrentEpisode() throws Exception {
        PlayerActivity player = player();
        LoadFailure old = load(player, "旧集错误", 0);
        LoadFailure current = load(player, "当前集离线失败", 1);
        complete(old);
        assertTrue(status(player).contains("第 2 集"));
        complete(current);
        assertEquals("✗ 当前集离线失败", status(player));
    }

    public void testActualDecoderRepeatedReturnsSaveAndRelease() throws Exception {
        for (int round = 0; round < 9; round++) {
            Intent destination = round % 3 == 0
                    ? new Intent(getInstrumentation().getTargetContext(), MainActivity.class)
                    : LibraryActivity.intent(getInstrumentation().getTargetContext(), round % 3 == 2);
            Activity origin = open(destination);
            PlayerActivity player = player();
            long deadline = System.currentTimeMillis() + 90000;
            Integer at = null;
            while (System.currentTimeMillis() < deadline) {
                at = main(() -> {
                    try {
                        MediaPlayer decoder = (MediaPlayer) field(player, "player");
                        return decoder != null && decoder.isPlaying() && decoder.getVideoWidth() > 0
                                && decoder.getCurrentPosition() > 100 ? decoder.getCurrentPosition() : null;
                    } catch (IllegalStateException e) {
                        return null;
                    }
                });
                if (at != null) break;
                Thread.sleep(50);
            }
            assertNotNull("第 " + round + " 轮没有实际解码播放", at);
            int playedAt = at;
            main(() -> { player.finish(); return null; });
            getInstrumentation().waitForIdleSync();
            long closeDeadline = System.currentTimeMillis() + 3000;
            while (!main(player::isDestroyed) && System.currentTimeMillis() < closeDeadline) Thread.sleep(20);
            assertTrue("退出后页面没有完成销毁", main(player::isDestroyed));
            assertNull("退出后仍持有解码器", main(() -> field(player, "player")));
            assertFalse("返回页面被关闭", origin.isFinishing());
            WatchRecord saved = HistoryStore.get(getInstrumentation().getTargetContext()).find("local", SERIES);
            assertNotNull(saved);
            assertEquals(1, saved.episode);
            assertTrue("退出时没有保存真实位置", saved.position >= playedAt);
            assertFalse(saved.pending);
            main(() -> { origin.finish(); return null; });
            getInstrumentation().waitForIdleSync();
        }
    }

    private static final class LoadFailure implements Http.Signer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final AtomicReference<Throwable> uncaught = new AtomicReference<>();
        final String message;

        LoadFailure(String message) { this.message = message; }

        @Override public void sign(String url, Map<String, String> headers) throws IOException {
            Thread worker = Thread.currentThread();
            if (!"episode-load".equals(worker.getName())) throw new IOException("错误的取流线程");
            worker.setUncaughtExceptionHandler((t, failure) -> uncaught.set(failure));
            thread.set(worker);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("取流测试超时");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("取流已取消", e);
            }
            throw new IOException(message);
        }
    }
}
