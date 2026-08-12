package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;

import java.util.Locale;

/**
 * Rounded determinate/indeterminate progress bar for segment finalization,
 * drawn to the recorder's visual system with a moving sheen. While attached
 * it polls its percent source ten times per second, so stitch progress
 * animates without external wiring; a percent below zero renders the
 * indeterminate waiting sweep.
 */
public final class FinalizingProgressBar extends android.view.View {
    public interface PercentSource {
        int percent();
    }

    private static final long FRAME_INTERVAL_MILLIS = 100L;
    private static final long SWEEP_PERIOD_MILLIS = 1400L;
    private static final int TRACK_COLOR = 0xFF1B2B42;
    private static final int FILL_COLOR = 0xFF3DC8FF;
    private static final int FILL_END_COLOR = 0xFF2F9FD8;
    private static final int SHEEN_COLOR = 0x59FFFFFF;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF fillRect = new RectF();
    private final Path clipPath = new Path();
    private PercentSource percentSource;
    private int percent = -1;

    private final Runnable frameUpdater =
            new Runnable() {
                @Override
                public void run() {
                    if (!isAttachedToWindow()) {
                        return;
                    }
                    if (percentSource != null) {
                        percent = percentSource.percent();
                    }
                    invalidate();
                    postDelayed(this, FRAME_INTERVAL_MILLIS);
                }
            };

    public FinalizingProgressBar(Context context) {
        super(context);
        trackPaint.setColor(TRACK_COLOR);
        fillPaint.setColor(FILL_COLOR);
        sheenPaint.setColor(SHEEN_COLOR);
        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP,
                        11f,
                        context.getResources().getDisplayMetrics()));
    }

    public void setPercentSource(PercentSource source) {
        percentSource = source;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(frameUpdater, FRAME_INTERVAL_MILLIS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frameUpdater);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        float textWidth = textPaint.measureText("100%") + height * 0.6f;
        float barRight = width - textWidth;
        float radius = height / 2f;
        barRect.set(0f, 0f, barRight, height);
        canvas.drawRoundRect(barRect, radius, radius, trackPaint);

        float sweepPhase =
                (SystemClock.uptimeMillis() % SWEEP_PERIOD_MILLIS)
                        / (float) SWEEP_PERIOD_MILLIS;
        if (percent >= 0) {
            float fillRight =
                    Math.max(
                            height,
                            barRight * Math.min(100, percent) / 100f);
            fillRect.set(0f, 0f, fillRight, height);
            fillPaint.setColor(
                    percent >= 99 ? FILL_COLOR : FILL_END_COLOR);
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint);
            float sheenWidth = Math.max(height * 2f, fillRight * 0.25f);
            float sheenX =
                    sweepPhase * (fillRight + sheenWidth) - sheenWidth;
            canvas.save();
            // Clip to the rounded fill shape so the moving sheen never shows
            // square corners past the bar's rounded ends.
            clipPath.reset();
            clipPath.addRoundRect(fillRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            canvas.drawRect(
                    sheenX,
                    0f,
                    sheenX + sheenWidth,
                    height,
                    sheenPaint);
            canvas.restore();
        } else {
            float bandWidth = barRight * 0.3f;
            float bandX =
                    sweepPhase * (barRight + bandWidth) - bandWidth;
            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(barRect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clipPath);
            fillPaint.setColor(FILL_END_COLOR);
            canvas.drawRect(bandX, 0f, bandX + bandWidth, height, fillPaint);
            canvas.restore();
        }

        float textY =
                height / 2f
                        - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(
                percent >= 0
                        ? String.format(Locale.US, "%d%%", percent)
                        : "…",
                width,
                textY,
                textPaint);
    }
}
