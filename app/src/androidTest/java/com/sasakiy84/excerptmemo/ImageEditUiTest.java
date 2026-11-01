package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
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
                ((MaterialToolbar)activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
                activity.getSupportFragmentManager().executePendingTransactions();
                var dialog = (ImageEditDialog)activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertThat(((MaterialButton)dialog.requireView().findViewById(R.id.edit_rectified)).isChecked()).isTrue();
                ((ImageEditView)dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
                ((Slider)dialog.requireView().findViewById(R.id.rotation_slider)).setValue(7.5f);
                dialog.requireView().findViewById(R.id.edit_original).performClick();
                ((Slider)dialog.requireView().findViewById(R.id.rotation_slider)).setValue(-3);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                var dialog = (ImageEditDialog)activity.getSupportFragmentManager().findFragmentByTag("image-edit");
                assertThat(((Slider)dialog.requireView().findViewById(R.id.rotation_slider)).getValue()).isEqualTo(-3);
                dialog.requireView().findViewById(R.id.edit_rectified).performClick();
                assertThat(((ImageEditView)dialog.requireView().findViewById(R.id.image_editor)).getCrop()).isEqualTo(new RectF(.1f, .2f, .8f, .9f));
                assertThat(((Slider)dialog.requireView().findViewById(R.id.rotation_slider)).getValue()).isEqualTo(7.5f);
                var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
                assertThat(model.page.getHeight()).isEqualTo(1200);
                assertThat(model.editorSource(false).getPixel(20, 20)).isEqualTo(Color.LTGRAY);
                assertThat(model.editorSource(true).getPixel(20, 20)).isEqualTo(Color.WHITE);
                dialog.dismiss();
            });
        }
    }
}
