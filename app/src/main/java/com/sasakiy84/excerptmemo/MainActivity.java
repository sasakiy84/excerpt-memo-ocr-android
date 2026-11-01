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
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends AppCompatActivity {
    private DocumentViewModel document;
    private PageView pageView;
    private EditText resultText;
    private PreviewView preview;
    private MaterialToolbar toolbar;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean cameraStarting, capturing, updatingText;
    private static final int MENU_ALL = 1, MENU_ROTATE = 2, MENU_LICENSES = 3;
    private OnBackPressedCallback back;
    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    new MaterialAlertDialogBuilder(this).setMessage(R.string.permission_rationale)
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
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        document = new ViewModelProvider(this).get(DocumentViewModel.class);
        toolbar = findViewById(R.id.toolbar);
        toolbar.getMenu().add(Menu.NONE, MENU_ALL, Menu.NONE, R.string.all).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        toolbar.getMenu().add(Menu.NONE, MENU_ROTATE, Menu.NONE, R.string.rotate);
        toolbar.getMenu().add(Menu.NONE, MENU_LICENSES, Menu.NONE, R.string.licenses);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_ALL) pageView.selectAll();
            else if (item.getItemId() == MENU_ROTATE) document.rotate();
            else showLicenses();
            return true;
        });
        toolbar.setNavigationOnClickListener(view -> leaveDocument());
        preview = findViewById(R.id.preview);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        pageView = findViewById(R.id.page_view);
        pageView.setSelectionListener((start, end) -> document.select(start, end));
        for (int id : new int[]{R.id.include_page_numbers, R.id.include_ruby, R.id.include_tables}) {
            ((android.widget.CompoundButton) findViewById(id)).setOnCheckedChangeListener((button, checked) -> {
                if (updatingText) return;
                document.setInclusions(((android.widget.CompoundButton) findViewById(R.id.include_page_numbers)).isChecked(),
                        ((android.widget.CompoundButton) findViewById(R.id.include_ruby)).isChecked(),
                        ((android.widget.CompoundButton) findViewById(R.id.include_tables)).isChecked());
            });
        }
        resultText = findViewById(R.id.result_text);
        resultText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!updatingText) {
                    document.editText(value.toString(), start, before, count);
                    findViewById(R.id.copy_button).setEnabled(!document.busy && !value.toString().isBlank());
                }
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        ((MaterialButtonToggleGroup) findViewById(R.id.mode_toggle)).addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
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
        toolbar.setNavigationIcon(hasPage ? ContextCompat.getDrawable(this, R.drawable.ic_back) : null);
        toolbar.setNavigationContentDescription(R.string.back);
        toolbar.getMenu().findItem(MENU_ALL).setVisible(hasPage).setEnabled(!document.busy);
        toolbar.getMenu().findItem(MENU_ROTATE).setVisible(hasPage).setEnabled(!document.busy);
        findViewById(R.id.import_button).setEnabled(!document.busy && !capturing);
        if (hasPage) {
            stopCamera();
            pageView.setPage(document.page, document.lines, document.selectionStart, document.selectionEnd);
            updatingText = true;
            int[] filterIds = {R.id.include_page_numbers, R.id.include_ruby, R.id.include_tables};
            boolean[] inclusions = {document.includePageNumbers, document.includeRuby, document.includeTables};
            for (int i = 0; i < filterIds.length; i++) {
                android.widget.CompoundButton filter = findViewById(filterIds[i]);
                filter.setChecked(inclusions[i]); filter.setEnabled(!document.busy);
            }
            updatingText = false;
            pageView.setEnabled(!document.busy);
            pageView.setVisibility(document.textMode ? View.GONE : View.VISIBLE);
            boolean hasText = !document.lines.isEmpty() || !document.text.isEmpty();
            resultText.setVisibility(hasText || document.textMode ? View.VISIBLE : View.GONE);
            resultText.setEnabled(!document.busy);
            if (!resultText.getText().toString().equals(document.text)) {
                updatingText = true;
                resultText.setText(document.text);
                updatingText = false;
            }
            MaterialButtonToggleGroup modes = findViewById(R.id.mode_toggle);
            modes.setVisibility(hasText ? View.VISIBLE : View.GONE);
            int modeId = document.textMode ? R.id.text_mode : R.id.page_mode;
            if (modes.getCheckedButtonId() != modeId) modes.check(modeId);
            TextView status = findViewById(R.id.status);
            status.setText(document.status);
            status.setVisibility(document.status.isEmpty() ? View.GONE : View.VISIBLE);
            findViewById(R.id.progress).setVisibility(document.busy ? View.VISIBLE : View.GONE);
            ((MaterialButton) findViewById(R.id.read_button)).setText(document.busy ? R.string.cancel : hasText ? R.string.reread : R.string.read);
            findViewById(R.id.copy_button).setEnabled(!document.busy && !document.text.isBlank());
        } else {
            boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            findViewById(R.id.permission_button).setVisibility(granted ? View.GONE : View.VISIBLE);
            findViewById(R.id.shutter).setEnabled(granted && imageCapture != null && !document.busy && !capturing);
            if (granted && !document.busy) startCamera();
            if (!document.status.isEmpty()) {
                Snackbar.make(findViewById(R.id.root), document.status, Snackbar.LENGTH_LONG).show();
                document.status = "";
            }
        }
    }

    private void requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new MaterialAlertDialogBuilder(this).setMessage(R.string.permission_rationale)
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
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(preview.getDisplay().getRotation()).build();
                CameraSelector selector = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                        ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                Camera camera = cameraProvider.bindToLifecycle(this, selector, cameraPreview, imageCapture);
                preview.setOnTouchListener((view, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_UP) {
                        camera.getCameraControl().startFocusAndMetering(new FocusMeteringAction.Builder(
                                preview.getMeteringPointFactory().createPoint(event.getX(), event.getY())).build());
                        view.performClick();
                    }
                    return true;
                });
                render();
            } catch (Exception exception) {
                imageCapture = null;
                Log.e("ExcerptMemo", "Camera startup failed", exception);
                Snackbar.make(findViewById(R.id.root), R.string.camera_unavailable, Snackbar.LENGTH_LONG).show();
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
                                Snackbar.make(findViewById(R.id.root), R.string.capture_failed, Snackbar.LENGTH_LONG).show();
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
        String[] names = {"NDLOCR-Lite", "NDLOCR-Lite dependencies", "DINOv3", "ONNX Runtime", "ONNX Runtime dependencies", "AndroidX / Material"};
        String[] files = {"NOTICE.txt", "NDLOCR-Lite-LICENCE_DEPENDENCEIES.txt", "DINOv3-LICENSE.md", "ONNX-Runtime-LICENSE.txt", "ONNX-Runtime-ThirdPartyNotices.txt", "Android-LICENSE.txt"};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.licenses).setItems(names, (dialog, which) -> {
            try (InputStream input = getAssets().open("licenses/" + files[which])) {
                TextView text = new TextView(this);
                int padding = Math.round(20 * getResources().getDisplayMetrics().density);
                text.setPadding(padding, padding, padding, padding);
                text.setText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                text.setTextIsSelectable(true);
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new MaterialAlertDialogBuilder(this).setTitle(names[which]).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
            } catch (Exception exception) { Log.e("ExcerptMemo", "License missing", exception); }
        }).show();
    }
}
