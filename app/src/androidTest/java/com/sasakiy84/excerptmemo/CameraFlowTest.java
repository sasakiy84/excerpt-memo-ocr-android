package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.Manifest;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.View;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CameraFlowTest {
    @Rule public GrantPermissionRule cameraPermission = GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Test(timeout = 600000) public void cameraCaptureOpensPageAndBackReturnsToCamera() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new File(context.getFilesDir(), "page.json").delete();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            AtomicReference<MainActivity> reference = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MainActivity main) reference.set(main);
                }
            });
            MainActivity activity = reference.get();
            assertThat(activity).isNotNull();
            waitFor(instrumentation, () -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            instrumentation.runOnMainSync(() -> new ViewModelProvider(activity).get(DocumentViewModel.class).closePage());
            waitFor(instrumentation, () -> activity.findViewById(R.id.shutter).isEnabled());
            // Continuous preview frames can prevent Espresso's idle condition on TCG.
            instrumentation.runOnMainSync(() -> activity.findViewById(R.id.shutter).performClick());
            waitFor(instrumentation, () -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                return model.page != null;
            });
            DocumentUiTest.screenshot(context, "test-camera-page.png");
            instrumentation.runOnMainSync(() -> {
                assertThat(activity.findViewById(R.id.page_view).getVisibility()).isEqualTo(View.VISIBLE);
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertThat(Math.max(model.page.getWidth(), model.page.getHeight())).isGreaterThan(500);
                assertThat(Math.max(model.page.getWidth(), model.page.getHeight())).isAtMost(2800);
                if (model.busy) {
                    activity.getOnBackPressedDispatcher().onBackPressed();
                    assertThat(model.busy).isFalse();
                    assertThat(model.page).isNotNull();
                }
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertThat(activity.findViewById(R.id.camera_panel).getVisibility()).isEqualTo(View.VISIBLE);
                activity.finish();
            });
        }
    }

    private static void waitFor(Instrumentation instrumentation, java.util.function.BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 300000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(250);
        }
        throw new AssertionError("Camera state did not become ready");
    }
}
