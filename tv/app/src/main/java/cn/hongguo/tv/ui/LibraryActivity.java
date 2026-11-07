package cn.hongguo.tv.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.LibraryRepository;
import cn.hongguo.tv.cache.WatchRecord;

/** Account-isolated history with honest dates, resumable positions and bounded UI pages. */
public class LibraryActivity extends TvActivity {
    public static Intent intent(android.content.Context context,boolean favorites){return new Intent(context,LibraryActivity.class).putExtra("favorites",favorites);}
    private LibraryRepository library;
    private LinearLayout content,root;
    private TextView summary,sync,previous,next;
    private EditText search;
    private ScrollView scroll;
    private View firstRecordAction;
    private boolean favorites;
    private int offset;
    private String query="",currentOwner="";
    private static final int PAGE_SIZE=24;
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh=this::render;
    private final Runnable searched=()->{offset=0;render();};
    private final java.util.Set<String> requestedMetadata=new java.util.HashSet<>();

    @Override protected void onCreate(Bundle saved){super.onCreate(saved);library=LibraryRepository.get(this);favorites=getIntent().getBooleanExtra("favorites",false);if(saved!=null){offset=saved.getInt("offset");query=saved.getString("query","");}buildUi();render();}
    private void buildUi(){
        root=TvUi.column(this);root.setBackgroundColor(TvUi.BG);root.setPadding(dp(48),dp(28),dp(48),dp(24));
        LinearLayout header=TvUi.header(this,favorites?"我的追剧":"观看历史");sync=TvUi.button(this,"同步手机记录",false,()->library.sync(true));sync.setTag("library-sync");header.addView(sync);root.addView(header);
        summary=TvUi.text(this,"",14,TvUi.DIM);summary.setPadding(0,dp(14),0,dp(18));root.addView(summary);
        LinearLayout controls=TvUi.row(this);
        search=new EditText(this);search.setId(View.generateViewId());search.setTag("history-search");search.setSingleLine(true);search.setHint(favorites?"搜索追剧作品":"搜索全部观看历史");search.setTextSize(16);search.setTextColor(TvUi.TEXT);search.setHintTextColor(TvUi.DIM);search.setPadding(dp(16),0,dp(16),0);search.setBackground(TvFocus.stateBg(this,12,TvUi.SURFACE,0xFF263750,0xFF324259,TvUi.ACCENT));search.setText(query);controls.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));
        TextView clear=TvUi.button(this,"清空搜索",false,()->search.setText(""));LinearLayout.LayoutParams clearLp=new LinearLayout.LayoutParams(dp(112),dp(44));clearLp.setMargins(dp(12),0,0,0);controls.addView(clear,clearLp);root.addView(controls);
        search.setOnEditorActionListener((v,action,event)->{hideKeyboard();ui.removeCallbacks(searched);offset=0;render();if(firstRecordAction!=null)firstRecordAction.requestFocus();return true;});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();ui.removeCallbacks(searched);ui.postDelayed(searched,200);}public void afterTextChanged(Editable s){}});
        scroll=new ScrollView(this);scroll.setFocusable(false);scroll.setVerticalScrollBarEnabled(false);scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);content=TvUi.column(this);content.setPadding(0,dp(18),0,dp(16));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout paging=TvUi.row(this);previous=TvUi.button(this,"上一页",false,()->{offset=Math.max(0,offset-PAGE_SIZE);scroll.scrollTo(0,0);render();});previous.setTag("history-previous");next=TvUi.button(this,"下一页",false,()->{offset+=PAGE_SIZE;scroll.scrollTo(0,0);render();});next.setTag("history-next");paging.addView(previous,new LinearLayout.LayoutParams(dp(110),dp(44)));paging.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));paging.addView(TvUi.text(this,"记录保存在本机 · 联网后自动同步",13,TvUi.DIM));paging.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));paging.addView(next,new LinearLayout.LayoutParams(dp(110),dp(44)));root.addView(paging);setContentView(root);sync.requestFocus();
    }
    private void render(){
        if(isDestroyed()||content==null)return;
        String owner=library.owner();if(!currentOwner.equals(owner)){offset=0;currentOwner=owner;requestedMetadata.clear();}
        View old=getCurrentFocus();Object tag=old==null?null:old.getTag();boolean contained=isWithin(old,content);int scrollY=scroll.getScrollY();
        content.removeAllViews();sync.setText(library.syncing()?"正在同步…":"同步手机记录");sync.setEnabled(Account.load(this).loggedIn()&&!library.syncing());
        List<List<View>> focus=new ArrayList<>();int total;
        if(favorites){List<Api.Series> all=library.store().favorites(owner);List<Api.Series> filtered=new ArrayList<>();for(Api.Series s:all)if(query.isEmpty()||s.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))||s.seriesId.equals(query))filtered.add(s);total=filtered.size();offset=adjustOffset(total);
            List<Api.Series> page=filtered.subList(offset,Math.min(total,offset+PAGE_SIZE));for(int from=0;from<page.size();from+=4){LinearLayout row=TvUi.row(this);List<View> cells=new ArrayList<>();for(int i=0;i<4;i++){if(from+i>=page.size()){row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));continue;}Api.Series s=page.get(from+i);View card=favoriteCard(s);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,0,i==3?0:dp(16),dp(16));row.addView(card,p);cells.add(card);}content.addView(row);focus.add(cells);}
        }else{
            total=library.store().count(owner,query);offset=adjustOffset(total);List<WatchRecord> rows=library.store().page(owner,query,offset,PAGE_SIZE);String group="";List<WatchRecord> needMeta=new ArrayList<>();
            for(WatchRecord record:rows){String date=date(record.watchedAt);if(!date.equals(group)){TextView title=TvUi.heading(this,date,18);title.setPadding(0,dp(14),0,dp(12));content.addView(title);group=date;}List<View> rowFocus=new ArrayList<>();content.addView(historyRow(record,rowFocus));focus.add(rowFocus);if((record.title.isEmpty()||record.cover.isEmpty())&&requestedMetadata.add(record.seriesId))needMeta.add(record);}
            if(!needMeta.isEmpty())library.enrich(needMeta);
        }
        previous.setEnabled(offset>0);next.setEnabled(offset+PAGE_SIZE<total);
        String count=total+" 部作品"+(total>PAGE_SIZE?" · "+(offset/PAGE_SIZE+1)+" / "+((total+PAGE_SIZE-1)/PAGE_SIZE)+" 页":"");
        summary.setText((Account.load(this).loggedIn()?"本机与云端记录":"本机记录 · 登录可同步手机进度")+" · "+count+(!library.error().isEmpty()?"\n"+library.error():""));
        if(total==0){LinearLayout empty=TvUi.column(this);empty.setPadding(dp(28),dp(40),dp(28),dp(36));empty.setBackground(TvFocus.round(this,18,TvUi.SURFACE,0,0));empty.addView(TvUi.heading(this,!query.isEmpty()?"没有找到匹配的作品":favorites?"把想看的故事留下来":"你的故事从这里开始",26));TextView hint=TvUi.text(this,!query.isEmpty()?"换个剧名或关键词试试。":favorites?"在剧集详情选择加入追剧，就能从这里快速找到。":"播放后会自动保存集数、位置和观看时间，记录不会因为数量多而被截断。",16,TvUi.DIM);hint.setPadding(0,dp(14),0,dp(20));empty.addView(hint);TextView action=TvUi.button(this,query.isEmpty()?"去发现好剧":"清空搜索",true,()->{if(!query.isEmpty())search.setText("");else finish();});empty.addView(action);content.addView(empty);focus.add(java.util.Collections.singletonList(action));}
        TvFocus.linkRows(focus);firstRecordAction=focus.isEmpty()?null:focus.get(0).get(0);if(!focus.isEmpty()){for(View v:focus.get(0))v.setNextFocusUpId(search.getId());List<View> last=focus.get(focus.size()-1);for(View v:last)v.setNextFocusDownId(next.isEnabled()?next.getId():previous.isEnabled()?previous.getId():search.getId());search.setNextFocusDownId(focus.get(0).get(0).getId());}
        content.post(()->{if(isDestroyed())return;if(contained&&tag!=null){View restored=content.findViewWithTag(tag);if(restored!=null)restored.requestFocus();else if(!focus.isEmpty())focus.get(0).get(0).requestFocus();}scroll.scrollTo(0,scrollY);});
    }
    private int adjustOffset(int total){return total==0?0:Math.min(offset,(total-1)/PAGE_SIZE*PAGE_SIZE);}
    private View historyRow(WatchRecord r,List<View> focus){
        LinearLayout outer=TvUi.row(this);LinearLayout card=TvUi.row(this);card.setId(View.generateViewId());card.setTag("history:"+r.seriesId);card.setPadding(dp(12),dp(10),dp(14),dp(10));TvFocus.applyCard(card,this,12);
        card.addView(TvUi.poster(this,r.cover,60,78));LinearLayout copy=TvUi.column(this);copy.setPadding(dp(18),0,dp(10),0);TextView title=TvUi.heading(this,r.title.isEmpty()?"旧版观看记录 · "+r.seriesId:r.title,18);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(title);
        TextView progress=TvUi.text(this,"看到第 "+r.episode+" 集"+(r.total>0?" / "+r.total+" 集":"")+"  ·  "+TvUi.time(r.position)+(r.duration>0?" / "+TvUi.time(r.duration):"")+(r.pending?"  ·  待同步":""),14,r.pending?TvUi.ACCENT:TvUi.DIM);progress.setPadding(0,dp(10),0,0);copy.addView(progress);card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));card.addView(TvUi.text(this,"继续看  ›",16,TvUi.ACCENT));
        card.setContentDescription((r.title.isEmpty()?r.seriesId:r.title)+"，第 "+r.episode+" 集，"+TvUi.time(r.position));card.setOnClickListener(v->{Api.Series s=new Api.Series();s.seriesId=r.seriesId;s.title=r.title.isEmpty()?"继续观看":r.title;s.cover=r.cover;s.episodeCount=r.total;SeriesDialog.show(this,s,library);});outer.addView(card,new LinearLayout.LayoutParams(0,-2,1));focus.add(card);
        TextView remove=TvUi.button(this,"移除",false,()->new android.app.AlertDialog.Builder(this).setTitle("移除这条本机历史？").setMessage("只移除电视端的显示记录；手机和云端历史保留，之后重新观看会再次记录。").setNegativeButton("保留",null).setPositiveButton("移除",(d,w)->{library.store().remove(library.owner(),r.seriesId);render();}).show());remove.setTag("remove:"+r.seriesId);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(78),dp(44));p.setMargins(dp(14),0,0,0);outer.addView(remove,p);focus.add(remove);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(10));outer.setLayoutParams(lp);return outer;
    }
    private View favoriteCard(Api.Series s){
        LinearLayout card=TvUi.column(this);card.setId(View.generateViewId());card.setTag("favorite:"+s.seriesId);card.setPadding(dp(8),dp(8),dp(8),dp(12));TvFocus.applyCard(card,this,12);card.addView(TvUi.poster(this,s.cover,184,230));TextView title=TvUi.heading(this,s.title.isEmpty()?"已追剧 · "+s.seriesId:s.title,17);title.setMaxLines(2);title.setMinLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(4),dp(10),dp(4),0);card.addView(title);WatchRecord r=library.store().find(library.owner(),s.seriesId);TextView count=TvUi.text(this,r==null?(s.episodeCount>0?s.episodeCount+" 集":"已追剧"):"看到第 "+r.episode+" 集",13,TvUi.ACCENT);count.setPadding(dp(4),dp(6),0,0);card.addView(count);card.setOnClickListener(v->SeriesDialog.show(this,s,library));return card;
    }
    private String date(long time){if(time<=0)return "旧版记录 · 观看时间未知";java.util.Calendar today=java.util.Calendar.getInstance();today.set(java.util.Calendar.HOUR_OF_DAY,0);today.set(java.util.Calendar.MINUTE,0);today.set(java.util.Calendar.SECOND,0);today.set(java.util.Calendar.MILLISECOND,0);long start=today.getTimeInMillis();if(time>=start)return "今天";today.add(java.util.Calendar.DAY_OF_MONTH,-1);if(time>=today.getTimeInMillis())return "昨天";return new SimpleDateFormat("yyyy 年 M 月 d 日",Locale.CHINA).format(new Date(time));}
    private boolean isWithin(View view,ViewGroup parent){while(view!=null){if(view==parent)return true;android.view.ViewParent p=view.getParent();view=p instanceof View?(View)p:null;}return false;}
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(im!=null)im.hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();}
    @Override protected void onResume(){super.onResume();if(library!=null){library.observe(refresh);render();library.sync(false);library.uploadPending();}}
    @Override protected void onPause(){if(library!=null)library.unobserve(refresh);super.onPause();}
    @Override protected void onDestroy(){ui.removeCallbacks(searched);if(library!=null)library.unobserve(refresh);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("offset",offset);state.putString("query",query);super.onSaveInstanceState(state);}
    private int dp(int value){return TvUi.dp(this,value);}
}
