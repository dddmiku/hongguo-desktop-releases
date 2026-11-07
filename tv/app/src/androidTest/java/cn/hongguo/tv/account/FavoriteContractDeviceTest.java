package cn.hongguo.tv.account;

import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;

public class FavoriteContractDeviceTest extends InstrumentationTestCase {
    private static final String SID="7699859403758914622";
    private static class Fake implements FavoriteSync.Transport {
        boolean saved,readFail,writeFail,appliedBeforeError;
        int reads,writes;
        public List<String> read()throws IOException {reads++;if(readFail)throw new IOException("offline");return saved?Collections.singletonList(SID):Collections.emptyList();}
        public void write(String sid,boolean enabled)throws IOException {writes++;if(appliedBeforeError)saved=enabled;if(writeFail)throw new IOException("追剧暂时未成功，请稍后重试（101144）");saved=enabled;}
    }
    public void testAlreadySavedIsIdempotent(){Fake f=new Fake();f.saved=true;FavoriteSync.Result r=new FavoriteSync(f,delay->{}).update(SID,true);assertTrue(r.ok());assertTrue(r.enabled);assertEquals(0,f.writes);}
    public void testUnavailableStateDoesNotWrite(){Fake f=new Fake();f.readFail=true;FavoriteSync.Result r=new FavoriteSync(f,delay->{}).update(SID,true);assertFalse(r.ok());assertFalse(r.known);assertEquals(0,f.writes);}
    public void testFailedWriteKeepsRealStateAndDoesNotRetry(){Fake f=new Fake();f.writeFail=true;FavoriteSync.Result r=new FavoriteSync(f,delay->{}).update(SID,true);assertFalse(r.ok());assertTrue(r.known);assertFalse(r.enabled);assertEquals(1,f.writes);assertTrue(r.error.contains("101144"));assertFalse(r.error.contains("几分钟"));}
    public void testAmbiguousWriteSuccessReadbackIsAcknowledged(){Fake f=new Fake();f.writeFail=true;f.appliedBeforeError=true;FavoriteSync.Result r=new FavoriteSync(f,delay->{}).update(SID,true);assertTrue(r.ok());assertTrue(r.enabled);assertEquals(1,f.writes);}
    public void testRepeatedAddSendsOnlyOneMutation(){Fake f=new Fake();FavoriteSync sync=new FavoriteSync(f,delay->{});assertTrue(sync.update(SID,true).ok());assertTrue(sync.update(SID,true).ok());assertEquals(1,f.writes);}
    public void testCompressedDigestMatchesActuallySentBody()throws Exception {
        JSONObject payload=new JSONObject().put("books",new org.json.JSONArray().put(new JSONObject().put("book_id",SID)));
        ContentBody body=new ContentBody(payload);assertEquals(0x1f,body.bytes[0]&255);assertEquals(0x8b,body.bytes[1]&255);
        ByteArrayOutputStream decoded=new ByteArrayOutputStream();try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(body.bytes))){byte[] b=new byte[1024];int n;while((n=gzip.read(b))!=-1)decoded.write(b,0,n);}
        assertEquals(payload.toString(),new String(decoded.toByteArray(),"UTF-8"));
        byte[] hash=java.security.MessageDigest.getInstance("MD5").digest(body.bytes);StringBuilder expected=new StringBuilder();for(byte b:hash)expected.append(String.format(java.util.Locale.US,"%02X",b&255));assertEquals(expected.toString(),body.digest);
    }
}
