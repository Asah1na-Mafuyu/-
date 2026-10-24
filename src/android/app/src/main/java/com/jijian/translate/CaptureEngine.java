package com.jijian.translate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.nio.ByteBuffer;

final class CaptureEngine {
    interface Listener{void stopped(CaptureEngine engine);}
    interface Shot{void finished(Bitmap bitmap,String error);}
    private final Context context;
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private final HandlerThread thread=new HandlerThread("screen-frame");
    private final Handler frames;
    private final Listener listener;
    private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;private Image held;
    private Shot pending;private long started;private int width,height;private volatile boolean released;
    CaptureEngine(Context context,Listener listener){this.context=context;this.listener=listener;thread.start();frames=new Handler(thread.getLooper());}
    void start(int result,android.content.Intent data)throws Exception{
        MediaProjectionManager manager=context.getSystemService(MediaProjectionManager.class);projection=manager.getMediaProjection(result,data);
        if(projection==null)throw new Exception("无法获取截屏会话。");
        projection.registerCallback(new MediaProjection.Callback(){
            @Override public void onStop(){listener.stopped(CaptureEngine.this);}
            @Override public void onCapturedContentResize(int w,int h){resize(w,h);}
        },main);
        int[] size=screenSize(context);int[] target=limited(size[0],size[1]);width=target[0];height=target[1];
        reader=newReader(width,height);
        display=projection.createVirtualDisplay("JiJianTranslate",width,height,context.getResources().getConfiguration().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,frames);
    }
    static int[] screenSize(Context context){
        WindowManager manager=context.getSystemService(WindowManager.class);
        if(Build.VERSION.SDK_INT>=30){Rect bounds=manager.getMaximumWindowMetrics().getBounds();return new int[]{bounds.width(),bounds.height()};}
        DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);return new int[]{metrics.widthPixels,metrics.heightPixels};
    }
    private static int[] limited(int w,int h){double scale=Math.min(1,Math.min(4096.0/Math.max(w,h),Math.sqrt(8_000_000.0/((double)w*h))));return new int[]{Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale))};}
    private ImageReader newReader(int w,int h){
        ImageReader result=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);
        result.setOnImageAvailableListener(images->{
            try{
                if(held!=null){held.close();held=null;}
                Image latest=images.acquireLatestImage();if(latest==null)return;
                if(pending!=null&&!released)held=latest;else latest.close();
            }catch(Exception e){if(pending!=null)complete(null,"无法读取屏幕画面，请重新截屏。");}
        },frames);return result;
    }
    void take(Shot callback){
        frames.post(()->{
            if(released){main.post(()->callback.finished(null,"截屏会话已结束，请重新授权。"));return;}
            if(pending!=null){main.post(()->callback.finished(null,"请先完成当前截屏。"));return;}
            pending=callback;started=android.os.SystemClock.uptimeMillis();frames.postDelayed(this::deliver,260);
        });
    }
    private void deliver(){
        if(released||pending==null)return;
        if(held==null){
            if(android.os.SystemClock.uptimeMillis()-started>3500){complete(null,"没有取得屏幕画面。请重新授权，或检查页面是否禁止截屏。");return;}
            frames.postDelayed(this::deliver,80);return;
        }
        Image image=held;held=null;Bitmap padded=null;
        try{
            Image.Plane plane=image.getPlanes()[0];int stride=plane.getPixelStride();if(stride!=4)throw new Exception("Unsupported pixels");
            int paddedWidth=plane.getRowStride()/stride;ByteBuffer pixels=plane.getBuffer();pixels.rewind();int bytes=paddedWidth*image.getHeight()*4;
            if(pixels.remaining()<bytes){ByteBuffer full=ByteBuffer.allocate(bytes);full.put(pixels);full.rewind();pixels=full;}
            padded=Bitmap.createBitmap(paddedWidth,image.getHeight(),Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(pixels);
            Bitmap bitmap=Bitmap.createBitmap(padded,0,0,image.getWidth(),image.getHeight());if(bitmap!=padded)padded.recycle();padded=null;complete(bitmap,null);
        }catch(Exception|OutOfMemoryError e){if(padded!=null)padded.recycle();complete(null,"屏幕图片读取失败，请重试或减小屏幕分辨率。");}
        finally{image.close();}
    }
    private void complete(Bitmap bitmap,String error){Shot callback=pending;pending=null;if(held!=null){held.close();held=null;}if(callback!=null)main.post(()->{if(!released)callback.finished(bitmap,error);});}
    void resize(int w,int h){
        if(w<=0||h<=0)return;frames.post(()->{
            if(released||display==null)return;int[] target=limited(w,h);if(target[0]==width&&target[1]==height)return;
            if(pending!=null)complete(null,"屏幕方向或尺寸已改变，请重新截屏。");
            if(held!=null){held.close();held=null;}ImageReader old=reader;
            try{width=target[0];height=target[1];reader=newReader(width,height);display.resize(width,height,context.getResources().getConfiguration().densityDpi);display.setSurface(reader.getSurface());}
            catch(Exception e){main.post(()->listener.stopped(this));}finally{old.close();}
        });
    }
    void close(){
        if(released)return;released=true;
        if(projection!=null){projection.stop();projection=null;}
        frames.post(()->{pending=null;if(held!=null){held.close();held=null;}if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;}thread.quitSafely();});
    }
}
