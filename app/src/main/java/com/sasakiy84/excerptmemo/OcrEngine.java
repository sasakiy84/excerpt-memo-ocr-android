package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import org.json.JSONArray;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/** Native ONNX inference; preprocessing and cascade follow NDLOCR-Lite 1.3.0. */
public final class OcrEngine implements AutoCloseable {
    public enum Stage { NDL_DETECTION, NDL_RECOGNITION, PADDLE_DETECTION, PADDLE_RECOGNITION, UVDOC }
    public interface Progress { void update(Stage stage, int done, int total); }
    private final Context context;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private final String[] charset;
    private PaddleOcr paddle;
    private final PaddleOcr.Policy recognitionPolicy;
    public record PageResult(Bitmap image, List<OcrLine> lines, boolean rectified, String workflow) {}
    private static final String DETECTOR = "deim-s-1024x1024.onnx";
    private static final String SHORT = "parseq-ndl-24x256-30-tiny-189epoch-tegaki3-r8data-202604.onnx";
    private static final String MEDIUM = "parseq-ndl-24x384-50-tiny-300epoch-tegaki3-r8data-202604.onnx";
    private static final String LONG = "parseq-ndl-24x768-100-tiny-153epoch-tegaki3-r8data-202604.onnx";

    public OcrEngine(Context context) throws Exception { this(context, true); }

    OcrEngine(Context context, boolean selectiveRecognition) throws Exception { this(context, selectiveRecognition ? PaddleOcr.Policy.SELECTIVE : PaddleOcr.Policy.SMALL); }

    OcrEngine(Context context, PaddleOcr.Policy recognitionPolicy) throws Exception {
        this.recognitionPolicy = recognitionPolicy;
        this.context = context.getApplicationContext();
        try (InputStream input = context.getAssets().open("charset.json")) {
            JSONArray characters = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            charset = new String[characters.length()];
            for (int i = 0; i < charset.length; i++) charset[i] = characters.getString(i);
        }
    }

