using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Win32;

namespace JiJianTranslate;

internal sealed class InstallWindow : Window
{
    internal static string InstallRoot => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "JiJianTranslate");
    private static string Shortcut => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "极简翻译.lnk");
    private const string RegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\JiJianTranslate";
    private readonly bool _remove;
    private readonly TextBlock _status;
    private readonly Button _action;
    private bool _installed;
    public InstallWindow(bool remove)
    {
        _remove = remove; Style = (Style)Application.Current.Resources[typeof(Window)]; Title = remove ? "卸载极简翻译" : "安装极简翻译"; Width = 475; Height = 345; ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var panel = new StackPanel { Margin = new Thickness(30) };
        panel.Children.Add(new TextBlock { Text = "极简翻译", FontSize = 26, FontWeight = FontWeights.SemiBold });
        panel.Children.Add(new TextBlock { Text = remove ? "卸载程序，保留你的设置和密钥。" : "文字与截图，随手翻译。", Foreground = (Brush)FindResource("Muted"), Margin = new Thickness(0, 10, 0, 24) });
        panel.Children.Add(new TextBlock { Text = remove ? "退出软件后移除程序与开始菜单快捷方式。" : "安装到当前用户目录，无需管理员权限。", TextWrapping = TextWrapping.Wrap });
        panel.Children.Add(new TextBlock { Text = InstallRoot, Foreground = (Brush)FindResource("Muted"), FontSize = 11, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 18) });
        _status = new TextBlock { Text = remove ? "需要清除密钥时，请先在软件设置中清除。" : "API Key 请在安装后的设置里填写。", FontSize = 11, TextWrapping = TextWrapping.Wrap, Foreground = (Brush)FindResource("Muted"), Margin = new Thickness(0, 0, 0, 18) }; panel.Children.Add(_status);
        _action = new Button { Content = remove ? "卸载" : "安装", Style = (Style)FindResource("PrimaryButton"), HorizontalAlignment = HorizontalAlignment.Left, Padding = new Thickness(30, 11, 30, 11) }; _action.Click += ActionClick; panel.Children.Add(_action); Content = panel;
    }
    private void ActionClick(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_installed) { Process.Start(new ProcessStartInfo(Path.Combine(InstallRoot, "极简翻译.exe")) { UseShellExecute = true }); Close(); return; }
            if (Mutex.TryOpenExisting("Local\\JiJianTranslate.Desktop", out var running)) { running?.Dispose(); _status.Text = "请先右键托盘图标退出极简翻译，再继续。"; return; }
            _action.IsEnabled = false;
            if (_remove) { Uninstall(); Application.Current.Shutdown(); return; }
            InstallPayload(Environment.ProcessPath!, InstallRoot);
            var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException();
            dynamic shell = Activator.CreateInstance(shellType)!; dynamic link = shell.CreateShortcut(Shortcut);
            try { link.TargetPath = Path.Combine(InstallRoot, "极简翻译.exe"); link.WorkingDirectory = InstallRoot; link.Description = "极简翻译"; link.Save(); }
            finally { Marshal.FinalReleaseComObject(link); Marshal.FinalReleaseComObject(shell); }
            using var reg = Registry.CurrentUser.CreateSubKey(RegistryPath);
            reg.SetValue("DisplayName", "极简翻译"); reg.SetValue("DisplayVersion", typeof(InstallWindow).Assembly.GetName().Version?.ToString(3) ?? "0.2.0"); reg.SetValue("Publisher", "极简翻译");
            reg.SetValue("InstallLocation", InstallRoot); reg.SetValue("DisplayIcon", Path.Combine(InstallRoot, "极简翻译.exe"));
            reg.SetValue("UninstallString", "\"" + Path.Combine(InstallRoot, "极简翻译.exe") + "\" --uninstall"); reg.SetValue("NoModify", 1); reg.SetValue("NoRepair", 1);
            _installed = true; _status.Text = "安装完成，已添加到开始菜单。"; _action.Content = "打开翻译";
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception or COMException)
        { _status.Text = "操作未完成，请先退出已运行的软件，检查目录权限后重试。"; }
        finally { _action.IsEnabled = true; }
    }
    internal static void InstallPayload(string source, string destination)
    {
        string root = Path.GetFullPath(destination); string target = Path.GetFullPath(Path.Combine(root, "极简翻译.exe"));
        if (!string.Equals(Path.GetDirectoryName(target), root, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException();
        if (string.Equals(Path.GetFullPath(source), target, StringComparison.OrdinalIgnoreCase)) return;
        Directory.CreateDirectory(root); string temporary = target + ".new";
        File.Copy(source, temporary, true); File.Move(temporary, target, true);
        foreach (string name in new[] { "DotNet-LICENSE.txt", "DotNet-THIRD-PARTY-NOTICES.txt", "WindowsDesktop-LICENSE.txt" })
        {
            using var input = Assembly.GetExecutingAssembly().GetManifestResourceStream("JiJianTranslate.Assets." + name);
            if (input is null) continue;
            using var output = File.Create(Path.Combine(root, name)); input.CopyTo(output);
        }
    }
    private static void Uninstall()
    {
        string expected = Path.GetFullPath(InstallRoot), exe = Path.GetFullPath(Path.Combine(expected, "极简翻译.exe"));
        if (!string.Equals(Path.GetDirectoryName(exe), expected, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException();
        File.Delete(Shortcut); Registry.CurrentUser.DeleteSubKeyTree(RegistryPath, false);
        string script = Path.Combine(Path.GetTempPath(), "JiJianTranslate-remove-" + Guid.NewGuid().ToString("N") + ".ps1");
        static string Quote(string value) => "'" + value.Replace("'", "''") + "'";
        string body = "$ErrorActionPreference = 'SilentlyContinue'\r\nWait-Process -Id " + Environment.ProcessId + " -ErrorAction SilentlyContinue\r\n" +
            "$expected = [IO.Path]::GetFullPath([IO.Path]::Combine([Environment]::GetFolderPath('LocalApplicationData'), 'Programs', 'JiJianTranslate'))\r\n" +
            "$target = " + Quote(exe) + "\r\nif ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($target)) -eq $expected) {\r\n" +
            "  Remove-Item -LiteralPath $target -Force\r\n  foreach ($leaf in @('DotNet-LICENSE.txt','DotNet-THIRD-PARTY-NOTICES.txt','WindowsDesktop-LICENSE.txt')) { Remove-Item -LiteralPath ([IO.Path]::Combine($expected, $leaf)) -Force }\r\n  if ((Test-Path -LiteralPath $expected) -and @(Get-ChildItem -LiteralPath $expected -Force).Count -eq 0) { [IO.Directory]::Delete($expected, $false) }\r\n}\r\nRemove-Item -LiteralPath " + Quote(script) + " -Force\r\n";
        File.WriteAllText(script, body, new UTF8Encoding(true));
        var start = new ProcessStartInfo("powershell.exe") { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden };
        foreach (string arg in new[] { "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script }) start.ArgumentList.Add(arg);
        Process.Start(start);
    }
}
