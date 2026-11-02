package com.sasakiy84.excerptmemo;

public record OcrLine(float left, float top, float right, float bottom, String text, Kind kind, boolean forceHorizontal) {
    public OcrLine(float left, float top, float right, float bottom, String text, Kind kind) {
        this(left, top, right, bottom, text, kind, false);
    }
    public enum Kind { TEXT, PAGE_NUMBER, RUBY, TABLE }
    public OcrLine(float left, float top, float right, float bottom, String text) {
        this(left, top, right, bottom, text, Kind.TEXT);
    }
    public boolean vertical() { return !forceHorizontal && bottom - top > (right - left) * 1.2f; }
    public float centerX() { return (left + right) / 2; }
    public float centerY() { return (top + bottom) / 2; }
    public boolean intersects(float x1, float y1, float x2, float y2) {
        return right > x1 && left < x2 && bottom > y1 && top < y2;
    }
}
