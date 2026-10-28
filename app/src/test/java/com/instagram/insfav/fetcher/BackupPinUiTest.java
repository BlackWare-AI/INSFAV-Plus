package com.instagram.insfav.fetcher;
import static org.junit.Assert.*;
import android.app.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class BackupPinUiTest {
    final List<EditText> fields=new ArrayList<>();
    void fields(View v){if(v instanceof EditText)fields.add((EditText)v);if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int n=0;n<group.getChildCount();n++)fields(group.getChildAt(n));}}
    Button older(View v){if(v instanceof Button&&((Button)v).getText().toString().contains("8+"))return (Button)v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int n=0;n<group.getChildCount();n++){Button found=older(group.getChildAt(n));if(found!=null)return found;}}return null;}
    @Test public void visibleFourBoxesRequireMatchingPinAndClearOnDismiss(){
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();AtomicReference<char[]> value=new AtomicReference<>();BackupPinUi.show(a,"Settings backup",true,value::set);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();fields(dialog.getWindow().getDecorView());assertEquals(8,fields.size());
        for(EditText field:fields){assertNull(field.getTransformationMethod());assertEquals(android.text.InputType.TYPE_CLASS_NUMBER,field.getInputType());assertFalse(field.isSaveEnabled());}
        for(int n=0;n<4;n++){fields.get(n).setText(String.valueOf(n));fields.get(n+4).setText("9");}dialog.getButton(-1).performClick();assertTrue(dialog.isShowing());assertNull(value.get());
        for(int n=0;n<4;n++)fields.get(n+4).setText(String.valueOf(n));dialog.getButton(-1).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertArrayEquals("0123".toCharArray(),value.get());for(EditText field:fields)assertEquals("",field.getText().toString());a.finish();
    }
    @Test public void olderLongPasswordRemainsVisibleAndImportable(){
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();AtomicReference<char[]> value=new AtomicReference<>();BackupPinUi.show(a,"Import",false,value::set);Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();AlertDialog first=ShadowAlertDialog.getLatestAlertDialog();Button older=older(first.getWindow().getDecorView());assertNotNull(older);older.performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();AlertDialog legacy=ShadowAlertDialog.getLatestAlertDialog();fields(legacy.getWindow().getDecorView());assertEquals(1,fields.size());assertNull(fields.get(0).getTransformationMethod());fields.get(0).setText("existing backup password");legacy.getButton(-1).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertArrayEquals("existing backup password".toCharArray(),value.get());assertEquals("",fields.get(0).getText().toString());a.finish();
    }
}
