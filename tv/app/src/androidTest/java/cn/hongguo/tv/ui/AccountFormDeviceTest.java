package cn.hongguo.tv.ui;

import android.app.Activity;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import cn.hongguo.tv.account.Account;

/** Authentication UI transitions use the isolated QA identity, without sending an SMS. */
public class AccountFormDeviceTest extends InstrumentationTestCase {
    private AccountActivity activity;
    @Override protected void setUp() throws Exception {
        super.setUp();assertTrue(getInstrumentation().getTargetContext().getPackageName().endsWith(".qa"));
        Account.clear(getInstrumentation().getTargetContext());
    }
    @Override protected void tearDown() throws Exception {
        if(activity!=null)getInstrumentation().runOnMainSync(()->activity.finish());
        Account.clear(getInstrumentation().getTargetContext());super.tearDown();
    }
    private void open() { activity=(AccountActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private Object field(String name)throws Exception{Field f=AccountActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void invoke(String name,Class<?>[] types,Object...args)throws Exception{Method m=AccountActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);getInstrumentation().runOnMainSync(()->{try{m.invoke(activity,args);}catch(Exception e){throw new AssertionError(e);}});}

    public void testSuccessfulLoginHidesFormKeypadAndStaleFocus()throws Exception{
        open();invoke("showKeypad",new Class<?>[]{int.class},1);
        View keypad=(View)field("keypadHost");assertEquals(View.VISIBLE,keypad.getVisibility());
        // Local fixture exercises AccountActivity.refresh, the exact successful-login UI path.
        Account.save(getInstrumentation().getTargetContext(),"qa-local-fixture", "", "test-account", "验收账号", "");
        invoke("refresh",new Class<?>[0]);Thread.sleep(300);getInstrumentation().waitForIdleSync();
        assertEquals(View.GONE,keypad.getVisibility());assertEquals(View.GONE,((View)field("loginForm")).getVisibility());
        assertFalse((Boolean)field("keypadShown"));assertEquals(0,((ScrollView)field("pageSv")).getPaddingBottom());
        assertEquals("account-history",activity.getCurrentFocus().getTag());
        invoke("showKeypad",new Class<?>[]{int.class},0);Thread.sleep(200);assertEquals(View.GONE,keypad.getVisibility());
        getInstrumentation().runOnMainSync(()->activity.finish());activity=null;open();Thread.sleep(200);
        assertEquals(View.GONE,((View)field("keypadHost")).getVisibility());assertEquals(View.GONE,((View)field("loginForm")).getVisibility());
    }

    public void testExplicitCloseDoesNotReopenViaInputFocus()throws Exception{
        open();invoke("showKeypad",new Class<?>[]{int.class},0);
        View keypad=(View)field("keypadHost"),close=(View)field("keypadClose");
        getInstrumentation().runOnMainSync(()->{close.setFocusableInTouchMode(true);close.requestFocus();});
        invoke("hideKeypad",new Class<?>[0]);Thread.sleep(300);
        assertEquals(View.GONE,keypad.getVisibility());assertFalse((Boolean)field("keypadShown"));
        assertEquals(0,((ScrollView)field("pageSv")).getPaddingBottom());
        invoke("showKeypad",new Class<?>[]{int.class},1);assertEquals(View.VISIBLE,keypad.getVisibility());
    }
}
