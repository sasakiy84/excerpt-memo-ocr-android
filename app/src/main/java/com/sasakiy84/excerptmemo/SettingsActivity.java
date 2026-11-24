package com.sasakiy84.excerptmemo;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.EdgeToEdge;
import androidx.fragment.app.FragmentActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class SettingsActivity extends FragmentActivity {
    private OcrLanguage modelLanguage;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        if (state == null) root.requestFocus();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        Toolbar toolbar = new Toolbar(this); toolbar.setTitle(R.string.settings_title);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationContentDescription(R.string.back);
        toolbar.setNavigationOnClickListener(view -> finish()); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); root.addView(scroll);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding); scroll.addView(content);
        var display = getSharedPreferences("display", MODE_PRIVATE);
        TextView label = label(content, R.string.result_text_size_label);
        SeekBar size = new SeekBar(this); size.setId(R.id.page_text_size); size.setMin(6); size.setMax(24);
        size.setProgress(Math.max(6, Math.min(24, display.getInt("pageTextSize", 14))));
        label.setText(getString(R.string.page_text_size, size.getProgress()));
        size.setContentDescription(label.getText()); content.addView(size);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int value, boolean user) {
                display.edit().putInt("pageTextSize", value).apply();
                label.setText(getString(R.string.page_text_size, value)); size.setContentDescription(label.getText());
            }
            @Override public void onStartTrackingTouch(SeekBar view) {}
            @Override public void onStopTrackingTouch(SeekBar view) {}
        });
        showQuotationFormat(content);
        modelLanguage = OcrLanguage.from(state != null ? state.getString("modelLanguage", "ja")
                : getSharedPreferences("extraction", MODE_PRIVATE).getString("language", "ja"));
        label(content, R.string.model_language);
        RadioGroup languages = new RadioGroup(this); languages.setOrientation(RadioGroup.HORIZONTAL);
        for (OcrLanguage language : OcrLanguage.values()) {
            RadioButton button = new RadioButton(this);
            button.setId(language == OcrLanguage.JAPANESE ? R.id.model_language_japanese : R.id.model_language_english);
            button.setText(language.label); languages.addView(button);
        }
        content.addView(languages);
        LinearLayout models = new LinearLayout(this); models.setOrientation(LinearLayout.VERTICAL); content.addView(models);
        languages.check(modelLanguage == OcrLanguage.JAPANESE ? R.id.model_language_japanese : R.id.model_language_english);
        showModels(models, modelLanguage);
        languages.setOnCheckedChangeListener((group, id) -> {
            modelLanguage = id == R.id.model_language_english ? OcrLanguage.ENGLISH : OcrLanguage.JAPANESE;
            showModels(models, modelLanguage);
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("modelLanguage", modelLanguage.code); super.onSaveInstanceState(state);
    }

    private void showQuotationFormat(LinearLayout content) {
        label(content, R.string.quotation_format);
        var prefs = QuotationFormat.preferences(this);
        var whole = new LinearLayout(this);
        content.addView(whole);
        quotationField(whole, prefs, R.id.quotation_prefix, R.string.quotation_prefix, QuotationFormat.PREFIX);
        quotationField(whole, prefs, R.id.quotation_suffix, R.string.quotation_suffix, QuotationFormat.SUFFIX);
        var lines = new LinearLayout(this);
        content.addView(lines);
        quotationField(lines, prefs, R.id.quotation_line_prefix, R.string.quotation_line_prefix, QuotationFormat.LINE_PREFIX);
        quotationField(lines, prefs, R.id.quotation_line_suffix, R.string.quotation_line_suffix, QuotationFormat.LINE_SUFFIX);
    }

    private void quotationField(LinearLayout row, SharedPreferences prefs, int id, int label, String key) {
        float density = getResources().getDisplayMetrics().density;
        var cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        var params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) params.setMarginStart(Math.round(12 * density));
        row.addView(cell, params);

        var title = new TextView(this);
        title.setText(label);
        title.setLabelFor(id);
        cell.addView(title);
        var input = new EditText(this);
        input.setId(id);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setMinimumHeight(Math.round(48 * density));
        input.setText(prefs.getString(key, ""));
        cell.addView(input);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                prefs.edit().putString(key, text.toString()).apply();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private void showModels(LinearLayout content, OcrLanguage language) {
        content.removeAllViews();
        var prefs = OcrSettings.preferences(this, language);
        var selected = OcrSettings.load(this, language);
        Switch automatic = new Switch(this); automatic.setId(R.id.automatic_model);
        automatic.setMinimumHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        automatic.setText(R.string.automatic_model); automatic.setChecked(selected.automatic()); content.addView(automatic);
        label(content, R.string.ocr_settings_hint);
        TextView detectorLabel = label(content, R.string.detection_model);
        Spinner detector = spinner(content, R.id.detection_model, R.array.detection_models, selected.model().ordinal());
        detectorLabel.setLabelFor(detector.getId());
        TextView recognitionLabel = label(content, R.string.recognition_model);
        Spinner recognition = spinner(content, R.id.recognition_model, R.array.recognition_models, selected.mediumRecognition() ? 1 : 0);
        recognitionLabel.setLabelFor(recognition.getId());
        Switch flatten = new Switch(this); flatten.setId(R.id.flatten_model); flatten.setText(R.string.flatten_model);
        flatten.setMinimumHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        flatten.setChecked(selected.flatten()); content.addView(flatten);
        Runnable update = () -> {
            boolean manual = !automatic.isChecked();
            boolean paddle = detector.getSelectedItemPosition() != OcrSettings.Model.NDL.ordinal();
            detector.setEnabled(manual); detectorLabel.setEnabled(manual);
            recognition.setEnabled(manual && paddle); recognitionLabel.setEnabled(manual && paddle);
            flatten.setEnabled(manual && paddle);
        };
        automatic.setOnCheckedChangeListener((button, checked) -> { prefs.edit().putBoolean("automatic", checked).apply(); update.run(); });
        detector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString("model", OcrSettings.Model.values()[position].name()).apply(); update.run();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        recognition.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { prefs.edit().putBoolean("mediumRecognition", position == 1).apply(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        flatten.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("flatten", checked).apply());
        update.run();
    }
    private TextView label(LinearLayout content, int text) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(16); view.setPadding(0, 24, 0, 12); content.addView(view); return view;
    }
    private Spinner spinner(LinearLayout content, int id, int items, int selection) {
        Spinner view = new Spinner(this); view.setId(id);
        var adapter = ArrayAdapter.createFromResource(this, items, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter); view.setSelection(selection); view.setMinimumHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        content.addView(view); return view;
    }
}
