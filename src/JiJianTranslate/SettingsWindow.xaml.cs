using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Media;

namespace JiJianTranslate;

public partial class SettingsWindow : Window
{
    private readonly AppSettings _draft;
    private readonly LocalStore _store;
    private readonly DeepSeekClient _client;
    private readonly Action<AppSettings> _apply;
    private CancellationTokenSource? _test;
    public SettingsWindow(AppSettings settings, LocalStore store, DeepSeekClient client, Action<AppSettings> apply)
    {
        _draft = JsonSerializer.Deserialize<AppSettings>(JsonSerializer.Serialize(settings))!; _store = store; _client = client; _apply = apply;
        InitializeComponent(); KeyBox.Password = _store.LoadKey(); BudgetBox.Text = settings.MonthlyBudget.ToString(CultureInfo.InvariantCulture);
        CaptureKey.ItemsSource = new[] { "Ctrl+Alt+A", "Ctrl+Shift+A", "Alt+Shift+A" }; CaptureKey.SelectedItem = settings.CaptureHotkey;
        WindowKey.ItemsSource = new[] { "Ctrl+Alt+T", "Ctrl+Shift+T", "Alt+Shift+T" }; WindowKey.SelectedItem = settings.WindowHotkey;
        DarkCheck.IsChecked = settings.DarkMode; FontSizeBox.ItemsSource = new[] { 12, 14, 15, 16, 18, 20, 24 }; FontSizeBox.SelectedItem = settings.EditorFontSize;
        AutoSpeakCheck.IsChecked = settings.AutoSpeak; SpeechRateBox.ItemsSource = new[] { "慢速", "正常", "快速" }; SpeechRateBox.SelectedIndex = settings.SpeechRate == -20 ? 0 : settings.SpeechRate == 20 ? 2 : 1;
        Closed += (_, _) => _test?.Cancel();
    }
    private void SaveClick(object sender, RoutedEventArgs e)
    {
        if (!decimal.TryParse(BudgetBox.Text, NumberStyles.Number, CultureInfo.InvariantCulture, out decimal budget) || budget <= 0 || budget > 10000) { Status("预算应大于 0 且不超过 10,000 元。", true); return; }
        if (KeyBox.Password.Trim().Any(char.IsWhiteSpace)) { Status("密钥中不能包含空格或换行。", true); return; }
        _draft.MonthlyBudget = budget; _draft.CaptureHotkey = CaptureKey.SelectedItem?.ToString() ?? "Ctrl+Alt+A"; _draft.WindowHotkey = WindowKey.SelectedItem?.ToString() ?? "Ctrl+Alt+T";
        _draft.DarkMode = DarkCheck.IsChecked == true; _draft.EditorFontSize = FontSizeBox.SelectedItem is int size ? size : 15;
        _draft.AutoSpeak = AutoSpeakCheck.IsChecked == true; _draft.SpeechRate = SpeechRateBox.SelectedIndex == 0 ? -20 : SpeechRateBox.SelectedIndex == 2 ? 20 : 0;
        var previous = _store.LoadSettings(); string previousKey = _store.LoadKey();
        try { _store.SaveKey(KeyBox.Password.Trim()); _store.SaveSettings(_draft); _apply(_draft); DialogResult = true; }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException)
        {
            try { _store.SaveKey(previousKey); _store.SaveSettings(previous); } catch (Exception x) when (x is IOException or UnauthorizedAccessException or System.Security.Cryptography.CryptographicException) { }
            Status(ex is InvalidOperationException ? ex.Message : "设置保存失败，请检查数据目录权限。", true);
        }
    }
    private async void TestClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(KeyBox.Password)) { Status("请先填写 API Key。", true); return; }
        _test?.Cancel(); _test = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        TestButton.IsEnabled = SaveButton.IsEnabled = false; Status("正在测试连接…");
        try { bool model = await _client.TestConnectionAsync(KeyBox.Password, _test.Token); Status(model ? "连接成功，可以使用 Flash 翻译。" : "密钥可用，但账号未返回 deepseek-flash，请核实模型权限。", !model); }
        catch (TranslationException ex) { Status(ex.Message, true); }
        catch (OperationCanceledException) { Status("连接测试超时或已取消，请检查网络。", true); }
        catch (Exception ex) when (ex is System.Net.Http.HttpRequestException or JsonException) { Status("无法连接，请检查网络或系统代理。", true); }
        finally { TestButton.IsEnabled = SaveButton.IsEnabled = true; _test?.Dispose(); _test = null; }
    }
    private void Status(string text, bool error = false) { SettingsStatus.Text = text; SettingsStatus.Foreground = error ? Brushes.IndianRed : (System.Windows.Media.Brush)FindResource("Muted"); }
    private void ClearKeyClick(object sender, RoutedEventArgs e) { KeyBox.Clear(); Status("点击“保存设置”后清除本机密钥。"); }
    private void CancelClick(object sender, RoutedEventArgs e) => Close();
    private void LicensesClick(object sender, RoutedEventArgs e)
    {
        var box = new System.Windows.Controls.TextBox { IsReadOnly = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = System.Windows.Controls.ScrollBarVisibility.Auto, Margin = new Thickness(16), Text = Read("Edge-NOTICE.txt") + "\n\n" + Read("Edge-LICENSE.txt") + "\n\n" + Read("Edge-GPL-3.0.txt") };
        new Window { Title = "开源许可", Width = 620, Height = 580, Owner = this, Content = box }.ShowDialog();
        static string Read(string name) { var assembly = typeof(SettingsWindow).Assembly; using var stream = assembly.GetManifestResourceStream("JiJianTranslate.Assets." + name); if (stream is null) return name; using var reader = new StreamReader(stream); return reader.ReadToEnd(); }
    }
    private void DataClick(object sender, RoutedEventArgs e) { try { Process.Start(new ProcessStartInfo("explorer.exe", _store.Root) { UseShellExecute = true }); } catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException) { Status("数据目录：" + _store.Root); } }
    private void PlatformClick(object sender, RoutedEventArgs e) { try { Process.Start(new ProcessStartInfo("https://platform.deepseek.com/api_keys") { UseShellExecute = true }); } catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException) { Status("请在浏览器打开 platform.deepseek.com。"); } }
}
