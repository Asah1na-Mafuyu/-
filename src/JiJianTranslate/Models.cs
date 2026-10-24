using System;

namespace JiJianTranslate;

public sealed class AppSettings
{
    public string SourceLanguage { get; set; } = "自动识别";
    public string TargetLanguage { get; set; } = "简体中文";
    public bool AlwaysOnTop { get; set; }
    public bool Compact { get; set; }
    public bool DarkMode { get; set; }
    public int EditorFontSize { get; set; } = 15;
    public bool AutoSpeak { get; set; }
    public int SpeechRate { get; set; }
    public decimal MonthlyBudget { get; set; } = 15m;
    public string CaptureHotkey { get; set; } = "Ctrl+Alt+A";
    public string WindowHotkey { get; set; } = "Ctrl+Alt+T";
}
public sealed record ApiUsage(int Input, int Output, int CacheHit)
{
    public decimal EstimatedCost => (Math.Max(0, Input - CacheHit) * 2m + Math.Min(Input, CacheHit) * .04m + Output * 8m) / 1_000_000m;
}
public sealed record TranslationResult(string Original, string Translation, ApiUsage Usage);
public sealed class TranslationException : Exception
{
    public ApiUsage? Usage { get; }
    public bool Submitted { get; }
    public TranslationException(string message, bool submitted = false, ApiUsage? usage = null, Exception? inner = null) : base(message, inner) { Submitted = submitted; Usage = usage; }
}
public sealed record SelectedImage(byte[] Bytes, int Width, int Height, string Label);
