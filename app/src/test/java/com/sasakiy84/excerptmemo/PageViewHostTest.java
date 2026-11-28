package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.Manifest;
import android.graphics.Bitmap;
import android.view.MotionEvent;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.util.List;
import java.util.Map;
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
@Config(sdk = 36, qualifiers = "w411dp-h731dp-420dpi", shadows = PageViewHostTest.SurfaceFreeMagnifier.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class PageViewHostTest {
    // Robolectric has no SurfaceControl compositor. Selection and drawing remain real;
    // only the platform popup surface is omitted from these host gesture tests.
    @org.robolectric.annotation.Implements(android.widget.Magnifier.class)
    public static class SurfaceFreeMagnifier {
        @org.robolectric.annotation.Implementation protected void show(float x, float y) {}
        @org.robolectric.annotation.Implementation protected void dismiss() {}
    }

    @Before public void freshDocument() {
        var app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.CAMERA);
        app.getSharedPreferences("extraction", 0).edit().clear().commit();
        new File(app.getFilesDir(), "page.json").delete();
    }

    @Test public void draggingEndHandleSelectsOnlyTheFirstThreeCharacters() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(100, 100, 900, 180, "資料の文字を読む"));
            model.setInclusions(false, false, false);
            PageView view = activity.findViewById(R.id.page_view);
            view.layout(0, 0, 1000, 1000);
            float density = activity.getResources().getDisplayMetrics().density;
            float dx = 8 * density, dy = 12 * density;
            touch(view, MotionEvent.ACTION_DOWN, 900 + dx, 180 + dy);
            touch(view, MotionEvent.ACTION_MOVE, 400 + dx, 180 + dy);
            touch(view, MotionEvent.ACTION_UP, 400 + dx, 180 + dy);
            assertEquals("資料の", model.text);
            assertEquals(3, model.selectionEnd);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void draggingAcrossTwoLinesSelectsCharactersInReadingOrder() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(100, 100, 900, 180, "資料の文字を読む"),
                    new OcrLine(100, 300, 500, 380, "選択範囲"));
            model.setInclusions(false, false, false);
            PageView view = activity.findViewById(R.id.page_view);
            view.layout(0, 0, 1000, 1000);
            touch(view, MotionEvent.ACTION_DOWN, 350, 140);
            touch(view, MotionEvent.ACTION_MOVE, 250, 340);
            touch(view, MotionEvent.ACTION_UP, 250, 340);
            assertEquals("の文字を読む選択", model.text);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void draggingStartHandleOnVerticalTextExcludesTheLeadingCharacters() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(500, 100, 580, 900, "資料の文字を読む"));
            model.setInclusions(false, false, false);
            PageView view = activity.findViewById(R.id.page_view);
            view.layout(0, 0, 1000, 1000);
            float density = activity.getResources().getDisplayMetrics().density;
            float handleOffset = 12 * density;
            touch(view, MotionEvent.ACTION_DOWN, 540, 100 - handleOffset);
            touch(view, MotionEvent.ACTION_MOVE, 540, 400 - handleOffset);
            touch(view, MotionEvent.ACTION_UP, 540, 400 - handleOffset);
            assertEquals("文字を読む", model.text);
            assertEquals(3, model.selectionStart);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void aTapNearTheVerticalHandleCanSelectTheNeighboringColumn() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(500, 200, 540, 600, "資料抜粋"),
                    new OcrLine(450, 100, 490, 500, "書籍本文"));
            model.setInclusions(false, false, false);
            model.select(0, 4);
            PageView view = activity.findViewById(R.id.page_view);
            view.layout(0, 0, 1000, 1000);
            touch(view, MotionEvent.ACTION_DOWN, 470, 170);
            touch(view, MotionEvent.ACTION_UP, 470, 170);
            assertEquals("書", model.text);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void draggingTheEndHandleAcrossVerticalColumnsSelectsRightToLeft() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model);
            model.closePage().get();
            model.page = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
            model.allLines = List.of(new OcrLine(600, 200, 640, 600, "資料抜粋"),
                    new OcrLine(540, 200, 580, 600, "書籍本文"));
            model.setInclusions(false, false, false);
            model.select(1, 3);
            PageView view = activity.findViewById(R.id.page_view);
            view.layout(0, 0, 1000, 1000);
            float offset = 12 * activity.getResources().getDisplayMetrics().density;
            touch(view, MotionEvent.ACTION_DOWN, 620, 500 + offset);
            touch(view, MotionEvent.ACTION_MOVE, 560, 400 + offset);
            touch(view, MotionEvent.ACTION_UP, 560, 400 + offset);
            assertEquals("料抜粋書籍", model.text);
        } finally { DocumentExtractionHostTest.close(controller); }
    }

    @Test public void aStraightDragStaysOnTheSlantedColumn() {
        var context = RuntimeEnvironment.getApplication();
        var parent = new android.widget.FrameLayout(context);
        var view = new PageView(context, null);
        var right = new OcrLine(100, 20, 160, 220, "資料抜粋");
        var left = new OcrLine(65, 20, 125, 220, "書籍本文");
        var geometry = new TextGeometry(List.of(right, left), Map.of(
                right, new VerticalInkGeometry.Stripe(110, 145, 8),
                left, new VerticalInkGeometry.Stripe(75, 110, 8)));
        Bitmap page = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        try {
            parent.addView(view);
            view.layout(0, 0, 300, 300);
            view.setPage(page, geometry, 0, 1);
            int[] selection = new int[2];
            view.setSelectionListener((start, end) -> { selection[0] = start; selection[1] = end; });
            float yOffset = 12 * context.getResources().getDisplayMetrics().density;
            touch(view, MotionEvent.ACTION_DOWN, 118.75f, 70 + yOffset);
            touch(view, MotionEvent.ACTION_MOVE, 118.75f, 205 + yOffset);
            touch(view, MotionEvent.ACTION_UP, 118.75f, 205 + yOffset);
            assertEquals("資料抜粋", geometry.text.substring(selection[0], selection[1]));
        } finally {
            page.recycle();
        }
    }

    private static void touch(PageView view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 20, action, x, y, 0);
        view.onTouchEvent(event); event.recycle();
    }
}
