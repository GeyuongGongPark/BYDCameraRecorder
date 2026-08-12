package com.ggpark.byddashcam;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ConfirmationDialog {
    public interface OptionAction {
        void run(boolean optionChecked);
    }

    public enum Tone {
        DEFAULT,
        DANGER,
        WARNING
    }

    private ConfirmationDialog() {
    }

    public static void show(
            Context context,
            String title,
            String message,
            String confirmLabel,
            Tone tone,
            final Runnable action) {
        showDialog(
                context,
                title,
                message,
                confirmLabel,
                tone,
                true,
                null,
                null,
                action);
    }

    public static void showWithOption(
            Context context,
            String title,
            String message,
            String confirmLabel,
            Tone tone,
            String optionLabel,
            final OptionAction action) {
        showDialog(
                context,
                title,
                message,
                confirmLabel,
                tone,
                true,
                optionLabel,
                action,
                null);
    }

    public static void showInfo(
            Context context,
            String title,
            String message) {
        showDialog(
                context,
                title,
                message,
                "Close",
                Tone.DEFAULT,
                false,
                null,
                null,
                new Runnable() {
                    @Override
                    public void run() {
                        // Informational dialogs have no state-changing action.
                    }
                });
    }

    private static void showDialog(
            Context context,
            String title,
            String message,
            String confirmLabel,
            Tone tone,
            boolean showCancel,
            String optionLabel,
            OptionAction optionAction,
            final Runnable action) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(
                buildContent(
                        context,
                        dialog,
                        title,
                        message,
                        confirmLabel,
                        tone,
                        showCancel,
                        optionLabel,
                        optionAction,
                        action));
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.78f;
            window.setAttributes(attributes);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(dp(context, 720), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static View buildContent(
            Context context,
            final Dialog dialog,
            String title,
            String message,
            String confirmLabel,
            Tone tone,
            boolean showCancel,
            String optionLabel,
            final OptionAction optionAction,
            final Runnable action) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 28), dp(context, 24), dp(context, 28), dp(context, 24));
        panel.setBackground(panelBackground(context, tone));

        TextView titleView = text(context, title, 23, true);
        panel.addView(titleView);

        TextView messageView = text(context, message, 16, false);
        messageView.setTextColor(Color.rgb(175, 194, 216));
        messageView.setLineSpacing(0f, 1.12f);
        LinearLayout.LayoutParams messageParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(context, 12);
        panel.addView(messageView, messageParams);

        final boolean[] optionChecked = new boolean[]{false};
        LinearLayout optionRow = null;
        if (optionLabel != null && optionAction != null) {
            optionRow = new LinearLayout(context);
            optionRow.setOrientation(LinearLayout.HORIZONTAL);
            optionRow.setGravity(Gravity.CENTER_VERTICAL);
            final IconCheckbox optionCheckbox =
                    new IconCheckbox(context, optionLabel);
            optionCheckbox.setListener(
                    new IconCheckbox.Listener() {
                        @Override
                        public void onCheckedChanged(boolean checked) {
                            optionChecked[0] = checked;
                        }
                    });
            optionRow.addView(
                    optionCheckbox,
                    new LinearLayout.LayoutParams(
                            dp(context, 30),
                            dp(context, 30)));
            TextView optionText = text(context, optionLabel, 13, false);
            LinearLayout.LayoutParams optionTextParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            optionTextParams.leftMargin = dp(context, 8);
            optionRow.addView(optionText, optionTextParams);
            optionRow.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            optionCheckbox.performClick();
                        }
                    });
        }

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(context, 24);
        panel.addView(actions, actionsParams);

        if (optionRow != null) {
            actions.addView(
                    optionRow,
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(context, 42)));
            actions.addView(
                    new View(context),
                    new LinearLayout.LayoutParams(
                            0,
                            1,
                            1f));
        }

        if (showCancel) {
            TextView cancel =
                    actionButton(context, "Cancel", Tone.DEFAULT, false);
            cancel.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            dialog.dismiss();
                        }
                    });
            actions.addView(
                    cancel,
                    new LinearLayout.LayoutParams(
                            dp(context, 150),
                            dp(context, 54)));
        }

        TextView confirm = actionButton(context, confirmLabel, tone, true);
        confirm.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                        public void onClick(View view) {
                            dialog.dismiss();
                            if (optionAction != null) {
                                optionAction.run(optionChecked[0]);
                            } else if (action != null) {
                                action.run();
                            }
                        }
                });
        LinearLayout.LayoutParams confirmParams =
                new LinearLayout.LayoutParams(dp(context, 210), dp(context, 54));
        confirmParams.leftMargin = showCancel ? dp(context, 12) : 0;
        actions.addView(confirm, confirmParams);
        return panel;
    }

    private static TextView actionButton(
            Context context,
            String label,
            Tone tone,
            boolean emphasized) {
        TextView button = text(context, label, 15, true);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.setBackground(buttonBackground(context, tone, emphasized));
        button.setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            view.animate().scaleX(0.96f).scaleY(0.96f)
                                    .setDuration(70L).start();
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                                || event.getActionMasked()
                                        == MotionEvent.ACTION_CANCEL) {
                            view.animate().scaleX(1f).scaleY(1f)
                                    .setDuration(120L).start();
                        }
                        return false;
                    }
                });
        return button;
    }

    private static GradientDrawable panelBackground(Context context, Tone tone) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(12, 25, 42));
        int border = tone == Tone.DANGER
                ? Color.rgb(181, 51, 78)
                : tone == Tone.WARNING
                        ? Color.rgb(182, 111, 39)
                        : Color.rgb(61, 200, 255);
        background.setStroke(dp(context, 1), border);
        background.setCornerRadius(dp(context, 24));
        return background;
    }

    private static GradientDrawable buttonBackground(
            Context context,
            Tone tone,
            boolean emphasized) {
        GradientDrawable background = new GradientDrawable();
        if (!emphasized) {
            background.setColor(Color.rgb(19, 35, 58));
            background.setStroke(dp(context, 1), Color.rgb(57, 84, 117));
        } else if (tone == Tone.DANGER) {
            background.setColor(Color.rgb(151, 31, 58));
            background.setStroke(dp(context, 1), Color.rgb(255, 91, 116));
        } else if (tone == Tone.WARNING) {
            background.setColor(Color.rgb(93, 53, 18));
            background.setStroke(dp(context, 1), Color.rgb(255, 174, 82));
        } else {
            background.setColor(Color.rgb(20, 67, 96));
            background.setStroke(dp(context, 1), Color.rgb(61, 200, 255));
        }
        background.setCornerRadius(dp(context, 16));
        return background;
    }

    private static TextView text(
            Context context,
            String value,
            int sizeSp,
            boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(243, 247, 252));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(
                value * context.getResources().getDisplayMetrics().density);
    }
}
