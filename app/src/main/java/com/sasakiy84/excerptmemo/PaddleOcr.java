package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import org.json.JSONArray;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import ai.onnxruntime.*;

/** PP-OCRv6 DB quadrilaterals and aspect-preserving CTC recognition. */
final class PaddleOcr {
    static final String TINY_DETECTOR = "ppocrv6-tiny-det.onnx", MEDIUM_DETECTOR = "ppocrv6-medium-det.onnx";
    static final String MEDIUM = "ppocrv6-medium-rec.onnx";
    static final String DETECTOR = "ppocrv6-small-det.onnx", RECOGNIZER = "ppocrv6-small-rec.onnx";
    interface Sessions { OrtSession open(String name) throws Exception; }
    private final Sessions sessions;
    enum Policy { SMALL, SELECTIVE, MEDIUM }
    private final Policy policy;
    private final String[] characters;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();

    PaddleOcr(Context context, Sessions sessions) throws Exception { this(context, sessions, Policy.SELECTIVE); }

    PaddleOcr(Context context, Sessions sessions, Policy policy) throws Exception {
        this.policy = policy;
        this.sessions = sessions;
        try (InputStream input = context.getAssets().open("ppocrv6-charset.json")) {
            JSONArray array = new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            characters = new String[array.length() + 2];
            characters[0] = "";
            for (int i = 0; i < array.length(); i++) characters[i + 1] = array.getString(i);
            characters[characters.length - 1] = " ";
        }
    }

    String recognize(Bitmap image) throws Exception { return recognize(image, new AtomicBoolean()); }

    String recognize(Bitmap image, AtomicBoolean cancelled) throws Exception { return recognize(image, cancelled, false); }

    String recognize(Bitmap image, AtomicBoolean cancelled, boolean horizontalOnly) throws Exception {
        return recognize(image, cancelled, horizontalOnly, policy);
    }

