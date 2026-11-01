package com.sasakiy84.excerptmemo;

import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maps grapheme boundaries to the line boxes supplied by PARSeq, which has no character boxes. */
final class TextGeometry {
    final List<OcrLine> lines;
    final String text;
    final int[] starts;
    private final int[][] boundaries;
    private final float[][] positions;

    TextGeometry(List<OcrLine> lines) {
        this.lines = lines;
        text = ReadingOrder.text(lines);
        starts = new int[lines.size()];
        boundaries = new int[lines.size()][];
        positions = new float[lines.size()][];
        Paint font = new Paint(Paint.ANTI_ALIAS_FLAG);
        font.setTextSize(100);
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            starts[i] = offset;
            offset += line.text().length() + 1;
            BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(line.text());
            List<Integer> breaks = new ArrayList<>();
            for (int next = iterator.first(); next != BreakIterator.DONE; next = iterator.next()) breaks.add(next);
            boundaries[i] = breaks.stream().mapToInt(Integer::intValue).toArray();
            positions[i] = new float[breaks.size()];
            float total = 0;
            for (int j = 1; j < breaks.size(); j++) {
                total += line.vertical() ? 1 : Math.max(1, font.measureText(line.text(), breaks.get(j - 1), breaks.get(j)));
                positions[i][j] = total;
            }
            if (total > 0) for (int j = 0; j < breaks.size(); j++) positions[i][j] /= total;
        }
    }

    int length() { return text.length(); }

    int lineAt(int offset, boolean end) {
        for (int i = 0; i < lines.size(); i++) {
            int limit = starts[i] + lines.get(i).text().length();
            if (offset < limit || (end && offset == limit)) return i;
        }
        return lines.size() - 1;
    }

    int snap(int offset, boolean forward) {
        offset = Math.max(0, Math.min(length(), offset));
        if (lines.isEmpty()) return 0;
        int line = 0;
        while (line + 1 < lines.size() && offset > starts[line] + lines.get(line).text().length()) line++;
        int local = offset - starts[line];
        int previous = 0;
        for (int boundary : boundaries[line]) {
            if (boundary == local) return starts[line] + boundary;
            if (boundary > local) return starts[line] + (forward ? boundary : previous);
            previous = boundary;
        }
        return starts[line] + previous;
    }

    int next(int offset) { return snap(Math.min(length(), offset + 1), true); }
    int previous(int offset) { return snap(Math.max(0, offset - 1), false); }

    private float fraction(int line, int offset) {
        int local = offset - starts[line];
        for (int j = 0; j < boundaries[line].length; j++) if (boundaries[line][j] >= local) return positions[line][j];
        return 1;
    }

    RectF highlight(int lineIndex, int start, int end) {
        OcrLine line = lines.get(lineIndex);
        int from = Math.max(starts[lineIndex], start);
        int to = Math.min(starts[lineIndex] + line.text().length(), end);
        if (from >= to) return new RectF();
        float a = fraction(lineIndex, from), b = fraction(lineIndex, to);
        return line.vertical()
                ? new RectF(line.left(), line.top() + (line.bottom() - line.top()) * a,
                        line.right(), line.top() + (line.bottom() - line.top()) * b)
                : new RectF(line.left() + (line.right() - line.left()) * a, line.top(),
                        line.left() + (line.right() - line.left()) * b, line.bottom());
    }

    PointF endpoint(int offset, boolean end) {
        int index = lineAt(offset, end);
        if (index < 0) return new PointF();
        OcrLine line = lines.get(index);
        float fraction = fraction(index, offset);
        return line.vertical() ? new PointF(line.right(), line.top() + (line.bottom() - line.top()) * fraction)
                : new PointF(line.left() + (line.right() - line.left()) * fraction, line.bottom());
    }

    int nearest(float x, float y, boolean boundary) {
        int nearestLine = -1;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            float dx = Math.max(Math.max(line.left() - x, 0), x - line.right());
            float dy = Math.max(Math.max(line.top() - y, 0), y - line.bottom());
            float squared = dx * dx + dy * dy;
            if (squared < distance) { distance = squared; nearestLine = i; }
        }
        if (nearestLine < 0) return 0;
        OcrLine line = lines.get(nearestLine);
        float position = line.vertical() ? (y - line.top()) / (line.bottom() - line.top())
                : (x - line.left()) / (line.right() - line.left());
        float[] points = positions[nearestLine];
        int chosen = 0;
        if (boundary) {
            for (int i = 1; i < points.length; i++) if (Math.abs(points[i] - position) < Math.abs(points[chosen] - position)) chosen = i;
        } else {
            while (chosen + 1 < points.length - 1 && position >= points[chosen + 1]) chosen++;
        }
        return starts[nearestLine] + boundaries[nearestLine][chosen];
    }
}
