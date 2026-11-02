package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
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

    @Test(timeout = 600000) public void japaneseDetectorsExecuteOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (OcrEngine engine = new OcrEngine(context);
             var input = ai.onnxruntime.OnnxTensor.createTensor(ai.onnxruntime.OrtEnvironment.getEnvironment(), java.nio.FloatBuffer.wrap(new float[3 * 32 * 32]), new long[]{1, 3, 32, 32})) {
            for (String model : new String[]{PaddleOcr.TINY_DETECTOR, PaddleOcr.MEDIUM_DETECTOR}) {
                var session = engine.session(model);
                try (var output = session.run(java.util.Map.of(session.getInputNames().iterator().next(), input))) {
                    var tensor = (ai.onnxruntime.OnnxTensor)output.get(0);
                    assertArrayEquals(new long[]{1, 1, 32, 32}, tensor.getInfo().getShape());
                    var values = tensor.getFloatBuffer();
                    while (values.hasRemaining()) { float value = values.get(); assertTrue(Float.isFinite(value) && value >= 0 && value <= 1); }
                }
            }
            Bitmap image = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
            Bitmap contrast = PaddleOcr.improveContrast(image);
            try { assertEquals(64, contrast.getWidth()); assertEquals(96, contrast.getHeight()); }
            finally { contrast.recycle(); image.recycle(); }
        }
    }

    @Test(timeout = 600000) public void bundledSmallReadsAnEnglishSentenceOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap image = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888);
        var canvas = new android.graphics.Canvas(image); canvas.drawColor(android.graphics.Color.WHITE);
        var ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ink.setColor(android.graphics.Color.BLACK); ink.setTextSize(36);
        canvas.drawText("Read, select and copy.", 40, 100, ink);
        try (OcrEngine engine = new OcrEngine(context)) {
            // Exercise the new horizontal preprocessing on Android. The full language
            // route (including the unchanged 1024px NDL layout) has separate host coverage.
            var paddle = new PaddleOcr(context, engine::session, PaddleOcr.Policy.SMALL);
            var lines = paddle.read(image, new AtomicBoolean(), (stage, done, total) ->
                    android.util.Log.i("OcrNativeTest", "English " + stage + " " + done + "/" + total), true);
            assertEquals("Read, select and copy.", ReadingOrder.text(lines));
            assertTrue(lines.stream().allMatch(OcrLine::forceHorizontal));
        } finally { image.recycle(); }
    }

    @Test(timeout = 600000) public void bundledMediumReadsJapaneseOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap full = OcrInstrumentedTest.fixture();
        Bitmap crop = Bitmap.createBitmap(full, 60, 100, 760, 100);
        try (OcrEngine engine = new OcrEngine(context)) {
            var recognizer = new PaddleOcr(context, engine::session, PaddleOcr.Policy.MEDIUM);
            assertEquals("資料の文字をコピーする", recognizer.recognize(crop));
        } finally { crop.recycle(); full.recycle(); }
    }

    @Test(timeout = 1200000) public void bundledMediumExecutesOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (OcrEngine engine = new OcrEngine(context);
             var input = ai.onnxruntime.OnnxTensor.createTensor(ai.onnxruntime.OrtEnvironment.getEnvironment(), java.nio.FloatBuffer.wrap(new float[3 * 48 * 32]), new long[]{1, 3, 48, 32})) {
            var session = engine.session(PaddleOcr.MEDIUM);
            try (var output = session.run(java.util.Map.of(session.getInputNames().iterator().next(), input))) {
                var tensor = (ai.onnxruntime.OnnxTensor)output.get(0);
                assertEquals(18710, tensor.getInfo().getShape()[2]);
                assertTrue(Float.isFinite(tensor.getFloatBuffer().get(0)));
            }
        }
    }

    @Test(timeout = 600000) public void bundledPaddleReadsJapaneseAndEnglishOnAndroidCpu() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap source = fixture();
        try (OcrEngine engine = new OcrEngine(context)) {
            var paddle = new PaddleOcr(context, engine::session);
            String text = ReadingOrder.text(paddle.read(source, new AtomicBoolean(), (stage, done, total) -> {}));
            assertTrue(text.contains("資料の文字をコピーする"));
            assertTrue(text.toLowerCase().contains("android"));
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
                assertEquals(source.getWidth(), flattened.getWidth());
                assertEquals(source.getHeight(), flattened.getHeight());
                assertNotSame(source, flattened);
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
