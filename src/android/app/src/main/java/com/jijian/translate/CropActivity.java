package com.jijian.translate;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

public final class CropActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);Ui.prepare(this);TranslateApp app=TranslateApp.get(this);
        if(app.image==null||app.busy){finish();return;}
        LinearLayout root=Ui.column(this);int pad=Ui.dp(this,16);root.setPadding(pad,pad,pad,pad);root.addView(Ui.label(this,"拖动框选文字区域",20,true));Ui.gap(root,12);
        RegionView region=new RegionView(this,app.image.bitmap);root.addView(region,new LinearLayout.LayoutParams(-1,0,1));Ui.gap(root,12);
        LinearLayout tools=Ui.row(this);Button cancel=Ui.button(this,"取消",false);cancel.setOnClickListener(v->finish());tools.addView(cancel,Ui.weight());Button rotate=Ui.button(this,"旋转",false);rotate.setOnClickListener(v->region.rotate());tools.addView(rotate,Ui.weight());Button reset=Ui.button(this,"全选",false);reset.setOnClickListener(v->region.reset());tools.addView(reset,Ui.weight());root.addView(tools);Ui.gap(root,8);
        Button use=Ui.button(this,"使用选区",true);use.setOnClickListener(v->{try{app.useBitmap(region.selectedBitmap(),false);finish();}catch(Exception e){app.info("选区无法处理，请重新选择。",true);}});root.addView(use);Ui.content(this,root);
    }
}
