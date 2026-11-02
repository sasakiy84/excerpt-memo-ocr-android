package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.SurfaceRequest;
import androidx.core.content.ContextCompat;

/** CameraX preview with one buffer-to-view transform shared by display and focus. */
public final class CameraPreview extends FrameLayout implements TextureView.SurfaceTextureListener {
    private SurfaceRequest pending, current;
    private SurfaceRequest.TransformationInfo info;
    private Size resolution;
    private final Matrix bufferToView = new Matrix();
    private Preview useCase;
    private boolean frontCamera;
    private final TextureView textureView;
    private final java.util.Map<SurfaceTexture, Integer> inUse = new java.util.HashMap<>();
    private final java.util.Set<SurfaceTexture> retired = new java.util.HashSet<>();
    public CameraPreview(Context context, AttributeSet attributes) {
        super(context, attributes);
        setClipChildren(true);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(this);
        addView(textureView, new FrameLayout.LayoutParams(1, 1));
    }
    Preview.SurfaceProvider getSurfaceProvider() { return request -> {
        if (pending != null) pending.willNotProvideSurface();
        pending = request; current = request; resolution = request.getResolution(); info = null;
        textureView.setLayoutParams(new FrameLayout.LayoutParams(resolution.getWidth(), resolution.getHeight()));
        request.addRequestCancellationListener(ContextCompat.getMainExecutor(getContext()), () -> {
            if (pending == request) pending = null;
        });
        request.setTransformationInfoListener(ContextCompat.getMainExecutor(getContext()), value -> {
            if (current == request) { info = value; updateTransform(); }
        });
        provide();
    }; }
    void setUseCase(Preview preview, boolean front) { useCase = preview; frontCamera = front; }
    private void provide() {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null || pending == null) return;
        SurfaceRequest request = pending; pending = null;
        texture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
        Surface surface = new Surface(texture);
        inUse.merge(texture, 1, Integer::sum);
        request.provideSurface(surface, ContextCompat.getMainExecutor(getContext()), result -> {
            surface.release();
            int remaining = inUse.get(texture) - 1;
            if (remaining == 0) {
                inUse.remove(texture);
                if (retired.remove(texture)) texture.release();
            } else inUse.put(texture, remaining);
        });
        updateTransform();
    }
    private void updateTransform() {
        if (resolution == null || info == null || getWidth() == 0 || getHeight() == 0) return;
        bufferToView.set(transform(resolution.getWidth(), resolution.getHeight(), new RectF(info.getCropRect()),
                info.getRotationDegrees(), frontCamera && info.hasCameraTransform(), getWidth(), getHeight()));
        // TextureView already applies the camera's sensor transform. Rotate its pixels
        // only for the remaining display rotation, and scale the upright surface bounds.
        RectF bounds = new RectF(0, 0, resolution.getWidth(), resolution.getHeight());
        bufferToView.mapRect(bounds);
        int displayRotation = useCase == null ? -1 : useCase.getTargetRotation();
        if (displayRotation < 0) displayRotation = getDisplay() == null ? 0 : getDisplay().getRotation();
        int remaining = info.hasCameraTransform() ? -displayRotation * 90 : info.getRotationDegrees();
        textureView.setTransform(textureCorrection(resolution.getWidth(), resolution.getHeight(), remaining));
        textureView.setPivotX(0); textureView.setPivotY(0);
        textureView.setScaleX(bounds.width() / resolution.getWidth());
        textureView.setScaleY(bounds.height() / resolution.getHeight());
        textureView.setTranslationX(bounds.left); textureView.setTranslationY(bounds.top);
    }
    static Matrix textureCorrection(int width, int height, int rotation) {
        Matrix matrix = new Matrix(); matrix.setRotate(rotation, width / 2f, height / 2f);
        RectF rotated = new RectF(); matrix.mapRect(rotated, new RectF(0, 0, width, height));
        matrix.postScale(width / rotated.width(), height / rotated.height(), width / 2f, height / 2f);
        return matrix;
    }

    static Matrix transform(int width, int height, RectF crop, int rotation, boolean mirror, int viewWidth, int viewHeight) {
        Matrix matrix = new Matrix(); matrix.setRotate(rotation, width / 2f, height / 2f);
        RectF rotated = new RectF(); matrix.mapRect(rotated, crop);
        matrix.postTranslate(-rotated.centerX(), -rotated.centerY());
        float scale = Math.max(viewWidth / rotated.width(), viewHeight / rotated.height());
        matrix.postScale(mirror ? -scale : scale, scale);
        matrix.postTranslate(viewWidth / 2f, viewHeight / 2f); return matrix;
    }
    MeteringPoint meteringPoint(float x, float y) {
        Matrix inverse = new Matrix();
        if (resolution == null || info == null || !bufferToView.invert(inverse))
            return new SurfaceOrientedMeteringPointFactory(1, 1).createPoint(.5f, .5f);
        float[] point = {x, y}; inverse.mapPoints(point);
        return new SurfaceOrientedMeteringPointFactory(resolution.getWidth(), resolution.getHeight(), useCase)
                .createPoint(point[0], point[1]);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (!inUse.isEmpty() && pending == null && current != null) current.invalidate();
        else provide();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { updateTransform(); }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { super.onSizeChanged(width, height, oldWidth, oldHeight); updateTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (pending != null) { pending.willNotProvideSurface(); pending = null; }
        if (!inUse.containsKey(texture)) return true;
        retired.add(texture);
        return false; // CameraX owns the buffer until its result callback.
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    @Override public boolean performClick() { super.performClick(); return true; }
}
