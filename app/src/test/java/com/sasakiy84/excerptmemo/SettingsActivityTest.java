package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.widget.Spinner;
import android.widget.Switch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public final class SettingsActivityTest {
    @Test public void languageTabsKeepIndependentModelsAndSelectedLanguageAfterRecreation() {
        var controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            var activity = controller.get();
            var tabs = (android.widget.RadioGroup) activity.findViewById(R.id.model_language_japanese).getParent();
            tabs.check(R.id.model_language_japanese);
            ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
            ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(OcrSettings.Model.MEDIUM.ordinal());
            ShadowLooper.idleMainLooper();
            ((Switch) activity.findViewById(R.id.flatten_model)).setChecked(true);
            var japanese = OcrSettings.load(activity, OcrLanguage.JAPANESE);
            tabs.check(R.id.model_language_english); ShadowLooper.idleMainLooper();
            assertTrue(((Switch) activity.findViewById(R.id.automatic_model)).isChecked());
            ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
            ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(OcrSettings.Model.SMALL.ordinal());
            ShadowLooper.idleMainLooper();
            controller.recreate(); activity = controller.get(); ShadowLooper.idleMainLooper();
            assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.model_language_english)).isChecked());
            assertEquals(OcrSettings.Model.SMALL, OcrSettings.load(activity, OcrLanguage.ENGLISH).model());
            assertEquals(japanese, OcrSettings.load(activity, OcrLanguage.JAPANESE));
            ((android.widget.RadioGroup) activity.findViewById(R.id.model_language_japanese).getParent()).check(R.id.model_language_japanese);
            ShadowLooper.idleMainLooper();
            assertEquals(OcrSettings.Model.MEDIUM.ordinal(), ((Spinner) activity.findViewById(R.id.detection_model)).getSelectedItemPosition());
            assertTrue(((Switch) activity.findViewById(R.id.flatten_model)).isChecked());
        } finally { clear(controller.get()); controller.pause().stop().destroy(); }
    }
    @Test public void existingSharedModelSettingsMigrateWithoutLinkingLanguages() {
        var context = org.robolectric.RuntimeEnvironment.getApplication();
        try {
            context.getSharedPreferences("ocr", 0).edit().putBoolean("automatic", false).putString("model", "MEDIUM").commit();
            var japanese = OcrSettings.load(context, OcrLanguage.JAPANESE);
            assertFalse(japanese.automatic()); assertEquals(OcrSettings.Model.MEDIUM, japanese.model());
            assertEquals(japanese, OcrSettings.load(context, OcrLanguage.ENGLISH));
            OcrSettings.preferences(context, OcrLanguage.ENGLISH).edit().putBoolean("automatic", true).commit();
            assertFalse(OcrSettings.load(context, OcrLanguage.JAPANESE).automatic());
            assertTrue(OcrSettings.load(context, OcrLanguage.ENGLISH).automatic());
        } finally { clear(context); }
    }
    private static void clear(android.content.Context context) {
        for (String name : new String[]{"ocr", "ocr_ja", "ocr_en"}) context.getSharedPreferences(name, 0).edit().clear().commit();
    }
    @Test public void automaticDefaultsOnAndManualSelectionsSurviveRecreationAndToggle() throws Exception {
        var controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            var activity = controller.get();
            assertTrue(OcrSettings.load(activity).automatic());
            assertFalse(activity.findViewById(R.id.detection_model).isEnabled());
            ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
            ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(OcrSettings.Model.MEDIUM.ordinal());
            ShadowLooper.idleMainLooper();
            ((Spinner) activity.findViewById(R.id.recognition_model)).setSelection(1);
            ((Switch) activity.findViewById(R.id.flatten_model)).setChecked(true);
            ShadowLooper.idleMainLooper();
            controller.recreate(); activity = controller.get();
            ShadowLooper.idleMainLooper();
            var selected = OcrSettings.load(activity);
            assertFalse(selected.automatic()); assertEquals(OcrSettings.Model.MEDIUM, selected.model());
            assertTrue(selected.mediumRecognition()); assertTrue(selected.flatten());
            var root = activity.getWindow().getDecorView();
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 1920);
            var bitmap = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(bitmap));
            var directory = new java.io.File("build/reports/ui"); directory.mkdirs();
            try (var out = new java.io.FileOutputStream(new java.io.File(directory, "settings.png"))) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally { bitmap.recycle(); }

            ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(true);
            assertFalse(activity.findViewById(R.id.detection_model).isEnabled());
            ((Switch) activity.findViewById(R.id.automatic_model)).setChecked(false);
            assertEquals(selected, OcrSettings.load(activity));
            ((Spinner) activity.findViewById(R.id.detection_model)).setSelection(0); ShadowLooper.idleMainLooper();
            assertFalse(activity.findViewById(R.id.recognition_model).isEnabled());
            assertFalse(activity.findViewById(R.id.flatten_model).isEnabled());
        } finally {
            clear(controller.get());
            controller.pause().stop().destroy();
        }
    }
}
