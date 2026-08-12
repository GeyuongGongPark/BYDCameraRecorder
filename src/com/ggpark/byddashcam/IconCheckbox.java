package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

public final class IconCheckbox extends FrameLayout {
    public interface Listener {
        void onCheckedChanged(boolean checked);
    }

    private final ImageView check;
    private final String label;
    private boolean checked;
    private Listener listener;

    public IconCheckbox(Context context, String label) {
        super(context);
        this.label = label;
        setClickable(true);
        setFocusable(true);
        setForegroundGravity(Gravity.CENTER);
        setPadding(dp(5), dp(5), dp(5), dp(5));

        check = new ImageView(context);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(Color.rgb(243, 247, 252));
        addView(
                check,
                new FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                        Gravity.CENTER));

        setOnClickListener(
                new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        setChecked(!checked);
                        if (listener != null) {
                            listener.onCheckedChanged(checked);
                        }
                    }
                });
        setOnTouchListener(
                new OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            animate().scaleX(0.9f).scaleY(0.9f)
                                    .setDuration(70L).start();
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                                || event.getActionMasked()
                                        == MotionEvent.ACTION_CANCEL) {
                            animate().scaleX(1f).scaleY(1f)
                                    .setDuration(120L).start();
                        }
                        return false;
                    }
                });
        setChecked(false);
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
        check.setVisibility(checked ? VISIBLE : INVISIBLE);
        setBackground(boxBackground(checked));
        setContentDescription(label + (checked ? ", checked" : ", not checked"));
        setSelected(checked);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private GradientDrawable boxBackground(boolean checked) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(
                checked
                        ? Color.rgb(20, 67, 96)
                        : Color.rgb(9, 21, 37));
        background.setStroke(
                dp(1),
                checked
                        ? Color.rgb(61, 200, 255)
                        : Color.rgb(70, 99, 130));
        background.setCornerRadius(dp(7));
        return background;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
