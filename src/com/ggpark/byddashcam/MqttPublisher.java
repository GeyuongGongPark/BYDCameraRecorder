package com.ggpark.byddashcam;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MQTT 3.1.1 QoS 0 경량 퍼블리셔.
 * 외부 라이브러리 없이 TCP 소켓으로 직접 구현합니다.
 * Home Assistant MQTT Discovery 자동 등록을 지원합니다.
 *
 * 지원: CONNECT, PUBLISH, PING, DISCONNECT
 * 미지원: QoS 1/2, SUBSCRIBE, TLS (로컬 HA 브로커 기준)
 */
public final class MqttPublisher {
    private static final String TAG = "BYDCamera";
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int KEEPALIVE_SECONDS  = 60;
    private static final int PING_INTERVAL_MS   = 50_000;
    private static final int RECONNECT_DELAY_MS = 10_000;
    private static final String CLIENT_ID = "byd-blackbox";
    private static final int QUEUE_CAPACITY = 64;

    private static final class Message {
        final String topic;
        final byte[] payload;
        final boolean retain;

        Message(String topic, byte[] payload, boolean retain) {
            this.topic   = topic;
            this.payload = payload;
            this.retain  = retain;
        }
    }

    private static final Message POISON = new Message("", new byte[0], false);

    private final BlockingQueue<Message> queue =
            new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private volatile String host;
    private volatile int    port;
    private volatile String username;
    private volatile String password;
    private volatile boolean enabled;
    private volatile boolean running;

    private Thread workerThread;

    public MqttPublisher(String host, int port, String username, String password, boolean enabled) {
        this.host     = host;
        this.port     = port;
        this.username = username;
        this.password = password;
        this.enabled  = enabled;
    }

    public void update(String host, int port, String username, String password, boolean enabled) {
        this.host     = host;
        this.port     = port;
        this.username = username;
        this.password = password;
        boolean wasEnabled = this.enabled;
        this.enabled  = enabled;
        if (enabled && !wasEnabled) {
            start();
        } else if (!enabled && wasEnabled) {
            stop();
        }
    }

    public synchronized void start() {
        if (!enabled || running) return;
        running = true;
        workerThread = new Thread(this::workerLoop, "byd-mqtt");
        workerThread.setDaemon(true);
        workerThread.start();
        Log.i(TAG, "MqttPublisher started -> " + host + ":" + port);
    }

