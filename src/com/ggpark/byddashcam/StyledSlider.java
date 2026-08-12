package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

public final class StyledSlider extends View {
    public interface Listener {
        void onValueChanged(int value);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int maximum;
    private Listener listener;
    private int value;

    public StyledSlider(Context context, int maximum) {
        super(context);
        this.maximum = Math.max(1, maximum);
        setFocusable(true);
        setClickable(true);
        setContentDescription("Fisheye edge crop");
    }

    public int getValue() {
        return value;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setValue(int requestedValue) {
        setValueInternal(requestedValue, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerY = getHeight() / 2f;
        float startX = dp(15);
        float endX = Math.max(startX, getWidth() - dp(15));
        float fraction = value / (float) maximum;
        float thumbX = startX + (endX - startX) * fraction;

        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(8));
        paint.setColor(Color.rgb(34, 57, 82));
        canvas.drawLine(startX, centerY, endX, centerY, paint);
        paint.setColor(Color.rgb(61, 200, 255));
        canvas.drawLine(startX, centerY, thumbX, centerY, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(12, 28, 45));
        canvas.drawCircle(thumbX, centerY, dp(14), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.rgb(85, 208, 255));
        canvas.drawCircle(thumbX, centerY, dp(14), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(203, 240, 255));
        canvas.drawCircle(thumbX, centerY, dp(5), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setParentScrollEnabled(false);
                updateFromPosition(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                updateFromPosition(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                updateFromPosition(event.getX());
                performClick();
                setParentScrollEnabled(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                setParentScrollEnabled(true);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private void setValueInternal(int requestedValue, boolean notify) {
        int normalized = Math.max(0, Math.min(maximum, requestedValue));
        if (normalized == value) {
            return;
        }
        value = normalized;
        setContentDescription(
                "Fisheye edge crop " + value + " percent");
        invalidate();
        if (notify && listener != null) {
            listener.onValueChanged(value);
        }
    }

    private void setParentScrollEnabled(boolean enabled) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!enabled);
        }
    }

    private void updateFromPosition(float x) {
        float startX = dp(15);
        float usableWidth = Math.max(1f, getWidth() - dp(30));
        float fraction =
                Math.max(0f, Math.min(1f, (x - startX) / usableWidth));
        setValueInternal(Math.round(fraction * maximum), true);
    }
}
