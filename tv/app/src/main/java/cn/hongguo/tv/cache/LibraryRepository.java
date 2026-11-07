package cn.hongguo.tv.cache;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.account.AccountApi;
import cn.hongguo.tv.api.Api;

/** Local-first viewing, complete cloud paging and serialized two-store uploads. */
public final class LibraryRepository {
    private static LibraryRepository instance;
    public static synchronized LibraryRepository get(Context context) {
        if(instance==null)instance=new LibraryRepository(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final HistoryStore store;
    private final AccountApi account;
    private final Api api;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners=new CopyOnWriteArrayList<>();
    private final AtomicBoolean pulling=new AtomicBoolean(),uploading=new AtomicBoolean(),again=new AtomicBoolean();
    private final Map<String,Long> lastPull=new HashMap<>();
    private volatile String error="";
    private final Runnable retry=()->{uploadPending();sync(false);};
    private boolean uploadScheduled;
    private final Runnable delayedUpload=()->{uploadScheduled=false;uploadPending();};

    private LibraryRepository(Context ctx) {
        context=ctx;store=HistoryStore.get(ctx);account=new AccountApi(ctx);api=new Api(ctx);
        try {
            ConnectivityManager cm=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm!=null)cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){ui.post(retry);}
            });
        }catch(RuntimeException ignored){ }
    }
    public String owner(){return Account.load(context).libraryKey();}
    public HistoryStore store(){return store;}
    public String error(){return error;}
    public boolean syncing(){return pulling.get()||uploading.get();}
    public void observe(Runnable listener){listeners.addIfAbsent(listener);}
    public void unobserve(Runnable listener){listeners.remove(listener);}
    private void changed(){ui.post(()->{for(Runnable listener:listeners)listener.run();});}
    private void guard(Account snapshot)throws IOException{
        if(!snapshot.sessionIdentity().equals(Account.load(context).sessionIdentity()))throw new IOException("账号已切换，已保留各账号本机记录");
    }

    public void save(WatchRecord record, boolean flush) {
        String owner=owner();store.merge(owner,record,true);changed();
        if(!owner.equals("local")){
            if(flush){ui.removeCallbacks(delayedUpload);uploadScheduled=false;uploadPending();}
            else if(!uploadScheduled){uploadScheduled=true;ui.postDelayed(delayedUpload,15000);}
        }
    }

    public void sync(boolean force) {
        Account snapshot=Account.load(context);
        if(!snapshot.loggedIn()){error="";changed();return;}
        if(pulling.get()){if(force)again.set(true);return;}
        synchronized(lastPull){if(!force&&System.currentTimeMillis()-lastPull.getOrDefault(snapshot.libraryKey(),0L)<60000)return;}
        if(!pulling.compareAndSet(false,true))return;
        error="";changed();
        worker.execute(()->{
            boolean complete=false;
            try{
                guard(snapshot);
                int offset=0;Set<String> fingerprints=new HashSet<>();
                for(int page=0;page<100;page++){
                    AccountApi.HistoryPage result=account.historyPage(snapshot,offset,500);guard(snapshot);
                    if(!result.fingerprint.isEmpty()&&!fingerprints.add(result.fingerprint))throw new IOException("云端重复返回同一页，已收到的历史已保存，可稍后重试");
                    store.mergeAll(snapshot.libraryKey(),result.items);changed();
                    if(!result.hasMore){complete=true;break;}
                    if(result.nextOffset<=offset)throw new IOException("云端翻页暂时无法继续，已收到的历史已保存");
                    offset=result.nextOffset;
                }
                if(!complete)throw new IOException("本轮同步较多，已保留全部收到的历史，可继续同步");
                List<String> ids=account.favorites(snapshot);guard(snapshot);
                store.replaceFavorites(snapshot.libraryKey(),ids);changed();
                store.metadata(snapshot.libraryKey(),api.seriesMeta(ids));guard(snapshot);
                synchronized(lastPull){lastPull.put(snapshot.libraryKey(),System.currentTimeMillis());}
            }catch(Exception e){error=friendly(e);ui.removeCallbacks(retry);ui.postDelayed(retry,60000);}
            finally{pulling.set(false);changed();if(again.getAndSet(false))sync(true);uploadPending();}
        });
    }

    public void uploadPending() {
        Account snapshot=Account.load(context);
        if(!snapshot.loggedIn()||!uploading.compareAndSet(false,true))return;
        worker.execute(()->{
            try{
                guard(snapshot);
                List<WatchRecord> pending=store.pending(snapshot.libraryKey());
                if(pending.isEmpty())return;
                Map<String,WatchRecord> history=new HashMap<>();
                for(WatchRecord r:account.historyPage(snapshot,0,500).items)history.put(r.seriesId,r);
                for(WatchRecord sent:pending){
                    guard(snapshot);
                    WatchRecord current=store.find(snapshot.libraryKey(),sent.seriesId);
                    if(current==null||current.watchedAt!=sent.watchedAt||!sent.samePosition(current))continue;
                    WatchRecord resume=account.resume(snapshot,sent.seriesId),old=history.get(sent.seriesId);
                    WatchRecord newest=old==null?resume:resume!=null&&resume.watchedAt>old.watchedAt?resume:old;
                    // Real recency wins, including an intentional replay of a lower episode.
                    if(newest!=null&&newest.watchedAt>sent.watchedAt){store.merge(snapshot.libraryKey(),newest,false);continue;}
                    boolean bothMatch=old!=null&&resume!=null&&old.watchedAt==sent.watchedAt&&resume.watchedAt==sent.watchedAt&&old.samePosition(sent)&&resume.samePosition(sent);
                    if(bothMatch||account.upload(snapshot,sent))store.acknowledge(snapshot.libraryKey(),sent);
                }
            }catch(Exception e){error="进度待同步："+friendly(e);ui.removeCallbacks(retry);ui.postDelayed(retry,60000);}
            finally{uploading.set(false);changed();if(!store.pending(snapshot.libraryKey()).isEmpty())ui.post(()->{if(!uploadScheduled){uploadScheduled=true;ui.postDelayed(delayedUpload,error.isEmpty()?15000:60000);}});}
        });
    }

    public void enrich(List<WatchRecord> rows) {
        Account snapshot=Account.load(context);List<String> ids=new ArrayList<>();
        for(WatchRecord r:rows)if(r.title.isEmpty()||r.cover.isEmpty())ids.add(r.seriesId);
        if(ids.isEmpty())return;
        worker.execute(()->{try{Map<String,Api.SeriesMeta> meta=api.seriesMeta(ids);guard(snapshot);if(!meta.isEmpty()){store.metadata(snapshot.libraryKey(),meta);changed();}}catch(Exception ignored){}});
    }

    public void refreshFavorites(){sync(true);}

    public void loadFavorite(Api.Series series,java.util.function.Consumer<cn.hongguo.tv.account.FavoriteSync.Result> done){
        Account snapshot=Account.load(context);
        if(!snapshot.loggedIn()){ui.post(()->done.accept(new cn.hongguo.tv.account.FavoriteSync.Result(true,false,"")));return;}
        worker.execute(()->{
            cn.hongguo.tv.account.FavoriteSync.Result result;
            try{List<String> ids=account.favorites(snapshot);guard(snapshot);store.replaceFavorites(snapshot.libraryKey(),ids);result=new cn.hongguo.tv.account.FavoriteSync.Result(true,ids.contains(series.seriesId),"");}
            catch(Exception e){result=new cn.hongguo.tv.account.FavoriteSync.Result(false,false,"暂时读不到追剧状态，请稍后重试");}
            final cn.hongguo.tv.account.FavoriteSync.Result state=result;ui.post(()->done.accept(state));
        });
    }

    public void favorite(Api.Series series,boolean enabled,java.util.function.Consumer<cn.hongguo.tv.account.FavoriteSync.Result> done){
        Account snapshot=Account.load(context);
        worker.execute(()->{
            cn.hongguo.tv.account.FavoriteSync sync=new cn.hongguo.tv.account.FavoriteSync(new cn.hongguo.tv.account.FavoriteSync.Transport(){
                public List<String> read()throws IOException{return account.favorites(snapshot);}
                public void write(String sid,boolean target)throws IOException{account.setFavorite(snapshot,sid,target);}
            });
            cn.hongguo.tv.account.FavoriteSync.Result result=sync.update(series.seriesId,enabled);
            try{guard(snapshot);if(result.known){store.favorite(snapshot.libraryKey(),series,result.enabled);changed();}}
            catch(IOException e){result=new cn.hongguo.tv.account.FavoriteSync.Result(false,false,friendly(e));}
            final cn.hongguo.tv.account.FavoriteSync.Result state=result;ui.post(()->done.accept(state));
        });
    }
    private static String friendly(Exception e){String text=e.getMessage();return text==null||text.trim().isEmpty()?"连接暂时不可用，本机记录仍可查看":text;}
}
