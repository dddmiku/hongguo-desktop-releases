package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.PlayProgress;

/**
 * 搜索页。从首页顶栏进入。
 *
 * 为什么独立成一个页面而不是嵌在首页：
 *   首页现在是内容流（封面卡片 + 分区），塞一个输入框会把首屏内容压掉；
 *   而且手机上软键盘弹出时，首页那套横向卡片布局会被挤得没法看。
 *   搜索独立成页，键盘收起后整屏都是结果。
 */
public class SearchActivity extends TvActivity {

    private static final String TAG = "HgSearch";

    private EditText input;
    private TextView status;
    private int generation;
    private LinearLayout results;

    private Api api;
    private PlayProgress progress;
    private final List<Api.Series> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        api = new Api(this);
        progress = new PlayProgress(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG);
        // 与首页一致的安全边距：电视 overscan 会裁掉边缘，
        // 交互元素必须留在 5%（约 48dp）以内。
        root.setPadding(dp(48), dp(36), dp(48), dp(24));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        input = new EditText(this);
        input.setHint("搜索剧名");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setTextColor(TvUi.TEXT);
        input.setHintTextColor(TvUi.DIM);
        input.setBackground(TvFocus.stateBg(this,12,TvUi.SURFACE,TvFocus.FOCUSED,0xFF324259,TvUi.ACCENT));
        input.setPadding(dp(16),dp(8),dp(16),dp(8));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // 只设 setSingleLine 不够：部分输入法（实测 vivo 自带）会吃掉回车，
        // 必须同时声明 IME_ACTION_SEARCH 并监听 KeyEvent。
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN) {
                doSearch(input.getText().toString().trim());
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button go = new Button(this);
        go.setText("搜索");
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        go.setAllCaps(false);
        go.setOnClickListener(v -> doSearch(input.getText().toString().trim()));
        bar.addView(go);

        root.addView(bar);

        status = new TextView(this);
        status.setTextColor(TvUi.DIM);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        input.requestFocus();
    }

    private void doSearch(String q) {
        if (q.isEmpty()) {
            return;
        }
        hideKeyboard();
        setStatus("搜索中…");
        final int request=++generation;
        new Thread(() -> {
            try {
                JSONObject j = api.search(q);
                int code = j.optInt("code", -1);
                if (code != 0) {
                    runOnUiThread(() -> setStatus("搜索失败 code=" + code));
                    return;
                }
                List<Api.Series> got = parseSearch(j);
                runOnUiThread(() -> {
                    if(request!=generation||isDestroyed())return;
                    items.clear();
                    items.addAll(got);
                    results.removeAllViews();
                    for (Api.Series s : got) {
                        results.addView(row(s));
                    }
                    setStatus(got.isEmpty() ? "没有找到短剧" : "找到 " + got.size() + " 部");
                });
            } catch (Throwable e) {
                Log.e(TAG, "搜索异常", e);
                runOnUiThread(() -> {if(request==generation&&!isDestroyed())setStatus("搜索暂时失败，请重试");});
            }
        }, "search").start();
    }

    /**
     * 从 search/tab/v 的响应里抽剧集。
     *
     * 实测结构：search_tabs[] → data[] (cell) → video_data[] → {series_id, title}
     * 剧集信息在 cell.video_data 里，不在 cell 顶层。
     */
    private List<Api.Series> parseSearch(JSONObject j) {
        List<Api.Series> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray tabs = j.optJSONArray("search_tabs");
        if (tabs == null) {
            return out;
        }
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tabObj = tabs.optJSONObject(i);
            JSONArray data = tabObj == null ? null : tabObj.optJSONArray("data");
            if (data == null) {
                continue;
            }
            for (int k = 0; k < data.length(); k++) {
                JSONObject cell = data.optJSONObject(k);
                if (cell == null) {
                    continue;
                }
                JSONArray vids = cell.optJSONArray("video_data");
                if (vids != null) {
                    for (int v = 0; v < vids.length(); v++) {
                        add(out, seen, vids.optJSONObject(v));
                    }
                }
                add(out, seen, cell);
            }
        }
        return out;
    }

    private void add(List<Api.Series> out, java.util.Set<String> seen, JSONObject o) {
        Api.Series s = Api.Series.from(o);
        if (s != null && seen.add(s.seriesId)) {
            out.add(s);
        }
    }

    /** 结果行：封面 + 标题 + 进度。 */
    private View row(Api.Series s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setId(View.generateViewId());
        row.setContentDescription(s.title);
        LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,-2);rowLp.setMargins(0,0,0,dp(12));row.setLayoutParams(rowLp);
        TvFocus.applyCard(row, this, 8);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF2A2A2A);
        ImageLoader.load(img, s.cover);
        row.addView(img, new LinearLayout.LayoutParams(dp(72), dp(96)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);

        TextView t = new TextView(this);
        t.setText(s.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(t);

        int ep = progress.episode(s.seriesId);
        if (ep > 1) {
            TextView p = new TextView(this);
            p.setText("看到第 " + ep + " 集");
            p.setTextColor(TvUi.ACCENT);
            p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            p.setPadding(0, dp(4), 0, 0);
            text.addView(p);
        }

        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> SeriesDialog.show(this,s,cn.hongguo.tv.cache.LibraryRepository.get(this)));
        return row;
    }

    private void openSeries(Api.Series s) {
        setStatus("加载《" + s.title + "》…");
        new Thread(() -> {
            try {
                JSONObject j = api.episodes(s.seriesId);
                JSONArray eps = Api.parseEpisodes(j, s.seriesId);
                if (eps.length() == 0) {
                    runOnUiThread(() -> setStatus("没有可播放的集"));
                    return;
                }
                Intent i = PlayerActivity.intent(this, s.seriesId, s.title, eps.toString());
                runOnUiThread(() -> startActivity(i));
            } catch (Throwable e) {
                Log.e(TAG, "取集失败", e);
                runOnUiThread(() -> setStatus("取集失败：" + e.getMessage()));
            }
        }, "episodes").start();
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            input.clearFocus();
        } catch (Throwable ignored) {
        }
    }

    private void setStatus(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> {if(!isDestroyed())status.setText(s);});
    }

    @Override protected void onDestroy(){generation++;super.onDestroy();}

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
