package com.sasakiy84.excerptmemo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

final class ImageEdits {
    private ImageEdits() {}
    static Matrix rotation(Bitmap source, float degrees, RectF bounds) {
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        bounds.set(0, 0, source.getWidth(), source.getHeight());
        matrix.mapRect(bounds);
        matrix.postTranslate(-bounds.left, -bounds.top);
        bounds.offsetTo(0, 0);
        return matrix;
    }
    static Bitmap apply(Bitmap source, float degrees, RectF normalizedCrop) {
        RectF bounds = new RectF();
        Matrix matrix = rotation(source, degrees, bounds);
        RectF crop = new RectF(normalizedCrop.left * bounds.width(), normalizedCrop.top * bounds.height(),
                normalizedCrop.right * bounds.width(), normalizedCrop.bottom * bounds.height());
        if (!crop.intersect(bounds) || crop.width() < 1 || crop.height() < 1) throw new IllegalArgumentException("Empty crop");
        float scale = Math.min(1, 2800f / Math.max(crop.width(), crop.height()));
        Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(crop.width() * scale)),
                Math.max(1, Math.round(crop.height() * scale)), Bitmap.Config.ARGB_8888);
        matrix.postTranslate(-crop.left, -crop.top);
        matrix.postScale(scale, scale);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        return result;
    }
}
