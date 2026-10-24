package com.jijian.translate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class OverlayService extends Service {
    static final String SHOW="show",START_CAPTURE="capture",STOP="stop",END_CAPTURE="end-capture";
    static boolean captureReady;
    private static final String CHANNEL="floating-translation";
    private TranslateApp app;private WindowManager windows;private TextView ball;private View menu,panel,crop;
    private WindowManager.LayoutParams ballParams;private CaptureEngine engine;private boolean taking,destroyed;
    private Context ui;
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){app.speech.stop();endProjection();remove(crop);crop=null;remove(menu);menu=null;remove(panel);panel=null;foreground(false);showBall();app.info("屏幕已关闭，截屏会话已结束。下次截屏时重新确认即可。",false);}
    }};
    @Override public void onCreate(){super.onCreate();app=TranslateApp.get(this);windows=getSystemService(WindowManager.class);ui=Ui.themed(this);NotificationChannel channel=new NotificationChannel(CHANNEL,"悬浮翻译",NotificationManager.IMPORTANCE_LOW);channel.setDescription("悬浮球与用户主动开启的截屏会话");getSystemService(NotificationManager.class).createNotificationChannel(channel);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}String action=intent.getAction();
        if(STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        try{
            if(START_CAPTURE.equals(action)){
                endProjection();foreground(true);Intent data=intent.getParcelableExtra("captureData");
                if(data==null)throw new IllegalArgumentException();
                engine=new CaptureEngine(this,this::projectionStopped);engine.start(intent.getIntExtra("captureResult",0),data);captureReady=true;showBall();foreground(true);
                if(intent.getBooleanExtra("autoCapture",true))app.main.postDelayed(()->{if(!destroyed)capture();},420);
                else note("截屏已就绪。切换到目标页面，点悬浮球 → 截屏翻译。");
            }else{if(END_CAPTURE.equals(action)){endProjection();note("截屏会话已结束。");}foreground(engine!=null);showBall();}
        }catch(Exception e){endProjection();taking=false;try{foreground(false);showBall();}catch(Exception ignored){stopSelf();}note("截屏或悬浮窗未能启动，请检查系统授权后重试。");}
        return START_NOT_STICKY;
    }
    private void foreground(boolean capturing){
        int immutable=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),immutable);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,OverlayService.class).setAction(STOP),immutable);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_translate).setContentTitle("极简翻译")
            .setContentText(capturing?"截屏会话已就绪 · 仅主动框选后上传":"悬浮球已开启 · 点此返回")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE);
        if(capturing){PendingIntent end=PendingIntent.getService(this,3,new Intent(this,OverlayService.class).setAction(END_CAPTURE),immutable);builder.addAction(new Notification.Action.Builder(null,"结束截屏",end).build());}
        builder.addAction(new Notification.Action.Builder(null,"关闭",stop).build());
        int type=Build.VERSION.SDK_INT>=34?ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE:0;
        if(capturing)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(7,builder.build(),type);
    }
    private WindowManager.LayoutParams layout(int w,int h,boolean focus){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|(focus?0:WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.START;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;return p;
    }
    private void showBall(){
        if(destroyed)return;
        if(ball!=null){ball.setVisibility(crop==null&&panel==null?View.VISIBLE:View.GONE);return;}
        ui=Ui.themed(this);ball=Ui.label(ui,"译",22,true);ball.setTextColor(Color.WHITE);ball.setGravity(Gravity.CENTER);ball.setContentDescription("极简翻译悬浮球，点击打开，拖动移动");ball.setBackground(Ui.shape(Ui.BLUE,50,ui));ball.setElevation(Ui.dp(ui,6));
        ball.setOnClickListener(v->showMenu());
        int size=Ui.dp(ui,50);int[] screen=CaptureEngine.screenSize(this);ballParams=layout(size,size,false);
        ballParams.x=(int)((screen[0]-size)*app.settings.prefs.getFloat("ball.x",0.96f));ballParams.y=(int)((screen[1]-size)*app.settings.prefs.getFloat("ball.y",0.42f));clampBall();
        final float[] start=new float[4];final boolean[] moved={false};
        ball.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){remove(menu);menu=null;start[0]=event.getRawX();start[1]=event.getRawY();start[2]=ballParams.x;start[3]=ballParams.y;moved[0]=false;return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getRawX()-start[0],dy=event.getRawY()-start[1];if(Math.hypot(dx,dy)>Ui.dp(ui,6))moved[0]=true;if(moved[0]){ballParams.x=(int)(start[2]+dx);ballParams.y=(int)(start[3]+dy);clampBall();updateBall();}return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){
                if(moved[0]){int[] bounds=CaptureEngine.screenSize(this);ballParams.x=ballParams.x<bounds[0]/2?Ui.dp(ui,8):bounds[0]-size-Ui.dp(ui,8);clampBall();updateBall();app.settings.prefs.edit().putFloat("ball.x",ballParams.x/(float)Math.max(1,bounds[0]-size)).putFloat("ball.y",ballParams.y/(float)Math.max(1,bounds[1]-size)).apply();}
                else v.performClick();return true;
            }return true;
        });
        try{windows.addView(ball,ballParams);}catch(Exception e){ball=null;note("无法显示悬浮球，请重新开启悬浮窗权限。");stopSelf();}
    }
    private void clampBall(){if(ballParams==null)return;int[] size=CaptureEngine.screenSize(this);ballParams.x=Math.max(0,Math.min(size[0]-ballParams.width,ballParams.x));ballParams.y=Math.max(Ui.dp(this,28),Math.min(size[1]-ballParams.height-Ui.dp(this,24),ballParams.y));}
    private void updateBall(){try{if(ball!=null)windows.updateViewLayout(ball,ballParams);}catch(Exception e){stopSelf();}}
    private void showMenu(){
        if(menu!=null||taking||crop!=null)return;LinearLayout root=Ui.column(ui);root.setPadding(Ui.dp(ui,8),Ui.dp(ui,8),Ui.dp(ui,8),Ui.dp(ui,8));root.setBackground(Ui.shape(Ui.card(ui),16,ui));root.setElevation(Ui.dp(ui,8));
        Button shot=Ui.button(ui,"截屏翻译",true);shot.setOnClickListener(v->capture());root.addView(shot);Ui.gap(root,6);Button text=Ui.button(ui,"文字小窗",false);text.setOnClickListener(v->showPanel());root.addView(text);Button close=Ui.button(ui,"关闭悬浮球",false);close.setOnClickListener(v->stopSelf());root.addView(close);
        int width=Ui.dp(ui,180);WindowManager.LayoutParams p=layout(width,-2,false);int[] size=CaptureEngine.screenSize(this);p.x=Math.max(Ui.dp(ui,8),Math.min(size[0]-width-Ui.dp(ui,8),ballParams.x-width));p.y=Math.max(Ui.dp(ui,32),Math.min(size[1]-Ui.dp(ui,190),ballParams.y));p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        root.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){remove(menu);menu=null;return true;}return false;});menu=root;add(root,p);
    }
    private void showPanel(){
        remove(menu);menu=null;remove(panel);panel=null;if(ball!=null)ball.setVisibility(View.GONE);ui=Ui.themed(this);
        ScrollView scroll=new ScrollView(ui);scroll.setFillViewport(true);scroll.setBackground(Ui.shape(Ui.page(ui),20,ui));scroll.setClipToOutline(true);scroll.setElevation(Ui.dp(ui,10));
        TranslatorView view=new TranslatorView(ui,true,new TranslatorView.Actions(){
            public void screenshot(){capture();}
            public void gallery(){openMain("gallery");}
            public void camera(){openMain("camera");}
            public void voice(){openMain("voice");}
            public void settings(){openMain("settings");}
            public void crop(){if(app.image!=null)showCrop(app.image.bitmap,false);}
            public void close(){closePanel();}
        });scroll.addView(view);int[] size=CaptureEngine.screenSize(this);int width=Math.min(Ui.dp(ui,410),size[0]-Ui.dp(ui,20));WindowManager.LayoutParams p=layout(width,Math.min(Ui.dp(ui,650),(int)(size[1]*0.73)),true);p.x=(size[0]-width)/2;p.y=Math.max(Ui.dp(ui,36),(int)(size[1]*0.10));p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        scroll.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){closePanel();return true;}return false;});panel=scroll;add(scroll,p);
    }
    private void closePanel(){app.speech.stop();remove(panel);panel=null;showBall();}
    private void openMain(String action){closePanel();try{startActivity(new Intent(this,MainActivity.class).putExtra(action,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}catch(Exception e){note("请从桌面打开极简翻译。");}}
    private void capture(){
        if(app.busy||app.loadingImage){note("请先停止当前翻译。");return;}if(taking||crop!=null)return;
        app.speech.stop();
        remove(menu);menu=null;remove(panel);panel=null;
        if(engine==null){showBall();try{startActivity(new Intent(this,CaptureActivity.class).putExtra("autoCapture",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){note("请回到主界面开启截屏授权。");}return;}
        taking=true;if(ball!=null)ball.setVisibility(View.GONE);
        engine.take((bitmap,error)->{taking=false;if(destroyed)return;if(bitmap==null){showBall();note(error);return;}showCrop(bitmap,true);});
    }
    private void showCrop(Bitmap bitmap,boolean autoTranslate){
        remove(menu);menu=null;remove(panel);panel=null;remove(crop);crop=null;if(ball!=null)ball.setVisibility(View.GONE);
        ui=Ui.themed(this);LinearLayout root=Ui.column(ui);int pad=Ui.dp(ui,16);root.setPadding(pad,Ui.dp(ui,36),pad,Ui.dp(ui,28));root.setBackgroundColor(Ui.page(ui));
        TextView title=Ui.label(ui,"拖动框选要翻译的文字",19,true);root.addView(title);TextView hint=Ui.label(ui,"只上传选中区域；空白画面可能是页面禁止截屏。",12,false);hint.setTextColor(Ui.muted(ui));root.addView(hint);Ui.gap(root,12);
        RegionView region=new RegionView(ui,bitmap);root.addView(region,new LinearLayout.LayoutParams(-1,0,1));Ui.gap(root,10);LinearLayout tools=Ui.row(ui);
        Button cancel=Ui.button(ui,"取消",false);cancel.setOnClickListener(v->{remove(crop);crop=null;showBall();});tools.addView(cancel,Ui.weight());Button all=Ui.button(ui,"全选",false);all.setOnClickListener(v->region.reset());tools.addView(all,Ui.weight());Button rotate=Ui.button(ui,"旋转",false);rotate.setOnClickListener(v->region.rotate());tools.addView(rotate,Ui.weight());root.addView(tools);Ui.gap(root,8);
        Button confirm=Ui.button(ui,autoTranslate?"翻译选区":"使用选区",true);confirm.setOnClickListener(v->{try{Bitmap selected=region.selectedBitmap();remove(crop);crop=null;showPanel();app.useBitmap(selected,autoTranslate);}catch(Exception e){note("选区无法处理，请重新框选。");}});root.addView(confirm);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(pad,Math.max(Ui.dp(ui,18),bars.top),pad,Math.max(Ui.dp(ui,18),bars.bottom));return insets;});
        WindowManager.LayoutParams p=layout(-1,-1,true);p.flags|=WindowManager.LayoutParams.FLAG_SECURE;crop=root;add(root,p);root.requestApplyInsets();
    }
    private void projectionStopped(CaptureEngine ended){if(engine!=ended)return;engine=null;captureReady=false;ended.close();taking=false;if(!destroyed){foreground(false);showBall();app.info("截屏会话已结束，下次截屏时重新确认即可。",false);}}
    private void endProjection(){CaptureEngine current=engine;engine=null;captureReady=false;taking=false;if(current!=null)current.close();}
    private void add(View view,WindowManager.LayoutParams p){try{windows.addView(view,p);}catch(Exception e){note("无法显示窗口，请重新检查悬浮窗权限。");stopSelf();}}
    private void remove(View view){if(view==null)return;try{windows.removeView(view);}catch(Exception ignored){}}
    private void note(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);remove(menu);menu=null;remove(panel);panel=null;if(crop!=null){remove(crop);crop=null;note("屏幕方向已改变，请重新截屏。");}clampBall();updateBall();if(engine!=null&&Build.VERSION.SDK_INT<34){int[] size=CaptureEngine.screenSize(this);engine.resize(size[0],size[1]);}if(ball!=null)ball.setVisibility(View.VISIBLE);}
    @Override public void onDestroy(){destroyed=true;app.speech.stop();endProjection();try{unregisterReceiver(screenOff);}catch(Exception ignored){}remove(menu);remove(panel);remove(crop);remove(ball);menu=panel=crop=ball=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
