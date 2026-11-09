package com.sasakiy84.excerptmemo;

import android.os.Bundle;
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
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
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
