using System;
using System.IO;
using System.Threading;
using System.Windows;
using System.Windows.Threading;

namespace JiJianTranslate;

public partial class App : Application
{
    private Mutex? _mutex;
    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        DispatcherUnhandledException += OnUnhandled;
        if (e.Args.Length > 0 && e.Args[0] == "--speech-check")
        {
            int code = await SpeechChecks.RunAsync(e.Args.Length > 1 ? e.Args[1] : Path.Combine(AppContext.BaseDirectory,"speech-qa")); Shutdown(code); return;
        }
        if (e.Args.Length > 0 && e.Args[0] == "--diagnostics")
        {
            int code = await Diagnostics.RunAsync(e.Args.Length > 1 ? e.Args[1] : Path.Combine(AppContext.BaseDirectory, "qa"));
            Shutdown(code); return;
        }
        string exeName = Path.GetFileNameWithoutExtension(Environment.ProcessPath) ?? "";
        if (Array.IndexOf(e.Args, "--install") >= 0 || exeName.Contains("安装", StringComparison.Ordinal))
        {
            ShutdownMode = ShutdownMode.OnLastWindowClose;
            new InstallWindow(false).Show(); return;
        }
        if (Array.IndexOf(e.Args, "--uninstall") >= 0)
        {
            ShutdownMode = ShutdownMode.OnLastWindowClose;
            new InstallWindow(true).Show(); return;
        }
        _mutex = new Mutex(true, "Local\\JiJianTranslate.Desktop", out bool isNew);
        if (!isNew)
        {
            var hwnd = Native.FindWindow(null, "极简翻译");
            if (hwnd != IntPtr.Zero) { Native.ShowWindow(hwnd, 9); Native.SetForegroundWindow(hwnd); }
            Shutdown(); return;
        }
        var main = new MainWindow(); MainWindow = main; main.Show();
    }
    private void OnUnhandled(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        e.Handled = true;
        MessageBox.Show("操作未完成，请重试或重新打开软件。你的设置会保留。", "极简翻译", MessageBoxButton.OK, MessageBoxImage.Information);
    }
    protected override void OnExit(ExitEventArgs e) { _mutex?.Dispose(); base.OnExit(e); }
}
