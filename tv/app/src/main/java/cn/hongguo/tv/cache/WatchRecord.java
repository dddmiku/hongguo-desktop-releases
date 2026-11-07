package cn.hongguo.tv.cache;

/** One genuine viewing position. All times are milliseconds; episode numbers start at one. */
public final class WatchRecord {
    public String seriesId = "", title = "", cover = "", vid = "";
    public int episode = 1, total;
    public long position, duration, watchedAt;
    public boolean pending;

    public boolean valid() {
        return seriesId.matches("[0-9]{8,19}") && episode > 0 && episode <= 100000
                && position >= 0 && position <= 86400000 && duration >= 0 && watchedAt >= 0;
    }

    public WatchRecord copy() {
        WatchRecord r = new WatchRecord();
        r.seriesId = seriesId; r.title = title; r.cover = cover; r.vid = vid;
        r.episode = episode; r.total = total; r.position = position;
        r.duration = duration; r.watchedAt = watchedAt; r.pending = pending;
        return r;
    }

    public boolean samePosition(WatchRecord other) {
        return other != null && episode == other.episode && position == other.position
                && (vid.isEmpty() || other.vid.isEmpty() || vid.equals(other.vid));
    }

    public static long milliseconds(long value) {
        return value > 0 && value < 100000000000L ? value * 1000 : Math.max(0, value);
    }
}
