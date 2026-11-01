package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Magnifier;
import java.util.List;

/** Grapheme selection handles over the photograph, with two-finger zoom and pan. */
public final class PageView extends View {
    public interface SelectionListener { void changed(int start, int end); }
    private Bitmap bitmap;
    private TextGeometry geometry = new TextGeometry(List.of());
    private int selectionStart, selectionEnd;
    private SelectionListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float zoom = 1, panX, panY, downX, downY, lastFocusX, lastFocusY, handleDeltaX, handleDeltaY;
    private boolean multiplePointers, dragged;
    private int activeHandle, anchor;
    private Magnifier magnifier;
    private final ScaleGestureDetector scaleDetector;

    public PageView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setFocusable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float imageX = (detector.getFocusX() - originX()) / scale();
                float imageY = (detector.getFocusY() - originY()) / scale();
                zoom = Math.max(1, Math.min(6, zoom * detector.getScaleFactor()));
                panX = detector.getFocusX() - imageX * scale() - centeredX();
                panY = detector.getFocusY() - imageY * scale() - centeredY();
                constrainPan(); invalidate(); return true;
            }
        });
    }

    public void setSelectionListener(SelectionListener listener) { this.listener = listener; }

    public void setPage(Bitmap page, List<OcrLine> lines, int start, int end) {
        if (bitmap != page) { zoom = 1; panX = 0; panY = 0; }
        bitmap = page;
        if (geometry.lines != lines) geometry = new TextGeometry(lines);
        selectionStart = geometry.snap(start, false);
        selectionEnd = geometry.snap(end, true);
        invalidate();
    }

    public void selectAll() {
        selectionStart = 0; selectionEnd = geometry.length();
        notifySelection(); invalidate();
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private float scale() {
        return bitmap == null ? 1 : Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()) * zoom;
    }
    private float centeredX() { return bitmap == null ? 0 : (getWidth() - bitmap.getWidth() * scale()) / 2; }
    private float centeredY() { return bitmap == null ? 0 : (getHeight() - bitmap.getHeight() * scale()) / 2; }
    private float originX() { return centeredX() + panX; }
    private float originY() { return centeredY() + panY; }
    private PointF pagePoint(float x, float y) {
        return new PointF((x - originX()) / scale(), (y - originY()) / scale());
    }
    private PointF screenPoint(PointF point) { return new PointF(originX() + point.x * scale(), originY() + point.y * scale()); }
    private void constrainPan() {
        if (bitmap == null) return;
        float limitX = Math.max(0, (bitmap.getWidth() * scale() - getWidth()) / 2);
        float limitY = Math.max(0, (bitmap.getHeight() * scale() - getHeight()) / 2);
        panX = Math.max(-limitX, Math.min(limitX, panX)); panY = Math.max(-limitY, Math.min(limitY, panY));
    }

    private PointF handleCenter(boolean end) {
        PointF point = screenPoint(geometry.endpoint(end ? selectionEnd : selectionStart, end));
        point.offset(dp(end ? 8 : -8), dp(12));
        // Keep handles touchable when the text meets the edge of the photograph/view.
        point.x = Math.max(dp(12), Math.min(getWidth() - dp(12), point.x));
        point.y = Math.max(dp(12), Math.min(getHeight() - dp(12), point.y));
        return point;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_canvas));
        if (bitmap == null) return;
        canvas.save(); canvas.translate(originX(), originY()); canvas.scale(scale(), scale());
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0, 0, paint);
        for (int i = 0; i < geometry.lines.size(); i++) {
            OcrLine line = geometry.lines.get(i);
            paint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_line_hint));
            canvas.drawRect(line.left(), line.top(), line.right(), line.bottom(), paint);
            RectF selected = geometry.highlight(i, selectionStart, selectionEnd);
            paint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_highlight)); canvas.drawRect(selected, paint);
        }
        canvas.restore();
        if (selectionStart < selectionEnd) { drawHandle(canvas, false); drawHandle(canvas, true); }
    }

    private void drawHandle(Canvas canvas, boolean end) {
        PointF edge = screenPoint(geometry.endpoint(end ? selectionEnd : selectionStart, end));
        PointF center = handleCenter(end);
        paint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_handle)); paint.setStrokeWidth(dp(2));
        canvas.drawLine(edge.x, edge.y - dp(10), edge.x, edge.y, paint);
        canvas.drawLine(edge.x, edge.y, center.x, center.y, paint);
        canvas.drawCircle(center.x, center.y, dp(9), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || !isEnabled()) return false;
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                requestFocus();
                getParent().requestDisallowInterceptTouchEvent(true);
                multiplePointers = false; dragged = false; activeHandle = 0;
                downX = event.getX(); downY = event.getY();
                if (selectionStart < selectionEnd) {
                    PointF first = handleCenter(false), last = handleCenter(true);
                    float a = (float) Math.hypot(downX - first.x, downY - first.y);
                    float b = (float) Math.hypot(downX - last.x, downY - last.y);
                    if (Math.min(a, b) <= dp(24)) {
                        activeHandle = a < b ? 1 : 2;
                        PointF edge = screenPoint(geometry.endpoint(activeHandle == 1 ? selectionStart : selectionEnd, activeHandle == 2));
                        handleDeltaX = downX - edge.x; handleDeltaY = downY - edge.y;
                    }
                }
                PointF point = pagePoint(downX, downY);
                anchor = geometry.nearest(point.x, point.y, false);
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                multiplePointers = true; dismissMagnifier();
                lastFocusX = (event.getX(0) + event.getX(1)) / 2;
                lastFocusY = (event.getY(0) + event.getY(1)) / 2;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (event.getPointerCount() >= 2) {
                    float focusX = (event.getX(0) + event.getX(1)) / 2, focusY = (event.getY(0) + event.getY(1)) / 2;
                    panX += focusX - lastFocusX; panY += focusY - lastFocusY;
                    lastFocusX = focusX; lastFocusY = focusY;
                    constrainPan(); invalidate();
                } else if (!multiplePointers && Math.hypot(event.getX() - downX, event.getY() - downY) > dp(4)) {
                    dragged = true;
                    moveSelection(event.getX(), event.getY());
                    if (isHardwareAccelerated()) {
                        if (magnifier == null) magnifier = new Magnifier.Builder(this).build();
                        magnifier.show(event.getX() - (activeHandle == 0 ? 0 : handleDeltaX),
                                event.getY() - (activeHandle == 0 ? 0 : handleDeltaY));
                    }
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (!multiplePointers && !geometry.lines.isEmpty()) {
                    if (!dragged && activeHandle == 0) { selectionStart = anchor; selectionEnd = geometry.next(anchor); notifySelection(); }
                    performClick(); invalidate();
                }
                dismissMagnifier(); getParent().requestDisallowInterceptTouchEvent(false);
            }
            case MotionEvent.ACTION_CANCEL -> { dismissMagnifier(); getParent().requestDisallowInterceptTouchEvent(false); }
            default -> { }
        }
        return true;
    }

    private void moveSelection(float x, float y) {
        if (geometry.lines.isEmpty()) return;
        PointF point = pagePoint(x - (activeHandle == 0 ? 0 : handleDeltaX), y - (activeHandle == 0 ? 0 : handleDeltaY));
        int offset = geometry.nearest(point.x, point.y, activeHandle != 0);
        if (activeHandle == 1) selectionStart = Math.min(offset, geometry.previous(selectionEnd));
        else if (activeHandle == 2) selectionEnd = Math.max(offset, geometry.next(selectionStart));
        else { selectionStart = Math.min(anchor, offset); selectionEnd = geometry.next(Math.max(anchor, offset)); }
        notifySelection(); invalidate();
    }

    private void notifySelection() { if (listener != null) listener.changed(selectionStart, selectionEnd); }
    private void dismissMagnifier() { if (magnifier != null) magnifier.dismiss(); }
    @Override protected void onDetachedFromWindow() { dismissMagnifier(); super.onDetachedFromWindow(); }
    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (geometry.lines.isEmpty()) return super.onKeyDown(key, event);
        boolean forward = key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean backward = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP;
        if (!forward && !backward) return super.onKeyDown(key, event);
        if (event.isShiftPressed()) {
            selectionEnd = forward ? geometry.next(selectionEnd) : Math.max(geometry.next(selectionStart), geometry.previous(selectionEnd));
        } else {
            selectionStart = forward ? Math.min(geometry.previous(geometry.length()), geometry.next(selectionStart)) : geometry.previous(selectionStart);
            selectionEnd = geometry.next(selectionStart);
        }
        notifySelection(); invalidate(); return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setText(geometry.text); info.setTextSelection(selectionStart, selectionEnd);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION && arguments != null && isEnabled()) {
            int start = geometry.snap(arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT), false);
            int end = geometry.snap(arguments.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT), true);
            if (start >= end) return false;
            selectionStart = start; selectionEnd = end; notifySelection(); invalidate(); return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
