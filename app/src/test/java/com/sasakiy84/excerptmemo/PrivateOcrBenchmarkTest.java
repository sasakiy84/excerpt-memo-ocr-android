package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Opt-in real-image evaluation. Private photographs and transcripts stay external. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PrivateOcrBenchmarkTest {
    @BeforeClass public static void loadHostCpuOpenCv() { nu.pattern.OpenCV.loadLocally(); }

    @Test public void compareOriginalAndAutomaticPipelinesOnExternalImages() throws Exception {
        String directory = System.getenv("OCR_EVALUATION_DIR");
        Assume.assumeTrue("Set OCR_EVALUATION_DIR to run private-image benchmarks", directory != null);
        File root = new File(directory);
        JSONObject truth = new JSONObject(new String(Files.readAllBytes(new File(root, "truth.private.json").toPath()), StandardCharsets.UTF_8));
        JSONArray output = new JSONArray();
        int baselineErrors = 0, automaticErrors = 0;
        for (String name : new String[]{"user-01.jpg", "user-02.jpg", "user-03.jpg", "user-05.jpg"}) {
            Bitmap source = BitmapFactory.decodeFile(new File(root, "input/" + name).toString());
            assertThat(source).isNotNull();
            double baselineTime = 0;
            try {
                for (boolean automatic : new boolean[]{false, true}) {
                    double[] times = new double[3];
                    OcrEngine.PageResult last = null;
                    try (OcrEngine engine = new OcrEngine(RuntimeEnvironment.getApplication())) {
                        for (int run = -1; run < times.length; run++) {
                            long start = System.nanoTime();
                            OcrEngine.PageResult result = automatic
                                    ? engine.readPage(source, false, new AtomicBoolean(), (stage, done, total) -> {})
                                    : new OcrEngine.PageResult(source, engine.read(source, new Rect(0, 0, source.getWidth(), source.getHeight()), new AtomicBoolean(), (stage, done, total) -> {}), false, "ndl");
                            double elapsed = (System.nanoTime() - start) / 1e9;
                            if (run >= 0) times[run] = elapsed;
                            if (last != null && last.image() != source) last.image().recycle();
                            last = result;
                        }
                        String text = ReadingOrder.text(last.lines());
                        if (automatic && (name.equals("user-01.jpg") || name.equals("user-05.jpg"))) {
                            assertThat(last.lines().stream().anyMatch(l -> l.kind() == OcrLine.Kind.PAGE_NUMBER && l.text().trim().equals("32"))).isTrue();
                            assertThat(last.lines().stream().anyMatch(l -> l.kind() == OcrLine.Kind.TEXT && l.text().trim().equals("32"))).isFalse();
                        }
                        JSONArray scores = new JSONArray();
                        int errors = 0, characters = 0;
                        JSONArray refs = truth.getJSONArray(name);
                        for (int i = 0; i < refs.length(); i++) {
                            int[] reference = normalized(refs.getString(i)), prediction = normalized(text);
                            int edits = excerptDistance(reference, prediction);
                            scores.put(new JSONArray().put(edits).put(reference.length));
                            errors += edits; characters += reference.length;
                        }
                        Arrays.sort(times);
                        if (!automatic) baselineTime = times[1];
                        if (!name.equals("user-05.jpg")) {
                            if (automatic) automaticErrors += errors; else baselineErrors += errors;
                        }
                        JSONObject row = new JSONObject().put("image", name).put("automatic", automatic)
                                .put("workflow", last.workflow()).put("median_seconds", times[1])
                                .put("times", new JSONArray(times)).put("errors", errors).put("characters", characters)
                                .put("excerpt_errors", scores).put("text", text).put("rectified", last.rectified());
                        output.put(row);
                        System.out.println("OCR_EVALUATION " + name + " " + last.workflow() + " " + times[1] + "s " + errors + "/" + characters);
                        if (automatic) assertThat(times[1]).isLessThan(baselineTime * 2);
                        if (automatic && last.rectified()) {
                            try (FileOutputStream stream = new FileOutputStream(new File(root, "results/java-" + name + ".png"))) { last.image().compress(Bitmap.CompressFormat.PNG, 100, stream); }
                        }
                    } finally { if (last != null && last.image() != source) last.image().recycle(); }
                }
            } finally { source.recycle(); }
        }
        Files.write(new File(root, "results/java-benchmark.json").toPath(), output.toString(2).getBytes(StandardCharsets.UTF_8));
        assertThat(automaticErrors).isLessThan(baselineErrors);
    }

    @Test public void editActualUvdocResultAndRetainTheOriginalPhotograph() throws Exception {
        String directory = System.getenv("OCR_EVALUATION_DIR");
        Assume.assumeTrue("Set OCR_EVALUATION_DIR to run private-image checks", directory != null);
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        new android.util.AtomicFile(new File(app.getFilesDir(), "page.json")).delete();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.open(android.net.Uri.fromFile(new File(directory, "input/user-03.jpg")));
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.hasRectifiedImage()).isTrue();
            assertThat(model.editRectified).isTrue();
            Bitmap base = model.editorSource(true);
            assertThat(base).isNotSameInstanceAs(model.editorSource(false));
            model.applyImageEdit(0, new android.graphics.RectF(.1f, .1f, .9f, .9f), true);
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(960);
            assertThat(model.editorSource(true)).isSameInstanceAs(base);
            assertThat(model.editorSource(false).getHeight()).isEqualTo(1200);
            assertThat(model.pageRectified).isTrue();
            assertThat(model.text).isNotEmpty();
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    private static int[] normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).codePoints().filter(c -> !Character.isWhitespace(c)).toArray();
    }

    private static int excerptDistance(int[] reference, int[] prediction) {
        int[] previous = new int[prediction.length + 1];
        for (int i = 1; i <= reference.length; i++) {
            int[] current = new int[prediction.length + 1]; current[0] = i;
            for (int j = 1; j <= prediction.length; j++) current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + (reference[i - 1] == prediction[j - 1] ? 0 : 1));
            previous = current;
        }
        return Arrays.stream(previous).min().orElse(reference.length);
    }
}
