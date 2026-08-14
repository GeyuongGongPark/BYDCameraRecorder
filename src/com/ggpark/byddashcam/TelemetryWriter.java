package com.ggpark.byddashcam;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 차량 텔레메트리를 세그먼트 디렉토리의 telemetry.bin 파일에 기록합니다.
 *
 * <p>패킷 포맷 (9바이트, little-endian):
 * <pre>
 * offset 0: int32  deltaMs            - 세그먼트 시작 기준 경과 ms
 * offset 4: uint8  speedKmh           - 0-255 클램프
 * offset 5: uint8  acceleratorPercent - 0-100
 * offset 6: uint8  brakePercent       - 0-100
 * offset 7: uint8  gearBlinkBeltFlags - bit0=P,1=R,2=N,3=D,4=좌,5=우,6=벨트
 * offset 8: uint8  lightFlags         - bit0=위치등,1=하향등,2=상향등,3=안개등
 * </pre>
 */
public final class TelemetryWriter {
    private static final String TAG = "BYDCamera";
    static final String FILENAME = "telemetry.bin";
    private static final int PACKET_SIZE = 9;
    // 1초(100ms * 10개)에 해당하는 버퍼 크기
    private static final int BUFFER_PACKETS = 100;

    private BufferedOutputStream outputStream;
    private long segmentStartNanos;
    private final byte[] packetBytes = new byte[PACKET_SIZE];
    private final ByteBuffer packetBuf =
            ByteBuffer.wrap(packetBytes).order(ByteOrder.LITTLE_ENDIAN);

    public void open(File segmentDirectory, long segmentStartNanos) throws IOException {
        close();
        this.segmentStartNanos = segmentStartNanos;
        File file = new File(segmentDirectory, FILENAME);
        outputStream = new BufferedOutputStream(
                new FileOutputStream(file),
                BUFFER_PACKETS * PACKET_SIZE);
    }

    public void offer(VehicleTelemetry telemetry, long nowNanos) {
        if (outputStream == null || telemetry == null || !telemetry.isAvailable()) {
            return;
        }
        try {
            long elapsedNanos = nowNanos - segmentStartNanos;
            int deltaMs = (int) Math.min(
                    (long) Integer.MAX_VALUE,
                    elapsedNanos / 1_000_000L);
            packetBuf.clear();
            packetBuf.putInt(deltaMs);
            packetBuf.put((byte) (telemetry.speedKmh & 0xff));
            packetBuf.put((byte) (telemetry.acceleratorPercent & 0xff));
            packetBuf.put((byte) (telemetry.brakePercent & 0xff));
            packetBuf.put((byte) (telemetry.gearBlinkBeltFlags & 0xff));
            packetBuf.put((byte) (telemetry.lightFlags & 0xff));
            outputStream.write(packetBytes, 0, PACKET_SIZE);
        } catch (IOException e) {
            Log.w(TAG, "Telemetry write failed", e);
            close();
        }
    }

    public void close() {
        if (outputStream != null) {
            try {
                outputStream.flush();
                outputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Telemetry close failed", e);
            }
            outputStream = null;
        }
    }
}
