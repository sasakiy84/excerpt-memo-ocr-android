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

    static Route choose(List<OcrLine> layout, int width, boolean alreadyRectified, OcrLanguage language) {
        List<OcrLine> horizontal = new ArrayList<>();
        int verticalCount = 0, horizontalCount = 0;
        for (OcrLine line : layout) {
            if (line.kind() != OcrLine.Kind.TEXT) continue;
            if (line.vertical()) verticalCount++;
            else {
                horizontalCount++;
                if (line.right() - line.left() >= width * .3f) horizontal.add(line);
            }
        }
        boolean japanese = language == OcrLanguage.JAPANESE;
        if (japanese && verticalCount > horizontalCount) return Route.NDL;
        if (alreadyRectified) return Route.FLATTEN_PADDLE;
        Route defaultRoute = japanese ? Route.NDL : Route.PADDLE;
        if (horizontal.size() < 8 || horizontalCount < (horizontalCount + verticalCount) * .6f) return defaultRoute;
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
        return !overlapRatios.isEmpty() && median(overlapRatios) > 1.2f ? Route.FLATTEN_PADDLE : defaultRoute;
    }

    private static float median(List<Float> values) {
        Collections.sort(values);
        int n = values.size();
        return n % 2 == 0 ? (values.get(n / 2 - 1) + values.get(n / 2)) / 2 : values.get(n / 2);
    }
}
