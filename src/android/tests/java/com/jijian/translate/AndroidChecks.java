package com.jijian.translate;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

public final class AndroidChecks extends Instrumentation {
    private final ArrayList<String> report=new ArrayList<>();private int failed;private boolean edgeLive,inputChecks;
    interface Check{void run()throws Exception;}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);edgeLive=arguments!=null&&"true".equals(arguments.getString("edgeLive"));inputChecks=arguments!=null&&"true".equals(arguments.getString("inputChecks"));start();}
    @Override public void onStart(){
        try{checks();}catch(Throwable e){failed++;report.add("FAIL diagnostics | "+e.getClass().getSimpleName()+": "+e.getMessage());}
        Bundle result=new Bundle();StringBuilder body=new StringBuilder("\nAndroid diagnostics: "+(report.size()-failed)+" passed, "+failed+" failed\n");for(String line:report)body.append(line).append('\n');result.putString("stream",body.toString());result.putInt("failed",failed);finish(failed==0?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void check(String name,Check work){try{work.run();report.add("PASS "+name);}catch(Throwable e){failed++;report.add("FAIL "+name+" | "+e.getClass().getSimpleName()+": "+e.getMessage());}}
    private static void require(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    private static class Fake extends HttpURLConnection {
        final int code;final String response;final ByteArrayOutputStream body=new ByteArrayOutputStream();
        Fake(URL url,int code,String response){super(url);this.code=code;this.response=response;}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
        public int getResponseCode(){return code;}public OutputStream getOutputStream(){return body;}
        public InputStream getInputStream(){return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));}
    }
    private static String stream(String text,String finish,boolean done)throws Exception{
        String content=new JSONObject().put("choices",new org.json.JSONArray().put(new JSONObject().put("delta",new JSONObject().put("content",text)).put("finish_reason",JSONObject.NULL))).toString();
        String end=new JSONObject().put("choices",new org.json.JSONArray().put(new JSONObject().put("delta",new JSONObject()).put("finish_reason",finish))).toString();
        return "data: "+content+"\n\ndata: "+end+"\n\ndata: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30}}\n\n"+(done?"data: [DONE]\n\n":"");
    }
    private Context isolated(){
        Context target=getTargetContext();String suffix="diagnostic-"+UUID.randomUUID();File folder=new File(target.getFilesDir(),suffix);folder.mkdirs();
        return new ContextWrapper(target){
            @Override public File getFilesDir(){return folder;}
            @Override public String getPackageName(){return super.getPackageName()+"."+suffix;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return target.getSharedPreferences(suffix+"-"+name,mode);}
        };
    }
    private void checks()throws Exception{
        Context context=isolated();SecurePrefs prefs=new SecurePrefs(context);
        check("camera temporary photo URI, write/read grant, cleanup and traversal rejection",()->{
            Context owner=getTargetContext();android.net.Uri uri=PhotoProvider.create(owner);File file=PhotoProvider.file(owner,uri);require(file.isFile()&&file.getParentFile().equals(new File(owner.getCacheDir(),"camera").getCanonicalFile()),"wrong folder");
            Intent intent=PhotoActivity.cameraIntent(uri);require(android.provider.MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())&&uri.equals(intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT)),"camera output");require((intent.getFlags()&3)==3&&intent.getClipData()!=null&&uri.equals(intent.getClipData().getItemAt(0).getUri()),"camera URI grant");
            try(OutputStream out=owner.getContentResolver().openOutputStream(uri)){out.write(new byte[]{1,2,3});}require(file.length()==3,"provider write");try(InputStream in=owner.getContentResolver().openInputStream(uri)){require(in.read()==1,"provider read");}
            for(String bad:new String[]{"content://com.jijian.translate.camera/../usage.json","content://com.jijian.translate.camera/%2e%2e%2fusage.json","content://com.jijian.translate.camera/key.dat","content://other/photo-00000000000000000000000000000000.jpg"}){boolean rejected=false;try{PhotoProvider.file(owner,android.net.Uri.parse(bad));}catch(java.io.FileNotFoundException e){rejected=true;}require(rejected,"untrusted photo URI accepted");}
            PhotoProvider.discard(owner,uri);require(!file.exists(),"photo not deleted");
        });
        check("voice languages, source selection, result parsing, errors and no audio payload",()->{
            require(VoiceActivity.initialLanguage("日本語","en-US",java.util.Locale.CHINA)==3,"source priority");require(VoiceActivity.initialLanguage("自动识别","ko-KR",java.util.Locale.CHINA)==4,"saved language");require(VoiceActivity.initialLanguage("自动识别","",java.util.Locale.TAIWAN)==1,"locale language");
            for(String tag:VoiceActivity.TAGS){Intent request=VoiceActivity.recognitionIntent(tag);require(tag.equals(request.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))&&request.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS,false),"speech language");}
            Bundle result=new Bundle();result.putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(java.util.Arrays.asList(""," hello ")));require(VoiceActivity.firstResult(result,android.speech.SpeechRecognizer.RESULTS_RECOGNITION).equals("hello"),"result trim");require(VoiceActivity.firstResult(null,"x").isEmpty(),"empty result");
            require(VoiceActivity.errorMessage(android.speech.SpeechRecognizer.ERROR_NETWORK).contains("网络")&&VoiceActivity.errorMessage(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("权限"),"voice errors");
        });
        check("Edge GEC v2 matches upstream fixed-time vectors",()->{
            require(EdgeToken.generate(0).equals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6"),"epoch token");
            require(EdgeToken.generate(1792627200).equals("C0796C264451D87E-0D4CBDF3F0CB24C1EFFFF53DEE91E99290B1F8EF64D1A53406AD915C4849B487"),"2026 token");
            require(EdgeToken.generate(2000000000).equals("2D62557F1142BC5D-FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E"),"future token");
        });
        check("speech voices, Unicode chunk boundaries, XML escaping",()->{
            StringBuilder b=new StringBuilder();for(int i=0;i<899;i++)b.append('中');b.append("😀<&\u0001");for(int i=0;i<1300;i++)b.append('語');String text=b.toString();StringBuilder combined=new StringBuilder();
            java.util.List<String> parts=EdgeSpeechClient.chunks(text);for(String part:parts){require(part.length()<=900&&!Character.isHighSurrogate(part.charAt(part.length()-1)),"unsafe boundary");combined.append(part);}require(parts.size()>1&&combined.toString().equals(text.replace('\u0001',' ')),"text lost");
            String xml=EdgeSpeechClient.ssml("</prosody><audio src='http://bad/'> &",EdgeSpeechClient.voice("简体中文"),20);require(!xml.contains("<audio")&&xml.contains("&lt;audio")&&xml.contains("rate='+20%'"),"unescaped input");for(String language:TranslatorView.TARGETS)require(EdgeSpeechClient.voice(language).endsWith("Neural"),language);require(!prefs.autoSpeak(),"autoplay must default off");
        });
        check("speech binary framing, damaged frame rejection, memory audio close",()->{
            byte[] header="Path:audio\r\nContent-Type:audio/mpeg\r\n".getBytes(StandardCharsets.UTF_8);byte[] frame=new byte[header.length+5];frame[0]=(byte)(header.length>>>8);frame[1]=(byte)header.length;System.arraycopy(header,0,frame,2,header.length);frame[frame.length-3]=1;frame[frame.length-2]=2;frame[frame.length-1]=3;
            require(java.util.Arrays.equals(EdgeSpeechClient.audioPayload(frame),new byte[]{1,2,3}),"audio offset");boolean rejected=false;try{EdgeSpeechClient.audioPayload(new byte[]{(byte)255,(byte)255,0});}catch(java.io.IOException e){rejected=true;}require(rejected,"damaged frame accepted");
            SpeechPlayback.MemoryAudio audio=new SpeechPlayback.MemoryAudio(new byte[]{1,2,3});byte[] read=new byte[6];require(audio.readAt(1,read,2,4)==2&&read[2]==2&&read[3]==3&&audio.readAt(3,read,0,1)==-1,"read bounds");audio.close();require(audio.getSize()==0,"audio retained");
        });
        check("Android Keystore encrypt, decrypt, no plaintext, clear",()->{
            String placeholder="diagnostic-placeholder-not-a-real-key";prefs.saveKey(placeholder);require(prefs.loadKey().equals(placeholder),"decrypt mismatch");require(!prefs.prefs.getString("key.enc","").contains(placeholder),"plaintext persisted");prefs.saveKey("");require(prefs.loadKey().isEmpty(),"clear failed");
        });
        check("text payload, no history, thinking disabled",()->{
            JSONObject payload=ApiClient.payload("hello","自动识别","简体中文",null);require(payload.getString("model").equals(ApiClient.MODEL),"model");require(payload.getJSONObject("thinking").getString("type").equals("disabled"),"thinking");require(payload.getJSONArray("messages").length()==2,"history");require(payload.getBoolean("stream")&&payload.getJSONObject("stream_options").getBoolean("include_usage"),"stream usage");
        });
        check("image payload contains PNG, original detail, JSON format",()->{
            Bitmap bitmap=Bitmap.createBitmap(20,12,Bitmap.Config.ARGB_8888);ImageTools.ImageData data=ImageTools.fromBitmap(bitmap);JSONObject payload=ApiClient.payload("","自动识别","English",data);String url=payload.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url");require(url.startsWith("data:image/png;base64,"),"image url");require(payload.getJSONObject("response_format").getString("type").equals("json_object"),"json");
        });
        check("text streaming, usage, official URL, one request",()->{
            final int[] count={0};final Fake[] sent={null};ApiClient api=new ApiClient(url->{require(url.toString().equals("https://api.deepseek.com/chat/completions"),"endpoint");count[0]++;return sent[0]=new Fake(url,200,stream("你好","stop",true));});
            ApiClient.Result result=api.translate("placeholder","hello","自动识别","简体中文",null,new ApiClient.Call(),null);require(result.translation.equals("你好")&&result.usage.input==120&&result.usage.output==30,"result");require(count[0]==1&&sent[0].body.size()>0,"repeated or missing request");
        });
        check("image structured original and translation",()->{
            ImageTools.ImageData data=ImageTools.fromBitmap(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));ApiClient api=new ApiClient(url->new Fake(url,200,stream("{\"original\":\"hello\",\"translation\":\"你好\"}","stop",true)));ApiClient.Result result=api.translate("placeholder","","自动识别","简体中文",data,new ApiClient.Call(),null);require(result.original.equals("hello")&&result.translation.equals("你好"),"image result");
        });
        for(int status:new int[]{401,402,429})check("HTTP "+status+" has no automatic retry or generation charge",()->{
            final int[] count={0};ApiClient api=new ApiClient(url->{count[0]++;return new Fake(url,status,"");});
            try{api.translate("placeholder","hello","自动识别","简体中文",null,new ApiClient.Call(),null);throw new Exception("unexpected success");}catch(ApiClient.Failure e){require(!e.mayCharge&&count[0]==1,"charged/retried");}
        });
        check("broken stream cannot be a success",()->{
            ApiClient api=new ApiClient(url->new Fake(url,200,stream("partial","stop",false)));
            try{api.translate("placeholder","hello","auto","中文",null,new ApiClient.Call(),null);throw new Exception("unexpected success");}catch(ApiClient.Failure e){require(e.mayCharge&&e.usage!=null,"usage lost");}
        });
        check("output truncation is incomplete and retains usage",()->{
            ApiClient api=new ApiClient(url->new Fake(url,200,stream("partial","length",true)));
            try{api.translate("placeholder","hello","auto","中文",null,new ApiClient.Call(),null);throw new Exception("unexpected success");}catch(ApiClient.Failure e){require(e.mayCharge&&e.usage!=null&&e.getMessage().contains("上限"),"truncation");}
        });
        check("submitted cancellation remains possibly charged",()->{
            ApiClient.Call call=new ApiClient.Call();ApiClient api=new ApiClient(url->new Fake(url,200,stream("partial","stop",true)));
            try{api.translate("placeholder","hello","auto","中文",null,call,part->call.cancel());throw new Exception("unexpected success");}catch(ApiClient.Failure e){require(e.mayCharge&&call.submitted,"cancel lost submitted state");}
        });
        check("budget persistence, rollback, cancellation pending, actual usage",()->{
            Context local=isolated();UsageLedger ledger=new UsageLedger(local);UsageLedger.Ticket first=ledger.reserve(0.05,1);require(new UsageLedger(local).totals()[1]>=0.05,"pending not persisted");ledger.settle(first,null,true);require(ledger.totals()[2]==1,"cancel dropped pending");UsageLedger.Ticket next=ledger.reserve(0.05,1);ledger.settle(next,null,false);require(ledger.totals()[2]==1,"rollback");ledger.settle(first,new ApiClient.Usage(120,30),true);require(ledger.totals()[2]==0&&ledger.totals()[0]>0,"actual settlement");try{ledger.reserve(2,1);throw new Exception("budget exceeded");}catch(Exception e){require(e.getMessage().contains("预算"),"budget");}
        });
        check("damaged ledger stops paid requests",()->{
            Context local=isolated();try(FileOutputStream out=new FileOutputStream(new File(local.getFilesDir(),"usage.json"))){out.write("broken".getBytes(StandardCharsets.UTF_8));}
            UsageLedger ledger=new UsageLedger(local);try{ledger.reserve(0.1,15);throw new Exception("unexpected reservation");}catch(Exception e){require(e.getMessage().contains("暂停"),"damaged ledger ignored");}
        });
        check("image rotation and maximum dimension",()->{
            ImageTools.ImageData data=ImageTools.fromBitmap(Bitmap.createBitmap(4096,1200,Bitmap.Config.ARGB_8888));require(data.bitmap.getWidth()==2048&&data.bitmap.getHeight()==600,"scale");Bitmap rotated=ImageTools.rotate(data.bitmap);require(rotated.getWidth()==600&&rotated.getHeight()==2048,"rotate");
        });
        check("crop coordinates map and clamp to image pixels",()->{
            Rect rect=RegionView.toBitmap(new RectF(100,150,300,350),new RectF(0,50,400,450),800,800);require(rect.equals(new Rect(200,200,600,600)),"mapping");Rect clipped=RegionView.toBitmap(new RectF(-100,-200,900,1000),new RectF(0,50,400,450),800,800);require(clipped.equals(new Rect(0,0,800,800)),"bounds");
        });
        check("connection test only queries models",()->{
            ApiClient api=new ApiClient(url->{require(url.getPath().equals("/models"),"paid endpoint");return new Fake(url,200,"{\"data\":[{\"id\":\"deepseek-flash\"}]}");});require(api.test("placeholder",new ApiClient.Call()),"model not found");
        });
        check("main UI integration, cache, source invalidation, image result visible",this::uiIntegration);
        check("speech loading can stop and source change cancels without late playback",this::speechCancellation);
        if(edgeLive)liveSpeech();
        if(inputChecks)check("native speech service partial/final results, errors, cancel and text-only translation",this::voiceIntegration);
    }
    private void waitIdle(TranslateApp app)throws Exception{long end=android.os.SystemClock.uptimeMillis()+6000;while(android.os.SystemClock.uptimeMillis()<end){final boolean[] busy={true};runOnMainSync(()->busy[0]=app.busy);if(!busy[0])return;Thread.sleep(25);}throw new Exception("UI translation timeout");}
    private void uiIntegration()throws Exception{
        TranslateApp app=(TranslateApp)getTargetContext().getApplicationContext();SecurePrefs originalPrefs=app.settings;UsageLedger originalLedger=app.ledger;ApiClient originalApi=app.api;
        Context local=isolated();SecurePrefs prefs=new SecurePrefs(local);prefs.saveKey("diagnostic-placeholder-not-a-real-key");final int[] requests={0};MainActivity activity=null;
        try{
            runOnMainSync(()->{app.settings=prefs;app.ledger=new UsageLedger(local);app.api=new ApiClient(url->{requests[0]++;return new Fake(url,200,stream("欢迎使用极简翻译。","stop",true));});app.cache.evictAll();app.input("Welcome to JiJian Translate.");});
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(app::translate);waitIdle(app);require(app.translation.equals("欢迎使用极简翻译。"),"UI result");
            runOnMainSync(app::translate);require(requests[0]==1&&app.status.contains("缓存"),"cache miss");
            final MainActivity screen=activity;runOnMainSync(()->app.info("离线验证 · 界面示例",false));waitForIdleSync();
            File output=new File(getTargetContext().getExternalFilesDir(null),"qa");output.mkdirs();runOnMainSync(()->{
                android.view.View view=screen.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(output,"main-example.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}
            });
            runOnMainSync(()->app.input("new source"));require(app.translation.isEmpty(),"stale translation");
            runOnMainSync(()->{app.translation="old";app.languages("自动识别","English");});require(app.translation.isEmpty(),"stale language result");
            String imageJson=new JSONObject().put("original","line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8").put("translation","图片译文应自动显示。").toString();
            ImageTools.ImageData selected=ImageTools.fromBitmap(Bitmap.createBitmap(1200,800,Bitmap.Config.ARGB_8888));
            runOnMainSync(()->{app.api=new ApiClient(url->new Fake(url,200,stream(imageJson,"stop",true)));app.image=selected;app.text="";app.translate();});waitIdle(app);waitForIdleSync();Thread.sleep(500);
            runOnMainSync(()->{
                android.widget.TextView result=findText(screen.getWindow().getDecorView(),"图片译文应自动显示。");Rect visible=new Rect();
                try{require(result!=null&&result.getGlobalVisibleRect(visible)&&visible.height()>20,"image result is below the viewport");}catch(Exception e){throw new RuntimeException(e);}
            });
        }finally{
            final MainActivity screen=activity;runOnMainSync(()->{if(screen!=null)screen.finish();app.settings=originalPrefs;app.ledger=originalLedger;app.api=originalApi;app.text="";app.translation="";app.image=null;app.cache.evictAll();app.info("离线验证已结束，请填写自己的 Key。",false);});
        }
    }
    private static android.widget.TextView findText(android.view.View view,String text){
        if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().equals(text))return (android.widget.TextView)view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.TextView found=findText(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private void voiceIntegration()throws Exception{
        TranslateApp app=(TranslateApp)getTargetContext().getApplicationContext();SecurePrefs oldPrefs=app.settings;ApiClient oldApi=app.api;UsageLedger oldLedger=app.ledger;VoiceActivity activity=null;
        Context local=isolated();SecurePrefs prefs=new SecurePrefs(local);prefs.saveKey("diagnostic-placeholder-not-a-real-key");prefs.prefs.edit().putString("source","English").commit();final int[] calls={0};final String[] sent={null};
        try{
            runOnMainSync(()->{app.settings=prefs;app.ledger=new UsageLedger(local);app.api=new ApiClient(url->{calls[0]++;Fake fake=new Fake(url,200,stream("系统语音识别后的翻译。","stop",true)){@Override public int getResponseCode(){sent[0]=new String(body.toByteArray(),StandardCharsets.UTF_8);return 200;}};return fake;});app.cache.evictAll();app.text="hello from system speech";app.image=null;app.translation="";});
            activity=(VoiceActivity)startActivitySync(new Intent(getTargetContext(),VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final VoiceActivity screen=activity;waitForIdleSync();
            runOnMainSync(()->findText(screen.getWindow().getDecorView(),"开始说话").performClick());waitVoiceText(screen,"识别完成，可修改文字后翻译。");require(calls[0]==0,"speech triggered paid request before confirmation");
            runOnMainSync(()->{android.widget.EditText text=findEdit(screen.getWindow().getDecorView());try{require(text.getText().toString().equals("hello from system speech"),"final result");app.image=ImageTools.fromBitmap(Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888));}catch(Exception e){throw new RuntimeException(e);}findText(screen.getWindow().getDecorView(),"翻译语音").performClick();});waitIdle(app);require(calls[0]==1&&app.image==null&&app.translation.equals("系统语音识别后的翻译。"),"voice translation pipeline");require(sent[0]!=null&&!sent[0].contains("image_url")&&!sent[0].contains("input_audio"),"sent audio or stale image");
            activity=(VoiceActivity)startActivitySync(new Intent(getTargetContext(),VoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final VoiceActivity errorScreen=activity;waitForIdleSync();
            runOnMainSync(()->{findSpinner(errorScreen.getWindow().getDecorView()).setSelection(5);});waitForIdleSync();runOnMainSync(()->findText(errorScreen.getWindow().getDecorView(),"开始说话").performClick());waitVoiceText(errorScreen,"系统语音服务暂不支持此语言，请换一种语言或在系统中下载语言包。");require(calls[0]==1,"error caused a translation");
            runOnMainSync(()->findSpinner(errorScreen.getWindow().getDecorView()).setSelection(8));waitForIdleSync();runOnMainSync(()->findText(errorScreen.getWindow().getDecorView(),"开始说话").performClick());
            Thread.sleep(120);runOnMainSync(()->errorScreen.finish());Thread.sleep(1000);require(calls[0]==1,"late speech callback triggered a translation");
        }finally{VoiceActivity screen=activity;runOnMainSync(()->{if(screen!=null&&!screen.isFinishing())screen.finish();app.settings=oldPrefs;app.api=oldApi;app.ledger=oldLedger;app.text="";app.translation="";app.image=null;app.resultReady=false;app.cache.evictAll();});}
    }
    private void waitVoiceText(VoiceActivity screen,String text)throws Exception{long until=android.os.SystemClock.uptimeMillis()+8000;while(android.os.SystemClock.uptimeMillis()<until){final boolean[] found={false};runOnMainSync(()->found[0]=findText(screen.getWindow().getDecorView(),text)!=null);if(found[0])return;Thread.sleep(40);}throw new Exception("Missing voice UI: "+text);}
    private static android.widget.EditText findEdit(android.view.View view){if(view instanceof android.widget.EditText)return (android.widget.EditText)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.EditText found=findEdit(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private static android.widget.Spinner findSpinner(android.view.View view){if(view instanceof android.widget.Spinner)return (android.widget.Spinner)view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.widget.Spinner found=findSpinner(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private void speechCancellation()throws Exception{
        TranslateApp app=(TranslateApp)getTargetContext().getApplicationContext();EdgeSpeechClient original=app.speech.client;MainActivity activity=null;java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);final EdgeSpeechClient.Call[] captured={null};
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->{app.speech.client=new EdgeSpeechClient(){@Override byte[] synthesize(String text,String language,int rate,Call call)throws Exception{captured[0]=call;started.countDown();while(!call.cancelled)Thread.sleep(10);throw new java.io.IOException("cancelled");}};app.translation="无密钥也可以朗读。";app.resultReady=true;app.speech.toggle();});
            require(started.await(5,java.util.concurrent.TimeUnit.SECONDS),"speech not requested");runOnMainSync(()->{app.speech.toggle();});require(captured[0].cancelled&&!app.speech.active,"stop did not cancel");
            runOnMainSync(()->{app.speech.toggle();app.input("modified during speech");});Thread.sleep(200);runOnMainSync(()->{});require(!app.speech.active&&app.speech.status.isEmpty()&&!app.resultReady,"old request resumed");
        }finally{MainActivity screen=activity;runOnMainSync(()->{app.speech.stop();app.speech.client=original;app.text="";app.translation="";app.resultReady=false;if(screen!=null)screen.finish();});}
    }
    private void liveSpeech(){
        String[] languages={"简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Русский"};String[] samples={"你好，欢迎使用极简翻译。","你好，歡迎使用極簡翻譯。","Hello. Welcome to simple translation.","こんにちは。翻訳へようこそ。","안녕하세요. 번역을 시작합니다.","Bonjour. Bienvenue.","Hallo. Herzlich willkommen.","Hola. Bienvenidos.","Здравствуйте. Добро пожаловать."};
        for(int i=0;i<languages.length;i++){final String language=languages[i],text=samples[i];check("LIVE Edge "+language+" download, decode and playback",()->{
            byte[] audio=new EdgeSpeechClient().synthesize(text,language,0,new EdgeSpeechClient.Call());android.media.MediaPlayer player=new android.media.MediaPlayer();SpeechPlayback.MemoryAudio data=new SpeechPlayback.MemoryAudio(audio);
            try{player.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());player.setVolume(0,0);player.setDataSource(data);player.prepare();require(player.getDuration()>100&&audio.length>100,"invalid MP3");player.start();long until=android.os.SystemClock.uptimeMillis()+4000;while(player.getCurrentPosition()<100&&android.os.SystemClock.uptimeMillis()<until)Thread.sleep(50);require(player.getCurrentPosition()>50,"player not advancing");File output=new File(getTargetContext().getExternalFilesDir(null),"qa");output.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(output,EdgeSpeechClient.voice(language)+".mp3"))){out.write(audio);}}
            finally{player.release();data.close();}
        });}
        check("speech UI manual playback, autoplay once after success, language invalidation",this::speechUiPlayback);
    }
    private void speechUiPlayback()throws Exception{
        TranslateApp app=(TranslateApp)getTargetContext().getApplicationContext();SecurePrefs originalPrefs=app.settings;ApiClient originalApi=app.api;UsageLedger originalLedger=app.ledger;EdgeSpeechClient originalClient=app.speech.client;MainActivity activity=null;
        byte[] audio=java.nio.file.Files.readAllBytes(new File(getTargetContext().getExternalFilesDir(null),"qa/zh-CN-XiaoxiaoNeural.mp3").toPath());Context local=isolated();SecurePrefs prefs=new SecurePrefs(local);prefs.saveKey("diagnostic-placeholder-not-a-real-key");final int[] synthesized={0};
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(()->{app.settings=prefs;app.ledger=new UsageLedger(local);app.api=new ApiClient(url->new Fake(url,200,stream("语音自动播放测试。","stop",true)));app.speech.client=new EdgeSpeechClient(){@Override byte[] synthesize(String text,String language,int rate,Call call){synthesized[0]++;return audio;}};app.translation="测试朗读";app.resultReady=true;app.changed();app.speech.toggle();});
            waitSpeaking(app);runOnMainSync(()->app.speech.toggle());require(!app.speech.active&&app.speech.status.isEmpty(),"manual stop");
            runOnMainSync(()->{prefs.prefs.edit().putBoolean("autoSpeak",true).commit();app.input("autoplay sample");app.translate();});waitIdle(app);waitSpeaking(app);require(synthesized[0]==2,"stream triggered multiple autoplays");
            runOnMainSync(()->app.languages("自动识别","English"));Thread.sleep(200);require(!app.speech.active&&!app.resultReady&&app.speech.status.isEmpty(),"language invalidation");
        }finally{MainActivity screen=activity;runOnMainSync(()->{app.speech.stop();app.settings=originalPrefs;app.api=originalApi;app.ledger=originalLedger;app.speech.client=originalClient;app.text="";app.translation="";app.resultReady=false;app.cache.evictAll();if(screen!=null)screen.finish();});}
    }
    private void waitSpeaking(TranslateApp app)throws Exception{long until=android.os.SystemClock.uptimeMillis()+5000;while(android.os.SystemClock.uptimeMillis()<until){final String[] state={""};final boolean[] active={false};runOnMainSync(()->{state[0]=app.speech.status;active[0]=app.speech.active;});if(state[0].startsWith("正在朗读"))return;if(!active[0])throw new Exception("playback failed: "+state[0]);Thread.sleep(25);}throw new Exception("playback did not start");}
}
