package com.sasakiy84.excerptmemo;

import android.app.Dialog;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import java.util.Locale;

public final class ImageEditDialog extends DialogFragment {
    private ImageEditView editor;
    private int turns;
    private float fine;
    private boolean fromRectified;
    private final float[] angles = new float[2];
    private final RectF[] crops = new RectF[2];
    @Override public Dialog onCreateDialog(Bundle state) { return new Dialog(requireContext(), R.style.Theme_ExcerptMemo); }
    @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
        DocumentViewModel model = new ViewModelProvider(requireActivity()).get(DocumentViewModel.class);
        if (model.page == null) { dismiss(); return new View(requireContext()); }
        LinearLayout root = new LinearLayout(requireContext()); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.excerpt_background));
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            var safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        MaterialToolbar toolbar = new MaterialToolbar(requireContext()); toolbar.setTitle(R.string.edit_image);
        toolbar.setTitleTextAppearance(requireContext(), R.style.TextAppearance_Excerpt_Toolbar);
        toolbar.setNavigationIconTint(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.excerpt_ink));
        toolbar.setNavigationIcon(R.drawable.ic_close); toolbar.setNavigationContentDescription(R.string.cancel);
        toolbar.setNavigationOnClickListener(v -> dismiss());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        angles[0] = model.editAngle; angles[1] = model.rectifiedEditAngle;
        crops[0] = new RectF(model.editCrop); crops[1] = new RectF(model.rectifiedEditCrop);
        fromRectified = model.editRectified && model.hasRectifiedImage();
        if (state != null) {
            fromRectified = state.getBoolean("rectified") && model.hasRectifiedImage();
            float[] savedAngles = state.getFloatArray("angles");
            if (savedAngles != null) { angles[0] = savedAngles[0]; angles[1] = savedAngles[1]; }
            for (int i = 0; i < 2; i++) {
                float[] crop = state.getFloatArray("crop" + i);
                if (crop != null) crops[i] = new RectF(crop[0], crop[1], crop[2], crop[3]);
            }
        }
        MaterialButtonToggleGroup sources = new MaterialButtonToggleGroup(requireContext());
        sources.setSingleSelection(true); sources.setSelectionRequired(true);
        for (int i = 0; i < 2; i++) {
            MaterialButton source = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            source.setId(i == 0 ? R.id.edit_original : R.id.edit_rectified);
            source.setText(i == 0 ? R.string.original_image : R.string.rectified_image);
            sources.addView(source, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        sources.check(fromRectified ? R.id.edit_rectified : R.id.edit_original);
        sources.setVisibility(model.hasRectifiedImage() ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams sourceLayout = new LinearLayout.LayoutParams(-1, dp(48));
        sourceLayout.setMargins(dp(24), 0, dp(24), dp(8)); root.addView(sources, sourceLayout);
        editor = new ImageEditView(requireContext()); editor.setImage(model.editorSource(fromRectified));
        int index = fromRectified ? 1 : 0;
        turns = Math.round(angles[index] / 90); fine = angles[index] - turns * 90;
        editor.setCrop(crops[index]);
        editor.setId(R.id.image_editor);
        editor.setAngle(turns * 90 + fine);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView angle = new TextView(requireContext()); angle.setGravity(Gravity.CENTER); angle.setTextSize(14);
        root.addView(angle, new LinearLayout.LayoutParams(-1, dp(36)));
        Slider slider = new Slider(requireContext()); slider.setValueFrom(-45); slider.setValueTo(45); slider.setStepSize(0.1f); slider.setValue(fine);
        slider.setContentDescription(getString(R.string.rotation_angle)); slider.setId(R.id.rotation_slider);
        slider.setLabelFormatter(value -> String.format(Locale.ROOT, "%.1f°", value));
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(-1, dp(48)); sliderLayout.setMargins(dp(24), 0, dp(24), 0);
        root.addView(slider, sliderLayout);
        Runnable update = () -> { editor.setAngle(turns * 90 + fine); angle.setText(String.format(Locale.ROOT, "%.1f°", turns * 90 + fine)); };
        slider.addOnChangeListener((view, value, user) -> { fine = value; update.run(); });
        sources.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            rememberEdit(); fromRectified = id == R.id.edit_rectified;
            int selected = fromRectified ? 1 : 0;
            editor.setImage(model.editorSource(fromRectified)); editor.setCrop(crops[selected]);
            turns = Math.round(angles[selected] / 90); fine = angles[selected] - turns * 90;
            slider.setValue(fine); update.run();
        });
        LinearLayout actions = new LinearLayout(requireContext()); actions.setGravity(Gravity.CENTER_VERTICAL); actions.setPadding(dp(16), dp(8), dp(16), dp(16));
        MaterialButton rotate = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        rotate.setText(R.string.rotate_quarter); rotate.setId(R.id.rotate_image_quarter);
        rotate.setOnClickListener(v -> {
            RectF crop = editor.getCrop();
            editor.setCrop(new RectF(1 - crop.bottom, crop.left, 1 - crop.top, crop.right));
            turns = (turns + 1) % 4; update.run();
        });
        actions.addView(rotate, new LinearLayout.LayoutParams(-2, dp(48)));
        MaterialButton apply = new MaterialButton(requireContext()); apply.setText(R.string.apply); apply.setId(R.id.apply_image_edit);
        LinearLayout.LayoutParams applyLayout = new LinearLayout.LayoutParams(0, dp(48), 1); applyLayout.setMargins(dp(12), 0, 0, 0);
        actions.addView(apply, applyLayout); root.addView(actions);
        apply.setOnClickListener(v -> { if (model.busy) model.cancel(); model.applyImageEdit(turns * 90 + fine, editor.getCrop(), fromRectified); dismiss(); });
        toolbar.getMenu().add(R.string.reset).setOnMenuItemClickListener(item -> {
            turns = 0; fine = 0; slider.setValue(0); editor.setCrop(new RectF(0, 0, 1, 1)); update.run(); return true;
        }).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        update.run(); return root;
    }
    @Override public void onStart() { super.onStart(); requireDialog().getWindow().setLayout(-1, -1); }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (editor == null) return;
        rememberEdit(); out.putBoolean("rectified", fromRectified); out.putFloatArray("angles", angles);
        for (int i = 0; i < 2; i++) { RectF c = crops[i]; out.putFloatArray("crop" + i, new float[]{c.left, c.top, c.right, c.bottom}); }
    }
    private void rememberEdit() { int index = fromRectified ? 1 : 0; angles[index] = turns * 90 + fine; crops[index] = editor.getCrop(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
