package com.ggpark.byddashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Scanner;

/**
 * GitHub Releases API를 폴링해서 새 버전이 있으면 콜백합니다.
 * 하루 1회만 체크 (SharedPreferences로 날짜 기억).
 */
public final class UpdateChecker {
    public interface Callback {
        void onUpdateAvailable(String tagName, String htmlUrl);
    }

    private static final String TAG = "BYDCamera";
    private static final String PREFS = "update_checker";
    private static final String KEY_LAST_CHECK = "last_check_date";
    private static final String API_URL =
            "https://api.github.com/repos/GeyuongGongPark/BYDCameraRecorder/releases/latest";

    private UpdateChecker() {}

    /**
     * 오늘 아직 체크하지 않은 경우에만 GitHub Releases를 조회합니다.
     * 새 버전이 있으면 메인 스레드에서 callback을 호출합니다.
     */
    public static void checkOnce(Context context, Callback callback) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        if (today.equals(prefs.getString(KEY_LAST_CHECK, ""))) return;
        prefs.edit().putString(KEY_LAST_CHECK, today).apply();

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.i(TAG, "UpdateChecker: HTTP " + code);
                    conn.disconnect();
                    return;
                }
                InputStream is = conn.getInputStream();
                String body = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
                is.close();
                conn.disconnect();

                JSONObject json = new JSONObject(body);
                String tagName = json.optString("tag_name", "");
                String htmlUrl = json.optString("html_url", "");
                if (tagName.isEmpty()) return;

                // 현재 versionName과 비교 (v 접두사 무시)
                PackageInfo info = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                String current = info.versionName;
                String remote = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                if (current.equals(remote)) {
                    Log.i(TAG, "UpdateChecker: up to date (" + current + ")");
                    return;
                }

                Log.i(TAG, "UpdateChecker: new version available — " + tagName
                        + " (current=" + current + ")");
                new Handler(Looper.getMainLooper()).post(() -> callback.onUpdateAvailable(tagName, htmlUrl));
            } catch (Exception e) {
                Log.i(TAG, "UpdateChecker: check failed (" + e.getClass().getSimpleName() + ")");
            }
        }, "UpdateCheck").start();
    }
}
