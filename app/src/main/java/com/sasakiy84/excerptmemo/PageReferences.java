package com.sasakiy84.excerptmemo;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses only detected folios; missing or ambiguous page numbers are never inferred. */
final class PageReferences {
    private record Folio(int number, float x) {}
    private static final Pattern NUMBER = Pattern.compile("(?:[pP]\\.?\\s*)?[-–—(（]?\\s*([0-9]{1,6})\\s*[-–—)）]?");
    private final List<Folio> folios = new ArrayList<>();
    private final float width;

    PageReferences(List<OcrLine> lines, float width) {
        this.width = width;
        for (OcrLine line : lines) {
            if (line.kind() != OcrLine.Kind.PAGE_NUMBER) continue;
            Matcher match = NUMBER.matcher(Normalizer.normalize(line.text().strip(), Normalizer.Form.NFKC));
            if (!match.matches()) continue;
            int number = Integer.parseInt(match.group(1));
            if (number > 0) folios.add(new Folio(number, line.centerX()));
        }
        folios.sort(Comparator.comparingDouble(Folio::x));
    }

    boolean spread() {
        return folios.size() == 2 && folios.get(0).number != folios.get(1).number
                && folios.get(1).x - folios.get(0).x > width * 0.25f;
    }

    float gutter() { return (folios.get(0).x + folios.get(1).x) / 2; }

    List<OcrLine> order(List<OcrLine> lines) {
        if (!spread()) return ReadingOrder.sort(lines);
        List<OcrLine> left = new ArrayList<>(), right = new ArrayList<>();
        for (OcrLine line : lines) (line.centerX() < gutter() ? left : right).add(line);
        // The printed folios determine the page order, including right-bound books.
        List<OcrLine> first = ReadingOrder.sort(folios.get(0).number < folios.get(1).number ? left : right);
        List<OcrLine> second = ReadingOrder.sort(folios.get(0).number < folios.get(1).number ? right : left);
        first.addAll(second);
        return first;
    }

    String suffix(List<OcrLine> selected) {
        if (selected.stream().noneMatch(line -> line.kind() != OcrLine.Kind.PAGE_NUMBER)) return "";
        if (folios.size() == 1) return "（p. " + folios.get(0).number + "）";
        if (!spread()) return "";
        boolean left = false, right = false;
        for (OcrLine line : selected) {
            if (line.kind() == OcrLine.Kind.PAGE_NUMBER) continue;
            if (line.centerX() < gutter()) left = true; else right = true;
        }
        if (!left && !right) return "";
        int a = folios.get(0).number, b = folios.get(1).number;
        if (!left || !right) return "（p. " + (left ? a : b) + "）";
        int first = Math.min(a, b), last = Math.max(a, b);
        return "（pp. " + first + (last == first + 1 ? "–" : ", ") + last + "）";
    }
}
