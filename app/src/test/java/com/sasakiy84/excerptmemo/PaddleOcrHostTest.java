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
    @Test public void englishPageUsesPaddleAndPreservesWordsAndPunctuation() throws Exception {
        Bitmap image = Bitmap.createBitmap(1500, 500, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(image); canvas.drawColor(android.graphics.Color.WHITE);
        android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ink.setColor(android.graphics.Color.BLACK); ink.setTextSize(48);
        String[] truth = {"A local library keeps ideas within reach.", "Read the page, choose a passage, and copy it.", "Offline recognition protects private notes."};
        for (int i = 0; i < truth.length; i++) canvas.drawText(truth[i], 60, 100 + i * 110, ink);
        java.util.List<String> progress = new java.util.ArrayList<>();
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            var result = engine.readPage(image, false, OcrLanguage.ENGLISH, new AtomicBoolean(),
                    (stage, done, total) -> progress.add(stage.name()));
            assertEquals("paddle-english", result.workflow());
            assertEquals(String.join("\n", truth), ReadingOrder.text(result.lines()));
            assertTrue(result.lines().stream().allMatch(OcrLine::forceHorizontal));
            assertTrue(progress.contains("PADDLE_RECOGNITION"));
            assertFalse(progress.contains("UVDOC"));
        } finally { image.recycle(); }
    }

    @Test public void manualModelsOverrideAutomaticRouteAndCanReturnToAutomatic() throws Exception {
        try (var input = getClass().getResourceAsStream("/vertical.png"); OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            Bitmap page = BitmapFactory.decodeStream(input);
            try {
                for (var model : OcrSettings.Model.values()) {
                    var stages = new java.util.ArrayList<OcrEngine.Stage>();
                    var result = engine.readPage(page, false, OcrLanguage.JAPANESE,
                            new OcrSettings.Selection(false, model, model == OcrSettings.Model.MEDIUM, false),
                            new AtomicBoolean(), (stage, done, total) -> stages.add(stage));
                    assertTrue(ReadingOrder.text(result.lines()).contains("資料"));
                    assertEquals(model == OcrSettings.Model.NDL ? "ndl" : "manual-" + model.name().toLowerCase(java.util.Locale.ROOT), result.workflow());
                    assertFalse(stages.contains(OcrEngine.Stage.UVDOC));
                    if (model == OcrSettings.Model.TINY) assertTrue(stages.contains(OcrEngine.Stage.PADDLE_TINY_DETECTION));
                    if (model == OcrSettings.Model.MEDIUM) assertTrue(stages.contains(OcrEngine.Stage.PADDLE_MEDIUM_DETECTION));
                }
                assertEquals("ndl", engine.readPage(page, false, OcrLanguage.JAPANESE,
                        new AtomicBoolean(), (stage, done, total) -> {}).workflow());
            } finally { page.recycle(); }
        }
    }

    @Test public void japaneseDetectorsPreserveHorizontalAndVerticalText() throws Exception {
        try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
            var paddle = new PaddleOcr(RuntimeEnvironment.getApplication(), engine::session, PaddleOcr.Policy.SMALL);
            for (String fixture : new String[]{"horizontal", "vertical"}) {
                try (var input = getClass().getResourceAsStream("/" + fixture + ".png")) {
                    Bitmap original = BitmapFactory.decodeStream(input);
                    Bitmap contrast = PaddleOcr.improveContrast(original);
                    try {
                        assertNotSame(original, contrast);
                        assertEquals(original.getWidth(), contrast.getWidth());
                        assertEquals(original.getHeight(), contrast.getHeight());
                        String detector = fixture.equals("vertical") ? PaddleOcr.TINY_DETECTOR : PaddleOcr.MEDIUM_DETECTOR;
                        String expected = fixture.equals("vertical") ? "資料の文字を読む" : "資料の文字をコピーする\nAndroid 16";
                        assertEquals(expected, ReadingOrder.text(paddle.read(contrast, new AtomicBoolean(), (stage, done, total) -> {}, false, detector, PaddleOcr.Policy.SMALL)));
                        assertFalse(original.isRecycled());
                    } finally { contrast.recycle(); original.recycle(); }
                }
            }
        }
    }

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
                assertTrue(progress.contains("PADDLE_MEDIUM_DETECTION:0/1"));
                assertFalse(progress.stream().anyMatch(stage -> stage.startsWith("UVDOC:")));
                assertSame(image, result.image());
                assertFalse(result.rectified());
                assertEquals("paddle-medium-det", result.workflow());
                assertEquals("資料の文字をコピーする\nAndroid 16", ReadingOrder.text(result.lines()));
            } finally { image.recycle(); }
        }
    }
}
