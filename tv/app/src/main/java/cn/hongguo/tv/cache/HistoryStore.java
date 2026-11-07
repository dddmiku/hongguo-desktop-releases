package cn.hongguo.tv.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import cn.hongguo.tv.account.Account;
import cn.hongguo.tv.api.Api;

/** Durable account libraries. Paging limits the view, never the stored history. */
public final class HistoryStore extends SQLiteOpenHelper {
    private static HistoryStore instance;
    public static synchronized HistoryStore get(Context context) {
        if (instance == null) {
            instance = new HistoryStore(context.getApplicationContext(), "hg_library.db");
            instance.migrateLegacy(context.getApplicationContext());
        }
        return instance;
    }

    public HistoryStore(Context context, String filename) { super(context, filename, null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history(owner TEXT NOT NULL,sid TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',cover TEXT NOT NULL DEFAULT '',vid TEXT NOT NULL DEFAULT '',episode INTEGER NOT NULL,total INTEGER NOT NULL DEFAULT 0,position INTEGER NOT NULL DEFAULT 0,duration INTEGER NOT NULL DEFAULT 0,watched_at INTEGER NOT NULL DEFAULT 0,pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,sid))");
        db.execSQL("CREATE INDEX history_order ON history(owner,watched_at DESC,sid DESC)");
        db.execSQL("CREATE TABLE favorites(owner TEXT NOT NULL,sid TEXT NOT NULL,title TEXT NOT NULL DEFAULT '',cover TEXT NOT NULL DEFAULT '',total INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(owner,sid))");
        db.execSQL("CREATE TABLE removed(owner TEXT NOT NULL,sid TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(owner,sid))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    private void migrateLegacy(Context context) {
        android.content.SharedPreferences p = context.getSharedPreferences("hg_progress", Context.MODE_PRIVATE);
        if (p.getBoolean("__library_migrated", false)) return;
        String owner = Account.load(context).libraryKey();
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                if (!entry.getKey().matches("[0-9]{8,19}") || !(entry.getValue() instanceof String)) continue;
                String[] values = ((String) entry.getValue()).split("\\|", -1);
                if (values.length != 2) continue;
                try {
                    WatchRecord r = new WatchRecord(); r.seriesId = entry.getKey();
                    r.episode = Integer.parseInt(values[0]); r.position = Long.parseLong(values[1]);
                    // The old format contains no date. Preserve that fact rather than inventing one.
                    merge(owner, r, false);
                } catch (NumberFormatException ignored) { }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        p.edit().putBoolean("__library_migrated", true).apply();
    }

    public synchronized WatchRecord find(String owner, String sid) {
        try (Cursor c = getReadableDatabase().query("history", null, "owner=? AND sid=?", new String[]{owner,sid}, null,null,null)) {
            return c.moveToFirst() ? record(c) : null;
        }
    }

    public synchronized void merge(String owner, WatchRecord incoming, boolean local) {
        if (owner == null || owner.isEmpty() || incoming == null || !incoming.valid()) return;
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor tombstone = db.rawQuery("SELECT at FROM removed WHERE owner=? AND sid=?",new String[]{owner,incoming.seriesId})) {
            if (tombstone.moveToFirst() && incoming.watchedAt <= tombstone.getLong(0)) return;
        }
        WatchRecord old = find(owner,incoming.seriesId), r = incoming.copy();
        boolean keepOld = old != null && (old.watchedAt > r.watchedAt || (old.watchedAt == r.watchedAt && !local));
        if (keepOld) r = old.copy();
        if (old != null) {
            if (r.title.isEmpty()) r.title = old.title;
            if (r.cover.isEmpty()) r.cover = old.cover;
            if (r.total == 0) r.total = old.total;
            if (r.episode == old.episode && (r.vid.isEmpty() || r.vid.equals(old.vid))) {
                if (r.vid.isEmpty()) r.vid = old.vid;
                if (r.duration == 0) r.duration = old.duration;
            }
        }
        if (keepOld) {
            if (!incoming.title.isEmpty()) r.title = incoming.title;
            if (!incoming.cover.isEmpty()) r.cover = incoming.cover;
            if (incoming.total > 0) r.total = incoming.total;
        }
        int pending = 0;
        if (keepOld || (old != null && local && owner.equals("local"))) {
            try (Cursor c = db.rawQuery("SELECT pending FROM history WHERE owner=? AND sid=?",new String[]{owner,r.seriesId})) {
                if (c.moveToFirst()) pending = c.getInt(0);
            }
        } else if (local && !owner.equals("local")) pending = 1;
        ContentValues v = values(r); v.put("owner",owner); v.put("pending",pending);
        db.insertWithOnConflict("history",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if (local) db.delete("removed","owner=? AND sid=?",new String[]{owner,r.seriesId});
    }

    public synchronized void mergeAll(String owner, List<WatchRecord> rows) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { for (WatchRecord r : rows) merge(owner,r,false); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    public synchronized List<WatchRecord> page(String owner, String query, int offset, int size) {
        String selection = "owner=?";
        List<String> args = new ArrayList<>(); args.add(owner);
        if (query != null && !query.trim().isEmpty()) {
            selection += " AND (title LIKE ? ESCAPE '\\' OR sid=?)";
            args.add("%"+query.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"); args.add(query.trim());
        }
        List<WatchRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("history",null,selection,args.toArray(new String[0]),null,null,"watched_at DESC,sid DESC",Math.max(0,offset)+","+Math.max(1,Math.min(200,size)))) {
            while (c.moveToNext()) out.add(record(c));
        }
        return out;
    }

    public synchronized int count(String owner) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history WHERE owner=?",new String[]{owner})) { c.moveToFirst(); return c.getInt(0); }
    }

    public synchronized int count(String owner,String query) {
        if(query==null||query.trim().isEmpty())return count(owner);
        String pattern="%"+query.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history WHERE owner=? AND (title LIKE ? ESCAPE '\\' OR sid=?)",new String[]{owner,pattern,query.trim()})){c.moveToFirst();return c.getInt(0);}
    }

    public synchronized List<WatchRecord> pending(String owner) {
        List<WatchRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("history",null,"owner=? AND pending=1",new String[]{owner},null,null,"watched_at ASC","40")) {
            while (c.moveToNext()) out.add(record(c));
        }
        return out;
    }

    public synchronized void acknowledge(String owner, WatchRecord sent) {
        ContentValues v = new ContentValues(); v.put("pending",0);
        getWritableDatabase().update("history",v,"owner=? AND sid=? AND watched_at=? AND position=? AND episode=? AND vid=?",new String[]{owner,sent.seriesId,Long.toString(sent.watchedAt),Long.toString(sent.position),Integer.toString(sent.episode),sent.vid});
    }

    public synchronized void remove(String owner, String sid) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("owner",owner);v.put("sid",sid);v.put("at",System.currentTimeMillis());
            db.insertWithOnConflict("removed",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.delete("history","owner=? AND sid=?",new String[]{owner,sid}); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized List<Api.Series> favorites(String owner) {
        List<Api.Series> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("favorites",null,"owner=?",new String[]{owner},null,null,"rowid DESC")) {
            while (c.moveToNext()) { Api.Series s = new Api.Series();s.seriesId=c.getString(c.getColumnIndexOrThrow("sid"));s.title=c.getString(c.getColumnIndexOrThrow("title"));s.cover=c.getString(c.getColumnIndexOrThrow("cover"));s.episodeCount=c.getInt(c.getColumnIndexOrThrow("total"));out.add(s); }
        }
        return out;
    }

    public synchronized void replaceFavorites(String owner, List<String> ids) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            List<Api.Series> old = favorites(owner); db.delete("favorites","owner=?",new String[]{owner});
            for (int i=ids.size()-1;i>=0;i--) {
                String sid=ids.get(i); if (!sid.matches("[0-9]{8,19}")) continue;
                ContentValues v = new ContentValues();v.put("owner",owner);v.put("sid",sid);
                for (Api.Series s : old) if (s.seriesId.equals(sid)) { v.put("title",s.title);v.put("cover",s.cover);v.put("total",s.episodeCount);break; }
                db.insertWithOnConflict("favorites",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void favorite(String owner,Api.Series s,boolean enabled) {
        SQLiteDatabase db=getWritableDatabase();
        if(!enabled){db.delete("favorites","owner=? AND sid=?",new String[]{owner,s.seriesId});return;}
        ContentValues v=new ContentValues();v.put("owner",owner);v.put("sid",s.seriesId);v.put("title",s.title);v.put("cover",s.cover);v.put("total",s.episodeCount);
        db.insertWithOnConflict("favorites",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void metadata(String owner, Map<String,Api.SeriesMeta> rows) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (Api.SeriesMeta m : rows.values()) {
                ContentValues v = new ContentValues();if(!m.title.isEmpty())v.put("title",m.title);if(!m.cover.isEmpty())v.put("cover",m.cover);if(m.episodeCount>0)v.put("total",m.episodeCount);
                if (v.size()==0) continue;
                db.update("favorites",v,"owner=? AND sid=?",new String[]{owner,m.seriesId});db.update("history",v,"owner=? AND sid=?",new String[]{owner,m.seriesId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static WatchRecord record(Cursor c) {
        WatchRecord r = new WatchRecord();r.seriesId=c.getString(c.getColumnIndexOrThrow("sid"));r.title=c.getString(c.getColumnIndexOrThrow("title"));r.cover=c.getString(c.getColumnIndexOrThrow("cover"));r.vid=c.getString(c.getColumnIndexOrThrow("vid"));r.episode=c.getInt(c.getColumnIndexOrThrow("episode"));r.total=c.getInt(c.getColumnIndexOrThrow("total"));r.position=c.getLong(c.getColumnIndexOrThrow("position"));r.duration=c.getLong(c.getColumnIndexOrThrow("duration"));r.watchedAt=c.getLong(c.getColumnIndexOrThrow("watched_at"));r.pending=c.getInt(c.getColumnIndexOrThrow("pending"))!=0;return r;
    }
    private static ContentValues values(WatchRecord r) {
        ContentValues v=new ContentValues();v.put("sid",r.seriesId);v.put("title",r.title);v.put("cover",r.cover);v.put("vid",r.vid);v.put("episode",r.episode);v.put("total",r.total);v.put("position",r.position);v.put("duration",r.duration);v.put("watched_at",r.watchedAt);return v;
    }
}
