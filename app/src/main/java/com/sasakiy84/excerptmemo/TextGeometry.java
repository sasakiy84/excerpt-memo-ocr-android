package com.sasakiy84.excerptmemo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.icu.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Estimates grapheme positions inside the OCR line boxes. */
final class TextGeometry {
    final List<OcrLine> lines;
    final String text;
    final int[] starts;
    private final int[][] boundaries;
    private final float[][] positions;
    private final VerticalInkGeometry.Stripe[] stripes;

    TextGeometry(List<OcrLine> lines) {
        this(lines, Map.of());
    }

    TextGeometry(List<OcrLine> lines, Map<OcrLine, VerticalInkGeometry.Stripe> verticalStripes) {
        this.lines = lines;
        text = ReadingOrder.text(lines);
        starts = new int[lines.size()];
        boundaries = new int[lines.size()][];
        positions = new float[lines.size()][];
        stripes = new VerticalInkGeometry.Stripe[lines.size()];
        Paint font = new Paint(Paint.ANTI_ALIAS_FLAG);
        font.setTextSize(100);
        int offset = 0;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            stripes[i] = verticalStripes.get(line);
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
            if (end && i > 0 && offset == starts[i]) return i - 1;
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

    int nextCharacterEnd(int start) {
        if (start >= length()) return length();
        return next(Math.max(start, starts[lineAt(start, false)]));
    }

    int previousCharacterStart(int end) {
        if (end <= 0) return 0;
        int line = lineAt(end, true);
        return previous(Math.min(end, starts[line] + lines.get(line).text().length()));
    }

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
        return line.vertical() ? new PointF(centerX(index, fraction), line.top() + (line.bottom() - line.top()) * fraction)
                : new PointF(line.left() + (line.right() - line.left()) * fraction, line.bottom());
    }

    boolean verticalEndpoint(int offset, boolean end) {
        int index = lineAt(offset, end);
        return index >= 0 && lines.get(index).vertical();
    }

    private static float verticalFraction(OcrLine line, float y) {
        return Math.max(0, Math.min(1, (y - line.top()) / (line.bottom() - line.top())));
    }

    private float centerX(int index, float fraction) {
        VerticalInkGeometry.Stripe stripe = stripes[index];
        return stripe == null ? lines.get(index).centerX() : stripe.centerAt(fraction);
    }

    void drawRegion(Canvas canvas, Paint paint, Path path, int index, RectF rect) {
        if (rect.isEmpty()) return;
        VerticalInkGeometry.Stripe stripe = stripes[index];
        if (stripe == null) {
            canvas.drawRect(rect, paint);
            return;
        }
        OcrLine line = lines.get(index);
        float top = stripe.centerAt(verticalFraction(line, rect.top));
        float bottom = stripe.centerAt(verticalFraction(line, rect.bottom));
        float halfWidth = stripe.halfWidth();
        path.rewind();
        path.moveTo(top - halfWidth, rect.top);
        path.lineTo(top + halfWidth, rect.top);
        path.lineTo(bottom + halfWidth, rect.bottom);
        path.lineTo(bottom - halfWidth, rect.bottom);
        path.close();
        canvas.drawPath(path, paint);
    }

    int nearest(float x, float y, boolean boundary) {
        int nearestLine = -1;
        float distance = Float.MAX_VALUE;
        float centerDistance = Float.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            VerticalInkGeometry.Stripe stripe = stripes[i];
            float crossDistance = line.vertical() ? Math.abs(x - centerX(i, verticalFraction(line, y)))
                    : Math.abs(y - line.centerY());
            float dx = stripe == null ? Math.max(0, Math.max(line.left() - x, x - line.right()))
                    : Math.max(0, crossDistance - stripe.halfWidth());
            float dy = Math.max(0, Math.max(line.top() - y, y - line.bottom()));
            float squared = dx * dx + dy * dy;
            if (squared < distance || (squared == distance && crossDistance < centerDistance)) {
                distance = squared;
                centerDistance = crossDistance;
                nearestLine = i;
            }
        }
        return nearestLine < 0 ? 0 : offsetOnLine(nearestLine, x, y, boundary);
    }

    int offsetOnLine(int index, float x, float y, boolean boundary) {
        OcrLine line = lines.get(index);
        if (line.vertical()) {
            int count = boundaries[index].length - 1;
            float character = verticalFraction(line, y) * count;
            int chosen = boundary ? Math.round(character) : Math.min(Math.max(0, count - 1), (int) character);
            return starts[index] + boundaries[index][chosen];
        }
        float position = (x - line.left()) / (line.right() - line.left());
        float[] points = positions[index];
        int chosen = 0;
        if (boundary) {
            for (int i = 1; i < points.length; i++) if (Math.abs(points[i] - position) < Math.abs(points[chosen] - position)) chosen = i;
        } else {
            while (chosen + 1 < points.length - 1 && position >= points[chosen + 1]) chosen++;
        }
        return starts[index] + boundaries[index][chosen];
    }
}
