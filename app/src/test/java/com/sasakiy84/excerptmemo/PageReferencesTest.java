package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class PageReferencesTest {
    private static OcrLine folio(float x, String number) { return new OcrLine(x, 950, x + 30, 980, number, OcrLine.Kind.PAGE_NUMBER); }
    private static final OcrLine LEFT = new OcrLine(100, 100, 400, 200, "左ページ");
    private static final OcrLine RIGHT = new OcrLine(650, 100, 950, 200, "右ページ");

    @Test public void oneDetectedFolioIsAppendedEvenThoughItIsExcludedFromBody() {
        assertEquals("（p. 12）", new PageReferences(List.of(folio(500, "１２")), 1000).suffix(List.of(LEFT)));
    }
    @Test public void selectionOnOnlyOneSideUsesOnlyThatPagesFolio() {
        var pages = new PageReferences(List.of(folio(100, "13"), folio(850, "12")), 1000);
        assertEquals("（p. 12）", pages.suffix(List.of(RIGHT)));
        assertEquals("（p. 13）", pages.suffix(List.of(LEFT)));
    }
    @Test public void selectionAcrossSpreadUsesBothPrintedFolios() {
        var pages = new PageReferences(List.of(folio(100, "13"), folio(850, "12")), 1000);
        assertEquals("（pp. 12–13）", pages.suffix(List.of(RIGHT, LEFT)));
        assertEquals(java.util.List.of(RIGHT, LEFT), pages.order(List.of(LEFT, RIGHT)));
    }
    @Test public void nonConsecutiveFoliosDoNotInventInterveningPages() {
        var pages = new PageReferences(List.of(folio(100, "12"), folio(850, "15")), 1000);
        assertEquals("（pp. 12, 15）", pages.suffix(List.of(LEFT, RIGHT)));
    }
    @Test public void ambiguousNumbersAreNotAppended() {
        assertTrue(new PageReferences(List.of(folio(100, "12"), folio(150, "13")), 1000).suffix(List.of(LEFT)).isEmpty());
        assertTrue(new PageReferences(List.of(folio(100, "12"), folio(500, "13"), folio(850, "14")), 1000).suffix(List.of(LEFT)).isEmpty());
        assertTrue(new PageReferences(List.of(folio(100, "12"), folio(850, "12")), 1000).suffix(List.of(LEFT)).isEmpty());
    }
    @Test public void missingFolioAndEmptySelectionProduceNoReference() {
        assertTrue(new PageReferences(List.of(), 1000).suffix(List.of(LEFT)).isEmpty());
        assertTrue(new PageReferences(List.of(folio(100, "12")), 1000).suffix(List.of()).isEmpty());
        assertTrue(new PageReferences(List.of(folio(100, "第12章")), 1000).suffix(List.of(LEFT)).isEmpty());
    }
}
