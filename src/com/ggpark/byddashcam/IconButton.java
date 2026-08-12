package com.ggpark.byddashcam;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

public final class IconButton extends ImageButton {
    public enum Tone {
        DEFAULT,
        PRIMARY,
        RECORD,
        SUCCESS,
        LOCKED
    }

    private static final long PRESS_DURATION_MILLIS = 70L;
    private static final long RELEASE_DURATION_MILLIS = 130L;
    private static final long PRESS_COLOR_DURATION_MILLIS = 90L;
    private static final long RELEASE_COLOR_DURATION_MILLIS = 160L;
    private static final float PRESSED_SCALE = 0.92f;
    private static final int PRIMARY_NORMAL_COLOR = Color.rgb(22, 89, 126);
    private static final int PRIMARY_PRESSED_COLOR = Color.rgb(28, 124, 174);
    private static final int PRIMARY_BORDER_COLOR = Color.rgb(62, 197, 255);

    private GradientDrawable animatedBackground;
    private ValueAnimator backgroundAnimator;
    private int currentBackgroundColor;

    public IconButton(
            Context context,
            int iconResource,
            String accessibilityLabel,
            Tone tone) {
        super(context);
        setImageResource(iconResource);
        if (tone == Tone.LOCKED) {
            setColorFilter(Color.rgb(255, 173, 69));
        }
        setContentDescription(accessibilityLabel);
        if (tone == Tone.PRIMARY) {
            currentBackgroundColor = PRIMARY_NORMAL_COLOR;
            animatedBackground =
                    rounded(PRIMARY_NORMAL_COLOR, PRIMARY_BORDER_COLOR);
            setBackground(animatedBackground);
        } else {
            setBackground(createBackground(tone));
        }
        setScaleType(ScaleType.CENTER);
        setPadding(dp(13), dp(13), dp(13), dp(13));
        setFocusable(true);
        setClickable(true);
        setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (!isEnabled()) {
                            return false;
                        }
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                animateBackground(
                                        PRIMARY_PRESSED_COLOR,
                                        PRESS_COLOR_DURATION_MILLIS);
                                animate()
                                        .scaleX(PRESSED_SCALE)
                                        .scaleY(PRESSED_SCALE)
                                        .setDuration(PRESS_DURATION_MILLIS)
                                        .start();
                                break;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                animateBackground(
                                        PRIMARY_NORMAL_COLOR,
                                        RELEASE_COLOR_DURATION_MILLIS);
                                animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(RELEASE_DURATION_MILLIS)
                                        .start();
                                break;
                            default:
                                break;
                        }
                        return false;
                    }
                });
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.30f);
        if (!enabled) {
            animate().cancel();
            setScaleX(1f);
            setScaleY(1f);
        }
        if (animatedBackground != null) {
            if (backgroundAnimator != null) {
                backgroundAnimator.cancel();
            }
            animatedBackground.setStroke(
                    dp(1),
                    enabled
                            ? PRIMARY_BORDER_COLOR
                            : Color.rgb(39, 56, 78));
            setAnimatedBackgroundColor(
                    enabled
                            ? PRIMARY_NORMAL_COLOR
                            : Color.rgb(17, 27, 43));
        }
    }

    public void setIconResource(int iconResource) {
        setImageResource(iconResource);
    }

    private void animateBackground(int targetColor, long durationMillis) {
        if (animatedBackground == null
                || !isEnabled()
                || currentBackgroundColor == targetColor) {
            return;
        }
        if (backgroundAnimator != null) {
            backgroundAnimator.cancel();
        }
        backgroundAnimator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                currentBackgroundColor,
                targetColor);
        backgroundAnimator.setDuration(durationMillis);
        backgroundAnimator.addUpdateListener(
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        setAnimatedBackgroundColor((Integer) animation.getAnimatedValue());
                    }
                });
        backgroundAnimator.start();
    }

    private void setAnimatedBackgroundColor(int color) {
        currentBackgroundColor = color;
        animatedBackground.setColor(color);
    }

    private StateListDrawable createBackground(Tone tone) {
        int normalColor;
        int pressedColor;
        int borderColor;
        switch (tone) {
            case PRIMARY:
                normalColor = PRIMARY_NORMAL_COLOR;
                pressedColor = PRIMARY_PRESSED_COLOR;
                borderColor = PRIMARY_BORDER_COLOR;
                break;
            case RECORD:
                normalColor = Color.rgb(111, 28, 48);
                pressedColor = Color.rgb(153, 37, 62);
                borderColor = Color.rgb(255, 91, 116);
                break;
            case SUCCESS:
                normalColor = Color.rgb(18, 85, 72);
                pressedColor = Color.rgb(24, 116, 96);
                borderColor = Color.rgb(69, 214, 154);
                break;
            case LOCKED:
                normalColor = Color.rgb(105, 58, 18);
                pressedColor = Color.rgb(145, 82, 25);
                borderColor = Color.rgb(255, 173, 69);
                break;
            case DEFAULT:
            default:
                normalColor = Color.rgb(28, 44, 66);
                pressedColor = Color.rgb(40, 64, 92);
                borderColor = Color.rgb(63, 88, 117);
                break;
        }
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                rounded(pressedColor, borderColor));
        states.addState(
                new int[]{-android.R.attr.state_enabled},
                rounded(Color.rgb(17, 27, 43), Color.rgb(39, 56, 78)));
        states.addState(new int[]{}, rounded(normalColor, borderColor));
        return states;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int fillColor, int borderColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), borderColor);
        return drawable;
    }
}
