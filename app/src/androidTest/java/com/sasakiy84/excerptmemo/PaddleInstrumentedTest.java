package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PaddleInstrumentedTest {
    private static Bitmap fixture() {
        // Native compatibility smoke check; timing comparisons use the full photographs.
        Bitmap full = OcrInstrumentedTest.fixture();
        Bitmap source = Bitmap.createScaledBitmap(full, 480, 280, true);
        full.recycle();
        return source;
    }

    @Test(timeout = 600000) public void bundledPaddleReadsJapaneseAndEnglishOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source = fixture();
        try (OcrEngine engine = new OcrEngine(context)) {
            var paddle = new PaddleOcr(context, engine::session);
            String text = ReadingOrder.text(paddle.read(source, new AtomicBoolean(), (stage, done, total) -> {}));
            assertThat(text).contains("資料の文字をコピーする");
            assertThat(text.toLowerCase()).contains("android");
            android.util.Log.i("OcrNativeTest", "PP-OCR verified");
        } finally { source.recycle(); }
    }

    @Test(timeout = 3600000) public void bundledUvdocPreservesOutputDimensionsOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.util.Log.i("OcrNativeTest", "Providers: " + ai.onnxruntime.OrtEnvironment.getAvailableProviders());
        Bitmap source = fixture();
        try (OcrEngine engine = new OcrEngine(context)) {
            Bitmap flattened = engine.flatten(source);
            try {
                assertThat(flattened.getWidth()).isEqualTo(source.getWidth());
                assertThat(flattened.getHeight()).isEqualTo(source.getHeight());
                assertThat(flattened).isNotSameInstanceAs(source);
                try (var input = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "uvdoc-native-input.png"));
                     var output = new java.io.FileOutputStream(new java.io.File(context.getFilesDir(), "uvdoc-native-output.png"))) {
                    source.compress(Bitmap.CompressFormat.PNG, 100, input);
                    flattened.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                android.util.Log.i("OcrNativeTest", "UVDoc verified");
            } finally { flattened.recycle(); }
        } finally { source.recycle(); }
    }
}
