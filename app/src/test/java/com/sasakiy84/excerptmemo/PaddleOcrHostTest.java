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
    @Test public void actualPaddleModelsReadJapaneseAndEnglish() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertThat(ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (done, total) -> {})))
                        .isEqualTo("資料の文字をコピーする\nAndroid 16");
            } finally { image.recycle(); }
        }
    }
    @Test public void actualPaddleModelReadsAnIntactVerticalJapaneseColumn() throws Exception {
        try (var input = getClass().getResourceAsStream("/vertical.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session);
                assertThat(ReadingOrder.text(paddle.read(image, new AtomicBoolean(), (done, total) -> {}))).isEqualTo("資料の文字を読む");
            } finally { image.recycle(); }
        }
    }
    @Test public void rereadingRectifiedPageKeepsPaddleRecognitionWithoutAnotherWarp() throws Exception {
        try (var input = getClass().getResourceAsStream("/horizontal.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap image = BitmapFactory.decodeStream(input);
            try {
                var result = engine.readPage(image, true, new AtomicBoolean(), (done, total) -> {});
                assertThat(result.image()).isSameInstanceAs(image);
                assertThat(result.rectified()).isFalse();
                assertThat(result.workflow()).isEqualTo("ndl-layout-paddle");
                assertThat(ReadingOrder.text(result.lines())).isEqualTo("資料の文字をコピーする\nAndroid 16");
            } finally { image.recycle(); }
        }
    }
}
