package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import androidx.lifecycle.ViewModelProvider;
import android.widget.Toolbar;
import java.util.List;
import java.util.Set;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "w411dp-h731dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class MainActivityHostTest {
    @Before public void cameraRequiresExplicitPermission() {
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        app.getSharedPreferences("extraction", 0).edit().clear().commit();
        new File(app.getFilesDir(), "page.json").delete();
        new File(app.getFilesDir(), "page.png").delete();
    }

    @Test public void languageButtonIsAvailableOnCaptureAndResultScreens() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        DocumentViewModel model = new ViewModelProvider(controller.get()).get(DocumentViewModel.class);
        try {
            DocumentExtractionHostTest.awaitIdle(model); model.closePage().get(); model.setLanguage(OcrLanguage.JAPANESE);
            Toolbar toolbar = controller.get().findViewById(R.id.toolbar);
            toolbar.getMenu().performIdentifierAction(5, 0);
            assertEquals(OcrLanguage.ENGLISH, model.language);
            assertEquals("English", toolbar.getMenu().findItem(5).getTitle());
            model.page = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(10, 10, 90, 30, "A local note", OcrLine.Kind.TEXT, true));
            model.setInclusions(false, false, false);
            assertTrue(toolbar.getMenu().findItem(5).isVisible());
            assertEquals("English", toolbar.getMenu().findItem(5).getTitle());
            model.save();
            model.closePage().get(); model.setLanguage(OcrLanguage.JAPANESE);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void resultTextSizeSettingUsesDedicatedActivityAndPersistsInBothModes() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.page = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(10, 10, 90, 30, "資料の文字"));
            model.textMode = false; model.setInclusions(false, false, false);
            ((Toolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(6, 0);
            var intent = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(SettingsActivity.class.getName(), intent.getComponent().getClassName());
            var settings = Robolectric.buildActivity(SettingsActivity.class).setup();
            ((android.widget.SeekBar) settings.get().findViewById(R.id.page_text_size)).setProgress(6);
            settings.pause().stop().destroy();
            controller.pause().resume();
            float scaled = activity.getResources().getDisplayMetrics().scaledDensity;
            assertEquals(6, ((EditText) activity.findViewById(R.id.result_text)).getTextSize() / scaled, .01f);
            controller.recreate(); activity = controller.get();
            assertEquals(6, ((EditText) activity.findViewById(R.id.result_text)).getTextSize() / scaled, .01f);
            ((android.widget.RadioGroup) activity.findViewById(R.id.mode_toggle)).check(R.id.text_mode);
            assertEquals(6, ((EditText) activity.findViewById(R.id.result_text)).getTextSize() / scaled, .01f);
        } finally {
            controller.get().getSharedPreferences("display", 0).edit().clear().commit();
            DocumentExtractionHostTest.close(controller);
        }
    }

    @Test public void firstLaunchShowsCameraWithoutTryingToLoadDrawableZero() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            DocumentExtractionHostTest.awaitIdle(new ViewModelProvider(activity).get(DocumentViewModel.class));
            assertEquals(View.VISIBLE, activity.findViewById(R.id.camera_panel).getVisibility());
            assertNull(((Toolbar) activity.findViewById(R.id.toolbar)).getNavigationIcon());
            render(activity, "camera");
            renderLauncherIcon(activity);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void modelChooserRereadsTheOpenPageAndCanReturnToAutomatic() throws Exception {
        HostOpenCv.load();
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
        var settings = OcrSettings.preferences(model.getApplication(), OcrLanguage.JAPANESE);
        try (var fixture = getClass().getResourceAsStream("/vertical.png")) {
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.setLanguage(OcrLanguage.JAPANESE);
            var page = BitmapFactory.decodeStream(fixture);
            model.page = page;
            model.setInclusions(false, false, false);
            Button button = activity.findViewById(R.id.ocr_model_button);
            assertEquals("自動 ▾", button.getText().toString());

            button.performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            var choices = dialog.getListView();
            assertEquals(0, choices.getCheckedItemPosition());
            assertEquals("PP-OCRv6 tiny", choices.getItemAtPosition(2).toString());
            choices.performItemClick(null, 2, choices.getItemIdAtPosition(2));
            assertTrue(model.busy);
            assertFalse(button.isEnabled());
            DocumentExtractionHostTest.awaitIdle(model);
            assertSame(page, model.page);
            assertTrue(model.text.contains("資料"));
            assertEquals(OcrSettings.Model.TINY, model.ocrSelection().model());
            assertFalse(model.ocrSelection().automatic());
            assertEquals("tiny ▾", button.getText().toString());

            button.performClick();
            choices = ShadowAlertDialog.getLatestAlertDialog().getListView();
            assertEquals(2, choices.getCheckedItemPosition());
            choices.performItemClick(null, 0, choices.getItemIdAtPosition(0));
            assertTrue(model.busy);
            DocumentExtractionHostTest.awaitIdle(model);
            assertTrue(model.text.contains("資料"));
            assertTrue(model.ocrSelection().automatic());
            assertEquals("自動 ▾", button.getText().toString());
        } finally {
            settings.edit().clear().commit();
            DocumentExtractionHostTest.close(controller);
        }
    }

    @Test public void copyHonorsReverseTextSelection() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            DocumentExtractionHostTest.awaitIdle(new ViewModelProvider(activity).get(DocumentViewModel.class));
            DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            model.page = Bitmap.createBitmap(1200, 1700, Bitmap.Config.ARGB_8888);
            Canvas paper = new Canvas(model.page); paper.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ink.setColor(android.graphics.Color.BLACK); ink.setTextSize(48);
            String[] paragraphs = {"地域図書館の利用調査", "調査は四月から六月まで実施した。", "利用者は資料の閲覧を主な目的とした。", "来館時間は午後に集中する傾向がある。", "今後は資料の配置と案内を見直す。"};
            for (int i = 0; i < paragraphs.length; i++) paper.drawText(paragraphs[i], 80, 180 + i * 130, ink);
            paper.drawText("12", 570, 1600, ink);
            try (FileOutputStream file = new FileOutputStream("build/reports/portrait.png")) { model.page.compress(Bitmap.CompressFormat.PNG, 100, file); }
            model.allLines = List.of(new OcrLine(80, 132, 560, 190, "地域図書館の利用調査"));
            model.busy = false;
            model.setInclusions(false, false, false);
            render(activity, "page");
            render(activity, "page-narrow", 840, 1470);
            View modes = activity.findViewById(R.id.mode_toggle);
            View modelButton = activity.findViewById(R.id.ocr_model_button);
            View readButton = activity.findViewById(R.id.read_button);
            View copyButton = activity.findViewById(R.id.copy_button);
            assertEquals(modes.getTop(), modelButton.getTop());
            assertEquals(modes.getTop(), readButton.getTop());
            assertEquals(modes.getTop(), copyButton.getTop());
            assertTrue(modes.getRight() <= modelButton.getLeft() && modelButton.getRight() <= readButton.getLeft()
                    && readButton.getRight() <= copyButton.getLeft());
            EditText text = activity.findViewById(R.id.result_text);
            text.setSelection(5, 0);
            activity.findViewById(R.id.copy_button).performClick();
            assertEquals("地域図書館", activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
            ((android.widget.RadioGroup) activity.findViewById(R.id.mode_toggle))
                    .check(R.id.text_mode);
            assertEquals(View.GONE, activity.findViewById(R.id.page_view).getVisibility());
            render(activity, "text");
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    private static void renderLauncherIcon(MainActivity activity) throws Exception {
        var icon = (android.graphics.drawable.AdaptiveIconDrawable) androidx.core.content.ContextCompat.getDrawable(activity, R.mipmap.ic_launcher);
        File directory = new File("build/reports/ui");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create icon preview directory");
        for (int size : new int[]{512, 144, 48}) {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, size, size); icon.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(directory, "icon-" + size + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();
        }
        var mask = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_document).mutate(); mask.setTint(android.graphics.Color.WHITE);
        var themed = new android.graphics.drawable.AdaptiveIconDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK), mask);
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        themed.setBounds(0, 0, 512, 512); themed.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, "icon-monochrome.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private static void render(MainActivity activity, String name) throws Exception {
        render(activity, name, 1080, 1920);
        render(activity, name + "-compact", 945, 1680);
    }

    private static void render(MainActivity activity, String name, int width, int height) throws Exception {
        View root = activity.findViewById(R.id.root);
        for (int i = 0; i < 3; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        android.util.TypedValue background = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.colorBackground, background, true);
        canvas.drawColor(background.data);
        root.draw(canvas);
        File directory = new File("build/reports/ui");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create screenshot directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }
}
