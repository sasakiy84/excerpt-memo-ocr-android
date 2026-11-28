package com.sasakiy84.excerptmemo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Paragraph cues use the original line boxes, including when only part of a line is selected. */
final class ParagraphText {
    record Result(String text, int[] origins) {}

    static Result extract(List<OcrLine> lines, int start, int end) {
        return extract(lines, start, end, Map.of(), Float.NaN);
    }

    static Result extract(List<OcrLine> lines, int start, int end,
                          Map<OcrLine, VerticalInkGeometry.Stripe> stripes, float gutter) {
        return extract(lines, start, end, stripes, gutter, false);
    }

    static Result extractLegacy(List<OcrLine> lines, int start, int end) {
        return extract(lines, start, end, Map.of(), Float.NaN, true);
    }

    private static Result extract(List<OcrLine> lines, int start, int end,
                                  Map<OcrLine, VerticalInkGeometry.Stripe> stripes, float gutter, boolean legacy) {
        StringBuilder text = new StringBuilder();
        List<Integer> origins = new ArrayList<>();
        int offset = 0, previous = -1;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            int from = Math.max(0, start - offset), to = Math.min(line.text().length(), end - offset);
            if (from < to) {
                if (previous >= 0) {
                    String separator = boundary(lines, previous, i, stripes, gutter, legacy)
                            ? "\n" : wordSeparator(text, line.text().charAt(from));
                    text.append(separator);
                    for (int j = 0; j < separator.length(); j++) origins.add(offset - 1);
                }
                text.append(line.text(), from, to);
                for (int j = from; j < to; j++) origins.add(offset + j);
                previous = i;
            }
            offset += line.text().length() + 1;
        }
        return new Result(text.toString(), origins.stream().mapToInt(Integer::intValue).toArray());
    }
    private static String wordSeparator(StringBuilder text, char next) {
        char last = text.charAt(text.length() - 1);
        if (Character.isWhitespace(last) || Character.isWhitespace(next)) return "";
        boolean latinNext = isLatinWord(next);
        boolean latinLast = isLatinWord(last) || ".,;:!?\"'’”)]".indexOf(last) >= 0;
        return latinNext && latinLast ? " " : "";
    }
    private static boolean isLatinWord(char c) {
        return (c >= '0' && c <= '9') || Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN;
    }
    private static float start(OcrLine l, boolean vertical) { return vertical ? l.top() : l.left(); }
    private static float end(OcrLine l, boolean vertical) { return vertical ? l.bottom() : l.right(); }

    private static float estimatedAdvance(OcrLine line, boolean vertical) {
        return (end(line, vertical) - start(line, vertical))
                / Math.max(1, line.text().codePointCount(0, line.text().length()));
    }

    private static float thickness(OcrLine line, boolean vertical,
                                   Map<OcrLine, VerticalInkGeometry.Stripe> stripes, boolean legacy) {
        if (!vertical) return line.bottom() - line.top();
        float width = line.right() - line.left();
        if (legacy) return width;
        VerticalInkGeometry.Stripe stripe = stripes.get(line);
        return stripe != null ? stripe.halfWidth() * 2 : Math.min(width, estimatedAdvance(line, true) * 1.2f);
    }

    private static float columnCenter(OcrLine line, Map<OcrLine, VerticalInkGeometry.Stripe> stripes, float y) {
        VerticalInkGeometry.Stripe stripe = stripes.get(line);
        if (stripe == null) return line.centerX();
        float fraction = (y - line.top()) / (line.bottom() - line.top());
        return stripe.centerAt(Math.max(0, Math.min(1, fraction)));
    }

    private static boolean boundary(List<OcrLine> lines, int before, int after,
                                    Map<OcrLine, VerticalInkGeometry.Stripe> stripes, float gutter, boolean legacy) {
        OcrLine a = lines.get(before), b = lines.get(after);
        boolean vertical = a.vertical();
        if (a.kind() != OcrLine.Kind.TEXT || b.kind() != OcrLine.Kind.TEXT || vertical != b.vertical()) return true;
        if (Float.isFinite(gutter) && (a.centerX() < gutter) != (b.centerX() < gutter)) return true;
        float widthA = thickness(a, vertical, stripes, legacy);
        float widthB = thickness(b, vertical, stripes, legacy);
        float thick = (widthA + widthB) / 2;
        float em = Math.max(Math.max(widthA * .8f, estimatedAdvance(a, vertical)),
                Math.max(widthB * .8f, estimatedAdvance(b, vertical)));
        float gap = vertical ? a.left() - b.right() : b.top() - a.bottom();
        if (vertical && !legacy) {
            // Slant inflates the OCR boxes. Compare the columns at the same height.
            float y = (Math.max(a.top(), b.top()) + Math.min(a.bottom(), b.bottom())) / 2;
            float distance = columnCenter(a, stripes, y) - columnCenter(b, stripes, y);
            float fragmentGap = b.top() - a.bottom();
            if (Math.abs(distance) <= Math.min(widthA, widthB) * .5f
                    && fragmentGap >= -em * .3f && fragmentGap <= em * 1.25f) return false;
            gap = distance - thick;
        }
        if (gap < -thick * .3f || gap > thick * 1.8f
                || Math.max(widthA, widthB) > Math.min(widthA, widthB) * 1.6f) return true;
        if (Math.abs(start(a, vertical) - start(b, vertical)) > em * 3) return true;
        float base = Math.min(start(a, vertical), start(b, vertical)), fullEnd = Math.max(end(a, vertical), end(b, vertical));
        float[] widths = new float[Math.min(lines.size(), after + 5) - Math.max(0, before - 4)];
        int count = 0;
        for (int i = Math.max(0, before - 4); i < Math.min(lines.size(), after + 5); i++) {
            OcrLine candidate = lines.get(i);
            if (candidate.kind() != OcrLine.Kind.TEXT || candidate.vertical() != vertical) continue;
            float width = thickness(candidate, vertical, stripes, legacy);
            if (Math.max(width, thick) > Math.min(width, thick) * 1.6f) continue;
            float step = vertical ? Math.abs(candidate.centerX() - b.centerX()) : Math.abs(candidate.centerY() - b.centerY());
            if (step > thick * 10 || Math.abs(start(candidate, vertical) - start(b, vertical)) > em * 3) continue;
            base = Math.min(base, start(candidate, vertical)); fullEnd = Math.max(fullEnd, end(candidate, vertical));
            widths[count++] = Math.max(width * .8f, estimatedAdvance(candidate, vertical));
        }
        if (count > 0) { Arrays.sort(widths, 0, count); em = widths[count / 2]; }
        // Small ragged ends and OCR box drift are common within a paragraph.
        // Require a clearly short line or a near-full-character indent to split.
        boolean shortPrevious = fullEnd - end(a, vertical) > em * 2;
        boolean indentedStart = start(b, vertical) - base >= em * .8f && start(b, vertical) - start(a, vertical) >= em * .8f;
        return shortPrevious || indentedStart;
    }
}
