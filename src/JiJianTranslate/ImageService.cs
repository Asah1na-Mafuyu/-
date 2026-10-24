using System;
using System.IO;
using System.Windows.Media.Imaging;

namespace JiJianTranslate;

public static class ImageService
{
    public static SelectedImage FromFile(string path)
    {
        if (new FileInfo(path).Length > 32 * 1024 * 1024) throw new InvalidOperationException("图片超过 32 MB，请先裁剪或缩小。");
        using var input = File.OpenRead(path);
        var decoder = BitmapDecoder.Create(input, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        return FromBitmap(decoder.Frames[0], Path.GetFileName(path));
    }
    public static SelectedImage FromBitmap(BitmapSource bitmap, string label)
    {
        if ((long)bitmap.PixelWidth * bitmap.PixelHeight > 60_000_000) throw new InvalidOperationException("图片像素过大，请先缩小图片。");
        BitmapSource source = bitmap;
        int longest = Math.Max(source.PixelWidth, source.PixelHeight);
        if (longest > 4096)
        {
            double scale = 4096d / longest;
            source = new TransformedBitmap(source, new System.Windows.Media.ScaleTransform(scale, scale));
        }
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(source));
        using var output = new MemoryStream(); encoder.Save(output);
        if (output.Length > 28 * 1024 * 1024) throw new InvalidOperationException("图片过大，请缩小选区后重试。");
        return new SelectedImage(output.ToArray(), source.PixelWidth, source.PixelHeight, label);
    }
    public static BitmapSource Decode(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes); var image = new BitmapImage();
        image.BeginInit(); image.CacheOption = BitmapCacheOption.OnLoad; image.StreamSource = stream; image.EndInit(); image.Freeze(); return image;
    }
    public static SelectedImage Rotate(SelectedImage image)
    {
        var rotated = new TransformedBitmap(Decode(image.Bytes), new System.Windows.Media.RotateTransform(90));
        return FromBitmap(rotated, image.Label);
    }
}
