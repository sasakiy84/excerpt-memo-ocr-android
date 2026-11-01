package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import java.util.List;
import java.nio.FloatBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ReadingOrderTest {
    @Test public void horizontalLinesReadFromTopToBottom() {
        var lower = new OcrLine(10, 100, 300, 130, "下の行");
        var upper = new OcrLine(10, 10, 300, 40, "上の行");
        assertThat(ReadingOrder.text(ReadingOrder.sort(List.of(lower, upper)))).isEqualTo("上の行\n下の行");
    }
    @Test public void verticalColumnsReadFromRightToLeft() {
        var left = new OcrLine(10, 10, 40, 300, "左の列");
        var right = new OcrLine(100, 10, 130, 300, "右の列");
        assertThat(ReadingOrder.text(ReadingOrder.sort(List.of(left, right)))).isEqualTo("右の列\n左の列");
    }
    @Test public void horizontalNewspaperColumnsStayTogether() {
        var a = new OcrLine(10, 10, 200, 30, "左上");
        var b = new OcrLine(10, 40, 200, 60, "左下");
        var c = new OcrLine(260, 10, 450, 30, "右上");
        var d = new OcrLine(260, 40, 450, 60, "右下");
        assertThat(ReadingOrder.text(ReadingOrder.sort(List.of(d, b, c, a)))).isEqualTo("左上\n左下\n右上\n右下");
    }
    @Test public void emptyPageHasNoText() {
        assertThat(ReadingOrder.text(ReadingOrder.sort(List.of()))).isEmpty();
    }
    @Test public void rectangleSelectionIncludesIntersectingTextAndExcludesOutsideLines() {
        var line = new OcrLine(100, 100, 400, 130, "選択");
        assertThat(line.intersects(150, 105, 200, 125)).isTrue();
        assertThat(line.intersects(0, 0, 50, 50)).isFalse();
    }
    @Test public void parseqDecoderStopsAtEndTokenAndKeepsRepeatedCharacters() {
        float[] logits = {0, 2, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 0, 0, 0, 0, 0, 0, 6};
        assertThat(OcrEngine.decode(FloatBuffer.wrap(logits), 5, 4, new String[]{"日", "本", "𠮷"})).isEqualTo("日日本");
    }
    @Test public void parseqDecoderPreservesSupplementaryUnicode() {
        assertThat(OcrEngine.decode(FloatBuffer.wrap(new float[]{0, 0, 0, 3}), 1, 4,
                new String[]{"日", "本", "𠮷"})).isEqualTo("𠮷");
    }
}
