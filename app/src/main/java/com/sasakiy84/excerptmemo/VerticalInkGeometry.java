package com.sasakiy84.excerptmemo;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Estimates the text stripe inside a vertical OCR box from the photograph. */
final class VerticalInkGeometry {
    record Stripe(float topCenter, float bottomCenter, float halfWidth) {
        float centerAt(float fraction) {
            return topCenter + (bottomCenter - topCenter) * fraction;
        }
    }

    static Map<OcrLine, Stripe> estimate(Bitmap page, List<OcrLine> lines) {
        if (page.getConfig() == Bitmap.Config.HARDWARE) return Map.of();
        Map<OcrLine, Stripe> stripes = new HashMap<>();
        for (OcrLine line : lines) {
            if (!line.vertical() || line.text().isBlank()) continue;
            Stripe stripe = estimate(page, line);
            if (stripe != null) stripes.put(line, stripe);
        }
        return stripes;
    }

    private static Stripe estimate(Bitmap page, OcrLine line) {
        int left = Math.max(0, (int) Math.floor(line.left()));
        int right = Math.min(page.getWidth(), (int) Math.ceil(line.right()));
        int top = Math.max(0, (int) Math.floor(line.top()));
        int bottom = Math.min(page.getHeight(), (int) Math.ceil(line.bottom()));
        int width = right - left, height = bottom - top;
        if (width < 4 || height < Math.max(24, width * 2)) return null;
        int count = line.text().codePointCount(0, line.text().length());
        float halfWidth = Math.min(width / 2f, Math.max(width / 4f, height * .52f / count));
        int strips = Math.min(12, height / 8);
        int step = Math.max(1, (width + 31) / 32), candidates = (width - 1) / step + 1;
        float[][] scores = new float[strips][candidates];
        int[] pixels = new int[width];
        int[] gray = new int[width];
        int supportedStrips = 0;
        for (int i = 0; i < strips; i++) {
            int[] ink = new int[width];
            int from = top + height * i / strips, to = top + height * (i + 1) / strips;
            int rowStep = Math.max(1, (to - from) / 24);
            for (int y = from; y < to; y += rowStep) {
                page.getPixels(pixels, 0, width, left, y, width, 1);
                int paper = 0;
                for (int x = 0; x < width; x++) {
                    int color = pixels[x];
                    gray[x] = (((color >> 16) & 255) * 77 + ((color >> 8) & 255) * 150 + (color & 255) * 29) >> 8;
                    paper = Math.max(paper, gray[x]);
                }
                for (int x = 0; x < width; x++) ink[x] += Math.max(0, paper - gray[x] - 12);
            }
            float peak = 0;
            for (int j = 0; j < candidates; j++) {
                int center = j * step;
                for (int x = Math.max(0, center - (int) halfWidth); x <= Math.min(width - 1, center + (int) halfWidth); x++) {
                    scores[i][j] += ink[x] * (1 - Math.abs(x - center) / halfWidth);
                }
                peak = Math.max(peak, scores[i][j]);
            }
            if (peak < ((to - from) / (float) rowStep) * 12) {
                // A strip with too little contrast should not decide the direction of the column.
                Arrays.fill(scores[i], 0);
                continue;
            }
            supportedStrips++;
            for (int j = 0; j < candidates; j++) scores[i][j] /= peak;
        }
        if (supportedStrips * 2 < strips) return null;

        // Compare straight paths through the upper and lower edges of the bounding box.
        float best = 0;
        int first = 0, last = 0;
        for (int a = 0; a < candidates; a++) {
            for (int b = 0; b < candidates; b++) {
                float score = -.03f * Math.abs((a + b) - (candidates - 1)) / candidates;
                for (int i = 0; i < strips; i++) {
                    int center = Math.round(a + (b - a) * (i + .5f) / strips);
                    score += scores[i][center];
                }
                if (score > best) {
                    best = score;
                    first = a;
                    last = b;
                }
            }
        }
        if (best < supportedStrips * .65f) return null;
        return new Stripe(left + first * step + .5f, left + last * step + .5f, halfWidth);
    }
}
