using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;

namespace JiJianTranslate;

internal sealed class CropWindow : Window
{
    private readonly BitmapSource _bitmap;
    private readonly Grid _surface;
    private readonly Canvas _canvas;
    private readonly Rectangle _outline;
    private readonly Button _accept;
    private Point _anchor;
    private Rect _selection;
    private bool _dragging;
    public SelectedImage? Result { get; private set; }
    public CropWindow(SelectedImage image)
    {
        _bitmap = ImageService.Decode(image.Bytes); Style = (Style)Application.Current.Resources[typeof(Window)]; Title = "裁剪图片 · 拖动选择要翻译的部分"; Width = 720; Height = 540; MinWidth = 400; MinHeight = 320; WindowStartupLocation = WindowStartupLocation.CenterOwner; ShowInTaskbar = false;
        var root = new Grid { Margin = new Thickness(18) }; root.RowDefinitions.Add(new RowDefinition()); root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        _surface = new Grid { Background = new SolidColorBrush(Color.FromRgb(35, 39, 49)), ClipToBounds = true };
        _surface.Children.Add(new Image { Source = _bitmap, Stretch = Stretch.Uniform });
        _canvas = new Canvas { Background = Brushes.Transparent }; _outline = new Rectangle { Stroke = new SolidColorBrush(Color.FromRgb(112, 150, 255)), StrokeThickness = 2, Fill = new SolidColorBrush(Color.FromArgb(30, 112, 150, 255)), IsHitTestVisible = false }; _canvas.Children.Add(_outline); _surface.Children.Add(_canvas); root.Children.Add(_surface);
        var footer = new Grid { Margin = new Thickness(0, 12, 0, 0) }; Grid.SetRow(footer, 1);
        footer.Children.Add(new TextBlock { Text = "拖动框选，按 Esc 取消", VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)FindResource("Muted") });
        _accept = new Button { Content = "使用选区", IsEnabled = false, HorizontalAlignment = HorizontalAlignment.Right, Style = (Style)FindResource("PrimaryButton") }; footer.Children.Add(_accept); root.Children.Add(footer); Content = root;
        _canvas.MouseLeftButtonDown += (_, e) => { _anchor = Clamp(e.GetPosition(_canvas)); _dragging = true; _canvas.CaptureMouse(); };
        _canvas.MouseMove += (_, e) => { if (!_dragging) return; Point end = Clamp(e.GetPosition(_canvas)); _selection = new Rect(_anchor, end); Canvas.SetLeft(_outline, _selection.X); Canvas.SetTop(_outline, _selection.Y); _outline.Width = _selection.Width; _outline.Height = _selection.Height; };
        _canvas.MouseLeftButtonUp += (_, _) => { _dragging = false; _canvas.ReleaseMouseCapture(); _accept.IsEnabled = _selection.Width >= 8 && _selection.Height >= 8; };
        _surface.SizeChanged += (_, _) => { _selection = Rect.Empty; _outline.Width = _outline.Height = 0; _accept.IsEnabled = false; };
        _accept.Click += (_, _) => { var bounds = ImageBounds(); double scale = _bitmap.PixelWidth / bounds.Width; int x = Math.Clamp((int)Math.Round((_selection.X - bounds.X) * scale), 0, _bitmap.PixelWidth - 1), y = Math.Clamp((int)Math.Round((_selection.Y - bounds.Y) * scale), 0, _bitmap.PixelHeight - 1); int w = Math.Clamp((int)Math.Round(_selection.Width * scale), 1, _bitmap.PixelWidth - x), h = Math.Clamp((int)Math.Round(_selection.Height * scale), 1, _bitmap.PixelHeight - y); Result = ImageService.FromBitmap(new CroppedBitmap(_bitmap, new Int32Rect(x, y, w, h)), "裁剪图片"); DialogResult = true; };
        KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
    }
    private Rect ImageBounds() { double scale = Math.Min(_surface.ActualWidth / _bitmap.PixelWidth, _surface.ActualHeight / _bitmap.PixelHeight); double w = _bitmap.PixelWidth * scale, h = _bitmap.PixelHeight * scale; return new Rect((_surface.ActualWidth - w) / 2, (_surface.ActualHeight - h) / 2, w, h); }
    private Point Clamp(Point p) { var r = ImageBounds(); return new Point(Math.Clamp(p.X, r.Left, r.Right), Math.Clamp(p.Y, r.Top, r.Bottom)); }
}
