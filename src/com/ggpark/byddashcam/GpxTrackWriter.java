package com.ggpark.byddashcam;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 세그먼트별 GPX 궤적 파일 저장기.
 * 세그먼트 시작 시 open(), 매 GPS fix마다 offerFix(), 종료 시 close().
 */
public final class GpxTrackWriter {
    private static final String TAG = "BYDCamera";
    private static final String GPX_FILE = "gps.gpx";
    private static final long POINT_INTERVAL_MS = 1000L;

    private final SimpleDateFormat iso8601;

    private BufferedWriter writer;
    private long lastPointMs = 0L;
    private boolean hasPoints = false;

    public GpxTrackWriter() {
        iso8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso8601.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /** 세그먼트 디렉토리에 gps.gpx를 생성하고 헤더를 씁니다. */
    public void open(File segmentDirectory) throws IOException {
        File gpxFile = new File(segmentDirectory, GPX_FILE);
        writer = new BufferedWriter(new FileWriter(gpxFile));
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write("<gpx version=\"1.1\" creator=\"BYD블랙박스\"\n");
        writer.write("  xmlns=\"http://www.topografix.com/GPX/1/1\"\n");
        writer.write("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        writer.write("  <trk><trkseg>\n");
        writer.flush();
        lastPointMs = 0L;
        hasPoints = false;
    }

    /**
     * GPS fix를 GPX trkpt로 기록합니다.
     * POINT_INTERVAL_MS(1초) 이상 간격인 경우에만 기록합니다.
     */
    public void offerFix(GpsFix fix, long wallTimeMs) {
        if (writer == null || fix == null || !fix.isAvailable()) {
            return;
        }
        if (lastPointMs != 0L && wallTimeMs - lastPointMs < POINT_INTERVAL_MS) {
            return;
        }
        try {
            lastPointMs = wallTimeMs;
            hasPoints = true;
            writer.write(String.format(Locale.US,
                    "    <trkpt lat=\"%.7f\" lon=\"%.7f\">\n",
                    fix.latitude, fix.longitude));
            writer.write(String.format(Locale.US,
                    "      <ele>%.1f</ele>\n", fix.altitude));
            writer.write(String.format(Locale.US,
                    "      <time>%s</time>\n",
                    iso8601.format(new Date(fix.fixTimeMs))));
            writer.write(String.format(Locale.US,
                    "      <extensions><speed>%.2f</speed></extensions>\n",
                    fix.speedKmh / 3.6)); // km/h → m/s
            writer.write("    </trkpt>\n");
            writer.flush();
        } catch (IOException exception) {
            Log.w(TAG, "GPX write failed", exception);
        }
    }

    /** GPX 파일을 닫습니다. fix가 하나도 없으면 파일을 삭제합니다. */
    public void close(File segmentDirectory) {
        if (writer == null) {
            return;
        }
        try {
            writer.write("  </trkseg></trk>\n</gpx>\n");
            writer.close();
        } catch (IOException exception) {
            Log.w(TAG, "GPX close failed", exception);
        } finally {
            writer = null;
        }
        if (!hasPoints) {
            // GPS fix가 없었으면 빈 파일 삭제
            new File(segmentDirectory, GPX_FILE).delete();
        }
    }
}
