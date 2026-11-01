package com.sasakiy84.excerptmemo;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static com.google.common.truth.Truth.assertThat;
import android.Manifest;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import java.io.File;
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
            DocumentUiTest.waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(DocumentViewModel.class).closePage());
            DocumentUiTest.waitFor(scenario, activity -> activity.findViewById(R.id.shutter).isEnabled());
            DocumentUiTest.screenshot(context, "test-camera.png");
            onView(withId(R.id.shutter)).perform(click());
            DocumentUiTest.waitFor(scenario, activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                return model.page != null && !model.busy;
            });
            onView(withId(R.id.page_view)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertThat(Math.max(model.page.getWidth(), model.page.getHeight())).isGreaterThan(500);
                assertThat(Math.max(model.page.getWidth(), model.page.getHeight())).isAtMost(2800);
                activity.getOnBackPressedDispatcher().onBackPressed();
            });
            onView(withId(R.id.camera_panel)).check(matches(isDisplayed()));
        }
    }
}
