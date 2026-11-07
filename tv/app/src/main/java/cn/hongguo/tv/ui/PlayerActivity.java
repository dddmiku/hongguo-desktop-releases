package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.MediaCache;
import cn.hongguo.tv.cache.PlayProgress;
import cn.hongguo.tv.media.EpisodePreparer;
import cn.hongguo.tv.media.TrackPicker;

/**
 * 播放页：连续播放 + 进度记忆 + 清晰度切换 + 倍速。
 *
 * 电视遥控器交互：
 *   确定 / 播放暂停 —— 暂停或继续
 *   左 / 右        —— 快退 / 快进 10 秒
 *   上 / 下        —— 上一集 / 下一集
 *   菜单键 / M     —— 清晰度切换（在可选档位间循环）
 *   返回           —— 退出（自动保存进度）
 *
 * 为什么用 MediaPlayer 而不是 ExoPlayer：
 *   包体约束。解密后已是标准 mp4（hvc1 + mp4a），系统解码器够用。
 *   老电视对 H.265 的支持依赖厂商，TrackPicker 已先问过系统解码能力。
 */
public class PlayerActivity extends TvActivity implements SurfaceHolder.Callback {

    private static final String TAG = "HgPlayer";

    public static final String EXTRA_SERIES_ID = "series_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_EPISODES = "episodes";
    public static final String EXTRA_START_INDEX = "start_index";

