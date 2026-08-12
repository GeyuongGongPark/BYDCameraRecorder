package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

public final class PhoneAccessQrView {
    private PhoneAccessQrView() {
    }

    public static WebView create(Context context) {
        WebView qrView = new WebView(context);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        qrView.setHorizontalScrollBarEnabled(false);
        qrView.setVerticalScrollBarEnabled(false);
        WebSettings settings = qrView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        return qrView;
    }

    public static void load(WebView qrView, String url) {
        qrView.loadUrl(
                url
                        + "qr.html?value="
                        + Uri.encode(url));
    }
}
