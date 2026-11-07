package cn.hongguo.tv.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.AccountApi;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;
import cn.hongguo.tv.cache.WatchRecord;

/** A remote-accessible detail sheet. Opening a sheet never records a viewing. */
final class SeriesDialog {
    static void show(TvActivity activity,Api.Series series,LibraryRepository library){new SeriesDialog(activity,series,library).show();}
    private final TvActivity activity;
    private final Api.Series series;
    private final LibraryRepository library;
    private final ExecutorService loader=Executors.newSingleThreadExecutor();
    private AlertDialog dialog;
    private LinearLayout body;
    private TextView status;
    private JSONArray episodes=new JSONArray();
    private volatile boolean dismissed;
    private boolean favoriteBusy;
    private String intro="";
    private final Runnable updated=this::render;
    private SeriesDialog(TvActivity a,Api.Series s,LibraryRepository l){activity=a;series=s;library=l;intro=s.intro;}
    private void show(){
        LinearLayout page=TvUi.column(activity);page.setBackground(TvFocus.round(activity,20,TvUi.BG,0xFF3D4D64,1));page.setPadding(dp(28),dp(24),dp(28),dp(24));
        LinearLayout top=TvUi.row(activity);top.addView(TvUi.heading(activity,"故事详情",22),new LinearLayout.LayoutParams(0,-2,1));top.addView(TvUi.button(activity,"关闭",false,()->dialog.dismiss()));page.addView(top);
        status=TvUi.text(activity,"正在加载剧集…",15,TvUi.DIM);status.setPadding(0,dp(12),0,dp(12));page.addView(status);
        ScrollView scroll=new ScrollView(activity);scroll.setFocusable(false);scroll.setVerticalScrollBarEnabled(false);body=TvUi.column(activity);scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog=new AlertDialog.Builder(activity).create();dialog.setView(page,0,0,0,0);dialog.setOnDismissListener(d->{dismissed=true;library.unobserve(updated);loader.shutdownNow();});dialog.show();
        if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getWindow().setLayout(dp(800),Math.min(dp(480),(int)(activity.getResources().getDisplayMetrics().heightPixels*.9f)));}
        library.observe(updated);render();
        loader.execute(()->{
            try{
                Api api=new Api(activity);JSONObject response=api.episodes(series.seriesId);JSONArray rows=Api.parseEpisodes(response,series.seriesId);
                JSONObject data=response.optJSONObject("data"),node=data==null?null:data.optJSONObject(series.seriesId),vd=node==null?null:node.optJSONObject("video_data");
                String plot=vd==null?intro:vd.optString("series_intro",intro);
                if(vd!=null){String title=vd.optString("series_title","");if(!title.isEmpty())series.title=title;String cover=vd.optString("series_cover","");if(!cover.isEmpty())series.cover=cover;}
                Account snapshot=Account.load(activity);
                if(snapshot.loggedIn()){
                    try{WatchRecord remote=new AccountApi(activity).resume(snapshot,series.seriesId);if(remote!=null)library.store().merge(snapshot.libraryKey(),remote,false);}catch(Exception ignored){ }
                }
                activity.runOnUiThread(()->{if(dismissed||activity.isDestroyed())return;episodes=rows;intro=plot;series.episodeCount=rows.length();status.setText(rows.length()==0?"暂时没有可播放的剧集。":"选择任意一集，或接着上次的位置看。");render();});
            }catch(Exception e){activity.runOnUiThread(()->{if(!dismissed&&!activity.isDestroyed())status.setText("剧集暂时加载失败，关闭后重试。");});}
        });
    }
    private void render(){
        if(dismissed||body==null)return;View old=dialog.getCurrentFocus();Object tag=old==null?null:old.getTag();body.removeAllViews();
        LinearLayout hero=TvUi.row(activity);hero.addView(TvUi.poster(activity,series.cover,100,138));LinearLayout copy=TvUi.column(activity);copy.setPadding(dp(24),0,0,0);
        TextView title=TvUi.heading(activity,series.title,27);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(title);TextView meta=TvUi.text(activity,(series.episodeCount>0?"全 "+series.episodeCount+" 集":"剧集信息")+(!series.category.isEmpty()?"  ·  "+series.category:""),15,TvUi.ACCENT);meta.setPadding(0,dp(10),0,dp(10));copy.addView(meta);
        TextView plot=TvUi.text(activity,intro.isEmpty()?"从第一集开始，看看这个故事。":intro,16,TvUi.DIM);plot.setMaxLines(3);plot.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(plot);hero.addView(copy,new LinearLayout.LayoutParams(0,-2,1));body.addView(hero);
        List<List<View>> focus=new ArrayList<>();LinearLayout actions=TvUi.row(activity);actions.setPadding(0,dp(20),0,dp(22));List<View> buttons=new ArrayList<>();WatchRecord record=library.store().find(library.owner(),series.seriesId);
        if(record!=null){TextView resume=TvUi.button(activity,"继续第 "+record.episode+" 集 · "+TvUi.time(record.position),true,()->play(record.episode,record.position));resume.setTag("detail-resume");resume.setEnabled(episodes.length()>0);actions.addView(resume);buttons.add(resume);}
        TextView first=TvUi.button(activity,record==null?"立即播放":"从第一集看",record==null,()->play(1,0));first.setTag("detail-first");first.setEnabled(episodes.length()>0);TvUi.margins(first,activity,record!=null?12:0,0,12,0);actions.addView(first);buttons.add(first);
        boolean saved=library.store().favorites(library.owner()).stream().anyMatch(s->s.seriesId.equals(series.seriesId));TextView favorite=TvUi.button(activity,saved?"已追剧":"加入追剧",false,()->favorite(!saved));favorite.setTag("detail-favorite");favorite.setEnabled(!favoriteBusy);actions.addView(favorite);buttons.add(favorite);body.addView(actions);focus.add(buttons);
        TextView heading=TvUi.heading(activity,"选集",19);heading.setPadding(0,0,0,dp(12));body.addView(heading);
        for(int from=0;from<episodes.length();from+=10){LinearLayout row=TvUi.row(activity);List<View> cells=new ArrayList<>();for(int i=0;i<10;i++){JSONObject episode=episodes.optJSONObject(from+i);if(episode==null)break;int number=episode.optInt("num",from+i+1);TextView button=TvUi.tab(activity,String.format(java.util.Locale.US,"%02d",number),record!=null&&record.episode==number,()->play(number,0));button.setPadding(0,0,0,0);button.setTag("detail-ep:"+number);button.setContentDescription("播放第 "+number+" 集");LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(0,0,dp(8),dp(8));row.addView(button,p);cells.add(button);}for(int i=cells.size();i<10;i++)row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));body.addView(row);focus.add(cells);}
        TvFocus.linkRows(focus);if(tag!=null){View restored=body.findViewWithTag(tag);if(restored!=null)restored.requestFocus();}else if(episodes.length()>0&&!buttons.isEmpty())buttons.get(0).requestFocus();
    }
    private void favorite(boolean enabled){
        if(!Account.load(activity).loggedIn()){status.setText("登录后可以追剧，观看记录仍会保存在本机。");return;}
        if(favoriteBusy)return;favoriteBusy=true;render();status.setText("正在更新追剧…");library.favorite(series,enabled,failure->{favoriteBusy=false;if(dismissed)return;status.setText(failure.isEmpty()?(enabled?"已加入追剧":"已取消追剧"):failure);render();});
    }
    private void play(int episode,long position){
        int index=-1;for(int i=0;i<episodes.length();i++){JSONObject row=episodes.optJSONObject(i);if(row!=null&&row.optInt("num",i+1)==episode){index=i;break;}}
        if(index<0){status.setText("这一集暂时不可播放，请重新选集。");return;}
        Intent intent=PlayerActivity.intent(activity,series.seriesId,series.title,series.cover,episodes.toString());intent.putExtra(PlayerActivity.EXTRA_START_INDEX,index);intent.putExtra("start_position_ms",position);dialog.dismiss();activity.startActivity(intent);
    }
    private int dp(int value){return TvUi.dp(activity,value);}
}
