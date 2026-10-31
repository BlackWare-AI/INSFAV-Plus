package com.instagram.insfav.fetcher;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.text.method.DigitsKeyListener;
import android.view.*;
import android.widget.*;
import java.util.Arrays;

/** Visible four-digit PIN controls. Existing long-password archives remain importable. */
public final class BackupPinUi {
    public interface Action { void run(char[] pin); }
    private static int dp(Activity a,int n){return (int)(n*a.getResources().getDisplayMetrics().density+.5f);}
    public static boolean valid(char[] pin){if(pin==null||pin.length!=4)return false;for(char digit:pin)if(digit<'0'||digit>'9')return false;return true;}
    public static void show(Activity a,String title,boolean confirm,Action action){ show(a,title,confirm,false,action); }
    private static void show(Activity a,String title,boolean confirm,boolean legacy,Action action){
        boolean tr=language(a);String ok=tr?"Devam et":"Continue",cancel=tr?"Vazgeç":"Cancel";
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);int pad=dp(a,22);body.setPadding(pad,pad,pad,pad);
        TextView heading=new TextView(a);heading.setText(title);heading.setTextColor(Color.WHITE);heading.setTextSize(20);body.addView(heading);
        TextView note=new TextView(a);note.setText(legacy?(tr?"Eski yedeğinizin parolasını görünür olarak girin.":"Enter the visible password of your older backup."):(tr?"4 haneli PIN · Rakamlar görünür. Yedeği güvenli bir klasörde saklayın.":"4-digit PIN · Digits are visible. Keep the backup in a safe folder."));note.setTextColor(0xffaaaaaa);note.setPadding(0,dp(a,12),0,dp(a,14));body.addView(note);
        EditText[] first=legacy?new EditText[]{input(a,false)}:row(a,body,tr?"PIN":"PIN");if(legacy)body.addView(first[0]);
        EditText[] second=confirm?row(a,body,tr?"PIN tekrar":"Repeat PIN"):new EditText[0];
        if(!confirm&&!legacy){Button old=new Button(a);old.setTextIsSelectable(false);old.setLongClickable(false);old.setText(tr?"Eski parola ile aç (8+ karakter)":"Open with an older password (8+ characters)");body.addView(old);old.setOnClickListener(v->{ /* listener installed below */ });}
        boolean secure=(a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0;a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.BLACK);bg.setCornerRadius(dp(a,26));bg.setStroke(dp(a,1),0xff242424);body.setBackground(bg);
        AlertDialog dialog=new AlertDialog.Builder(a,android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(body).setPositiveButton(ok,null).setNegativeButton(cancel,null).create();
        dialog.setOnDismissListener(d->{for(EditText e:first)e.setText("");for(EditText e:second)e.setText("");if(!secure)a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);});
        if(!confirm&&!legacy)((Button)body.getChildAt(body.getChildCount()-1)).setOnClickListener(v->{dialog.dismiss();show(a,title,false,true,action);});
        dialog.setOnShowListener(d->{dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);dialog.getButton(-1).setTextColor(Color.WHITE);dialog.getButton(-2).setTextColor(0xffaaaaaa);dialog.getButton(-1).setOnClickListener(v->{
            String value=read(first);char[] pin=value.toCharArray();if((legacy?pin.length<8:!valid(pin))||(confirm&&!value.equals(read(second)))){Arrays.fill(pin,'\0');first[0].setError(tr?"PIN'ler aynı 4 rakam olmalı.":"Use four matching digits.");return;}dialog.dismiss();action.run(pin);
        });first[0].requestFocus();});dialog.show();
    }
    private static EditText[] row(Activity a,LinearLayout parent,String label){
        TextView name=new TextView(a);name.setText(label);name.setTextColor(0xffaaaaaa);name.setPadding(0,dp(a,8),0,dp(a,6));parent.addView(name);
        LinearLayout row=new LinearLayout(a);EditText[] cells=new EditText[4];for(int i=0;i<4;i++){final int at=i;EditText cell=input(a,true);cells[i]=cell;LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,58),1);p.setMargins(dp(a,3),0,dp(a,3),0);row.addView(cell,p);cell.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(s.length()==1&&at<3)cells[at+1].requestFocus();}public void afterTextChanged(Editable e){}});cell.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DEL&&event.getAction()==KeyEvent.ACTION_DOWN&&cell.length()==0&&at>0){cells[at-1].requestFocus();cells[at-1].setText("");return true;}return false;});}parent.addView(row);return cells;
    }
    private static String read(EditText[] fields){StringBuilder s=new StringBuilder();for(EditText e:fields)s.append(e.getText());return s.toString();}
    private static EditText input(Activity a,boolean pin){EditText e=new EditText(a);e.setTextColor(Color.WHITE);e.setSingleLine();e.setTextSize(24);e.setGravity(android.view.Gravity.CENTER);e.setInputType(pin?android.text.InputType.TYPE_CLASS_NUMBER:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setTransformationMethod(null);if(pin){e.setKeyListener(DigitsKeyListener.getInstance("0123456789"));e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1)});}e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);return e;}
    private static boolean language(Activity a){return com.instagram.insfav.UiLanguage.context(a).getResources().getConfiguration().getLocales().get(0).getLanguage().equals("tr");}
    private BackupPinUi(){}
}
