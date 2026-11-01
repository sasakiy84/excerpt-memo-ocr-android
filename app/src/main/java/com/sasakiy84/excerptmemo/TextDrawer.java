package com.sasakiy84.excerptmemo;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/** Continuous resizing from the drawer handle; text scrolling stays inside the editor. */
public final class TextDrawer extends LinearLayout {
    public interface SizeListener { void changed(float fraction); }
    private float fraction = .28f, downY;
    private int downHeight;
    private boolean expanded;
    private SizeListener listener;
    public TextDrawer(Context context, AttributeSet attrs) { super(context, attrs); }
    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        View handle = findViewById(R.id.drawer_handle);
        handle.setOnTouchListener((view, event) -> {
            if (expanded) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { downY = event.getRawY(); downHeight = getHeight(); getParent().requestDisallowInterceptTouchEvent(true); }
                case MotionEvent.ACTION_MOVE -> resize(Math.round(downHeight + downY - event.getRawY()));
                case MotionEvent.ACTION_UP -> { getParent().requestDisallowInterceptTouchEvent(false); view.performClick(); }
                case MotionEvent.ACTION_CANCEL -> getParent().requestDisallowInterceptTouchEvent(false);
                default -> { }
            }
            return true;
        });
        handle.setOnKeyListener((view, code, event) -> {
            if (expanded || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            resize(getHeight() + Math.round((code == KeyEvent.KEYCODE_DPAD_UP ? 32 : -32) * getResources().getDisplayMetrics().density));
            return true;
        });
    }
    public void setSizeListener(SizeListener listener) { this.listener = listener; }
    public void bind(float fraction, boolean expanded) {
        this.fraction = fraction; this.expanded = expanded;
        findViewById(R.id.drawer_handle).setVisibility(expanded ? GONE : VISIBLE);
        updateHeight();
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ((View) getParent()).addOnLayoutChangeListener(parentLayout);
    }
    @Override protected void onDetachedFromWindow() {
        ((View) getParent()).removeOnLayoutChangeListener(parentLayout); super.onDetachedFromWindow();
    }
    private final OnLayoutChangeListener parentLayout = (v, l, t, r, b, ol, ot, or, ob) -> updateHeight();
    private int available() {
        if (!(getParent() instanceof View parent)) return 0;
        LayoutParams params = (LayoutParams) getLayoutParams();
        return Math.max(0, parent.getHeight() - params.topMargin - params.bottomMargin);
    }
    private void resize(int height) {
        int available = available(); if (available <= 0) return;
        int minimum = Math.min(available, Math.round(96 * getResources().getDisplayMetrics().density));
        fraction = Math.max(minimum, Math.min(available, height)) / (float) available;
        if (listener != null) listener.changed(fraction);
        updateHeight();
    }
    private void updateHeight() {
        int available = available(); if (available <= 0) return;
        LayoutParams params = (LayoutParams) getLayoutParams();
        int minimum = Math.min(available, Math.round(96 * getResources().getDisplayMetrics().density));
        int target = expanded ? 0 : Math.max(minimum, Math.min(available, Math.round(available * fraction)));
        float weight = expanded ? 1 : 0;
        if (params.height != target || params.weight != weight) { params.height = target; params.weight = weight; setLayoutParams(params); }
    }
}
