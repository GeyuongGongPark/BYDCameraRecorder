package com.ggpark.byddashcam;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloudflare Quick Tunnel을 통해 로컬 PhoneAccessServer를 외부에서 접근 가능하게 만듭니다.
 *
 * 첫 실행 시 GitHub Releases에서 cloudflared ARM64 바이너리를 자동 다운로드합니다.
 * 실행 중에는 stdout/stderr에서 터널 URL을 파싱하여 리스너로 콜백합니다.
 *
 * 지원: Quick Tunnel (--url 플래그, 인증 불필요)
 * 미지원: Named Tunnel (계정 필요), TLS 설정
 */
public final class CloudflaredTunnel {
    private static final String TAG = "BYDCamera";
    private static final String BINARY_NAME = "cloudflared";
    private static final String DOWNLOAD_URL =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64";
    private static final long MAX_BINARY_BYTES = 64L * 1024L * 1024L; // 64 MB guard
    private static final int DOWNLOAD_TIMEOUT_MS = 60_000;
    private static final Pattern TUNNEL_URL_PATTERN =
            Pattern.compile("https://[a-z0-9\\-]+\\.trycloudflare\\.com");

    public interface Listener {
        void onTunnelUrl(String url);
        void onTunnelStopped();
    }

    private final Context context;
    private volatile Listener listener;
    private volatile boolean enabled;
    private volatile int localPort;
    private volatile String localToken;

    private volatile Process process;
    private Thread monitorThread;
    private Thread downloadThread;

    public CloudflaredTunnel(Context context, int localPort, String localToken, boolean enabled) {
        this.context = context.getApplicationContext();
        this.localPort = localPort;
        this.localToken = localToken;
        this.enabled = enabled;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void update(boolean enabled) {
        boolean wasEnabled = this.enabled;
        this.enabled = enabled;
        if (enabled && !wasEnabled) {
            start();
        } else if (!enabled && wasEnabled) {
            stop();
        }
    }

    public synchronized void start() {
        if (!enabled) return;
        if (monitorThread != null && monitorThread.isAlive()) return;

        monitorThread = new Thread(this::runTunnel, "byd-cloudflare");
        monitorThread.setDaemon(true);
        monitorThread.start();
        Log.i(TAG, "CloudflaredTunnel starting");
    }

    public synchronized void stop() {
        enabled = false;
        Process proc = process;
        if (proc != null) {
            proc.destroy();
            process = null;
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        if (downloadThread != null) {
            downloadThread.interrupt();
            downloadThread = null;
        }
        Log.i(TAG, "CloudflaredTunnel stopped");
        Listener l = listener;
        if (l != null) l.onTunnelStopped();
    }

    // ── 터널 실행 루프 ─────────────────────────────────────────────────

    private void runTunnel() {
        try {
            File binary = getBinaryFile();
            if (!binary.exists()) {
                Log.i(TAG, "cloudflared not found, downloading...");
                downloadBinary(binary);
            }
            if (!binary.canExecute() && !binary.setExecutable(true)) {
                Log.e(TAG, "Cannot set cloudflared executable");
                return;
            }
            while (enabled && !Thread.currentThread().isInterrupted()) {
                launchAndMonitor(binary);
                if (enabled) {
                    Log.w(TAG, "cloudflared exited, restarting in 15s");
                    Thread.sleep(15_000);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            Log.e(TAG, "CloudflaredTunnel error", exception);
        }
    }

    private void launchAndMonitor(File binary) throws IOException, InterruptedException {
        String localUrl = "http://127.0.0.1:" + localPort + "/" + localToken + "/";
        ProcessBuilder builder = new ProcessBuilder(
                binary.getAbsolutePath(),
                "tunnel",
                "--url",
                localUrl,
                "--no-autoupdate");
        builder.redirectErrorStream(true); // stderr → stdout
        Process proc = builder.start();
        process = proc;
        Log.i(TAG, "cloudflared launched, awaiting tunnel URL");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "cloudflared: " + line);
                Matcher matcher = TUNNEL_URL_PATTERN.matcher(line);
                if (matcher.find()) {
                    String tunnelUrl = matcher.group();
                    Log.i(TAG, "Tunnel URL: " + tunnelUrl);
                    Listener l = listener;
                    if (l != null) l.onTunnelUrl(tunnelUrl);
                }
            }
        } finally {
            proc.waitFor();
            process = null;
        }
    }

    // ── 바이너리 다운로드 ──────────────────────────────────────────────

    private void downloadBinary(File destination) throws IOException, InterruptedException {
        File tmp = new File(destination.getParent(), BINARY_NAME + ".tmp");
        try {
            URL url = new URL(DOWNLOAD_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("cloudflared download failed: HTTP " + code);
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_BINARY_BYTES) {
                throw new IOException("cloudflared binary too large: " + contentLength);
            }
            try (InputStream input = conn.getInputStream();
                 FileOutputStream output = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0L;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Download interrupted");
                    }
                    output.write(buffer, 0, count);
                    total += count;
                    if (total > MAX_BINARY_BYTES) {
                        throw new IOException("cloudflared binary exceeds size limit");
                    }
                }
                output.flush();
            }
            conn.disconnect();
            if (!tmp.renameTo(destination)) {
                throw new IOException("Cannot move cloudflared binary to destination");
            }
            Log.i(TAG, "cloudflared downloaded: " + destination.getAbsolutePath());
        } catch (IOException | InterruptedException exception) {
            tmp.delete();
            throw exception;
        }
    }

    private File getBinaryFile() {
        return new File(context.getFilesDir(), BINARY_NAME);
    }

    /** 캐시된 바이너리를 삭제합니다 (업데이트 강제 시 사용). */
    public void deleteCachedBinary() {
        File binary = getBinaryFile();
        if (binary.exists()) {
            binary.delete();
            Log.i(TAG, "cloudflared binary deleted");
        }
    }
}
