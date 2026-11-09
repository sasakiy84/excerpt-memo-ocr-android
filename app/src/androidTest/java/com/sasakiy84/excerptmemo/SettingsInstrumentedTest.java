package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.SeekBar;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SettingsInstrumentedTest {
    @Test public void manualModelAndResultTextSizePersistAcrossRecreation() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var prefs = context.getSharedPreferences("ocr", 0);
        prefs.edit().clear().commit();
        context.getSharedPreferences("ocr_ja", 0).edit().clear().commit();
        context.getSharedPreferences("ocr_en", 0).edit().clear().commit();
        SettingsActivity activity = (SettingsActivity) instrumentation.startActivitySync(
                new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        SettingsActivity[] recreated = new SettingsActivity[1];
        try {
            awaitResumed(activity);
            instrumentation.runOnMainSync(() -> {
                ((android.widget.RadioGroup) activity.findViewById(R.id.model_language_japanese).getParent()).check(R.id.model_language_japanese);
                assertTrue(((Switch) activity.findViewById(R.id.automatic_model)).isChecked());
                ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
                ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(OcrSettings.Model.MEDIUM.ordinal());
                ((SeekBar) activity.findViewById(R.id.page_text_size)).setProgress(6);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertTrue(activity.findViewById(R.id.recognition_model).isEnabled());
                ((Spinner) activity.findViewById(R.id.recognition_model)).setSelection(1);
                ((Switch) activity.findViewById(R.id.flatten_model)).setChecked(true);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                ((android.widget.RadioGroup) activity.findViewById(R.id.model_language_english).getParent()).check(R.id.model_language_english);
                assertTrue(((Switch) activity.findViewById(R.id.automatic_model)).isChecked());
                ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
                ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(OcrSettings.Model.SMALL.ordinal());
            });
            instrumentation.waitForIdleSync();
            var monitor = instrumentation.addMonitor(SettingsActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(activity::recreate);
                recreated[0] = (SettingsActivity) monitor.waitForActivityWithTimeout(60000);
                assertNotNull("Settings Activity must be recreated", recreated[0]);
                assertNotSame(activity, recreated[0]);
                awaitResumed(recreated[0]);
                instrumentation.runOnMainSync(() -> {
                    var current = recreated[0];
                    assertTrue(((android.widget.RadioButton) current.findViewById(R.id.model_language_english)).isChecked());
                    var english = OcrSettings.load(current, OcrLanguage.ENGLISH);
                    assertFalse(english.automatic()); assertEquals(OcrSettings.Model.SMALL, english.model());
                    assertFalse(english.mediumRecognition()); assertFalse(english.flatten());
                    var selected = OcrSettings.load(current, OcrLanguage.JAPANESE);
                    assertFalse(selected.automatic()); assertEquals(OcrSettings.Model.MEDIUM, selected.model());
                    assertTrue(selected.mediumRecognition()); assertTrue(selected.flatten());
                    assertEquals(6, ((SeekBar) current.findViewById(R.id.page_text_size)).getProgress());
                    ((Switch) current.findViewById(R.id.automatic_model)).setChecked(true);
                    assertFalse(current.findViewById(R.id.detection_model).isEnabled());
                });
            } finally { instrumentation.removeMonitor(monitor); }
        } finally {
            instrumentation.runOnMainSync(() -> { if (recreated[0] != null) recreated[0].finish(); activity.finish(); });
            prefs.edit().clear().commit();
            context.getSharedPreferences("ocr_ja", 0).edit().clear().commit();
            context.getSharedPreferences("ocr_en", 0).edit().clear().commit();
            context.getSharedPreferences("display", 0).edit().clear().commit();
        }
    }
    private static void awaitResumed(SettingsActivity activity) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        long deadline = SystemClock.uptimeMillis() + 60000;
        boolean[] resumed = {false};
        do {
            instrumentation.runOnMainSync(() -> resumed[0] = activity.getLifecycle().getCurrentState() == Lifecycle.State.RESUMED);
            if (resumed[0]) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Settings Activity must resume");
    }
}
