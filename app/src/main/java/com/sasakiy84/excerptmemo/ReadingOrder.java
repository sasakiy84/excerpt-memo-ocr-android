package com.sasakiy84.excerptmemo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Whitespace cuts preserve columns; vertical columns are read from right to left. */
public final class ReadingOrder {
    private ReadingOrder() {}

    public static List<OcrLine> sort(List<OcrLine> lines) {
        boolean vertical = lines.stream().filter(OcrLine::vertical).count() > lines.size() / 2;
        return cut(new ArrayList<>(lines), vertical, 0);
    }

    private record Gap(float position, float score) {}

    private static Gap largestGap(List<OcrLine> lines, boolean xAxis) {
        List<OcrLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(line -> xAxis ? line.left() : line.top()));
        float start = xAxis ? sorted.get(0).left() : sorted.get(0).top();
        float end = xAxis ? sorted.get(0).right() : sorted.get(0).bottom();
        float gap = 0;
        float position = 0;
        for (OcrLine line : sorted) {
            float next = xAxis ? line.left() : line.top();
            if (next - end > gap) {
                gap = next - end;
                position = (next + end) / 2;
            }
            end = Math.max(end, xAxis ? line.right() : line.bottom());
        }
        return new Gap(position, gap);
    }

    private static List<OcrLine> cut(List<OcrLine> lines, boolean vertical, int depth) {
        if (lines.size() < 2) return lines;
        Gap x = largestGap(lines, true);
        Gap y = largestGap(lines, false);
        boolean xAxis = x.score > y.score;
        Gap gap = xAxis ? x : y;
        if (depth >= 32 || gap.score <= 0) {
            lines.sort(vertical
                    ? Comparator.comparingDouble(OcrLine::centerX).reversed().thenComparingDouble(OcrLine::top)
                    : Comparator.comparingDouble(OcrLine::top).thenComparingDouble(OcrLine::left));
            return lines;
        }
        List<OcrLine> before = new ArrayList<>();
        List<OcrLine> after = new ArrayList<>();
        for (OcrLine line : lines) {
            ((xAxis ? line.centerX() : line.centerY()) < gap.position ? before : after).add(line);
        }
        if (before.isEmpty() || after.isEmpty()) return lines;
        List<OcrLine> first = cut(before, vertical, depth + 1);
        List<OcrLine> second = cut(after, vertical, depth + 1);
        if (xAxis && vertical) { second.addAll(first); return second; }
        first.addAll(second);
        return first;
    }

    public static String text(List<OcrLine> lines) {
        return String.join("\n", lines.stream().map(OcrLine::text).toList());
    }
}
