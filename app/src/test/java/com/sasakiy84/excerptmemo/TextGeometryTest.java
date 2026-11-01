package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
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
        assertThat(geometry.next(0)).isEqualTo(2);
        assertThat(geometry.next(2)).isEqualTo(4);
        assertThat(geometry.previous(4)).isEqualTo(2);
        assertThat(geometry.snap(1, false)).isEqualTo(0);
        assertThat(geometry.snap(3, true)).isEqualTo(4);
    }
    @Test public void partialVerticalHighlightStopsAtCharacterBoundary() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(100, 100, 150, 500, "資料抜粋")));
        var highlight = geometry.highlight(0, 1, 3);
        assertThat(highlight.left).isEqualTo(100f);
        assertThat(highlight.top).isEqualTo(200f);
        assertThat(highlight.bottom).isEqualTo(400f);
        assertThat(geometry.nearest(120, 320, false)).isEqualTo(2);
    }
    @Test public void rangeAcrossLinesIncludesOnlyTheRequestedCharacters() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(0, 0, 400, 40, "資料抜粋"), new OcrLine(0, 100, 400, 140, "文字選択")));
        assertThat(geometry.snap(4, true)).isEqualTo(4);
        assertThat(geometry.next(3)).isEqualTo(4);
        assertThat(geometry.text.substring(2, 7)).isEqualTo("抜粋\n文字");
        assertThat(geometry.endpoint(4, true).x).isEqualTo(400f);
        assertThat(geometry.endpoint(5, false).y).isEqualTo(140f);
        assertThat(geometry.highlight(1, 2, 7).right).isWithin(1f).of(200f);
    }
    @Test public void horizontalLatinPositionsUseGlyphWidths() {
        TextGeometry geometry = new TextGeometry(List.of(new OcrLine(0, 0, 300, 40, "WiW")));
        float wide = geometry.highlight(0, 0, 1).width();
        float narrow = geometry.highlight(0, 1, 2).width();
        assertThat(wide).isGreaterThan(narrow * 2);
    }
}
