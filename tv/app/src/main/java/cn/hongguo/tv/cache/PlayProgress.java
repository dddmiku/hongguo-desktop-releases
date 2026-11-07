package cn.hongguo.tv.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import cn.hongguo.tv.account.Account;

/** Compatibility facade for durable, account-isolated viewing and screen aspect preferences. */
public final class PlayProgress {
    private static final String TAG="HgProgress";
    private final Context context;
    private final SharedPreferences sp;
    private final HistoryStore store;
    public PlayProgress(Context ctx){context=ctx.getApplicationContext();sp=context.getSharedPreferences("hg_progress",Context.MODE_PRIVATE);store=HistoryStore.get(context);}
    public void save(String seriesId,int episode,long positionMs){WatchRecord r=new WatchRecord();r.seriesId=seriesId;r.episode=episode;r.position=Math.max(0,positionMs);r.watchedAt=System.currentTimeMillis();store.merge(Account.load(context).libraryKey(),r,false);}
    public int episode(String seriesId){WatchRecord r=store.find(Account.load(context).libraryKey(),seriesId);return r==null?1:r.episode;}
    public long position(String seriesId){WatchRecord r=store.find(Account.load(context).libraryKey(),seriesId);return r==null?0:r.position;}
    public long resumeAt(String seriesId,int episode,long durationMs){WatchRecord r=store.find(Account.load(context).libraryKey(),seriesId);if(r==null||r.episode!=episode)return 0;return durationMs>0?Math.min(r.position,Math.max(0,durationMs-300)):r.position;}

    private static final String K_ASPECT = "__aspect_mode";

    /** 上次选的画面比例模式（0=适应 / 1=填充 / 2=拉伸）。默认适应。 */
    public int aspectMode() {
        return sp.getInt(K_ASPECT, 0);
    }

    /** 记住画面比例模式。 */
    public void saveAspectMode(int mode) {
        try {
            sp.edit().putInt(K_ASPECT, mode).apply();
        } catch (Throwable e) {
            Log.w(TAG, "保存画面模式失败", e);
        }
    }

}
