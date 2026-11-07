package cn.hongguo.tv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

/**
 * TV 焦点样式。
 *
 * 为什么这是 TV 应用的头等大事：
 *   电视只有遥控器，没有触摸、没有鼠标悬停。用户完全靠「焦点框在哪」
 *   判断「按确定会打开什么」。没有焦点态 = 应用不可用 —— 不是体验问题，
 *   是功能问题。
 *
 * 规范要点（Android TV 设计指南）：
 *   1. 焦点态必须显著：描边 + 提亮 + 抬层级，三者至少用两个。
 *      纯换色在低端电视的色域下几乎看不出来。
 *   2. 焦点态**不能改变卡片尺寸**。原实现用 scaleX/scaleY(1.05) 实现「放大」，
 *      代价是聚焦卡比同排其他卡大一圈 —— 用户看到的是「有些剧图大小不一」，
 *      而不是「这一张被选中了」。位移还会让卡片超出父容器边界，
 *      底部的描边被裁掉（实测白框只有左/上/右三边，底边 0px），
 *      看起来像渲染坏了。
 *   3. 常态要有可识别性：用户得知道「这里可以按」，所以常态也要有
 *      轻微边框或背景，不能和背景糊在一起。
 */
final class TvFocus {

    /** 品牌红。 */
    static final int ACCENT = 0xFFEAC58B;
    /** 卡片/条目常态背景。 */
    static final int SURFACE = 0xFF182438;
    /** 焦点态背景。 */
    static final int FOCUSED = 0xFF304159;
    /** 页面底色（比卡片更暗，形成层次）。 */
    static final int BG = 0xFF0D1523;
    /** 主文字。 */
    static final int TEXT = 0xFFF5F5F5;
    /** 次要文字。 */
    static final int TEXT_DIM = 0xFFA8B5C6;
    /** 三级文字（分区标题、说明）。 */
    static final int TEXT_FAINT = 0xFF8293AA;

    private TvFocus() {
    }

