package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import java.util.List;
import org.junit.Test;

public final class PageReferencesTest {
    private static OcrLine folio(float x, String number) { return new OcrLine(x, 950, x + 30, 980, number, OcrLine.Kind.PAGE_NUMBER); }
    private static final OcrLine LEFT = new OcrLine(100, 100, 400, 200, "左ページ");
    private static final OcrLine RIGHT = new OcrLine(650, 100, 950, 200, "右ページ");

    @Test public void oneDetectedFolioIsAppendedEvenThoughItIsExcludedFromBody() {
        assertThat(new PageReferences(List.of(folio(500, "１２")), 1000).suffix(List.of(LEFT))).isEqualTo("（p. 12）");
    }
    @Test public void selectionOnOnlyOneSideUsesOnlyThatPagesFolio() {
        var pages = new PageReferences(List.of(folio(100, "13"), folio(850, "12")), 1000);
        assertThat(pages.suffix(List.of(RIGHT))).isEqualTo("（p. 12）");
        assertThat(pages.suffix(List.of(LEFT))).isEqualTo("（p. 13）");
    }
    @Test public void selectionAcrossSpreadUsesBothPrintedFolios() {
        var pages = new PageReferences(List.of(folio(100, "13"), folio(850, "12")), 1000);
        assertThat(pages.suffix(List.of(RIGHT, LEFT))).isEqualTo("（pp. 12–13）");
        assertThat(pages.order(List.of(LEFT, RIGHT))).containsExactly(RIGHT, LEFT).inOrder();
    }
    @Test public void nonConsecutiveFoliosDoNotInventInterveningPages() {
        var pages = new PageReferences(List.of(folio(100, "12"), folio(850, "15")), 1000);
        assertThat(pages.suffix(List.of(LEFT, RIGHT))).isEqualTo("（pp. 12, 15）");
    }
    @Test public void ambiguousNumbersAreNotAppended() {
        assertThat(new PageReferences(List.of(folio(100, "12"), folio(150, "13")), 1000).suffix(List.of(LEFT))).isEmpty();
        assertThat(new PageReferences(List.of(folio(100, "12"), folio(500, "13"), folio(850, "14")), 1000).suffix(List.of(LEFT))).isEmpty();
        assertThat(new PageReferences(List.of(folio(100, "12"), folio(850, "12")), 1000).suffix(List.of(LEFT))).isEmpty();
    }
    @Test public void missingFolioAndEmptySelectionProduceNoReference() {
        assertThat(new PageReferences(List.of(), 1000).suffix(List.of(LEFT))).isEmpty();
        assertThat(new PageReferences(List.of(folio(100, "12")), 1000).suffix(List.of())).isEmpty();
        assertThat(new PageReferences(List.of(folio(100, "第12章")), 1000).suffix(List.of(LEFT))).isEmpty();
    }
}