    private static final int SEEK_MS = 10000;
    /** 进度自动保存间隔。 */
    private static final long SAVE_INTERVAL_MS = 5000;
    /** 可用倍速。 */
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};

    private SurfaceView surface;
    private TextView status;
    private TextView titleView;
    private TextView timeView;
    private TextView qualityView;
    /** 收藏按钮：显示当前是否已收藏，点击切换。 */
    private TextView favView;
    /** 当前剧是否在云端书架里；未登录或还没查过时为 false。 */
    private volatile boolean favorited;
    private boolean favoriteBusy;
    /** 是否已经成功查过一次书架状态（决定按钮文案能不能信）。 */
    private volatile boolean favoriteKnown;
    /** 系列封面（进度上报用，让手机端历史里也有图）。 */
    private String seriesCover = "";
    private ProgressBar bar;
    private LinearLayout overlay;

    private MediaPlayer player;
    private boolean prepared;
    private boolean surfaceReady;
    private File pendingFile;
    private long pendingSeek;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Api api;
    private MediaCache cache;
    private PlayProgress progress;
    private EpisodePreparer preparer;
    /** 账号接口：登录后把进度同步到云端（手机「历史」页能看到）。 */
    private cn.hongguo.tv.account.AccountApi account;
    /** 云端已知的最新集号；0 表示还没读过。 */
    private volatile int cloudEpisode;
    /** 上一次上报的「集号|位置」，避免每个 tick 都打一次网络。 */
    private String lastReported = "";

    private String seriesId;
    private String seriesTitle;
    private final List<Ep> episodes = new ArrayList<>();
    private int index;
    private int loadGeneration;
    private cn.hongguo.tv.cache.LibraryRepository library;
    private String playbackOwner;
    private long requestedStartPosition=-1;
    private boolean forceFlush;
    private boolean autoplay=true;
    private boolean foreground;
    private android.app.AlertDialog playbackMenu;


    /** 当前清晰度上限，用于切换。 */
    private int capIndex;
    private static final int[] CAPS = {1080, 720, 480};
    private static final String[] CAP_NAMES = {"1080p", "720p", "480p"};
    /** 当前倍速下标。 */
    private int speedIndex;

    /** 实际选中的清晰度名（TrackPicker 可能因设备能力降档）。 */
    private String actualDefinition = "";

    private final Runnable saveTick = new Runnable() {
        @Override
        public void run() {
            saveProgress();
            ui.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    private static final class Ep {
        String vid;
        int num;
        String title;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cn.hongguo.tv.sign.RequestSigner.bind(this);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES_ID);
        seriesTitle = getIntent().getStringExtra(EXTRA_TITLE);
        seriesCover = getIntent().getStringExtra(EXTRA_COVER);
        if (seriesCover == null) {
            seriesCover = "";
        }
        parseEpisodes(getIntent().getStringExtra(EXTRA_EPISODES));
        if (episodes.isEmpty()) {
            finish();
            return;
        }

        api = new Api(this);
        cache = new MediaCache(this);
        progress = new PlayProgress(this);
        library = cn.hongguo.tv.cache.LibraryRepository.get(this);
        playbackOwner = library.owner();
        requestedStartPosition = getIntent().getLongExtra("start_position_ms",-1);
        autoplay = getPreferences(0).getBoolean("autoplay",true);
        capIndex = Math.max(0,Math.min(CAPS.length-1,getPreferences(0).getInt("quality",0)));
        speedIndex = Math.max(0,Math.min(SPEEDS.length-1,getPreferences(0).getInt("speed",0)));
        preparer = new EpisodePreparer(cache);
        account = new cn.hongguo.tv.account.AccountApi(this);
        // 沿用上次选的画面比例模式
        aspectMode = progress.aspectMode();

        buildUi();

        // 续播：优先用 Intent 指定的集，否则用记忆里的集
        int start = getIntent().getIntExtra(EXTRA_START_INDEX, -1);
        if (start < 0) {
            start = indexOfEpisode(progress.episode(seriesId));
        }
        play(Math.max(0, Math.min(start, episodes.size() - 1)));
        // 后台读一次云端最新集号，用于进度合并保护（不阻塞播放）
        library.uploadPending();
        // 后台查一次收藏状态（未登录会立刻返回，不发请求）
        loadFavoriteState();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Surface 包一层裁剪容器：「填充」模式下 Surface 会比屏幕大，
        // 不裁剪会溢出到别的界面之上。clipChildren 让超出部分被切掉，
        // 这正是「填充」想要的视觉效果（放大后裁边）。
        FrameLayout videoBox = new FrameLayout(this);
        videoBox.setClipChildren(true);
        videoBox.setClipToPadding(true);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        videoBox.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(videoBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 控制层放在**底部**。
        //
        // 为什么在下面：竖屏短剧的画面居中、上下都有黑边，信息放顶部会
        // 压在画面上方的大片黑边里，离画面太远看着散。放底部是播放器
        // 的通行做法（进度条本来就在底部，标题紧贴它更自然）。
        //
        // 外层用 FrameLayout 让内容贴底；内层 LinearLayout 排内容。
        FrameLayout bottomBar = new FrameLayout(this);
        // 不要给控制栏加渐变背景。
        //
        // 早先用黑色渐变「保证文字可读」，但它会永久压暗画面底部 ——
        // 控制栏淡出后那层渐变还在（alpha 动画作用在 overlay 上，
        // bottomBar 的背景不受影响），观感很差。
        // 现在只在控制栏实际显示时给 overlay 一个半透明底，
        // 隐藏后画面上不留任何遮挡。
        bottomBar.setBackgroundColor(Color.TRANSPARENT);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        // 半透明底只挂在 overlay 上，跟着 alpha 动画一起淡出。
        // 这样文字在亮画面上可读，而控制栏隐藏后画面完全不被遮挡。
        GradientDrawable ovBg = new GradientDrawable();
        ovBg.setColor(0xD00D1523);
        overlay.setBackground(ovBg);
        // 安全边距：电视 overscan 会裁边，信息必须留在 5%（约 48dp）以内。
        overlay.setPadding(dp(48), dp(20), dp(48), dp(28));
        FrameLayout.LayoutParams ovlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ovlp.gravity = Gravity.BOTTOM;
        bottomBar.addView(overlay, ovlp);

        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overlay.addView(titleView);

        // 状态 + 时间 + 清晰度 放同一行，横向排列更紧凑
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setPadding(0, dp(8), 0, dp(8));

        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setTextSize(14);
        infoRow.addView(status);

        timeView = new TextView(this);
        timeView.setTextColor(0xFFAAAAAA);
        timeView.setTextSize(14);
        timeView.setPadding(dp(16), 0, 0, 0);
        infoRow.addView(timeView);

        // 清晰度标签：当前档位 + 点击切换入口。
        // 之前只能用遥控器按菜单键切，屏幕上没提示，用户不知道有这功能。
        //
        // 注意：这个 View 不能抢焦点。播放页的所有按键都由 Activity
        // 的 onKeyDown 统一处理，如果它变成 focusable，按确定会先落到
        // 它身上触发切换清晰度（实测踩过：按确定想暂停，结果换了档）。
        qualityView = new TextView(this);
        qualityView.setTextColor(Color.WHITE);
        qualityView.setTextSize(13);
        qualityView.setPadding(dp(10), dp(4), dp(10), dp(4));
        qualityView.setFocusable(false);
        qualityView.setFocusableInTouchMode(false);
        GradientDrawable qbg = new GradientDrawable();
        qbg.setCornerRadius(dp(10));
        qbg.setColor(0xDD263750);
        qualityView.setBackground(qbg);
        // 只响应触摸（手机/盒子用鼠标），遥控器走菜单键
        qualityView.setOnClickListener(v -> showPlaybackMenu());
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.setMargins(dp(16), 0, 0, 0);
        infoRow.addView(qualityView, qlp);

        // 收藏按钮：遥控器按 F（菜单键已被清晰度占用），或鼠标点击。
        // 和 qualityView 一样不能 focusable —— 播放页所有按键由 onKeyDown
        // 统一处理，可聚焦的子 View 会先吃掉确定键。
        favView = new TextView(this);
        favView.setTextColor(Color.WHITE);
        favView.setTextSize(13);
        favView.setPadding(dp(10), dp(4), dp(10), dp(4));
        favView.setFocusable(false);
        favView.setFocusableInTouchMode(false);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setCornerRadius(dp(10));
        fbg.setColor(0xDD263750);
        favView.setBackground(fbg);
        favView.setOnClickListener(v -> toggleFavorite());
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMargins(dp(10), 0, 0, 0);
        infoRow.addView(favView, flp);
        updateFavoriteLabel();

        overlay.addView(infoRow);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 4);
        overlay.addView(bar, blp);
        TextView hint=TvUi.text(this,"确定  暂停/继续    左右  快进退    上  播放设置    下  选集",13,TvUi.DIM);
        hint.setPadding(0,dp(12),0,0);overlay.addView(hint);

        // bottomBar 自己必须贴底。
        //
        // 早先只给内部的 overlay 设了 gravity=BOTTOM，但 bottomBar 本身
        // 是 WRAP_CONTENT 且没设 gravity，FrameLayout 默认把它放顶部 ——
        // 结果整块控制栏跑到屏幕上方，还压了一条黑渐变在画面上。
        // 外层 LayoutParams 的 gravity 才是决定位置的那个。
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, barLp);

        setContentView(root);
    }

    // ------------------------------------------------------------ 播放控制

    private void play(int i) {
        if (i < 0 || i >= episodes.size()) {
            return;
        }
        // 切集前先存当前进度，否则切走后进度丢失
        flushProgress();
        index = i;
        Ep ep = episodes.get(i);
        final int request=++loadGeneration;
        releasePlayer();
        setStatus("第 " + ep.num + " 集 加载中…");
        titleView.setText(seriesTitle + "  第 " + ep.num + " 集");
        bar.setProgress(0);
        timeView.setText("");

        final int cap = CAPS[capIndex];
        new Thread(() -> {
            try {
                JSONObject j = api.videoModel(ep.vid);
                JSONObject vm = Api.findVideoModel(j, ep.vid);
                if (vm == null) {
                    fail("取流失败");
                    return;
                }
                TrackPicker.Track t = TrackPicker.pick(vm, cap);
                Log.i(TAG, "第 " + ep.num + " 集 选中 " + t);
                if(request!=loadGeneration||isDestroyed())return;
                actualDefinition = t.definition;
                ui.post(() -> {if(request==loadGeneration&&!isDestroyed())updateQualityLabel();});
                // 缓存键要带清晰度，否则换档会命中旧档的文件
                String bucket = t.definition;
                preparer.prepare(ep.vid, bucket, t.mainUrl, t.spadeA,
                        t.encrypted, new EpisodePreparer.Listener() {
                            @Override
                            public void onProgress(String stage, int pct) {
                                ui.post(() -> {if(request==loadGeneration&&!isDestroyed())setStatus("第 " + ep.num + " 集 "
                                        + stage + (pct > 0 && pct < 100 ? " " + pct + "%" : ""));});
                            }

                            @Override
                            public void onDone(File plain) {
                                ui.post(() -> {if(request==loadGeneration&&!isDestroyed())startPlayback(plain,ep.num);});
                            }

                            @Override
                            public void onError(String message) {
                                ui.post(() -> {if(request==loadGeneration&&!isDestroyed())fail(message);});
                            }
                        });
            } catch (Throwable e) {
                Log.e(TAG, "取流异常", e);
                if(request==loadGeneration&&!isDestroyed())fail(String.valueOf(e.getMessage()));
            }
        }, "episode-load").start();
    }

    private void startPlayback(File f, int epNum) {
        if (!surfaceReady) {
            pendingFile = f;
            pendingSeek = -1;
            return;
        }
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDisplay(surface.getHolder());
            player.setDataSource(f.getAbsolutePath());
            player.setOnVideoSizeChangedListener((mp, w, h) -> {
                // 按视频真实宽高比调整 Surface，不做拉伸。
                //
                // 红果的短剧原始素材是竖屏 9:16，但上游把它装进 16:9 容器
                // 再返回（实测 1920x1080 的片子里画面是竖的，两侧黑边）。
                // 这里用 MediaPlayer 报的真实宽高来定 Surface 尺寸，
                // 竖屏素材就能铺满屏幕高度而不是被压成中间一条。
                applyAspect(w, h);
            });
            player.setOnPreparedListener(mp -> {
                prepared = true;
                // 续播：同一集重进时回到上次位置
                long dur = mp.getDuration();
                long at = resumeTarget(epNum, dur);
                if (at > 0) {
                    mp.seekTo((int) at);
                }
                applySpeed();
                setStatus("播放中" + (at > 0 ? "（续播）" : ""));
                if(foreground)mp.start();
                startTicker();
                scheduleHideOverlay();
            });
            player.setOnCompletionListener(mp -> {
                flushProgress();
                if (autoplay && index + 1 < episodes.size()) {
                    play(index + 1);
                } else {
                    setStatus(autoplay?"已是最后一集":"本集播放完毕");
                    showOverlay();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 错误 what=" + what + " extra=" + extra);
                fail("播放失败（" + what + "/" + extra + "）");
                return true;
            });
            player.prepareAsync();
        } catch (Throwable e) {
            Log.e(TAG, "起播异常", e);
            fail("无法播放：" + e.getMessage());
        }
    }

    /**
     * 画面比例模式。
     *
     * 为什么需要手动切换：
     *   红果上游把竖屏素材（9:16）装进 16:9 容器再返回，MediaPlayer 上报的
     *   宽高是 1920x1080，但画面内容只占中间一条竖条。这种「容器比例 ≠
     *   内容比例」的情况，靠 API 拿不到真实内容比例，只能让用户选。
     *
     * 三种模式覆盖所有场景：
     *   适应（FIT）  —— 保持比例完整显示，可能有黑边。默认。
     *   填充（FILL） —— 铺满屏幕，超出部分裁掉。竖屏素材选这个才铺满。
     *   拉伸（STRETCH）—— 变形铺满。画面有人脸时不建议，但有些用户偏好。
     */
    private static final int MODE_FIT = 0;
    private static final int MODE_FILL = 1;
    private static final int MODE_STRETCH = 2;
    private static final String[] MODE_NAMES = {"适应", "填充", "拉伸"};

    private int aspectMode = MODE_FIT;
    private int lastVideoW;
    private int lastVideoH;

    /**
     * 按视频宽高比 + 当前模式设置 Surface 尺寸。
     *
     * 用 CENTER 定位而不是拉伸：拉伸会让人脸变形，竖屏短剧里特别明显。
     */
    private void applyAspect(int videoW, int videoH) {
        if (videoW <= 0 || videoH <= 0) {
            return;
        }
        lastVideoW = videoW;
        lastVideoH = videoH;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        float videoRatio = (float) videoW / videoH;
        float screenRatio = (float) screenW / screenH;

        int w;
        int h;
        switch (aspectMode) {
            case MODE_FILL:
                // 铺满：取能覆盖屏幕的最大尺寸，超出部分由父容器裁掉
                if (videoRatio > screenRatio) {
                    h = screenH;
                    w = (int) (screenH * videoRatio);
                } else {
                    w = screenW;
                    h = (int) (screenW / videoRatio);
                }
                break;
            case MODE_STRETCH:
                w = screenW;
                h = screenH;
                break;
            case MODE_FIT:
            default:
                // 适应：完整显示，不裁不拉
                if (videoRatio > screenRatio) {
                    w = screenW;
                    h = (int) (screenW / videoRatio);
                } else {
                    h = screenH;
                    w = (int) (screenH * videoRatio);
                }
                break;
        }
        Log.i(TAG, "画面 " + videoW + "x" + videoH + " 模式=" + MODE_NAMES[aspectMode]
                + " -> surface " + w + "x" + h);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    /** 切换画面比例模式。选择会记住，下次播放自动沿用。 */
    private void cycleAspect() {
        aspectMode = (aspectMode + 1) % MODE_NAMES.length;
        progress.saveAspectMode(aspectMode);
        if (lastVideoW > 0) {
            applyAspect(lastVideoW, lastVideoH);
        }
        setStatus("画面：" + MODE_NAMES[aspectMode]);
    }

    /** 计算续播起点。duration 未知时按 0 处理。 */
    private long resumeTarget(int epNum, long durationMs) {
        if(requestedStartPosition>=0){long at=requestedStartPosition;requestedStartPosition=-1;return durationMs>0?Math.min(at,Math.max(0,durationMs-300)):at;}
        return progress.resumeAt(seriesId, epNum, durationMs);
    }

    /** 进度条刷新。 */
    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (dur > 0) {
                        bar.setProgress((int) (pos * 1000L / dur));
                        timeView.setText(String.format(Locale.US, "%s / %s  %s×",
                                fmt(pos), fmt(dur), trimSpeed()));
                    }
                } catch (Throwable ignored) {
                }
                ui.postDelayed(this, 1000);
            }
        }
    };

    private static String fmt(int ms) {
        int s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String trimSpeed() {
        float f = SPEEDS[speedIndex];
        return f == Math.round(f) ? String.valueOf((int) f)
                : String.valueOf(f).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** 应用当前倍速。低于 Android 6 的 setPlaybackParams 不可用，忽略即可。 */
    private void applySpeed() {
        if (player == null || !prepared) {
            return;
        }
        try {
            boolean wasPlaying=player.isPlaying();
            android.media.PlaybackParams p = player.getPlaybackParams();
            p.setSpeed(SPEEDS[speedIndex]);
            player.setPlaybackParams(p);
            if(!wasPlaying)player.pause();
        } catch (Throwable e) {
            Log.w(TAG, "倍速设置失败", e);
        }
    }

    private void saveProgress() {
        if(player==null||!prepared||library==null||!playbackOwner.equals(library.owner()))return;
        try {
            cn.hongguo.tv.cache.WatchRecord r=new cn.hongguo.tv.cache.WatchRecord();
            r.seriesId=seriesId;r.title=seriesTitle==null?"":seriesTitle;r.cover=seriesCover;
            r.episode=episodes.get(index).num;r.vid=episodes.get(index).vid;r.total=episodes.size();
            r.position=Math.max(0,player.getCurrentPosition());r.duration=Math.max(0,player.getDuration());r.watchedAt=System.currentTimeMillis();
            library.save(r,forceFlush);
        }catch(RuntimeException e){Log.w(TAG,"本机观看位置保存失败",e);}
    }

    private void flushProgress(){forceFlush=true;try{saveProgress();}finally{forceFlush=false;}}

    // ------------------------------------------------------------ Control visibility

    private static final long OVERLAY_HIDE_MS = 3000;

    private final Runnable hideOverlay = () -> {
        if (overlay != null) {
            overlay.animate().alpha(0f).setDuration(250).start();
        }
    };

    /** 播放开始后隐藏叠加层；按任意键重新显示。 */
    private void scheduleHideOverlay() {
        ui.removeCallbacks(hideOverlay);
        // 暂停时不自动隐藏：用户暂停通常就是想看进度/切清晰度，
        // 3 秒后把界面藏起来反而碍事。
        if (player != null && prepared) {
            try {
                if (!player.isPlaying()) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        ui.postDelayed(hideOverlay, OVERLAY_HIDE_MS);
    }

    /** 显示叠加层并重置隐藏计时。 */
    private void showOverlay() {
        if (overlay != null) {
            overlay.animate().alpha(1f).setDuration(150).start();
        }
        scheduleHideOverlay();
    }

    private void fail(String msg) {
        setStatus("✗ " + msg);
    }

    private void setStatus(String s) {
        status.setText(s);
        Log.i(TAG, s);
    }

    private void releasePlayer() {
        prepared = false;
        ui.removeCallbacks(ticker);
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            player.release();
            player = null;
        }
    }

    // ------------------------------------------------------------ 清晰度 / 倍速

    /** 切下一档清晰度并从当前位置续播。 */
    private void cycleQuality() {
        capIndex = (capIndex + 1) % CAPS.length;
        getPreferences(0).edit().putInt("quality",capIndex).apply();
        long at = 0;
        try {
            if (player != null && prepared) {
                at = player.getCurrentPosition();
            }
        } catch (Throwable ignored) {
        }
        setStatus("切换到 " + CAP_NAMES[capIndex] + "…");
        updateQualityLabel();
        // 换档后缓存键变了，会重新下载解密；位置靠进度记忆带过去
        requestedStartPosition=at;
        play(index);
    }

    /** 刷新清晰度标签。actualDefinition 是实际选中的档位（可能因设备能力降级）。 */
    private void updateQualityLabel() {
        if (qualityView == null) {
            return;
        }
        String text = actualDefinition != null && !actualDefinition.isEmpty()
                ? actualDefinition : CAP_NAMES[capIndex];
        qualityView.setText("清晰度 " + text);
    }

    /** 切下一档倍速。 */
    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        getPreferences(0).edit().putInt("speed",speedIndex).apply();
        applySpeed();
        setStatus("倍速 " + trimSpeed() + "×");
    }

    // ------------------------------------------------------------ 收藏

    /**
     * 切换收藏状态。
     *
     * 未登录时直接提示去账号页登录 —— 收藏是云端行为，没有账号就没有
     * 可写的地方，静默失败会让用户以为收藏成功了。
     */
    private void toggleFavorite() {
        showOverlay();
        cn.hongguo.tv.account.Account a = cn.hongguo.tv.account.Account.load(this);
        if (!a.loggedIn()) {
            setStatus("登录后才能收藏");
            return;
        }
        if(favoriteBusy)return;
        favoriteBusy=true;
        final boolean target = !favorited;
        setStatus(target ? "正在加入收藏…" : "正在取消收藏…");
        Api.Series item=new Api.Series();item.seriesId=seriesId;item.title=seriesTitle;item.cover=seriesCover;item.episodeCount=episodes.size();
        library.favorite(item,target,failure->{favoriteBusy=false;if(isDestroyed())return;if(failure.isEmpty()){favorited=target;favoriteKnown=true;updateFavoriteLabel();setStatus(target?"已加入追剧":"已取消追剧");}else setStatus(failure);});
    }

    /** 读一次云端书架，确定当前剧是否已收藏。失败保持「收藏」文案（不谎报已收藏）。 */
    private void loadFavoriteState() {
        cn.hongguo.tv.account.Account a = cn.hongguo.tv.account.Account.load(this);
        if (!a.loggedIn()) {
            favoriteKnown = true;
            ui.post(this::updateFavoriteLabel);
            return;
        }
        new Thread(() -> {
            try {
                List<String> ids = account.favorites();
                favorited = ids != null && ids.contains(seriesId);
                favoriteKnown = true;
                ui.post(this::updateFavoriteLabel);
            } catch (Throwable e) {
                Log.d(TAG, "读取收藏状态失败（忽略）: " + e.getMessage());
            }
        }, "favorite-state").start();
    }

    private void updateFavoriteLabel() {
        if (favView == null) {
            return;
        }
        favView.setText(favorited ? "已收藏" : "收藏");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(4));
        // 已收藏用品牌红，未收藏用中性灰 —— 一眼能看出状态
        g.setColor(favorited ? 0xCC735B35 : 0xDD263750);
        favView.setBackground(g);
    }

    // ------------------------------------------------------------ 遥控器

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 任何按键都先唤出叠加层：用户操作时要能看见进度和状态
        showOverlay();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                showPlaybackMenu();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showEpisodePicker();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_M:
                showPlaybackMenu();
                return true;
            case KeyEvent.KEYCODE_S:
                cycleSpeed();
                return true;
            case KeyEvent.KEYCODE_F:
                toggleFavorite();
                return true;
            case KeyEvent.KEYCODE_A:
                // 画面比例：适应 / 填充 / 拉伸
                cycleAspect();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void showPlaybackMenu(){
        if(playbackMenu!=null&&playbackMenu.isShowing())return;
        String[] items={"选集 · 当前第 "+episodes.get(index).num+" 集","清晰度 · "+(actualDefinition.isEmpty()?CAP_NAMES[capIndex]:actualDefinition),"倍速 · "+trimSpeed()+"×","画面 · "+MODE_NAMES[aspectMode],autoplay?"关闭自动连播":"开启自动连播",favorited?"取消追剧":"加入追剧"};
        playbackMenu=new android.app.AlertDialog.Builder(this).setTitle("播放设置").setItems(items,(d,which)->{switch(which){case 0:showEpisodePicker();break;case 1:cycleQuality();break;case 2:cycleSpeed();break;case 3:cycleAspect();break;case 4:autoplay=!autoplay;getPreferences(0).edit().putBoolean("autoplay",autoplay).apply();setStatus(autoplay?"自动连播已开启":"自动连播已关闭");break;case 5:toggleFavorite();break;}}).setNegativeButton("返回播放",null).show();
    }
    private void showEpisodePicker(){
        String[] labels=new String[episodes.size()];for(int i=0;i<labels.length;i++)labels[i]="第 "+episodes.get(i).num+" 集";
        new android.app.AlertDialog.Builder(this).setTitle("选集").setSingleChoiceItems(labels,index,(d,which)->{d.dismiss();requestedStartPosition=0;play(which);}).setNegativeButton("返回播放",null).show();
    }

    private void togglePause() {
        if (player == null || !prepared) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                setStatus("已暂停");
                flushProgress();
            } else {
                player.start();
                setStatus("播放中");
            }
        } catch (Throwable e) {
            Log.w(TAG, "暂停切换失败", e);
        }
    }

    private void seekBy(int deltaMs) {
        if (player == null || !prepared) {
            return;
        }
        try {
            int pos = player.getCurrentPosition();
            int dur = player.getDuration();
            int target = Math.max(0, Math.min(dur, pos + deltaMs));
            player.seekTo(target);
            // 只更新时间那一行，不要覆盖 status。
            // 早先这里调 setStatus(时间)，会和 timeView 的时间叠在一起，
            // 出现「1:22 / 1:29  1:24 / 1:29」这种重复显示。
            timeView.setText(String.format(Locale.US, "%s / %s  %s×",
                    fmt(target), fmt(dur), trimSpeed()));
        } catch (Throwable e) {
            Log.w(TAG, "seek 失败", e);
        }
    }

    // ------------------------------------------------------------ Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (pendingFile != null) {
            File f = pendingFile;
            pendingFile = null;
            startPlayback(f, episodes.get(index).num);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onPause() {
        foreground=false;
        super.onPause();
        flushProgress();
        ui.removeCallbacks(saveTick);
        if (player != null && prepared) {
            try {
                player.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground=true;
        if(library!=null&&!playbackOwner.equals(library.owner())){finish();return;}
        ui.postDelayed(saveTick, SAVE_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        super.onDestroy();
        flushProgress();
        ui.removeCallbacks(saveTick);
        ui.removeCallbacks(ticker);
        releasePlayer();
        new Thread(() -> cache.trim(), "cache-trim").start();
    }

    // ------------------------------------------------------------ 入参

    private void parseEpisodes(String json) {
        if (json == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Ep e = new Ep();
                e.vid = o.optString("vid");
                e.num = o.optInt("num", i + 1);
                e.title = o.optString("title");
                if (!e.vid.isEmpty()) {
                    episodes.add(e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "集列表解析失败", e);
        }
    }

    /** 集号 → 列表下标。找不到返回 0。 */
    private int indexOfEpisode(int num) {
        for (int i = 0; i < episodes.size(); i++) {
            if (episodes.get(i).num == num) {
                return i;
            }
        }
        return 0;
    }

    /** 供其它页面跳转用。 */
    public static Intent intent(android.content.Context ctx, String seriesId,
                                String title, String episodesJson) {
        return intent(ctx, seriesId, title, "", episodesJson);
    }

    /** 带封面的跳转：云端历史有封面，手机端列表才有图。 */
    public static Intent intent(android.content.Context ctx, String seriesId,
                                String title, String cover, String episodesJson) {
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.putExtra(EXTRA_SERIES_ID, seriesId);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_COVER, cover == null ? "" : cover);
        i.putExtra(EXTRA_EPISODES, episodesJson);
        return i;
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
