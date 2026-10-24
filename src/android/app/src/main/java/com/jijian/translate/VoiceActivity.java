package com.jijian.translate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

/** A visible, single-utterance recording session; audio never passes to DeepSeek. */
public final class VoiceActivity extends Activity {
    static final String[] LANGUAGES={"简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Русский"};
    static final String[] TAGS={"zh-CN","zh-TW","en-US","ja-JP","ko-KR","fr-FR","de-DE","es-ES","ru-RU"};
    private TranslateApp app;private Spinner language;private EditText transcript;private TextView state;private Button record,translate,voiceSettings,permissionSettings;
    private SpeechRecognizer recognizer;private boolean active,processing,external,usingDevice;private int generation;private Runnable timeout;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=TranslateApp.get(this);Ui.prepare(this);app.speech.stop();
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=Ui.column(this);int pad=Ui.dp(this,20);root.setPadding(pad,pad,pad,pad);scroll.addView(root);
        LinearLayout head=Ui.row(this);head.addView(Ui.label(this,"语音翻译",23,true),Ui.weight());Button cancel=Ui.button(this,"取消",false);cancel.setOnClickListener(v->finish());head.addView(cancel);root.addView(head);Ui.gap(root,18);
        root.addView(Ui.label(this,"说话语言",14,true));language=new Spinner(this);language.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,LANGUAGES));
        int selected=initialLanguage(app.settings.source(),app.settings.prefs.getString("voiceLanguage",""),Locale.getDefault());if(saved!=null)selected=Math.max(0,Math.min(TAGS.length-1,saved.getInt("language",selected)));language.setSelection(selected);root.addView(language);Ui.gap(root,18);
        state=Ui.label(this,"点击开始说话，识别后可编辑文字。",14,false);state.setTextColor(Ui.muted(this));root.addView(state);Ui.gap(root,12);
        transcript=new EditText(this);transcript.setTextSize(app.settings.textSize());transcript.setTextColor(Ui.text(this));transcript.setHintTextColor(Ui.muted(this));transcript.setHint("识别出的原文");transcript.setGravity(Gravity.TOP|Gravity.START);transcript.setMinLines(4);transcript.setMaxLines(10);transcript.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);transcript.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(20000)});root.addView(transcript);if(saved!=null)transcript.setText(saved.getString("text",""));Ui.gap(root,14);
        record=Ui.button(this,"开始说话",false);record.setOnClickListener(v->{if(active){if(processing)cancelSession(true);else endSpeech();}else requestSpeech();});root.addView(record);Ui.gap(root,12);
        translate=Ui.button(this,"翻译语音",true);translate.setOnClickListener(v->submit());root.addView(translate);Ui.gap(root,18);
        TextView privacy=Ui.label(this,"语音由手机系统识别，部分设备会联网处理。仅确认翻译后，将识别文字发送给 DeepSeek；本软件不保存录音。",12,false);privacy.setTextColor(Ui.muted(this));root.addView(privacy);Ui.gap(root,12);
        voiceSettings=Ui.button(this,"系统语音设置",false);voiceSettings.setVisibility(android.view.View.GONE);voiceSettings.setOnClickListener(v->{cancelSession(false);try{startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));}catch(Exception e){note("请在手机系统设置中启用语音识别服务。",true);}});root.addView(voiceSettings);
        permissionSettings=Ui.button(this,"应用权限设置",false);permissionSettings.setVisibility(android.view.View.GONE);permissionSettings.setOnClickListener(v->{cancelSession(false);try{startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception e){note("请在系统设置中允许本软件使用麦克风。",true);}});root.addView(permissionSettings);
        transcript.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render();}public void afterTextChanged(Editable e){}});Ui.content(this,scroll);render();
    }
    static int initialLanguage(String source,String saved,Locale locale){
        for(int i=0;i<LANGUAGES.length;i++)if(LANGUAGES[i].equals(source))return i;
        for(int i=0;i<TAGS.length;i++)if(TAGS[i].equals(saved))return i;
        if("zh".equals(locale.getLanguage()))return "TW".equals(locale.getCountry())||"HK".equals(locale.getCountry())?1:0;
        for(int i=0;i<TAGS.length;i++)if(TAGS[i].startsWith(locale.getLanguage()+"-"))return i;
        return 2;
    }
    static Intent recognitionIntent(String tag){return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,tag).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);}
    private void requestSpeech(){
        if(app.busy||app.loadingImage){note("请先停止当前翻译，再使用语音。",true);return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},41);return;}
        begin(false);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);if(code==41){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)app.main.post(()->{if(!isFinishing()&&!isDestroyed())begin(false);});else note("未获得麦克风权限。可点“应用权限设置”开启，或手动输入文字。",true);}}
    private void begin(boolean forceSystem){
        cancelSession(false);if(isFinishing()||isDestroyed())return;
        String tag=TAGS[language.getSelectedItemPosition()];app.settings.prefs.edit().putString("voiceLanguage",tag).apply();final int ticket=++generation;
        try{
            usingDevice=!forceSystem&&Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
            if(usingDevice)recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            else if(SpeechRecognizer.isRecognitionAvailable(this))recognizer=SpeechRecognizer.createSpeechRecognizer(this);
            else{
                Intent intent=recognitionIntent(tag).putExtra(RecognizerIntent.EXTRA_PROMPT,"说出要翻译的内容");if(intent.resolveActivity(getPackageManager())==null){note("手机尚未提供语音识别服务，请在系统语音设置中启用。",true);return;}
                external=true;startActivityForResult(intent,42);return;
            }
            active=true;processing=false;note("正在准备麦克风…",false);render();
            recognizer.setRecognitionListener(new RecognitionListener(){
                private boolean valid(){return active&&ticket==generation&&!isFinishing()&&!isDestroyed();}
                public void onReadyForSpeech(Bundle params){if(valid())note("正在聆听…说完可点“结束说话”。",false);}
                public void onBeginningOfSpeech(){if(valid())note("正在聆听…",false);}
                public void onRmsChanged(float value){}public void onBufferReceived(byte[] buffer){}
                public void onEndOfSpeech(){if(valid()){processing=true;note("正在识别…",false);render();}}
                public void onError(int code){if(!valid())return;boolean retry=usingDevice&&(code==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED||code==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE||code==SpeechRecognizer.ERROR_SERVER||code==SpeechRecognizer.ERROR_CLIENT);cancelSession(false);if(retry){note("本机识别暂不可用，正在使用系统语音服务…",false);begin(true);}else note(errorMessage(code),true);}
                public void onResults(Bundle results){if(!valid())return;String value=firstResult(results,SpeechRecognizer.RESULTS_RECOGNITION);cancelSession(false);if(value.isEmpty())note("没有听清，请再说一次；也可以修改已有文字。",true);else{transcript.setText(value);note("识别完成，可修改文字后翻译。",false);}render();}
                public void onPartialResults(Bundle results){if(valid()){String value=firstResult(results,SpeechRecognizer.RESULTS_RECOGNITION);if(!value.isEmpty())transcript.setText(value);}}
                public void onEvent(int type,Bundle params){}
            });
            timeout=()->{if(ticket==generation&&active){cancelSession(false);note("识别时间较长，已停止。请分段说话或修改已有文字。",true);}};app.main.postDelayed(timeout,45000);
            recognizer.startListening(recognitionIntent(tag));
        }catch(SecurityException e){cancelSession(false);note("无法使用麦克风，请检查应用权限和系统的麦克风开关。",true);}
        catch(Exception e){boolean retry=usingDevice&&!forceSystem;cancelSession(false);if(retry)begin(true);else note("语音服务无法启动，请检查系统语音设置。",true);}
    }
    static String firstResult(Bundle result,String key){if(result==null)return "";ArrayList<String> values=result.getStringArrayList(key);if(values==null)return "";for(String value:values)if(value!=null&&!value.trim().isEmpty()){String text=value.trim();if(text.length()>20000){int end=20000;if(Character.isHighSurrogate(text.charAt(end-1)))end--;text=text.substring(0,end);}return text;}return "";}
    static String errorMessage(int code){switch(code){
        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:return "无法使用麦克风，请检查应用权限和系统的麦克风开关。";
        case SpeechRecognizer.ERROR_NETWORK:case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:return "语音识别网络不可用，请检查网络后重试。";
        case SpeechRecognizer.ERROR_NO_MATCH:case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:return "没有听清，请靠近麦克风再说一次。";
        case SpeechRecognizer.ERROR_AUDIO:return "麦克风暂时不可用，请关闭其他录音应用后重试。";
        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:return "语音服务忙，请稍后再试。";
        case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:return "系统语音服务暂不支持此语言，请换一种语言或在系统中下载语言包。";
        case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:return "语音识别请求较频繁，请稍后再试。";
        default:return "语音识别未完成，请重试或检查系统语音设置。";}}
    private void endSpeech(){if(recognizer==null)return;processing=true;note("正在识别…",false);render();try{recognizer.stopListening();}catch(Exception e){cancelSession(false);note("识别已停止，可修改文字后翻译。",false);}}
    private void cancelSession(boolean show){
        ++generation;active=false;processing=false;if(timeout!=null)app.main.removeCallbacks(timeout);timeout=null;
        SpeechRecognizer old=recognizer;recognizer=null;if(old!=null){try{old.cancel();}catch(Exception ignored){}try{old.destroy();}catch(Exception ignored){}}
        if(show)note("识别已停止，可修改文字或重新开始。",false);render();
    }
    private void submit(){String value=transcript.getText().toString().trim();if(active||value.isEmpty())return;if(app.busy||app.loadingImage){note("请先停止当前操作。",true);return;}cancelSession(false);app.languages(LANGUAGES[language.getSelectedItemPosition()],app.settings.target());app.input(value);app.translate();finish();}
    private void render(){if(record==null||translate==null)return;record.setText(active?(processing?"取消识别":"结束说话"):"开始说话");language.setEnabled(!active);transcript.setEnabled(!active);translate.setEnabled(!active&&!transcript.getText().toString().trim().isEmpty());}
    private void note(String text,boolean failed){if(state!=null){state.setText(text);state.setTextColor(failed?Ui.RED:Ui.muted(this));}if(voiceSettings!=null)voiceSettings.setVisibility(failed&&text.contains("系统语音")?android.view.View.VISIBLE:android.view.View.GONE);if(permissionSettings!=null)permissionSettings.setVisibility(failed&&(text.contains("权限")||text.contains("麦克风开关"))?android.view.View.VISIBLE:android.view.View.GONE);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==42){external=false;String value=result==RESULT_OK&&data!=null?firstResult(data.getExtras(),RecognizerIntent.EXTRA_RESULTS):"";if(!value.isEmpty()){transcript.setText(value);note("识别完成，可修改文字后翻译。",false);}else note("语音识别已取消或未取得文字。",false);render();}}
    @Override protected void onSaveInstanceState(Bundle saved){super.onSaveInstanceState(saved);saved.putString("text",transcript.getText().toString());saved.putInt("language",language.getSelectedItemPosition());}
    @Override protected void onStop(){boolean wasActive=active;cancelSession(false);if(wasActive)note("识别已停止。点击开始说话可重新识别。",false);super.onStop();}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(!focused&&active){cancelSession(false);note("识别已停止。点击开始说话可重新识别。",false);}}
    @Override protected void onDestroy(){cancelSession(false);super.onDestroy();}
}
