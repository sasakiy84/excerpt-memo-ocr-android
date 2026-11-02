package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
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
            assertEquals(1, model.lines.size());
            assertEquals("本文", model.text);
            assertEquals("本文（p. 12）", model.forClipboard(0, 0));
            model.status = "文字が見つかりませんでした。";
            ((android.widget.Toolbar) controller.get().findViewById(R.id.toolbar)).getMenu().performIdentifierAction(4, 0);
            android.app.Dialog options = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            options.findViewById(R.id.include_page_numbers).performClick();
            options.findViewById(R.id.include_ruby).performClick();
            options.findViewById(R.id.include_tables).performClick();
            options.dismiss();
            assertEquals(4, model.lines.size());
            assertTrue(model.status.isEmpty());
            assertTrue(model.text.contains("ほんぶん"));
            assertTrue(model.text.contains("表の値"));
            assertTrue(model.text.contains("12"));
            model.setInclusions(false, false, false);
            assertEquals("本文", model.text);
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
            assertEquals("右の本文\n左の本文（pp. 12–13）", model.forClipboard(0, 0));
            assertEquals("右の本文（p. 12）", model.forClipboard(4, 0));
            assertEquals("左の本文（p. 13）", model.forClipboard(5, 9));
            assertEquals("右の本文\n左の本文", model.text);
            model.select(2, 7);
            assertEquals("本文\n左の（pp. 12–13）", model.forClipboard(0, 0));
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
            assertEquals("右の資文\n左の本文", model.text);
            assertEquals("左の本文（p. 13）", model.forClipboard(5, 9));
            editor.getText().insert(0, "引用:");
            assertEquals("左の本文（p. 13）", model.forClipboard(8, 12));
            assertTrue(model.forClipboard(0, 0).endsWith("（pp. 12–13）"));
        } finally { close(controller); }
    }

    @Test public void joinedParagraphsKeepPageReferencesThroughTextCorrections() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = document(controller.get());
            model.allLines = List.of(
                    new OcrLine(600, 100, 950, 120, "右の本文の前半"),
                    new OcrLine(600, 130, 950, 150, "右の本文の後半"),
                    new OcrLine(100, 100, 450, 120, "左の本文の前半"),
                    new OcrLine(100, 130, 450, 150, "左の本文の後半"),
                    new OcrLine(850, 940, 900, 980, "12", OcrLine.Kind.PAGE_NUMBER),
                    new OcrLine(100, 940, 150, 980, "13", OcrLine.Kind.PAGE_NUMBER));
            model.setInclusions(false, false, false);
            String right = "右の本文の前半右の本文の後半", left = "左の本文の前半左の本文の後半";
            assertEquals(right + "\n" + left, model.text);
            assertEquals(left + "（p. 13）", model.forClipboard(right.length() + 1, model.text.length()));
            android.widget.EditText editor = controller.get().findViewById(R.id.result_text);
            editor.getText().insert(0, "引用:");
            assertEquals(left + "（p. 13）", model.forClipboard(right.length() + 4, model.text.length()));
            assertTrue(model.forClipboard(0, 0).endsWith("（pp. 12–13）"));
            var geometry = new TextGeometry(model.lines);
            int line = model.lines.indexOf(model.allLines.get(1));
            model.select(geometry.starts[line] + 2, geometry.starts[line] + 6);
            assertEquals("本文の後", model.text);
            assertEquals("本文の後（p. 12）", model.forClipboard(0, 0));
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
            assertEquals("料の文", model.text);
            assertEquals(1, model.selectionStart);
            assertEquals(4, model.selectionEnd);
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
            var statuses = new java.util.ArrayList<String>();
            model.changes.observe(controller.get(), ignored -> statuses.add(model.status));
            model.open(Uri.fromFile(input));
            assertTrue(model.busy);
            awaitIdle(model);
            assertTrue(model.status.isEmpty());
            assertEquals("資料の文字をコピーする\nAndroid 16", model.text);
            assertTrue(statuses.contains("NDLOCR-Lite を実行中、1/2"));
            assertTrue(model.processingSeconds > 0);
            assertEquals(model.getApplication().getString(R.string.processing_seconds, model.processingSeconds),
                    ((android.widget.TextView) controller.get().findViewById(R.id.status)).getText().toString());
            assertEquals(0, model.selectionStart);
            assertEquals(model.text.length(), model.selectionEnd);
        } finally { close(controller); }
    }
}
