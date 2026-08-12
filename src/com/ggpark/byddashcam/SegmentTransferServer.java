package com.ggpark.byddashcam;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @deprecated Replaced by the persistent service-owned {@link PhoneAccessServer}.
 */
@Deprecated
public final class SegmentTransferServer implements Closeable {
    private static final int ACCEPT_TIMEOUT_MILLIS = 1000;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int CLIENT_TIMEOUT_MILLIS = 5000;
    private static final long SESSION_DURATION_MILLIS = 10L * 60L * 1000L;
    private static final String TAG = "BYDCamera";

    private final long expiresAtMillis;
    private final Map<String, File> filesByName;
    private final ServerSocket serverSocket;
    private final Thread serverThread;
    private final String token;
    private volatile boolean running = true;

    public SegmentTransferServer(File segmentDirectory) throws IOException {
        if (segmentDirectory == null || !segmentDirectory.isDirectory()) {
            throw new IOException("The transfer segment is unavailable");
        }
        if (new File(segmentDirectory, "recording.marker").exists()) {
            throw new IOException("An active segment cannot be transferred");
        }
        filesByName = collectFinalizedVideos(segmentDirectory);
        if (filesByName.isEmpty()) {
            throw new IOException("The segment has no finalized videos");
        }
        token = createToken();
        expiresAtMillis = System.currentTimeMillis() + SESSION_DURATION_MILLIS;
        serverSocket = new ServerSocket(0);
        serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
        serverThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        serve();
                    }
                },
                "byd-segment-transfer");
        serverThread.start();
        Log.i(TAG, "Local segment transfer started on port " + serverSocket.getLocalPort());
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException exception) {
            Log.w(TAG, "Local transfer socket close failed", exception);
        }
        serverThread.interrupt();
        Log.i(TAG, "Local segment transfer stopped");
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public String getUrl() throws IOException {
        return String.format(
                Locale.US,
                "http://%s:%d/%s/",
                findLocalIpv4Address(),
                serverSocket.getLocalPort(),
                token);
    }

    private Map<String, File> collectFinalizedVideos(File segmentDirectory)
            throws IOException {
        String segmentPath = segmentDirectory.getCanonicalPath();
        Map<String, File> videos = new LinkedHashMap<>();
        for (File video : RecordingFiles.listVideos(segmentDirectory)) {
            String name = video.getName();
            if (video.isFile()
                    && video.length() > 0L
                    && video.getCanonicalFile().getParentFile() != null
                    && video.getCanonicalFile()
                            .getParentFile()
                            .getCanonicalPath()
                            .equals(segmentPath)) {
                videos.put(name, video);
            }
        }
        return videos;
    }

    private String createToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder tokenBuilder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            tokenBuilder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return tokenBuilder.toString();
    }

    private String findLocalIpv4Address() throws IOException {
        Enumeration<NetworkInterface> interfaces =
                NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            throw new IOException("No local network is available");
        }
        List<InetAddress> fallbackAddresses = new ArrayList<>();
        for (NetworkInterface networkInterface : Collections.list(interfaces)) {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            for (InetAddress address :
                    Collections.list(networkInterface.getInetAddresses())) {
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                    continue;
                }
                if (address.isSiteLocalAddress()) {
                    return address.getHostAddress();
                }
                fallbackAddresses.add(address);
            }
        }
        if (!fallbackAddresses.isEmpty()) {
            return fallbackAddresses.get(0).getHostAddress();
        }
        throw new IOException("Connect this device to the phone's Wi-Fi hotspot");
    }

    private void serve() {
        while (running && System.currentTimeMillis() < expiresAtMillis) {
            try {
                Socket client = serverSocket.accept();
                handleClient(client);
            } catch (SocketTimeoutException ignored) {
                // Periodically recheck the session expiry.
            } catch (IOException exception) {
                if (running) {
                    Log.e(TAG, "Local transfer request failed", exception);
                }
            }
        }
        running = false;
        try {
            serverSocket.close();
        } catch (IOException exception) {
            Log.w(TAG, "Expired transfer socket close failed", exception);
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(CLIENT_TIMEOUT_MILLIS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            String header;
            do {
                header = reader.readLine();
            } while (header != null && !header.isEmpty());

            if (requestLine == null || !requestLine.startsWith("GET ")) {
                sendText(socket.getOutputStream(), 405, "Method not allowed");
                return;
            }
            int pathEnd = requestLine.indexOf(' ', 4);
            if (pathEnd < 0) {
                sendText(socket.getOutputStream(), 400, "Invalid request");
                return;
            }
            String path = requestLine.substring(4, pathEnd);
            int queryStart = path.indexOf('?');
            if (queryStart >= 0) {
                path = path.substring(0, queryStart);
            }
            String prefix = "/" + token + "/";
            if (!path.startsWith(prefix)) {
                sendText(socket.getOutputStream(), 404, "Transfer link not found");
                return;
            }
            String encodedName = path.substring(prefix.length());
            if (encodedName.isEmpty()) {
                sendIndex(socket.getOutputStream());
                return;
            }
            String name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8.name());
            File requested = filesByName.get(name);
            if (requested == null) {
                sendText(socket.getOutputStream(), 404, "Recording not found");
                return;
            }
            sendFile(socket.getOutputStream(), requested);
        } catch (IOException exception) {
            Log.w(TAG, "Local transfer client disconnected", exception);
        }
    }

    private void sendFile(OutputStream output, File file) throws IOException {
        writeHeaders(
                output,
                200,
                "video/mp4",
                file.length(),
                "Content-Disposition: attachment; filename=\""
                        + file.getName()
                        + "\"\r\n");
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        }
        output.flush();
        Log.i(TAG, "Transferred finalized recording " + file.getName());
    }

    private void sendIndex(OutputStream output) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\">"
                + "<title>BYD Camera recordings</title>"
                + "<style>body{font:16px sans-serif;background:#08111f;color:#f3f7fc;"
                + "max-width:720px;margin:32px auto;padding:0 18px}"
                + "a{display:block;color:#3dc8ff;background:#101c2d;border:1px solid "
                + "#324967;border-radius:12px;padding:16px;margin:12px 0;text-decoration:none}"
                + "small{color:#9fb3cc}</style>"
                + "<h1>Finalized recordings</h1>"
                + "<small>This private transfer link expires automatically.</small>");
        for (Map.Entry<String, File> entry : filesByName.entrySet()) {
            html.append("<a download href=\"")
                    .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name()))
                    .append("\">")
                    .append(entry.getKey())
                    .append(" — ")
                    .append(StorageRepository.formatBytes(entry.getValue().length()))
                    .append("</a>");
        }
        byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, 200, "text/html; charset=utf-8", body.length, "");
        output.write(body);
        output.flush();
    }

    private void sendText(OutputStream output, int status, String message)
            throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "text/plain; charset=utf-8", body.length, "");
        output.write(body);
        output.flush();
    }

    private void writeHeaders(
            OutputStream output,
            int status,
            String contentType,
            long contentLength,
            String extraHeaders) throws IOException {
        String reason = status == 200
                ? "OK"
                : status == 400
                        ? "Bad Request"
                        : status == 405 ? "Method Not Allowed" : "Not Found";
        String headers = String.format(
                Locale.US,
                "HTTP/1.1 %d %s\r\n"
                        + "Content-Type: %s\r\n"
                        + "Content-Length: %d\r\n"
                        + "Cache-Control: no-store\r\n"
                        + "X-Content-Type-Options: nosniff\r\n"
                        + "%s"
                        + "Connection: close\r\n\r\n",
                status,
                reason,
                contentType,
                contentLength,
                extraHeaders);
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
    }
}