    OrtSession session(String name) throws IOException, OrtException {
        if (sessions.containsKey(name)) return sessions.get(name);
        File directory = new File(context.getNoBackupFilesDir(), "models-v1.3.0");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create model directory");
        File model = new File(directory, name);
        if (!model.isFile()) {
            File temporary = new File(directory, name + ".tmp");
            try (InputStream input = context.getAssets().open("models/" + name)) {
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporary.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads((name.equals(DETECTOR) || name.equals(PaddleOcr.DETECTOR)) ? Math.min(4, Runtime.getRuntime().availableProcessors()) : 2);
            options.setInterOpNumThreads(1);
            OrtSession loaded = environment.createSession(model.toString(), options);
            sessions.put(name, loaded);
            return loaded;
        }
    }

    private record Detection(Rect box, float confidence, int tier, OcrLine.Kind kind) {}

    public List<OcrLine> read(Bitmap page, Rect region, AtomicBoolean cancelled, Progress progress) throws Exception {
        checkCancelled(cancelled);
        Bitmap crop = Bitmap.createBitmap(page, region.left, region.top, region.width(), region.height());
        try {
            progress.update(Stage.NDL_DETECTION, 0, 1);
            OrtSession detector = session(DETECTOR);
            checkCancelled(cancelled);
            List<Detection> detections = detect(crop, detector);
            List<OcrLine> lines = new ArrayList<>();
            int done = 0;
            progress.update(Stage.NDL_RECOGNITION, 0, detections.size());
            for (Detection detection : detections) {
                checkCancelled(cancelled);
                Rect box = detection.box;
                Bitmap line = Bitmap.createBitmap(crop, box.left, box.top, box.width(), box.height());
                String text;
                try {
                    text = recognize(line, detection.tier, cancelled);
                } finally { if (line != crop) line.recycle(); }
                if (!text.isBlank()) lines.add(new OcrLine(box.left + region.left, box.top + region.top,
                        box.right + region.left, box.bottom + region.top, text, detection.kind));
                progress.update(Stage.NDL_RECOGNITION, ++done, detections.size());
            }
            checkCancelled(cancelled);
            return ReadingOrder.sort(lines);
        } finally { if (crop != page) crop.recycle(); }
    }

    /** One chosen route, no model race or user-facing engine selector. */
    public PageResult readPage(Bitmap page, boolean alreadyRectified, AtomicBoolean cancelled, Progress progress) throws Exception {
        checkCancelled(cancelled); progress.update(Stage.NDL_DETECTION, 0, 1);
        List<Detection> layout = detect(page, session(DETECTOR));
        List<OcrLine> geometry = layout.stream().map(d -> new OcrLine(d.box.left, d.box.top, d.box.right, d.box.bottom, "", d.kind)).toList();
        OcrRouting.Route route = alreadyRectified ? OcrRouting.Route.FLATTEN_PADDLE
                : OcrRouting.choose(geometry, page.getWidth(), page.getHeight(), true);
        checkCancelled(cancelled);
        if (route == OcrRouting.Route.PADDLE) {
            List<OcrLine> recognized = paddle().read(page, cancelled, progress);
            List<OcrLine> classified = new ArrayList<>();
            for (OcrLine line : recognized) {
                OcrLine.Kind kind = OcrLine.Kind.TEXT;
                float best = .5f;
                for (OcrLine detected : geometry) {
                    if (detected.kind() == OcrLine.Kind.TEXT) continue;
                    float ratio = OcrRouting.categoryOverlap(line, detected);
                    if (ratio > best) { best = ratio; kind = detected.kind(); }
                }
                classified.add(new OcrLine(line.left(), line.top(), line.right(), line.bottom(), line.text(), kind));
            }
            // Keep detected folios even when the general-purpose detector omits tiny text.
            for (Detection d : layout) if (d.kind == OcrLine.Kind.PAGE_NUMBER && classified.stream().noneMatch(l -> l.kind() == d.kind && l.intersects(d.box.left, d.box.top, d.box.right, d.box.bottom))) {
                checkCancelled(cancelled);
                progress.update(Stage.NDL_RECOGNITION, 0, 1);
                Bitmap crop = Bitmap.createBitmap(page, d.box.left, d.box.top, d.box.width(), d.box.height());
                String text;
                try { text = recognize(crop, d.tier, cancelled); } finally { if (crop != page) crop.recycle(); }
                if (!text.isBlank()) classified.add(new OcrLine(d.box.left, d.box.top, d.box.right, d.box.bottom, text, d.kind));
                progress.update(Stage.NDL_RECOGNITION, 1, 1);
            }
            return new PageResult(page, ReadingOrder.sort(classified), false, "paddle-long-vertical");
        }
        Bitmap target = page;
        if (route == OcrRouting.Route.FLATTEN_PADDLE && !alreadyRectified) {
            progress.update(Stage.UVDOC, 0, 1);
            target = flatten(page);
            try {
                checkCancelled(cancelled);
                progress.update(Stage.UVDOC, 1, 1);
                progress.update(Stage.NDL_DETECTION, 0, 1);
                layout = detect(target, session(DETECTOR));
            } catch (Exception | OutOfMemoryError exception) { target.recycle(); throw exception; }
        }
        try {
            List<OcrLine> lines = new ArrayList<>(); int done = 0;
            Stage stage = route == OcrRouting.Route.FLATTEN_PADDLE ? Stage.PADDLE_RECOGNITION : Stage.NDL_RECOGNITION;
            progress.update(stage, 0, layout.size());
            for (Detection d : layout) {
                checkCancelled(cancelled);
                Bitmap crop = Bitmap.createBitmap(target, d.box.left, d.box.top, d.box.width(), d.box.height());
                String text;
                try { text = route == OcrRouting.Route.FLATTEN_PADDLE ? paddle().recognize(crop, cancelled) : recognize(crop, d.tier, cancelled); }
                finally { if (crop != target) crop.recycle(); }
                if (!text.isBlank()) lines.add(new OcrLine(d.box.left, d.box.top, d.box.right, d.box.bottom, text, d.kind));
                progress.update(stage, ++done, layout.size());
            }
            checkCancelled(cancelled);
            return new PageResult(target, ReadingOrder.sort(lines), target != page,
                    route != OcrRouting.Route.FLATTEN_PADDLE ? "ndl"
                    : alreadyRectified ? "ndl-layout-paddle" : "uvdoc-ndl-layout-paddle");
        } catch (Exception | OutOfMemoryError exception) { if (target != page) target.recycle(); throw exception; }
    }

    private PaddleOcr paddle() throws Exception {
        if (paddle == null) paddle = new PaddleOcr(context, this::session, recognitionPolicy);
        return paddle;
    }

    Bitmap flatten(Bitmap page) throws Exception {
        int w = page.getWidth(), h = page.getHeight(), size = w * h;
        int[] pixels = new int[size]; page.getPixels(pixels, 0, w, 0, 0, w, h);
        float[] values = new float[size * 3];
        for (int c = 0; c < 3; c++) for (int i = 0; i < size; i++) values[c * size + i] = ((pixels[i] >> (c * 8)) & 255) / 255f;
        OrtSession session = session("uvdoc.onnx");
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, h, w});
             OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), input))) {
            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            for (int i = 0; i < size; i++) {
                int b = Math.max(0, Math.min(255, (int)(output.get(i) * 255)));
                int g = Math.max(0, Math.min(255, (int)(output.get(size + i) * 255)));
                int r = Math.max(0, Math.min(255, (int)(output.get(size * 2 + i) * 255)));
                pixels[i] = 0xFF000000 | r << 16 | g << 8 | b;
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private List<Detection> detect(Bitmap image, OrtSession detector) throws Exception {
        int side = Math.max(image.getWidth(), image.getHeight());
        List<String> inputNames = new ArrayList<>(detector.getInputNames());
        long[] inputShape = ((TensorInfo) detector.getInputInfo().get(inputNames.get(0)).getInfo()).getShape();
        int inputHeight = (int) inputShape[2], inputWidth = (int) inputShape[3];
        Bitmap resized = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resized);
        canvas.drawColor(Color.BLACK);
        float ratio = inputWidth / (float) side;
        canvas.drawBitmap(image, null, new RectF(0, 0, image.getWidth() * ratio, image.getHeight() * ratio),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        float[] pixels = channels(resized, true);
        resized.recycle();
        List<Detection> candidates = new ArrayList<>();
        List<Rect> tables = new ArrayList<>();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), new long[]{1, 3, inputHeight, inputWidth});
             OnnxTensor dimensions = OnnxTensor.createTensor(environment, new long[][]{{inputHeight, inputWidth}});
             OrtSession.Result result = detector.run(Map.of(inputNames.get(0), tensor, inputNames.get(1), dimensions))) {
            long[] labels = longValues(result.get(0));
            FloatBuffer boxes = ((OnnxTensor) result.get(1)).getFloatBuffer();
            FloatBuffer scores = ((OnnxTensor) result.get(2)).getFloatBuffer();
            long[] counts = result.size() > 3 ? longValues(result.get(3)) : null;
            for (int i = 0; i < labels.length; i++) {
                int label = (int) labels[i];
                float confidence = scores.get(i);
                // NDLOCR exports one-based labels. Table blocks only classify contained lines.
                if (confidence <= 0.2f || !((label >= 2 && label <= 6) || label == 10 || label == 11 || label == 16 || label == 17)) continue;
                float scale = side / (float) inputWidth;
                Rect box = new Rect(
                        Math.max(0, (int) (boxes.get(i * 4) * scale)),
                        Math.max(0, (int) (boxes.get(i * 4 + 1) * scale)),
                        Math.min(image.getWidth(), (int) (boxes.get(i * 4 + 2) * scale)),
                        Math.min(image.getHeight(), (int) (boxes.get(i * 4 + 3) * scale)));
                if (box.width() < 2 || box.height() < 2) continue;
                if (label == 16) { tables.add(box); continue; }
                OcrLine.Kind kind = label == 10 ? OcrLine.Kind.PAGE_NUMBER : label == 11 ? OcrLine.Kind.RUBY : OcrLine.Kind.TEXT;
                candidates.add(new Detection(box, confidence, counts == null ? 1 : (int) counts[i], kind));
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            Detection line = candidates.get(i);
            if (line.kind != OcrLine.Kind.TEXT) continue;
            for (Rect table : tables) {
                Rect overlap = new Rect(line.box);
                if (overlap.intersect(table) && overlap.width() * (float) overlap.height()
                        >= line.box.width() * (float) line.box.height() * 0.5f) {
                    candidates.set(i, new Detection(line.box, line.confidence, line.tier, OcrLine.Kind.TABLE));
                    break;
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Detection::confidence).reversed());
        List<Detection> kept = new ArrayList<>();
        for (Detection candidate : candidates) {
            boolean duplicate = false;
            for (Detection other : kept) {
                if (candidate.kind != other.kind) continue;
                Rect overlap = new Rect(candidate.box);
                if (overlap.intersect(other.box)) {
                    float area = overlap.width() * (float) overlap.height();
                    float union = candidate.box.width() * (float) candidate.box.height()
                            + other.box.width() * (float) other.box.height() - area;
                    if (area / union > 0.65f) { duplicate = true; break; }
                }
            }
            if (!duplicate) kept.add(candidate);
        }
        return kept;
    }

    private static long[] longValues(OnnxValue value) {
        OnnxTensor tensor = (OnnxTensor) value;
        long[] values = new long[(int) tensor.getInfo().getNumElements()];
        java.nio.LongBuffer buffer = tensor.getLongBuffer();
        buffer.get(values);
        return values;
    }

    private String recognize(Bitmap image, int tier, AtomicBoolean cancelled) throws Exception {
        if (tier == 3) {
            String text = recognize(image, SHORT, 256);
            if (text.codePointCount(0, text.length()) < 25) return text;
            checkCancelled(cancelled);
            tier = 2;
        }
        if (tier == 2) {
            String text = recognize(image, MEDIUM, 384);
            if (text.codePointCount(0, text.length()) < 45) return text;
            checkCancelled(cancelled);
        }
        return recognize(image, LONG, 768);
    }

    private String recognize(Bitmap image, String model, int width) throws Exception {
        Bitmap horizontal = image;
        if (image.getHeight() > image.getWidth() * 0.8f) {
            Matrix rotation = new Matrix();
            rotation.postRotate(-90);
            horizontal = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), rotation, true);
        }
        Bitmap resized = Bitmap.createScaledBitmap(horizontal, width, 24, true);
        float[] pixels = channels(resized, false);
        if (resized != image && resized != horizontal) resized.recycle();
        if (horizontal != image) horizontal.recycle();
        OrtSession recognizer = session(model);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), new long[]{1, 3, 24, width});
             OrtSession.Result result = recognizer.run(Map.of(recognizer.getInputNames().iterator().next(), tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            FloatBuffer logits = output.getFloatBuffer();
            return decode(logits, (int) shape[1], (int) shape[2], charset);
        }
    }

    static String decode(FloatBuffer logits, int steps, int classes, String[] characters) {
        StringBuilder text = new StringBuilder();
        for (int step = 0; step < steps; step++) {
            int best = 0;
            for (int index = 1; index < classes; index++) {
                if (logits.get(step * classes + index) > logits.get(step * classes + best)) best = index;
            }
            if (best == 0) break;
            if (best > characters.length) break;
            text.append(characters[best - 1]);
        }
        return text.toString();
    }

    private static float[] channels(Bitmap bitmap, boolean detector) {
        int size = bitmap.getWidth() * bitmap.getHeight();
        int[] colors = new int[size];
        bitmap.getPixels(colors, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        float[] output = new float[size * 3];
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        for (int channel = 0; channel < 3; channel++) {
            int shift = detector ? 16 - channel * 8 : channel * 8;
            for (int i = 0; i < size; i++) {
                int value = (colors[i] >> shift) & 255;
                output[channel * size + i] = detector ? (value / 255f - mean[channel]) / std[channel] : value / 127.5f - 1f;
            }
        }
        return output;
    }

    @Override public void close() throws OrtException {
        for (OrtSession session : sessions.values()) session.close();
        sessions.clear();
    }
}
