package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import cn.hongguo.tv.api.Api;
import cn.hongguo.tv.cache.HistoryStore;
import cn.hongguo.tv.cache.WatchRecord;

/** Genuine content and decoder QA runs in the separate, logged-out .qa application. */
public class TvUiDeviceTest extends InstrumentationTestCase {
    private Activity activity;
    @Override protected void setUp() throws Exception {super.setUp();assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));cn.hongguo.tv.account.Account.clear(getInstrumentation().getTargetContext());}
    @Override protected void tearDown() throws Exception {if(activity!=null)main(()->{activity.finish();return null;});super.tearDown();}
    private <T>T main(Supplier<T> task){AtomicReference<T> value=new AtomicReference<>();getInstrumentation().runOnMainSync(()->value.set(task.get()));return value.get();}
    private void waitFor(String label,Supplier<Boolean> check,long timeout)throws Exception{long deadline=System.currentTimeMillis()+timeout;while(System.currentTimeMillis()<deadline){if(main(check))return;Thread.sleep(100);}fail(label);}
    private Activity open(Class<? extends Activity> type,Intent extras){Intent i=extras==null?new Intent(getInstrumentation().getTargetContext(),type):extras;i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=getInstrumentation().startActivitySync(i);return activity;}
    private View byTag(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private View tagPrefix(View v,String prefix){if(v.getTag() instanceof String&&((String)v.getTag()).startsWith(prefix))return v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View hit=tagPrefix(group.getChildAt(i),prefix);if(hit!=null)return hit;}}return null;}
    private View first(Class<?> type,View v){if(type.isInstance(v))return v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View found=first(type,group.getChildAt(i));if(found!=null)return found;}}return null;}
    private boolean contains(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
    private void key(int code){getInstrumentation().sendKeyDownUpSync(code);getInstrumentation().waitForIdleSync();}

    public void testHomeRemoteFocusAndFilteredLibrary()throws Exception{
        open(MainActivity.class,null);
        waitFor("真实片单没有加载",()->tagPrefix(activity.getWindow().getDecorView(),"series:")!=null,60000);
        waitFor("初始焦点未落在推荐播放按钮",()->activity.getCurrentFocus()!=null&&String.valueOf(activity.getCurrentFocus().getTag()).startsWith("hero:"),5000);
        key(KeyEvent.KEYCODE_DPAD_DOWN);if(main(()->String.valueOf(activity.getCurrentFocus().getTag()).startsWith("recent:")))key(KeyEvent.KEYCODE_DPAD_DOWN);String before=main(()->String.valueOf(activity.getCurrentFocus().getTag()));assertTrue("未进入真实片单："+before,before.startsWith("series:"));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);String after=main(()->String.valueOf(activity.getCurrentFocus().getTag()));assertTrue(after.startsWith("series:"));assertFalse(before.equals(after));
        key(KeyEvent.KEYCODE_DPAD_UP);if(main(()->String.valueOf(activity.getCurrentFocus().getTag()).startsWith("recent:")))key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_UP);assertEquals("nav-home",main(()->activity.getCurrentFocus().getTag()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor("片库入口不可用",()->byTag("genre:0")!=null,10000);
        waitFor("真实题材未加载",()->contains(activity.getWindow().getDecorView(),"动作"),60000);
        View genre=main(()->byTag("genre:1"));main(()->{genre.performClick();return null;});
        waitFor("体裁切换未生效",()->byTag("genre:1").isSelected()&&tagPrefix(activity.getWindow().getDecorView(),"series:")!=null,60000);
        key(KeyEvent.KEYCODE_BACK);waitFor("返回精选失效",()->tagPrefix(activity.getWindow().getDecorView(),"hero:")!=null,10000);
    }

    public void testCompleteHistorySearchAndPaging()throws Exception{
        HistoryStore store=HistoryStore.get(getInstrumentation().getTargetContext());java.util.List<WatchRecord> rows=new java.util.ArrayList<>();
        for(int i=0;i<130;i++){WatchRecord r=new WatchRecord();r.seriesId=Long.toString(7500000000000000000L+i);r.title="验收故事"+i;r.episode=3;r.position=850;r.watchedAt=System.currentTimeMillis()+i;rows.add(r);}store.mergeAll("local",rows);
        try{
            open(LibraryActivity.class,LibraryActivity.intent(getInstrumentation().getTargetContext(),false));
            assertTrue(store.count("local")>=130);
            main(()->{byTag("history-next").performClick();return null;});
            waitFor("历史下一页未显示",()->byTag("history-previous").isEnabled(),5000);
            main(()->{((EditText)byTag("history-search")).setText("验收故事105");return null;});
            waitFor("全量历史搜索未找到旧记录",()->byTag("history:7500000000000000105")!=null&&!byTag("history-next").isEnabled(),5000);
            assertFalse(main(()->byTag("history-next").isEnabled()));
            assertEquals(850L,store.find("local","7500000000000000105").position);
        }finally{for(WatchRecord r:rows)store.remove("local",r.seriesId);}
    }

    public void testActualVideoPauseAndExactResume()throws Exception{
        String sid="7699859403758914622";Api api=new Api(getInstrumentation().getTargetContext());org.json.JSONArray episodes=Api.parseEpisodes(api.episodes(sid),sid);assertTrue(episodes.length()>1);
        Intent intent=PlayerActivity.intent(getInstrumentation().getTargetContext(),sid,"人前小保姆，人后是亲妈","",episodes.toString());intent.putExtra(PlayerActivity.EXTRA_START_INDEX,0);intent.putExtra("start_position_ms",0L);open(PlayerActivity.class,intent);
        java.lang.reflect.Field field=PlayerActivity.class.getDeclaredField("player");field.setAccessible(true);
        waitFor("没有真实解码播放",()->{try{MediaPlayer p=(MediaPlayer)field.get(activity);return p!=null&&p.isPlaying()&&p.getVideoWidth()>0&&p.getCurrentPosition()>2000;}catch(Exception e){return false;}},180000);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        WatchRecord saved=HistoryStore.get(getInstrumentation().getTargetContext()).find("local",sid);assertNotNull(saved);assertEquals(1,saved.episode);assertTrue(saved.position>1000);assertTrue(saved.position<15000);assertEquals(episodes.getJSONObject(0).getString("vid"),saved.vid);assertFalse(saved.pending);
        assertEquals(saved.position,new cn.hongguo.tv.cache.PlayProgress(getInstrumentation().getTargetContext()).resumeAt(sid,1,179420));
        key(KeyEvent.KEYCODE_DPAD_UP);java.lang.reflect.Field menuField=PlayerActivity.class.getDeclaredField("playbackMenu");menuField.setAccessible(true);
        assertTrue(main(()->{try{android.app.AlertDialog menu=(android.app.AlertDialog)menuField.get(activity);return menu!=null&&menu.isShowing()&&menu.getListView().getCount()==6;}catch(Exception e){return false;}}));key(KeyEvent.KEYCODE_BACK);
        main(()->{activity.finish();return null;});activity=null;
        Intent resume=PlayerActivity.intent(getInstrumentation().getTargetContext(),sid,"人前小保姆，人后是亲妈","",episodes.toString());open(PlayerActivity.class,resume);
        waitFor("再次打开没有精确续播",()->{try{MediaPlayer p=(MediaPlayer)field.get(activity);return p!=null&&p.isPlaying()&&p.getCurrentPosition()>=saved.position;}catch(Exception e){return false;}},60000);
        key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
}
