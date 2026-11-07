package cn.hongguo.tv.account;

import java.io.IOException;
import java.util.List;

/** Reconcile real shelf state before writing and after ambiguous failures. No automatic writes. */
public final class FavoriteSync {
    public interface Transport { List<String> read()throws IOException; void write(String sid,boolean enabled)throws IOException; }
    public static final class Result {
        public final boolean known,enabled;
        public final String error;
        public Result(boolean known,boolean enabled,String error){this.known=known;this.enabled=enabled;this.error=error;}
        public boolean ok(){return error.isEmpty();}
    }
    private final Transport transport;
    public interface Pause {void waitFor(long milliseconds)throws IOException;}
    private final Pause pause;
    public FavoriteSync(Transport transport){this(transport,milliseconds->{try{Thread.sleep(milliseconds);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("操作已取消",e);}});}
    public FavoriteSync(Transport transport,Pause pause){this.transport=transport;this.pause=pause;}
    public Result update(String sid,boolean desired) {
        boolean current;
        try { current=transport.read().contains(sid); }
        catch(IOException e){return new Result(false,false,"暂时读不到追剧状态，请稍后重试");}
        if(current==desired)return new Result(true,current,"");
        try { transport.write(sid,desired);return new Result(true,desired,""); }
        catch(IOException error){
            boolean actual=current;
            for(long delay:new long[]{0,1000,2000})try {
                if(delay>0)pause.waitFor(delay);
                actual=transport.read().contains(sid);
                if(actual==desired)return new Result(true,actual,"");
            }catch(IOException ignored){ }
            return new Result(true,actual,message(error));
        }
    }
    private static String message(IOException e){return e.getMessage()==null?"追剧暂时未成功，请稍后重试":e.getMessage();}
}
