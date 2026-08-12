package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

public final class IconStateToggle extends FrameLayout {
    public enum Tone {
        ACCESS,
        RECORDING,
        STANDARD
    }

    public interface Listener {
        void onToggleRequested(boolean checked);
    }

    private final int checkedDrawable;
    private final String checkedLabel;
    private final Tone tone;
    private final ImageView thumb;
    private final int uncheckedDrawable;
    private final String uncheckedLabel;
    private boolean checked;
    private Listener listener;

    public IconStateToggle(
            Context context,
            int uncheckedDrawable,
            int checkedDrawable,
            String uncheckedLabel,
            String checkedLabel) {
        this(
                context,
                uncheckedDrawable,
                checkedDrawable,
                uncheckedLabel,
                checkedLabel,
                Tone.RECORDING);
    }

    public IconStateToggle(
            Context context,
            int uncheckedDrawable,
            int checkedDrawable,
            String uncheckedLabel,
            String checkedLabel,
            boolean checkedRecordTone) {
        this(
                context,
                uncheckedDrawable,
                checkedDrawable,
                uncheckedLabel,
                checkedLabel,
                checkedRecordTone ? Tone.RECORDING : Tone.ACCESS);
    }

    public IconStateToggle(
            Context context,
            int uncheckedDrawable,
            int checkedDrawable,
            String uncheckedLabel,
            String checkedLabel,
            Tone tone) {
        super(context);
        this.uncheckedDrawable = uncheckedDrawable;
        this.checkedDrawable = checkedDrawable;
        this.uncheckedLabel = uncheckedLabel;
        this.checkedLabel = checkedLabel;
        this.tone = tone == null ? Tone.STANDARD : tone;
        setClickable(true);
        setFocusable(true);
        setPadding(dp(5), dp(5), dp(5), dp(5));

        thumb = createThumb();
        addView(
                thumb,
                new FrameLayout.LayoutParams(
                        dp(48),
                        dp(48),
                        Gravity.START | Gravity.CENTER_VERTICAL));

        setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (listener != null) {
                            listener.onToggleRequested(!checked);
                        }
                    }
                });
        setOnTouchListener(
                new OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (!isEnabled()) {
                            return false;
                        }
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            animate().scaleX(0.95f).scaleY(0.95f)
                                    .setDuration(70L).start();
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            animate().scaleX(1f).scaleY(1f)
                                    .setDuration(130L).start();
                        }
                        return false;
                    }
                });
        setChecked(false);
    }

    public void setChecked(boolean checked) {
        boolean changed = this.checked != checked;
        this.checked = checked;
        setContentDescription(checked ? checkedLabel : uncheckedLabel);
        setBackground(track(checked));
        thumb.setImageResource(checked ? checkedDrawable : uncheckedDrawable);
        thumb.setBackground(thumbBackground(checked));
        updateThumbPosition(changed && getWidth() > 0);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateThumbPosition(false);
    }

    private ImageView createThumb() {
        ImageView icon = new ImageView(getContext());
        icon.setPadding(dp(12), dp(12), dp(12), dp(12));
        return icon;
    }

    private GradientDrawable thumbBackground(boolean active) {
        GradientDrawable background = new GradientDrawable();
        if (active && tone == Tone.RECORDING) {
            background.setColor(Color.rgb(151, 31, 58));
            background.setStroke(dp(1), Color.rgb(255, 91, 116));
        } else if (active && tone == Tone.ACCESS) {
            background.setColor(Color.rgb(16, 99, 76));
            background.setStroke(dp(1), Color.rgb(69, 214, 154));
        } else if (active) {
            background.setColor(Color.rgb(35, 82, 151));
            background.setStroke(dp(1), Color.rgb(120, 170, 255));
        } else if (tone == Tone.ACCESS) {
            background.setColor(Color.rgb(111, 35, 51));
            background.setStroke(dp(1), Color.rgb(255, 91, 116));
        } else {
            background.setColor(Color.rgb(20, 67, 96));
            background.setStroke(dp(1), Color.rgb(61, 200, 255));
        }
        background.setCornerRadius(dp(24));
        return background;
    }

    private GradientDrawable track(boolean checked) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(
                checked && tone == Tone.RECORDING
                        ? Color.rgb(53, 20, 34)
                        : checked && tone == Tone.ACCESS
                                ? Color.rgb(10, 47, 39)
                                : checked
                                        ? Color.rgb(12, 38, 74)
                                : tone == Tone.ACCESS
                                        ? Color.rgb(54, 20, 31)
                        : Color.rgb(12, 25, 42));
        background.setStroke(
                dp(1),
                checked && tone == Tone.RECORDING
                        ? Color.rgb(157, 48, 70)
                        : checked && tone == Tone.ACCESS
                                ? Color.rgb(45, 153, 112)
                                : checked
                                        ? Color.rgb(67, 117, 190)
                                : tone == Tone.ACCESS
                                        ? Color.rgb(137, 50, 69)
                                        : Color.rgb(42, 66, 92));
        background.setCornerRadius(dp(30));
        return background;
    }

    private void updateThumbPosition(boolean animate) {
        int travel = Math.max(0, getWidth() - getPaddingLeft()
                - getPaddingRight() - dp(48));
        float destination = checked ? travel : 0f;
        if (animate) {
            thumb.animate()
                    .translationX(destination)
                    .setDuration(180L)
                    .start();
        } else {
            thumb.setTranslationX(destination);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
