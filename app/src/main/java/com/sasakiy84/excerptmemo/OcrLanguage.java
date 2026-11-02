package com.sasakiy84.excerptmemo;

public enum OcrLanguage {
    JAPANESE("ja", R.string.language_japanese), ENGLISH("en", R.string.language_english);
    public final String code;
    public final int label;
    OcrLanguage(String code, int label) { this.code = code; this.label = label; }
    static OcrLanguage from(String code) { return "en".equals(code) ? ENGLISH : JAPANESE; }
}
