package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.AccountApi;
import cn.hongguo.tv.api.Api;

/**
 * 账号页：验证码登录 / 收藏 / 观看历史。
 *
 * 三段式布局（未登录时只显示第一段）：
 *   1. 账号卡：头像 + 昵称/手机号 + 登录/退出
 *   2. 收藏：云端书架，点开即可播放
 *   3. 历史：云端观看记录，点开从上次那一集续播
 *
 * 为什么历史和收藏要读云端而不是本地：
 *   手机端看过的剧必须在这里也能看到（用户对「历史」的预期是跨端的）。
 *   本地的 PlayProgress 只记「这台设备看到哪」，云端才是全局真相。
 *   播放时同时写两边：本地保证离线续播，云端保证跨端同步。
 */
public class AccountActivity extends TvActivity {

    private static final String TAG = "HgAccount";

    private AccountApi account;
    private Api api;

    private LinearLayout accountCard;
    private LinearLayout loginForm;
    private LinearLayout lists;

    private EditText mobileInput;
    private EditText codeInput;
    private Button sendCodeBtn;
    private Button loginBtn;
    private TextView statusView;
    private View accountBack;
    private boolean loggedInUi;

    private cn.hongguo.tv.cache.LibraryRepository library;
    private final Runnable libraryUpdated=()->{if(!isDestroyed()&&cn.hongguo.tv.account.Account.load(this).loggedIn())loadLists();};
    private String mobileTicket = "";
    private int countdown = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        account = new AccountApi(this);
        library = cn.hongguo.tv.cache.LibraryRepository.get(this);
        api = new Api(this);
        // 兜底：签名的 Context 由 Application 绑定，但如果是被别的方式
        // 拉起的进程（极少数老系统），这里再绑一次不花代价。
        cn.hongguo.tv.sign.RequestSigner.bind(this);
        buildUi();
        refresh();
    }

    // --------------------------------------------------------------- 布局

    private void buildUi() {
        // 存成字段：showKeypad/hideKeypad 需要改它的 padding，
        // 而那两个方法在 buildUi 之外调用。
        pageSv = new ScrollView(this);
        final ScrollView sv = pageSv;
        sv.setBackgroundColor(TvUi.BG);
        sv.setFocusable(false);
        sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 上下留白从 32dp 压到 14dp：账号页未登录时要一屏放下
        // 标题 + 账号卡 + 手机号/验证码 + 内置数字键盘。
        // 原来的 32dp 让键盘最后一行永远在屏幕外（电视上等于不可用）。
        root.setPadding(dp(48), dp(14), dp(48), dp(14));
        sv.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // 标题栏高度从默认按钮高度（约 96px）压到 40dp：
        // 这一行的存在感很低，省下的高度刚好够键盘最后一行。
        top.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        Button back = new Button(this);
        back.setTag("account-back");
        back.setId(View.generateViewId());
        back.setFocusableInTouchMode(true);
        accountBack = back;
        back.setText("返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        back.setAllCaps(false);
        back.setPadding(dp(16), 0, dp(16), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(96), dp(34)));
        TextView title = new TextView(this);
        title.setText("账号与同步");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPadding(dp(16), 0, 0, 0);
        top.addView(title);

        // 「检查更新」放在顶栏：它对未登录用户同样重要 —— 恰恰是登录
        // 出问题时最需要升级。之前它挂在「已登录」卡片里，未登录时
        // 整个按钮不存在，用户没法自救。
        LinearLayout spacer = new LinearLayout(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        Button upd = new Button(this);
        upd.setText("检查更新");
        upd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        upd.setAllCaps(false);
        upd.setPadding(dp(14), 0, dp(14), 0);
        upd.setOnClickListener(v -> checkUpdate());
        top.addView(upd, new LinearLayout.LayoutParams(dp(140), dp(34)));
        root.addView(top);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFB0B0B0);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // 状态行只在有话说时占高度；空着时不留白，把纵向空间让给键盘。
        statusView.setPadding(dp(4), dp(6), 0, dp(2));
        root.addView(statusView);

        accountCard = new LinearLayout(this);
        accountCard.setOrientation(LinearLayout.VERTICAL);
        root.addView(accountCard);

        loginForm = new LinearLayout(this);
        loginForm.setOrientation(LinearLayout.VERTICAL);
        // 顶部不再留白：标题栏到手机号之间原来是 10dp + status 的 padding，
        // 叠起来约 40dp，正好是键盘最后一行的位置。
        loginForm.setPadding(0, 0, 0, 0);
        root.addView(loginForm);

        lists = new LinearLayout(this);
        lists.setOrientation(LinearLayout.VERTICAL);
        root.addView(lists);

        // 内层 ScrollView（滚动）+ 外层固定底部键盘。
        //
        // 为什么要拆两层：键盘弹出时必须完整可见 —— 它是唯一的输入手段。
        // 放进滚动区时，只要上方内容高一点最后一行就掉出屏幕，遥控器按
        // 「下」能走到但看不见（实测踩了三次，分别剩 2 行、1 行、0 行）。
        // 固定在外层底部后，滚动区自己缩，键盘永远完整。
        //
        // 键盘默认**隐藏**：只在焦点进入手机号/验证码输入框、或点了键盘
        // 按钮时才显示。常驻会一直占掉底部三分之一，看收藏/历史时很碍事。
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(TvUi.BG);
        page.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 键盘占位：底部固定，高度 wrap_content，初始不可见
        keypadHost = new LinearLayout(this);
        keypadHost.setOrientation(LinearLayout.VERTICAL);
        keypadHost.setBackgroundColor(TvUi.SURFACE);
        keypadHost.setPadding(dp(48), dp(8), dp(48), dp(10));
        keypadHost.setVisibility(View.GONE);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.gravity = Gravity.BOTTOM;
        page.addView(keypadHost, klp);
        // 滚动区底部留白：键盘收起时不留（否则内容下面凭空多一块），
        // 展开时由 showKeypad() 补上。
        sv.setClipToPadding(false);
        setContentView(page);
        buildLoginForm();
    }

    /** 屏幕底部固定键盘的容器（不随内容滚动）。 */
    private LinearLayout keypadHost;

    /** 内容滚动区（键盘展开时要在底部留白，避免内容被键盘盖住）。 */
    private ScrollView pageSv;

    /** 键盘是否正在显示。 */
    private boolean keypadShown;

    /** 键盘展开时滚动区要留出的底部空间（实测键盘高约 165dp + 余量）。 */
    private static final int KEYPAD_INSET_DP = 200;

    /**
     * 显示键盘。
     *
     * @param target 0=手机号 1=验证码；<0 表示只显示、不切换目标
     */
    private void showKeypad(int target) {
        if (Account.load(this).loggedIn() || loginForm.getVisibility() != View.VISIBLE) {
            clearKeypad();
            return;
        }
        if (target >= 0) {
            selectKeypadTarget(target);
        }
        if (keypadShown) {
            return;
        }
        keypadShown = true;
        keypadHost.animate().cancel();
        keypadHost.setVisibility(View.VISIBLE);
        keypadHost.setTranslationY(dp(240));
        keypadHost.animate().translationY(0).setDuration(160).start();
        if (pageSv != null) pageSv.setPadding(0, 0, 0, dp(KEYPAD_INSET_DP));
    }

    /** Hide deterministically; focusing an EditText here would immediately reopen the keypad. */
    private void hideKeypad() {
        boolean hadFocus = keypadHost.hasFocus();
        clearKeypad();
        if (!hadFocus) return;
        if (Account.load(this).loggedIn()) focusAccountAction();
        else if (loginBtn != null && loginBtn.isShown()) loginBtn.requestFocus();
        else if (accountBack != null) accountBack.requestFocus();
    }

    private void clearKeypad() {
        keypadShown = false;
        if (keypadHost != null) {
            keypadHost.animate().cancel();
            keypadHost.clearFocus();
            keypadHost.setVisibility(View.GONE);
            keypadHost.setTranslationY(0);
        }
        if (pageSv != null) pageSv.setPadding(0, 0, 0, 0);
    }

    private void focusAccountAction() {
        View action = lists == null ? null : lists.findViewWithTag("account-history");
        if (action != null && action.isShown()) action.requestFocus();
        else if (accountBack != null) accountBack.requestFocus();
    }

    private void buildLoginForm() {
        // 手机号行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        mobileInput = new EditText(this);
        mobileInput.setHint("手机号");
        mobileInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mobileInput.setSingleLine(true);
        mobileInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        mobileInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        // 不弹系统输入法：改用下面的内置数字面板。
        //
        // 为什么必须自己画：电视/盒子的系统输入法要么根本没有，要么是
        // 遥控器逐字符在九宫格里挪（输 11 位手机号要按几十下），要么是
        // 厂商定制的残缺键盘。电视上的通行做法就是 App 自己给数字面板。
        disableSoftKeyboard(mobileInput);
        // 焦点/点击进入手机号框 → 自动弹出键盘
        mobileInput.setOnFocusChangeListener((v, has) -> {
            if (has) {
                showKeypad(0);
            }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        mlp.setMargins(0, 0, dp(16), 0);
        row.addView(mobileInput, mlp);
        loginForm.addView(row);

        // 验证码行
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2lp.setMargins(0, dp(10), 0, 0);
        row2.setLayoutParams(r2lp);

        codeInput = new EditText(this);
        codeInput.setHint("验证码");
        codeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        disableSoftKeyboard(codeInput);
        codeInput.setOnFocusChangeListener((v, has) -> {
            if (has) {
                showKeypad(1);
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(200), dp(48));
        clp.setMargins(0, 0, dp(12), 0);
        row2.addView(codeInput, clp);

        sendCodeBtn = new Button(this);
        sendCodeBtn.setText("获取验证码");
        sendCodeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sendCodeBtn.setAllCaps(false);
        sendCodeBtn.setOnClickListener(v -> doSendCode());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(130), dp(48));
        slp.setMargins(0, 0, dp(12), 0);
        row2.addView(sendCodeBtn, slp);

        loginBtn = new Button(this);
        loginBtn.setText("登录");
        loginBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        loginBtn.setAllCaps(false);
        loginBtn.setOnClickListener(v -> doLogin());
        row2.addView(loginBtn, new LinearLayout.LayoutParams(dp(110), dp(48)));

        loginForm.addView(row2);

        // 内置数字面板：固定贴在屏幕底部（不随上方内容滚动）
        buildKeypad();

        TextView hint = new TextView(this);
        hint.setText("登录后可在手机与电视之间同步收藏和观看进度。不登录也能正常看剧。");
        hint.setTextColor(0xFF7A7A7A);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setPadding(dp(2), dp(12), 0, 0);
        loginForm.addView(hint);

        // 手动打开键盘的入口。
        //
        // 键盘默认隐藏（常驻会一直占掉底部三分之一，看收藏/历史时很碍事）。
        // 聚焦输入框会自动弹出，但电视上用户可能用方向键跳到别处再想回来，
        // 给一个显式按钮最稳。
        Button openKeypad = new Button(this);
        openKeypad.setText("输入号码");
        openKeypad.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        openKeypad.setAllCaps(false);
        openKeypad.setOnClickListener(v -> showKeypad(0));
        LinearLayout.LayoutParams okp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okp.setMargins(0, dp(10), 0, 0);
        loginForm.addView(openKeypad, okp);

        // 备用登录方式：从电脑版导入。
        //
        // 「从电脑版导入登录」保留为**可选的旁路**（不是必需）：
        // 验证码登录本身已经能用，但有些账号会要求 2046 身份验证，
        // 那条链路 TV 端还没实现，导入电脑版已登录的会话可以绕过去。
        Button importBtn = new Button(this);
        importBtn.setText("导入旧版账号备份（可选）");
        importBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        importBtn.setAllCaps(false);
        importBtn.setOnClickListener(v -> showImportDialog());
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibp.setMargins(0, dp(10), 0, 0);
        loginForm.addView(importBtn, ibp);
    }

    /**
     * 导入电脑版登录态。
     *
     * 电脑版把会话存在
     *   %APPDATA%\cn.guoban.desktop-companion\desktop-account.json
     * 里，字段是 cookie / uid / user_name / mobile。这里让用户把那个文件
     * 的内容整段粘进来（电视上打字不方便，但只需要粘一次）。
     */
    private void showImportDialog() {
        final EditText input = new EditText(this);
        input.setHint("粘贴已有的旧版账号备份（JSON）");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导入旧版账号备份")
                .setMessage("仅适用于有旧版账号备份的用户。新版 PC 的登录信息由 Windows 加密保存，电视端可直接通过手机号登录同一账号。")
                .setView(input)
                .setPositiveButton("导入", (d, w) -> doImport(input.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) {
            setStatus("没有内容");
            return;
        }
        try {
            JSONObject j = new JSONObject(raw);
            String cookie = j.optString("cookie", "");
            if (cookie.isEmpty()) {
                setStatus("内容里没有 cookie，请确认复制的是桌面版账号文件");
                return;
            }
            String uid = j.optString("uid", "");
            String name = j.optString("user_name", "");
            String mobile = j.optString("mobile", "");
            Account.save(this, cookie, "", uid, name, mobile);
            setStatus("导入成功");
            refresh();
            library.sync(true);
        } catch (Throwable e) {
            setStatus("导入失败：格式不对（应是以 { 开头的 JSON）");
        }
    }

    // --------------------------------------------------------------- 状态

    private void refresh() {
        Account a = Account.load(this);
        View focus = getCurrentFocus();
        boolean moveFocus = a.loggedIn() && (!loggedInUi || isInside(focus, loginForm)
                || isInside(focus, keypadHost));
        loggedInUi = a.loggedIn();
        accountCard.removeAllViews();
        if (a.loggedIn()) {
            loginForm.setVisibility(View.GONE);
            mobileInput.setFocusable(false);
            codeInput.setFocusable(false);
            mobileInput.clearFocus();
            codeInput.clearFocus();
            clearKeypad();
            codeInput.setText("");
            accountCard.addView(loggedInCard(a));
            loadLists();
            if (moveFocus) lists.post(() -> { if (!isDestroyed() && loggedInUi) focusAccountAction(); });
        } else {
            mobileInput.setFocusable(true);
            mobileInput.setFocusableInTouchMode(true);
            codeInput.setFocusable(true);
            codeInput.setFocusableInTouchMode(true);
            loginForm.setVisibility(View.VISIBLE);
            // 未登录不显示账号卡：一屏要放下表单 + 内置数字键盘，
            // 「未登录」这三个字不值得占掉一整行。
            lists.removeAllViews();
        }
    }

    private View anonymousCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        // 未登录卡片就是个「你是谁」的提示，不值得占高度：
        // 整块隐藏，把纵向空间全部让给输入表单 + 内置数字键盘。
        // 未登录时顶栏已经写着「账号」，用户不会分不清状态。
        box.setVisibility(View.GONE);
        return box;
    }

    private View loggedInCard(Account a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));

        String n = a.displayName();
        box.addView(avatarBubble(n.isEmpty() ? "我" : n.substring(0, 1)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        TextView name = new TextView(this);
        name.setText(n);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(name);

        String m = Account.maskMobile(a.mobile);
        if (!m.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(m);
            sub.setTextColor(0xFF8A8A8A);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            text.addView(sub);
        }
        box.addView(text);

        LinearLayout spacer = new LinearLayout(this);
        box.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        Button logout = new Button(this);
        logout.setTag("account-logout");
        logout.setId(View.generateViewId());
        logout.setFocusableInTouchMode(true);
        logout.setText("退出登录");
        logout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logout.setAllCaps(false);
        logout.setOnClickListener(v -> doLogout());
        box.addView(logout);
        return box;
    }

    /** 禁止 EditText 弹出系统输入法（电视上用内置面板代替）。 */
    private void disableSoftKeyboard(EditText e) {
        try {
            e.setShowSoftInputOnFocus(false);
        } catch (Throwable ignored) {
            // 低版本没有这个方法，靠 setInputType 兜底
        }
        // 点输入框时**只切换输入目标**，不要把焦点抢走。
        //
        // 早先这里调用 focusKeypad() 把焦点送到第一个数字键，结果是：
        // 用户点「手机号」框 → 焦点跳到键盘 → 之后用 adb/遥控器直接打字
        // 时字符进了 EditText 但光标不在，看起来像「没输进去」。
        // 正确做法是保持 EditText 可聚焦、由它接收字符，键盘上的点击
        // 负责追加（onKeypadKey 直接改 setText，不依赖焦点）。
        e.setOnClickListener(v -> {
            if (e == mobileInput) {
                showKeypad(0);
            } else if (e == codeInput) {
                showKeypad(1);
            }
        });
    }

    /** 当前内置键盘输入到哪个框：0=手机号 1=验证码。 */
    private int keypadTarget;

    /** 内置数字键（按遥控器方向键移动）。 */
    private final java.util.List<TextView> keypadKeys = new java.util.ArrayList<>();

    /**
     * 内置数字面板：0-9 + 删除 + 清空。
     *
     * 布局 3 列，方向键左右在键之间移动、上下跨行；面板上方两个按钮把
     * 输入目标切到「手机号 / 验证码」。这是电视端输入数字的通行做法：
     * 系统输入法在盒子上基本不可用。
     */
    private void buildKeypad() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, 0);

        // 目标切换行
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        TextView tm = keypadTargetBtn("输入手机号", 0);
        TextView tc = keypadTargetBtn("输入验证码", 1);
        tabs.addView(tm, keypadTabLp());
        tabs.addView(tc, keypadTabLp());
        keypadTargetBtns[0] = tm;
        keypadTargetBtns[1] = tc;

        // 收起键盘：放在切换按钮同一行的最右。
        // 电视上没有「返回键收起输入法」这种约定（我们用的是自绘面板），
        // 必须给一个明确的关闭入口，否则键盘只能靠离开输入框才消失。
        TextView close = new TextView(this);
        close.setText("收起");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        close.setTextColor(0xFFCFCFCF);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setClickable(true);
        close.setBackground(TvFocus.stateBg(this, 8,
                0xFF2A2A2A, 0xFF3A2020, 0xFF3A3A3A, TvFocus.ACCENT));
        close.setOnClickListener(v -> hideKeypad());
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(dp(88), dp(28));
        clp2.setMargins(0, 0, 0, dp(6));
        tabs.addView(close, clp2);
        keypadClose = close;

        box.addView(tabs);

        // 数字键盘：5 列 2 行（0-9），第 3 行放 清空/删除/确定
        //
        // 为什么 5 列：电视是 16:9 宽屏，横向空间富余、纵向极其紧张。
        // 实测 1080p 下手机号+验证码两行表单已经吃掉约 55% 高度，
        // 留给键盘的只有 ~3 行。5 列 2 行 + 1 行操作键刚好放得下；
        // 3 列 4 行会让最后一行永远在屏幕外（遥控器能走到但看不见）。
        String[][] rows = {
                {"1", "2", "3", "4", "5"},
                {"6", "7", "8", "9", "0"},
                {"清空", "删除", "确定"},
        };
        final java.util.List<java.util.List<View>> grid = new java.util.ArrayList<>();
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            final java.util.List<View> cells = new java.util.ArrayList<>();
            for (String label : r) {
                TextView k = keypadKey(label);
                row.addView(k, keypadKeyLp());
                keypadKeys.add(k);
                cells.add(k);
            }
            box.addView(row);
            grid.add(cells);
        }
        // 显式焦点图：方向键在键盘内按列网格走（最后一行短，用 min 兜住）
        for (int r = 0; r < grid.size(); r++) {
            for (int c = 0; c < grid.get(r).size(); c++) {
                View v = grid.get(r).get(c);
                View left = grid.get(r).get(Math.max(0, c - 1));
                View right = grid.get(r).get(Math.min(grid.get(r).size() - 1, c + 1));
                View up = r > 0
                        ? grid.get(r - 1).get(Math.min(c, grid.get(r - 1).size() - 1))
                        : tm;
                View down = r + 1 < grid.size()
                        ? grid.get(r + 1).get(Math.min(c, grid.get(r + 1).size() - 1))
                        : null;
                TvFocus.ensureId(v, 0x7F100000 + r * 10 + c);
                v.setNextFocusLeftId(left.getId());
                v.setNextFocusRightId(right.getId());
                v.setNextFocusUpId(up.getId());
                if (down != null) {
                    v.setNextFocusDownId(down.getId());
                }
            }
        }
        // 目标切换按钮：向上回到输入框，向下进键盘第一行
        TvFocus.ensureId(tm, 0x7F0FF001);
        TvFocus.ensureId(tc, 0x7F0FF002);
        tm.setNextFocusRightId(tc.getId());
        tc.setNextFocusLeftId(tm.getId());
        tm.setNextFocusDownId(grid.get(0).get(0).getId());
        tc.setNextFocusDownId(grid.get(0).get(2).getId());
        tm.setNextFocusUpId(mobileInput.getId());
        tc.setNextFocusUpId(codeInput.getId());

        keypadHost.addView(box);
        selectKeypadTarget(0);
    }
    private final TextView[] keypadTargetBtns = new TextView[2];

    /** 键盘上的「收起」按钮。 */
    private TextView keypadClose;

    private TextView keypadTargetBtn(String text, final int idx) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> selectKeypadTarget(idx));
        return t;
    }

    private LinearLayout.LayoutParams keypadTabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(28));
        lp.setMargins(0, 0, dp(8), dp(6));
        return lp;
    }

    private TextView keypadKey(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(TvFocus.stateBg(this, 8,
                0xFF1C1C1C, 0xFF3A2020, 0xFF2A2A2A, TvFocus.ACCENT));
        t.setOnFocusChangeListener((v, has) -> v.setElevation(has ? dp(6) : 0));
        t.setOnClickListener(v -> onKeypadKey(label));
        return t;
    }

    private LinearLayout.LayoutParams keypadKeyLp() {
        // 高度 34dp：这是「全部内容一屏放下」的实测边界。
        // 手机号/验证码两行表单 + 三行键盘 + 标题栏，1080p 下总高必须
        // <= 安全区高度（约 620dp）。34dp × 3 行 = 102dp 刚好。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(74), dp(34));
        lp.setMargins(0, 0, dp(6), dp(5));
        return lp;
    }

    private void selectKeypadTarget(int idx) {
        keypadTarget = idx;
        for (int i = 0; i < keypadTargetBtns.length; i++) {
            if (keypadTargetBtns[i] == null) {
                continue;
            }
            boolean on = i == idx;
            keypadTargetBtns[i].setTextColor(on ? Color.WHITE : 0xFF8A8A8A);
            keypadTargetBtns[i].setBackground(TvFocus.stateBg(this, 8,
                    on ? 0xFF2C1414 : 0x00000000,
                    on ? 0xFF3A2020 : 0x00000000,
                    on ? TvFocus.ACCENT : 0x00000000,
                    TvFocus.ACCENT));
        }
        // 只更新高亮，不主动 requestFocus。
        //
        // 早先这里会 requestFocus 到对应输入框，而输入框的 onFocusChange
        // 又会调 showKeypad(0/1) —— 形成「选目标 → 请求焦点 → 触发聚焦 →
        // 再选目标」的递归，键盘会在显示/隐藏之间抖动。
        // 焦点由用户自己用方向键或点击控制，这里只负责高亮与 keypadTarget。
    }

    /** 把焦点送进键盘（点输入框时调用，避免焦点停在不可编辑的框上）。 */
    private void focusKeypad() {
        if (!keypadKeys.isEmpty()) {
            keypadKeys.get(0).requestFocus();
        }
    }

    private void onKeypadKey(String label) {
        if (Account.load(this).loggedIn() || !loginForm.isShown()) { clearKeypad(); return; }
        EditText target = keypadTarget == 0 ? mobileInput : codeInput;
        if (target == null) {
            return;
        }
        String cur = target.getText().toString();
        if ("确定".equals(label)) {
            // 遥控器上按「确定」直接提交登录，省得再挪到右侧按钮
            doLogin();
            return;
        }
        if ("删除".equals(label)) {
            if (!cur.isEmpty()) {
                target.setText(cur.substring(0, cur.length() - 1));
                target.setSelection(target.getText().length());
            }
            return;
        }
        if ("清空".equals(label)) {
            target.setText("");
            return;
        }
        // 手机号最多 11 位，验证码 8 位；到上限就不再追加
        int max = keypadTarget == 0 ? 11 : 8;
        if (cur.length() >= max) {
            setStatus(keypadTarget == 0 ? "手机号已满 11 位" : "验证码最多 8 位");
            return;
        }
        target.setText(cur + label);
        target.setSelection(target.getText().length());
        // 手机号填满 11 位自动跳到验证码
        if (keypadTarget == 0 && target.getText().length() == 11) {
            selectKeypadTarget(1);
        }
    }

    private TextView avatarBubble(String ch) {
        TextView av = new TextView(this);
        av.setText(ch);
        av.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        av.setTextColor(Color.WHITE);
        av.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(TvFocus.ACCENT);
        av.setBackground(g);
        av.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return av;
    }

    private void setStatus(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> statusView.setText(s));
    }

    // --------------------------------------------------------------- 登录

    private void doSendCode() {
        String mobile = mobileInput.getText().toString().trim();
        if (!mobile.matches("1\\d{10}")) {
            setStatus("请输入正确的手机号");
            return;
        }
        sendCodeBtn.setEnabled(false);
        setStatus("正在发送验证码…");
        new Thread(() -> {
            try {
                JSONObject j = account.sendCode(mobile);
                JSONObject data = j.optJSONObject("data");
                mobileTicket = data == null ? "" : data.optString("mobile_ticket", "");
                // 护照信封是 {"message":"success","data":{...}}，
                // 失败时 data 里才有 error_code/description。
                // 直接读顶层 description 会拿到空串、再退回 message
                // 拿到字面量 "error"（实测就是这个显示）。
                int errCode = data == null ? 0 : data.optInt("error_code", 0);
                String shown;
                if (errCode != 0) {
                    shown = data.optString("description", "");
                    // error_code=7 是「客户端判定异常」，但服务端文案会伪装成
                    // 「访问太频繁」。实测（MuMu / 2026-11-04）：同一台设备、
                    // 同一组字段，用随包 so 签名就恒等于 7，用桌面端签名服务
                    // 就正常下发。所以它不是频率问题，换号、等多久都不会好。
                    // 直接把结论告诉用户，省得他一直重试。
                    if (errCode == 7) {
                        shown = "本机组件未通过服务端校验，暂时无法发送验证码"
                                + "（换号、重试都无效）。请先升级到最新版本。";
                    } else if (shown.isEmpty()) {
                        shown = "发送失败（错误码 " + errCode + "）";
                    } else if (errCode == 1206) {
                        shown = "这个号码发送太频繁，请稍后再试"
                                + "（或换一个号码）";
                    }
                } else {
                    shown = "验证码已发送";
                    if (data != null && data.optInt("retry_time", 0) > 0) {
                        shown += "，请留意短信";
                    }
                }
                final String msg = shown;
                final boolean sent = errCode == 0;
                runOnUiThread(() -> {
                    setStatus(msg);
                    if (sent) {
                        startCountdown();
                        codeInput.requestFocus();
                    } else {
                        sendCodeBtn.setEnabled(true);
                    }
                });
            } catch (Throwable e) {
                Log.w(TAG, "发码失败", e);
                runOnUiThread(() -> {
                    setStatus("发送失败：" + e.getMessage());
                    sendCodeBtn.setEnabled(true);
                });
            }
        }, "send-code").start();
    }

    private void startCountdown() {
        countdown = 60;
        sendCodeBtn.setText(countdown + "s");
        sendCodeBtn.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (countdown <= 0) {
                    sendCodeBtn.setEnabled(true);
                    sendCodeBtn.setText("获取验证码");
                    return;
                }
                countdown--;
                sendCodeBtn.setText(countdown + "s");
                sendCodeBtn.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void doLogin() {
        String mobile = mobileInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (!mobile.matches("1\\d{10}")) {
            setStatus("请输入正确的手机号");
            return;
        }
        if (!code.matches("\\d{4,8}")) {
            setStatus("请输入验证码");
            return;
        }
        loginBtn.setEnabled(false);
        setStatus("登录中…");
        new Thread(() -> {
            try {
                JSONObject j = account.smsLogin(mobile, code, mobileTicket);
                boolean ok = j.optInt("code", -1) == 0
                        || "success".equalsIgnoreCase(j.optString("message", ""));
                JSONObject data = j.optJSONObject("data");
                if (data != null && data.optInt("error_code", 0) != 0) {
                    ok = false;
                }
                if (ok) {
                    runOnUiThread(() -> {
                        setStatus("登录成功");
                        refresh();
                        library.sync(true);
                    });
                } else {
                    JSONObject data2 = j.optJSONObject("data");
                    int err = data2 == null ? 0 : data2.optInt("error_code", 0);
                    String why = data2 == null ? "" : data2.optString("description", "");
                    if (err == 7) {
                        why = "本机组件未通过服务端校验，登录被拒。"
                                + "请先升级到最新版本。";
                    } else if (why.isEmpty()) {
                        why = j.optString("message", "登录失败");
                    }
                    final String msg = why;
                    runOnUiThread(() -> {
                        setStatus(msg);
                        loginBtn.setEnabled(true);
                    });
                }
            } catch (Throwable e) {
                Log.w(TAG, "登录失败", e);
                runOnUiThread(() -> {
                    setStatus("登录失败：" + e.getMessage());
                    loginBtn.setEnabled(true);
                });
            }
        }, "sms-login").start();
    }

    private void doLogout() {
        Account.clear(this);
        if(library!=null)library.sync(false);
        setStatus("已退出登录");
        refresh();
    }

    // ------------------------------------------------------------ 收藏/历史

    private void loadLists() {
        if(library==null||isDestroyed())return;
        View focused=getCurrentFocus();Object tag=focused==null?null:focused.getTag();
        boolean restore=isInside(focused,lists);
        int scrollY=pageSv.getScrollY();
        lists.removeAllViews();
        TextView summary=TvUi.text(this,"本机保存 "+library.store().count(library.owner())+" 部观看记录 · "+(library.syncing()?"正在同步":"联网后自动同步"),15,TvUi.DIM);
        summary.setPadding(0,dp(18),0,dp(14));lists.addView(summary);
        LinearLayout entries=TvUi.row(this);
        TextView history=TvUi.button(this,"完整观看历史",false,()->startActivity(LibraryActivity.intent(this,false)));
        history.setTag("account-history");
        entries.addView(history,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView favorites=TvUi.button(this,"我的追剧",false,()->startActivity(LibraryActivity.intent(this,true)));
        favorites.setTag("account-favorites");
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(12),0,0,0);entries.addView(favorites,p);
        TextView sync=TvUi.button(this,"立即同步",true,()->library.sync(true));sync.setTag("account-sync");p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(12),0,0,0);entries.addView(sync,p);lists.addView(entries);
        if(!library.error().isEmpty()){TextView error=TvUi.text(this,library.error(),14,TvUi.ACCENT);error.setPadding(0,dp(12),0,0);lists.addView(error);}
        TvFocus.linkRows(java.util.Collections.singletonList(java.util.Arrays.asList(history,favorites,sync)));
        View logout=accountCard.findViewWithTag("account-logout");
        for(View action:java.util.Arrays.asList(history,favorites,sync))action.setNextFocusUpId(logout==null?accountBack.getId():logout.getId());
        if(logout!=null)logout.setNextFocusDownId(sync.getId());
        if(restore&&tag!=null)lists.post(()->{if(isDestroyed())return;View action=lists.findViewWithTag(tag);if(action!=null)action.requestFocus();pageSv.scrollTo(0,scrollY);});
    }

    private View sectionTitle(String text, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(10));

        View bar = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(TvFocus.ACCENT);
        g.setCornerRadius(dp(2));
        bar.setBackground(g);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(4), dp(20));
        blp.setMargins(0, 0, dp(10), 0);
        row.addView(bar, blp);

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        row.addView(t);

        TextView c = new TextView(this);
        c.setText("  " + count);
        c.setTextColor(0xFF7A7A7A);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(c);
        return row;
    }

    /**
     * 收藏网格：竖版海报 + 标题。
     *
     * 云端书架只回 series_id，标题/封面由 {@link Api#seriesMeta} 批量补齐。
     * 补不到的（上游对该 id 回空，通常是已下架）退化成「编号」卡片，
     * 仍然可点 —— 点击后取集失败会给出提示，不会静默无反应。
     */
    private View favoriteGrid(List<String> ids, java.util.Map<String, Api.SeriesMeta> meta) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        int perRow = 6;
        LinearLayout row = null;
        for (int i = 0; i < ids.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                col.addView(row);
            }
            String sid = ids.get(i);
            Api.SeriesMeta m = meta == null ? null : meta.get(sid);
            row.addView(favoriteCard(sid, m));
        }
        return col;
    }

    /** 单张收藏卡片：海报 + 两行标题。尺寸与首页网格同量级。 */
    private View favoriteCard(String sid, Api.SeriesMeta m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(6), dp(6), dp(8));
        // 复用首页的卡片焦点样式（红描边 + 抬层级，不缩放）
        box.setFocusable(true);
        box.setClickable(true);
        box.setBackground(TvFocus.stateBg(this, 8,
                0xFF1C1C1C, 0xFF3A2020, 0xFF2A2A2A, TvFocus.ACCENT));
        box.setOnFocusChangeListener((v, has) -> v.setElevation(has ? dp(10) : 0));

        int h = dp(150);
        int w = (int) (h * 0.72);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF2A2A2A);
        String cover = m == null ? "" : m.cover;
        ImageLoader.load(img, cover);
        box.addView(img, new LinearLayout.LayoutParams(w, h));

        TextView t = new TextView(this);
        String title = m == null ? "" : m.title;
        t.setText(title.isEmpty() ? "#" + sid : title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // 固定两行高度：一行/两行混排会让整行卡片下边缘参差
        t.setMaxLines(2);
        t.setMinLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(2), dp(6), dp(2), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(t, tlp);

        TextView d = new TextView(this);
        int cnt = m == null ? 0 : m.episodeCount;
        d.setText(cnt > 0 ? "共 " + cnt + " 集" : "");
        d.setTextColor(0xFF8A8A8A);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        d.setMaxLines(1);
        d.setMinLines(1);
        d.setPadding(dp(2), dp(2), dp(2), 0);
        box.addView(d);

        final String openTitle = title;
        final String openCover = cover;
        box.setOnClickListener(v -> openById(sid, openTitle, openCover));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        box.setLayoutParams(lp);
        return box;
    }

    private View historyRow(AccountApi.HistoryItem it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        row.setBackground(TvFocus.stateBg(this, 8,
                0xFF161616, 0xFF3A2020, 0xFF242424, TvFocus.ACCENT));
        row.setFocusable(true);
        row.setClickable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(6));
        row.setLayoutParams(rlp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF2A2A2A);
        ImageLoader.load(img, it.cover);
        row.addView(img, new LinearLayout.LayoutParams(dp(48), dp(64)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        TextView t = new TextView(this);
        t.setText(it.title.isEmpty() ? it.seriesId : it.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.addView(t);

        TextView sub = new TextView(this);
        StringBuilder sb = new StringBuilder("看到第 ").append(it.episode).append(" 集");
        if (it.total > 0) {
            sb.append(" / 共 ").append(it.total).append(" 集");
        }
        sub.setText(sb.toString());
        sub.setTextColor(0xFF9E9E9E);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.addView(sub);

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> openById(it.seriesId, it.title, it.cover));
        return row;
    }

    /** 按剧集 id 打开：先取集列表，再带起始集号进播放器。 */
    private void openById(String seriesId, String title) {
        openById(seriesId, title, "");
    }

    private void openById(String seriesId, String title, String cover) {
        setStatus("加载中…");
        new Thread(() -> {
            try {
                JSONObject j = api.episodes(seriesId);
                JSONArray eps = Api.parseEpisodes(j, seriesId);
                if (eps.length() == 0) {
                    runOnUiThread(() -> setStatus("没有可播放的集"));
                    return;
                }
                Intent i = PlayerActivity.intent(this, seriesId,
                        title == null ? "" : title, cover == null ? "" : cover,
                        eps.toString());
                runOnUiThread(() -> startActivity(i));
            } catch (Throwable e) {
                Log.e(TAG, "取集失败", e);
                runOnUiThread(() -> setStatus("取集失败：" + e.getMessage()));
            }
        }, "episodes").start();
    }

    // ------------------------------------------------------------ 更新检查

    /** 防止连点开出多个对话框（连点会让人以为「弹出来一下就没了」）。 */
    private boolean updateBusy;

    /**
     * 检查更新。走和首页完全相同的流程 —— 之前这里只弹一句「发现新版本」
     * 就结束，没有下载/安装入口，用户只能去首页再点一次。
     */
    private void checkUpdate() {
        if (updateBusy) {
            return;
        }
        updateBusy = true;
        // 收起键盘：输入法占着下半屏时弹窗会被挤到很窄的位置，
        // 甚至因为 adjustResize 触发的重新布局而看起来「一闪就没」。
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
            mobileInput.clearFocus();
            codeInput.clearFocus();
        } catch (Throwable ignored) {
        }
        setStatus("检查更新…");
        new Thread(() -> {
            try {
                cn.hongguo.tv.update.Updater.Result r =
                        cn.hongguo.tv.update.Updater.check(this);
                runOnUiThread(() -> {
                    updateBusy = false;
                    setStatus(r.message());
                    if (!r.ok) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("检查更新失败")
                                .setMessage(r.error)
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    if (!r.updateAvailable) {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("已是最新版本")
                                .setMessage("当前版本 " + r.current)
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    showUpdateDialog(r);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    updateBusy = false;
                    setStatus("检查更新失败：" + e.getMessage());
                });
            }
        }, "check-update").start();
    }

    /** 发现新版：确认后下载并安装（与首页同一套逻辑）。 */
    private void showUpdateDialog(cn.hongguo.tv.update.Updater.Result r) {
        String msg = r.notes.isEmpty() ? "是否下载并安装？" : r.notes;
        if (r.size > 0) {
            msg += "\n\n安装包 " + (r.size / 1024 / 1024) + "MB";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.latest)
                .setMessage(msg)
                .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(r))
                .setNeutralButton("用浏览器下载", (d, w) ->
                        cn.hongguo.tv.update.Updater.openDownloadPage(this, r.pageUrl))
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 已下载的安装包（授权「未知来源」后回来直接装，不用重下）。 */
    private java.io.File pendingApk;
    /** 待装包对应的版本号，用来判断「是否已经装上」。 */
    private String pendingApkVersion = "";

    private void downloadAndInstall(cn.hongguo.tv.update.Updater.Result r) {
        if (r.apkUrl.isEmpty()) {
            setStatus("更新信息里没有安装包地址");
            return;
        }
        java.io.File dir = new java.io.File(getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            setStatus("无法创建下载目录");
            return;
        }
        final java.io.File dst = new java.io.File(dir, "hongguo-tv-" + r.latest + ".apk");
        // 已经下好且校验通过就直接装，不再重下（授权完回来走这条路）
        if (dst.isFile() && dst.length() > 0
                && (r.sha256.isEmpty()
                || sha256Matches(dst, r.sha256))) {
            pendingApk = dst;
            cn.hongguo.tv.update.ApkInstaller.install(this, dst);
            return;
        }

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("下载更新 " + r.latest);
        pd.setMessage("准备中…");
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> cn.hongguo.tv.update.ApkInstaller.download(
                r.apkUrl, r.sha256, dst,
                new cn.hongguo.tv.update.ApkInstaller.Progress() {
                    @Override
                    public void onProgress(int pct, long done, long total) {
                        runOnUiThread(() -> {
                            pd.setProgress(pct);
                            pd.setMessage(pct + "%  " + (done / 1024 / 1024)
                                    + " / " + (total / 1024 / 1024) + " MB");
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            pd.dismiss();
                            setStatus(message);
                        });
                    }

                    @Override
                    public void onDone(java.io.File apk) {
                        runOnUiThread(() -> {
                            pd.dismiss();
                            setStatus("下载完成，正在安装…");
                            pendingApk = apk;
                            pendingApkVersion = r.latest;
                            cn.hongguo.tv.update.ApkInstaller.install(
                                    AccountActivity.this, apk);
                        });
                    }
                }), "update-download").start();
    }

    private static boolean sha256Matches(java.io.File f, String expect) {
        try {
            return expect.equalsIgnoreCase(
                    cn.hongguo.tv.update.ApkInstaller.sha256(f));
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(loginForm!=null)refresh();
        if(library!=null){library.observe(libraryUpdated);library.sync(false);}
        // 从「允许安装未知应用」授权页回来：已经下好包就接着装
        java.io.File apk = pendingApk;
        // 本地版本已追平待装版本 = 刚才装成功了，把安装包清掉。
        if (apk != null) {
            String local = cn.hongguo.tv.update.Updater.currentVersion(this);
            if (!pendingApkVersion.isEmpty()
                    && cn.hongguo.tv.update.Updater.compare(local, pendingApkVersion) >= 0) {
                cn.hongguo.tv.update.ApkInstaller.cleanOld(this, null);
                pendingApk = null;
                pendingApkVersion = "";
                apk = null;
            }
        } else {
            // 没有待装包时顺手清一次启动残留。
            cn.hongguo.tv.update.ApkInstaller.cleanOld(this, null);
        }
        if (apk != null && apk.isFile() && apk.length() > 0) {
            try {
                boolean can = android.os.Build.VERSION.SDK_INT
                        < android.os.Build.VERSION_CODES.O
                        || getPackageManager().canRequestPackageInstalls();
                if (can) {
                    cn.hongguo.tv.update.ApkInstaller.install(this, apk);
                }
            } catch (Throwable e) {
                Log.w(TAG, "续装失败", e);
            }
        }
    }

    /**
     * 焦点离开「键盘 + 两个输入框」时自动收起键盘。
     *
     * 为什么用 dispatchKeyEvent 而不是焦点监听：
     *   方向键在键盘内部移动也会触发失焦/获焦，用焦点监听会把「从一个
     *   数字键移到另一个数字键」误判成离开键盘，键盘一路抖。
     *   这里改成「每次按键之后检查一次当前焦点落在哪」，只有焦点真的
     *   跑到键盘和输入框之外才收，判断稳定。
     */
    @Override protected void onPause(){clearKeypad();if(library!=null)library.unobserve(libraryUpdated);super.onPause();}
    @Override protected void onDestroy(){if(library!=null)library.unobserve(libraryUpdated);super.onDestroy();}

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (keypadShown && Account.load(this).loggedIn()) { clearKeypad(); focusAccountAction(); }
        boolean handled = super.dispatchKeyEvent(event);
        if (event.getAction() == android.view.KeyEvent.ACTION_UP && keypadShown) {
            // 放到下一轮消息循环：此刻焦点可能还在切换过程中
            getWindow().getDecorView().post(this::autoHideKeypadIfLeft);
        }
        return handled;
    }

    /** 焦点若已离开键盘与输入框，收起键盘。 */
    private void autoHideKeypadIfLeft() {
        if(Account.load(this).loggedIn()){clearKeypad();return;}
        if (!keypadShown) {
            return;
        }
        View f = getCurrentFocus();
        if (f == null) {
            return;
        }
        // 键盘内部、两个输入框，以及「获取验证码 / 登录 / 清空 / 删除 / 确定」
        // 这些同一表单里的控件都算「还在输入」，不收起。
        if (isInside(f, keypadHost) || f == mobileInput || f == codeInput
                || f == sendCodeBtn || f == loginBtn) {
            return;
        }
        hideKeypad();
    }

    private static boolean isInside(View v, ViewGroup root) {
        if (root == null || v == null) {
            return false;
        }
        View p = v;
        while (p != null) {
            if (p == root) {
                return true;
            }
            p = (p.getParent() instanceof View) ? (View) p.getParent() : null;
        }
        return false;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
