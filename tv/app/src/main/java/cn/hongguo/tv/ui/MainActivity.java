package cn.hongguo.tv.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.CatalogRepository;
import cn.hongguo.tv.cache.LibraryRepository;
import cn.hongguo.tv.cache.WatchRecord;

/** Living-room discovery and a separate filtered library, with stable remote focus. */
public class MainActivity extends TvActivity {
    private static final String TAG="HgTV";
    private static final String[] GENRES={"短剧","漫剧","AI 短剧"};
    private final ExecutorService loaders=Executors.newFixedThreadPool(3);
    private final Map<String,List<Api.Series>> sections=new LinkedHashMap<>();
    private final List<List<View>> focusRows=new ArrayList<>();
    private final List<TextView> navigation=new ArrayList<>();
    private final Map<Integer,List<String[]>> themeCache=new HashMap<>();
    private List<String[]> themes=new ArrayList<>();
    private Api api;
    private CatalogRepository catalogs;
    private LibraryRepository library;
    private LinearLayout content,header,root;
    private ScrollView scroll;
    private TextView status,updateBtn,accountBtn;
    private View firstAction;
    private int page,genre,offset;
    private String theme="",sort="hot_score",themeName="全部题材";
    private int generation;
    private boolean initiallyFocused,userNavigated,loadingMore;
    private final Runnable libraryChanged=()->{if(!isFinishing()&&!isDestroyed()){updateAccount();if(page==0&&!sections.isEmpty())render();}};
    private boolean updateUiBusy;
    private java.io.File pendingApk;
    private String pendingApkVersion="";

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);cn.hongguo.tv.sign.RequestSigner.bind(this);
        api=new Api(this);catalogs=new CatalogRepository(this);library=LibraryRepository.get(this);
        if(saved!=null){page=saved.getInt("page");genre=saved.getInt("genre");theme=saved.getString("theme","");themeName=saved.getString("themeName","全部题材");sort=saved.getString("sort","hot_score");}
        buildUi();load(false);loadThemes();autoCheckUpdate();
        if(cn.hongguo.tv.BuildConfig.DEBUG){android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();Log.i("HgQA","density="+metrics.density+" scaled="+metrics.scaledDensity+" fontScale="+getResources().getConfiguration().fontScale);}

    }

    private void buildUi(){
        root=TvUi.column(this);root.setBackgroundColor(TvUi.BG);root.setPadding(dp(48),dp(20),dp(48),dp(14));
        header=TvUi.row(this);root.addView(header,new LinearLayout.LayoutParams(-1,dp(56)));buildHeader();
        status=TvUi.text(this,"好故事，值得在大屏上看。",14,TvUi.DIM);status.setPadding(0,dp(8),0,dp(4));root.addView(status);
        scroll=new ScrollView(this);scroll.setFocusable(false);scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        content=TvUi.column(this);content.setPadding(0,dp(8),0,dp(18));content.setClipChildren(false);content.setClipToPadding(false);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);navigation.get(0).requestFocus();
    }

    private void buildHeader(){
        View old=getCurrentFocus();String oldTag=old==null?null:old.getTag() instanceof String?(String)old.getTag():null;header.removeAllViews();navigation.clear();
        LinearLayout brand=TvUi.column(this);TextView name=TvUi.heading(this,"红果客厅",22);name.setTextColor(TvUi.ACCENT);brand.addView(name);TextView hint=TvUi.text(this,"让故事占满这一刻",11,TvUi.DIM);hint.setPadding(0,dp(4),0,0);brand.addView(hint);header.addView(brand,new LinearLayout.LayoutParams(dp(144),-2));
        addNav("精选",page==0,()->switchPage(0),"nav-home");addNav("片库",page==1,()->switchPage(1),"nav-browse");
        addNav("追剧",false,()->startActivity(LibraryActivity.intent(this,true)),"nav-favorites");addNav("历史",false,()->startActivity(LibraryActivity.intent(this,false)),"nav-history");
        header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        addNav("搜索",false,()->startActivity(new Intent(this,SearchActivity.class)),"nav-search");
        accountBtn=addNav(Account.load(this).loggedIn()?"账号":"登录",false,()->startActivity(new Intent(this,AccountActivity.class)),"nav-account");
        updateBtn=addNav("更新",false,this::checkUpdate,"nav-update");linkNavigation();
        if(oldTag!=null){View restored=header.findViewWithTag(oldTag);if(restored!=null)restored.requestFocus();}
    }
    private TextView addNav(String title,boolean selected,Runnable action,String tag){TextView b=TvUi.tab(this,title,selected,action);b.setTag(tag);b.setPadding(dp(13),0,dp(13),0);header.addView(b,new LinearLayout.LayoutParams(-2,dp(44)));navigation.add(b);return b;}
    private void updateAccount(){if(accountBtn!=null){String value=Account.load(this).loggedIn()?"账号":"登录";accountBtn.setText(value);accountBtn.setContentDescription(value);}}
    private void switchPage(int next){if(page==next){if(firstAction!=null)firstAction.requestFocus();return;}page=next;scroll.scrollTo(0,0);sections.clear();buildHeader();load(false);}
    private void linkNavigation(){for(int i=0;i<navigation.size();i++){TextView v=navigation.get(i);v.setNextFocusLeftId(navigation.get(Math.max(0,i-1)).getId());v.setNextFocusRightId(navigation.get(Math.min(navigation.size()-1,i+1)).getId());if(firstAction!=null)v.setNextFocusDownId(firstAction.getId());}}

    private void loadThemes(){
        final int selected=genre;
        if(themeCache.containsKey(selected)){themes=themeCache.get(selected);if(page==1)render();return;}
        loaders.execute(()->{try{List<String[]> result=api.themes(selected);runOnUiThread(()->{if(isDestroyed())return;themeCache.put(selected,result);if(genre==selected){themes=result;if(page==1)render();}});}catch(Exception e){runOnUiThread(()->{if(genre==selected&&page==1)setStatus("题材暂时无法刷新，可以继续浏览片库。" );});}});
    }

    private void load(boolean refresh){
        final int request=++generation,selectedGenre=genre;final String selectedTheme=theme,selectedSort=sort;final int selectedPage=page;
        offset=0;loadingMore=false;sections.clear();
        String[] ordering=page==0?new String[]{"hot_score","online_time"}:new String[]{sort};
        for(String order:ordering){List<Api.Series> hit=catalogs.cached(genre,page==0?"":theme,order);if(!hit.isEmpty())sections.put(order,hit);}
        render();setStatus(sections.isEmpty()?"正在找好故事…":"片单已就绪，正在刷新。");
        for(String order:ordering)loaders.execute(()->{
            try{
                List<Api.Series> items=api.browse(selectedGenre,selectedPage==0?"":selectedTheme,order,0,18);
                if(!items.isEmpty())catalogs.save(selectedGenre,selectedPage==0?"":selectedTheme,order,items);
                runOnUiThread(()->{if(request!=generation||isDestroyed())return;if(!items.isEmpty())sections.put(order,items);render();setStatus(items.isEmpty()?"这个分类暂时没有内容，试试其他题材。":selectedPage==0?"精选好剧 · "+GENRES[selectedGenre]:GENRES[selectedGenre]+" · "+themeName);});
            }catch(Exception e){runOnUiThread(()->{if(request==generation&&!isDestroyed()){setStatus(sections.isEmpty()?"片单暂时加载失败，按下方重试。":"暂时无法刷新，已保留当前片单。" );render();}});}
        });
    }

    private void render(){
        View previous=getCurrentFocus();Object focusTag=previous==null?null:previous.getTag();boolean contentFocus=isWithin(previous,content);int scrollY=scroll.getScrollY();
        content.removeAllViews();focusRows.clear();firstAction=null;
        if(page==1){filters();for(List<Api.Series> items:sections.values())grid(items);}
        else{
            List<Api.Series> hot=sections.get("hot_score"),fresh=sections.get("online_time");
            Api.Series featured=hot!=null&&!hot.isEmpty()?hot.get(0):fresh!=null&&!fresh.isEmpty()?fresh.get(0):null;
            if(featured!=null)hero(featured);
            List<WatchRecord> recent=library.store().page(library.owner(),"",0,3);if(!recent.isEmpty())recent(recent);
            if(hot!=null&&!hot.isEmpty())rail("正在热播",hot);
            if(fresh!=null&&!fresh.isEmpty())rail("新鲜上架",fresh);
        }
        if(sections.isEmpty()){LinearLayout empty=TvUi.column(this);empty.setPadding(dp(28),dp(44),dp(28),dp(32));empty.setBackground(TvFocus.round(this,18,TvUi.SURFACE,0,0));empty.addView(TvUi.heading(this,"好故事正在路上",26));TextView message=TvUi.text(this,"首次加载需要连接内容服务，已有片单和本机历史可离线查看。",16,TvUi.DIM);message.setPadding(0,dp(12),0,dp(22));empty.addView(message);TextView retry=TvUi.button(this,"重新加载",true,()->load(true));retry.setTag("retry-catalog");empty.addView(retry);content.addView(empty);focusRows.add(java.util.Collections.singletonList(retry));if(firstAction==null)firstAction=retry;}
        TvFocus.linkRows(focusRows);linkNavigation();
        if(!focusRows.isEmpty())for(View v:focusRows.get(0))v.setNextFocusUpId(navigation.get(page).getId());
        content.post(()->{if(isDestroyed())return;if(contentFocus&&focusTag!=null){View restored=content.findViewWithTag(focusTag);if(restored!=null){restored.requestFocus();scroll.scrollTo(0,scrollY);}else if(firstAction!=null)firstAction.requestFocus();}else if(!initiallyFocused&&!userNavigated&& !sections.isEmpty()&&firstAction!=null){firstAction.requestFocus();initiallyFocused=true;}else scroll.scrollTo(0,scrollY);});
    }
    private boolean isWithin(View view,ViewGroup parent){while(view!=null){if(view==parent)return true;android.view.ViewParent p=view.getParent();view=p instanceof View?(View)p:null;}return false;}

    private void hero(Api.Series s){
        LinearLayout hero=TvUi.row(this);GradientDrawable bg=TvUi.backdrop();bg.setCornerRadius(dp(20));bg.setStroke(dp(1),0xFF34465D);hero.setBackground(bg);hero.setPadding(dp(24),dp(12),dp(24),dp(12));
        hero.addView(TvUi.poster(this,s.cover,92,124));LinearLayout copy=TvUi.column(this);copy.setPadding(dp(28),0,0,0);copy.addView(TvUi.text(this,"今夜，遇见一个好故事",12,TvUi.ACCENT));
        TextView title=TvUi.heading(this,s.title,24);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(0,dp(4),0,dp(2));copy.addView(title);
        copy.addView(TvUi.text(this,(s.episodeCount>0?s.episodeCount+" 集  ·  ":"")+(!s.category.isEmpty()?s.category:GENRES[genre]),13,TvUi.DIM));
        TextView play=TvUi.button(this,"选集播放  ›",true,()->SeriesDialog.show(this,s,library));play.setTag("hero:"+s.seriesId);copy.addView(play,new LinearLayout.LayoutParams(dp(144),dp(40)));firstAction=play;focusRows.add(java.util.Collections.singletonList(play));hero.addView(copy,new LinearLayout.LayoutParams(0,-2,1));content.addView(hero,new LinearLayout.LayoutParams(-1,-2));
    }
    private void recent(List<WatchRecord> rows){
        LinearLayout line=TvUi.row(this);line.setPadding(0,dp(16),0,0);List<View> focus=new ArrayList<>();
        for(WatchRecord r:rows){String title=r.title.isEmpty()?"继续上次的故事":r.title;TextView button=TvUi.button(this,"继续 · "+title+"  第 "+r.episode+" 集",false,()->{Api.Series s=new Api.Series();s.seriesId=r.seriesId;s.title=title;s.cover=r.cover;s.episodeCount=r.total;SeriesDialog.show(this,s,library);});button.setTag("recent:"+r.seriesId);button.setSingleLine();button.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(0,0,dp(12),0);line.addView(button,p);focus.add(button);}
        content.addView(line);focusRows.add(focus);
    }
    private void sectionTitle(String value){LinearLayout row=TvUi.row(this);row.setPadding(0,dp(18),0,dp(12));row.addView(TvUi.heading(this,value,20),new LinearLayout.LayoutParams(0,-2,1));row.addView(TvUi.text(this,"左右选择 · 确定查看",12,TvUi.DIM));content.addView(row);}
    private void rail(String title,List<Api.Series> items){
        sectionTitle(title);HorizontalScrollView sv=new HorizontalScrollView(this);sv.setHorizontalScrollBarEnabled(false);sv.setFocusable(false);sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);sv.setClipToPadding(false);sv.setClipChildren(false);
        LinearLayout row=TvUi.row(this);List<View> focus=new ArrayList<>();
        for(Api.Series s:items){View card=card(s,132);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(144),-2);p.setMargins(0,0,dp(14),dp(4));row.addView(card,p);focus.add(card);}
        sv.addView(row);content.addView(sv,new LinearLayout.LayoutParams(-1,-2));focusRows.add(focus);
    }
    private View card(Api.Series s,int posterWidth){
        LinearLayout card=TvUi.column(this);card.setId(View.generateViewId());card.setTag("series:"+s.seriesId);card.setPadding(dp(6),dp(6),dp(6),dp(10));TvFocus.applyCard(card,this,12);card.setContentDescription(s.title+(s.episodeCount>0?"，"+s.episodeCount+" 集":""));
        card.addView(TvUi.poster(this,s.cover,posterWidth,posterWidth<=132?posterWidth*12/11:posterWidth*4/3));TextView title=TvUi.heading(this,s.title,16);title.setMaxLines(posterWidth<=132?1:2);title.setMinLines(posterWidth<=132?1:2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(6),dp(8),dp(6),0);card.addView(title);
        WatchRecord watched=library.store().find(library.owner(),s.seriesId);String subtitle=watched!=null?"看到第 "+watched.episode+" 集":s.episodeCount>0?s.episodeCount+" 集" :s.category;
        TextView meta=TvUi.text(this,subtitle,11,watched!=null?TvUi.ACCENT:TvUi.DIM);meta.setPadding(dp(6),dp(4),dp(6),0);meta.setSingleLine();meta.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(meta);card.setOnClickListener(v->SeriesDialog.show(this,s,library));return card;
    }

    private void filters(){
        TextView heading=TvUi.heading(this,"探索片库",26);heading.setPadding(0,0,0,dp(18));content.addView(heading);
        List<View> genres=new ArrayList<>();LinearLayout genreRow=TvUi.row(this);
        for(int i=0;i<GENRES.length;i++){final int selected=i;TextView button=TvUi.tab(this,GENRES[i],genre==i,()->{if(genre!=selected){genre=selected;theme="";themeName="全部题材";themes=new ArrayList<>();load(false);loadThemes();}});button.setTag("genre:"+i);genreRow.addView(button);genres.add(button);}
        content.addView(genreRow);focusRows.add(genres);firstAction=genres.get(genre);
        HorizontalScrollView themesScroll=new HorizontalScrollView(this);themesScroll.setFocusable(false);themesScroll.setHorizontalScrollBarEnabled(false);LinearLayout themeRow=TvUi.row(this);themeRow.setPadding(0,dp(8),0,dp(8));List<View> themeFocus=new ArrayList<>();
        addTheme(themeRow,themeFocus,"全部题材","");for(String[] t:themes)if(t.length>=2&&!t[1].isEmpty())addTheme(themeRow,themeFocus,t[0],t[1]);themesScroll.addView(themeRow);content.addView(themesScroll);focusRows.add(themeFocus);
        LinearLayout sorting=TvUi.row(this);List<View> sortFocus=new ArrayList<>();String[][] orders={{"热门","hot_score"},{"最新","online_time"},{"最多追剧","hot_collect"}};
        for(String[] order:orders){TextView b=TvUi.tab(this,order[0],sort.equals(order[1]),()->{sort=order[1];load(false);});b.setTag("sort:"+order[1]);sorting.addView(b);sortFocus.add(b);}
        sorting.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));TextView refresh=TvUi.button(this,"刷新片单",false,()->load(true));refresh.setTag("refresh-catalog");sorting.addView(refresh);sortFocus.add(refresh);sorting.setPadding(0,0,0,dp(18));content.addView(sorting);focusRows.add(sortFocus);
    }
    private void addTheme(LinearLayout row,List<View> focus,String name,String id){TextView b=TvUi.tab(this,name,theme.equals(id),()->{theme=id;themeName=name;load(false);});b.setTag("theme:"+id);row.addView(b);focus.add(b);}
    private void grid(List<Api.Series> items){
        final int cols=5;for(int from=0;from<items.size();from+=cols){LinearLayout row=TvUi.row(this);List<View> focus=new ArrayList<>();for(int i=0;i<cols;i++){if(from+i<items.size()){View card=card(items.get(from+i),146);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(0,0,i==cols-1?0:dp(14),dp(16));row.addView(card,p);focus.add(card);}else row.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));}content.addView(row);focusRows.add(focus);}
        TextView more=TvUi.button(this,loadingMore?"加载中…":"查看更多",false,this::loadMore);more.setTag("catalog-more");more.setEnabled(!loadingMore);content.addView(more,new LinearLayout.LayoutParams(dp(160),dp(44)));focusRows.add(java.util.Collections.singletonList(more));
    }
    private void loadMore(){
        if(loadingMore)return;loadingMore=true;final int request=generation;List<Api.Series> current=sections.get(sort);final int start=current==null?0:current.size();final int g=genre;final String t=theme,s=sort;render();
        loaders.execute(()->{try{List<Api.Series> next=api.browse(g,t,s,start,18);runOnUiThread(()->{if(request!=generation||isDestroyed())return;loadingMore=false;List<Api.Series> rows=sections.get(s);if(rows==null)return;java.util.Set<String> seen=new java.util.HashSet<>();for(Api.Series item:rows)seen.add(item.seriesId);int count=0;for(Api.Series item:next)if(seen.add(item.seriesId)){rows.add(item);count++;}render();setStatus(count==0?"已经看到这一分类的末尾。":"已加载 "+rows.size()+" 部作品");});}catch(Exception e){runOnUiThread(()->{if(request==generation&&!isDestroyed()){loadingMore=false;render();setStatus("下一页暂时加载失败，可以再次点查看更多。" );}});}});
    }

    private void autoCheckUpdate(){long previous=getPreferences(0).getLong("update_checked",0);if(System.currentTimeMillis()-previous<86400000)return;new Thread(()->{cn.hongguo.tv.update.Updater.Result result=cn.hongguo.tv.update.Updater.check(this);runOnUiThread(()->{if(isDestroyed())return;if(result.ok)getPreferences(0).edit().putLong("update_checked",System.currentTimeMillis()).apply();if(result.updateAvailable){updateBtn.setText("更新 •");updateBtn.setTextColor(TvUi.ACCENT);}});},"tv-update-check").start();}
    @Override protected void onResume(){super.onResume();if(library!=null){library.observe(libraryChanged);updateAccount();library.sync(false);library.uploadPending();resumePendingInstall();}}
    @Override protected void onPause(){if(library!=null)library.unobserve(libraryChanged);super.onPause();}
    @Override protected void onDestroy(){generation++;loaders.shutdownNow();if(library!=null)library.unobserve(libraryChanged);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("page",page);state.putInt("genre",genre);state.putString("theme",theme);state.putString("themeName",themeName);state.putString("sort",sort);super.onSaveInstanceState(state);}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(event.getAction()==KeyEvent.ACTION_DOWN)userNavigated=true;return super.dispatchKeyEvent(event);}
    @Override public void onBackPressed(){if(scroll.getScrollY()>0){scroll.smoothScrollTo(0,0);navigation.get(page).requestFocus();return;}if(page==1){switchPage(0);return;}new android.app.AlertDialog.Builder(this).setTitle("退出红果客厅？").setMessage("观看位置会保留，下次可以接着看。").setPositiveButton("退出",(d,w)->finish()).setNegativeButton("继续看",null).show();}
    private void setStatus(String text){runOnUiThread(()->{if(!isDestroyed()&&status!=null)status.setText(text);});}
    private int dp(int value){return TvUi.dp(this,value);}
    // Existing update download, checksum and unknown-source handoff are appended below.

    private void resumePendingInstall() {
        // 1) 先清理：本地版本已经 >= 待装版本，说明刚才装成功了。
        java.io.File apk = pendingApk;
        if (apk != null) {
            String local = cn.hongguo.tv.update.Updater.currentVersion(this);
            if (!pendingApkVersion.isEmpty()
                    && cn.hongguo.tv.update.Updater.compare(local, pendingApkVersion) >= 0) {
                Log.i(TAG, "安装已完成（本地 " + local + "），清理安装包");
                cn.hongguo.tv.update.ApkInstaller.cleanOld(this, null);
                pendingApk = null;
                pendingApkVersion = "";
                apk = null;
            }
        } else {
            // 没有待装包时也顺手清一次启动残留（上次装完没清掉的）。
            cn.hongguo.tv.update.ApkInstaller.cleanOld(this, null);
        }

        if (apk == null || !apk.isFile() || apk.length() == 0) {
            return;
        }
        try {
            boolean canInstall = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls();
            if (!canInstall) {
                // 还没授权：说明用户刚才是取消了授权页，保持现状等他再点。
                return;
            }
            Log.i(TAG, "回到前台，续装 " + apk.getName()
                    + (pendingApkVersion.isEmpty() ? "" : "（" + pendingApkVersion + "）"));
            setStatus("正在安装 " + pendingApkVersion + "…");
            cn.hongguo.tv.update.ApkInstaller.install(this, apk);
        } catch (Throwable e) {
            Log.w(TAG, "续装失败", e);
        }
    }


    private void checkUpdate() {
        // 连点是「对话框一闪就没」的直接原因：每点一次都新开一个线程 + 新弹一个
        // 对话框，叠在一起之后用户按一次返回，看到的就是「刚弹出来就没了」。
        if (updateUiBusy) {
            setStatus("正在检查更新…");
            return;
        }
        updateUiBusy = true;
        setStatus("检查更新…");
        new Thread(() -> {
            cn.hongguo.tv.update.Updater.Result r =
                    cn.hongguo.tv.update.Updater.check(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                updateUiBusy = false;
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
        }, "update-check").start();
    }

    /** 发现新版：确认后直接下载安装，不再让用户去浏览器。 */
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

    /**
     * 下载 APK 并调起安装器。
     *
     * 进度用不可取消的对话框显示，下载完成后自动跳到系统安装器。
     * 失败时给出具体原因（镜像全挂 / sha256 不匹配），不要静默。
     */
    private void downloadAndInstall(cn.hongguo.tv.update.Updater.Result r) {
        if (r.apkUrl.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("无法下载")
                    .setMessage("更新信息里没有安装包地址，请用浏览器下载。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        java.io.File dir = new java.io.File(getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            setStatus("无法创建下载目录");
            return;
        }
            // 复用已下载的安装包（进程被杀后重启时也能续上）。
            java.io.File cached =
                    cn.hongguo.tv.update.ApkInstaller.findPending(this, r.latest);
            if (cached != null
                    && (r.sha256.isEmpty() || sha256Matches(cached, r.sha256))) {
                Log.i(TAG, "复用已下载的安装包 " + cached.getName());
                pendingApk = cached;
                pendingApkVersion = r.latest;
                setStatus("已下载完成，正在安装 " + r.latest + "…");
                cn.hongguo.tv.update.ApkInstaller.install(this, cached);
                return;
            }
            if (cached != null) {
                // 文件在但校验不过（下载被截断/被替换），删掉重下。
                Log.w(TAG, "缓存安装包校验失败，删除后重下");
                cached.delete();
            }
            downloadThenInstall(r);
    }

    /**
     * 真正执行下载。与 downloadAndInstall 分开，是为了让「已经下好」的
     * 分支不必再构造进度对话框。
     */
    private void downloadThenInstall(cn.hongguo.tv.update.Updater.Result r) {
        java.io.File dir = new java.io.File(getCacheDir(), "updates");
        final java.io.File dst = new java.io.File(dir, "hongguo-tv-" + r.latest + ".apk");

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
                if (isFinishing() || isDestroyed()) return;
                            pd.setProgress(pct);
                            pd.setMessage(pct + "%  " + (done / 1024 / 1024)
                                    + " / " + (total / 1024 / 1024) + " MB");
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                            pd.dismiss();
                            setStatus(message);
                            new android.app.AlertDialog.Builder(MainActivity.this)
                                    .setTitle("下载失败")
                                    .setMessage(message + "\n\n可以改用浏览器下载。")
                                    .setPositiveButton("用浏览器", (d, w) ->
                                            cn.hongguo.tv.update.Updater
                                                    .openDownloadPage(MainActivity.this,
                                                            r.pageUrl))
                                    .setNegativeButton("取消", null)
                                    .show();
                        });
                    }

                    @Override
                    public void onDone(java.io.File apk) {
                        runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                            pd.dismiss();
                            setStatus("下载完成，正在安装…");
                            pendingApk = apk;
                            pendingApkVersion = r.latest;
                            cn.hongguo.tv.update.ApkInstaller.install(
                                    MainActivity.this, apk);
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


}
