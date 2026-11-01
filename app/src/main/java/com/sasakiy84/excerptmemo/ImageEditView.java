package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

/** Crop handles operate in the rotated image space used by the exported bitmap. */
public final class ImageEditView extends View {
    private Bitmap image;
    private float degrees;
    private final RectF crop = new RectF(0, 0, 1, 1), frame = new RectF(), downCrop = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private int edges;
    private float downX, downY;
    public ImageEditView(Context context) { super(context); setContentDescription(context.getString(R.string.crop_description)); }
    public void setImage(Bitmap image) { this.image = image; invalidate(); }
    public void setAngle(float degrees) { this.degrees = degrees; invalidate(); }
    public RectF getCrop() { return new RectF(crop); }
    public void setCrop(RectF bounds) { crop.set(bounds); invalidate(); }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_canvas));
        if (image == null) return;
        RectF bounds = new RectF();
        Matrix matrix = ImageEdits.rotation(image, degrees, bounds);
        float scale = Math.min((getWidth() - dp(40)) / bounds.width(), (getHeight() - dp(40)) / bounds.height());
        float left = (getWidth() - bounds.width() * scale) / 2, top = (getHeight() - bounds.height() * scale) / 2;
        frame.set(left, top, left + bounds.width() * scale, top + bounds.height() * scale);
        matrix.postScale(scale, scale); matrix.postTranslate(left, top);
        paint.setColor(Color.WHITE); canvas.drawRect(frame, paint); canvas.drawBitmap(image, matrix, paint);
        RectF box = new RectF(left + crop.left * frame.width(), top + crop.top * frame.height(),
                left + crop.right * frame.width(), top + crop.bottom * frame.height());
        paint.setColor(0x99000000);
        canvas.drawRect(frame.left, frame.top, frame.right, box.top, paint);
        canvas.drawRect(frame.left, box.bottom, frame.right, frame.bottom, paint);
        canvas.drawRect(frame.left, box.top, box.left, box.bottom, paint);
        canvas.drawRect(box.right, box.top, frame.right, box.bottom, paint);
        paint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_muted), 128)); paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(box, paint);
        for (int i = 1; i < 3; i++) {
            canvas.drawLine(box.left + box.width() * i / 3, box.top, box.left + box.width() * i / 3, box.bottom, paint);
            canvas.drawLine(box.left, box.top + box.height() * i / 3, box.right, box.top + box.height() * i / 3, paint);
        }
        paint.setColor(androidx.core.content.ContextCompat.getColor(getContext(), R.color.excerpt_handle)); paint.setStrokeWidth(dp(4));
        for (float x : new float[]{box.left, box.right}) for (float y : new float[]{box.top, box.bottom}) {
            canvas.drawLine(x, y, x + (x == box.left ? dp(20) : -dp(20)), y, paint);
            canvas.drawLine(x, y, x, y + (y == box.top ? dp(20) : -dp(20)), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (image == null || frame.isEmpty()) return false;
        float x = (event.getX() - frame.left) / frame.width(), y = (event.getY() - frame.top) / frame.height();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                float hx = dp(24) / frame.width(), hy = dp(24) / frame.height();
                if (x < crop.left - hx || x > crop.right + hx || y < crop.top - hy || y > crop.bottom + hy) return false;
                edges = 0;
                if (Math.abs(x - crop.left) < hx) edges |= 1; else if (Math.abs(x - crop.right) < hx) edges |= 2;
                if (Math.abs(y - crop.top) < hy) edges |= 4; else if (Math.abs(y - crop.bottom) < hy) edges |= 8;
                downX = x; downY = y; downCrop.set(crop);
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = x - downX, dy = y - downY;
                crop.set(downCrop);
                float minX = Math.min(1, Math.max(0.04f, dp(32) / frame.width()));
                float minY = Math.min(1, Math.max(0.04f, dp(32) / frame.height()));
                if (edges == 0) crop.offset(Math.max(-crop.left, Math.min(1 - crop.right, dx)), Math.max(-crop.top, Math.min(1 - crop.bottom, dy)));
                else {
                    if ((edges & 1) != 0) crop.left = Math.max(0, Math.min(crop.right - minX, crop.left + dx));
                    if ((edges & 2) != 0) crop.right = Math.min(1, Math.max(crop.left + minX, crop.right + dx));
                    if ((edges & 4) != 0) crop.top = Math.max(0, Math.min(crop.bottom - minY, crop.top + dy));
                    if ((edges & 8) != 0) crop.bottom = Math.min(1, Math.max(crop.top + minY, crop.bottom + dy));
                }
                invalidate();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { getParent().requestDisallowInterceptTouchEvent(false); performClick(); }
            default -> { }
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
