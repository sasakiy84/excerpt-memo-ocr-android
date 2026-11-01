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
import androidx.test.espresso.action.GeneralSwipeAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Swipe;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Tap;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
            onView(withId(R.id.read_button)).perform(click());
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

    @Test public void selectLinesSwitchToTextCopyAndRestoreAfterRotation() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Each scenario starts without a previous document, including after a failed run.
        new File(context.getFilesDir(), "page.json").delete();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                model.page = OcrInstrumentedTest.fixture();
                model.lines = List.of(new OcrLine(80, 120, 750, 190, "資料の文字をコピーする"),
                        new OcrLine(80, 340, 440, 410, "Android 16"));
                model.busy = false;
                model.select(new Rect(0, 0, 1200, 700), Set.of(0, 1));
            });
            onView(withId(R.id.page_view)).check(matches(isDisplayed()));
            onView(withId(R.id.page_view)).perform(new GeneralClickAction(Tap.SINGLE,
                    view -> pageCoordinate(view, 250, 370), Press.FINGER));
            scenario.onActivity(activity -> assertThat(new ViewModelProvider(activity).get(DocumentViewModel.class).text)
                    .isEqualTo("Android 16"));
            onView(withId(R.id.page_view)).perform(new GeneralSwipeAction(Swipe.SLOW,
                    view -> pageCoordinate(view, 20, 90), view -> pageCoordinate(view, 1000, 220), Press.FINGER));
            onView(withId(R.id.text_mode)).perform(click());
            onView(withId(R.id.result_text)).check(matches(isDisplayed()));
            onView(withId(R.id.copy_button)).check(matches(isEnabled())).perform(click());
            scenario.onActivity(activity -> assertThat(activity.getSystemService(ClipboardManager.class)
                    .getPrimaryClip().getItemAt(0).getText().toString()).isEqualTo("資料の文字をコピーする"));
            scenario.recreate();
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertThat(model.text).isEqualTo("資料の文字をコピーする");
                assertThat(model.textMode).isTrue();
                assertThat(model.selected).containsExactly(0);
            });
        }
    }

    private static float[] pageCoordinate(android.view.View view, float x, float y) {
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        float scale = Math.min(view.getWidth() / 1200f, view.getHeight() / 700f);
        return new float[]{origin[0] + (view.getWidth() - 1200 * scale) / 2 + x * scale,
                origin[1] + (view.getHeight() - 700 * scale) / 2 + y * scale};
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
