using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Forms = System.Windows.Forms;
using Rect = System.Windows.Rect;
using Point = System.Windows.Point;
using Color = System.Windows.Media.Color;
using Brushes = System.Windows.Media.Brushes;

namespace JiJianTranslate;

public sealed class CaptureService
{
    private readonly List<CaptureWindow> _windows = new();
    private TaskCompletionSource<SelectedImage?>? _completion;
    private BitmapSource? _desktop;
    private Rectangle _bounds;
    private Native.PointI _anchor;
    private bool _dragging;
    public Task<SelectedImage?> CaptureAsync()
    {
        if (_completion is not null) throw new InvalidOperationException("截图正在进行。");
        var completion = new TaskCompletionSource<SelectedImage?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _completion = completion;
        try
        {
            var screens = Forms.Screen.AllScreens;
            _bounds = screens.Select(s => s.Bounds).Aggregate(Rectangle.Union);
            _desktop = CaptureDesktop(_bounds);
            foreach (var screen in screens)
            {
                var b = screen.Bounds;
                var image = new CroppedBitmap(_desktop, new Int32Rect(b.X - _bounds.X, b.Y - _bounds.Y, b.Width, b.Height)); image.Freeze();
                var window = new CaptureWindow(this, b, image); _windows.Add(window); window.Show();
                Native.SetWindowPos(new WindowInteropHelper(window).Handle, new IntPtr(-1), b.X, b.Y, b.Width, b.Height, 0x0040);
            }
            _windows.FirstOrDefault()?.Activate();
        }
        catch (Exception ex) { Finish(null, ex); }
        return completion.Task;
    }
    public static BitmapSource CaptureDesktop(Rectangle bounds)
    {
        using var bitmap = new Bitmap(bounds.Width, bounds.Height, System.Drawing.Imaging.PixelFormat.Format32bppArgb);
        using (var graphics = Graphics.FromImage(bitmap)) graphics.CopyFromScreen(bounds.Location, System.Drawing.Point.Empty, bounds.Size, CopyPixelOperation.SourceCopy);
        IntPtr handle = bitmap.GetHbitmap();
        try { var source = Imaging.CreateBitmapSourceFromHBitmap(handle, IntPtr.Zero, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions()); source.Freeze(); return source; }
        finally { Native.DeleteObject(handle); }
    }
    internal void Begin(CaptureWindow window)
    {
        Native.GetCursorPos(out _anchor); _dragging = true; window.CaptureMouse(); Update();
    }
    internal void Update()
    {
        if (!_dragging) return;
        Native.GetCursorPos(out var now);
        var area = Selection(_anchor.X, _anchor.Y, now.X, now.Y, _bounds);
        foreach (var window in _windows) window.SetSelection(area);
    }
    internal static Rectangle Selection(int x1, int y1, int x2, int y2, Rectangle bounds) => Rectangle.Intersect(Rectangle.FromLTRB(Math.Min(x1, x2), Math.Min(y1, y2), Math.Max(x1, x2), Math.Max(y1, y2)), bounds);
    internal void End()
    {
        if (!_dragging || _desktop is null) return;
        Native.GetCursorPos(out var now); var area = Selection(_anchor.X, _anchor.Y, now.X, now.Y, _bounds); _dragging = false;
        if (area.Width < 8 || area.Height < 8) { Finish(null); return; }
        try
        {
            var cropped = new CroppedBitmap(_desktop, new Int32Rect(area.X - _bounds.X, area.Y - _bounds.Y, area.Width, area.Height)); cropped.Freeze();
            Finish(ImageService.FromBitmap(cropped, "截图"));
        }
        catch (Exception e) { Finish(null, e); }
    }
    internal void Cancel() => Finish(null);
    private void Finish(SelectedImage? image, Exception? error = null)
    {
        _dragging = false;
        foreach (var window in _windows.ToArray()) { window.ReleaseMouseCapture(); window.Close(); }
        _windows.Clear(); _desktop = null;
        var task = _completion; _completion = null;
        if (error is null) task?.TrySetResult(image); else task?.TrySetException(error);
    }
}
internal sealed class CaptureWindow : Window
{
    private readonly CaptureService _owner;
    private readonly Rectangle _screen;
    private readonly BitmapSource _image;
    private Rectangle _selection;
    internal CaptureWindow(CaptureService owner, Rectangle screen, BitmapSource image)
    {
        _owner = owner; _screen = screen; _image = image;
        WindowStyle = WindowStyle.None; ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false; Topmost = true;
        WindowStartupLocation = WindowStartupLocation.Manual; Left = screen.X; Top = screen.Y; Width = screen.Width; Height = screen.Height;
        Background = Brushes.Black; Cursor = Cursors.Cross; Focusable = true;
        MouseLeftButtonDown += (_, e) => { _owner.Begin(this); e.Handled = true; };
        MouseMove += (_, _) => _owner.Update(); MouseLeftButtonUp += (_, _) => _owner.End();
        KeyDown += (_, e) => { if (e.Key == Key.Escape) _owner.Cancel(); };
    }
    internal void SetSelection(Rectangle selection) { _selection = selection; InvalidateVisual(); }
    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        var full = new Rect(0, 0, ActualWidth, ActualHeight); dc.DrawImage(_image, full);
        var clip = Rectangle.Intersect(_selection, _screen);
        var selection = new Rect((clip.X - _screen.X) * ActualWidth / _screen.Width, (clip.Y - _screen.Y) * ActualHeight / _screen.Height, clip.Width * ActualWidth / _screen.Width, clip.Height * ActualHeight / _screen.Height);
        var mask = new GeometryGroup { FillRule = FillRule.EvenOdd }; mask.Children.Add(new RectangleGeometry(full));
        if (clip.Width > 0 && clip.Height > 0) mask.Children.Add(new RectangleGeometry(selection));
        dc.DrawGeometry(new SolidColorBrush(Color.FromArgb(135, 10, 15, 30)), null, mask);
        if (clip.Width > 0 && clip.Height > 0) dc.DrawRectangle(null, new System.Windows.Media.Pen(new SolidColorBrush(Color.FromRgb(112, 150, 255)), 2), selection);
        var text = new FormattedText("拖动选择翻译区域   ·   Esc 取消", System.Globalization.CultureInfo.CurrentCulture, FlowDirection.LeftToRight, new Typeface("Microsoft YaHei UI"), 14, Brushes.White, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromArgb(225, 28, 33, 48)), null, new Rect(24, 24, text.Width + 32, 44), 10, 10); dc.DrawText(text, new Point(40, 36));
    }
}
