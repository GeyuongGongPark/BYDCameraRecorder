package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class PinDisplay extends LinearLayout {
    private final boolean includePrefix;
    private final TextView valueView;
    private final IconButton visibilityButton;
    private String pin = "";
    private String unavailableText = "PIN pending";
    private boolean revealed;

    public PinDisplay(Context context, boolean includePrefix) {
        super(context);
        this.includePrefix = includePrefix;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        valueView = new TextView(context);
        valueView.setTextColor(Color.rgb(243, 247, 252));
        valueView.setTextSize(18);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueView.setGravity(Gravity.CENTER);
        valueView.setSingleLine(true);
        valueView.setLetterSpacing(0.13f);
        addView(
                valueView,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f));

        visibilityButton = new IconButton(
                context,
                R.drawable.ic_eye,
                "Show phone PIN",
                IconButton.Tone.DEFAULT);
        visibilityButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        revealed = !revealed;
                        updateDisplay();
                    }
                });
        addView(
                visibilityButton,
                new LinearLayout.LayoutParams(dp(44), dp(44)));
        updateDisplay();
    }

    public void setPin(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.equals(pin)) {
            revealed = false;
        }
        pin = normalized;
        updateDisplay();
    }

    public void setUnavailableText(String value) {
        unavailableText = value == null ? "PIN pending" : value;
        pin = "";
        revealed = false;
        updateDisplay();
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private void updateDisplay() {
        boolean available = !pin.isEmpty();
        String displayedValue =
                available
                        ? revealed ? pin : "••••••"
                        : unavailableText;
        valueView.setText(
                includePrefix
                        ? "PIN  " + displayedValue
                        : displayedValue);
        visibilityButton.setEnabled(available);
        visibilityButton.setIconResource(
                revealed ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        visibilityButton.setContentDescription(
                revealed ? "Hide phone PIN" : "Show phone PIN");
        valueView.setContentDescription(
                available
                        ? revealed
                                ? "Phone PIN is visible"
                                : "Phone PIN is hidden"
                        : unavailableText);
    }
}
