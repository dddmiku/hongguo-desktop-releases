package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.TextUtils;

final class TvUi {
    static final int BG=0xFF0D1523,SURFACE=0xFF182438,TEXT=0xFFF2F1EB,DIM=0xFFA8B5C6,ACCENT=0xFFEAC58B;
    private TvUi(){ }
    static int dp(Context c,int value){return TvFocus.dp(c,value);}
    static TextView text(Context c,String value,int size,int color){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("tnum");t.setIncludeFontPadding(false);return t;}
    static TextView heading(Context c,String value,int size){TextView t=text(c,value,size,TEXT);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setClipToPadding(false);l.setClipChildren(false);return l;}
    static TextView button(Context c,String value,boolean primary,Runnable action){
        TextView t=text(c,value,16,primary?BG:TEXT);t.setGravity(Gravity.CENTER);t.setPadding(dp(c,18),0,dp(c,18),0);t.setId(View.generateViewId());t.setFocusable(true);t.setClickable(true);t.setMinHeight(dp(c,44));
        t.setFocusableInTouchMode(true);
        t.setBackground(TvFocus.stateBg(c,12,primary?ACCENT:SURFACE,primary?0xFFFBE5BE:0xFF304159,primary?ACCENT:0xFF324259,0xFFFFE8BA));
        t.setOnClickListener(v->action.run());t.setContentDescription(value);return t;
    }
    static TextView tab(Context c,String value,boolean selected,Runnable action){
        TextView t=button(c,value,false,action);t.setSelected(selected);t.setTextColor(selected?ACCENT:DIM);
        android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused},TvFocus.round(c,12,0xFF34445D,0xFFFFE8BA,2));
        bg.addState(new int[]{android.R.attr.state_selected},TvFocus.round(c,12,0xFF28374E,ACCENT,1));
        bg.addState(new int[]{},TvFocus.round(c,12,0x00000000,0,0));t.setBackground(bg);return t;
    }
    static ImageView poster(Context c,String url,int width,int height){ImageView v=new ImageView(c);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setBackground(TvFocus.round(c,10,0xFF27354A,0,0));v.setClipToOutline(true);v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,width),dp(c,height)));ImageLoader.load(v,url);return v;}
    static LinearLayout header(Activity a,String title){
        LinearLayout row=row(a);TextView back=button(a,"‹  返回",false,a::finish);row.addView(back,new LinearLayout.LayoutParams(dp(a,96),dp(a,44)));
        TextView name=heading(a,title,26);name.setPadding(dp(a,22),0,0,0);row.addView(name,new LinearLayout.LayoutParams(0,dp(a,48),1));name.setGravity(Gravity.CENTER_VERTICAL);return row;
    }
    static GradientDrawable backdrop(){return new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF25364E,0xFF172338,0xFF101928});}
    static String time(long milliseconds){long seconds=Math.max(0,milliseconds/1000);return String.format(java.util.Locale.US,"%d:%02d",seconds/60,seconds%60);}
    static void margins(View view,Context c,int left,int top,int right,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));view.setLayoutParams(p);}
}
