package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class OcrInstrumentedTest {
    static Bitmap fixture() {
        Bitmap page = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(page);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK); paint.setTextSize(64);
        canvas.drawText("資料の文字をコピーする", 80, 180, paint);
        canvas.drawText("Android 16", 80, 400, paint);
        return page;
    }

    @Test public void bundledModelsRecognizeJapaneseAndEnglishAndHonorCrop() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (OcrEngine engine = new OcrEngine(context)) {
            Bitmap page = fixture();
            List<OcrLine> result = engine.read(page, new Rect(0, 0, 1200, 700), new AtomicBoolean(), (stage, done, total) -> {});
            String text = ReadingOrder.text(result);
            assertTrue(text.contains("資料"));
            assertTrue(text.contains("コピー"));
            assertTrue(text.toLowerCase().contains("android"));
            assertEquals(2, result.size());
            List<OcrLine> crop = engine.read(page, new Rect(20, 90, 1000, 220), new AtomicBoolean(), (stage, done, total) -> {});
            assertTrue(ReadingOrder.text(crop).contains("資料"));
            assertFalse(ReadingOrder.text(crop).toLowerCase().contains("android"));
            assertTrue(crop.get(0).top() >= 90f);
            page.recycle();
        }
    }

    @Test public void blankImageProducesNoText() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (OcrEngine engine = new OcrEngine(context)) {
            Bitmap blank = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            assertTrue(engine.read(blank, new Rect(0, 0, 800, 800), new AtomicBoolean(), (stage, done, total) -> {}).isEmpty());
            blank.recycle();
        }
    }

    @Test public void verticalJapaneseIsRotatedBeforeRecognition() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap page = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(page); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(64);
        String phrase = "資料の文字を読む";
        for (int i = 0; i < phrase.length(); i++) canvas.drawText(phrase.substring(i, i + 1), 500, 120 + i * 70, paint);
        try (OcrEngine engine = new OcrEngine(context)) {
            List<OcrLine> result = engine.read(page, new Rect(0, 0, 800, 1000), new AtomicBoolean(), (stage, done, total) -> {});
            assertTrue(ReadingOrder.text(result).contains("資料"));
            assertTrue(result.stream().anyMatch(OcrLine::vertical));
        }
        page.recycle();
    }
}
