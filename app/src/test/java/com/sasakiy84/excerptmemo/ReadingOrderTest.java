package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
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
        assertEquals("上の行\n下の行", ReadingOrder.text(ReadingOrder.sort(List.of(lower, upper))));
    }
    @Test public void verticalColumnsReadFromRightToLeft() {
        var left = new OcrLine(10, 10, 40, 300, "左の列");
        var right = new OcrLine(100, 10, 130, 300, "右の列");
        assertEquals("右の列\n左の列", ReadingOrder.text(ReadingOrder.sort(List.of(left, right))));
    }
    @Test public void staggeredVerticalColumnsAndHorizontalAnnotationsKeepTheBodyOrder() {
        var left = new OcrLine(10, 10, 30, 100, "左の列");
        var right = new OcrLine(70, 200, 90, 300, "右の列");
        var ruby = new OcrLine(35, 30, 62, 40, "ひだり", OcrLine.Kind.RUBY);
        var folio = new OcrLine(35, 350, 62, 360, "51", OcrLine.Kind.PAGE_NUMBER);
        var lines = ReadingOrder.sort(List.of(left, folio, ruby, right));
        assertTrue(lines.indexOf(right) < lines.indexOf(left));
    }
    @Test public void horizontalNewspaperColumnsStayTogether() {
        var a = new OcrLine(10, 10, 200, 30, "左上");
        var b = new OcrLine(10, 40, 200, 60, "左下");
        var c = new OcrLine(260, 10, 450, 30, "右上");
        var d = new OcrLine(260, 40, 450, 60, "右下");
        assertEquals("左上\n左下\n右上\n右下", ReadingOrder.text(ReadingOrder.sort(List.of(d, b, c, a))));
    }
    @Test public void emptyPageHasNoText() {
        assertTrue(ReadingOrder.text(ReadingOrder.sort(List.of())).isEmpty());
    }
    @Test public void rectangleSelectionIncludesIntersectingTextAndExcludesOutsideLines() {
        var line = new OcrLine(100, 100, 400, 130, "選択");
        assertTrue(line.intersects(150, 105, 200, 125));
        assertFalse(line.intersects(0, 0, 50, 50));
    }
    @Test public void parseqDecoderStopsAtEndTokenAndKeepsRepeatedCharacters() {
        float[] logits = {0, 2, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 0, 0, 0, 0, 0, 0, 6};
        assertEquals("日日本", OcrEngine.decode(FloatBuffer.wrap(logits), 5, 4, new String[]{"日", "本", "𠮷"}));
    }
    @Test public void parseqDecoderPreservesSupplementaryUnicode() {
        assertEquals("𠮷", OcrEngine.decode(FloatBuffer.wrap(new float[]{0, 0, 0, 3}), 1, 4,
                new String[]{"日", "本", "𠮷"}));
    }
    @Test public void parseqDecoderRemovesSpacesAroundJapanesePunctuation() {
        String[] characters = {"日", " ", "、", "本", "。"};
        int[] tokens = {1, 2, 3, 2, 4, 2, 5, 2, 0};
        int classes = characters.length + 1;
        FloatBuffer logits = FloatBuffer.allocate(tokens.length * classes);
        for (int i = 0; i < tokens.length; i++) logits.put(i * classes + tokens[i], 1);
        assertEquals("日、本。", OcrEngine.decode(logits, tokens.length, classes, characters));
    }
}
