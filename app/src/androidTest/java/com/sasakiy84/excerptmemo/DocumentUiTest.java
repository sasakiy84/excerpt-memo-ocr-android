package com.sasakiy84.excerptmemo;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.google.common.truth.Truth.assertThat;
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
                assertThat(model.text).contains("資料");
                assertThat(model.text).contains("コピー");
                assertThat(model.text.toLowerCase()).contains("android");
            });
            screenshot(context, "test-page.png");
            onView(withId(R.id.text_mode)).perform(click());
            screenshot(context, "test-text.png");
            onView(withId(R.id.copy_button)).perform(click());
            scenario.onActivity(activity -> assertThat(activity.getSystemService(ClipboardManager.class)
                    .getPrimaryClip().getItemAt(0).getText().toString()).contains("コピー"));
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
                assertThat(activity.findViewById(R.id.page_view).getVisibility()).isEqualTo(android.view.View.VISIBLE);
                touchPage(activity, android.view.MotionEvent.ACTION_DOWN, 250, 370);
                touchPage(activity, android.view.MotionEvent.ACTION_UP, 250, 370);
                assertThat(new ViewModelProvider(activity).get(DocumentViewModel.class).text).isNotEmpty();
            });
            scenario.onActivity(activity -> touchPage(activity, android.view.MotionEvent.ACTION_DOWN, 100, 155));
            SystemClock.sleep(40);
            scenario.onActivity(activity -> touchPage(activity, android.view.MotionEvent.ACTION_MOVE, 720, 155));
            SystemClock.sleep(40);
            scenario.onActivity(activity -> {
                touchPage(activity, android.view.MotionEvent.ACTION_UP, 720, 155);
                assertThat(new ViewModelProvider(activity).get(DocumentViewModel.class).text).isEqualTo("資料の文字をコピーする");
                clickVisible(activity, R.id.text_mode);
                assertThat(new ViewModelProvider(activity).get(DocumentViewModel.class).textMode).isTrue();
                assertThat(activity.findViewById(R.id.page_view).getVisibility()).isEqualTo(android.view.View.GONE);
                assertThat(activity.findViewById(R.id.result_text).getVisibility()).isEqualTo(android.view.View.VISIBLE);
                clickVisible(activity, R.id.copy_button);
                assertThat(activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString())
                        .isEqualTo("資料の文字をコピーする");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertThat(model.text).isEqualTo("資料の文字をコピーする");
                assertThat(model.textMode).isTrue();
                assertThat(model.textPanelFraction).isEqualTo(drawerFraction.get());
                assertThat(model.selectionStart).isEqualTo(0);
                assertThat(model.selectionEnd).isEqualTo("資料の文字をコピーする".length());
            });
            scenario.onActivity(activity -> {
                ((com.google.android.material.appbar.MaterialToolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
                activity.getSupportFragmentManager().executePendingTransactions();
                ImageEditDialog dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertThat(dialog.requireDialog().isShowing()).isTrue();
                assertThat((android.view.View) dialog.requireView().findViewById(R.id.image_editor)).isNotNull();
                ((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).setValue(7.5f);
                dialog.dismiss();
                activity.getSupportFragmentManager().executePendingTransactions();
                assertThat(new ViewModelProvider(activity).get(DocumentViewModel.class).text).isEqualTo("資料の文字をコピーする");
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
        assertThat(view.getGlobalVisibleRect(visible)).isTrue();
        assertThat(visible.width() * (long) visible.height()).isAtLeast((long) (view.getWidth() * (double) view.getHeight() * 0.9));
        assertThat(view.isEnabled()).isTrue();
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
        assertThat(screenshot).isNotNull();
        try (FileOutputStream stream = new FileOutputStream(new File(context.getFilesDir(), name))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        screenshot.recycle();
    }
}
