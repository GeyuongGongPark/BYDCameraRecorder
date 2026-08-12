package com.ggpark.byddashcam;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telegram Bot API를 통해 알림 메시지를 발송합니다.
 * 모든 전송은 백그라운드 스레드에서 비동기로 수행됩니다.
 */
public final class TelegramNotifier {
    private static final String TAG = "BYDCamera";
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile String botToken;
    private volatile String chatId;
    private volatile boolean enabled;

    public TelegramNotifier(String botToken, String chatId, boolean enabled) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.enabled = enabled;
    }

    public void update(String botToken, String chatId, boolean enabled) {
        this.botToken = botToken;
        this.chatId = chatId;
        this.enabled = enabled;
    }

    /** 비동기로 메시지를 발송합니다. 설정이 없거나 비활성화 상태면 무시합니다. */
    public void send(final String message) {
        if (!enabled
                || botToken == null || botToken.isEmpty()
                || chatId == null || chatId.isEmpty()) {
            return;
        }
        final String token = botToken;
        final String id = chatId;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                sendSync(token, id, message);
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void sendSync(String token, String id, String message) {
        try {
            URL url = new URL(API_BASE + token + "/sendMessage");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            String body = "{\"chat_id\":" + PhoneJson.quote(id)
                    + ",\"text\":" + PhoneJson.quote(message)
                    + ",\"parse_mode\":\"HTML\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Telegram send failed: HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception exception) {
            Log.w(TAG, "Telegram send error", exception);
        }
    }
}
