package com.jijian.translate;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public final class CaptureActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"请先开启悬浮窗权限。",Toast.LENGTH_LONG).show();complete();return;}
        if(saved==null){
            MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
            Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
            try{startActivityForResult(request,30);}catch(Exception e){Toast.makeText(this,"系统无法启动截屏授权。",Toast.LENGTH_LONG).show();complete();}
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==30){
            if(result==RESULT_OK&&data!=null){
                try{startForegroundService(new Intent(this,OverlayService.class).setAction(OverlayService.START_CAPTURE)
                    .putExtra("captureData",data).putExtra("captureResult",result).putExtra("autoCapture",getIntent().getBooleanExtra("autoCapture",true)));}
                catch(Exception e){Toast.makeText(this,"截屏服务未能启动，请重新开启悬浮球。",Toast.LENGTH_LONG).show();}
            }else Toast.makeText(this,"已取消截屏授权，可继续文字翻译。",Toast.LENGTH_SHORT).show();
            complete();
        }
    }
    private void complete(){if(isTaskRoot())finishAndRemoveTask();else finish();}
}
