package com.jijian.translate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SpeechPlayback {
    private final TranslateApp app;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    EdgeSpeechClient client=new EdgeSpeechClient();
    private EdgeSpeechClient.Call current;private MediaPlayer player;private MemoryAudio audio;private AudioFocusRequest focus;
    private final AudioManager manager;private Runnable timeout;private int generation;
    boolean active,error;String status="";private boolean listening;
    private final BroadcastReceiver noisy=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){stop();}};
    SpeechPlayback(TranslateApp app){this.app=app;manager=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);}
    void toggle(){if(active)stop();else if(!app.busy&&!app.loadingImage&&app.resultReady&&!app.translation.trim().isEmpty())speak(app.translation,app.settings.target(),app.settings.speechRate());}
    void stop(){stop(true);}
    void stopQuiet(){stop(false);}
    private void stop(boolean notify){
        ++generation;if(current!=null)current.cancel();current=null;releasePlayer();
        if(focus!=null){manager.abandonAudioFocusRequest(focus);focus=null;}
        if(listening){try{app.unregisterReceiver(noisy);}catch(Exception ignored){}listening=false;}
        active=false;error=false;status="";if(notify)app.changed();
    }
    void speak(String text,String language,int rate){
        stop();List<String> parts=EdgeSpeechClient.chunks(text);if(parts.isEmpty())return;
        final int ticket=generation;active=true;
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).setOnAudioFocusChangeListener(change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)stop();},app.main).build();
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){fail("暂时无法播放，请在前台打开翻译或悬浮小窗后重试。");return;}
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(android.os.Build.VERSION.SDK_INT>=33)app.registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(noisy,filter);listening=true;
        next(parts,0,language,rate,ticket,attributes);
    }
    private void next(List<String> parts,int index,String language,int rate,int ticket,AudioAttributes attributes){
        if(ticket!=generation)return;if(index>=parts.size()){stop();return;}
        status=parts.size()==1?"正在准备语音…":"正在准备语音 "+(index+1)+"/"+parts.size()+"…";app.changed();
        EdgeSpeechClient.Call call=new EdgeSpeechClient.Call();current=call;
        timeout=()->{if(ticket==generation){call.cancel();fail("朗读连接超时，请检查网络后重试。");}};app.main.postDelayed(timeout,45000);
        worker.execute(()->{
            try{byte[] audio=client.synthesize(parts.get(index),language,rate,call);app.main.post(()->{
                if(ticket!=generation||call.cancelled)return;removeTimeout();current=null;
                try{
                    MediaPlayer playing=new MediaPlayer();player=playing;playing.setAudioAttributes(attributes);this.audio=new MemoryAudio(audio);playing.setDataSource(this.audio);
                    timeout=()->{if(ticket==generation)fail("语音无法播放，请重试。");};app.main.postDelayed(timeout,15000);
                    playing.setOnPreparedListener(p->{if(ticket!=generation)return;removeTimeout();status=parts.size()==1?"正在朗读 · Edge":"正在朗读 "+(index+1)+"/"+parts.size()+" · Edge";app.changed();p.start();});
                    playing.setOnCompletionListener(p->{if(ticket!=generation)return;releasePlayer();next(parts,index+1,language,rate,ticket,attributes);});
                    playing.setOnErrorListener((p,what,extra)->{if(ticket==generation)fail("语音无法播放，请检查音量或稍后重试。");return true;});playing.prepareAsync();
                }catch(Exception e){fail("语音无法播放，请稍后重试。");}
            });}catch(Exception e){app.main.post(()->{if(ticket==generation&&!call.cancelled)fail("Edge 朗读暂不可用，请检查网络和系统时间后重试。");});}
        });
    }
    private void removeTimeout(){if(timeout!=null)app.main.removeCallbacks(timeout);timeout=null;}
    private void releasePlayer(){removeTimeout();if(player!=null){player.reset();player.release();player=null;}if(audio!=null){audio.close();audio=null;}}
    private void fail(String message){stop();status=message;error=true;app.changed();}
    static final class MemoryAudio extends MediaDataSource {
        private byte[] bytes;
        MemoryAudio(byte[] bytes){this.bytes=bytes;}
        @Override public synchronized int readAt(long position,byte[] buffer,int offset,int size){if(size==0)return 0;if(position<0||position>=bytes.length)return -1;int count=Math.min(size,bytes.length-(int)position);System.arraycopy(bytes,(int)position,buffer,offset,count);return count;}
        @Override public synchronized long getSize(){return bytes.length;}
        @Override public synchronized void close(){bytes=new byte[0];}
    }
}
