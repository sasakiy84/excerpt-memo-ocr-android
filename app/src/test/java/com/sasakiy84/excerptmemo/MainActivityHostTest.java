package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.Manifest;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
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

    @Test public void firstLaunchShowsCameraWithoutTryingToLoadDrawableZero() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            DocumentExtractionHostTest.awaitIdle(new ViewModelProvider(activity).get(DocumentViewModel.class));
            assertThat(activity.findViewById(R.id.camera_panel).getVisibility()).isEqualTo(View.VISIBLE);
            assertThat(((MaterialToolbar) activity.findViewById(R.id.toolbar)).getNavigationIcon()).isNull();
            render(activity, "camera");
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void copyHonorsReverseTextSelection() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            DocumentExtractionHostTest.awaitIdle(new ViewModelProvider(activity).get(DocumentViewModel.class));
            DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            model.page = BitmapFactory.decodeStream(getClass().getResourceAsStream("/horizontal.png"));
            model.allLines = List.of(new OcrLine(80, 100, 800, 180, "資料の文字をコピーする"));
            model.busy = false;
            model.setInclusions(false, false, false);
            render(activity, "page");
            EditText text = activity.findViewById(R.id.result_text);
            text.setSelection(5, 0);
            activity.findViewById(R.id.copy_button).performClick();
            assertThat(activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString())
                    .isEqualTo("資料の文字");
            ((com.google.android.material.button.MaterialButtonToggleGroup) activity.findViewById(R.id.mode_toggle))
                    .check(R.id.text_mode);
            assertThat(activity.findViewById(R.id.page_view).getVisibility()).isEqualTo(View.GONE);
            render(activity, "text");
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    private static void render(MainActivity activity, String name) throws Exception {
        render(activity, name, 1080, 1920);
        render(activity, name + "-compact", 945, 1680);
    }

    private static void render(MainActivity activity, String name, int width, int height) throws Exception {
        View root = activity.findViewById(R.id.root);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
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
