package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.widget.Toolbar;
import android.widget.RadioButton;
import android.widget.SeekBar;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ImageEditUiTest {
    @Test public void switchOriginalAndCorrectionKeepDraftsAcrossRecreation() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getFilesDir();
        new File(directory, "rectified.png").delete();
        Bitmap original = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888); original.eraseColor(Color.LTGRAY);
        Bitmap corrected = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888); corrected.eraseColor(Color.WHITE);
        try (var output = new FileOutputStream(new File(directory, "source.png"))) { original.compress(Bitmap.CompressFormat.PNG, 100, output); }
        try (var output = new FileOutputStream(new File(directory, "page.png"))) { corrected.compress(Bitmap.CompressFormat.PNG, 100, output); }
        original.recycle(); corrected.recycle();
        Files.write(new File(directory, "page.json").toPath(), "{\"pageRectified\":true,\"lines\":[]}".getBytes(StandardCharsets.UTF_8));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            DocumentUiTest.waitFor(scenario, activity -> !new ViewModelProvider(activity).get(DocumentViewModel.class).busy);
            scenario.onActivity(activity -> {
                ((Toolbar)activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
                activity.getSupportFragmentManager().executePendingTransactions();
                var dialog = (ImageEditDialog)activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertTrue(((RadioButton)dialog.requireView().findViewById(R.id.edit_rectified)).isChecked());
                ((ImageEditView)dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
                ((SeekBar)dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(525);
                dialog.requireView().findViewById(R.id.edit_original).performClick();
                ((SeekBar)dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(420);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                var dialog = (ImageEditDialog)activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertEquals(420, ((SeekBar)dialog.requireView().findViewById(R.id.rotation_slider)).getProgress());
                dialog.requireView().findViewById(R.id.edit_rectified).performClick();
                assertEquals(new RectF(.1f, .2f, .8f, .9f), ((ImageEditView)dialog.requireView().findViewById(R.id.image_editor)).getCrop());
                assertEquals(525, ((SeekBar)dialog.requireView().findViewById(R.id.rotation_slider)).getProgress());
                var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertEquals(1200, model.page.getHeight());
                assertEquals(Color.LTGRAY, model.editorSource(false).getPixel(20, 20));
                assertEquals(Color.WHITE, model.editorSource(true).getPixel(20, 20));
                dialog.dismiss();
            });
        }
    }
}
