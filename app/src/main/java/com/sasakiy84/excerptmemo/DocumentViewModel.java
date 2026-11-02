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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retains the page across rotation and serializes image/model work off the UI thread. */
public final class DocumentViewModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public Bitmap page;
    private Bitmap sourcePage;
    private Bitmap rectifiedPage;
    public boolean pageRectified;
    public boolean editRectified;
    public float editAngle;
    public android.graphics.RectF editCrop = new android.graphics.RectF(0, 0, 1, 1);
    public float rectifiedEditAngle;
    public android.graphics.RectF rectifiedEditCrop = new android.graphics.RectF(0, 0, 1, 1);
    public List<OcrLine> lines = List.of();
    public List<OcrLine> allLines = List.of();
    private TextGeometry cachedGeometry = new TextGeometry(lines);
    public int selectionStart, selectionEnd;
    public OcrLanguage language;
    public boolean includePageNumbers, includeRuby, includeTables;
    public String text = "", status = "";
    public double processingSeconds = -1;
    private int[] textOrigins = new int[0];
    public boolean busy, textMode;
    public float textPanelFraction = .28f;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AtomicBoolean cancellation = new AtomicBoolean();
    private OcrEngine engine;

    public DocumentViewModel(Application application) {
        super(application);
        var preferences = application.getSharedPreferences("extraction", 0);
        language = OcrLanguage.from(preferences.getString("language", "ja"));
        includePageNumbers = preferences.getBoolean("pageNumbers", false);
        includeRuby = preferences.getBoolean("ruby", false);
        includeTables = preferences.getBoolean("tables", false);
        busy = true;
        worker.execute(() -> {
            try {
                File image = imageFile();
                File state = stateFile();
                if (image.isFile() && state.isFile()) {
                    Bitmap restored = decode(ImageDecoder.createSource(image));
                    Bitmap source = sourceFile().isFile() ? decode(ImageDecoder.createSource(sourceFile())) : restored;
                    JSONObject json = new JSONObject(new String(new AtomicFile(state).readFully(), StandardCharsets.UTF_8));
                    // 0.5.0 retained only the displayed UVDoc result; use it as the initial edit base.
                    Bitmap rectified = rectifiedFile().isFile() ? decode(ImageDecoder.createSource(rectifiedFile()))
                            : json.optBoolean("pageRectified") ? restored : null;
                    List<OcrLine> restoredLines = new ArrayList<>();
                    JSONArray array = json.getJSONArray("lines");
                    for (int i = 0; i < array.length(); i++) {
                        JSONArray line = array.getJSONArray(i);
                        restoredLines.add(new OcrLine((float) line.getDouble(0), (float) line.getDouble(1),
                                (float) line.getDouble(2), (float) line.getDouble(3), line.getString(4),
                                OcrLine.Kind.valueOf(line.optString(5, "TEXT")), line.optBoolean(6, false)));
                    }
                    main.post(() -> {
                        language = OcrLanguage.from(json.optString("language", "ja"));
                        page = restored; sourcePage = source; allLines = restoredLines;
                        pageRectified = json.optBoolean("pageRectified");
                        processingSeconds = json.optDouble("processingSeconds", -1);
                        rectifiedPage = rectified;
                        editRectified = rectified != null && json.optBoolean("editRectified", pageRectified);
                        rectifiedEditAngle = (float) json.optDouble("rectifiedEditAngle", 0);
                        JSONArray correctedCrop = json.optJSONArray("rectifiedEditCrop");
                        if (correctedCrop != null && correctedCrop.length() == 4) rectifiedEditCrop = new android.graphics.RectF((float)correctedCrop.optDouble(0), (float)correctedCrop.optDouble(1), (float)correctedCrop.optDouble(2), (float)correctedCrop.optDouble(3));
                        editAngle = (float) json.optDouble("editAngle", 0);
                        JSONArray savedCrop = json.optJSONArray("editCrop");
                        if (savedCrop != null && savedCrop.length() == 4) editCrop = new android.graphics.RectF((float)savedCrop.optDouble(0), (float)savedCrop.optDouble(1), (float)savedCrop.optDouble(2), (float)savedCrop.optDouble(3));
                        rebuildLines(false);
                        if (json.has("selectionStart")) {
                            TextGeometry geometry = geometry();
                            selectionStart = geometry.snap(json.optInt("selectionStart"), false);
                            selectionEnd = Math.max(selectionStart, geometry.snap(json.optInt("selectionEnd"), true));
                        }
                        textPanelFraction = (float) json.optDouble("textPanelFraction", .28);
                        String savedText = json.optString("text", geometry().text.substring(selectionStart, selectionEnd));
                        boolean migrateParagraphs = !json.optBoolean("paragraphText") && savedText.equals(geometry().text.substring(selectionStart, selectionEnd));
                        if (migrateParagraphs) setExcerpt(); else { text = savedText; resetTextOrigins(); }
                        JSONArray origins = json.optJSONArray("textOrigins");
                        if (!migrateParagraphs && origins != null && origins.length() == text.length()) {
                            textOrigins = new int[text.length()];
                            for (int i = 0; i < textOrigins.length; i++) textOrigins[i] = origins.optInt(i, -1);
                        }
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
    private File sourceFile() { return new File(getApplication().getFilesDir(), "source.png"); }
    private File rectifiedFile() { return new File(getApplication().getFilesDir(), "rectified.png"); }
    public Bitmap editorSource() { return sourcePage == null ? page : sourcePage; }
    public boolean hasRectifiedImage() { return rectifiedPage != null; }
    public Bitmap editorSource(boolean rectified) { return rectified ? rectifiedPage : editorSource(); }
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
        processingSeconds = -1; busy = true; status = getApplication().getString(R.string.image_loading); changed();
        worker.execute(() -> {
            try {
                Bitmap decoded = decode(ImageDecoder.createSource(getApplication().getContentResolver(), uri));
                if (token.get()) return;
                writeImage(decoded);
                writeBitmap(decoded, sourceFile());
                new AtomicFile(rectifiedFile()).delete();
                main.post(() -> {
                    if (token.get()) return;
                    page = decoded; sourcePage = decoded; pageRectified = false; editAngle = 0; editCrop = new android.graphics.RectF(0, 0, 1, 1); allLines = lines = List.of(); selectionStart = selectionEnd = 0; text = ""; textOrigins = new int[0]; textMode = false;
                    rectifiedPage = null; editRectified = false; rectifiedEditAngle = 0; rectifiedEditCrop = new android.graphics.RectF(0, 0, 1, 1);
                    status = ""; busy = false; changed(); save(); read();
                });
            } catch (Exception | OutOfMemoryError exception) {
                Log.e("ExcerptMemo", "Image decoding failed", exception);
                main.post(() -> finishError(token, R.string.image_failed));
            }
        });
    }

    private void writeImage(Bitmap bitmap) throws Exception { writeBitmap(bitmap, imageFile()); }

    private void writeBitmap(Bitmap bitmap, File file) throws Exception {
        AtomicFile target = new AtomicFile(file);
        FileOutputStream stream = target.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new java.io.IOException("Cannot save image");
            target.finishWrite(stream);
        } catch (Exception exception) { target.failWrite(stream); throw exception; }
    }

    public void applyImageEdit(float degrees, android.graphics.RectF crop) {
        applyImageEdit(degrees, crop, editRectified);
    }

    public void applyImageEdit(float degrees, android.graphics.RectF crop, boolean fromRectified) {
        if (busy || page == null) return;
        if (fromRectified && !hasRectifiedImage()) return;
        AtomicBoolean token = new AtomicBoolean(); cancellation = token;
        Bitmap original = editorSource(fromRectified);
        android.graphics.RectF selection = new android.graphics.RectF(crop);
        processingSeconds = -1; busy = true; status = getApplication().getString(R.string.image_loading); changed();
        worker.execute(() -> {
            try {
                Bitmap edited = ImageEdits.apply(original, degrees, selection);
                if (token.get()) return;
                if (!sourceFile().isFile()) writeBitmap(editorSource(), sourceFile());
                if (rectifiedPage != null && !rectifiedFile().isFile()) writeBitmap(rectifiedPage, rectifiedFile());
                writeImage(edited);
                main.post(() -> {
                    if (token.get()) return;
                    if (sourcePage == null) sourcePage = original;
                    page = edited; pageRectified = fromRectified; editRectified = fromRectified;
                    if (fromRectified) { rectifiedEditAngle = degrees; rectifiedEditCrop = selection; }
                    else { editAngle = degrees; editCrop = selection; }
                    allLines = lines = List.of(); selectionStart = selectionEnd = 0;
                    text = ""; textOrigins = new int[0]; textMode = false;
                    busy = false; status = ""; changed(); save(); read();
                });
            } catch (Exception | OutOfMemoryError exception) {
                Log.e("ExcerptMemo", "Image edit failed", exception);
                main.post(() -> finishError(token, R.string.image_failed));
            }
        });
    }

    public void setLanguage(OcrLanguage selected) {
        if (language == selected) return;
        if (busy) cancel();
        language = selected;
        getApplication().getSharedPreferences("extraction", 0).edit().putString("language", selected.code).apply();
        if (page != null) read(); else changed();
    }

    public void read() {
        if (busy || page == null) return;
        AtomicBoolean token = new AtomicBoolean(); cancellation = token;
        Rect crop = new Rect(0, 0, page.getWidth(), page.getHeight());
        if (!crop.intersect(0, 0, page.getWidth(), page.getHeight()) || crop.width() < 2 || crop.height() < 2) return;
        Bitmap source = page;
        boolean alreadyRectified = pageRectified;
        OcrLanguage selectedLanguage = language;
        long started = System.nanoTime();
        processingSeconds = -1; busy = true; status = getApplication().getString(R.string.loading_model); changed();
        worker.execute(() -> {
            try {
                if (engine == null) engine = new OcrEngine(getApplication());
                OcrEngine.PageResult recognized = engine.readPage(source, alreadyRectified, selectedLanguage, token, (stage, done, total) -> main.post(() -> {
                    if (token.get()) return;
                    int label = switch (stage) {
                        case NDL_DETECTION -> R.string.ndl_detecting;
                        case NDL_RECOGNITION -> R.string.ndl_recognizing;
                        case PADDLE_DETECTION -> R.string.paddle_detecting;
                        case PADDLE_RECOGNITION -> R.string.paddle_recognizing;
                        case UVDOC -> R.string.uvdoc_running;
                    };
                    String name = getApplication().getString(label);
                    status = total > 0 ? getApplication().getString(R.string.ocr_progress, name, done, total) : name;
                    changed();
                }));
                if (token.get()) { if (recognized.image() != source) recognized.image().recycle(); return; }
                if (recognized.image() != source) writeImage(recognized.image());
                if (recognized.rectified()) writeBitmap(recognized.image(), rectifiedFile());
                double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
                main.post(() -> {
                    if (token.get()) { if (recognized.image() != source) recognized.image().recycle(); return; }
                    page = recognized.image(); pageRectified = pageRectified || recognized.rectified();
                    if (recognized.rectified()) {
                        rectifiedPage = recognized.image(); editRectified = true;
                        rectifiedEditAngle = 0; rectifiedEditCrop = new android.graphics.RectF(0, 0, 1, 1);
                    }
                    processingSeconds = elapsed;
                    allLines = recognized.lines(); rebuildLines(false);
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

    public void select(int start, int end) {
        if (busy) return;
        TextGeometry geometry = geometry();
        selectionStart = geometry.snap(Math.min(start, end), false);
        selectionEnd = geometry.snap(Math.max(start, end), true);
        setExcerpt();
        changed();
    }

    public void setInclusions(boolean pageNumbers, boolean ruby, boolean tables) {
        if (busy) return;
        includePageNumbers = pageNumbers; includeRuby = ruby; includeTables = tables;
        getApplication().getSharedPreferences("extraction", 0).edit()
                .putBoolean("pageNumbers", pageNumbers).putBoolean("ruby", ruby).putBoolean("tables", tables).apply();
        rebuildLines(true);
        status = lines.isEmpty() ? getApplication().getString(R.string.empty_result) : "";
        changed(); save();
    }

    private void rebuildLines(boolean preserve) {
        List<OcrLine> previous = lines;
        TextGeometry old = geometry();
        int oldStart = selectionStart, oldEnd = selectionEnd;
        boolean all = !preserve || (oldStart == 0 && oldEnd == old.length());
        List<OcrLine> visible = new ArrayList<>();
        for (OcrLine line : allLines) {
            if (line.kind() == OcrLine.Kind.PAGE_NUMBER && !includePageNumbers) continue;
            if (line.kind() == OcrLine.Kind.RUBY && !includeRuby) continue;
            if (line.kind() == OcrLine.Kind.TABLE && !includeTables) continue;
            visible.add(line);
        }
        lines = new PageReferences(allLines, page == null ? 1 : page.getWidth()).order(visible);
        TextGeometry geometry = geometry();
        selectionStart = 0; selectionEnd = all ? geometry.length() : 0;
        if (!all) {
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                int previousIndex = previous.indexOf(lines.get(i));
                if (previousIndex < 0) continue;
                int from = Math.max(0, oldStart - old.starts[previousIndex]);
                int to = Math.min(lines.get(i).text().length(), oldEnd - old.starts[previousIndex]);
                if (from >= to) continue;
                if (!found) { selectionStart = geometry.starts[i] + from; found = true; }
                selectionEnd = geometry.starts[i] + to;
            }
        }
        setExcerpt();
    }

    private TextGeometry geometry() {
        if (cachedGeometry.lines != lines) cachedGeometry = new TextGeometry(lines);
        return cachedGeometry;
    }

    private void setExcerpt() {
        ParagraphText.Result excerpt = ParagraphText.extract(lines, selectionStart, selectionEnd);
        text = excerpt.text(); textOrigins = excerpt.origins();
    }

    private void resetTextOrigins() {
        textOrigins = new int[text.length()];
        for (int i = 0; i < text.length(); i++) textOrigins[i] = selectionStart + Math.min(i, Math.max(0, selectionEnd - selectionStart - 1));
    }

    // Keep source positions through OCR corrections so a later text selection still
    // refers to the correct side of a spread, even after insertions or deletions.
    public void editText(String value, int start, int before, int count) {
        if (textOrigins != null && textOrigins.length == text.length() && start >= 0 && before >= 0 && count >= 0
                && start + before <= text.length() && value.length() == text.length() - before + count) {
            int[] mapped = new int[value.length()];
            System.arraycopy(textOrigins, 0, mapped, 0, start);
            int a = textOrigins.length == 0 ? selectionStart : textOrigins[Math.min(start, textOrigins.length - 1)];
            int b = before == 0 ? a : textOrigins[start + before - 1];
            for (int i = 0; i < count; i++) mapped[start + i] = count <= 1 ? a : a + (b - a) * i / (count - 1);
            System.arraycopy(textOrigins, start + before, mapped, start + count, text.length() - start - before);
            textOrigins = mapped;
        } else textOrigins = null;
        text = value;
    }

    public String forClipboard(int localStart, int localEnd) {
        int from = Math.max(0, Math.min(text.length(), Math.min(localStart, localEnd)));
        int to = Math.max(from, Math.min(text.length(), Math.max(localStart, localEnd)));
        if (from == to) { from = 0; to = text.length(); }
        String copied = text.substring(from, to);
        if (copied.isBlank()) return copied;
        TextGeometry geometry = geometry();
        int start = selectionStart, end = selectionEnd;
        if (textOrigins != null && textOrigins.length == text.length()) {
            start = geometry.length(); end = 0;
            for (int i = from; i < to; i++) {
                int origin = textOrigins[i];
                if (origin >= 0 && origin < geometry.length()) { start = Math.min(start, origin); end = Math.max(end, origin + 1); }
            }
        } else if (text.equals(geometry.text.substring(start, end))) { start += from; end = selectionStart + to; }
        List<OcrLine> chosen = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (geometry.starts[i] < end && geometry.starts[i] + lines.get(i).text().length() > start) chosen.add(lines.get(i));
        }
        return copied + new PageReferences(allLines, page == null ? 1 : page.getWidth()).suffix(chosen);
    }

    public java.util.concurrent.Future<?> closePage() {
        processingSeconds = -1;
        cancel();
        language = OcrLanguage.from(getApplication().getSharedPreferences("extraction", 0).getString("language", "ja"));
        page = null; sourcePage = null; pageRectified = false; editAngle = 0; editCrop = new android.graphics.RectF(0, 0, 1, 1); allLines = lines = List.of(); selectionStart = selectionEnd = 0; text = ""; textOrigins = new int[0]; textMode = false; changed();
        rectifiedPage = null; editRectified = false; rectifiedEditAngle = 0; rectifiedEditCrop = new android.graphics.RectF(0, 0, 1, 1);
        return worker.submit(() -> { new AtomicFile(imageFile()).delete(); new AtomicFile(sourceFile()).delete(); new AtomicFile(rectifiedFile()).delete(); new AtomicFile(stateFile()).delete(); });
    }

    public void save() {
        if (page == null) return;
        try {
            JSONArray array = new JSONArray();
            for (OcrLine line : allLines) array.put(new JSONArray().put(line.left()).put(line.top()).put(line.right()).put(line.bottom()).put(line.text()).put(line.kind().name()).put(line.forceHorizontal()));
            JSONObject state = new JSONObject().put("processingSeconds", processingSeconds).put("language", language.code).put("lines", array).put("text", text).put("textMode", textMode)
                    .put("selectionStart", selectionStart).put("selectionEnd", selectionEnd)
                    .put("paragraphText", true).put("textPanelFraction", textPanelFraction).put("pageRectified", pageRectified)
                    .put("editRectified", editRectified).put("rectifiedEditAngle", rectifiedEditAngle)
                    .put("rectifiedEditCrop", new JSONArray().put(rectifiedEditCrop.left).put(rectifiedEditCrop.top).put(rectifiedEditCrop.right).put(rectifiedEditCrop.bottom))
                    .put("editAngle", editAngle).put("editCrop", new JSONArray().put(editCrop.left).put(editCrop.top).put(editCrop.right).put(editCrop.bottom));
            if (textOrigins != null) {
                JSONArray origins = new JSONArray();
                for (int origin : textOrigins) origins.put(origin);
                state.put("textOrigins", origins);
            }
            byte[] json = state.toString().getBytes(StandardCharsets.UTF_8);
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
