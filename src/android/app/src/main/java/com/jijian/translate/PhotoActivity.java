package com.jijian.translate;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class PhotoActivity extends Activity {
    private TranslateApp app;private Uri photo;private boolean launched,reading,destroyed,preview;private int generation;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=TranslateApp.get(this);Ui.prepare(this);app.speech.stop();
        if(app.busy||app.loadingImage){app.info("请先停止当前操作，再拍照。",true);finish();return;}
        LinearLayout root=Ui.column(this);int pad=Ui.dp(this,20);root.setPadding(pad,pad,pad,pad);TextView state=Ui.label(this,"正在打开相机…",18,true);root.addView(state);Ui.gap(root,18);Button cancel=Ui.button(this,"取消",false);cancel.setOnClickListener(v->finish());root.addView(cancel);Ui.content(this,root);
        if(saved!=null){String value=saved.getString("photo");photo=value==null?null:Uri.parse(value);launched=saved.getBoolean("launched");}
        if(!launched)root.post(this::takePhoto);
        else if(saved!=null&&saved.getBoolean("preview")){if(photo!=null)readPhoto();else finish();}
    }
    private void takePhoto(){
        if(isFinishing()||destroyed)return;
        preview=false;
        try{
            PhotoProvider.discard(this,photo);photo=PhotoProvider.create(this);
            Intent camera=cameraIntent(photo);
            if(camera.resolveActivity(getPackageManager())==null){app.info("手机未提供可用相机，请启用系统相机后重试。",true);finish();return;}
            launched=true;startActivityForResult(camera,31);
        }catch(Exception e){app.info("相机无法打开，请检查系统相机是否可用。",true);finish();}
    }
    static Intent cameraIntent(Uri uri){Intent intent=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("待翻译照片",uri));return intent;}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=31)return;if(result==RESULT_OK)readPhoto();else{app.info("已取消拍照。",false);finish();}}
    private void readPhoto(){
        if(photo==null||reading)return;
        try{if(!PhotoProvider.file(this,photo).isFile()||PhotoProvider.file(this,photo).length()==0){app.info("相机没有返回照片，请重新拍摄。",true);finish();return;}}catch(Exception e){app.info("照片无法读取，请重拍。",true);finish();return;}
        reading=true;app.loadingImage=true;final Uri selected=photo;final int ticket=++generation;
        app.worker.execute(()->{
            try{ImageTools.ImageData data=ImageTools.load(this,selected);app.main.post(()->{
                if(destroyed||isFinishing()||ticket!=generation)return;reading=false;app.loadingImage=false;showPreview(data.bitmap);
            });}catch(Exception e){app.main.post(()->{if(destroyed||ticket!=generation)return;reading=false;app.loadingImage=false;app.info("照片无法读取或尺寸过大，请重拍。",true);finish();});}
        });
    }
    private void showPreview(Bitmap bitmap){
        preview=true;
        LinearLayout root=Ui.column(this);int pad=Ui.dp(this,16);root.setPadding(pad,pad,pad,pad);
        root.addView(Ui.label(this,"拍照翻译",22,true));Ui.gap(root,8);TextView hint=Ui.label(this,"拖动框选文字；确认后翻译选区。",13,false);hint.setTextColor(Ui.muted(this));root.addView(hint);Ui.gap(root,12);
        RegionView region=new RegionView(this,bitmap);root.addView(region,new LinearLayout.LayoutParams(-1,0,1));Ui.gap(root,12);
        LinearLayout tools=Ui.row(this);Button cancel=Ui.button(this,"取消",false);cancel.setOnClickListener(v->finish());tools.addView(cancel,Ui.weight());Button again=Ui.button(this,"重拍",false);again.setOnClickListener(v->takePhoto());tools.addView(again,Ui.weight());Button all=Ui.button(this,"全选",false);all.setOnClickListener(v->region.reset());tools.addView(all,Ui.weight());Button rotate=Ui.button(this,"旋转",false);rotate.setOnClickListener(v->region.rotate());tools.addView(rotate,Ui.weight());root.addView(tools);Ui.gap(root,10);
        Button translate=Ui.button(this,"翻译照片",true);translate.setOnClickListener(v->{try{Bitmap selected=region.selectedBitmap();app.useBitmap(selected,true);finish();}catch(Exception e){app.info("选区无法处理，请重新框选。",true);}});root.addView(translate);Ui.content(this,root);
    }
    @Override protected void onSaveInstanceState(Bundle saved){super.onSaveInstanceState(saved);if(photo!=null)saved.putString("photo",photo.toString());saved.putBoolean("launched",launched);saved.putBoolean("preview",preview||reading);}
    @Override protected void onDestroy(){destroyed=true;++generation;if(reading){reading=false;app.loadingImage=false;}if(isFinishing())PhotoProvider.discard(this,photo);super.onDestroy();}
}
