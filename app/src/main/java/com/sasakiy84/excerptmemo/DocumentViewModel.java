package com.sasakiy84.excerptmemo;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retains the page across rotation and serializes image/model work off the UI thread. */
public final class DocumentViewModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public Bitmap page;
    public List<OcrLine> lines = List.of();
    public Set<Integer> selected = new HashSet<>();
    public Rect region = new Rect();
    public String text = "", status = "";
    public boolean busy, textMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AtomicBoolean cancellation = new AtomicBoolean();
    private OcrEngine engine;

    public DocumentViewModel(Application application) {
        super(application);
        busy = true;
        worker.execute(() -> {
            try {
                File image = imageFile();
                File state = stateFile();
                if (image.isFile() && state.isFile()) {
                    Bitmap restored = decode(ImageDecoder.createSource(image));
                    JSONObject json = new JSONObject(new String(new AtomicFile(state).readFully(), StandardCharsets.UTF_8));
                    List<OcrLine> restoredLines = new ArrayList<>();
                    JSONArray array = json.getJSONArray("lines");
                    for (int i = 0; i < array.length(); i++) {
                        JSONArray line = array.getJSONArray(i);
                        restoredLines.add(new OcrLine((float) line.getDouble(0), (float) line.getDouble(1),
                                (float) line.getDouble(2), (float) line.getDouble(3), line.getString(4)));
                    }
                    main.post(() -> {
                        page = restored; lines = restoredLines;
                        region = new Rect(0, 0, page.getWidth(), page.getHeight());
                        selectAllLines();
                        JSONArray selection = json.optJSONArray("selected");
                        if (selection != null) {
                            selected.clear();
                            for (int i = 0; i < selection.length(); i++) selected.add(selection.optInt(i));
                        }
                        JSONArray bounds = json.optJSONArray("region");
                        if (bounds != null && bounds.length() == 4) region = new Rect(bounds.optInt(0), bounds.optInt(1), bounds.optInt(2), bounds.optInt(3));
                        text = json.optString("text", ReadingOrder.text(lines));
                        textMode = json.optBoolean("textMode");
                        busy = false; changed();
                    });
                    return;
                }
            } catch (Exception exception) { Log.w("ExcerptMemo", "Cannot restore last page", exception); }
            main.post(() -> { busy = false; changed(); });
        });
    }

    private File imageFile() { return new File(getApplication().getFilesDir(), "page.png"); }
    private File stateFile() { return new File(getApplication().getFilesDir(), "page.json"); }
    private void changed() { changes.setValue(changes.getValue() + 1); }

    private static Bitmap decode(ImageDecoder.Source source) throws Exception {
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float scale = Math.min(1, 2800f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    public void open(Uri uri) {
        if (busy) return;
        cancellation.set(true);
        cancellation = new AtomicBoolean();
        AtomicBoolean token = cancellation;
        busy = true; status = getApplication().getString(R.string.image_loading); changed();
        worker.execute(() -> {
            try {
                Bitmap decoded = decode(ImageDecoder.createSource(getApplication().getContentResolver(), uri));
                if (token.get()) return;
                writeImage(decoded);
                main.post(() -> {
                    if (token.get()) return;
                    page = decoded; lines = List.of(); selected.clear(); text = ""; textMode = false;
                    region = new Rect(0, 0, page.getWidth(), page.getHeight());
                    status = ""; busy = false; changed(); save();
                });
            } catch (Exception | OutOfMemoryError exception) {
                Log.e("ExcerptMemo", "Image decoding failed", exception);
                main.post(() -> finishError(token, R.string.image_failed));
            }
        });
    }

    private void writeImage(Bitmap bitmap) throws Exception {
        AtomicFile target = new AtomicFile(imageFile());
        FileOutputStream stream = target.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("Cannot save image");
            target.finishWrite(stream);
        } catch (Exception exception) { target.failWrite(stream); throw exception; }
    }

    public void rotate() {
        if (busy || page == null) return;
        AtomicBoolean token = new AtomicBoolean(); cancellation = token;
        Bitmap original = page;
        busy = true; changed();
        worker.execute(() -> {
            try {
                Matrix rotation = new Matrix(); rotation.postRotate(90);
                Bitmap rotated = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), rotation, true);
                writeImage(rotated);
                main.post(() -> {
                    if (token.get()) return;
                    page = rotated; lines = List.of(); selected.clear(); text = ""; textMode = false;
                    region = new Rect(0, 0, page.getWidth(), page.getHeight());
                    busy = false; status = ""; changed(); save();
                });
            } catch (Exception | OutOfMemoryError exception) {
                Log.e("ExcerptMemo", "Rotation failed", exception);
                main.post(() -> finishError(token, R.string.image_failed));
            }
        });
    }

    public void read() {
        if (busy || page == null) return;
        AtomicBoolean token = new AtomicBoolean(); cancellation = token;
        Rect crop = new Rect(region);
        if (!crop.intersect(0, 0, page.getWidth(), page.getHeight()) || crop.width() < 2 || crop.height() < 2) return;
        Bitmap source = page;
        busy = true; status = getApplication().getString(R.string.loading_model); changed();
        worker.execute(() -> {
            try {
                if (engine == null) engine = new OcrEngine(getApplication());
                List<OcrLine> recognized = engine.read(source, crop, token, (done, total) -> main.post(() -> {
                    if (token.get()) return;
                    status = total == 0 ? getApplication().getString(R.string.detecting)
                            : getApplication().getString(R.string.recognizing, done, total);
                    changed();
                }));
                main.post(() -> {
                    if (token.get()) return;
                    lines = recognized; selectAllLines(); text = ReadingOrder.text(lines);
                    status = lines.isEmpty() ? getApplication().getString(R.string.empty_result) : "";
                    busy = false; changed(); save();
                });
            } catch (CancellationException ignored) {
                // A cancelled job must not replace the current page or text.
            } catch (Exception | OutOfMemoryError exception) {
                Log.e("ExcerptMemo", "OCR failed", exception);
                main.post(() -> finishError(token, R.string.ocr_failed));
            }
        });
    }

    private void finishError(AtomicBoolean token, int message) {
        if (token.get()) return;
        busy = false; status = getApplication().getString(message); changed();
    }

    public void cancel() { cancellation.set(true); busy = false; status = ""; changed(); }

    public void select(Rect selection, Set<Integer> indices) {
        if (busy) return;
        region = selection; selected = new HashSet<>(indices);
        List<OcrLine> chosen = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) if (selected.contains(i)) chosen.add(lines.get(i));
        text = ReadingOrder.text(chosen);
        changed(); save();
    }

    private void selectAllLines() {
        selected = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) selected.add(i);
    }

    public void closePage() {
        cancel();
        page = null; lines = List.of(); selected.clear(); text = ""; textMode = false; changed();
        worker.execute(() -> { new AtomicFile(imageFile()).delete(); new AtomicFile(stateFile()).delete(); });
    }

    public void save() {
        if (page == null) return;
        try {
            JSONArray array = new JSONArray();
            for (OcrLine line : lines) array.put(new JSONArray().put(line.left()).put(line.top()).put(line.right()).put(line.bottom()).put(line.text()));
            JSONArray selection = new JSONArray();
            for (int index : selected) selection.put(index);
            JSONArray bounds = new JSONArray().put(region.left).put(region.top).put(region.right).put(region.bottom);
            byte[] json = new JSONObject().put("lines", array).put("text", text).put("textMode", textMode)
                    .put("selected", selection).put("region", bounds)
                    .toString().getBytes(StandardCharsets.UTF_8);
            worker.execute(() -> {
                AtomicFile target = new AtomicFile(stateFile());
                FileOutputStream stream = null;
                try { stream = target.startWrite(); stream.write(json); target.finishWrite(stream); }
                catch (Exception exception) { if (stream != null) target.failWrite(stream); Log.w("ExcerptMemo", "Cannot save page", exception); }
            });
        } catch (Exception exception) { Log.w("ExcerptMemo", "Cannot serialize page", exception); }
    }

    @Override protected void onCleared() {
        cancellation.set(true);
        worker.execute(() -> {
            if (engine != null) try { engine.close(); } catch (Exception exception) { Log.w("ExcerptMemo", "Model close failed", exception); }
        });
        worker.shutdown();
    }
}
