using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using Forms = System.Windows.Forms;

namespace JiJianTranslate;

public partial class MainWindow : Window
{
    private readonly LocalStore _store;
    private AppSettings _settings;
    private readonly DeepSeekClient _client;
    private readonly SpeechPlayback _speech;
    private UsageLedger? _ledger;
    private HotkeyService? _hotkeys;
    private Forms.NotifyIcon? _tray;
    private readonly CaptureService _capture = new();
    private readonly Dictionary<string, TranslationResult> _cache = new();
    private readonly Queue<string> _cacheOrder = new();
    private CancellationTokenSource? _request;
    private SelectedImage? _image;
    private bool _initializing = true, _busy, _capturing, _exiting, _programmatic;
    private bool _finished;
    private readonly bool _diagnostics;
    private int _generation;
    private double _largeWidth = 680, _largeHeight = 790;
    private static readonly string[] Languages = { "简体中文", "English", "日本語", "한국어", "繁體中文", "Français", "Deutsch", "Español", "Русский" };
    public MainWindow(LocalStore? store = null, DeepSeekClient? client = null, bool diagnostics = false)
    {
        _store = store ?? new LocalStore(); _settings = _store.LoadSettings(); _client = client ?? new DeepSeekClient(); _diagnostics = diagnostics;
        _speech = new SpeechPlayback(_store.Root, diagnostics ? 0 : 1); InitializeComponent();
        _speech.Changed += (message, error) => {
            SpeakButton.Content = _speech.Active ? "停止" : "朗读"; SpeakButton.IsEnabled = _speech.Active || (!_busy && _finished);
            SpeechStatus.Text = message; SpeechStatus.Visibility = message.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
            SpeechStatus.Foreground = error ? Brushes.IndianRed : (System.Windows.Media.Brush)FindResource("Muted");
        };
        SourceLanguage.Items.Add("自动识别"); foreach (string s in Languages) { SourceLanguage.Items.Add(s); TargetLanguage.Items.Add(s); }
        SourceLanguage.SelectedItem = SourceLanguage.Items.Contains(_settings.SourceLanguage) ? _settings.SourceLanguage : "自动识别";
        TargetLanguage.SelectedItem = TargetLanguage.Items.Contains(_settings.TargetLanguage) ? _settings.TargetLanguage : "简体中文";
        try { _ledger = new UsageLedger(_store); } catch (Exception e) { SetStatus(e.Message, true); }
        ApplyAppearance(); _initializing = false;
        if (_ledger is not null) SetStatus(_store.LoadKey().Length == 0 ? "先在设置中填入 API Key" : "准备好了");
        UpdateUsage();
        SourceInitialized += (_, _) => {
            if (_diagnostics) return;
            _hotkeys = new HotkeyService(this, async () => await CaptureAsync(), ToggleVisible);
            try { _hotkeys.Configure(_settings.CaptureHotkey, _settings.WindowHotkey); } catch (Exception e) { SetStatus(e.Message, true); }
        };
        Loaded += (_, _) => { if (!_diagnostics) CreateTray(); };
        Closing += OnClosing;
    }
    private void CreateTray()
    {
        if (_tray is not null) return;
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add("打开翻译", null, (_, _) => ShowMain());
        menu.Items.Add("截图翻译", null, async (_, _) => await CaptureAsync());
        menu.Items.Add("设置", null, (_, _) => OpenSettings()); menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => Exit());
        _tray = new Forms.NotifyIcon { Text = "极简翻译 · 右键退出", Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!) ?? System.Drawing.SystemIcons.Application, Visible = true, ContextMenuStrip = menu };
        _tray.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) ShowMain(); };
    }
    internal void ShowMain() { Show(); WindowState = WindowState.Normal; Activate(); }
    private void ToggleVisible() { if (_capturing) return; if (IsVisible && IsActive) { _speech.Stop(); Hide(); } else ShowMain(); }
    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (!_exiting && !_diagnostics) { e.Cancel = true; _speech.Stop(); Hide(); return; }
        _request?.Cancel(); _capture.Cancel(); _hotkeys?.Dispose(); if (_tray is not null) { _tray.Visible = false; _tray.Dispose(); }
        _speech.Dispose(); _client.Dispose();
    }
    private void Exit() { _exiting = true; Close(); if (!_diagnostics) Application.Current.Shutdown(); }
    private void ApplyAppearance()
    {
        Theme.Apply(_settings.DarkMode); InputBox.FontSize = OutputBox.FontSize = _settings.EditorFontSize;
        Topmost = _settings.AlwaysOnTop;
        PinButton.Foreground = (System.Windows.Media.Brush)FindResource(Topmost ? "Accent" : "Muted"); PinButton.ToolTip = Topmost ? "取消置顶" : "置顶";
        Width = _settings.Compact ? 410 : _largeWidth; Height = _settings.Compact ? 630 : _largeHeight;
        CompactButton.Content = _settings.Compact ? "展开" : "小窗";
        ShortcutHint.Text = _settings.CaptureHotkey;
    }
    private void Persist() { try { _store.SaveSettings(_settings); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { SetStatus("设置保存失败，请检查数据目录权限。", true); } }
    private void SetStatus(string message, bool error = false) { StatusLabel.Text = message; StatusLabel.Foreground = error ? new SolidColorBrush(Color.FromRgb(204, 70, 76)) : (System.Windows.Media.Brush)FindResource("Muted"); }
    private void UpdateUsage()
    {
        UsageLabel.Text = _ledger is null ? "用量记录异常，暂不发送请求" : $"本机本月估算 ¥{_ledger.MonthlyCost:F2} / ¥{_settings.MonthlyBudget:F0}" + (_ledger.PendingCount > 0 ? $" · {_ledger.PendingCount} 笔待核对" : "");
    }
    private void SetBusy(bool value)
    {
        _busy = value; TranslateButton.Content = value ? "停止" : "翻译   ↵";
        CaptureButton.IsEnabled = ImportButton.IsEnabled = ClearButton.IsEnabled = ImagePanel.IsEnabled = !value;
        SourceLanguage.IsEnabled = TargetLanguage.IsEnabled = !value; InputBox.IsReadOnly = value;
        CopyButton.IsEnabled = !value && _finished;
        SpeakButton.IsEnabled = _speech.Active || (!value && _finished);
    }
    internal async Task TranslateAsync()
    {
        if (_busy) { _request?.Cancel(); return; }
        if (_capturing) return;
        _speech.Stop();
        string text = InputBox.Text.Trim();
        if (_image is null && text.Length == 0) { SetStatus("先输入文字，或选择一张图片。"); InputBox.Focus(); return; }
        if (text.Length > 16000) { SetStatus("请将长文分段，每次不超过 16,000 字符。", true); return; }
        string key = _store.LoadKey();
        if (key.Length == 0) { SetStatus("请先在设置中填写 DeepSeek API Key。", true); OpenSettings(); return; }
        if (_ledger is null) { SetStatus("用量记录异常，暂不发送请求。", true); return; }
        string from = SourceLanguage.SelectedItem?.ToString() ?? "自动识别", to = TargetLanguage.SelectedItem?.ToString() ?? "简体中文";
        string cacheKey = DeepSeekClient.CacheKey(from, to, _image is null ? text : "", _image);
        if (_cache.TryGetValue(cacheKey, out var cached)) { SetResult(cached); SetStatus("已从本次会话缓存读取 · 未发起请求"); if (_settings.AutoSpeak) _ = ReadResultAsync(); return; }
        UsageEntry reservation;
        try { reservation = _ledger.Reserve(((text.Length * 2 + 512 + (_image is null ? 0 : 1024)) * 2m + 4096 * 8m) / 1_000_000m, _settings.MonthlyBudget); }
        catch (Exception e) when (e is InvalidOperationException or IOException or UnauthorizedAccessException) { SetStatus(e is InvalidOperationException ? e.Message : "无法保存用量记录，本次未发送。", true); return; }
        SetBusy(true); _finished = false; OutputBox.Clear(); SetStatus(_image is null ? "正在翻译…" : "正在识别并翻译图片…"); UpdateUsage();
        _request = new CancellationTokenSource(TimeSpan.FromSeconds(120)); int ticket = ++_generation;
        var progress = new Progress<string>(part => { if (_busy && ticket == _generation) OutputBox.Text = part; });
        try
        {
            var result = await _client.TranslateAsync(key, text, from, to, _image, progress, _request.Token);
            _ledger.Complete(reservation, result.Usage, true); SetResult(result);
            if (_cache.Count >= 24 && _cacheOrder.TryDequeue(out var oldest)) _cache.Remove(oldest);
            _cache[cacheKey] = result; _cacheOrder.Enqueue(cacheKey);
            SetStatus($"翻译完成 · 本次估算 ¥{result.Usage.EstimatedCost:F4}");
        }
        catch (TranslationException e)
        {
            try { _ledger.Complete(reservation, e.Usage, e.Submitted); }
            catch (Exception x) when (x is IOException or UnauthorizedAccessException) { }
            OutputBox.Clear(); _finished = false; SetStatus(e.Message, true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        { _finished = false; SetStatus("用量保存失败，本次保留费用预留记录，请检查数据目录。", true); }
        finally { ++_generation; _request.Dispose(); _request = null; SetBusy(false); UpdateUsage(); }
        if (_finished && _settings.AutoSpeak) _ = ReadResultAsync();
    }
    private void SetResult(TranslationResult result)
    {
        _programmatic = true; InputBox.Text = result.Original; _programmatic = false;
        OutputBox.Text = result.Translation; _finished = true; CopyButton.IsEnabled = true; SpeakButton.IsEnabled = !_busy;
    }
    private void SetImage(SelectedImage? image)
    {
        _speech.Stop();
        _image = image; ImagePanel.Visibility = image is null ? Visibility.Collapsed : Visibility.Visible;
        ImagePreview.Source = image is null ? null : ImageService.Decode(image.Bytes);
        ImageInfo.Text = image is null ? "" : $"{image.Label} · {image.Width} × {image.Height}";
        if (image is not null) { _programmatic = true; InputBox.Clear(); _programmatic = false; OutputBox.Clear(); _finished = false; CopyButton.IsEnabled = SpeakButton.IsEnabled = false; }
        InputHint.Text = image is null ? "输入或粘贴文字，也可以拖入图片。" : "翻译后可在这里核对识别原文。";
    }
    internal async Task CaptureAsync()
    {
        if (_busy || _capturing || OwnedWindows.Count > 0) return;
        _speech.Stop();
        _capturing = true; bool wasVisible = IsVisible;
        try
        {
            Hide(); await Task.Delay(180);
            var selected = await _capture.CaptureAsync();
            if (_exiting) return;
            if (selected is not null) { SetImage(selected); ShowMain(); SetStatus("截图已就绪"); }
            else { if (wasVisible) ShowMain(); SetStatus("已取消截图"); }
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or ExternalException)
        { ShowMain(); SetStatus("截图未完成，请重试或直接粘贴图片。", true); }
        finally { _capturing = false; }
        if (_image is not null && IsVisible && StatusLabel.Text == "截图已就绪")
        { if (_store.LoadKey().Length > 0) await TranslateAsync(); else SetStatus("截图已就绪，请在设置里填入 API Key 后翻译。"); }
    }
    private void OpenSettings()
    {
        if (_busy || _capturing) { SetStatus("请先停止当前翻译，再打开设置。"); return; }
        _speech.Stop();
        ShowMain();
        var dialog = new SettingsWindow(_settings, _store, _client, settings => {
            _hotkeys?.Configure(settings.CaptureHotkey, settings.WindowHotkey);
            _settings = settings; ApplyAppearance(); UpdateUsage();
        }) { Owner = this };
        dialog.ShowDialog();
    }
    private async void TranslateClick(object sender, RoutedEventArgs e) => await TranslateAsync();
    private async void CaptureClick(object sender, RoutedEventArgs e) => await CaptureAsync();
    private void SettingsClick(object sender, RoutedEventArgs e) => OpenSettings();
    private void CompactClick(object sender, RoutedEventArgs e)
    {
        if (!_settings.Compact) { _largeWidth = Width; _largeHeight = Height; }
        _settings.Compact = !_settings.Compact; if (_settings.Compact) _settings.AlwaysOnTop = true;
        ApplyAppearance(); Persist();
    }
    private void PinClick(object sender, RoutedEventArgs e) { _settings.AlwaysOnTop = !Topmost; Topmost = _settings.AlwaysOnTop; PinButton.Foreground = (System.Windows.Media.Brush)FindResource(Topmost ? "Accent" : "Muted"); PinButton.ToolTip = Topmost ? "取消置顶" : "置顶"; Persist(); }
    private void MinimizeClick(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void HideClick(object sender, RoutedEventArgs e) { _speech.Stop(); Hide(); }
    private void LanguageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing) return;
        _settings.SourceLanguage = SourceLanguage.SelectedItem?.ToString() ?? "自动识别"; _settings.TargetLanguage = TargetLanguage.SelectedItem?.ToString() ?? "简体中文"; Persist();
        InvalidateResult("语言已切换，点击翻译。");
    }
    private void SwapClick(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        string from = SourceLanguage.SelectedItem?.ToString() ?? "自动识别", to = TargetLanguage.SelectedItem?.ToString() ?? "简体中文";
        string previous = _finished ? OutputBox.Text : "";
        SourceLanguage.SelectedItem = to; TargetLanguage.SelectedItem = from == "自动识别" ? (to == "English" ? "简体中文" : "English") : from;
        if (previous.Length > 0) { SetImage(null); InputBox.Text = previous; OutputBox.Clear(); }
    }
    private void ClearClick(object sender, RoutedEventArgs e) { SetImage(null); InputBox.Clear(); OutputBox.Clear(); _finished = false; CopyButton.IsEnabled = SpeakButton.IsEnabled = false; SetStatus("已清空"); }
    private async void SpeakClick(object sender, RoutedEventArgs e) { if (_speech.Active) _speech.Stop(); else await ReadResultAsync(); }
    private Task ReadResultAsync() => !_finished || _busy ? Task.CompletedTask : _speech.SpeakAsync(OutputBox.Text, TargetLanguage.SelectedItem?.ToString() ?? "简体中文", _settings.SpeechRate);
    private void CopyClick(object sender, RoutedEventArgs e)
    { try { if (_finished) { Clipboard.SetText(OutputBox.Text); SetStatus("译文已复制"); } } catch (ExternalException) { SetStatus("剪贴板正被占用，请稍后复制。", true); } }
    private void InputChanged(object sender, TextChangedEventArgs e)
    {
        if (InputHint is not null) InputHint.Visibility = InputBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (_initializing || _programmatic || _busy) return;
        if (_image is not null) SetImage(null);
        InvalidateResult("按 Ctrl+Enter 翻译");
    }
    private void InvalidateResult(string message) { _speech.Stop(); _finished = false; if (CopyButton is not null) CopyButton.IsEnabled = false; if (SpeakButton is not null) SpeakButton.IsEnabled = false; if (OutputBox is not null) OutputBox.Clear(); if (StatusLabel is not null) SetStatus(message); }
    private void OutputChanged(object sender, TextChangedEventArgs e) { if (OutputHint is not null) OutputHint.Visibility = OutputBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed; }
    private async void ImportClick(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Filter = "图片|*.png;*.jpg;*.jpeg;*.webp;*.bmp;*.gif;*.tif;*.tiff", Title = "选择要翻译的图片" };
        if (dialog.ShowDialog(this) == true) await LoadImageAsync(() => ImageService.FromFile(dialog.FileName));
    }
    private Task LoadImageAsync(Func<SelectedImage> load)
    {
        if (_busy || _capturing) return Task.CompletedTask;
        try { SetImage(load()); ShowMain(); SetStatus(_store.LoadKey().Length > 0 ? "图片已就绪，可裁剪后点击翻译。" : "图片已就绪，请先在设置中填入 API Key。"); }
        catch (Exception e) when (e is IOException or NotSupportedException or FileFormatException or InvalidOperationException or ArgumentException)
        { SetStatus("图片无法读取，或尺寸过大。请换一张 PNG/JPG 图片。", true); }
        return Task.CompletedTask;
    }
    private void RotateClick(object sender, RoutedEventArgs e) { if (_image is not null && !_busy) { try { SetImage(ImageService.Rotate(_image)); SetStatus("图片已旋转，点击翻译。"); } catch (Exception x) when (x is InvalidOperationException or NotSupportedException) { SetStatus("图片旋转失败。", true); } } }
    private void CropClick(object sender, RoutedEventArgs e) { if (_image is null || _busy) return; var dialog = new CropWindow(_image) { Owner = this }; if (dialog.ShowDialog() == true && dialog.Result is not null) { SetImage(dialog.Result); SetStatus("图片已裁剪，点击翻译。"); } }
    private void RemoveImageClick(object sender, RoutedEventArgs e) { if (!_busy) SetImage(null); }
    private void OnDragOver(object sender, DragEventArgs e) { e.Effects = !_busy && (e.Data.GetDataPresent(DataFormats.FileDrop) || e.Data.GetDataPresent(DataFormats.Bitmap)) ? DragDropEffects.Copy : DragDropEffects.None; e.Handled = true; }
    private async void OnDrop(object sender, DragEventArgs e)
    {
        e.Handled = true;
        if (e.Data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0) await LoadImageAsync(() => ImageService.FromFile(files[0]));
        else if (e.Data.GetData(DataFormats.Bitmap) is System.Windows.Media.Imaging.BitmapSource bitmap) await LoadImageAsync(() => ImageService.FromBitmap(bitmap, "粘贴图片"));
    }
    private async void WindowKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && Keyboard.Modifiers == ModifierKeys.Control) { e.Handled = true; await TranslateAsync(); }
        else if (e.Key == Key.Escape) { if (_speech.Active) _speech.Stop(); else if (_busy) _request?.Cancel(); else if (_settings.Compact) Hide(); e.Handled = true; }
        else if (e.Key == Key.V && Keyboard.Modifiers == ModifierKeys.Control && !_busy)
        {
            try { if (Clipboard.ContainsImage()) { var bitmap = Clipboard.GetImage(); if (bitmap is not null) { e.Handled = true; await LoadImageAsync(() => ImageService.FromBitmap(bitmap, "粘贴图片")); } } }
            catch (ExternalException) { SetStatus("剪贴板正被占用，请稍后粘贴。", true); }
        }
    }
    internal void PreviewSample(bool compact)
    {
        _settings.Compact = compact; _settings.AlwaysOnTop = compact; ApplyAppearance();
        InputBox.Text = "A little less friction.\nA little more understanding."; OutputBox.Text = "少一点繁琐，\n多一点理解。"; _finished = true; CopyButton.IsEnabled = SpeakButton.IsEnabled = true; SetStatus("翻译完成 · 示例界面");
    }
}
internal static class Theme
{
    public static void Apply(bool dark)
    {
        string[] names = { "Page", "Card", "Ink", "Muted", "Line", "Accent", "Soft" };
        string[] colors = dark ? new[] { "#181B23", "#222630", "#ECEDF2", "#A2A9B8", "#333947", "#728EFF", "#293350" } : new[] { "#F5F6FA", "#FFFFFF", "#202532", "#818797", "#E8EBF1", "#4263EB", "#EDF1FF" };
        for (int i = 0; i < names.Length; i++) Application.Current.Resources[names[i]] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(colors[i]));
    }
}
