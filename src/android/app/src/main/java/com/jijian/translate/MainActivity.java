package com.jijian.translate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TranslateApp app;
    private String pendingAction, appearance = "";
    private boolean notificationPrompt;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); app=TranslateApp.get(this); Ui.prepare(this);
        if(saved!=null){pendingAction=saved.getString("pendingAction");notificationPrompt=saved.getBoolean("notificationPrompt");} build(); handleIntent(getIntent());
    }
    private void build() {
        appearance=app.settings.prefs.getString("theme","system")+Ui.dark(this);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        TranslatorView panel=new TranslatorView(this,false,new TranslatorView.Actions() {
            public void screenshot(){ enable("capture"); }
            public void gallery(){ pickImage(); }
            public void camera(){ openInput(PhotoActivity.class); }
            public void voice(){ openInput(VoiceActivity.class); }
            public void settings(){ startActivity(new Intent(MainActivity.this,SettingsActivity.class)); }
            public void crop(){ if(app.image!=null) startActivity(new Intent(MainActivity.this,CropActivity.class)); }
            public void close(){ enable("bubble"); }
        }); scroll.addView(panel); Ui.content(this,scroll);
    }
    @Override protected void onResume() {
        super.onResume(); if(app==null)return;
        if(!appearance.equals(app.settings.prefs.getString("theme","system")+Ui.dark(this))) { Ui.prepare(this); build(); }
        if(pendingAction!=null && !notificationPrompt && Settings.canDrawOverlays(this)) continueEnable();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);}
    private void handleIntent(Intent intent){
        if(Intent.ACTION_SEND.equals(intent.getAction())){
            if(intent.getType()!=null && intent.getType().startsWith("image/")){
                Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM); if(uri!=null)app.loadImage(uri);
            }else{
                CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if(text!=null){ if(text.length()>20000)app.info("分享内容太长，请分段分享，每段两万字以内。",true); else{app.input(text.toString());app.changed();} }
            }
            intent.setAction(Intent.ACTION_MAIN);
        }
        if(intent.getBooleanExtra("gallery",false)){intent.removeExtra("gallery");pickImage();}
        if(intent.getBooleanExtra("settings",false)){intent.removeExtra("settings");startActivity(new Intent(this,SettingsActivity.class));}
        if(intent.getBooleanExtra("camera",false)){intent.removeExtra("camera");openInput(PhotoActivity.class);}
        if(intent.getBooleanExtra("voice",false)){intent.removeExtra("voice");openInput(VoiceActivity.class);}
    }
    private void openInput(Class<? extends Activity> type){if(app.busy||app.loadingImage){app.info("请先停止当前操作。",true);return;}app.speech.stop();try{startActivity(new Intent(this,type));}catch(Exception e){app.info("输入界面无法打开，请重试。",true);}}
    private void pickImage(){
        if(app.busy||app.loadingImage){app.info("请先停止当前操作。",true);return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,12);}catch(Exception e){app.info("没有可用的图片选择器。",true);}
    }
    private void enable(String action){
        if(app.busy||app.loadingImage){app.info("请先停止当前操作。",true);return;}
        pendingAction=action;
        if(!Settings.canDrawOverlays(this)){
            new android.app.AlertDialog.Builder(this).setTitle("启用悬浮球").setMessage("允许“显示在其他应用上层”，就能在其他页面打开小窗或截屏翻译。")
                .setPositiveButton("去开启",(d,w)->{try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),20);}catch(Exception e){pendingAction=null;app.info("请到系统设置中开启悬浮窗权限。",true);}})
                .setNegativeButton("暂不",(d,w)->pendingAction=null).setOnCancelListener(d->pendingAction=null).show(); return;
        }
        continueEnable();
    }
    private void continueEnable(){
        if(notificationPrompt)return;
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !app.settings.prefs.getBoolean("notificationAsked",false)){
            app.settings.prefs.edit().putBoolean("notificationAsked",true).apply();notificationPrompt=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);return;
        }
        String action=pendingAction; pendingAction=null;
        try{
            startForegroundService(new Intent(this,OverlayService.class).setAction(OverlayService.SHOW));
            if("capture".equals(action)){
                if(!OverlayService.captureReady)startActivity(new Intent(this,CaptureActivity.class).putExtra("autoCapture",false));
                else Toast.makeText(this,"截屏已就绪。切换到目标页面，点悬浮球 → 截屏翻译。",Toast.LENGTH_LONG).show();
            }
            else Toast.makeText(this,"悬浮球已开启，切换到其他页面即可使用。",Toast.LENGTH_LONG).show();
        }catch(Exception e){app.info("悬浮球未能启动，请检查系统权限后重试。",true);}
    }
    @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==22){notificationPrompt=false;if(pendingAction!=null)continueEnable();}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==12&&result==RESULT_OK&&data!=null&&data.getData()!=null)app.loadImage(data.getData());
        if(request==20&&pendingAction!=null){if(Settings.canDrawOverlays(this))continueEnable();else{pendingAction=null;app.info("悬浮窗权限未开启，仍可在主界面翻译。",false);}}
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("pendingAction",pendingAction);state.putBoolean("notificationPrompt",notificationPrompt);}
    @Override protected void onStop(){if(app!=null)app.speech.stop();super.onStop();}
}
