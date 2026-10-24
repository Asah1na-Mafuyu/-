package com.jijian.translate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Locale;

public final class SettingsActivity extends Activity {
    private TranslateApp app; private EditText key,budget; private TextView connection,usage; private ApiClient.Call testCall;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);app=TranslateApp.get(this);app.speech.stop();Ui.prepare(this);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);LinearLayout root=Ui.column(this);int pad=Ui.dp(this,20);root.setPadding(pad,pad,pad,pad);scroll.addView(root);
        LinearLayout header=Ui.row(this);header.addView(Ui.label(this,"设置",24,true),Ui.weight());Button back=Ui.button(this,"返回",false);back.setOnClickListener(v->finish());header.addView(back);root.addView(header);Ui.gap(root,20);
        root.addView(Ui.label(this,"DeepSeek API Key",14,true));
        key=new EditText(this);key.setSingleLine(true);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setHint("填入自己的 API Key");key.setSaveEnabled(false);key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);root.addView(key);
        try{key.setText(app.settings.loadKey());}catch(Exception e){android.widget.Toast.makeText(this,"密钥无法读取，请重新填写。",android.widget.Toast.LENGTH_LONG).show();}
        LinearLayout keyActions=Ui.row(this);Button test=Ui.button(this,"测试连接",false);keyActions.addView(test,Ui.weight());Button account=Ui.button(this,"获取 Key",false);keyActions.addView(account,Ui.weight());root.addView(keyActions);
        account.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://platform.deepseek.com/api_keys")));}catch(Exception e){note("请在浏览器访问 DeepSeek 开放平台。");}});
        connection=Ui.label(this,"连接测试只查询模型列表，不生成翻译。",12,false);connection.setTextColor(Ui.muted(this));root.addView(connection);
        test.setOnClickListener(v->{
            if(testCall!=null){testCall.cancel();return;}String value=key.getText().toString().trim();if(value.isEmpty()){note("先填写 API Key。");return;}
            ApiClient.Call call=new ApiClient.Call();testCall=call;test.setText("停止测试");connection.setText("正在连接…");
            app.worker.execute(()->{String message;try{message=app.api.test(value,call)?"连接成功，可以使用 Flash 翻译。":"Key 可用，但当前账号未返回 Flash 模型，请核对模型权限。";}catch(Exception e){message=e.getMessage();}final String done=message;app.main.post(()->{testCall=null;if(!isFinishing()&&!isDestroyed()){test.setText("测试连接");connection.setText(done);}});});
        });
        Ui.gap(root,24);root.addView(Ui.label(this,"本机月预算（元）",14,true));budget=new EditText(this);budget.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);budget.setSingleLine(true);budget.setText(String.format(Locale.ROOT,"%.2f",app.settings.budget()));root.addView(budget);
        usage=Ui.label(this,"",13,false);usage.setTextColor(Ui.muted(this));root.addView(usage);refreshUsage();
        TextView cost=Ui.label(this,"只统计本手机；电脑另行统计。取消或超时可能保留待核对费用，最终账单以 DeepSeek 平台为准。",12,false);cost.setTextColor(Ui.muted(this));root.addView(cost);
        Ui.gap(root,20);root.addView(Ui.label(this,"外观",14,true));Spinner theme=new Spinner(this);ArrayAdapter<String> themes=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"跟随系统","浅色","深色"});theme.setAdapter(themes);String mode=app.settings.prefs.getString("theme","system");theme.setSelection(mode.equals("light")?1:mode.equals("dark")?2:0);root.addView(theme);
        Ui.gap(root,12);root.addView(Ui.label(this,"文字大小",14,true));Spinner size=new Spinner(this);size.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"标准","较大","大"}));size.setSelection(app.settings.textSize()==19?1:app.settings.textSize()==21?2:0);root.addView(size);
        Ui.gap(root,20);root.addView(Ui.label(this,"译文朗读 · Edge 在线语音",14,true));CheckBox autoSpeak=new CheckBox(this);autoSpeak.setText("翻译完成后自动朗读");autoSpeak.setChecked(app.settings.autoSpeak());root.addView(autoSpeak);
        Spinner speechRate=new Spinner(this);speechRate.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"慢速","正常语速","快速"}));speechRate.setSelection(app.settings.speechRate()==-20?0:app.settings.speechRate()==20?2:1);root.addView(speechRate);
        TextView speechNote=Ui.label(this,"默认手动朗读。朗读时将译文发送至微软，不消耗 DeepSeek 额度；在线服务不可用时可稍后重试。",12,false);speechNote.setTextColor(Ui.muted(this));root.addView(speechNote);
        Ui.gap(root,20);Button save=Ui.button(this,"保存",true);root.addView(save);save.setOnClickListener(v->{
            if(app.busy){note("请先停止当前翻译，再修改设置。");return;}
            try{double value=Double.parseDouble(budget.getText().toString());if(!Double.isFinite(value)||value<0||value>10000)throw new IllegalArgumentException();
                app.settings.saveKey(key.getText().toString());if(!app.settings.prefs.edit().putString("budget",Double.toString(value)).putString("theme",new String[]{"system","light","dark"}[theme.getSelectedItemPosition()]).putInt("textSize",new int[]{17,19,21}[size.getSelectedItemPosition()]).putBoolean("autoSpeak",autoSpeak.isChecked()).putInt("speechRate",new int[]{-20,0,20}[speechRate.getSelectedItemPosition()]).commit())throw new Exception();
                app.info("设置已保存。",false);finish();
            }catch(NumberFormatException e){note("请输入正确的月预算金额。");}catch(IllegalArgumentException e){note("月预算范围为 0 至 10000 元。");}catch(Exception e){note("设置未能保存，请重试。");}
        });
        Ui.gap(root,20);Button stop=Ui.button(this,"关闭悬浮球与截屏会话",false);root.addView(stop);stop.setOnClickListener(v->{stopService(new Intent(this,OverlayService.class));note("悬浮球已关闭。");});
        Button clear=Ui.button(this,"清除密钥",false);root.addView(clear);clear.setOnClickListener(v->{if(app.busy){note("请先停止翻译。");return;}new AlertDialog.Builder(this).setMessage("清除本手机保存的 API Key？").setPositiveButton("清除",(d,w)->{try{app.settings.saveKey("");key.setText("");app.cache.evictAll();note("密钥已清除。");}catch(Exception e){note("未能清除，请重试。");}}).setNegativeButton("取消",null).show();});
        Button reset=Ui.button(this,"重置本机用量记录",false);root.addView(reset);reset.setOnClickListener(v->{if(app.busy){note("请先停止翻译。");return;}new AlertDialog.Builder(this).setTitle("重置用量记录").setMessage("请先在 DeepSeek 平台核对费用。重置会清空本手机的估算与待核对记录，预算会重新从零统计。").setPositiveButton("重置",(d,w)->{try{app.ledger.reset();refreshUsage();}catch(Exception e){note(e.getMessage());}}).setNegativeButton("取消",null).show();});
        Button licenses=Ui.button(this,"开源许可",false);root.addView(licenses);licenses.setOnClickListener(v->showLicenses());
        Ui.gap(root,22);TextView privacy=Ui.label(this,"极简翻译 · Android v0.3.0\n\n拍照使用系统相机，照片暂存于本机私有缓存，结束或取消后清理。确认翻译时仅上传选区至 DeepSeek。\n\n语音使用系统识别服务，部分设备需要联网；本软件不保存录音，确认翻译后只把识别文字发给 DeepSeek。\n\nKey 由 Android Keystore 加密。文字和选区仅在主动翻译时发送至 DeepSeek，不保存翻译历史或截图文件。\n\n朗读仅将译文发送至微软，音频只保留在内存中。开启自动朗读后，成功翻译的译文也会自动发送以合成语音。\n\n启用截屏后，系统会显示屏幕共享指示；可随时在通知中结束。只在点击截屏时取图，框选确认后上传。\n\n参考价格：Flash 输入 2 元／百万 tokens、输出 8 元／百万 tokens（2026-10-22 高峰价估算）。",12,false);privacy.setTextColor(Ui.muted(this));root.addView(privacy);Ui.content(this,scroll);
    }
    private void note(String value){android.widget.Toast.makeText(this,value,android.widget.Toast.LENGTH_LONG).show();}
    private void showLicenses(){try{StringBuilder value=new StringBuilder();for(String name:new String[]{"Edge-NOTICE.txt","Edge-LICENSE.txt","Edge-GPL-3.0.txt"}){try(java.io.InputStream in=getAssets().open(name)){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);value.append(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");}}TextView text=Ui.label(this,value.toString(),12,false);text.setPadding(Ui.dp(this,18),Ui.dp(this,12),Ui.dp(this,18),Ui.dp(this,12));text.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(text);new AlertDialog.Builder(this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭",null).show();}catch(Exception e){note("许可文件无法读取。");}}
    private void refreshUsage(){try{double[] total=app.ledger.totals();usage.setText(String.format(Locale.CHINA,"本月估算 ¥%.4f · 待核对 ¥%.4f（%.0f 笔）",total[0],total[1],total[2]));}catch(Exception e){usage.setText(e.getMessage());}}
    @Override protected void onDestroy(){if(testCall!=null)testCall.cancel();super.onDestroy();}
}
