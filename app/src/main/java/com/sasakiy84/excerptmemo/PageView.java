package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One-finger rectangle selection, line toggles, and two-finger zoom/pan in page coordinates. */
public final class PageView extends View {
    public interface SelectionListener { void changed(Rect region, Set<Integer> selected); }
    private Bitmap bitmap;
    private List<OcrLine> lines = List.of();
    private final Set<Integer> selected = new HashSet<>();
    private final RectF region = new RectF();
    private SelectionListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float zoom = 1, panX, panY, startX, startY, downX, downY, lastFocusX, lastFocusY;
    private boolean multiplePointers, dragged, lineSelectionStarted;
    private int keyboardLine = -1;
    private final ScaleGestureDetector scaleDetector;

    public PageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float oldScale = scale();
                float imageX = (detector.getFocusX() - originX()) / oldScale;
                float imageY = (detector.getFocusY() - originY()) / oldScale;
                zoom = Math.max(1, Math.min(6, zoom * detector.getScaleFactor()));
                panX = detector.getFocusX() - imageX * scale() - centeredX();
                panY = detector.getFocusY() - imageY * scale() - centeredY();
                constrainPan();
                invalidate();
                return true;
            }
        });
    }

    public void setSelectionListener(SelectionListener listener) { this.listener = listener; }

    public void setPage(Bitmap page, List<OcrLine> result, Set<Integer> chosen, Rect selection) {
        if (bitmap != page) { zoom = 1; panX = 0; panY = 0; }
        if (bitmap != page || lines != result) lineSelectionStarted = !chosen.isEmpty() && chosen.size() < result.size();
        bitmap = page;
        lines = result;
        selected.clear();
        selected.addAll(chosen);
        region.set(selection);
        invalidate();
    }

    public void selectAll() {
        if (bitmap == null) return;
        region.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        selected.clear();
        for (int i = 0; i < lines.size(); i++) selected.add(i);
        lineSelectionStarted = false;
        notifySelection();
        invalidate();
    }

    private float scale() {
        if (bitmap == null) return 1;
        return Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()) * zoom;
    }
    private float centeredX() { return bitmap == null ? 0 : (getWidth() - bitmap.getWidth() * scale()) / 2; }
    private float centeredY() { return bitmap == null ? 0 : (getHeight() - bitmap.getHeight() * scale()) / 2; }
    private float originX() { return centeredX() + panX; }
    private float originY() { return centeredY() + panY; }
    private PointF pagePoint(float x, float y) {
        return new PointF(Math.max(0, Math.min(bitmap.getWidth(), (x - originX()) / scale())),
                Math.max(0, Math.min(bitmap.getHeight(), (y - originY()) / scale())));
    }
    private void constrainPan() {
        if (bitmap == null) return;
        float limitX = Math.max(0, (bitmap.getWidth() * scale() - getWidth()) / 2);
        float limitY = Math.max(0, (bitmap.getHeight() * scale() - getHeight()) / 2);
        panX = Math.max(-limitX, Math.min(limitX, panX));
        panY = Math.max(-limitY, Math.min(limitY, panY));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(28, 34, 31));
        if (bitmap == null) return;
        canvas.save();
        canvas.translate(originX(), originY());
        canvas.scale(scale(), scale());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0, 0, paint);
        float stroke = 2 * getResources().getDisplayMetrics().density / scale();
        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(selected.contains(i) ? 0x6638BFA0 : 0x1648A8FF);
            canvas.drawRect(line.left(), line.top(), line.right(), line.bottom(), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke / 2);
            paint.setColor(selected.contains(i) ? 0xFF087D63 : 0x9948A8FF);
            canvas.drawRect(line.left(), line.top(), line.right(), line.bottom(), paint);
        }
        if (!region.isEmpty()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0xFF71E9C7);
            canvas.drawRect(region, paint);
            if (lines.isEmpty()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x55000000);
                canvas.drawRect(0, 0, bitmap.getWidth(), region.top, paint);
                canvas.drawRect(0, region.bottom, bitmap.getWidth(), bitmap.getHeight(), paint);
                canvas.drawRect(0, region.top, region.left, region.bottom, paint);
                canvas.drawRect(region.right, region.top, bitmap.getWidth(), region.bottom, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || !isEnabled()) return false;
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                getParent().requestDisallowInterceptTouchEvent(true);
                multiplePointers = false;
                dragged = false;
                downX = event.getX(); downY = event.getY();
                PointF point = pagePoint(downX, downY);
                startX = point.x; startY = point.y;
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                multiplePointers = true;
                lastFocusX = (event.getX(0) + event.getX(1)) / 2;
                lastFocusY = (event.getY(0) + event.getY(1)) / 2;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (event.getPointerCount() >= 2) {
                    float focusX = (event.getX(0) + event.getX(1)) / 2;
                    float focusY = (event.getY(0) + event.getY(1)) / 2;
                    panX += focusX - lastFocusX; panY += focusY - lastFocusY;
                    lastFocusX = focusX; lastFocusY = focusY;
                    constrainPan();
                    invalidate();
                } else if (!multiplePointers && Math.hypot(event.getX() - downX, event.getY() - downY) > 8 * getResources().getDisplayMetrics().density) {
                    dragged = true;
                    lineSelectionStarted = true;
                    PointF point = pagePoint(event.getX(), event.getY());
                    region.set(Math.min(startX, point.x), Math.min(startY, point.y), Math.max(startX, point.x), Math.max(startY, point.y));
                    selected.clear();
                    for (int i = 0; i < lines.size(); i++) {
                        OcrLine line = lines.get(i);
                        if (line.intersects(region.left, region.top, region.right, region.bottom)) selected.add(i);
                    }
                    invalidate();
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (!multiplePointers) {
                    if (!dragged) { toggleLine(startX, startY); performClick(); }
                    if (region.width() < 2 || region.height() < 2) region.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
                    notifySelection();
                }
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            case MotionEvent.ACTION_CANCEL -> getParent().requestDisallowInterceptTouchEvent(false);
            default -> { }
        }
        return true;
    }

    private void toggleLine(float x, float y) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            OcrLine line = lines.get(i);
            if (x >= line.left() && x <= line.right() && y >= line.top() && y <= line.bottom()) {
                if (!lineSelectionStarted) { selected.clear(); lineSelectionStarted = true; }
                if (!selected.remove(i)) selected.add(i);
                region.setEmpty();
                invalidate();
                return;
            }
        }
    }

    private void notifySelection() {
        if (listener == null || bitmap == null) return;
        Rect bounds = region.isEmpty() ? new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
                : new Rect((int) region.left, (int) region.top, (int) Math.ceil(region.right), (int) Math.ceil(region.bottom));
        listener.changed(bounds, new HashSet<>(selected));
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (lines.isEmpty()) return super.onKeyDown(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            keyboardLine = (keyboardLine + 1) % lines.size();
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            keyboardLine = Math.floorMod(keyboardLine - 1, lines.size());
        } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (keyboardLine < 0) keyboardLine = 0;
            OcrLine line = lines.get(keyboardLine);
            toggleLine(line.centerX(), line.centerY());
            notifySelection();
            return true;
        } else return super.onKeyDown(keyCode, event);
        setContentDescription(lines.get(keyboardLine).text());
        return true;
    }
}
