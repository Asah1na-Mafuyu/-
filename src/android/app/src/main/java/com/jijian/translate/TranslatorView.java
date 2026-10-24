package com.jijian.translate;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

final class TranslatorView extends LinearLayout {
    interface Actions { void screenshot(); void gallery(); void camera(); void voice(); void settings(); void crop(); void close(); }
    static final String[] SOURCES = {"自动识别","简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Русский"};
    static final String[] TARGETS = {"简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Русский"};
    private final TranslateApp app;
    private final boolean compact;
    private final EditText input;
    private final TextView output, status, sourceLabel, speechStatus;
    private final ImageView preview;
    private final LinearLayout imageActions;
    private final Button translate, clear, copy, speak, crop, rotate, screenshot, gallery, camera, voice;
    private final Spinner source, target;
    private boolean rendering;
    private boolean wasBusy;
    private String lastTranslation="";
    final Runnable listener = this::render;
    TranslatorView(Context context, boolean compact, Actions actions) {
        super(context); app = TranslateApp.get(context); this.compact = compact; setOrientation(VERTICAL); setPadding(Ui.dp(context,16), Ui.dp(context,12), Ui.dp(context,16), Ui.dp(context,16));
        setBackground(Ui.shape(Ui.page(context), 20, context));
        LinearLayout header = Ui.row(context);
        header.addView(Ui.label(context, "极简翻译", compact ? 19 : 24, true), Ui.weight());
        Button settings = Ui.button(context, "设置", false); settings.setOnClickListener(v -> actions.settings()); header.addView(settings);
        if (compact) { Button close = Ui.button(context, "收起", false); close.setOnClickListener(v -> actions.close()); header.addView(close); }
        addView(header); if (!compact) { TextView subtitle = Ui.label(context, "文字与截图，随手翻译。", 13, false); subtitle.setTextColor(Ui.muted(context)); addView(subtitle); }
        Ui.gap(this, 14);
        LinearLayout languages = Ui.row(context); source = spinner(context, SOURCES, app.settings.source()); target = spinner(context, TARGETS, app.settings.target());
        languages.addView(source, Ui.weight()); TextView arrow = Ui.label(context, "→", 18, false); arrow.setPadding(Ui.dp(context,4),0,Ui.dp(context,4),0); languages.addView(arrow); languages.addView(target, Ui.weight()); addView(languages);
        AdapterView.OnItemSelectedListener selection = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (!rendering && !app.busy && !app.loadingImage) app.languages(source.getSelectedItem().toString(), target.getSelectedItem().toString()); }
            public void onNothingSelected(AdapterView<?> parent) { }
        }; source.setOnItemSelectedListener(selection); target.setOnItemSelectedListener(selection);
        Ui.gap(this, 14);
        LinearLayout inputCard = Ui.column(context); inputCard.setBackground(Ui.shape(Ui.card(context), 16, context)); inputCard.setPadding(Ui.dp(context,14),Ui.dp(context,10),Ui.dp(context,14),Ui.dp(context,10));
        LinearLayout inputHead = Ui.row(context); sourceLabel = Ui.label(context, "原文", 13, true); inputHead.addView(sourceLabel, Ui.weight());
        clear = Ui.button(context, "清空", false); clear.setOnClickListener(v -> app.clear()); inputHead.addView(clear); inputCard.addView(inputHead);
        input = new EditText(context); input.setTextSize(app.settings.textSize()); input.setTextColor(Ui.text(context)); input.setHintTextColor(Ui.muted(context)); input.setHint("输入或粘贴要翻译的内容"); input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT); input.setPadding(0,Ui.dp(context,8),0,Ui.dp(context,8)); input.setMinLines(compact ? 3 : 4); input.setMaxLines(compact ? 5 : 8);
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(20000)});
        inputCard.addView(input, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        preview = new ImageView(context); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setContentDescription("待翻译的图片选区"); inputCard.addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context,compact ? 96 : 120)));
        imageActions = Ui.row(context); crop = Ui.button(context, "裁剪", false); crop.setOnClickListener(v -> actions.crop()); imageActions.addView(crop, Ui.weight());
        rotate = Ui.button(context, "旋转", false); rotate.setOnClickListener(v -> { if (app.image != null) app.useBitmap(ImageTools.rotate(app.image.bitmap), false); }); imageActions.addView(rotate, Ui.weight()); inputCard.addView(imageActions); addView(inputCard);
        input.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int start,int count,int after) {} public void onTextChanged(CharSequence s,int start,int before,int count) { if (!rendering) { app.input(s.toString()); render(); } } public void afterTextChanged(Editable e) {} });
        Ui.gap(this, 10); LinearLayout tools = Ui.row(context);
        screenshot = Ui.button(context, "截屏", false); screenshot.setOnClickListener(v -> actions.screenshot()); tools.addView(screenshot, Ui.weight());
        gallery = Ui.button(context, "图片", false); gallery.setOnClickListener(v -> actions.gallery()); tools.addView(gallery, Ui.weight());
        if (!compact) { Button bubble = Ui.button(context, "悬浮球", false); bubble.setOnClickListener(v -> actions.close()); tools.addView(bubble, Ui.weight()); }
        addView(tools);Ui.gap(this,6);LinearLayout quick=Ui.row(context);
        camera=Ui.button(context,"拍照翻译",false);camera.setOnClickListener(v->actions.camera());quick.addView(camera,Ui.weight());
        voice=Ui.button(context,"语音翻译",false);voice.setOnClickListener(v->actions.voice());quick.addView(voice,Ui.weight());addView(quick);Ui.gap(this,10);
        translate = Ui.button(context, "翻译", true); translate.setTextSize(16); translate.setOnClickListener(v -> { if (app.busy) app.cancel(); else app.translate(); }); addView(translate, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context,48)));
        Ui.gap(this,8); status = Ui.label(context,"",12,false); status.setTextColor(Ui.muted(context)); addView(status);
        Ui.gap(this, 14); LinearLayout outputCard = Ui.column(context); outputCard.setBackground(Ui.shape(Ui.card(context),16,context)); outputCard.setPadding(Ui.dp(context,14),Ui.dp(context,10),Ui.dp(context,14),Ui.dp(context,16));
        LinearLayout resultHead = Ui.row(context); resultHead.addView(Ui.label(context,"译文",13,true),Ui.weight());
        speak=Ui.button(context,"朗读",false);speak.setOnClickListener(v->app.speech.toggle());resultHead.addView(speak);
        copy = Ui.button(context,"复制",false); copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE); clipboard.setPrimaryClip(ClipData.newPlainText("译文",app.translation)); android.widget.Toast.makeText(context,"已复制译文",android.widget.Toast.LENGTH_SHORT).show();
        }); resultHead.addView(copy); outputCard.addView(resultHead);
        output = Ui.label(context,"",app.settings.textSize(),false); output.setTextIsSelectable(true); output.setHint("译文会显示在这里"); output.setHintTextColor(Ui.muted(context)); output.setMinHeight(Ui.dp(context,compact ? 72 : 108)); output.setPadding(0,Ui.dp(context,6),0,0); outputCard.addView(output); addView(outputCard);
        speechStatus=Ui.label(context,"",12,false);speechStatus.setTextColor(Ui.muted(context));outputCard.addView(speechStatus);
        render();
    }
    private static Spinner spinner(Context c, String[] values, String selected) {
        Spinner spinner = new Spinner(c); ArrayAdapter<String> adapter = new ArrayAdapter<>(c,android.R.layout.simple_spinner_item,values); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        for (int i=0;i<values.length;i++) if (values[i].equals(selected)) spinner.setSelection(i); return spinner;
    }
    void render() {
        rendering = true;
        try {
            if (!input.getText().toString().equals(app.text)) input.setText(app.text);
            select(source,SOURCES,app.settings.source()); select(target,TARGETS,app.settings.target());
            input.setMinLines(app.image == null ? (compact ? 3 : 4) : 1);
            input.setMaxLines(compact ? (app.image == null ? 5 : 2) : 8);
            input.setHint(app.image == null ? "输入或粘贴要翻译的内容" : "识别原文会显示在这里");
            input.setTextSize(app.settings.textSize()); output.setTextSize(app.settings.textSize()); output.setText(app.translation); status.setText(app.status); status.setTextColor(app.error ? Ui.RED : Ui.muted(getContext()));
            boolean enabled = !app.busy && !app.loadingImage;
            input.setEnabled(enabled); source.setEnabled(enabled); target.setEnabled(enabled); clear.setEnabled(enabled); crop.setEnabled(enabled); rotate.setEnabled(enabled); gallery.setEnabled(enabled); screenshot.setEnabled(enabled);
            camera.setEnabled(enabled);voice.setEnabled(enabled);
            translate.setEnabled(!app.loadingImage); translate.setText(app.busy ? "停止" : "翻译"); copy.setEnabled(!app.translation.isEmpty());
            speak.setText(app.speech.active?"停止":"朗读");speak.setEnabled(app.speech.active||enabled&&app.resultReady&&!app.translation.trim().isEmpty());
            speechStatus.setText(app.speech.status);speechStatus.setTextColor(app.speech.error?Ui.RED:Ui.muted(getContext()));speechStatus.setVisibility(app.speech.status.isEmpty()?GONE:VISIBLE);
            preview.setVisibility(app.image == null ? GONE : VISIBLE); preview.setImageBitmap(app.image == null ? null : app.image.bitmap); imageActions.setVisibility(app.image == null ? GONE : VISIBLE);
            sourceLabel.setText(app.image == null ? "原文" : "识别原文 · 图片");
            if(!app.busy&&!app.error&&!app.translation.isEmpty()&&(wasBusy||!lastTranslation.equals(app.translation))){
                output.post(()->output.requestRectangleOnScreen(new android.graphics.Rect(0,0,output.getWidth(),Math.min(output.getHeight(),Ui.dp(getContext(),100))),false));
            }
            wasBusy=app.busy;lastTranslation=app.translation;
        } finally { rendering = false; }
    }
    private static void select(Spinner view,String[] values,String selected){for(int i=0;i<values.length;i++)if(values[i].equals(selected)&&view.getSelectedItemPosition()!=i){view.setSelection(i);break;}}
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); app.listen(listener); render(); }
    @Override protected void onDetachedFromWindow() { app.unlisten(listener); super.onDetachedFromWindow(); }
}
