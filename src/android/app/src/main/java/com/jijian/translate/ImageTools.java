package com.jijian.translate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

final class ImageTools {
    static final class ImageData {
        final Bitmap bitmap; final byte[] bytes;
        ImageData(Bitmap bitmap, byte[] bytes) { this.bitmap = bitmap; this.bytes = bytes; }
    }
    static ImageData fromBitmap(Bitmap bitmap) throws Exception {
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max > 2048) bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 2048 / max), Math.max(1, bitmap.getHeight() * 2048 / max), true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new Exception("图片无法读取。");
        byte[] bytes = output.toByteArray(); if (bytes.length > 16 * 1024 * 1024) throw new Exception("图片太大，请先缩小或裁剪。");
        return new ImageData(bitmap, bytes);
    }
    static ImageData load(Context context, Uri uri) throws Exception {
        byte[] bytes;
        try (InputStream stream = context.getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (stream == null) throw new Exception("无法打开图片。"); byte[] buffer = new byte[16384]; int count, size = 0;
            while ((count = stream.read(buffer)) != -1) { size += count; if (size > 32 * 1024 * 1024) throw new Exception("图片超过 32 MB，请先缩小。"); output.write(buffer, 0, count); }
            bytes = output.toByteArray();
        }
        Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            if ((long) width * height > 60_000_000L) throw new IllegalArgumentException("图片尺寸太大。");
            int max = Math.max(width, height); if (max > 2048) decoder.setTargetSize(Math.max(1, width * 2048 / max), Math.max(1, height * 2048 / max));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        return fromBitmap(bitmap);
    }
    static Bitmap rotate(Bitmap bitmap) { Matrix matrix = new Matrix(); matrix.postRotate(90); return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true); }
}
