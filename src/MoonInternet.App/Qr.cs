using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace MoonInternet.App;

/// <summary>Renders text (a share link) to a QR image for the "Show QR code" dialog. PNG path = no System.Drawing dependency.</summary>
public static class Qr
{
    public static BitmapImage? Make(string? text, int pixelsPerModule = 8)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        try
        {
            using var gen = new QRCodeGenerator();
            using var data = gen.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
            byte[] png = new PngByteQRCode(data).GetGraphic(pixelsPerModule,
                new byte[] { 0xEC, 0xE9, 0xF5 },   // light modules (theme text colour)
                new byte[] { 0x0B, 0x09, 0x16 });   // dark background (matches the dialog card)
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = new MemoryStream(png);
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch { return null; }
    }
}
