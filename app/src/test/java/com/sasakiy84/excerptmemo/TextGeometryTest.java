package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TextGeometryTest {
    @Test public void supplementaryKanjiAndCombiningMarksAreNeverSplit() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(0, 0, 400, 40, "𠮷か\u3099文字")));
        assertEquals(2, geometry.next(0));
        assertEquals(4, geometry.next(2));
        assertEquals(2, geometry.previous(4));
        assertEquals(0, geometry.snap(1, false));
        assertEquals(4, geometry.snap(3, true));
    }
    @Test public void partialVerticalHighlightStopsAtCharacterBoundary() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(100, 100, 150, 500, "資料抜粋")));
        var highlight = geometry.highlight(0, 1, 3);
        assertEquals(100f, highlight.left, 0f);
        assertEquals(200f, highlight.top, 0f);
        assertEquals(400f, highlight.bottom, 0f);
        assertEquals(2, geometry.nearest(120, 320, false));
    }

    @Test public void selectsTheInkColumnWhereSlantedDetectionBoxesOverlap() {
        var right = new OcrLine(95, 20, 153, 220, "一二三四五六七八九十上下");
        var left = new OcrLine(60, 20, 118, 220, "甲乙丙丁戊己庚辛壬癸本文");
        List<OcrLine> lines = List.of(right, left);
        Bitmap page = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(page);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint();
            for (int i = 0; i < 12; i++) {
                float top = 23 + i * 16;
                canvas.drawRect(65 + i * 3, top, 80 + i * 3, top + 10, ink);
                canvas.drawRect(100 + i * 3, top, 115 + i * 3, top + 10, ink);
            }
            var stripes = VerticalInkGeometry.estimate(page, lines);
            TextGeometry geometry = new TextGeometry(lines, stripes);
            int offset = geometry.nearest(106, 204, false);
            assertEquals(geometry.starts[1] + 11, offset);
            assertTrue(geometry.endpoint(geometry.starts[1], false).x < 83);
            assertTrue(geometry.endpoint(geometry.length(), true).x > 101);
        } finally {
            page.recycle();
        }
    }

    @Test public void fallsBackToTheNearestColumnCenterWhenThereIsNoInk() {
        var right = new OcrLine(100, 10, 160, 150, "右欄");
        var left = new OcrLine(70, 10, 130, 150, "左欄");
        var lines = List.of(right, left);
        Bitmap page = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888);
        try {
            page.eraseColor(Color.WHITE);
            var stripes = VerticalInkGeometry.estimate(page, lines);
            assertTrue(stripes.isEmpty());
            TextGeometry geometry = new TextGeometry(lines, stripes);
            assertEquals(geometry.starts[1], geometry.nearest(105, 30, false));
        } finally {
            page.recycle();
        }
    }
    @Test public void rangeAcrossLinesIncludesOnlyTheRequestedCharacters() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(0, 0, 400, 40, "資料抜粋"), new OcrLine(0, 100, 400, 140, "文字選択")));
        assertEquals(4, geometry.snap(4, true));
        assertEquals(4, geometry.next(3));
        assertEquals("抜粋\n文字", geometry.text.substring(2, 7));
        assertEquals(400f, geometry.endpoint(4, true).x, 0f);
        assertEquals(140f, geometry.endpoint(5, false).y, 0f);
        assertEquals(200f, geometry.highlight(1, 2, 7).right, 1f);
    }
    @Test public void horizontalLatinPositionsUseGlyphWidths() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(0, 0, 300, 40, "WiW")));
        float wide = geometry.highlight(0, 0, 1).width();
        float narrow = geometry.highlight(0, 1, 2).width();
        assertTrue(wide > narrow * 2);
    }
}
