package com.sasakiy84.excerptmemo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Whitespace cuts preserve columns; vertical columns are read from right to left. */
public final class ReadingOrder {
    private ReadingOrder() {}

    public static List<OcrLine> sort(List<OcrLine> lines) {
        boolean hasBody = lines.stream().anyMatch(line -> line.kind() == OcrLine.Kind.TEXT);
        float verticalLength = 0, horizontalLength = 0;
        for (OcrLine line : lines) {
            if (hasBody && line.kind() != OcrLine.Kind.TEXT) continue;
            if (line.vertical()) verticalLength += line.bottom() - line.top();
            else horizontalLength += line.right() - line.left();
        }
        return cut(new ArrayList<>(lines), verticalLength > horizontalLength, 0, false);
    }

    // Saved selection offsets from v0.11.1 and earlier refer to this order.
    static List<OcrLine> legacySort(List<OcrLine> lines) {
        boolean vertical = lines.stream().filter(OcrLine::vertical).count() > lines.size() / 2;
        return cut(new ArrayList<>(lines), vertical, 0, true);
    }
    private record Gap(float position, float score) {}

    private static Gap largestGap(List<OcrLine> lines, boolean xAxis) {
        List<OcrLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(line -> xAxis ? line.left() : line.top()));
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

    private static List<OcrLine> cut(List<OcrLine> lines, boolean vertical, int depth, boolean legacy) {
        if (lines.size() < 2) return lines;
        Gap x = largestGap(lines, true);
        Gap y = largestGap(lines, false);
        boolean xAxis = x.score > y.score;
        // Staggered vertical columns may have a large vertical gap but still read right to left.
        if (!legacy && vertical && !xAxis && x.score > 0 && y.score > 0) {
            xAxis = separateVerticalColumns(lines, y.position);
        }
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
        List<OcrLine> first = cut(before, vertical, depth + 1, legacy);
        List<OcrLine> second = cut(after, vertical, depth + 1, legacy);
        if (xAxis && vertical) { second.addAll(first); return second; }
        first.addAll(second);
        return first;
    }

    private static boolean separateVerticalColumns(List<OcrLine> lines, float splitY) {
        float upperLeft = Float.POSITIVE_INFINITY, upperRight = Float.NEGATIVE_INFINITY;
        float lowerLeft = Float.POSITIVE_INFINITY, lowerRight = Float.NEGATIVE_INFINITY;
        for (OcrLine line : lines) {
            if (line.kind() != OcrLine.Kind.TEXT || !line.vertical()) continue;
            if (line.centerY() < splitY) {
                upperLeft = Math.min(upperLeft, line.left());
                upperRight = Math.max(upperRight, line.right());
            } else {
                lowerLeft = Math.min(lowerLeft, line.left());
                lowerRight = Math.max(lowerRight, line.right());
            }
        }
        return Float.isFinite(upperLeft) && Float.isFinite(lowerLeft)
                && (upperRight <= lowerLeft || lowerRight <= upperLeft);
    }

    public static String text(List<OcrLine> lines) {
        return String.join("\n", lines.stream().map(OcrLine::text).toList());
    }
}
