package com.ggpark.byddashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneAccessServer implements Closeable {
    private static final int ACCEPT_TIMEOUT_MILLIS = 1000;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int CLIENT_TIMEOUT_MILLIS = 10000;
    private static final int CAMERA_STREAM_SEND_BUFFER_BYTES = 128 * 1024;
    private static final long FINALIZING_PUSH_INTERVAL_MILLIS = 500L;
    private static final long FINALIZING_HEARTBEAT_MILLIS = 5000L;
    private static final long PIN_RETRY_MILLIS = 5000L;
    private static final int PORT = 8765;
    private static final String SESSION_KEY = "authorized_sessions";
    private static final String SESSION_COOKIE = "byd_session";
    private static final long SESSION_MAX_AGE_SECONDS = 10L * 365L * 24L * 60L * 60L;
    private static final String SESSION_PREFERENCES = "phone_access_sessions";
    private static final String TAG = "BYDCamera";
    private static final String WEBSOCKET_GUID =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final class Request {
        final String body;
        final boolean downloadRequested;
        final Map<String, String> headers;
        final String method;
        final String path;
        final String remoteAddress;

        Request(
                String method,
                String path,
                boolean downloadRequested,
                Map<String, String> headers,
                String body,
                String remoteAddress) {
            this.method = method;
            this.path = path;
            this.downloadRequested = downloadRequested;
            this.headers = headers;
            this.body = body;
            this.remoteAddress = remoteAddress;
        }
    }

    private final AssetManager assets;
    private final Set<Socket> activeClients =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    // One thread per open connection. Browsers hold camera WebSockets and
    // paused video downloads open for long periods, so a small fixed pool
    // starves every other request; a cached pool sizes itself to the
    // handful of phones on the car hotspot.
    private final ExecutorService clients =
            Executors.newCachedThreadPool();
    private final Map<String, Long> nextPinAttempts = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final SharedPreferences sessionPreferences;
    private final CameraRecorderService service;
    private final ServerSocket serverSocket;
    private final Thread serverThread;
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();
    private final String token;
    private volatile String pin;
    private volatile boolean running = true;

    public PhoneAccessServer(
            Context context,
            CameraRecorderService service,
            String token,
            String pin) throws IOException {
        this.assets = context.getAssets();
        this.service = service;
        this.token = token;
        this.pin = pin;
        sessionPreferences =
                context.getSharedPreferences(
                        SESSION_PREFERENCES,
                        Context.MODE_PRIVATE);
        for (String session :
                sessionPreferences.getStringSet(
                        SESSION_KEY,
                        Collections.<String>emptySet())) {
            sessions.put(session, Boolean.TRUE);
        }
        serverSocket = new ServerSocket(PORT);
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
        serverThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        serve();
                    }
                },
                "byd-phone-access");
        serverThread.start();
        Log.i(TAG, "Phone app access started on port " + PORT);
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException exception) {
            Log.w(TAG, "Phone access socket close failed", exception);
        }
        serverThread.interrupt();
        closeClientConnections();
        clients.shutdownNow();
        nextPinAttempts.clear();
        Log.i(TAG, "Phone app access stopped");
    }

    public String getUrl() throws IOException {
        return String.format(
                Locale.US,
                "http://%s:%d/%s/",
                PhoneAccessNetwork.findLocalIpv4Address(),
                PORT,
                token);
    }

    public void updatePin(String updatedPin) {
        if (constantTimeEquals(pin, updatedPin)) {
            return;
        }
        pin = updatedPin;
        sessions.clear();
        persistSessions();
        nextPinAttempts.clear();
        closeClientConnections();
        Log.i(TAG, "Phone PIN changed; browser sessions invalidated");
    }

    private void serve() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                activeClients.add(client);
                try {
                    clients.execute(
                            new Runnable() {
                                @Override
                                public void run() {
                                    handleClient(client);
                                }
                            });
                } catch (RuntimeException exception) {
                    activeClients.remove(client);
                    try {
                        client.close();
                    } catch (IOException closeException) {
                        Log.w(
                                TAG,
                                "Rejected phone client close failed",
                                closeException);
                    }
                    if (running) {
                        Log.e(
                                TAG,
                                "Phone request worker rejected a client",
                                exception);
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Periodically recheck lifecycle state.
            } catch (IOException exception) {
                if (running) {
                    Log.e(TAG, "Phone access request failed", exception);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client) {
            socket.setSoTimeout(CLIENT_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            Request request = readRequest(socket);
            route(socket, request);
        } catch (IOException exception) {
            if (exception instanceof SocketException) {
                Log.d(
                        TAG,
                        "Phone browser closed a local request: "
                                + exception.getMessage());
            } else {
                Log.w(TAG, "Phone browser request failed", exception);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Phone access handler failed", exception);
        } finally {
            activeClients.remove(client);
        }
    }

    private void closeClientConnections() {
        for (Socket client : activeClients) {
            try {
                client.close();
            } catch (IOException exception) {
                Log.w(TAG, "Phone client close failed", exception);
            }
        }
        activeClients.clear();
    }

    private Request readRequest(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        String requestLine = readHttpLine(input);
        if (requestLine == null) {
            throw new IOException("Empty HTTP request");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Invalid HTTP request");
        }
        Map<String, String> headers = new HashMap<>();
        String header;
        while ((header = readHttpLine(input)) != null && !header.isEmpty()) {
            int separator = header.indexOf(':');
            if (separator > 0) {
                headers.put(
                        header.substring(0, separator).trim().toLowerCase(Locale.US),
                        header.substring(separator + 1).trim());
            }
        }
        int contentLength = parseInt(headers.get("content-length"), 0);
        if (contentLength < 0 || contentLength > BUFFER_BYTES) {
            throw new IOException("Phone request body is too large");
        }
        byte[] bodyBytes = new byte[contentLength];
        int offset = 0;
        while (offset < bodyBytes.length) {
            int count = input.read(bodyBytes, offset, bodyBytes.length - offset);
            if (count < 0) {
                throw new IOException("Incomplete HTTP request body");
            }
            offset += count;
        }
        return new Request(
                parts[0],
                stripQuery(parts[1]),
                hasQueryParameter(parts[1], "download", "1"),
                headers,
                new String(bodyBytes, StandardCharsets.UTF_8),
                socket.getInetAddress().getHostAddress());
    }

    private String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= BUFFER_BYTES) {
            int next = input.read();
            if (next < 0) {
                return line.size() == 0
                        ? null
                        : new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (next == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length -= 1;
                }
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(next);
        }
        throw new IOException("HTTP request line is too large");
    }

    private void route(Socket socket, Request request) throws IOException {
        OutputStream output = socket.getOutputStream();
        String prefix = "/" + token + "/";
        if (!request.path.startsWith(prefix)) {
            sendText(output, 404, "Private phone link not found");
            return;
        }
        String relativePath = request.path.substring(prefix.length());
        if (relativePath.equals("api/auth")) {
            routeAuthentication(output, request);
            return;
        }
        if (relativePath.startsWith("api/") && !isAuthorized(request)) {
            sendJson(output, 401, "{\"authenticated\":false}");
            return;
        }
        if (relativePath.equals("api/system") && request.method.equals("GET")) {
            SystemMonitor.Snapshot snap = service.getSystemSnapshot();
            sendJson(output, 200, snap != null ? snap.toJson() : "{}");
            return;
        }
        if (relativePath.equals("api/state") && request.method.equals("GET")) {
            service.notePhoneClient();
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (relativePath.equals("api/status") && request.method.equals("GET")) {
            service.notePhoneClient();
            sendJson(output, 200, service.createPhoneStatusJson());
            return;
        }
        if (relativePath.equals("api/recording") && request.method.equals("POST")) {
            boolean enabled = jsonBoolean(request.body, "enabled", false);
            service.setRecordingFromPhone(enabled);
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (relativePath.equals("api/parking") && request.method.equals("POST")) {
            boolean enabled = jsonBoolean(request.body, "enabled", false);
            service.setParkingFromPhone(enabled);
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (relativePath.equals("api/settings") && request.method.equals("POST")) {
            service.saveSettingsFromPhone(request.body);
            Log.i(TAG, "Phone settings request applied");
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (relativePath.equals("api/background-access")
                && request.method.equals("POST")) {
            service.requestBackgroundAccessFromPhone();
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (relativePath.equals("api/finalizing/stream")
                && request.method.equals("GET")) {
            routeFinalizingWebSocket(socket, request);
            return;
        }
        if (relativePath.startsWith("api/cameras/")
                && relativePath.endsWith("/stream")
                && request.method.equals("GET")) {
            int camera = parseInt(
                    relativePath.substring(
                            "api/cameras/".length(),
                            relativePath.length() - "/stream".length()),
                    0);
            routeCameraWebSocket(socket, request, camera - 1, false);
            return;
        }
        if (relativePath.startsWith("api/editor-cameras/")
                && relativePath.endsWith("/stream")
                && request.method.equals("GET")) {
            int camera = parseInt(
                    relativePath.substring(
                            "api/editor-cameras/".length(),
                            relativePath.length() - "/stream".length()),
                    0);
            routeCameraWebSocket(socket, request, camera - 1, true);
            return;
        }
        if (relativePath.startsWith("api/cameras/")
                && relativePath.endsWith(".jpg")
                && request.method.equals("GET")) {
            service.notePhoneClient();
            int camera = parseInt(
                    relativePath.substring(
                            "api/cameras/".length(),
                            relativePath.length() - ".jpg".length()),
                    0);
            byte[] jpeg = service.getPhonePreviewJpeg(camera - 1);
            if (jpeg == null) {
                sendText(output, 503, "Preview is starting");
            } else {
                sendBytes(output, 200, "image/jpeg", jpeg, "");
            }
            return;
        }
        if (relativePath.startsWith("api/editor-cameras/")
                && relativePath.endsWith(".jpg")
                && request.method.equals("GET")) {
            service.notePhoneClient();
            int camera = parseInt(
                    relativePath.substring(
                            "api/editor-cameras/".length(),
                            relativePath.length() - ".jpg".length()),
                    0);
            byte[] jpeg =
                    service.getPhoneUncroppedPreviewJpeg(camera - 1);
            if (jpeg == null) {
                sendText(output, 503, "Uncropped preview is starting");
            } else {
                sendBytes(output, 200, "image/jpeg", jpeg, "");
            }
            return;
        }
        if (relativePath.startsWith("api/segments/")) {
            routeSegment(output, request, relativePath);
            return;
        }
        if (!request.method.equals("GET")) {
            sendText(output, 405, "Method not allowed");
            return;
        }
        serveAsset(output, relativePath.isEmpty() ? "index.html" : relativePath);
    }

    private void routeAuthentication(OutputStream output, Request request)
            throws IOException {
        if (request.method.equals("GET")) {
            sendJson(
                    output,
                    200,
                    "{\"authenticated\":" + isAuthorized(request) + "}");
            return;
        }
        if (!request.method.equals("POST")) {
            sendText(output, 405, "Method not allowed");
            return;
        }
        long now = System.currentTimeMillis();
        Long nextAttempt = nextPinAttempts.get(request.remoteAddress);
        if (nextAttempt != null && nextAttempt > now) {
            long retrySeconds = Math.max(
                    1L,
                    (nextAttempt - now + 999L) / 1000L);
            sendJson(
                    output,
                    429,
                    "{\"authenticated\":false,\"retryAfterSeconds\":"
                            + retrySeconds
                            + "}",
                    "Retry-After: " + retrySeconds + "\r\n");
            return;
        }
        String suppliedPin = PhoneJson.stringValue(request.body, "pin", "");
        if (!constantTimeEquals(pin, suppliedPin)) {
            nextPinAttempts.put(
                    request.remoteAddress,
                    now + PIN_RETRY_MILLIS);
            sendJson(
                    output,
                    401,
                    "{\"authenticated\":false,\"retryAfterSeconds\":5}",
                    "Retry-After: 5\r\n");
            return;
        }
        nextPinAttempts.remove(request.remoteAddress);
        String session = createSessionToken();
        sessions.put(session, Boolean.TRUE);
        persistSessions();
        sendJson(
                output,
                200,
                "{\"authenticated\":true}",
                "Set-Cookie: "
                        + SESSION_COOKIE
                        + "="
                        + session
                        + "; Path=/"
                        + token
                        + "/; Max-Age="
                        + SESSION_MAX_AGE_SECONDS
                        + "; HttpOnly; SameSite=Strict\r\n");
    }

    /**
     * Completes the HTTP-to-WebSocket upgrade handshake. Returns false after
     * writing an error response when the request is not a valid upgrade.
     */
    private boolean upgradeToWebSocket(Socket socket, Request request)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        if (!"websocket".equalsIgnoreCase(request.headers.get("upgrade"))) {
            sendText(output, 404, "WebSocket upgrade required");
            return false;
        }
        String key = request.headers.get("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            sendText(output, 404, "WebSocket key is missing");
            return false;
        }
        String accept;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            accept = Base64.encodeToString(
                    digest.digest(
                            (key + WEBSOCKET_GUID)
                                    .getBytes(StandardCharsets.US_ASCII)),
                    Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("WebSocket handshake is unavailable", exception);
        }
        String response =
                "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: "
                        + accept
                        + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        return true;
    }

    /**
     * Pushes stitch-progress JSON while segments are being finalized. The
     * payload is tiny, so pushing beats having the phone poll the heavy
     * full-state endpoint for progress updates.
     */
    private void routeFinalizingWebSocket(Socket socket, Request request)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        if (!upgradeToWebSocket(socket, request)) {
            return;
        }
        String lastPayload = null;
        long lastWriteMillis = 0L;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                String payload = service.createFinalizingProgressJson();
                long now = System.currentTimeMillis();
                if (!payload.equals(lastPayload)
                        || now - lastWriteMillis
                                >= FINALIZING_HEARTBEAT_MILLIS) {
                    writeWebSocketTextFrame(output, payload);
                    output.flush();
                    lastPayload = payload;
                    lastWriteMillis = now;
                }
                Thread.sleep(FINALIZING_PUSH_INTERVAL_MILLIS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void routeCameraWebSocket(
            Socket socket,
            Request request,
            int cameraIndex,
            boolean uncropped) throws IOException {
        OutputStream output = socket.getOutputStream();
        if (cameraIndex < 0
                || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            sendText(output, 404, "Camera stream is unavailable");
            return;
        }
        // A small send buffer keeps only a frame or two in flight, so a slow
        // Wi-Fi link lowers the delivered frame rate instead of building up
        // seconds of latency in socket buffers.
        try {
            socket.setSendBufferSize(CAMERA_STREAM_SEND_BUFFER_BYTES);
        } catch (SocketException exception) {
            Log.w(TAG, "Camera stream send buffer not applied", exception);
        }
        if (!upgradeToWebSocket(socket, request)) {
            return;
        }
        long version = -1L;
        Log.i(
                TAG,
                "Phone camera WebSocket opened: camera="
                        + (cameraIndex + 1)
                        + " uncropped="
                        + uncropped
                        + " client="
                        + request.remoteAddress);
        service.addPhoneCameraSubscriber(cameraIndex, uncropped);
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                CameraRecorderService.PhonePreviewFrame frame =
                        service.awaitPhonePreview(
                                cameraIndex,
                                uncropped,
                                version);
                if (frame == null) {
                    // No frame arrived within the wait window (camera idle or
                    // stopped). Ping so a dead socket is detected and this
                    // thread and its camera subscription are released.
                    writeWebSocketPing(output);
                    output.flush();
                    continue;
                }
                version = frame.version;
                if (frame.jpeg != null) {
                    writeWebSocketBinaryFrame(output, frame.jpeg);
                    output.flush();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            service.removePhoneCameraSubscriber(cameraIndex, uncropped);
            Log.i(
                    TAG,
                    "Phone camera WebSocket closed: camera="
                            + (cameraIndex + 1)
                            + " uncropped="
                            + uncropped
                            + " client="
                            + request.remoteAddress);
        }
    }

    private void writeWebSocketPing(OutputStream output) throws IOException {
        output.write(0x89);
        output.write(0);
    }

    private void writeWebSocketTextFrame(
            OutputStream output,
            String payload) throws IOException {
        writeWebSocketFrame(
                output,
                0x81,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private void writeWebSocketBinaryFrame(
            OutputStream output,
            byte[] payload) throws IOException {
        writeWebSocketFrame(output, 0x82, payload);
    }

    private void writeWebSocketFrame(
            OutputStream output,
            int opcode,
            byte[] payload) throws IOException {
        output.write(opcode);
        int length = payload.length;
        if (length <= 125) {
            output.write(length);
        } else if (length <= 65_535) {
            output.write(126);
            output.write((length >>> 8) & 0xff);
            output.write(length & 0xff);
        } else {
            output.write(127);
            long longLength = length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) ((longLength >>> shift) & 0xffL));
            }
        }
        output.write(payload);
    }

    private void routeSegment(
            OutputStream output,
            Request request,
            String relativePath) throws IOException {
        String remainder = relativePath.substring("api/segments/".length());
        int actionSeparator = remainder.indexOf('/');
        if (actionSeparator <= 0) {
            sendText(output, 404, "Recording not found");
            return;
        }
        String segmentId = decode(remainder.substring(0, actionSeparator));
        String action = remainder.substring(actionSeparator + 1);
        if (action.equals("lock") && request.method.equals("POST")) {
            service.setSegmentLockedFromPhone(
                    segmentId,
                    jsonBoolean(request.body, "locked", false));
            sendJson(output, 200, service.createPhoneStateJson());
            return;
        }
        if (action.equals("preview.jpg") && request.method.equals("GET")) {
            sendCachedBytes(
                    output,
                    200,
                    "image/jpeg",
                    service.getPhoneSegmentPreviewJpeg(segmentId),
                    "");
            return;
        }
        if (action.startsWith("files/") && request.method.equals("GET")) {
            String fileName = decode(action.substring("files/".length()));
            File file = service.resolvePhoneVideo(segmentId, fileName);
            sendVideo(
                    output,
                    file,
                    request.headers.get("range"),
                    request.downloadRequested);
            return;
        }
        sendText(output, 404, "Recording action not found");
    }

    private void serveAsset(OutputStream output, String path) throws IOException {
        if (path.contains("..") || path.startsWith("/")) {
            sendText(output, 404, "Asset not found");
            return;
        }
        try (InputStream input = assets.open("phone/" + path)) {
            byte[] body = readAll(input);
            sendBytes(output, 200, contentType(path), body, "");
        } catch (IOException exception) {
            sendText(output, 404, "Asset not found");
        }
    }

    private void sendVideo(
            OutputStream output,
            File file,
            String rangeHeader,
            boolean downloadRequested)
            throws IOException {
        long start = 0L;
        long end = file.length() - 1L;
        int status = 200;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String range = rangeHeader.substring("bytes=".length());
            int separator = range.indexOf('-');
            if (separator >= 0) {
                start = parseLong(range.substring(0, separator), 0L);
                String requestedEnd = range.substring(separator + 1);
                if (!requestedEnd.isEmpty()) {
                    end = Math.min(end, parseLong(requestedEnd, end));
                }
                status = 206;
            }
        }
        if (start < 0L || start > end || end >= file.length()) {
            sendText(output, 416, "Requested range is unavailable");
            return;
        }
        String extra = "Accept-Ranges: bytes\r\n";
        if (downloadRequested) {
            extra += "Content-Disposition: attachment; filename=\""
                    + safeHeaderFileName(file.getName())
                    + "\"\r\n";
        }
        if (status == 206) {
            extra += String.format(
                    Locale.US,
                    "Content-Range: bytes %d-%d/%d\r\n",
                    start,
                    end,
                    file.length());
        }
        writeHeaders(output, status, "video/mp4", end - start + 1L, extra);
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = end - start + 1L;
        try (FileInputStream input = new FileInputStream(file)) {
            long skipped = 0L;
            while (skipped < start) {
                long count = input.skip(start - skipped);
                if (count <= 0L) {
                    throw new IOException("Cannot seek recording file");
                }
                skipped += count;
            }
            while (remaining > 0L) {
                int count = input.read(
                        buffer,
                        0,
                        (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        output.flush();
    }

    private void sendJson(OutputStream output, int status, String json)
            throws IOException {
        sendJson(output, status, json, "");
    }

    private void sendJson(
            OutputStream output,
            int status,
            String json,
            String extraHeaders) throws IOException {
        sendBytes(
                output,
                status,
                "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8),
                extraHeaders);
    }

    private void sendText(OutputStream output, int status, String message)
            throws IOException {
        sendBytes(
                output,
                status,
                "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8),
                "");
    }

    private void sendBytes(
            OutputStream output,
            int status,
            String contentType,
            byte[] body,
            String extraHeaders) throws IOException {
        writeHeaders(
                output,
                status,
                contentType,
                body.length,
                extraHeaders,
                "no-store");
        output.write(body);
        output.flush();
    }

    private void sendCachedBytes(
            OutputStream output,
            int status,
            String contentType,
            byte[] body,
            String extraHeaders) throws IOException {
        writeHeaders(
                output,
                status,
                contentType,
                body.length,
                extraHeaders,
                "private, max-age=31536000, immutable");
        output.write(body);
        output.flush();
    }

    private void writeHeaders(
            OutputStream output,
            int status,
            String contentType,
            long contentLength,
            String extraHeaders) throws IOException {
        writeHeaders(
                output,
                status,
                contentType,
                contentLength,
                extraHeaders,
                "no-store");
    }

    private void writeHeaders(
            OutputStream output,
            int status,
            String contentType,
            long contentLength,
            String extraHeaders,
            String cacheControl) throws IOException {
        String reason;
        switch (status) {
            case 200:
                reason = "OK";
                break;
            case 206:
                reason = "Partial Content";
                break;
            case 401:
                reason = "Unauthorized";
                break;
            case 405:
                reason = "Method Not Allowed";
                break;
            case 429:
                reason = "Too Many Requests";
                break;
            case 416:
                reason = "Range Not Satisfiable";
                break;
            case 503:
                reason = "Service Unavailable";
                break;
            default:
                reason = "Not Found";
                break;
        }
        String headers = String.format(
                Locale.US,
                "HTTP/1.1 %d %s\r\n"
                        + "Content-Type: %s\r\n"
                        + "Content-Length: %d\r\n"
                        + "Cache-Control: %s\r\n"
                        + "X-Content-Type-Options: nosniff\r\n"
                        + "X-Frame-Options: DENY\r\n"
                        + "%s"
                        + "Connection: close\r\n\r\n",
                status,
                reason,
                contentType,
                contentLength,
                cacheControl,
                extraHeaders);
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean isAuthorized(Request request) {
        String cookieHeader = request.headers.get("cookie");
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return false;
        }
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String trimmed = cookie.trim();
            String prefix = SESSION_COOKIE + "=";
            if (trimmed.startsWith(prefix)) {
                return sessions.containsKey(trimmed.substring(prefix.length()));
            }
        }
        return false;
    }

    private String createSessionToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder tokenBuilder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            tokenBuilder.append(
                    String.format(Locale.US, "%02x", value & 0xff));
        }
        return tokenBuilder.toString();
    }

    private synchronized void persistSessions() {
        sessionPreferences.edit()
                .putStringSet(
                        SESSION_KEY,
                        new HashSet<>(sessions.keySet()))
                .apply();
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes =
                (left == null ? "" : left).getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes =
                (right == null ? "" : right).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) {
            return "application/json; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private String decode(String value) throws IOException {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
    }

    private boolean jsonBoolean(String json, String key, boolean fallback) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        int separator = keyIndex < 0 ? -1 : json.indexOf(':', keyIndex + marker.length());
        if (separator < 0) {
            return fallback;
        }
        String value = json.substring(separator + 1).trim();
        return value.startsWith("true")
                || (!value.startsWith("false") && fallback);
    }

    private String stripQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private boolean hasQueryParameter(
            String path,
            String expectedKey,
            String expectedValue) {
        int query = path.indexOf('?');
        if (query < 0 || query >= path.length() - 1) {
            return false;
        }
        String[] parameters = path.substring(query + 1).split("&");
        for (String parameter : parameters) {
            int separator = parameter.indexOf('=');
            String key = separator < 0
                    ? parameter
                    : parameter.substring(0, separator);
            String value = separator < 0
                    ? ""
                    : parameter.substring(separator + 1);
            if (expectedKey.equals(key) && expectedValue.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private String safeHeaderFileName(String fileName) {
        return fileName.replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null || value.isEmpty()
                    ? fallback
                    : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
