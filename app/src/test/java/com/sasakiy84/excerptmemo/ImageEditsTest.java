package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.*;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ImageEditsTest {
    @BeforeClass public static void loadHostCpuOpenCv() { HostOpenCv.load(); }
    @Test public void quarterTurnAndCropUseThePreviewCoordinateSpace() {
        Bitmap input = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(input); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(); paint.setColor(Color.RED); canvas.drawRect(0, 0, 100, 150, paint);
        Bitmap output = ImageEdits.apply(input, 90, new RectF(.5f, 0, 1, .5f));
        assertEquals(150, output.getWidth());
        assertEquals(100, output.getHeight());
        assertEquals(Color.RED, output.getPixel(75, 50));
    }
    @Test public void fineRotationDoesNotStretchTheSourceAndFillsCornersWhite() {
        Bitmap input = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888); input.eraseColor(Color.BLUE);
        Bitmap output = ImageEdits.apply(input, 12, new RectF(0, 0, 1, 1));
        assertTrue(output.getWidth() > 200);
        assertTrue(output.getHeight() > 300);
        assertEquals(Color.WHITE, output.getPixel(0, 0));
        assertEquals(Color.BLUE, output.getPixel(output.getWidth()/2, output.getHeight()/2));
    }
    @Test public void draggingCropCornerAndInteriorChangesAndMovesTheSelection() {
        var context = RuntimeEnvironment.getApplication();
        ImageEditView editor = new ImageEditView(context); new FrameLayout(context).addView(editor);
        editor.setImage(Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888));
        editor.layout(0, 0, 600, 1000);
        editor.draw(new Canvas(Bitmap.createBitmap(600, 1000, Bitmap.Config.ARGB_8888)));
        touch(editor, 0, 20, 80); touch(editor, 2, 76, 164); touch(editor, 1, 76, 164);
        assertEquals(.1f, editor.getCrop().left, .001f);
        assertEquals(.1f, editor.getCrop().top, .001f);
        touch(editor, 0, 300, 500); touch(editor, 2, 244, 416); touch(editor, 1, 244, 416);
        assertEquals(0, editor.getCrop().left, .001f);
        assertEquals(.9f, editor.getCrop().right, .001f);
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
            assertEquals(182, model.page.getHeight());
            assertFalse(model.pageRectified);
            assertTrue(model.text.contains("資料"));
            assertFalse(model.text.toLowerCase().contains("android"));
            model.applyImageEdit(0, new RectF(0, 0, 1, 1));
            DocumentExtractionHostTest.awaitIdle(model);
            assertEquals(700, model.page.getHeight());
            assertTrue(model.text.toLowerCase().contains("android"));
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
            ((android.widget.Toolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
            activity.getSupportFragmentManager().executePendingTransactions();
            var dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
            dialog.requireView().findViewById(R.id.rotate_image_quarter).performClick();
            ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(525);
            controller.recreate(); activity = controller.get();
            dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            RectF restoredCrop = ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop();
            assertEquals(.1f, restoredCrop.left, .001f);
            assertEquals(.1f, restoredCrop.top, .001f);
            assertEquals(.8f, restoredCrop.right, .001f);
            assertEquals(.8f, restoredCrop.bottom, .001f);
            assertEquals(525, ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).getProgress());
            dialog.dismiss(); activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(1200, new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class).page.getWidth());
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    @Test public void correctedCropSurvivesProcessRestoreAndCanReturnToEitherBase() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA);
        app = savedCorrection();
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        var model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
        DocumentExtractionHostTest.awaitIdle(model);
        assertTrue(model.editRectified);
        model.applyImageEdit(0, new RectF(0, .1f, 1, .36f), true);
        DocumentExtractionHostTest.awaitIdle(model);
        assertEquals(182, model.page.getHeight());
        assertTrue(model.pageRectified);
        assertTrue(model.text.contains("資料"));
        assertFalse(model.text.toLowerCase().contains("android"));
        long deadline = System.nanoTime() + 5_000_000_000L;
        var state = new java.io.File(app.getFilesDir(), "page.json");
        while (!new String(java.nio.file.Files.readAllBytes(state.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("rectifiedEditCrop") && System.nanoTime() < deadline) Thread.sleep(10);
        controller.pause().stop().destroy();
        controller = org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        try {
            model = new androidx.lifecycle.ViewModelProvider(controller.get()).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            assertEquals(182, model.page.getHeight());
            assertEquals(700, model.editorSource(true).getHeight());
            assertEquals(900, model.editorSource(false).getHeight());
            assertEquals(.1f, model.rectifiedEditCrop.top, .001f);
            model.applyImageEdit(0, new RectF(0, 0, 1, 1), true);
            DocumentExtractionHostTest.awaitIdle(model);
            assertEquals(700, model.page.getHeight());
            assertTrue(model.pageRectified);
            assertTrue(model.text.toLowerCase().contains("android"));
            model.applyImageEdit(0, new RectF(0, 0, 1, 1), false);
            DocumentExtractionHostTest.awaitIdle(model);
            assertEquals(900, model.page.getHeight());
            assertFalse(model.pageRectified);
            assertTrue(model.hasRectifiedImage());
            assertEquals(Color.BLUE, model.editorSource(false).getPixel(20, 20));
            model.closePage().get();
            assertFalse(new java.io.File(app.getFilesDir(), "rectified.png").exists());
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
            assertNotNull(restoredModel.page);
            ((android.widget.Toolbar) activity.findViewById(R.id.toolbar)).getMenu().performIdentifierAction(2, 0);
            activity.getSupportFragmentManager().executePendingTransactions();
            var dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            assertTrue(((android.widget.RadioButton)dialog.requireView().findViewById(R.id.edit_rectified)).isChecked());
            ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).setCrop(new RectF(.1f, .2f, .8f, .9f));
            ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(525);
            dialog.requireView().findViewById(R.id.edit_original).performClick();
            assertEquals(new RectF(0, 0, 1, 1), ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop());
            ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).setProgress(420);
            controller.recreate(); activity = controller.get();
            dialog = (ImageEditDialog) activity.getSupportFragmentManager().findFragmentByTag("image-edit");
            assertEquals(420, ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).getProgress());
            dialog.requireView().findViewById(R.id.edit_rectified).performClick();
            assertEquals(new RectF(.1f, .2f, .8f, .9f), ((ImageEditView) dialog.requireView().findViewById(R.id.image_editor)).getCrop());
            assertEquals(525, ((android.widget.SeekBar) dialog.requireView().findViewById(R.id.rotation_slider)).getProgress());
            android.view.View root = dialog.requireView();
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(411, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 411, 800);
            Bitmap shot = Bitmap.createBitmap(411, 800, Bitmap.Config.ARGB_8888); root.draw(new Canvas(shot));
            var directory = new java.io.File("build/reports/ui"); directory.mkdirs();
            try (var out = new java.io.FileOutputStream(new java.io.File(directory, "edit-corrected.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
            shot.recycle();
            dialog.dismiss(); activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(700, new androidx.lifecycle.ViewModelProvider(activity).get(DocumentViewModel.class).page.getHeight());
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
