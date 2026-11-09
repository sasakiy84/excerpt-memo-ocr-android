package com.sasakiy84.excerptmemo;

import android.content.Context;

final class OcrSettings {
    enum Model { NDL, TINY, SMALL, MEDIUM }
    record Selection(boolean automatic, Model model, boolean mediumRecognition, boolean flatten) {}
    static Selection load(Context context) {
        return load(context, OcrLanguage.JAPANESE);
    }
    static android.content.SharedPreferences preferences(Context context, OcrLanguage language) {
        var prefs = context.getSharedPreferences("ocr_" + language.code, Context.MODE_PRIVATE);
        if (!prefs.contains("automatic")) {
            var legacy = context.getSharedPreferences("ocr", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("automatic", legacy.getBoolean("automatic", true))
                    .putString("model", legacy.getString("model", "TINY"))
                    .putBoolean("mediumRecognition", legacy.getBoolean("mediumRecognition", false))
                    .putBoolean("flatten", legacy.getBoolean("flatten", false)).apply();
        }
        return prefs;
    }
    static Selection load(Context context, OcrLanguage language) {
        var prefs = preferences(context, language);
        Model model;
        try { model = Model.valueOf(prefs.getString("model", "TINY")); }
        catch (IllegalArgumentException exception) { model = Model.TINY; }
        return new Selection(prefs.getBoolean("automatic", true), model,
                prefs.getBoolean("mediumRecognition", false), prefs.getBoolean("flatten", false));
    }
}
