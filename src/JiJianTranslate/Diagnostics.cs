using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace JiJianTranslate;

internal static class Diagnostics
{
    private sealed class Handler : HttpMessageHandler
    {
        public int Calls;
        public string? LastBody;
        public Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>>? Reply;
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            Calls++; LastBody = request.Content is null ? null : await request.Content.ReadAsStringAsync(token);
            return Reply is null ? StreamResponse("你好，世界。", false) : await Reply(request, token);
        }
    }
    private static HttpResponseMessage StreamResponse(string content, bool image, string finish = "stop", bool done = true)
    {
        string a = JsonSerializer.Serialize(new { choices = new[] { new { delta = new { content }, finish_reason = (string?)null } } });
        string b = JsonSerializer.Serialize(new { choices = new[] { new { delta = new { }, finish_reason = finish } } });
        string u = JsonSerializer.Serialize(new { choices = Array.Empty<object>(), usage = new { prompt_tokens = 500, completion_tokens = 300, prompt_cache_hit_tokens = 100 } });
        return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("data: " + a + "\n\ndata: " + b + "\n\ndata: " + u + "\n\n" + (done ? "data: [DONE]\n\n" : ""), Encoding.UTF8, "text/event-stream") };
    }
    private static void Require(bool value, string message) { if (!value) throw new InvalidOperationException(message); }
    internal static async Task<int> RunAsync(string directory)
    {
        Directory.CreateDirectory(directory); var report = new List<string>();
        async Task Test(string name, Func<Task> test) { try { await test(); report.Add("PASS " + name); } catch (Exception ex) { report.Add("FAIL " + name + " | " + ex.GetType().Name + ": " + ex.Message); } }
        var root = Path.Combine(directory, "state-" + Guid.NewGuid().ToString("N")); var store = new LocalStore(root);
        await Test("Edge GEC v2 与上游固定时间向量一致", () => {
            Require(EdgeToken.Generate(0)=="52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6","epoch token");
            Require(EdgeToken.Generate(1792627200)=="C0796C264451D87E-0D4CBDF3F0CB24C1EFFFF53DEE91E99290B1F8EF64D1A53406AD915C4849B487","2026 token");
            Require(EdgeToken.Generate(2000000000)=="2D62557F1142BC5D-FD9C30C68FF194A5EE6B45FEFC5E2BA1D1239F88A90569AB55CFFFAA7799F40E","future token");return Task.CompletedTask;
        });
        await Test("9 种语言声音、长文分段及 SSML 注入防护", () => {
            string text=new string('中',899)+"😀<&\u0001"+new string('語',1300);var parts=EdgeSpeechClient.Chunks(text);
            Require(string.Concat(parts)==text.Replace('\u0001',' '),"分段丢字");Require(parts.Count>1&&parts.All(x=>x.Length<=900&&!char.IsHighSurrogate(x[^1])),"破坏 Unicode");
            string xml=EdgeSpeechClient.Ssml("</prosody><audio src='http://bad/'> &",EdgeSpeechClient.Voice("简体中文"),20);Require(!xml.Contains("<audio")&&xml.Contains("&lt;audio")&&xml.Contains("rate='+20%'"),"未转义文本");
            foreach(string language in new[]{"简体中文","繁體中文","English","日本語","한국어","Français","Deutsch","Español","Русский"})Require(EdgeSpeechClient.Voice(language).EndsWith("Neural",StringComparison.Ordinal),language);return Task.CompletedTask;
        });
        await Test("Edge 二进制音频边界及损坏响应拒绝", () => {
            byte[] header=Encoding.UTF8.GetBytes("Path:audio\r\nContent-Type:audio/mpeg\r\n");byte[] frame=new byte[header.Length+5];frame[0]=(byte)(header.Length>>8);frame[1]=(byte)header.Length;header.CopyTo(frame,2);frame[^3]=1;frame[^2]=2;frame[^1]=3;Require(EdgeSpeechClient.AudioPayload(frame).SequenceEqual(new byte[]{1,2,3}),"音频偏移错误");
            bool rejected=false;try{EdgeSpeechClient.AudioPayload(new byte[]{255,255,0});}catch(InvalidDataException){rejected=true;}Require(rejected,"接受坏帧");return Task.CompletedTask;
        });
        await Test("DPAPI 密钥往返、磁盘不存明文、清除密钥", () => {
            const string key = "diagnostic-placeholder-not-a-real-key"; store.SaveKey(key);
            Require(store.LoadKey() == key, "密钥解密失败"); Require(!File.ReadAllText(Path.Combine(root, "key.dat")).Contains(key), "密钥明文落盘"); store.SaveKey(""); Require(store.LoadKey() == "", "密钥未清除"); return Task.CompletedTask;
        });
        await Test("文字 SSE 协议、关闭思考、无历史、用量合并", async () => {
            var handler = new Handler(); using var client = new DeepSeekClient(handler);
            var r = await client.TranslateAsync("placeholder", "Hello world", "自动识别", "简体中文", null, null, CancellationToken.None);
            Require(r.Translation == "你好，世界。" && r.Usage.Input == 500 && r.Usage.Output == 300, "解析失败");
            using var doc = JsonDocument.Parse(handler.LastBody!); var p = doc.RootElement;
            Require(p.GetProperty("thinking").GetProperty("type").GetString() == "disabled", "未关闭思考"); Require(p.GetProperty("messages").GetArrayLength() == 2, "携带了历史");
        });
        var pixels = new byte[80 * 40 * 4]; for (int i = 0; i < pixels.Length; i += 4) { pixels[i] = 240; pixels[i + 1] = 120; pixels[i + 2] = 60; pixels[i + 3] = 255; }
        var bitmap = BitmapSource.Create(80, 40, 96, 96, PixelFormats.Bgra32, null, pixels, 320); bitmap.Freeze(); var image = ImageService.FromBitmap(bitmap, "诊断图片");
        await Test("图片 data URL 与 JSON 原文译文解析", async () => {
            var handler = new Handler { Reply = (_, _) => Task.FromResult(StreamResponse("{\"original\":\"Hello\",\"translation\":\"你好\"}", true)) }; using var client = new DeepSeekClient(handler);
            var r = await client.TranslateAsync("placeholder", "", "自动识别", "简体中文", image, null, CancellationToken.None); Require(r.Original == "Hello" && r.Translation == "你好", "图片解析失败");
            using var doc = JsonDocument.Parse(handler.LastBody!); var p = doc.RootElement;
            Require(p.GetProperty("response_format").GetProperty("type").GetString() == "json_object", "未启用 JSON"); Require(p.GetProperty("messages")[1].GetProperty("content")[1].GetProperty("image_url").GetProperty("url").GetString()!.StartsWith("data:image/png;base64,"), "图像格式错误");
        });
        foreach (var code in new[] { HttpStatusCode.Unauthorized, HttpStatusCode.PaymentRequired, HttpStatusCode.TooManyRequests })
            await Test("HTTP " + (int)code + " 不自动重试、不记生成费", async () => {
                var handler = new Handler { Reply = (_, _) => Task.FromResult(new HttpResponseMessage(code)) }; using var client = new DeepSeekClient(handler);
                try { await client.TranslateAsync("placeholder", "a", "自动识别", "简体中文", null, null, CancellationToken.None); throw new InvalidOperationException("应失败"); }
                catch (TranslationException ex) { Require(!ex.Submitted && handler.Calls == 1, "错误状态处理失败"); }
            });
        await Test("连接截断不能标记为完整成功", async () => {
            using var client = new DeepSeekClient(new Handler { Reply = (_, _) => Task.FromResult(StreamResponse("partial", false, "stop", false)) });
            try { await client.TranslateAsync("p", "a", "自动识别", "简体中文", null, null, CancellationToken.None); throw new InvalidOperationException("错误接受残缺流"); } catch (TranslationException ex) { Require(ex.Submitted && ex.Usage is not null, "未保留用量"); }
        });
        await Test("输出截断保留用量、不当作成功", async () => {
            using var client = new DeepSeekClient(new Handler { Reply = (_, _) => Task.FromResult(StreamResponse("partial", false, "length")) });
            try { await client.TranslateAsync("p", "a", "自动识别", "简体中文", null, null, CancellationToken.None); throw new InvalidOperationException("错误接受截断输出"); } catch (TranslationException ex) { Require(ex.Message.Contains("上限") && ex.Usage is not null, "未报告截断"); }
        });
        await Test("取消已提交请求，保留待核对状态", async () => {
            using var client = new DeepSeekClient(new Handler { Reply = async (_, token) => { await Task.Delay(10000, token); return StreamResponse("", false); } }); using var cancel = new CancellationTokenSource(30);
            try { await client.TranslateAsync("p", "a", "自动识别", "简体中文", null, null, cancel.Token); throw new InvalidOperationException("取消失效"); } catch (TranslationException ex) { Require(ex.Submitted && ex.InnerException is OperationCanceledException, "取消状态丢失"); }
        });
        await Test("预算预留、重启保留、失败回滚、损坏账本阻止调用", () => {
            var s = new LocalStore(Path.Combine(root, "ledger")); var ledger = new UsageLedger(s); var pending = ledger.Reserve(.02m, .03m);
            Require(new UsageLedger(s).PendingCount == 1, "重启丢失预留");
            bool blocked = false; try { ledger.Reserve(.02m, .03m); } catch (InvalidOperationException) { blocked = true; } Require(blocked, "预算未阻止请求");
            ledger.Complete(pending, null, false); Require(ledger.MonthlyCost == 0, "未发送请求未回滚");
            var success = ledger.Reserve(.02m, .03m); ledger.Complete(success, new ApiUsage(500, 300, 0), true); Require(ledger.MonthlyCost == .0034m, "费用计算错误");
            File.WriteAllText(Path.Combine(s.Root, "usage.json"), "broken"); blocked = false; try { _ = new UsageLedger(s); } catch (InvalidOperationException) { blocked = true; } Require(blocked, "错误重置了损坏账本"); return Task.CompletedTask;
        });
        await Test("图片旋转、负坐标跨屏框选、桌面像素读取", () => {
            var rotated = ImageService.Rotate(image); Require(rotated.Width == 40 && rotated.Height == 80, "旋转尺寸错误");
            var rect = CaptureService.Selection(-200, 80, 80, 240, new Rectangle(-1920, 0, 3840, 1080)); Require(rect.X == -200 && rect.Width == 280 && rect.Height == 160, "负坐标换算错误");
            Require(CaptureService.CaptureDesktop(new Rectangle(0, 0, 16, 16)).PixelWidth == 16, "无法读取桌面"); return Task.CompletedTask;
        });
        await Test("主界面翻译与重复内容缓存集成", async () => {
            var state = new LocalStore(Path.Combine(root, "ui")); state.SaveKey("diagnostic-placeholder-not-a-real-key"); var handler = new Handler(); var main = new MainWindow(state, new DeepSeekClient(handler), true);
            main.InputBox.Text = "Hello world"; await main.TranslateAsync(); Require(main.OutputBox.Text == "你好，世界。", "界面没有译文"); await main.TranslateAsync(); Require(handler.Calls == 1 && main.StatusLabel.Text.Contains("缓存"), "重复内容再次扣费");
            Require(main.SpeakButton.IsEnabled,"成功后未启用朗读");main.TargetLanguage.SelectedItem = "日本語"; Require(main.OutputBox.Text.Length == 0 && !main.CopyButton.IsEnabled && !main.SpeakButton.IsEnabled, "切换语言保留了陈旧译文"); await main.TranslateAsync(); Require(handler.Calls == 2, "目标语言改变仍错误复用缓存");
            main.InputBox.Text = "Modified"; Require(main.OutputBox.Text.Length == 0 && !main.CopyButton.IsEnabled, "修改原文保留了陈旧译文"); main.Close();
        });
        await Test("安装文件复制到隔离目录", () => {
            string source = Path.Combine(root, "payload.exe"); File.WriteAllText(source, "diagnostic payload"); string target = Path.Combine(root, "installation"); InstallWindow.InstallPayload(source, target); Require(File.ReadAllText(Path.Combine(target, "极简翻译.exe")) == "diagnostic payload", "安装复制失败"); return Task.CompletedTask;
        });
        await Test("系统热键注册与窗口消息", async () => {
            var window = new MainWindow(new LocalStore(Path.Combine(root, "hotkeys")), null, true); bool called = false;
            using var keys = new HotkeyService(window, () => called = true, () => { }); keys.Configure("Ctrl+Alt+Shift+Q", "Ctrl+Alt+Shift+W");
            Native.PostMessage(new System.Windows.Interop.WindowInteropHelper(window).Handle, 0x312, new IntPtr(1), IntPtr.Zero); await Task.Delay(30); Require(called, "未接收到热键消息"); window.Close();
        });
        await Test("实际桌面截图、选区遮罩与裁剪闭环", async () => {
            Native.GetCursorPos(out var cursor); var capture = new CaptureService();
            try {
                var task = capture.CaptureAsync(); await Task.Delay(120);
                var window = Application.Current.Windows.OfType<CaptureWindow>().First(); var screen = System.Windows.Forms.Screen.PrimaryScreen!.Bounds;
                Native.SetCursorPos(screen.Left + 90, screen.Top + 90); capture.Begin(window); Native.SetCursorPos(screen.Left + 290, screen.Top + 210); capture.Update(); capture.End();
                var result = await task; Require(result is not null && result.Width == 200 && result.Height == 120, "截图选区与物理像素不匹配");
            } finally { capture.Cancel(); Native.SetCursorPos(cursor.X, cursor.Y); }
        });
        await Test("界面渲染：主界面、小窗、设置、深色", () => {
            var main = new MainWindow(new LocalStore(Path.Combine(root, "preview")), null, true); main.PreviewSample(false); Render(main, Path.Combine(directory, "main.png"), 680, 790);
            main.PreviewSample(true); Render(main, Path.Combine(directory, "compact.png"), 410, 630);
            using var client = new DeepSeekClient(new Handler()); var settings = new SettingsWindow(new AppSettings(), store, client, _ => { }); Render(settings, Path.Combine(directory, "settings.png"), 465, 695); settings.Close();
            Theme.Apply(true); Render(main, Path.Combine(directory, "dark.png"), 410, 630); Theme.Apply(false); main.Close(); return Task.CompletedTask;
        });
        report.Add("注意：以上为离线模拟及本机验证，未调用真实付费 API；多显示器实机与真实翻译质量待使用者验证。");
        int failed = report.FindAll(x => x.StartsWith("FAIL ", StringComparison.Ordinal)).Count;
        report.Insert(0, $"Diagnostics: {report.FindAll(x => x.StartsWith("PASS ", StringComparison.Ordinal)).Count} passed, {failed} failed");
        File.WriteAllLines(Path.Combine(directory, "report.txt"), report, new UTF8Encoding(false)); return failed == 0 ? 0 : 1;
    }
    private static void Render(Window window, string path, int width, int height)
    {
        var element = (FrameworkElement)window.Content; element.Width = double.NaN; element.Height = double.NaN; element.Measure(new System.Windows.Size(width, height)); element.Arrange(new Rect(0, 0, width, height)); element.UpdateLayout();
        var backdrop = new DrawingVisual(); using (var dc = backdrop.RenderOpen()) dc.DrawRectangle(window.Background ?? (System.Windows.Media.Brush)Application.Current.FindResource("Page"), null, new Rect(0, 0, width, height));
        var image = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32); image.Render(backdrop); image.Render(element); var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(image)); using var output = File.Create(path); encoder.Save(output);
    }
}
