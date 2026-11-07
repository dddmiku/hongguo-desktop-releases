package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.KeyEvent;
import android.widget.EditText;

/** A consistent living-room stage on televisions, boxes and high-density emulator screens. */
public abstract class TvActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        DisplayMetrics m=base.getResources().getDisplayMetrics();
        Configuration c=new Configuration(base.getResources().getConfiguration());
        c.densityDpi=Math.max(160,Math.min(640,Math.round(Math.max(m.widthPixels,m.heightPixels)*160f/960f)));
        super.attachBaseContext(base.createConfigurationContext(c));
    }
    @Override protected void onCreate(Bundle saved) {super.onCreate(saved);immersive();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)immersive();}
    private int routedKey;
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(event.getAction()==KeyEvent.ACTION_UP&&event.getKeyCode()==routedKey){routedKey=0;return true;}
        if(event.getAction()==KeyEvent.ACTION_DOWN){
            View focused=getCurrentFocus();int code=event.getKeyCode(),target=View.NO_ID;
            if(focused!=null&&!(focused instanceof EditText&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT))){
                switch(code){case KeyEvent.KEYCODE_DPAD_UP:target=focused.getNextFocusUpId();break;case KeyEvent.KEYCODE_DPAD_DOWN:target=focused.getNextFocusDownId();break;case KeyEvent.KEYCODE_DPAD_LEFT:target=focused.getNextFocusLeftId();break;case KeyEvent.KEYCODE_DPAD_RIGHT:target=focused.getNextFocusRightId();break;}
                View next=target==View.NO_ID?null:getWindow().getDecorView().findViewById(target);
                if(next!=null&&next.isShown()&&next.isEnabled()&&next.requestFocus()){routedKey=code;return true;}
            }
        }
        return super.dispatchKeyEvent(event);
    }
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
}
