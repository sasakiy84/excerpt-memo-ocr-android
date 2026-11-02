package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.Matrix;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class CameraPreviewTest {
    @Test public void textureCorrectionKeepsTheBufferBoundsWhileRotatingPixels() {
        Matrix matrix = CameraPreview.textureCorrection(1280, 960, -90);
        RectF bounds = new RectF(); matrix.mapRect(bounds, new RectF(0, 0, 1280, 960));
        assertEquals(0, bounds.left, .001f); assertEquals(0, bounds.top, .001f);
        assertEquals(1280, bounds.right, .001f); assertEquals(960, bounds.bottom, .001f);
        float[] point = {0, 0}; matrix.mapPoints(point);
        assertEquals(0, point[0], .001f); assertEquals(960, point[1], .001f);
    }
    @Test public void rotationCropAndMirrorKeepFocusOnDisplayedPixel() {
        for (int rotation : new int[]{0, 90, 180, 270}) for (boolean mirror : new boolean[]{false, true}) {
            RectF crop = new RectF(160, 120, 1120, 840);
            Matrix display = CameraPreview.transform(1280, 960, crop, rotation, mirror, 411, 650);
            float[] center = {640, 480}; display.mapPoints(center);
            assertEquals(205.5f, center[0], .001f); assertEquals(325, center[1], .001f);
            RectF visible = new RectF(); display.mapRect(visible, crop);
            assertTrue(visible.width() >= 411 - .001f); assertTrue(visible.height() >= 650 - .001f);
            float[] point = {500, 350}; display.mapPoints(point);
            Matrix inverse = new Matrix(); assertTrue(display.invert(inverse)); inverse.mapPoints(point);
            assertEquals(500, point[0], .001f); assertEquals(350, point[1], .001f);
        }
    }
}
