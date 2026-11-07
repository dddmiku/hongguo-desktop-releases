package cn.hongguo.tv.cache;

import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.JSONObject;
import cn.hongguo.tv.account.ProgressContract;

/** Real Android SQLite tests use a separate test database, never the viewer's library. */
public class HistoryStoreDeviceTest extends InstrumentationTestCase {
    private HistoryStore store;
    private Context context;
    @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();assertTrue(context.getPackageName().endsWith(".qa"));context.deleteDatabase("history-test.db");store=new HistoryStore(context,"history-test.db");}
    @Override protected void tearDown() throws Exception {store.close();context.deleteDatabase("history-test.db");super.tearDown();}
    private WatchRecord record(long id,int episode,long position,long at){WatchRecord r=new WatchRecord();r.seriesId=Long.toString(id);r.title="故事 "+id;r.episode=episode;r.position=position;r.watchedAt=at;r.vid="7699861398091746329";return r;}
    public void testCompletePagingAndAccountIsolation(){
        java.util.List<WatchRecord> rows=new java.util.ArrayList<>();for(int i=0;i<1500;i++)rows.add(record(7600000000000000000L+i,5,1234,1760000000000L+i));store.mergeAll("viewer-a",rows);
        assertEquals(1500,store.count("viewer-a"));assertEquals(0,store.count("viewer-b"));assertEquals(24,store.page("viewer-a","",0,24).size());assertEquals(12,store.page("viewer-a","",1488,24).size());assertEquals(1,store.count("viewer-a","7600000000000000200"));
    }
    public void testRecentReplayWinsInsteadOfMaximumEpisode(){WatchRecord newer=record(7600000000000000001L,2,850,1760000000002L),old=record(7600000000000000001L,80,54000,1760000000001L);store.merge("viewer",old,false);store.merge("viewer",newer,true);assertEquals(2,store.find("viewer",newer.seriesId).episode);assertEquals(850L,store.find("viewer",newer.seriesId).position);assertTrue(store.find("viewer",newer.seriesId).pending);}
    public void testOldAckCannotClearNewProgress(){WatchRecord first=record(7600000000000000002L,3,9000,1760000000001L);store.merge("viewer",first,true);WatchRecord second=first.copy();second.position=11000;second.watchedAt++;store.merge("viewer",second,true);store.acknowledge("viewer",first);assertEquals(1,store.pending("viewer").size());store.acknowledge("viewer",second);assertEquals(0,store.pending("viewer").size());}
    public void testCloudTimeConflictAndMetadataPreservation(){WatchRecord local=record(7600000000000000003L,10,15000,1760000000003L);local.duration=179420;store.merge("viewer",local,true);WatchRecord remote=local.copy();remote.watchedAt--;remote.position=900;remote.title="";remote.cover="";remote.duration=0;store.merge("viewer",remote,false);assertEquals(15000L,store.find("viewer",local.seriesId).position);assertEquals(179420L,store.find("viewer",local.seriesId).duration);remote.watchedAt=local.watchedAt+1;remote.episode=1;remote.vid="7699861290088401945";store.merge("viewer",remote,false);assertEquals(1,store.find("viewer",local.seriesId).episode);assertEquals(0L,store.find("viewer",local.seriesId).duration);assertEquals(0,store.pending("viewer").size());}
    public void testRemovedEntryDoesNotReappearOnCloudRefresh(){WatchRecord r=record(7600000000000000004L,1,2000,1);store.merge("viewer",r,false);store.remove("viewer",r.seriesId);store.merge("viewer",r,false);assertEquals(0,store.count("viewer"));r.watchedAt=System.currentTimeMillis()+1;store.merge("viewer",r,true);assertEquals(1,store.count("viewer"));}
    public void testOfficialTwoStoreContractUsesOneRealTimestamp() throws Exception {WatchRecord r=record(7699859403758914622L,20,45035,1794000000000L);r.duration=179420;JSONObject history=ProgressContract.historyBody(r).getJSONArray("update_datas").getJSONObject(0),resume=ProgressContract.resumeBody(r).getJSONArray("books").getJSONObject(0);assertEquals(r.watchedAt,history.getLong("read_timestamp_ms"));assertEquals(r.watchedAt,resume.getLong("read_timestamp_ms"));assertEquals(45035L,resume.getLong("current_play_time"));assertEquals(r.vid,resume.getString("item_id"));assertEquals(r.seriesId,resume.getString("book_id"));assertEquals(20,resume.getInt("vid_index"));}
}
