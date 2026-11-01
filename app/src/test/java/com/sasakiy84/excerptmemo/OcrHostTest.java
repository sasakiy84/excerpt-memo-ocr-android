package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
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
            assertThat(input).isNotNull();
            return BitmapFactory.decodeStream(input);
        }
    }

    @Test public void nativeEngineReadsJapaneseAndEnglishAndKeepsCropCoordinates() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap page = fixture("horizontal.png");
            var result = engine.read(page, new Rect(0, 0, 1200, 700), new AtomicBoolean(), (done, total) -> {});
            assertThat(result).hasSize(2);
            assertThat(ReadingOrder.text(result)).isEqualTo("資料の文字をコピーする\nAndroid 16");
            var cropped = engine.read(page, new Rect(20, 90, 1000, 220), new AtomicBoolean(), (done, total) -> {});
            assertThat(ReadingOrder.text(cropped)).isEqualTo("資料の文字をコピーする");
            assertThat(cropped.get(0).top()).isAtLeast(90f);
            page.recycle();
        }
    }

    @Test public void nativeEngineReadsVerticalJapanese() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap page = fixture("vertical.png");
            var result = engine.read(page, new Rect(0, 0, page.getWidth(), page.getHeight()), new AtomicBoolean(), (done, total) -> {});
            assertThat(ReadingOrder.text(result)).contains("資料");
            assertThat(result.stream().anyMatch(OcrLine::vertical)).isTrue();
            page.recycle();
        }
    }

    @Test public void nativeEngineReturnsNoTextForBlankPage() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap blank = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
            blank.eraseColor(Color.WHITE);
            assertThat(engine.read(blank, new Rect(0, 0, 800, 800), new AtomicBoolean(), (done, total) -> {})).isEmpty();
            blank.recycle();
        }
    }
}