    private String recognize(Bitmap image, AtomicBoolean cancelled, boolean horizontalOnly, Policy chosenPolicy) throws Exception {
        Bitmap horizontal = image;
        if (!horizontalOnly && image.getHeight() >= image.getWidth() * 1.5f) {
            Matrix rotation = new Matrix(); rotation.postRotate(-90);
            horizontal = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), rotation, true);
        }
        int width = Math.min(3200, Math.max(320, (int) Math.ceil(48.0 * horizontal.getWidth() / horizontal.getHeight())));
        int contentWidth = Math.min(width, (int) Math.ceil(48.0 * horizontal.getWidth() / horizontal.getHeight()));
        Bitmap resized = Bitmap.createScaledBitmap(horizontal, contentWidth, 48, true);
        try {
            int[] pixels = new int[contentWidth * 48]; resized.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, 48);
            float[] values = new float[3 * 48 * width];
            for (int c = 0; c < 3; c++) for (int y = 0; y < 48; y++) for (int x = 0; x < contentWidth; x++)
                values[c * 48 * width + y * width + x] = ((pixels[y * contentWidth + x] >> (c * 8)) & 255) / 127.5f - 1;
            Recognition first = recognizeTensor(values, width, chosenPolicy == Policy.MEDIUM ? MEDIUM : RECOGNIZER);
            if (chosenPolicy == Policy.SELECTIVE && !first.text().isBlank() && first.confidence() < .95f) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                return recognizeTensor(values, width, MEDIUM).text();
            }
            return first.text();
        } finally {
            if (resized != image && resized != horizontal) resized.recycle();
            if (horizontal != image) horizontal.recycle();
        }
    }

    private Recognition recognizeTensor(float[] values, int width, String model) throws Exception {
        OrtSession session = sessions.open(model);
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, 48, width});
             OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), input))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            long[] shape = output.getInfo().getShape();
            return decodeScored(output.getFloatBuffer(), (int) shape[1], (int) shape[2], characters);
        }
    }

    record Recognition(String text, float confidence) {}

    static String decode(FloatBuffer scores, int steps, int classes, String[] charset) {
        return decodeScored(scores, steps, classes, charset).text();
    }

    static Recognition decodeScored(FloatBuffer scores, int steps, int classes, String[] charset) {
        StringBuilder text = new StringBuilder();
        int previous = -1, emitted = 0;
        float confidence = 0;
        for (int step = 0; step < steps; step++) {
            int best = 0;
            int offset = step * classes;
            float bestScore = scores.get(offset);
            for (int c = 1; c < classes; c++) {
                float score = scores.get(offset + c);
                if (score > bestScore) { best = c; bestScore = score; }
            }
            if (best != 0 && best != previous && best < charset.length) { text.append(charset[best]); confidence += bestScore; emitted++; }
            previous = best;
        }
        return new Recognition(text.toString(), emitted == 0 ? 0 : confidence / emitted);
    }

    List<OcrLine> read(Bitmap image, AtomicBoolean cancelled, OcrEngine.Progress progress) throws Exception {
        return read(image, cancelled, progress, false);
    }

    List<OcrLine> read(Bitmap image, AtomicBoolean cancelled, OcrEngine.Progress progress, boolean horizontalOnly) throws Exception {
        return read(image, cancelled, progress, horizontalOnly, DETECTOR, policy);
    }

    List<OcrLine> read(Bitmap image, AtomicBoolean cancelled, OcrEngine.Progress progress,
                       boolean horizontalOnly, String detectorModel, Policy chosenPolicy) throws Exception {
        ensureOpenCv();
        OcrEngine.Stage detectionStage = detectorModel.equals(TINY_DETECTOR) ? OcrEngine.Stage.PADDLE_TINY_DETECTION
                : detectorModel.equals(MEDIUM_DETECTOR) ? OcrEngine.Stage.PADDLE_MEDIUM_DETECTION : OcrEngine.Stage.PADDLE_DETECTION;
        progress.update(detectionStage, 0, 1);
        List<Point[]> boxes = detect(image, detectorModel);
        List<OcrLine> lines = new ArrayList<>();
        Mat source = toMat(image);
        try {
            int done = 0;
            progress.update(OcrEngine.Stage.PADDLE_RECOGNITION, 0, boxes.size());
            for (Point[] points : boxes) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                int width = Math.max(2, (int) Math.max(distance(points[0], points[1]), distance(points[2], points[3])));
                int height = Math.max(2, (int) Math.max(distance(points[0], points[3]), distance(points[1], points[2])));
                MatOfPoint2f from = new MatOfPoint2f(points), to = new MatOfPoint2f(new Point(0, 0), new Point(width, 0), new Point(width, height), new Point(0, height));
                Mat matrix = Imgproc.getPerspectiveTransform(from, to), crop = new Mat();
                try {
                    Imgproc.warpPerspective(source, crop, matrix, new Size(width, height), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE);
                    Bitmap bitmap = toBitmap(crop);
                    String text;
                    try { text = recognize(bitmap, cancelled, horizontalOnly, chosenPolicy); } finally { bitmap.recycle(); }
                    if (!text.isBlank()) {
                        float left = Float.POSITIVE_INFINITY, top = left, right = 0, bottom = 0;
                        for (Point p : points) { left = Math.min(left, (float)p.x); top = Math.min(top, (float)p.y); right = Math.max(right, (float)p.x); bottom = Math.max(bottom, (float)p.y); }
                        lines.add(new OcrLine(left, top, right, bottom, text, OcrLine.Kind.TEXT, horizontalOnly));
                    }
                } finally { crop.release(); matrix.release(); from.release(); to.release(); }
                progress.update(OcrEngine.Stage.PADDLE_RECOGNITION, ++done, boxes.size());
            }
        } finally { source.release(); }
        return ReadingOrder.sort(lines);
    }

    private List<Point[]> detect(Bitmap image, String detectorModel) throws Exception {
        int h = image.getHeight(), w = image.getWidth();
        double ratio = Math.min(1, 1024.0 / Math.max(h, w));
        int rh = Math.max(32, (int) Math.rint(h * ratio / 32) * 32), rw = Math.max(32, (int) Math.rint(w * ratio / 32) * 32);
        Mat source = toMat(image), resized = new Mat();
        float[] values = new float[3 * rh * rw];
        try {
            Imgproc.resize(source, resized, new Size(rw, rh));
            byte[] bytes = new byte[rh * rw * 3]; resized.get(0, 0, bytes);
            float[] mean = {.485f, .456f, .406f}, std = {.229f, .224f, .225f};
            for (int c = 0; c < 3; c++) for (int i = 0; i < rh * rw; i++) values[c * rh * rw + i] = ((bytes[i * 3 + c] & 255) / 255f - mean[c]) / std[c];
        } finally { source.release(); resized.release(); }
        OrtSession session = sessions.open(detectorModel);
        float[] probabilities;
        int ph, pw;
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), new long[]{1, 3, rh, rw});
             OrtSession.Result result = session.run(Map.of(session.getInputNames().iterator().next(), input))) {
            OnnxTensor tensor = (OnnxTensor) result.get(0);
            long[] shape = tensor.getInfo().getShape(); ph = (int)shape[2]; pw = (int)shape[3];
            probabilities = new float[ph * pw]; tensor.getFloatBuffer().get(probabilities);
        }
        Mat map = new Mat(ph, pw, CvType.CV_32FC1), binary = new Mat(), hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        List<Point[]> boxes = new ArrayList<>();
        try {
            map.put(0, 0, probabilities); Imgproc.threshold(map, binary, .2, 255, Imgproc.THRESH_BINARY); binary.convertTo(binary, CvType.CV_8UC1);
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            for (int i = 0; i < Math.min(3000, contours.size()); i++) {
                MatOfPoint2f contour = new MatOfPoint2f(contours.get(i).toArray());
                RotatedRect rect;
                try { rect = Imgproc.minAreaRect(contour); } finally { contour.release(); }
                if (Math.min(rect.size.width, rect.size.height) < 3) continue;
                Point[] points = new Point[4]; rect.points(points);
                if (meanScore(probabilities, ph, pw, points) < .45) continue;
                // Offset a rectangle by the DB unclip distance; retain parallel edges.
                double distance = rect.size.area() * 1.4 / (2 * (rect.size.width + rect.size.height));
                rect.size.width += 2 * distance; rect.size.height += 2 * distance;
                if (Math.min(rect.size.width, rect.size.height) < 5) continue;
                rect.points(points); points = order(points);
                for (Point p : points) { p.x = Math.max(0, Math.min(w - 1, p.x * w / pw)); p.y = Math.max(0, Math.min(h - 1, p.y * h / ph)); }
                boxes.add(points);
            }
        } finally { map.release(); binary.release(); hierarchy.release(); for (MatOfPoint contour : contours) contour.release(); }
        return boxes;
    }

    static Bitmap improveContrast(Bitmap image) {
        ensureOpenCv();
        Mat source = toMat(image), lab = new Mat(), result = new Mat();
        List<Mat> channels = new ArrayList<>();
        var clahe = Imgproc.createCLAHE(2, new Size(8, 8));
        try {
            Imgproc.cvtColor(source, lab, Imgproc.COLOR_BGR2Lab);
            Core.split(lab, channels);
            clahe.apply(channels.get(0), channels.get(0));
            Core.merge(channels, lab);
            Imgproc.cvtColor(lab, result, Imgproc.COLOR_Lab2BGR);
            return toBitmap(result);
        } finally {
            for (Mat channel : channels) channel.release();
            source.release(); lab.release(); result.release(); clahe.collectGarbage(); clahe.clear();
        }
    }

    private static double meanScore(float[] probabilities, int h, int w, Point[] points) {
        int left = w - 1, top = h - 1, right = 0, bottom = 0;
        for (Point p : points) { left = Math.min(left, (int)Math.floor(p.x)); top = Math.min(top, (int)Math.floor(p.y)); right = Math.max(right, (int)Math.ceil(p.x)); bottom = Math.max(bottom, (int)Math.ceil(p.y)); }
        left = Math.max(0, left); top = Math.max(0, top); right = Math.min(w - 1, right); bottom = Math.min(h - 1, bottom);
        if (left > right || top > bottom) return 0;
        int width = right - left + 1, height = bottom - top + 1;
        Mat mask = Mat.zeros(height, width, CvType.CV_8UC1);
        Point[] shifted = new Point[4]; for (int i = 0; i < 4; i++) shifted[i] = new Point(points[i].x - left, points[i].y - top);
        MatOfPoint polygon = new MatOfPoint(shifted);
        try {
            Imgproc.fillPoly(mask, List.of(polygon), new Scalar(1));
            byte[] included = new byte[width * height]; mask.get(0, 0, included);
            double sum = 0; int count = 0;
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) if (included[y * width + x] != 0) { sum += probabilities[(top + y) * w + left + x]; count++; }
            return count == 0 ? 0 : sum / count;
        } finally { mask.release(); polygon.release(); }
    }

    private static Point[] order(Point[] points) {
        java.util.Arrays.sort(points, Comparator.comparingDouble(p -> p.x));
        Point a = points[0].y <= points[1].y ? points[0] : points[1], d = a == points[0] ? points[1] : points[0];
        Point b = points[2].y <= points[3].y ? points[2] : points[3], c = b == points[2] ? points[3] : points[2];
        return new Point[]{a, b, c, d};
    }

    private static double distance(Point a, Point b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    private static Mat toMat(Bitmap bitmap) {
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()]; bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        byte[] bytes = new byte[pixels.length * 3];
        for (int i = 0; i < pixels.length; i++) { bytes[i * 3] = (byte)pixels[i]; bytes[i * 3 + 1] = (byte)(pixels[i] >> 8); bytes[i * 3 + 2] = (byte)(pixels[i] >> 16); }
        Mat result = new Mat(bitmap.getHeight(), bitmap.getWidth(), CvType.CV_8UC3); result.put(0, 0, bytes); return result;
    }

    private static Bitmap toBitmap(Mat image) {
        byte[] bytes = new byte[image.rows() * image.cols() * 3]; image.get(0, 0, bytes);
        int[] pixels = new int[image.rows() * image.cols()];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xFF000000 | (bytes[i * 3 + 2] & 255) << 16 | (bytes[i * 3 + 1] & 255) << 8 | bytes[i * 3] & 255;
        return Bitmap.createBitmap(pixels, image.cols(), image.rows(), Bitmap.Config.ARGB_8888);
    }

    private static synchronized void ensureOpenCv() {
        try { Core.getVersionString(); }
        catch (UnsatisfiedLinkError notLoaded) { if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Cannot load OpenCV", notLoaded); }
    }
}
