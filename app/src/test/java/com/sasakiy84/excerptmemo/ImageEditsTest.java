package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.*;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ImageEditsTest {
    @Test public void quarterTurnAndCropUseThePreviewCoordinateSpace() {
        Bitmap input = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(); paint.setColor(Color.RED); canvas.drawRect(0, 0, 100, 150, paint);
        Bitmap output = ImageEdits.apply(input, 90, new RectF(.5f, 0, 1, .5f));
        assertThat(output.getWidth()).isEqualTo(150);
        assertThat(output.getHeight()).isEqualTo(100);
        assertThat(output.getPixel(75, 50)).isEqualTo(Color.RED);
    }
    @Test public void fineRotationDoesNotStretchTheSourceAndFillsCornersWhite() {
        Bitmap input = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888); input.eraseColor(Color.BLUE);
        Bitmap output = ImageEdits.apply(input, 12, new RectF(0, 0, 1, 1));
        assertThat(output.getWidth()).isGreaterThan(200);
        assertThat(output.getHeight()).isGreaterThan(300);
        assertThat(output.getPixel(0, 0)).isEqualTo(Color.WHITE);
        assertThat(output.getPixel(output.getWidth()/2, output.getHeight()/2)).isEqualTo(Color.BLUE);
    }
    @Test public void draggingCropCornerAndInteriorChangesAndMovesTheSelection() {
        var context = RuntimeEnvironment.getApplication();
        ImageEditView editor = new ImageEditView(context); new FrameLayout(context).addView(editor);
        editor.setImage(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888));
        editor.layout(0, 0, 600, 1000);
        editor.draw(new Canvas(Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)));
        touch(editor, 0, 20, 80); touch(editor, 2, 76, 164); touch(editor, 1, 76, 164);
        assertThat(editor.getCrop().left).isWithin(.001f).of(.1f);
        assertThat(editor.getCrop().top).isWithin(.001f).of(.1f);
        touch(editor, 0, 300, 500); touch(editor, 2, 244, 416); touch(editor, 1, 244, 416);
        assertThat(editor.getCrop().left).isWithin(.001f).of(0);
        assertThat(editor.getCrop().right).isWithin(.001f).of(.9f);
    }
    @Test public void appliedCropIsReadAutomaticallyWithRealModels() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        new java.io.File(app.getFilesDir(), "page.json").delete();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.page = BitmapFactory.decodeStream(getClass().getResourceAsStream("/horizontal.png"));
            model.pageRectified = true;
            model.applyImageEdit(0, new RectF(0, .1f, 1, .36f));
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(182);
            assertThat(model.pageRectified).isFalse();
            assertThat(model.text).contains("資料");
            assertThat(model.text.toLowerCase()).doesNotContain("android");
            model.applyImageEdit(0, new RectF(0, 0, 1, 1));
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(700);
            assertThat(model.text.toLowerCase()).contains("android");
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    @Test public void editorRetainsCropAndFineAngleAfterActivityRecreationAndCancelDoesNotEditImage() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        new java.io.File(app.getFilesDir(), "page.json").delete();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.page = Bitmap.createBitmap(1200, 1700, Bitmap.Config.ARGB_8888);
            ((com.google.android.material.appbar.MaterialToolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
            activity.getSupportFragmentManager().executePendingTransactions();
            var dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
            dialog.requireView().findViewById(R.id.rotate_image_quarter).performClick();
            ((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).setValue(7.5f);
            controller.recreate(); activity = controller.get();
            dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            RectF restoredCrop = ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop();
            assertThat(restoredCrop.left).isWithin(.001f).of(.1f);
            assertThat(restoredCrop.top).isWithin(.001f).of(.1f);
            assertThat(restoredCrop.right).isWithin(.001f).of(.8f);
            assertThat(restoredCrop.bottom).isWithin(.001f).of(.8f);
            assertThat(((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).getValue()).isEqualTo(7.5f);
            dialog.dismiss(); activity.getSupportFragmentManager().executePendingTransactions();
            assertThat(new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class).page.getWidth()).isEqualTo(1200);
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    @Test public void correctedCropSurvivesProcessRestoreAndCanReturnToEitherBase() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        app = savedCorrection();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        var model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
        DocumentExtractionHostTest.awaitIdle(model);
        assertThat(model.editRectified).isTrue();
        model.applyImageEdit(0, new RectF(0, .1f, 1, .36f), true);
        DocumentExtractionHostTest.awaitIdle(model);
        assertThat(model.page.getHeight()).isEqualTo(182);
        assertThat(model.pageRectified).isTrue();
        assertThat(model.text).contains("資料");
        assertThat(model.text.toLowerCase()).doesNotContain("android");
        long deadline = System.nanoTime() + 5_000_000_000L;
        var state = new java.io.File(app.getFilesDir(), "page.json");
        while (!new String(java.nio.file.Files.readAllBytes(state.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("rectifiedEditCrop") && System.nanoTime() < deadline) Thread.sleep(10);
        controller.pause().stop().destroy();
        controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(182);
            assertThat(model.editorSource(true).getHeight()).isEqualTo(700);
            assertThat(model.editorSource(false).getHeight()).isEqualTo(900);
            assertThat(model.rectifiedEditCrop.top).isWithin(.001f).of(.1f);
            model.applyImageEdit(0, new RectF(0, 0, 1, 1), true);
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(700);
            assertThat(model.pageRectified).isTrue();
            assertThat(model.text.toLowerCase()).contains("android");
            model.applyImageEdit(0, new RectF(0, 0, 1, 1), false);
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(900);
            assertThat(model.pageRectified).isFalse();
            assertThat(model.hasRectifiedImage()).isTrue();
            assertThat(model.editorSource(false).getPixel(20, 20)).isEqualTo(Color.BLUE);
            model.closePage().get();
            assertThat(new java.io.File(app.getFilesDir(), "rectified.png").exists()).isFalse();
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    @Test public void switchingEditBasePreservesBothDraftsAcrossActivityRecreation() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        app = savedCorrection();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var restoredModel = new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(restoredModel);
            assertThat(restoredModel.page).isNotNull();
            ((com.google.android.material.appbar.MaterialToolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
            activity.getSupportFragmentManager().executePendingTransactions();
            var dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            assertThat(((com.google.android.material.button.MaterialButton)dialog.requireView().findViewById(R.id.edit_rectified)).isChecked()).isTrue();
            ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
            ((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).setValue(7.5f);
            dialog.requireView().findViewById(R.id.edit_original).performClick();
            assertThat(((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop()).isEqualTo(new RectF(0, 0, 1, 1));
            ((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).setValue(-3);
            controller.recreate(); activity = controller.get();
            dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            assertThat(((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).getValue()).isEqualTo(-3);
            dialog.requireView().findViewById(R.id.edit_rectified).performClick();
            assertThat(((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop()).isEqualTo(new RectF(.1f, .2f, .8f, .9f));
            assertThat(((com.google.android.material.slider.Slider) dialog.requireView().findViewById(R.id.rotation_slider)).getValue()).isEqualTo(7.5f);
            android.view.View root = dialog.requireView();
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(411, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 411, 800);
            Bitmap shot = Bitmap.createBitmap(411, 800, Bitmap.Config.ARGB_8888); root.draw(new Canvas(shot));
            var directory = new java.io.File("build/reports/ui"); directory.mkdirs();
            try (var out = new java.io.FileOutputStream(new java.io.File(directory, "edit-corrected.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
            shot.recycle();
            dialog.dismiss(); activity.getSupportFragmentManager().executePendingTransactions();
            assertThat(new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class).page.getHeight()).isEqualTo(700);
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    private android.app.Application savedCorrection() throws Exception {
        // AndroidX's default factory retains its first Application across host test cases.
        // Seed the directory used by the actual ViewModel, then reopen through the Activity.
        var probe = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        var model = new androidx.lifecycle.ViewModelProvider(probe.get()).get(DocumentViewModel.class);
        DocumentExtractionHostTest.awaitIdle(model);
        android.app.Application app = model.getApplication();
        DocumentExtractionHostTest.close(probe);
        var directory = app.getFilesDir();
        new java.io.File(directory, "rectified.png").delete();
        try (var image = getClass().getResourceAsStream("/horizontal.png")) { java.nio.file.Files.copy(image, new java.io.File(directory, "page.png").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        Bitmap original = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888); original.eraseColor(Color.BLUE);
        try (var out = new java.io.FileOutputStream(new java.io.File(directory, "source.png"))) { original.compress(Bitmap.CompressFormat.PNG, 100, out); }
        original.recycle();
        java.nio.file.Files.write(new java.io.File(directory, "page.json").toPath(), "{\"pageRectified\":true,\"lines\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return app;
    }
    private static void touch(ImageEditView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0); view.onTouchEvent(event); event.recycle();
    }
}
