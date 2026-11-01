package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.Manifest;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class DocumentExtractionHostTest {
    @Before public void startWithoutSavedDocumentOrInclusions() {
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        app.getSharedPreferences("extraction", 0).edit().clear().commit();
        new File(app.getFilesDir(), "page.json").delete();
        new File(app.getFilesDir(), "page.png").delete();
    }

    private static DocumentViewModel document(MainActivity activity) throws Exception {
        var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
        awaitIdle(model);
        model.closePage().get();
        model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        return model;
    }

    static void awaitIdle(DocumentViewModel model) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (!model.busy) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Document work timed out");
    }

    static void close(org.robolectric.android.controller.ActivityController<MainActivity> controller) throws Exception {
        new ViewModelProvider(controller.get()).get(DocumentViewModel.class).closePage().get();
        controller.pause().stop().destroy();
    }

    @Test public void filtersDefaultOffAndTogglingImmediatelyChangesTheExcerpt() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = document(controller.get());
            model.allLines = List.of(new OcrLine(100, 100, 500, 150, "本文"),
                    new OcrLine(100, 180, 500, 200, "ほんぶん", OcrLine.Kind.RUBY),
                    new OcrLine(100, 300, 500, 350, "表の値", OcrLine.Kind.TABLE),
                    new OcrLine(450, 940, 500, 980, "12", OcrLine.Kind.PAGE_NUMBER));
            model.setInclusions(false, false, false);
            assertThat(model.lines).hasSize(1);
            assertThat(model.text).isEqualTo("本文");
            assertThat(model.forClipboard(0, 0)).isEqualTo("本文（p. 12）");
            model.status = "文字が見つかりませんでした。";
            controller.get().findViewById(R.id.include_page_numbers).performClick();
            controller.get().findViewById(R.id.include_ruby).performClick();
            controller.get().findViewById(R.id.include_tables).performClick();
            assertThat(model.lines).hasSize(4);
            assertThat(model.status).isEmpty();
            assertThat(model.text).contains("ほんぶん");
            assertThat(model.text).contains("表の値");
            assertThat(model.text).contains("12");
            model.setInclusions(false, false, false);
            assertThat(model.text).isEqualTo("本文");
        } finally { close(controller); }
    }

    @Test public void copyUsesOnlyThePagesInTheSelectedCharactersAndDoesNotMutateText() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = document(controller.get());
            model.allLines = List.of(new OcrLine(600, 100, 950, 150, "右の本文"),
                    new OcrLine(100, 100, 450, 150, "左の本文"),
                    new OcrLine(850, 940, 900, 980, "12", OcrLine.Kind.PAGE_NUMBER),
                    new OcrLine(100, 940, 150, 980, "13", OcrLine.Kind.PAGE_NUMBER));
            model.setInclusions(false, false, false);
            assertThat(model.forClipboard(0, 0)).isEqualTo("右の本文\n左の本文（pp. 12–13）");
            assertThat(model.forClipboard(4, 0)).isEqualTo("右の本文（p. 12）");
            assertThat(model.forClipboard(5, 9)).isEqualTo("左の本文（p. 13）");
            assertThat(model.text).isEqualTo("右の本文\n左の本文");
            model.select(2, 7);
            assertThat(model.forClipboard(0, 0)).isEqualTo("本文\n左の（pp. 12–13）");
        } finally { close(controller); }
    }

    @Test public void correctingAndInsertingTextRetainsThePageOfANarrowerSelection() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = document(controller.get());
            model.allLines = List.of(new OcrLine(600, 100, 950, 150, "右の本文"),
                    new OcrLine(100, 100, 450, 150, "左の本文"),
                    new OcrLine(850, 940, 900, 980, "12", OcrLine.Kind.PAGE_NUMBER),
                    new OcrLine(100, 940, 150, 980, "13", OcrLine.Kind.PAGE_NUMBER));
            model.setInclusions(false, false, false);
            android.widget.EditText editor = controller.get().findViewById(R.id.result_text);
            editor.getText().replace(2, 3, "資");
            assertThat(model.text).isEqualTo("右の資文\n左の本文");
            assertThat(model.forClipboard(5, 9)).isEqualTo("左の本文（p. 13）");
            editor.getText().insert(0, "引用:");
            assertThat(model.forClipboard(8, 12)).isEqualTo("左の本文（p. 13）");
            assertThat(model.forClipboard(0, 0)).endsWith("（pp. 12–13）");
        } finally { close(controller); }
    }

    @Test public void filteringPreservesPartialCharacterSelection() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = document(controller.get());
            model.allLines = List.of(new OcrLine(100, 100, 500, 150, "資料の文字"),
                    new OcrLine(100, 180, 500, 200, "しりょう", OcrLine.Kind.RUBY));
            model.setInclusions(false, false, false);
            model.select(1, 4);
            model.setInclusions(false, true, false);
            assertThat(model.text).isEqualTo("料の文");
            assertThat(model.selectionStart).isEqualTo(1);
            assertThat(model.selectionEnd).isEqualTo(4);
        } finally { close(controller); }
    }

    @Test public void openingImageAutomaticallyRunsActualBundledOcr() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = new ViewModelProvider(controller.get()).get(DocumentViewModel.class);
            awaitIdle(model);
            File input = new File(RuntimeEnvironment.getApplication().getCacheDir(), "auto-ocr.png");
            try (var source = getClass().getResourceAsStream("/horizontal.png"); var output = new FileOutputStream(input)) {
                source.transferTo(output);
            }
            model.open(Uri.fromFile(input));
            assertThat(model.busy).isTrue();
            awaitIdle(model);
            assertThat(model.status).isEmpty();
            assertThat(model.text).isEqualTo("資料の文字をコピーする\nAndroid 16");
            assertThat(model.selectionStart).isEqualTo(0);
            assertThat(model.selectionEnd).isEqualTo(model.text.length());
        } finally { close(controller); }
    }
}
