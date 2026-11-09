package com.sasakiy84.excerptmemo;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.FragmentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import android.app.AlertDialog;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends FragmentActivity {
    private DocumentViewModel document;
    private PageView pageView;
    private EditText resultText;
    private CameraPreview preview;
    private Toolbar toolbar;
    private int pageTextSize = 14;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean cameraStarting, capturing, updatingText;
    private static final int MENU_ALL = 1, MENU_ROTATE = 2, MENU_LICENSES = 3, MENU_FILTERS = 4, MENU_LANGUAGE = 5, MENU_SETTINGS = 6;
    private OnBackPressedCallback back;
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    new AlertDialog.Builder(this).setMessage(R.string.permission_rationale)
                            .setNegativeButton(R.string.cancel, null)
                            .setPositiveButton(R.string.settings, (dialog, which) -> startActivity(new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
                            .show();
                }
                render();
            });
    private final ActivityResultLauncher<PickVisualMediaRequest> picker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> { if (uri != null) document.open(uri); });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        pageTextSize = Math.max(6, Math.min(24, getSharedPreferences("display", MODE_PRIVATE).getInt("pageTextSize", 14)));
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        document = new ViewModelProvider(this).get(DocumentViewModel.class);
        toolbar = findViewById(R.id.toolbar);
        toolbar.getMenu().add(Menu.NONE, MENU_LANGUAGE, Menu.NONE, R.string.language_japanese).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.getMenu().add(Menu.NONE, MENU_ALL, Menu.NONE, R.string.all).setIcon(R.drawable.ic_select_all).setIconTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.excerpt_ink))).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        toolbar.getMenu().add(Menu.NONE, MENU_ROTATE, Menu.NONE, R.string.edit_image).setIcon(R.drawable.ic_edit_image).setIconTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.excerpt_ink))).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.getMenu().add(Menu.NONE, MENU_FILTERS, Menu.NONE, R.string.extraction_options);
        toolbar.getMenu().add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.settings);
        toolbar.getMenu().add(Menu.NONE, MENU_LICENSES, Menu.NONE, R.string.licenses);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_LANGUAGE) document.setLanguage(document.language == OcrLanguage.JAPANESE ? OcrLanguage.ENGLISH : OcrLanguage.JAPANESE);
            else if (item.getItemId() == MENU_ALL) pageView.selectAll();
            else if (item.getItemId() == MENU_ROTATE) {
                if (getSupportFragmentManager().findFragmentByTag("image-edit") == null) new ImageEditDialog().show(getSupportFragmentManager(), "image-edit");
            }
            else if (item.getItemId() == MENU_SETTINGS) startActivity(new Intent(this, SettingsActivity.class));
            else if (item.getItemId() == MENU_FILTERS) showExtractionOptions();
            else showLicenses();
            return true;
        });
        toolbar.setNavigationOnClickListener(view -> leaveDocument());
        preview = findViewById(R.id.preview);
        pageView = findViewById(R.id.page_view);
        pageView.setSelectionListener((start, end) -> document.select(start, end));
        ((TextDrawer) findViewById(R.id.text_drawer)).setSizeListener(fraction -> document.textPanelFraction = fraction);
        resultText = findViewById(R.id.result_text);
        resultText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!updatingText) {
                    document.editText(value.toString(), start, before, count);
                    boolean canCopy = !document.busy && !value.toString().isBlank();
                    findViewById(R.id.copy_button).setEnabled(canCopy);
                    findViewById(R.id.copy_button).setAlpha(canCopy ? 1f : .4f);
                }
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        ((RadioGroup) findViewById(R.id.mode_toggle)).setOnCheckedChangeListener((group, id) -> {
            document.textMode = id == R.id.text_mode;
            resultText.clearFocus();
            render();
        });
        findViewById(R.id.import_button).setOnClickListener(view -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        findViewById(R.id.permission_button).setOnClickListener(view -> requestCamera());
        findViewById(R.id.shutter).setOnClickListener(view -> capture());
        findViewById(R.id.read_button).setOnClickListener(view -> { if (document.busy) document.cancel(); else document.read(); });
        findViewById(R.id.copy_button).setOnClickListener(view -> copy());
        back = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { leaveDocument(); }
        };
        getOnBackPressedDispatcher().addCallback(this, back);
        document.changes.observe(this, ignored -> render());
    }

    @Override protected void onResume() {
        super.onResume();
        pageTextSize = Math.max(6, Math.min(24, getSharedPreferences("display", MODE_PRIVATE).getInt("pageTextSize", 14)));
        if (document != null) render();
    }

    @Override protected void onStop() { document.save(); super.onStop(); }

    private void leaveDocument() {
        if (document.busy) { document.cancel(); return; }
        if (document.page != null) {
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(resultText.getWindowToken(), 0);
            document.closePage();
        }
    }

    private void render() {
        if (isDestroyed()) return;
        boolean hasPage = document.page != null;
        findViewById(R.id.root).setKeepScreenOn(!hasPage || document.busy);
        findViewById(R.id.camera_panel).setVisibility(hasPage ? View.GONE : View.VISIBLE);
        findViewById(R.id.document_panel).setVisibility(hasPage ? View.VISIBLE : View.GONE);
        back.setEnabled(hasPage || document.busy);
        toolbar.getMenu().findItem(MENU_LANGUAGE).setTitle(document.language.label).setEnabled(!document.busy)
                .setContentDescription(getString(R.string.recognition_language, getString(document.language.label)));
        toolbar.setNavigationIcon(hasPage ? ContextCompat.getDrawable(this, R.drawable.ic_back) : null);
        toolbar.setNavigationContentDescription(R.string.back);
        toolbar.getMenu().findItem(MENU_ALL).setVisible(hasPage).setEnabled(!document.busy);
        toolbar.getMenu().findItem(MENU_ROTATE).setVisible(hasPage);
        toolbar.getMenu().findItem(MENU_FILTERS).setVisible(hasPage).setEnabled(!document.busy);
        findViewById(R.id.import_button).setEnabled(!document.busy && !capturing);
        if (hasPage) {
            stopCamera();
            pageView.setPage(document.page, document.lines, document.selectionStart, document.selectionEnd);
            pageView.setEnabled(!document.busy);
            pageView.setVisibility(document.textMode ? View.GONE : View.VISIBLE);
            boolean hasText = !document.lines.isEmpty() || !document.text.isEmpty();
            TextDrawer drawer = findViewById(R.id.text_drawer);
            drawer.setVisibility(hasText || document.textMode ? View.VISIBLE : View.GONE);
            drawer.bind(document.textPanelFraction, document.textMode);
            resultText.setEnabled(!document.busy);
            resultText.setTextSize(document.textMode ? getSharedPreferences("display", MODE_PRIVATE).getInt("pageTextSize", 17) : pageTextSize);
            if (!resultText.getText().toString().equals(document.text)) {
                updatingText = true;
                resultText.setText(document.text);
                updatingText = false;
            }
            RadioGroup modes = findViewById(R.id.mode_toggle);
            modes.setVisibility(hasText ? View.VISIBLE : View.INVISIBLE);
            int modeId = document.textMode ? R.id.text_mode : R.id.page_mode;
            if (modes.getCheckedRadioButtonId() != modeId) modes.check(modeId);
            TextView status = findViewById(R.id.status);
            String message = document.status;
            if (!document.busy && document.processingSeconds >= 0) {
                String elapsed = getString(R.string.processing_seconds, document.processingSeconds);
                message = message.isEmpty() ? elapsed : message + " · " + elapsed;
            }
            status.setText(message);
            status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
            findViewById(R.id.progress).setVisibility(document.busy ? View.VISIBLE : View.GONE);
            ImageButton readButton = findViewById(R.id.read_button);
            readButton.setImageResource(document.busy ? R.drawable.ic_close : R.drawable.ic_refresh);
            String readAction = getString(document.busy ? R.string.cancel : hasText ? R.string.reread : R.string.read);
            readButton.setContentDescription(readAction); readButton.setTooltipText(readAction);
            findViewById(R.id.copy_button).setEnabled(!document.busy && !document.text.isBlank());
            findViewById(R.id.copy_button).setAlpha(!document.busy && !document.text.isBlank() ? 1f : .4f);
        } else {
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            findViewById(R.id.permission_button).setVisibility(granted ? View.GONE : View.VISIBLE);
            findViewById(R.id.shutter).setEnabled(granted && imageCapture != null && !document.busy && !capturing);
            if (granted && !document.busy) startCamera();
            if (!document.status.isEmpty()) {
                Toast.makeText(MainActivity.this, document.status, Toast.LENGTH_LONG).show();
                document.status = "";
            }
        }
    }

    private void showExtractionOptions() {
        var sheet = new android.app.Dialog(this);
        View content = getLayoutInflater().inflate(R.layout.extraction_options, null);
        sheet.setContentView(content);
        int[] ids = {R.id.include_page_numbers, R.id.include_ruby, R.id.include_tables};
        boolean[] values = {document.includePageNumbers, document.includeRuby, document.includeTables};
        for (int i = 0; i < ids.length; i++) ((android.widget.CompoundButton) content.findViewById(ids[i])).setChecked(values[i]);
        for (int id : ids) ((android.widget.CompoundButton) content.findViewById(id)).setOnCheckedChangeListener((button, checked) ->
                document.setInclusions(((android.widget.CompoundButton) content.findViewById(ids[0])).isChecked(),
                        ((android.widget.CompoundButton) content.findViewById(ids[1])).isChecked(),
                        ((android.widget.CompoundButton) content.findViewById(ids[2])).isChecked()));
        sheet.show();
        sheet.getWindow().setLayout(-1, -2);
        sheet.getWindow().setGravity(android.view.Gravity.BOTTOM);
    }

    private void requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(this).setMessage(R.string.permission_rationale)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.camera_permission, (dialog, which) -> permission.launch(Manifest.permission.CAMERA)).show();
        } else permission.launch(Manifest.permission.CAMERA);
    }

    private void startCamera() {
        if (cameraStarting || imageCapture != null || document.page != null || isDestroyed()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cameraStarting = true;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            cameraStarting = false;
            if (isDestroyed() || document.page != null || document.busy) return;
            try {
                cameraProvider = future.get();
                Preview cameraPreview = new Preview.Builder().setTargetRotation(preview.getDisplay().getRotation()).build();
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(preview.getDisplay().getRotation()).build();
                CameraSelector selector = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                preview.setUseCase(cameraPreview, selector == CameraSelector.DEFAULT_FRONT_CAMERA);
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this, selector, cameraPreview, imageCapture);
                preview.setOnTouchListener((view, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                                preview.meteringPoint(event.getX(), event.getY())).build());
                        view.performClick();
                    }
                    return true;
                });
                render();
            } catch (Exception exception) {
                imageCapture = null;
                Log.e("ExcerptMemo", "Camera startup failed", exception);
                Toast.makeText(MainActivity.this, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null && imageCapture != null) cameraProvider.unbindAll();
        imageCapture = null;
    }

    private void capture() {
        if (imageCapture == null || document.busy || capturing) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestCamera(); return; }
        capturing = true;
        render();
        File file = new File(getCacheDir(), "capture.jpg");
        imageCapture.setTargetRotation(preview.getDisplay().getRotation());
        try {
            imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override public void onImageSaved(ImageCapture.OutputFileResults results) {
                            capturing = false;
                            document.open(Uri.fromFile(file));
                        }
                        @Override public void onError(ImageCaptureException exception) {
                            capturing = false;
                            Log.e("ExcerptMemo", "Capture failed", exception);
                            if (!isDestroyed()) {
                                render();
                                Toast.makeText(MainActivity.this, R.string.capture_failed, Toast.LENGTH_LONG).show();
                            }
                        }
                    });
        } catch (SecurityException exception) { capturing = false; render(); requestCamera(); }
    }

    private void copy() {
        String text = document.forClipboard(resultText.getSelectionStart(), resultText.getSelectionEnd());
        if (!text.isBlank()) getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
    }

    private void showLicenses() {
        String[] names = {"NDLOCR-Lite", "NDLOCR-Lite dependencies", "DINOv3", "ONNX Runtime", "ONNX Runtime dependencies", "AndroidX", "PaddleOCR / UVDoc", "PaddleOCR license", "UVDoc license", "OpenCV"};
        String[] files = {"NOTICE.txt", "NDLOCR-Lite-LICENCE_DEPENDENCEIES.txt", "DINOv3-LICENSE.md", "ONNX-Runtime-LICENSE.txt", "ONNX-Runtime-ThirdPartyNotices.txt", "Android-LICENSE.txt", "PaddleOCR-NOTICE.txt", "PaddleOCR-LICENSE.txt", "UVDoc-LICENSE.txt", "OpenCV-LICENSE.txt"};
        new AlertDialog.Builder(this).setTitle(R.string.licenses).setItems(names, (dialog, which) -> {
            try (InputStream input = getAssets().open("licenses/" + files[which])) {
                TextView text = new TextView(this);
                int padding = Math.round(20 * getResources().getDisplayMetrics().density);
                text.setPadding(padding, padding, padding, padding);
                text.setText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                text.setTextIsSelectable(true);
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle(names[which]).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
            } catch (Exception exception) { Log.e("ExcerptMemo", "License missing", exception); }
        }).show();
    }
}
