using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace JiJianTranslate;

public sealed class LocalStore
{
    public string Root { get; }
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    public LocalStore(string? root = null) { Root = root ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "JiJianTranslate"); Directory.CreateDirectory(Root); }
    public AppSettings LoadSettings()
    {
        try
        {
            var s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Path.Combine(Root, "settings.json"))) ?? new();
            s.EditorFontSize = Math.Clamp(s.EditorFontSize, 12, 24);
            if (s.MonthlyBudget < 0 || s.MonthlyBudget > 10000) s.MonthlyBudget = 15;
            return s;
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { return new(); }
    }
    public void SaveSettings(AppSettings settings) => AtomicWrite("settings.json", JsonSerializer.Serialize(settings, Json));
    public void SaveKey(string key)
    {
        if (string.IsNullOrWhiteSpace(key)) { File.Delete(Path.Combine(Root, "key.dat")); return; }
        byte[] plain = Encoding.UTF8.GetBytes(key.Trim());
        try { AtomicWrite("key.dat", Convert.ToBase64String(ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser))); }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }
    public string LoadKey()
    {
        try
        {
            byte[] data = ProtectedData.Unprotect(Convert.FromBase64String(File.ReadAllText(Path.Combine(Root, "key.dat"))), null, DataProtectionScope.CurrentUser);
            try { return Encoding.UTF8.GetString(data); }
            finally { CryptographicOperations.ZeroMemory(data); }
        }
        catch (Exception e) when (e is IOException or FormatException or CryptographicException or UnauthorizedAccessException) { return ""; }
    }
    public void AtomicWrite(string name, string content)
    {
        string path = Path.Combine(Root, name), tmp = path + ".tmp";
        File.WriteAllText(tmp, content, new UTF8Encoding(false)); File.Move(tmp, path, true);
    }
}
public sealed class UsageEntry
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public DateTimeOffset Time { get; set; } = DateTimeOffset.Now;
    public decimal Cost { get; set; }
    public int Input { get; set; }
    public int Output { get; set; }
    public string State { get; set; } = "待核对";
}
public sealed class UsageLedger
{
    private readonly LocalStore _store;
    private readonly List<UsageEntry> _entries;
    public UsageLedger(LocalStore store)
    {
        _store = store;
        string path = Path.Combine(store.Root, "usage.json");
        if (!File.Exists(path)) { _entries = new(); return; }
        try
        {
            _entries = JsonSerializer.Deserialize<List<UsageEntry>>(File.ReadAllText(path)) ?? throw new JsonException();
            if (_entries.Any(e => e is null || e.Cost < 0 || e.Input < 0 || e.Output < 0 || string.IsNullOrWhiteSpace(e.Id))) throw new JsonException();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException)
        { throw new InvalidOperationException("用量记录无法读取，为避免丢失预算统计，暂不发送请求。请从设置打开数据目录备份 usage.json 后处理。", e); }
    }
    private static string Month(DateTimeOffset t) => t.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM", CultureInfo.InvariantCulture);
    public decimal MonthlyCost => _entries.Where(e => Month(e.Time) == Month(DateTimeOffset.Now)).Sum(e => e.Cost);
    public int PendingCount => _entries.Count(e => Month(e.Time) == Month(DateTimeOffset.Now) && e.State == "待核对");
    public UsageEntry Reserve(decimal amount, decimal budget)
    {
        if (budget <= 0 || MonthlyCost + amount > budget) throw new InvalidOperationException("已接近本机月预算。请在设置里调整预算后继续。");
        var e = new UsageEntry { Cost = amount }; _entries.Add(e);
        try { Save(); } catch { _entries.Remove(e); throw; }
        return e;
    }
    public void Complete(UsageEntry entry, ApiUsage? usage, bool submitted)
    {
        if (!submitted) { entry.Cost = 0; entry.State = "未发送"; }
        else if (usage is not null) { entry.Cost = usage.EstimatedCost; entry.Input = usage.Input; entry.Output = usage.Output; entry.State = "已核算"; }
        Save();
    }
    private void Save() => _store.AtomicWrite("usage.json", JsonSerializer.Serialize(_entries));
}
