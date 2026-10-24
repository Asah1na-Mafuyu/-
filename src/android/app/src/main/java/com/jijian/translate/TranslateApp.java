package com.jijian.translate;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TranslateApp extends Application {
    SecurePrefs settings; UsageLedger ledger; ApiClient api; SpeechPlayback speech;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final ArrayList<Runnable> listeners = new ArrayList<>();
    final LruCache<String, ApiClient.Result> cache = new LruCache<>(24);
    String text = "", translation = "", status = "输入文字，或选择截图和图片。";
    ImageTools.ImageData image; android.graphics.Bitmap cropSource;
    boolean busy, error, loadingImage, resultReady; private ApiClient.Call current; private Runnable deadline;
    public static TranslateApp get(android.content.Context context) { return (TranslateApp) context.getApplicationContext(); }
    @Override public void onCreate() { super.onCreate(); settings = new SecurePrefs(this); ledger = new UsageLedger(this); api = new ApiClient(); speech = new SpeechPlayback(this); }
    void listen(Runnable listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    void input(String value) {
        if (busy || loadingImage || value.equals(text)&&image==null) return;
        speech.stopQuiet();resultReady=false;text = value; image = null; translation = ""; error = false; status = "准备翻译。"; changed();
    }
    void languages(String source, String target) {
        if (source.equals(settings.source()) && target.equals(settings.target())) return;
        speech.stopQuiet();resultReady=false;settings.prefs.edit().putString("source", source).putString("target", target).apply(); translation = ""; status = "语言已修改，重新翻译即可。"; error = false; changed();
    }
    void clear() { if (busy || loadingImage) return;speech.stopQuiet();resultReady=false; text = ""; translation = ""; image = null; status = "已清空。"; error = false; changed(); }
    void info(String message, boolean failed) { status = message; error = failed; changed(); }
    void loadImage(android.net.Uri uri) {
        if (busy || loadingImage) return;speech.stopQuiet();resultReady=false; loadingImage = true; info("正在读取图片…", false);
        worker.execute(() -> {
            try { ImageTools.ImageData loaded = ImageTools.load(this, uri); main.post(() -> { loadingImage = false; image = loaded; text = ""; translation = ""; info("图片已就绪，可先裁剪或旋转。", false); }); }
            catch (Exception e) { main.post(() -> { loadingImage = false; info("图片无法读取，请选择较小的 PNG、JPG 或 WebP 图片。", true); }); }
        });
    }
    void useBitmap(android.graphics.Bitmap bitmap, boolean translate) {
        if (busy || loadingImage) return;speech.stopQuiet();resultReady=false; loadingImage = true; info("正在准备选区…", false);
        worker.execute(() -> {
            try { ImageTools.ImageData loaded = ImageTools.fromBitmap(bitmap); main.post(() -> { loadingImage = false; image = loaded; text = ""; translation = ""; info("选区已就绪。", false); if (translate) translate(); }); }
            catch (Exception e) { main.post(() -> { loadingImage = false; info("图片处理失败，请缩小选区后重试。", true); }); }
        });
    }
    void translate() {
        if (busy || loadingImage) return;
        speech.stopQuiet();
        if (image == null && text.trim().isEmpty()) { info("先输入文字，或选择一张图片。", true); return; }
        if (text.length() > 20000) { info("文字太长，请分成每段两万字以内。", true); return; }
        final String key;
        try { key = settings.loadKey(); } catch (Exception e) { info("密钥无法读取，请到设置重新填写。", true); return; }
        if (key.isEmpty()) { info("请先在设置中填写 DeepSeek API Key。", true); return; }
        final String input = text, source = settings.source(), target = settings.target(); final ImageTools.ImageData selected = image;
        final String cacheKey;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(("v1|" + ApiClient.MODEL + "|" + source + "|" + target + "|" + (selected == null ? input : "image") + "|").getBytes(StandardCharsets.UTF_8));
            if (selected != null) digest.update(selected.bytes); cacheKey = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
        } catch (Exception e) { info("无法准备翻译，请重新打开软件。", true); return; }
        ApiClient.Result cached = cache.get(cacheKey);
        if (cached != null) { text = cached.original; translation = cached.translation;resultReady=true; info("已使用本次运行中的缓存。", false);if(settings.autoSpeak())speech.speak(translation,target,settings.speechRate());return; }
        final UsageLedger.Ticket ticket;
        try {
            double estimate = ((selected == null ? input.getBytes(StandardCharsets.UTF_8).length : 1024) + 2048) * UsageLedger.INPUT / 1_000_000.0 + 4096 * UsageLedger.OUTPUT / 1_000_000.0;
            ticket = ledger.reserve(estimate, settings.budget());
        } catch (Exception e) { info(e.getMessage(), true); return; }
        resultReady=false;busy = true; error = false; translation = ""; status = selected == null ? "正在翻译…" : "正在识别并翻译选区…";
        final ApiClient.Call call = new ApiClient.Call(); current = call; changed();
        deadline = () -> { if (current == call && busy) { call.cancel(); info("请求超时，正在停止…", true); } }; main.postDelayed(deadline, 120000);
        worker.execute(() -> {
            ApiClient.Result result = null; ApiClient.Failure failure = null; final long[] last = {0};
            try { result = api.translate(key, input, source, target, selected, call, part -> {
                long now = android.os.SystemClock.uptimeMillis(); if (now - last[0] < 60) return; last[0] = now;
                main.post(() -> { if (current == call && busy && !call.cancelled.get()) { translation = part; changed(); } });
            }); } catch (ApiClient.Failure e) { failure = e; }
            String ledgerError = null;
            try { ledger.settle(ticket, result != null ? result.usage : failure == null ? null : failure.usage, failure != null && failure.mayCharge); }
            catch (Exception e) { ledgerError = e.getMessage(); }
            final ApiClient.Result completed = result; final ApiClient.Failure failed = failure; final String saveError = ledgerError;
            main.post(() -> {
                if (deadline != null) main.removeCallbacks(deadline); current = null; busy = false;
                if (completed != null) {resultReady=true; text = completed.original; translation = completed.translation; cache.put(cacheKey, completed); error = saveError != null; status = saveError == null ? "翻译完成。" : saveError; }
                else { error = true; status = failed == null ? "翻译未完成，请重试。" : failed.getMessage(); if (saveError != null) status += " " + saveError; }
                changed();
                if(completed!=null&&settings.autoSpeak())speech.speak(translation,target,settings.speechRate());
            });
        });
    }
    void cancel() { if (current != null) { current.cancel(); info("正在停止…", false); } }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.evictAll(); }
}
