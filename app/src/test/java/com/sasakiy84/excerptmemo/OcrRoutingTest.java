package com.sasakiy84.excerptmemo;

import static org.junit.Assert.*;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class OcrRoutingTest {
    @Test public void paddedSmallFolioMatchesWithoutMisclassifyingTheSurroundingBodyColumn() {
        OcrLine folio = new OcrLine(474, 701, 484, 709, "32", OcrLine.Kind.PAGE_NUMBER);
        assertEquals(1f, OcrRouting.categoryOverlap(new OcrLine(472, 699, 487, 711, "32"), folio), 0f);
        assertEquals(0f, OcrRouting.categoryOverlap(new OcrLine(470, 100, 492, 750, "本文"), folio), 0f);
    }
    @Test public void longVerticalColumnsUseNdlWithoutAHeading() {
        List<OcrLine> lines = new ArrayList<>();
        for (int x = 100; x < 400; x += 40) lines.add(new OcrLine(x, 100, x + 20, 950, "本文"));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(lines, 900, false, OcrLanguage.JAPANESE));
    }
    @Test public void horizontalChapterHeadingAboveVerticalColumnsKeepsNdlLayout() {
        List<OcrLine> lines = new ArrayList<>();
        for (int x = 100; x < 600; x += 40) lines.add(new OcrLine(x, 180, x + 20, 1000, "本文"));
        lines.add(new OcrLine(95, 140, 270, 162, "横書きの章見出し"));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(lines, 900, false, OcrLanguage.JAPANESE));
    }
    @Test public void rectifiedVerticalColumnsStillUseNdl() {
        List<OcrLine> lines = new ArrayList<>();
        lines.add(new OcrLine(100, 150, 125, 410, "本文"));
        lines.add(new OcrLine(140, 140, 165, 420, "本文"));
        lines.add(new OcrLine(95, 60, 270, 82, "見出し"));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(lines, 900, true, OcrLanguage.JAPANESE));
    }
    @Test public void newspaperColumnsStayWithNdlLayoutAndRecognition() {
        List<OcrLine> lines = new ArrayList<>();
        for (int y = 100; y < 900; y += 230) for (int x = 100; x < 400; x += 40)
            lines.add(new OcrLine(x, y, x + 20, y + 180, "本文"));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(lines, 900, false, OcrLanguage.JAPANESE));
    }
    @Test public void overlappingLongHorizontalRowsCanBeFlattenedOnce() {
        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) lines.add(new OcrLine(100, 100 + i * 25, 700, 180 + i * 25, "本文"));
        assertEquals(OcrRouting.Route.FLATTEN_PADDLE, OcrRouting.choose(lines, 900, false, OcrLanguage.JAPANESE));
    }
    @Test public void flatHorizontalRowsDoNotSpendEnergyOnUnwarping() {
        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) lines.add(new OcrLine(100, 100 + i * 40, 700, 120 + i * 40, "本文"));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(lines, 900, false, OcrLanguage.JAPANESE));
        assertEquals(OcrRouting.Route.NDL, OcrRouting.choose(List.of(), 900, false, OcrLanguage.JAPANESE));
    }
    @Test public void ctcDecodingCollapsesRepeatsButRetainsRepeatsSeparatedByBlank() {
        int[] ids = {1, 1, 0, 1, 3, 0, 2};
        float[] scores = new float[ids.length * 4];
        for (int i = 0; i < ids.length; i++) scores[i * 4 + ids[i]] = 1;
        assertEquals("ああ😀いう", PaddleOcr.decode(FloatBuffer.wrap(scores), ids.length, 4, new String[]{"", "あ", "いう", "😀"}));
    }
}
