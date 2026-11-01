package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import java.util.List;
import org.junit.Test;

public final class ParagraphTextTest {
    private static OcrLine h(float start, float end, float y, String text) { return new OcrLine(start, y, end, y + 20, text); }
    private static OcrLine v(float start, float end, float x, String text) { return new OcrLine(x, start, x + 20, end, text); }
    private static String joined(List<OcrLine> lines) { return ParagraphText.extract(lines, 0, ReadingOrder.text(lines).length()).text(); }
    @Test public void fullWidthLinesWithinParagraphAreJoined() {
        assertThat(joined(List.of(h(0,200,0,"地域の資料を調べる。"), h(0,200,30,"利用者の声を集める。"))))
                .isEqualTo("地域の資料を調べる。利用者の声を集める。");
    }
    @Test public void shortPreviousLineEndsParagraph() {
        assertThat(joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,100,30,"さしすせそ"), h(0,200,60,"たちつてとなにぬねの"))))
                .isEqualTo("あいうえおかきくけこさしすせそ\nたちつてとなにぬねの");
    }
    @Test public void oneCharacterShortEndingStartsParagraphButSmallBoxNoiseDoesNot() {
        assertThat(joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,180,30,"さしすせそたちつて"), h(0,200,60,"となにぬねのはひふへ"))))
                .isEqualTo("あいうえおかきくけこさしすせそたちつて\nとなにぬねのはひふへ");
        assertThat(joined(List.of(h(0,195,0,"あいうえおかきくけこ"), h(0,200,30,"さしすせそたちつてと"))))
                .isEqualTo("あいうえおかきくけこさしすせそたちつてと");
    }
    @Test public void oneCharacterIndentStartsParagraphAndFollowingLineContinuesIt() {
        assertThat(joined(List.of(h(0,200,0,"あいうえおかきくけこ"), h(20,200,30,"さしすせそたちつて"), h(0,200,60,"となにぬねのはひふへ"))))
                .isEqualTo("あいうえおかきくけこ\nさしすせそたちつてとなにぬねのはひふへ");
    }
    @Test public void verticalColumnsUseTopIndentAndBottomLineEnd() {
        assertThat(joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(20,200,70,"さしすせそたちつて"), v(0,200,40,"となにぬねのはひふへ"))))
                .isEqualTo("あいうえおかきくけこ\nさしすせそたちつてとなにぬねのはひふへ");
    }
    @Test public void verticalShortColumnEndsParagraph() {
        assertThat(joined(List.of(v(0,200,100,"あいうえおかきくけこ"), v(0,100,70,"さしすせそ"), v(0,200,40,"たちつてとなにぬねの"))))
                .isEqualTo("あいうえおかきくけこさしすせそ\nたちつてとなにぬねの");
    }
    @Test public void englishWordsKeepTheirSpaceWhenLinesJoin() {
        assertThat(joined(List.of(h(0,200,0,"Read the original"), h(0,200,30,"document carefully."))))
                .isEqualTo("Read the original document carefully.");
    }
    @Test public void selectionAcrossJoinedLinesRetainsOriginalOffsets() {
        var lines = List.of(h(0,200,0,"あいうえおかきくけこ"), h(0,200,30,"さしすせそたちつてと"));
        var result = ParagraphText.extract(lines, 8, 14);
        assertThat(result.text()).isEqualTo("けこさしす");
        assertThat(result.origins()).asList().containsExactly(8,9,11,12,13).inOrder();
    }
    @Test public void columnsAndTableRowsRemainSeparate() {
        assertThat(joined(List.of(h(0,200,300,"左の列"), h(300,500,0,"右の列")))).isEqualTo("左の列\n右の列");
        assertThat(joined(List.of(new OcrLine(0,0,200,20,"項目",OcrLine.Kind.TABLE),new OcrLine(0,30,200,50,"数値",OcrLine.Kind.TABLE))))
                .isEqualTo("項目\n数値");
    }
}
