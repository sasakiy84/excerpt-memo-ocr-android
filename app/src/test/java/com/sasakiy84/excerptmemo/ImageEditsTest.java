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
            model.applyImageEdit(0, new RectF(0, .1f, 1, .36f));
            DocumentExtractionHostTest.awaitIdle(model);
            assertThat(model.page.getHeight()).isEqualTo(182);
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
    private static void touch(ImageEditView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0); view.onTouchEvent(event); event.recycle();
    }
}
