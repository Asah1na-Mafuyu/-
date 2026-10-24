package com.jijian.translate;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Arrays;

/** Only in the separate test APK. Exercises the real Android recognition IPC. */
public final class TestRecognitionService extends RecognitionService {
    private final Handler main=new Handler(Looper.getMainLooper());private Runnable pending;
    private Bundle words(String text){Bundle b=new Bundle();b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,new ArrayList<>(Arrays.asList(text)));return b;}
    @Override protected void onStartListening(Intent request,Callback result){
        if(pending!=null)main.removeCallbacks(pending);String language=request.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        try{
            result.readyForSpeech(new Bundle());result.beginningOfSpeech();result.partialResults(words("hello partial"));
            pending=()->{try{if("fr-FR".equals(language))result.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED);else if("de-DE".equals(language))result.error(SpeechRecognizer.ERROR_NETWORK);else if(!"es-ES".equals(language)){result.endOfSpeech();result.results(words("hello from system speech"));}}catch(Exception ignored){}};
            main.postDelayed(pending,700);
        }catch(Exception ignored){}
    }
    @Override protected void onStopListening(Callback result){if(pending!=null)main.removeCallbacks(pending);try{result.results(words("hello after stop"));}catch(Exception ignored){}}
    @Override protected void onCancel(Callback result){if(pending!=null)main.removeCallbacks(pending);pending=null;}
}
