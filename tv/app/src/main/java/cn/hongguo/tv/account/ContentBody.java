package cn.hongguo.tv.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;

/** Body bytes and digest are one unit: the signer must cover the bytes actually sent. */
public final class ContentBody {
    public final byte[] bytes;
    public final String digest;
    public ContentBody(JSONObject value) throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(GZIPOutputStream gzip=new GZIPOutputStream(buffer)){gzip.write(value.toString().getBytes("UTF-8"));}
        bytes=buffer.toByteArray();
        try {
            StringBuilder hash=new StringBuilder();
            for(byte b:MessageDigest.getInstance("MD5").digest(bytes))hash.append(String.format(Locale.US,"%02X",b&255));
            digest=hash.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException("请求摘要无法生成",e);}
    }
}
