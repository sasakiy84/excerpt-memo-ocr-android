package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class ParagraphTextTest {
    private static OcrLine h(float start, float end, float y, String text) { return new OcrLine(start, y, end, y + 20, text); }
    private static OcrLine v(float start, float end, float x, String text) { return new OcrLine(x, start, x + 20, end, text); }
    private static String joined(List<OcrLine> lines) { return ParagraphText.extract(lines, 0, ReadingOrder.text(lines).length()).text(); }
    @Test public void japaneseFullWidthNumbersStayJoined() {
        assertEquals("２０２５年", joined(List.of(h(0, 200, 0, "２０２"), h(0, 200, 30, "５年"))));
    }
    @Test public void englishWordsKeepSpacesAndAccentedLettersAcrossLineBreaks() {
        assertEquals("A café keeps ideas", joined(List.of(h(0, 200, 0, "A café"), h(0, 200, 30, "keeps ideas"))));
        assertEquals("A quote” continues here", joined(List.of(h(0, 200, 0, "A quote”"), h(0, 200, 30, "continues here"))));
        assertEquals("well-known", joined(List.of(h(0, 200, 0, "well-"), h(0, 200, 30, "known"))));
    }
    @Test public void fullWidthLinesWithinParagraphAreJoined() {
        assertEquals("地域の資料を調べる。利用者の声を集める。", joined(List.of(h(0,200,0,"地域の資料を調べる。"), h(0,200,30,"利用者の声を集める。"))));
    }
    @Test public void shortPreviousLineEndsParagraph() {
        assertEquals("あいうえおかきくけこさしすせそ\nたちつてとなにぬねの", joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,100,30,"さしすせそ"), h(0,200,60,"たちつてとなにぬねの"))));
    }
    @Test public void oneCharacterShortEndingAndSmallBoxNoiseStayJoined() {
        assertEquals("あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへ", joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,180,30,"さしすせそたちつて"), h(0,200,60,"となにぬねのはひふへ"))));
        assertEquals("あいうえおかきくけこさしすせそたちつてと", joined(List.of(h(0,195,0,"あいうえおかきくけこ"), h(0,200,30,"さしすせそたちつてと"))));
    }
    @Test public void modestIndentAndLineSpacingStayJoinedInBothDirections() {
        assertEquals("前の行次の行", joined(List.of(h(0,200,0,"前の行"), h(12,200,50,"次の行"))));
        assertEquals("前の列次の列", joined(List.of(v(0,200,100,"前の列"), v(12,200,50,"次の列"))));
    }
    @Test public void subCharacterIndentIsNotEnoughToSplitAParagraph() {
        String expected = "あいうえおかきくけこさしすせそたちつてと";
        assertEquals(expected, joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(12,200,30,"さしすせそたちつてと"))));
        assertEquals(expected, joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(12,200,70,"さしすせそたちつてと"))));
    }
    @Test public void slightlyShortVerticalColumnStaysJoined() {
        assertEquals("あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへ", joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(0,180,70,"さしすせそたちつて"), v(0,200,40,"となにぬねのはひふへ"))));
    }
    @Test public void clearBlankLineRemainsAParagraphBoundary() {
        assertEquals("前の段落\n次の段落", joined(List.of(h(0,200,0,"前の段落"), h(0,200,70,"次の段落"))));
    }
    @Test public void oneCharacterIndentStartsParagraphAndFollowingLineContinuesIt() {
        assertEquals("あいうえおかきくけこ\nさしすせそたちつてとなにぬねのはひふへ", joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(20,200,30,"さしすせそたちつて"), h(0,200,60,"となにぬねのはひふへ"))));
    }
    @Test public void verticalColumnsUseTopIndentAndBottomLineEnd() {
        assertEquals("あいうえおかきくけこ\nさしすせそたちつてとなにぬねのはひふへ", joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(20,200,70,"さしすせそたちつて"), v(0,200,40,"となにぬねのはひふへ"))));
    }
    @Test public void verticalShortColumnEndsParagraph() {
        assertEquals("あいうえおかきくけこさしすせそ\nたちつてとなにぬねの", joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(0,100,70,"さしすせそ"), v(0,200,40,"たちつてとなにぬねの"))));
    }
    @Test public void englishWordsKeepTheirSpaceWhenLinesJoin() {
        assertEquals("Read the original document carefully.", joined(List.of(h(0,200,0,"Read the original"), h(0,200,30,"document carefully."))));
    }
    @Test public void selectionAcrossJoinedLinesRetainsOriginalOffsets() {
        var lines = List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,200,30,"さしすせそたちつてと"));
        var result = ParagraphText.extract(lines, 8, 14);
        assertEquals("けこさしす", result.text());
        assertArrayEquals(new int[]{8,9,11,12,13}, result.origins());
    }
    @Test public void columnsAndTableRowsRemainSeparate() {
        assertEquals("左の列\n右の列", joined(List.of(h(0,200,300,"左の列"), h(300,500,0,"右の列"))));
        assertEquals("項目\n数値", joined(List.of(new OcrLine(0,0,200,20,"項目",OcrLine.Kind.TABLE),new OcrLine(0,30,200,50,"数値",OcrLine.Kind.TABLE))));
    }
}