    /**
     * 给一个卡片类 View 套上焦点样式：焦点时品牌红描边 + 背景提亮 + 抬层级。
     *
     * 不做缩放。电视上焦点必须极显著，但「显著」不等于「变大」：
     *   · 放大 5% 会让聚焦卡比同排其他卡大一圈，看起来像封面尺寸不一致；
     *   · 放大后的卡片会压住相邻卡片的边缘，横向滚动时还会被父容器裁掉描边
     *     （实测底边描边整条丢失，只剩左/上/右三面白框）。
     * 改用 2dp 品牌红描边 + 背景提亮 + elevation，四边完整、尺寸不变。
     *
     * @param radiusDp 圆角
     */
    static void applyCard(View v, Context ctx, int radiusDp) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        v.setBackground(stateBg(ctx, radiusDp, SURFACE, FOCUSED, 0xFF324259, ACCENT));
        // 焦点时只抬层级（让阴影盖住相邻卡片），不改 scale。
        // 3dp 的轻微上浮足够让卡片「跳出来」，且不会改变占位尺寸。
        v.setOnFocusChangeListener((view, has) -> {
            view.animate()
                    .translationZ(has ? dp(ctx, 3) : 0)
                    .setDuration(120)
                    .start();
            view.setElevation(has ? dp(ctx, 10) : 0);
        });
        // 描边 2dp 画在视图内部（GradientDrawable.setStroke 向内），
        // 不需要越界，理论上不会被裁。但父链若 clipToPadding=true，
        // elevation 阴影仍会被切，保持不裁剪。
        disableClipping(v);
    }

    /**
     * 关闭 View 及其父链的裁剪。
     *
     * 卡片放大后会超出自身边界，如果祖先容器 clipChildren=true，
     * 超出的描边/阴影会被切掉，看起来像「凭空多出一条白边」。
     * 一路往上关掉裁剪，直到遇到 DecorView。
     */
    private static void disableClipping(View v) {
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof ViewGroup && guard++ < 8) {
            ViewGroup g = (ViewGroup) p;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            p = g.getParent();
        }
    }

    /**
     * 给导航条目套焦点样式：背景提亮 + 品牌红描边（由调用方的背景负责）。
     *
     * 不做位移动画。原实现是「焦点时右移 6dp」——那套是为左侧竖排
     * 导航设计的（竖条在屏幕边缘，横向位移不越界）。现在导航条在顶部
     * 横排，横向位移会把相邻项顶开、还会和自动滚动打架。
     * 焦点态完全交给背景色 + 描边表达，足够了。
     */
    static void applyRailItem(View v, Context ctx) {
        v.setFocusable(true);
        v.setClickable(true);
    }

    /**
     * 生成四态背景：常态 / 焦点 / 按下 / 选中。
     *
     * @param normalColor 常态填充
     * @param focusColor  焦点填充
     * @param normalStroke 常态描边
     * @param focusStroke  焦点描边
     */
    static StateListDrawable stateBg(Context ctx, int radiusDp, int normalColor,
                                     int focusColor, int normalStroke, int focusStroke) {
        StateListDrawable d = new StateListDrawable();
        // 顺序重要：先匹配更具体的状态
        d.addState(new int[]{android.R.attr.state_focused},
                round(ctx, radiusDp, focusColor, focusStroke, 2));
        d.addState(new int[]{android.R.attr.state_pressed},
                round(ctx, radiusDp, focusColor, focusStroke, 2));
        d.addState(new int[]{}, round(ctx, radiusDp, normalColor, normalStroke, 1));
        return d;
    }

    /** 导航条目专用：焦点时左侧红条。 */
    static StateListDrawable railBg(Context ctx, int radiusDp) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},
                round(ctx, radiusDp, 0xFF331A1A, ACCENT, 2));
        d.addState(new int[]{android.R.attr.state_selected},
                round(ctx, radiusDp, 0xFF2C1414, ACCENT, 2));
        d.addState(new int[]{}, round(ctx, radiusDp, 0x00000000, 0x00000000, 0));
        return d;
    }

    /** 圆角矩形，可带描边。 */
    static GradientDrawable round(Context ctx, int radiusDp, int fill,
                                  int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(ctx, radiusDp));
        g.setColor(fill);
        if (strokeDp > 0 && stroke != 0) {
            g.setStroke(dp(ctx, strokeDp), stroke);
        }
        return g;
    }

    static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * 建立「左右在行内走、上下跨行」的焦点图。
     *
     * 为什么要手写而不是靠系统默认：
     *   系统默认的方向键搜索是「按几何位置找最近的 focusable」，
     *   在横向滚动行里会出问题 —— 上一行滚到一半时，按「下」可能落到
     *   同一行更右边的卡片，而不是下一行。电视上这是最影响手感的缺陷。
     *   显式设 nextFocusDown/Up 让它按「行」跳转。
     *
     * @param rows 每一行的卡片列表（按显示顺序）
     */
    static void linkRows(java.util.List<java.util.List<View>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            java.util.List<View> row = rows.get(r);
            java.util.List<View> up = r > 0 ? rows.get(r - 1) : null;
            java.util.List<View> down = r + 1 < rows.size() ? rows.get(r + 1) : null;
            for (int i = 0; i < row.size(); i++) {
                View v = row.get(i);
                // 行内左右
                if (i > 0) {
                    v.setNextFocusLeftId(row.get(i - 1).getId());
                } else v.setNextFocusLeftId(v.getId());
                if (i + 1 < row.size()) {
                    v.setNextFocusRightId(row.get(i + 1).getId());
                } else v.setNextFocusRightId(v.getId());
                // 跨行上下：优先同列，列数不够就退到该行最后一个
                if (up != null && !up.isEmpty()) {
                    v.setNextFocusUpId(up.get(Math.min(i, up.size() - 1)).getId());
                }
                if (down != null && !down.isEmpty()) {
                    v.setNextFocusDownId(down.get(Math.min(i, down.size() - 1)).getId());
                }
            }
        }
    }

    /** 确保 View 有 id（nextFocus 需要）。 */
    static void ensureId(View v, int id) {
        if (v.getId() == View.NO_ID) {
            v.setId(id);
        }
    }
}
