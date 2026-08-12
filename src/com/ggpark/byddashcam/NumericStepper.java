package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NumericStepper extends LinearLayout {
    public interface Listener {
        void onValueChanged(int value);
    }

    public interface ValueFormatter {
        String format(int value);
    }

    public static final class Specification {
        public final ValueFormatter formatter;
        public final String label;
        public final int maximum;
        public final int minimum;
        public final int step;

        public Specification(
                String label,
                int minimum,
                int maximum,
                int step,
                ValueFormatter formatter) {
            if (label == null || label.trim().isEmpty()) {
                throw new IllegalArgumentException("Stepper label is required");
            }
            if (maximum < minimum) {
                throw new IllegalArgumentException(
                        "Stepper maximum must be at least its minimum");
            }
            if (step <= 0) {
                throw new IllegalArgumentException(
                        "Stepper step must be positive");
            }
            if (formatter == null) {
                throw new IllegalArgumentException(
                        "Stepper formatter is required");
            }
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            this.formatter = formatter;
        }
    }

    private static final long HOLD_START_DELAY_MILLIS = 460L;
    private static final long MAX_REPEAT_DELAY_MILLIS = 260L;
    private static final long MIN_REPEAT_DELAY_MILLIS = 48L;
    private static final double REPEAT_ACCELERATION = 0.86;

    private final ImageButton decrementButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageButton incrementButton;
    private final Specification specification;
    private final TextView valueView;
    private int holdDirection;
    private int repeatCount;
    private Listener listener;
    private int value;

    private final Runnable holdRunnable =
            new Runnable() {
                @Override
                public void run() {
                    if (holdDirection == 0) {
                        return;
                    }
                    if (!adjustValue(holdDirection)) {
                        return;
                    }
                    repeatCount++;
                    double acceleratedDelay =
                            MAX_REPEAT_DELAY_MILLIS
                                    * Math.pow(
                                            REPEAT_ACCELERATION,
                                            repeatCount);
                    long nextDelay = Math.max(
                            MIN_REPEAT_DELAY_MILLIS,
                            Math.round(acceleratedDelay));
                    handler.postDelayed(this, nextDelay);
                }
            };

    public NumericStepper(
            Context context,
            Specification specification) {
        super(context);
        this.specification = specification;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(containerBackground());
        setClipToOutline(true);
        setFocusable(true);

        decrementButton = createButton(
                R.drawable.ic_minus,
                "Decrease " + specification.label,
                -1);
        incrementButton = createButton(
                R.drawable.ic_plus,
                "Increase " + specification.label,
                1);
        valueView = createValueView();

        addView(
                decrementButton,
                new LayoutParams(dp(48), LayoutParams.MATCH_PARENT));
        addView(separator(), new LayoutParams(dp(1), dp(30)));
        addView(
                valueView,
                new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        addView(separator(), new LayoutParams(dp(1), dp(30)));
        addView(
                incrementButton,
                new LayoutParams(dp(48), LayoutParams.MATCH_PARENT));
        setValue(specification.minimum);
    }

    public int getValue() {
        return value;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setValue(int requestedValue) {
        value = clamp(requestedValue);
        updateDisplayedValue();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            stopHold();
        }
        updateButtonStates();
        setAlpha(enabled ? 1f : 0.55f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopHold();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            stopHold();
        }
    }

    private boolean adjustValue(int direction) {
        long requested =
                (long) value + (long) direction * specification.step;
        int adjusted = clamp(requested);
        if (adjusted == value) {
            return false;
        }
        value = adjusted;
        updateDisplayedValue();
        if (listener != null) {
            listener.onValueChanged(value);
        }
        return true;
    }

    private int clamp(long requestedValue) {
        return (int) Math.max(
                specification.minimum,
                Math.min(specification.maximum, requestedValue));
    }

    private ImageButton createButton(
            int iconResource,
            String accessibilityLabel,
            final int direction) {
        final ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconResource);
        button.setContentDescription(accessibilityLabel);
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(buttonBackground());
        button.setClickable(true);
        button.setFocusable(true);
        final boolean[] suppressPointerClick = new boolean[]{false};
        button.setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (suppressPointerClick[0]) {
                            suppressPointerClick[0] = false;
                            return;
                        }
                        adjustValue(direction);
                    }
                });
        button.setOnTouchListener(
                new OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (!view.isEnabled()) {
                            return false;
                        }
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                suppressPointerClick[0] = true;
                                startHold(direction);
                                animatePressed(view, true);
                                setParentScrollEnabled(false);
                                break;
                            case MotionEvent.ACTION_UP:
                                stopHold();
                                animatePressed(view, false);
                                setParentScrollEnabled(true);
                                break;
                            case MotionEvent.ACTION_CANCEL:
                            case MotionEvent.ACTION_OUTSIDE:
                                suppressPointerClick[0] = false;
                                stopHold();
                                animatePressed(view, false);
                                setParentScrollEnabled(true);
                                break;
                            default:
                                break;
                        }
                        return false;
                    }
                });
        return button;
    }

    private TextView createValueView() {
        TextView display = new TextView(getContext());
        display.setGravity(Gravity.CENTER);
        display.setTextColor(Color.rgb(235, 247, 255));
        display.setTextSize(15);
        display.setTypeface(display.getTypeface(), android.graphics.Typeface.BOLD);
        display.setSingleLine(true);
        display.setPadding(dp(8), 0, dp(8), 0);
        return display;
    }

    private View separator() {
        View line = new View(getContext());
        line.setBackgroundColor(Color.rgb(48, 75, 103));
        return line;
    }

    private void startHold(int direction) {
        stopHold();
        holdDirection = direction;
        repeatCount = 0;
        if (adjustValue(direction)) {
            handler.postDelayed(
                    holdRunnable,
                    HOLD_START_DELAY_MILLIS);
        } else {
            stopHold();
        }
    }

    private void stopHold() {
        holdDirection = 0;
        repeatCount = 0;
        handler.removeCallbacks(holdRunnable);
        updateButtonStates();
        setParentScrollEnabled(true);
    }

    private void updateDisplayedValue() {
        String formatted = specification.formatter.format(value);
        valueView.setText(formatted);
        setContentDescription(specification.label + ": " + formatted);
        updateButtonStates();
    }

    private void updateButtonStates() {
        if (decrementButton == null || incrementButton == null) {
            return;
        }
        decrementButton.setEnabled(
                isEnabled()
                        && (value > specification.minimum
                                || holdDirection < 0));
        incrementButton.setEnabled(
                isEnabled()
                        && (value < specification.maximum
                                || holdDirection > 0));
        decrementButton.setAlpha(decrementButton.isEnabled() ? 1f : 0.28f);
        incrementButton.setAlpha(incrementButton.isEnabled() ? 1f : 0.28f);
    }

    private void animatePressed(View view, boolean pressed) {
        view.animate()
                .scaleX(pressed ? 0.90f : 1f)
                .scaleY(pressed ? 0.90f : 1f)
                .setDuration(pressed ? 70L : 130L)
                .start();
    }

    private void setParentScrollEnabled(boolean enabled) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!enabled);
        }
    }

    private StateListDrawable buttonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                rounded(Color.rgb(27, 89, 128), Color.TRANSPARENT, 10));
        states.addState(
                new int[]{},
                rounded(Color.TRANSPARENT, Color.TRANSPARENT, 10));
        return states;
    }

    private GradientDrawable containerBackground() {
        return rounded(
                Color.rgb(13, 27, 44),
                Color.rgb(55, 84, 115),
                12);
    }

    private GradientDrawable rounded(
            int fillColor,
            int borderColor,
            int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (borderColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), borderColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
