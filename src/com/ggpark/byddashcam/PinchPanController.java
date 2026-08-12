package com.ggpark.byddashcam;

import android.view.MotionEvent;
import android.view.View;

final class PinchPanController {
    interface Listener {
        void onScaleChanged(float scale);
    }

    private final View target;
    private float currentScale = 1f;
    private float lastPinchFocusX;
    private float lastPinchFocusY;
    private float lastPinchSpan;
    private float lastTouchX;
    private float lastTouchY;
    private boolean moved;
    private boolean panAnchorReady;
    private boolean pinching;
    private Listener listener;

    PinchPanController(View target) {
        this.target = target;
    }

    boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ensureCenteredPivot();
                lastTouchX = event.getRawX();
                lastTouchY = event.getRawY();
                moved = false;
                panAnchorReady = true;
                pinching = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2) {
                    beginPinch(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    updatePinch(event);
                } else if (!pinching && currentScale > 1f) {
                    float nextX = event.getRawX();
                    float nextY = event.getRawY();
                    if (!panAnchorReady) {
                        lastTouchX = nextX;
                        lastTouchY = nextY;
                        panAnchorReady = true;
                        break;
                    }
                    float deltaX = nextX - lastTouchX;
                    float deltaY = nextY - lastTouchY;
                    if (deltaX != 0f || deltaY != 0f) {
                        target.setTranslationX(
                                target.getTranslationX() + deltaX);
                        target.setTranslationY(
                                target.getTranslationY() + deltaY);
                        clampTranslation();
                        moved = true;
                    }
                    lastTouchX = nextX;
                    lastTouchY = nextY;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pinching = false;
                panAnchorReady = false;
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                pinching = false;
                panAnchorReady = false;
                break;
            default:
                break;
        }
        return currentScale > 1f
                || event.getPointerCount() > 1
                || pinching;
    }

    boolean hasMoved() {
        return moved;
    }

    void reset() {
        currentScale = 1f;
        target.setPivotX(target.getWidth() / 2f);
        target.setPivotY(target.getHeight() / 2f);
        target.setScaleX(1f);
        target.setScaleY(1f);
        target.setTranslationX(0f);
        target.setTranslationY(0f);
        moved = false;
        panAnchorReady = false;
        pinching = false;
        notifyScaleChanged();
    }

    void setListener(Listener listener) {
        this.listener = listener;
        notifyScaleChanged();
    }

    private void clampTranslation() {
        float maximumX =
                target.getWidth() * (currentScale - 1f) / 2f;
        float maximumY =
                target.getHeight() * (currentScale - 1f) / 2f;
        target.setTranslationX(
                Math.max(
                        -maximumX,
                        Math.min(maximumX, target.getTranslationX())));
        target.setTranslationY(
                Math.max(
                        -maximumY,
                        Math.min(maximumY, target.getTranslationY())));
    }

    private void beginPinch(MotionEvent event) {
        ensureCenteredPivot();
        lastPinchFocusX = transformedFocusX(event);
        lastPinchFocusY = transformedFocusY(event);
        lastPinchSpan = transformedSpan(event);
        pinching = true;
        panAnchorReady = false;
        moved = true;
    }

    private void ensureCenteredPivot() {
        float centerX = target.getWidth() / 2f;
        float centerY = target.getHeight() / 2f;
        if (target.getPivotX() != centerX
                || target.getPivotY() != centerY) {
            target.setPivotX(centerX);
            target.setPivotY(centerY);
        }
    }

    private float transformedFocusX(MotionEvent event) {
        return (
                transformedPointerX(event, 0)
                        + transformedPointerX(event, 1))
                / 2f;
    }

    private float transformedFocusY(MotionEvent event) {
        return (
                transformedPointerY(event, 0)
                        + transformedPointerY(event, 1))
                / 2f;
    }

    private float transformedPointerX(
            MotionEvent event,
            int pointerIndex) {
        return target.getPivotX()
                + currentScale
                        * (event.getX(pointerIndex) - target.getPivotX())
                + target.getTranslationX();
    }

    private float transformedPointerY(
            MotionEvent event,
            int pointerIndex) {
        return target.getPivotY()
                + currentScale
                        * (event.getY(pointerIndex) - target.getPivotY())
                + target.getTranslationY();
    }

    private float transformedSpan(MotionEvent event) {
        float deltaX =
                transformedPointerX(event, 1)
                        - transformedPointerX(event, 0);
        float deltaY =
                transformedPointerY(event, 1)
                        - transformedPointerY(event, 0);
        return (float) Math.hypot(deltaX, deltaY);
    }

    private void updatePinch(MotionEvent event) {
        if (!pinching) {
            beginPinch(event);
            return;
        }
        float span = transformedSpan(event);
        if (lastPinchSpan <= 0f || span <= 0f) {
            beginPinch(event);
            return;
        }
        float focusX = transformedFocusX(event);
        float focusY = transformedFocusY(event);
        float requestedScale =
                Math.max(1f, currentScale * span / lastPinchSpan);
        if (Float.isNaN(requestedScale)
                || Float.isInfinite(requestedScale)) {
            beginPinch(event);
            return;
        }
        float ratio = requestedScale / currentScale;
        float centerX = target.getPivotX();
        float centerY = target.getPivotY();
        float translationX =
                focusX
                        - centerX
                        - ratio
                                * (lastPinchFocusX
                                        - centerX
                                        - target.getTranslationX());
        float translationY =
                focusY
                        - centerY
                        - ratio
                                * (lastPinchFocusY
                                        - centerY
                                        - target.getTranslationY());
        currentScale = requestedScale;
        target.setScaleX(currentScale);
        target.setScaleY(currentScale);
        target.setTranslationX(translationX);
        target.setTranslationY(translationY);
        if (currentScale <= 1f) {
            target.setTranslationX(0f);
            target.setTranslationY(0f);
        } else {
            clampTranslation();
        }
        lastPinchFocusX = focusX;
        lastPinchFocusY = focusY;
        lastPinchSpan = span;
        moved = true;
        notifyScaleChanged();
    }

    private void notifyScaleChanged() {
        if (listener != null) {
            listener.onScaleChanged(currentScale);
        }
    }
}
