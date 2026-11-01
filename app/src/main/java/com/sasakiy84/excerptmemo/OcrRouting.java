package com.sasakiy84.excerptmemo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Select one sequential pipeline from layout geometry, before recognizing text. */
final class OcrRouting {
    enum Route { NDL, PADDLE, FLATTEN_PADDLE }

    static float categoryOverlap(OcrLine recognized, OcrLine detected) {
        if (recognized.centerX() < detected.left() || recognized.centerX() > detected.right()
                || recognized.centerY() < detected.top() || recognized.centerY() > detected.bottom()) return 0;
        float overlap = Math.max(0, Math.min(recognized.right(), detected.right()) - Math.max(recognized.left(), detected.left()))
                * Math.max(0, Math.min(recognized.bottom(), detected.bottom()) - Math.max(recognized.top(), detected.top()));
        float area = Math.min((recognized.right() - recognized.left()) * (recognized.bottom() - recognized.top()),
                (detected.right() - detected.left()) * (detected.bottom() - detected.top()));
        return overlap / Math.max(1, area);
    }

    static Route choose(List<OcrLine> layout, int width, int height, boolean allowFlatten) {
        List<OcrLine> body = layout.stream().filter(l -> l.kind() == OcrLine.Kind.TEXT).toList();
        List<Float> verticalHeights = new ArrayList<>();
        List<OcrLine> horizontal = new ArrayList<>();
        int horizontalCount = 0;
        for (OcrLine line : body) {
            if (line.vertical()) verticalHeights.add(line.bottom() - line.top());
            else {
                horizontalCount++;
                if (line.right() - line.left() >= width * .3f) horizontal.add(line);
            }
        }
        if (verticalHeights.size() >= 3 && verticalHeights.size() >= body.size() * .7f
                && median(verticalHeights) > height * .35f) return Route.PADDLE;
        if (!allowFlatten || horizontal.size() < 8 || horizontalCount < body.size() * .6f) return Route.NDL;
        List<Float> overlapRatios = new ArrayList<>();
        for (OcrLine line : horizontal) {
            float pitch = Float.POSITIVE_INFINITY;
            for (OcrLine other : horizontal) {
                if (other == line) continue;
                float overlap = Math.min(other.right(), line.right()) - Math.max(other.left(), line.left());
                if (overlap < Math.min(other.right() - other.left(), line.right() - line.left()) * .8f) continue;
                float distance = Math.abs(other.centerY() - line.centerY());
                if (distance > Math.min(other.bottom() - other.top(), line.bottom() - line.top()) * .15f)
                    pitch = Math.min(pitch, distance);
            }
            if (Float.isFinite(pitch)) overlapRatios.add((line.bottom() - line.top()) / pitch);
        }
        // Bent/slanted rows have overlapping axis-aligned boxes; flat rows do not.
        return !overlapRatios.isEmpty() && median(overlapRatios) > 1.2f ? Route.FLATTEN_PADDLE : Route.NDL;
    }

    private static float median(List<Float> values) {
        Collections.sort(values);
        int n = values.size();
        return n % 2 == 0 ? (values.get(n / 2 - 1) + values.get(n / 2)) / 2 : values.get(n / 2);
    }
}
