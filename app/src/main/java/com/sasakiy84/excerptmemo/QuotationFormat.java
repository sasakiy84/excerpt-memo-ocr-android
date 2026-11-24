package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.regex.Pattern;

record QuotationFormat(String prefix, String suffix, String linePrefix, String lineSuffix) {
    static final String PREFIX = "prefix";
    static final String SUFFIX = "suffix";
    static final String LINE_PREFIX = "linePrefix";
    static final String LINE_SUFFIX = "lineSuffix";
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences("quotation", Context.MODE_PRIVATE);
    }

    static QuotationFormat load(Context context) {
        var prefs = preferences(context);
        return new QuotationFormat(prefs.getString(PREFIX, ""), prefs.getString(SUFFIX, ""),
                prefs.getString(LINE_PREFIX, ""), prefs.getString(LINE_SUFFIX, ""));
    }

    String format(String text) {
        String wrapped = prefix + text + suffix;
        if (linePrefix.isEmpty() && lineSuffix.isEmpty()) return wrapped;

        // Apply line markers last so Markdown markers start at the beginning of the line.
        var lineBreaks = LINE_BREAK.matcher(wrapped);
        var result = new StringBuilder(linePrefix);
        int start = 0;
        while (lineBreaks.find()) {
            result.append(wrapped, start, lineBreaks.start()).append(lineSuffix);
            result.append(lineBreaks.group()).append(linePrefix);
            start = lineBreaks.end();
        }
        return result.append(wrapped, start, wrapped.length()).append(lineSuffix).toString();
    }
}
