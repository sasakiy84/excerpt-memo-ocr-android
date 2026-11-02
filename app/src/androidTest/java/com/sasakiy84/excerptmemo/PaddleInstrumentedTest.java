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

    @Test(timeout = 600000) public void bundledMediumReadsJapaneseOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap full = OcrInstrumentedTest.fixture();
        Bitmap crop = Bitmap.createBitmap(full, 60, 100, 760, 100);
        try (OcrEngine engine = new OcrEngine(context)) {
            var recognizer = new PaddleOcr(context, engine::session, PaddleOcr.Policy.MEDIUM);
            assertThat(recognizer.recognize(crop)).isEqualTo("資料の文字をコピーする");
        } finally { crop.recycle(); full.recycle(); }
    }

    @Test(timeout = 1200000) public void bundledMediumExecutesOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (OcrEngine engine = new OcrEngine(context);
             var input = ai.onnxruntime.OnnxTensor.createTensor(ai.onnxruntime.OrtEnvironment.getEnvironment(), java.nio.FloatBuffer.wrap(new float[3 * 48 * 32]), new long[]{1, 3, 48, 32})) {
            var session = engine.session(PaddleOcr.MEDIUM);
            try (var output = session.run(java.util.Map.of(session.getInputNames().iterator().next(), input))) {
                var tensor = (ai.onnxruntime.OnnxTensor)output.get(0);
                assertThat(tensor.getInfo().getShape()[2]).isEqualTo(18710);
                assertThat(tensor.getFloatBuffer().get(0)).isFinite();
            }
        }
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
