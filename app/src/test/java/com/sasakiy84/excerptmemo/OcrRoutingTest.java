package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class OcrRoutingTest {
    @Test public void paddedSmallFolioMatchesWithoutMisclassifyingTheSurroundingBodyColumn() {
        OcrLine folio = new OcrLine(474, 701, 484, 709, "32", OcrLine.Kind.PAGE_NUMBER);
        assertThat(OcrRouting.categoryOverlap(new OcrLine(472, 699, 487, 711, "32"), folio)).isEqualTo(1f);
        assertThat(OcrRouting.categoryOverlap(new OcrLine(470, 100, 492, 750, "本文"), folio)).isEqualTo(0f);
    }
    @Test public void longUnbrokenVerticalColumnsUsePaddle() {
        List<OcrLine> lines = new ArrayList<>();
        for (int x = 100; x < 400; x += 40) lines.add(new OcrLine(x, 100, x + 20, 950, "本文"));
        assertThat(OcrRouting.choose(lines, 900, 1200, true)).isEqualTo(OcrRouting.Route.PADDLE);
    }
    @Test public void newspaperColumnsStayWithNdlLayoutAndRecognition() {
        List<OcrLine> lines = new ArrayList<>();
        for (int y = 100; y < 900; y += 230) for (int x = 100; x < 400; x += 40)
            lines.add(new OcrLine(x, y, x + 20, y + 180, "本文"));
        assertThat(OcrRouting.choose(lines, 900, 1200, true)).isEqualTo(OcrRouting.Route.NDL);
    }
    @Test public void overlappingLongHorizontalRowsCanBeFlattenedOnce() {
        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) lines.add(new OcrLine(100, 100 + i * 25, 700, 180 + i * 25, "本文"));
        assertThat(OcrRouting.choose(lines, 900, 1200, true)).isEqualTo(OcrRouting.Route.FLATTEN_PADDLE);
        assertThat(OcrRouting.choose(lines, 900, 1200, false)).isEqualTo(OcrRouting.Route.NDL);
    }
    @Test public void flatHorizontalRowsDoNotSpendEnergyOnUnwarping() {
        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) lines.add(new OcrLine(100, 100 + i * 40, 700, 120 + i * 40, "本文"));
        assertThat(OcrRouting.choose(lines, 900, 1200, true)).isEqualTo(OcrRouting.Route.NDL);
        assertThat(OcrRouting.choose(List.of(), 900, 1200, true)).isEqualTo(OcrRouting.Route.NDL);
    }
    @Test public void ctcDecodingCollapsesRepeatsButRetainsRepeatsSeparatedByBlank() {
        int[] ids = {1, 1, 0, 1, 3, 0, 2};
        float[] scores = new float[ids.length * 4];
        for (int i = 0; i < ids.length; i++) scores[i * 4 + ids[i]] = 1;
        assertThat(PaddleOcr.decode(FloatBuffer.wrap(scores), ids.length, 4, new String[]{"", "あ", "いう", "😀"})).isEqualTo("ああ😀いう");
    }
}
