package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
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
    @BeforeClass public static void loadHostCpuOpenCv() { nu.pattern.OpenCV.loadLocally(); }
    @Test public void confidenceExcludesBlankAndRepeatedCtcFrames() {
        var result = PaddleOcr.decodeScored(java.nio.FloatBuffer.wrap(new float[]{.99f, .005f, .005f, .1f, .8f, .1f, .05f, .9f, .05f, .8f, .1f, .1f, .2f, .2f, .6f}), 5, 3, new String[]{"", "甲", "乙"});
        assertThat(result.text()).isEqualTo("甲乙");
        assertThat(result.confidence()).isWithin(.0001f).of(.7f);
    }
    @Test public void actualPaddleModelsReadJapaneseAndEnglish() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertThat(ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (stage, done, total) -> {})))
                        .isEqualTo("資料の文字をコピーする\nAndroid 16");
            } finally { image.recycle(); }
        }
    }
    @Test public void actualPaddleModelReadsAnIntactVerticalJapaneseColumn() throws Exception {
        try (var input = getClass().getResourceAsStream("/vertical.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertThat(ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (stage, done, total) -> {}))).isEqualTo("資料の文字を読む");
            } finally { image.recycle(); }
        }
    }
    @Test public void rereadingRectifiedPageKeepsPaddleRecognitionWithoutAnotherWarp() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var progress = new java.util.ArrayList<String>();
                var result = engine.readPage(image, true, new AtomicBoolean(), (stage, done, total) -> progress.add(stage + ":" + done + "/" + total));
                assertThat(progress).containsExactly("NDL_DETECTION:0/1", "PADDLE_RECOGNITION:0/2", "PADDLE_RECOGNITION:1/2", "PADDLE_RECOGNITION:2/2").inOrder();
                assertThat(result.image()).isSameInstanceAs(image);
                assertThat(result.rectified()).isFalse();
                assertThat(result.workflow()).isEqualTo("ndl-layout-paddle");
                assertThat(ReadingOrder.text(result.lines())).isEqualTo("資料の文字をコピーする\nAndroid 16");
            } finally { image.recycle(); }
        }
    }
}
