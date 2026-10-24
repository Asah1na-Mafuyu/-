using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace JiJianTranslate;

internal static class Native
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] internal static extern IntPtr FindWindow(string? cls, string title);
    [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] internal static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool RegisterHotKey(IntPtr hwnd, int id, uint modifiers, uint key);
    [DllImport("user32.dll")] internal static extern bool UnregisterHotKey(IntPtr hwnd, int id);
    [DllImport("user32.dll")] internal static extern bool GetCursorPos(out PointI point);
    [DllImport("user32.dll")] internal static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] internal static extern bool PostMessage(IntPtr hwnd, int message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("gdi32.dll")] internal static extern bool DeleteObject(IntPtr obj);
    [DllImport("user32.dll")] internal static extern bool DestroyIcon(IntPtr icon);
    [StructLayout(LayoutKind.Sequential)] internal struct PointI { public int X; public int Y; }
}
public sealed class HotkeyService : IDisposable
{
    private readonly IntPtr _handle;
    private readonly HwndSource _source;
    private readonly Action _capture, _toggle;
    private string _captureKey = "", _windowKey = "";
    public HotkeyService(Window window, Action capture, Action toggle)
    {
        _handle = new WindowInteropHelper(window).EnsureHandle(); _source = HwndSource.FromHwnd(_handle); _source.AddHook(Hook);
        _capture = capture; _toggle = toggle;
    }
    private static (uint Modifiers, uint Key) Parse(string value)
    {
        var parts = value.Split('+'); uint mods = 0x4000;
        foreach (string p in parts) mods |= p switch { "Ctrl" => 2u, "Alt" => 1u, "Shift" => 4u, _ => 0u };
        return (mods, parts[^1][0]);
    }
    private bool Register(int id, string value) { var p = Parse(value); return Native.RegisterHotKey(_handle, id, p.Modifiers, p.Key); }
    public void Configure(string capture, string toggle)
    {
        Native.UnregisterHotKey(_handle, 1); Native.UnregisterHotKey(_handle, 2);
        bool a = Register(1, capture), b = Register(2, toggle);
        if (!a || !b)
        {
            Native.UnregisterHotKey(_handle, 1); Native.UnregisterHotKey(_handle, 2);
            if (_captureKey.Length > 0) Register(1, _captureKey);
            if (_windowKey.Length > 0) Register(2, _windowKey);
            throw new InvalidOperationException($"快捷键 {(!a ? capture : toggle)} 已被其他程序占用，请在设置中更换。");
        }
        _captureKey = capture; _windowKey = toggle;
    }
    private IntPtr Hook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == 0x312) { if (wParam.ToInt32() == 1) _capture(); if (wParam.ToInt32() == 2) _toggle(); handled = true; }
        return IntPtr.Zero;
    }
    public void Dispose() { Native.UnregisterHotKey(_handle, 1); Native.UnregisterHotKey(_handle, 2); _source.RemoveHook(Hook); }
}
