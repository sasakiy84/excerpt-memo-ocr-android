package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import android.os.SystemClock;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DocumentUiTest {
    @Test(timeout = 600000) public void openImageRecognizeAndCopyWithBundledModels() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new File(context.getFilesDir(), "page.json").delete();
        File fixture = new File(context.getCacheDir(), "fixture.png");
        try (FileOutputStream stream = new FileOutputStream(fixture)) {
            OcrInstrumentedTest.fixture().compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(DocumentViewModel.class).open(Uri.fromFile(fixture)));
            waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertTrue(model.text.contains("資料"));
                assertTrue(model.text.contains("コピー"));
                assertTrue(model.text.toLowerCase().contains("android"));
            });
            screenshot(context, "test-page.png");
            scenario.onActivity(activity -> clickVisible(activity, R.id.text_mode));
            screenshot(context, "test-text.png");
            scenario.onActivity(activity -> clickVisible(activity, R.id.copy_button));
            scenario.onActivity(activity -> assertTrue(activity.getSystemService(ClipboardManager.class)
                    .getPrimaryClip().getItemAt(0).getText().toString().contains("コピー")));
        }
    }

    @Test public void selectCharactersSwitchToTextCopyAndRestoreAfterRotation() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Each scenario starts without a previous document, including after a failed run.
        new File(context.getFilesDir(), "page.json").delete();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                model.page = OcrInstrumentedTest.fixture();
                model.allLines = List.of(new OcrLine(80, 120, 750, 190, "資料の文字をコピーする"),
                        new OcrLine(80, 340, 440, 410, "Android 16"));
                model.busy = false;
                model.setInclusions(false, false, false);
            });
            waitFor(scenario, activity -> {
                android.view.View view = activity.findViewById(R.id.page_view);
                return view.getWidth() > 0 && view.getHeight() > 0 && !view.isLayoutRequested();
            });
            var heightBeforeDrag = new java.util.concurrent.atomic.AtomicInteger();
            var drawerFraction = new java.util.concurrent.atomic.AtomicReference<Float>();
            scenario.onActivity(activity -> {
                heightBeforeDrag.set(activity.findViewById(R.id.text_drawer).getHeight());
                touchDrawer(activity, android.view.MotionEvent.ACTION_DOWN, 400);
                touchDrawer(activity, android.view.MotionEvent.ACTION_MOVE, 300);
                touchDrawer(activity, android.view.MotionEvent.ACTION_UP, 300);
                drawerFraction.set(new ViewModelProvider(activity).get(DocumentViewModel.class).textPanelFraction);
            });
            waitFor(scenario, activity -> activity.findViewById(R.id.text_drawer).getHeight() == heightBeforeDrag.get() + 100);
            scenario.onActivity(activity -> {
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.page_view).getVisibility());
                touchPage(activity, android.view.MotionEvent.ACTION_DOWN, 250, 370);
                touchPage(activity, android.view.MotionEvent.ACTION_UP, 250, 370);
                assertFalse(new ViewModelProvider(activity).get(DocumentViewModel.class).text.isEmpty());
            });
            scenario.onActivity(activity -> touchPage(activity, android.view.MotionEvent.ACTION_DOWN, 100, 155));
            SystemClock.sleep(40);
            scenario.onActivity(activity -> touchPage(activity, android.view.MotionEvent.ACTION_MOVE, 720, 155));
            SystemClock.sleep(40);
            scenario.onActivity(activity -> {
                touchPage(activity, android.view.MotionEvent.ACTION_UP, 720, 155);
                assertEquals("資料の文字をコピーする", new ViewModelProvider(activity).get(DocumentViewModel.class).text);
                clickVisible(activity, R.id.text_mode);
                assertTrue(new ViewModelProvider(activity).get(DocumentViewModel.class).textMode);
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.page_view).getVisibility());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.result_text).getVisibility());
                clickVisible(activity, R.id.copy_button);
                assertEquals("資料の文字をコピーする", activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertEquals("資料の文字をコピーする", model.text);
                assertTrue(model.textMode);
                assertEquals(drawerFraction.get(), model.textPanelFraction, 0f);
                assertEquals(0, model.selectionStart);
                assertEquals("資料の文字をコピーする".length(), model.selectionEnd);
            });
            scenario.onActivity(activity -> {
                ((android.widget.Toolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
                activity.getSupportFragmentManager().executePendingTransactions();
                ImageEditDialog dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertTrue(dialog.requireDialog().isShowing());
                assertNotNull((android.view.View) dialog.requireView().findViewById(R.id.image_editor));
                ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(525);
                dialog.dismiss();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertEquals("資料の文字をコピーする", new ViewModelProvider(activity).get(DocumentViewModel.class).text);
            });
        }
    }

    private static void touchDrawer(MainActivity activity, int action, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, 20, y, 0);
        activity.findViewById(R.id.drawer_handle).dispatchTouchEvent(event);
        event.recycle();
    }

    private static void touchPage(MainActivity activity, int action, float x, float y) {
        android.view.View view = activity.findViewById(R.id.page_view);
        float scale = Math.min(view.getWidth() / 1200f, view.getHeight() / 700f);
        float localX = (view.getWidth() - 1200 * scale) / 2 + x * scale;
        float localY = (view.getHeight() - 700 * scale) / 2 + y * scale;
        android.view.MotionEvent event = android.view.MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, localX, localY, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void clickVisible(MainActivity activity, int id) {
        android.view.View view = activity.findViewById(id);
        Rect visible = new Rect();
        assertTrue(view.getGlobalVisibleRect(visible));
        assertTrue(visible.width() * (long) visible.height() >= (long) (view.getWidth() * (double) view.getHeight() * 0.9));
        assertTrue(view.isEnabled());
        view.performClick();
    }

    static void waitFor(ActivityScenario<MainActivity> scenario, java.util.function.Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 300000;
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = new java.util.concurrent.atomic.AtomicBoolean();
            scenario.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Timed out waiting for document/camera state");
    }

    static void screenshot(android.content.Context context, String name) throws Exception {
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), name))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        screenshot.recycle();
    }
}
