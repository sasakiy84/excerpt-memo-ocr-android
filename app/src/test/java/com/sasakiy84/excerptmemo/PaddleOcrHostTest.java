package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PaddleOcrHostTest {
    @BeforeClass public static void loadHostCpuOpenCv() { HostOpenCv.load(); }
    @Test public void confidenceExcludesBlankAndRepeatedCtcFrames() {
        var result = PaddleOcr.decodeScored(java.nio.FloatBuffer.wrap(new float[]{.99f, .005f, .005f, .1f, .8f, .1f, .05f, .9f, .05f, .8f, .1f, .1f, .2f, .2f, .6f}), 5, 3, new String[]{"", "甲", "乙"});
        assertEquals("甲乙", result.text());
        assertEquals(.7f, result.confidence(), .0001f);
    }
    @Test public void actualPaddleModelsReadJapaneseAndEnglish() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertEquals("資料の文字をコピーする\nAndroid 16", ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (stage, done, total) -> {})));
            } finally { image.recycle(); }
        }
    }
    @Test public void actualPaddleModelReadsAnIntactVerticalJapaneseColumn() throws Exception {
        try (var input = getClass().getResourceAsStream("/vertical.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertEquals("資料の文字を読む", ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (stage, done, total) -> {})));
            } finally { image.recycle(); }
        }
    }
    @Test public void rereadingRectifiedPageKeepsPaddleRecognitionWithoutAnotherWarp() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var progress = new java.util.ArrayList<String>();
                var result = engine.readPage(image, true, new AtomicBoolean(), (stage, done, total) -> progress.add(stage + ":" + done + "/" + total));
                assertEquals(java.util.List.of("NDL_DETECTION:0/1", "PADDLE_RECOGNITION:0/2", "PADDLE_RECOGNITION:1/2", "PADDLE_RECOGNITION:2/2"), progress);
                assertSame(image, result.image());
                assertFalse(result.rectified());
                assertEquals("ndl-layout-paddle", result.workflow());
                assertEquals("資料の文字をコピーする\nAndroid 16", ReadingOrder.text(result.lines()));
            } finally { image.recycle(); }
        }
    }
}