    public synchronized void stop() {
        running = false;
        queue.offer(POISON);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    /** 메시지를 큐에 넣습니다. 큐가 가득 찼으면 가장 오래된 항목을 버립니다. */
    public void publish(String topic, String payload) {
        publish(topic, payload, false);
    }

    public void publish(String topic, String payload, boolean retain) {
        if (!enabled || topic == null || payload == null) return;
        Message msg = new Message(topic, payload.getBytes(StandardCharsets.UTF_8), retain);
        if (!queue.offer(msg)) {
            queue.poll();
            queue.offer(msg);
        }
    }

    /**
     * Home Assistant MQTT Discovery 설정 토픽을 발행합니다.
     * https://www.home-assistant.io/integrations/mqtt/#mqtt-discovery
     */
    public void publishHaDiscovery(String nodeId, String objectId,
            String name, String stateTopic, String unit, String deviceClass) {
        String config = "{\"name\":" + PhoneJson.quote(name)
                + ",\"state_topic\":" + PhoneJson.quote(stateTopic)
                + (unit       != null ? ",\"unit_of_measurement\":" + PhoneJson.quote(unit) : "")
                + (deviceClass != null ? ",\"device_class\":" + PhoneJson.quote(deviceClass) : "")
                + ",\"unique_id\":" + PhoneJson.quote(nodeId + "_" + objectId)
                + ",\"device\":{\"identifiers\":[" + PhoneJson.quote(nodeId) + "]"
                + ",\"name\":\"BYD 블랙박스\",\"model\":\"BYDCameraRecorder\",\"manufacturer\":\"ggpark\"}"
                + "}";
        String topic = "homeassistant/sensor/" + nodeId + "/" + objectId + "/config";
        publish(topic, config, true);
    }

    // ── 워커 루프 ─────────────────────────────────────────────────────────────

    private void workerLoop() {
        while (running) {
            try {
                connectAndRun();
            } catch (Exception exception) {
                if (running) {
                    Log.w(TAG, "MQTT disconnected, reconnecting in " + RECONNECT_DELAY_MS + "ms", exception);
                    sleepQuietly(RECONNECT_DELAY_MS);
                }
            }
        }
        Log.i(TAG, "MqttPublisher stopped");
    }

    private void connectAndRun() throws IOException, InterruptedException {
        Socket socket = new Socket();
        socket.connect(
                new java.net.InetSocketAddress(host, port),
                CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(PING_INTERVAL_MS + 5_000);
        OutputStream out = socket.getOutputStream();
        InputStream  in  = socket.getInputStream();

        sendConnect(out);
        readConnAck(in);
        Log.i(TAG, "MQTT connected to " + host + ":" + port);

        long lastPingSentAt = System.currentTimeMillis();
        try {
            while (running) {
                Message msg = queue.poll(PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (msg == POISON || !running) break;
                if (msg != null) {
                    sendPublish(out, msg.topic, msg.payload, msg.retain);
                }
                long now = System.currentTimeMillis();
                if (now - lastPingSentAt >= PING_INTERVAL_MS) {
                    sendPingReq(out);
                    lastPingSentAt = now;
                }
            }
        } finally {
            try { sendDisconnect(out); } catch (Exception ignored) {}
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ── MQTT 패킷 인코딩 ────────────────────────────────────────────────────

    private void sendConnect(OutputStream out) throws IOException {
        byte[] clientIdBytes  = CLIENT_ID.getBytes(StandardCharsets.UTF_8);
        byte[] userBytes      = username != null && !username.isEmpty()
                ? username.getBytes(StandardCharsets.UTF_8) : null;
        byte[] passBytes      = password != null && !password.isEmpty()
                ? password.getBytes(StandardCharsets.UTF_8) : null;

        int payloadLen = 6 + 2 + "MQTT".length() // protocol name
                + 1 + 1 + 2                        // protocol level, flags, keepalive
                + 2 + clientIdBytes.length;
        byte connectFlags = 0x02; // clean session
        if (userBytes != null) {
            connectFlags |= 0x80;
            payloadLen += 2 + userBytes.length;
        }
        if (passBytes != null) {
            connectFlags |= 0x40;
            payloadLen += 2 + passBytes.length;
        }

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        // Fixed header: CONNECT (0x10)
        buf.write(0x10);
        writeVarInt(buf, payloadLen);
        // Protocol Name "MQTT"
        writeUtf8(buf, "MQTT");
        // Protocol Level 4 (MQTT 3.1.1)
        buf.write(4);
        // Connect Flags
        buf.write(connectFlags);
        // Keep Alive
        buf.write((KEEPALIVE_SECONDS >> 8) & 0xFF);
        buf.write(KEEPALIVE_SECONDS & 0xFF);
        // Client Identifier
        buf.write((clientIdBytes.length >> 8) & 0xFF);
        buf.write(clientIdBytes.length & 0xFF);
        buf.write(clientIdBytes);
        if (userBytes != null) {
            buf.write((userBytes.length >> 8) & 0xFF);
            buf.write(userBytes.length & 0xFF);
            buf.write(userBytes);
        }
        if (passBytes != null) {
            buf.write((passBytes.length >> 8) & 0xFF);
            buf.write(passBytes.length & 0xFF);
            buf.write(passBytes);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private void readConnAck(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 != 0x20 || b1 != 0x02) {
            throw new IOException("Expected CONNACK, got " + b0 + " " + b1);
        }
        int sessionPresent = in.read();
        int returnCode     = in.read();
        if (returnCode != 0) {
            throw new IOException("CONNACK refused: code=" + returnCode);
        }
    }

    private void sendPublish(OutputStream out, String topic, byte[] payload, boolean retain)
            throws IOException {
        byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
        int remainLen = 2 + topicBytes.length + payload.length;
        byte fixedHeader = (byte) (0x30 | (retain ? 0x01 : 0x00)); // QoS 0

        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(fixedHeader);
        writeVarInt(buf, remainLen);
        buf.write((topicBytes.length >> 8) & 0xFF);
        buf.write(topicBytes.length & 0xFF);
        buf.write(topicBytes);
        buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }

    private void sendPingReq(OutputStream out) throws IOException {
        out.write(new byte[]{(byte) 0xC0, 0x00});
        out.flush();
    }

    private void sendDisconnect(OutputStream out) throws IOException {
        out.write(new byte[]{(byte) 0xE0, 0x00});
        out.flush();
    }

    private void writeVarInt(java.io.ByteArrayOutputStream buf, int value) {
        do {
            int digit = value & 0x7F;
            value >>= 7;
            if (value > 0) digit |= 0x80;
            buf.write(digit);
        } while (value > 0);
    }

    private void writeUtf8(java.io.ByteArrayOutputStream buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.write((bytes.length >> 8) & 0xFF);
        buf.write(bytes.length & 0xFF);
        buf.write(bytes, 0, bytes.length);
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
