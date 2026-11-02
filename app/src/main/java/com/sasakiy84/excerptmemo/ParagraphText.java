package com.sasakiy84.excerptmemo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Paragraph cues use the original line boxes, including when only part of a line is selected. */
final class ParagraphText {
    record Result(String text, int[] origins) {}
    static Result extract(List<OcrLine> lines, int start, int end) {
        StringBuilder text = new StringBuilder();
        List<Integer> origins = new ArrayList<>();
        int offset = 0, previous = -1;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            int from = Math.max(0, start - offset), to = Math.min(line.text().length(), end - offset);
            if (from < to) {
                if (previous >= 0) {
                    String separator = boundary(lines, previous, i) ? "\n" : wordSeparator(text, line.text().charAt(from));
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
    private static float thickness(OcrLine l, boolean vertical) { return vertical ? l.right() - l.left() : l.bottom() - l.top(); }
    private static float advance(OcrLine l, boolean vertical) {
        return Math.max(thickness(l, vertical) * .8f,
                (end(l, vertical) - start(l, vertical)) / Math.max(1, l.text().codePointCount(0, l.text().length())));
    }
    private static boolean boundary(List<OcrLine> lines, int before, int after) {
        OcrLine a = lines.get(before), b = lines.get(after);
        boolean vertical = a.vertical();
        if (a.kind() != OcrLine.Kind.TEXT || b.kind() != OcrLine.Kind.TEXT || vertical != b.vertical()) return true;
        float thick = (thickness(a, vertical) + thickness(b, vertical)) / 2;
        float gap = vertical ? a.left() - b.right() : b.top() - a.bottom();
        if (gap < -thick * .3f || gap > thick * 1.2f
                || Math.max(thickness(a, vertical), thickness(b, vertical)) > Math.min(thickness(a, vertical), thickness(b, vertical)) * 1.6f) return true;
        float em = Math.max(advance(a, vertical), advance(b, vertical));
        if (Math.abs(start(a, vertical) - start(b, vertical)) > em * 3) return true;
        float base = Math.min(start(a, vertical), start(b, vertical)), fullEnd = Math.max(end(a, vertical), end(b, vertical));
        float[] widths = new float[Math.min(lines.size(), after + 5) - Math.max(0, before - 4)];
        int count = 0;
        for (int i = Math.max(0, before - 4); i < Math.min(lines.size(), after + 5); i++) {
            OcrLine candidate = lines.get(i);
            if (candidate.kind() != OcrLine.Kind.TEXT || candidate.vertical() != vertical
                    || Math.max(thickness(candidate, vertical), thick) > Math.min(thickness(candidate, vertical), thick) * 1.6f) continue;
            float step = vertical ? Math.abs(candidate.centerX() - b.centerX()) : Math.abs(candidate.centerY() - b.centerY());
            if (step > thick * 10 || Math.abs(start(candidate, vertical) - start(b, vertical)) > em * 3) continue;
            base = Math.min(base, start(candidate, vertical)); fullEnd = Math.max(fullEnd, end(candidate, vertical));
            widths[count++] = advance(candidate, vertical);
        }
        if (count > 0) { Arrays.sort(widths, 0, count); em = widths[count / 2]; }
        boolean shortPrevious = fullEnd - end(a, vertical) > em * .8f;
        boolean indentedStart = start(b, vertical) - base >= em * .55f && start(b, vertical) - start(a, vertical) >= em * .55f;
        return shortPrevious || indentedStart;
    }
}
