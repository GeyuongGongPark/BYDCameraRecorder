package com.ggpark.byddashcam;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public final class PhoneAccessActivity extends Activity {
    private LinearLayout networkRow;
    private TextView networkView;
    private IconButton copyButton;
    private CameraRecorderService recorderService;
    private boolean serviceBound;
    private PinDisplay pinView;
    private TextView statusView;
    private TextView urlView;
    private WebView qrView;

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    CameraRecorderService.LocalBinder localBinder =
                            (CameraRecorderService.LocalBinder) binder;
                    recorderService = localBinder.getService();
                    serviceBound = true;
                    populateAccessDetails();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceBound = false;
                    recorderService = null;
                    statusView.setText(getString(R.string.msg_service_disconnected));
                    copyButton.setEnabled(false);
                }
            };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildContent());
        refreshWifiName();
        Intent serviceIntent = new Intent(this, CameraRecorderService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (qrView != null) {
            qrView.destroy();
        }
        super.onDestroy();
    }

    private View buildContent() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(5, 11, 20));

        LinearLayout panel = vertical();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundResource(R.drawable.modal_background);
        panel.setPadding(dp(28), dp(22), dp(28), dp(22));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.phone_access_activity_title), 25, true);
        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        IconButton close = new IconButton(
                this,
                R.drawable.ic_close,
                "Close phone app access",
                IconButton.Tone.DEFAULT);
        close.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        finish();
                    }
                });
        header.addView(close, new LinearLayout.LayoutParams(dp(58), dp(58)));
        panel.addView(header);

        TextView requirement = text(
                getString(R.string.phone_access_requirement),
                17,
                true);
        requirement.setPadding(0, dp(10), 0, dp(12));
        panel.addView(requirement);

        LinearLayout accessBody = horizontal();
        accessBody.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout qrColumn = vertical();
        qrColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        qrView = PhoneAccessQrView.create(this);
        qrColumn.addView(qrView, new LinearLayout.LayoutParams(dp(250), dp(250)));
        TextView scanLabel = text(getString(R.string.phone_access_scan), 13, true);
        scanLabel.setTextColor(Color.rgb(159, 179, 204));
        scanLabel.setGravity(Gravity.CENTER);
        scanLabel.setPadding(0, dp(7), 0, 0);
        qrColumn.addView(scanLabel);
        accessBody.addView(
                qrColumn,
                new LinearLayout.LayoutParams(
                        dp(280),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout detailsColumn = vertical();
        networkRow = horizontal();
        networkRow.setGravity(Gravity.CENTER_VERTICAL);
        networkRow.setBackgroundResource(R.drawable.card_background);
        networkRow.setPadding(dp(14), dp(8), dp(14), dp(8));
        ImageView wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.ic_wifi);
        networkRow.addView(wifi, new LinearLayout.LayoutParams(dp(34), dp(34)));
        networkView = text("", 15, true);
        LinearLayout.LayoutParams networkParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        networkParams.leftMargin = dp(10);
        networkRow.addView(networkView, networkParams);
        detailsColumn.addView(
                networkRow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = text(getString(R.string.phone_access_starting), 14, false);
        statusView.setTextColor(Color.rgb(159, 179, 204));
        statusView.setPadding(dp(2), dp(9), 0, dp(7));
        detailsColumn.addView(statusView);

        urlView = text("", 18, true);
        urlView.setTextColor(Color.rgb(61, 200, 255));
        urlView.setTextIsSelectable(true);
        urlView.setSingleLine(true);
        urlView.setGravity(Gravity.CENTER_VERTICAL);
        urlView.setBackgroundResource(R.drawable.card_background);
        urlView.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout urlRow = horizontal();
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.addView(
                urlView,
                new LinearLayout.LayoutParams(
                        0,
                        dp(52),
                        1f));
        copyButton = new IconButton(
                this,
                R.drawable.ic_share,
                "Copy phone app link",
                IconButton.Tone.DEFAULT);
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        copyLink();
                    }
                });
        LinearLayout.LayoutParams copyParams =
                new LinearLayout.LayoutParams(dp(52), dp(52));
        copyParams.leftMargin = dp(9);
        urlRow.addView(copyButton, copyParams);
        detailsColumn.addView(urlRow);

        LinearLayout pinRow = horizontal();
        pinRow.setGravity(Gravity.CENTER_VERTICAL);
        pinRow.setBackgroundResource(R.drawable.card_background);
        pinRow.setPadding(dp(14), 0, dp(6), 0);
        TextView pinLabel = text(getString(R.string.phone_access_pin_label), 14, false);
        pinLabel.setTextColor(Color.rgb(159, 179, 204));
        pinRow.addView(
                pinLabel,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        pinView = new PinDisplay(this, false);
        pinRow.addView(
                pinView,
                new LinearLayout.LayoutParams(dp(170), dp(48)));
        IconButton regenerate = new IconButton(
                this,
                R.drawable.ic_refresh,
                "Regenerate phone PIN",
                IconButton.Tone.DEFAULT);
        regenerate.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        confirmPinRegeneration();
                    }
                });
        LinearLayout.LayoutParams regenerateParams =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        regenerateParams.leftMargin = dp(9);
        pinRow.addView(regenerate, regenerateParams);
        LinearLayout.LayoutParams pinRowParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52));
        pinRowParams.topMargin = dp(10);
        detailsColumn.addView(pinRow, pinRowParams);

        LinearLayout.LayoutParams detailsParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        detailsParams.leftMargin = dp(22);
        accessBody.addView(detailsColumn, detailsParams);
        panel.addView(
                accessBody,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(dp(28), dp(20), dp(28), dp(20));
        screen.addView(panel, panelParams);
        return screen;
    }

    private void populateAccessDetails() {
        statusView.setText(recorderService.getPhoneAccessStatus());
        pinView.setPin(recorderService.getPhoneAccessPin());
        try {
            String url = recorderService.getPhoneAccessUrl();
            urlView.setText(url);
            copyButton.setEnabled(true);
            qrView.setVisibility(View.VISIBLE);
            PhoneAccessQrView.load(qrView, url);
        } catch (IOException exception) {
            urlView.setText(exception.getMessage());
            copyButton.setEnabled(false);
            qrView.setVisibility(View.GONE);
        }
    }

    private void refreshWifiName() {
        if (networkRow == null || networkView == null) {
            return;
        }
        String wifiName = PhoneAccessNetwork.getWifiName(this);
        networkView.setText(wifiName);
        networkRow.setVisibility(
                wifiName.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmPinRegeneration() {
        ConfirmationDialog.show(
                this,
                getString(R.string.confirm_pin_title),
                getString(R.string.confirm_pin_message),
                getString(R.string.action_regenerate),
                ConfirmationDialog.Tone.WARNING,
                new Runnable() {
                    @Override
                    public void run() {
                        if (recorderService == null) {
                            showMessage(getString(R.string.msg_service_unavailable));
                            return;
                        }
                        pinView.setPin(
                                recorderService.regeneratePhoneAccessPin());
                        showMessage(getString(R.string.msg_pin_regenerated));
                    }
                });
    }

    private void copyLink() {
        if (urlView.getText().length() == 0) {
            return;
        }
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            showMessage(getString(R.string.clipboard_unavailable));
            return;
        }
        clipboard.setPrimaryClip(
                ClipData.newPlainText("BYD Camera phone app", urlView.getText()));
        showMessage(getString(R.string.phone_access_link_copied));
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
