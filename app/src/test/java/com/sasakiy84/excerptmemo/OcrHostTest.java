package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class OcrHostTest {
    private static Bitmap fixture(String name) throws Exception {
        try (InputStream input = OcrHostTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(input);
            return BitmapFactory.decodeStream(input);
        }
    }

    @Test public void nativeEngineReadsJapaneseAndEnglishAndKeepsCropCoordinates() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap page = fixture("horizontal.png");
            var progress = new java.util.ArrayList<String>();
            var result = engine.read(page, new Rect(0, 0, 1200, 700), new AtomicBoolean(), (stage, done, total) -> progress.add(stage + ":" + done + "/" + total));
            assertEquals(java.util.List.of("NDL_DETECTION:0/1", "NDL_RECOGNITION:0/2", "NDL_RECOGNITION:1/2", "NDL_RECOGNITION:2/2"), progress);
            assertEquals(2, result.size());
            assertEquals("資料の文字をコピーする\nAndroid 16", ReadingOrder.text(result));
            var cropped = engine.read(page, new Rect(20, 90, 1000, 220), new AtomicBoolean(), (stage, done, total) -> {});
            assertEquals("資料の文字をコピーする", ReadingOrder.text(cropped));
            assertTrue(cropped.get(0).top() >= 90f);
            page.recycle();
        }
    }

    @Test public void nativeEngineReadsVerticalJapanese() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap page = fixture("vertical.png");
            var result = engine.read(page, new Rect(0, 0, page.getWidth(), page.getHeight()), new AtomicBoolean(), (stage, done, total) -> {});
            assertTrue(ReadingOrder.text(result).contains("資料"));
            assertTrue(result.stream().anyMatch(OcrLine::vertical));
            page.recycle();
        }
    }

    @Test public void nativeEngineReturnsNoTextForBlankPage() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap blank = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            assertTrue(engine.read(blank, new Rect(0, 0, 800, 800), new AtomicBoolean(), (stage, done, total) -> {}).isEmpty());
            blank.recycle();
        }
    }
}
