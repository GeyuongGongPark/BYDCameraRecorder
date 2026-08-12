package com.ggpark.byddashcam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.TextureView;

public final class ZoomTextureView extends TextureView {
    public interface ZoomListener {
        void onZoomChanged(float scale);
    }

    private final PinchPanController zoomController;
    private ZoomListener zoomListener;

    public ZoomTextureView(Context context) {
        this(context, null);
    }

    public ZoomTextureView(Context context, AttributeSet attributes) {
        super(context, attributes);
        zoomController = new PinchPanController(this);
        zoomController.setListener(
                new PinchPanController.Listener() {
                    @Override
                    public void onScaleChanged(float scale) {
                        if (zoomListener != null) {
                            zoomListener.onZoomChanged(scale);
                        }
                    }
                });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        zoomController.handleTouch(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                && !zoomController.hasMoved()) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void resetZoom() {
        zoomController.reset();
    }

    public void setZoomListener(ZoomListener listener) {
        zoomListener = listener;
        zoomController.setListener(
                new PinchPanController.Listener() {
                    @Override
                    public void onScaleChanged(float scale) {
                        if (zoomListener != null) {
                            zoomListener.onZoomChanged(scale);
                        }
                    }
                });
    }
}
