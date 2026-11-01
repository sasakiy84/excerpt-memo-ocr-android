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
            renderLauncherIcon(activity);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void copyHonorsReverseTextSelection() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            DocumentExtractionHostTest.awaitIdle(new ViewModelProvider(activity).get(DocumentViewModel.class));
            DocumentViewModel model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            model.page = Bitmap.createBitmap(1200, 1700, Bitmap.Config.ARGB_8888);
            Canvas paper = new Canvas(model.page); paper.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ink.setColor(android.graphics.Color.BLACK); ink.setTextSize(48);
            String[] paragraphs = {"地域図書館の利用調査", "調査は四月から六月まで実施した。", "利用者は資料の閲覧を主な目的とした。", "来館時間は午後に集中する傾向がある。", "今後は資料の配置と案内を見直す。"};
            for (int i = 0; i < paragraphs.length; i++) paper.drawText(paragraphs[i], 80, 180 + i * 130, ink);
            paper.drawText("12", 570, 1600, ink);
            try (FileOutputStream file = new FileOutputStream("build/reports/portrait.png")) { model.page.compress(Bitmap.CompressFormat.PNG, 100, file); }
            model.allLines = List.of(new OcrLine(80, 132, 560, 190, "地域図書館の利用調査"));
            model.busy = false;
            model.setInclusions(false, false, false);
            render(activity, "page");
            EditText text = activity.findViewById(R.id.result_text);
            text.setSelection(5, 0);
            activity.findViewById(R.id.copy_button).performClick();
            assertThat(activity.getSystemService(ClipboardManager.class).getPrimaryClip().getItemAt(0).getText().toString())
                    .isEqualTo("地域図書館");
            ((com.google.android.material.button.MaterialButtonToggleGroup) activity.findViewById(R.id.mode_toggle))
                    .check(R.id.text_mode);
            assertThat(activity.findViewById(R.id.page_view).getVisibility()).isEqualTo(View.GONE);
            render(activity, "text");
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    private static void renderLauncherIcon(MainActivity activity) throws Exception {
        var icon = (android.graphics.drawable.AdaptiveIconDrawable) androidx.core.content.ContextCompat.getDrawable(activity, R.mipmap.ic_launcher);
        File directory = new File("build/reports/ui");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create icon preview directory");
        for (int size : new int[]{512, 144, 48}) {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, size, size); icon.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(directory, "icon-" + size + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            }
            bitmap.recycle();
        }
        var mask = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_document).mutate(); mask.setTint(android.graphics.Color.WHITE);
        var themed = new android.graphics.drawable.AdaptiveIconDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK), mask);
        Bitmap bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        themed.setBounds(0, 0, 512, 512); themed.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(directory, "icon-monochrome.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private static void render(MainActivity activity, String name) throws Exception {
        render(activity, name, 1080, 1920);
        render(activity, name + "-compact", 945, 1680);
    }

    private static void render(MainActivity activity, String name, int width, int height) throws Exception {
        View root = activity.findViewById(R.id.root);
        for (int i = 0; i < 3; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
        }
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
