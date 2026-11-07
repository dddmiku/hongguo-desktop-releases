package cn.hongguo.tv.cache;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import cn.hongguo.tv.api.Api;

/** Public first-page snapshots survive process restarts and are replaced atomically. */
public final class CatalogRepository {
    private final File directory;
    public CatalogRepository(Context context){directory=new File(context.getCacheDir(),"catalog-v1");directory.mkdirs();}
    private File file(int genre,String theme,String sort){return new File(directory,genre+"-"+theme.replaceAll("[^0-9]","")+"-"+sort.replaceAll("[^a-z_]","")+".json");}
    public synchronized List<Api.Series> cached(int genre,String theme,String sort){
        List<Api.Series> out=new ArrayList<>();File source=file(genre,theme,sort);
        if(!source.isFile()||source.length()>2*1024*1024)return out;
        try(FileInputStream in=new FileInputStream(source)){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
            JSONObject data=new JSONObject(new String(bytes.toByteArray(),"UTF-8"));
            if(data.optInt("version")!=1||System.currentTimeMillis()-data.optLong("at")>7L*86400000)return out;
            JSONArray rows=data.optJSONArray("items");if(rows==null)return out;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);Api.Series s=Api.Series.from(row);if(s!=null){s.category=row.optString("category","");s.premiere=row.optString("premiere","");out.add(s);}}
        }catch(Exception ignored){ }return out;
    }
    public synchronized void save(int genre,String theme,String sort,List<Api.Series> items){
        if(items.isEmpty())return;
        File dst=file(genre,theme,sort),part=new File(dst.getPath()+".part");
        try{
            JSONArray rows=new JSONArray();for(Api.Series s:items){JSONObject row=new JSONObject().put("series_id",s.seriesId).put("title",s.title).put("cover",s.cover).put("video_desc",s.intro).put("episode_cnt",s.episodeCount).put("category",s.category).put("premiere",s.premiere);rows.put(row);}
            JSONObject data=new JSONObject().put("version",1).put("at",System.currentTimeMillis()).put("items",rows);
            try(FileOutputStream out=new FileOutputStream(part)){out.write(data.toString().getBytes("UTF-8"));out.getFD().sync();}
            if(!part.renameTo(dst))part.delete();
            File[] files=directory.listFiles((d,name)->name.endsWith(".json"));
            if(files!=null&&files.length>30){java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-30;i++)files[i].delete();}
        }catch(Exception ignored){part.delete();}
    }
}
