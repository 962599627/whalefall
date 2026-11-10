package com.whalefall.movie;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** 本地二维码渲染（zxing，已有依赖；不引新库）。 */
final class PanQr {
    private PanQr() {}

    static Bitmap render(String content, int sizePx) {
        if (content == null || content.trim().isEmpty() || sizePx <= 0) return null;
        try {
            BitMatrix matrix =
                    new QRCodeWriter()
                            .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx);
            Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < sizePx; x++)
                for (int y = 0; y < sizePx; y++)
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** JSONP 回调包装（cb({...})）→ 纯 JSON。 */
    static String stripJsonp(String body) {
        if (body == null) return "";
        String text = body.trim();
        int open = text.indexOf('(');
        int close = text.lastIndexOf(')');
        if (open >= 0 && close > open && !text.startsWith("{")) return text.substring(open + 1, close);
        return text;
    }
}
