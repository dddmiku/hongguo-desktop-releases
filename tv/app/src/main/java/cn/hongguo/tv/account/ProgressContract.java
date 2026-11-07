package cn.hongguo.tv.account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import cn.hongguo.tv.cache.WatchRecord;

/** Official history and resume stores use different fields and must both be written. */
public final class ProgressContract {
    private ProgressContract() { }

    public static WatchRecord history(JSONObject o) {
        WatchRecord r = new WatchRecord();
        r.seriesId = o.optString("book_id_str",o.optString("book_id",""));
        r.title=o.optString("book_name","");r.cover=o.optString("thumb_url","");
        r.vid=o.optString("vid","");r.episode=o.optInt("vid_index",o.optInt("chapter_index",1));
        r.total=o.optInt("episode_cnt",0);r.position=o.optLong("current_play_position",0);
        r.watchedAt=WatchRecord.milliseconds(o.optLong("read_timestamp_ms",0));
        // The cloud duration can represent the entire series, not this episode.
        return r;
    }

    public static WatchRecord resume(JSONObject o) {
        WatchRecord r=new WatchRecord();r.seriesId=o.optString("series_id","");
        r.vid=o.optString("vid","");r.episode=o.optInt("vid_index",1);
        r.position=o.optLong("current_play_time",0);r.watchedAt=WatchRecord.milliseconds(o.optLong("last_watch_time",0));
        return r;
    }

    public static JSONObject historyBody(WatchRecord r) throws JSONException {
        JSONObject one=new JSONObject();one.put("book_id",Long.parseLong(r.seriesId));one.put("book_id_str",r.seriesId);one.put("book_type",2);
        one.put("vid_index",r.episode);one.put("chapter_index",r.episode);one.put("read_timestamp_ms",r.watchedAt);one.put("update_timestamp_ms",0);
        one.put("current_play_position",r.position);one.put("player_accumulate_total_time",r.position);one.put("duration",r.duration);one.put("episode_cnt",r.total);
        one.put("is_delete",false);one.put("use_soft_delete",false);one.put("genre_type",2150);
        if(!r.vid.isEmpty())one.put("vid",Long.parseLong(r.vid));if(!r.title.isEmpty())one.put("book_name",r.title);if(!r.cover.isEmpty())one.put("thumb_url",r.cover);
        return new JSONObject().put("update_datas",new JSONArray().put(one));
    }

    public static JSONObject resumeBody(WatchRecord r) throws JSONException {
        JSONObject one=new JSONObject();one.put("book_id",r.seriesId);one.put("book_type",2);one.put("item_id",r.vid);one.put("vid_index",r.episode);
        one.put("read_timestamp_ms",r.watchedAt);one.put("current_play_time",r.position);one.put("player_cumulative_total_duration",r.position);
        return new JSONObject().put("books",new JSONArray().put(one)).put("write_mode",1);
    }
}
