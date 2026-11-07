package cn.hongguo.tv.account;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import org.json.JSONObject;

/** Optional scoped QA: a separate private scope file is required for a genuine user-requested add. */
public class LiveFavoriteDeviceTest extends InstrumentationTestCase {
    public void testRequestedAddUsesNativeSignerAndReadsBack()throws Exception {
        assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        File file=new File(getInstrumentation().getTargetContext().getFilesDir(),"favorite-qa-scope.json");
        if(!file.isFile())return;
        byte[] bytes=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){assertEquals(bytes.length,in.read(bytes));}
        JSONObject scope=new JSONObject(new String(bytes,"UTF-8"));String sid=scope.getString("seriesId");assertTrue(sid.matches("[0-9]{8,19}"));
        Account snapshot=Account.load(getInstrumentation().getTargetContext());assertTrue(snapshot.loggedIn());
        AccountApi api=new AccountApi(getInstrumentation().getTargetContext());List<String> before=api.favorites(snapshot);
        final int[] writes={0};
        FavoriteSync.Result result=new FavoriteSync(new FavoriteSync.Transport(){
            public List<String> read()throws IOException{return api.favorites(snapshot);}
            public void write(String value,boolean target)throws IOException{writes[0]++;api.setFavorite(snapshot,value,target);}
        }).update(sid,true);
        android.util.Log.i("HgFavoriteQA","beforeCount="+before.size()+" writes="+writes[0]+" success="+result.ok()+" error="+result.error);
        assertTrue(result.error,result.ok());assertTrue(result.enabled);assertTrue(writes[0]<=1);
        List<String> after=api.favorites(snapshot);
        android.util.Log.i("HgFavoriteQA","afterCount="+after.size()+" requestedSaved="+after.contains(sid));
        assertTrue("请求成功后云端列表没有目标剧",after.contains(sid));
    }
}
