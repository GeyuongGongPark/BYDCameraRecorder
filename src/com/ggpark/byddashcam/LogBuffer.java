package com.ggpark.byddashcam;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 최근 N개 로그 항목을 메모리에 유지하는 순환 버퍼.
 * PhoneAccessServer를 통해 /api/debug/logs 로 조회됩니다.
 */
public final class LogBuffer {
    private static final int MAX_ENTRIES = 500;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final ArrayDeque<String> entries = new ArrayDeque<>();

    public synchronized void append(String tag, String message) {
        String line = TIME_FORMAT.format(new Date()) + " [" + tag + "] " + message;
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(line);
    }

    /** 현재 저장된 모든 항목을 시간순으로 반환합니다. */
    public synchronized List<String> snapshot() {
        return new ArrayList<>(entries);
    }

    /** JSON 배열 문자열로 직렬화합니다. */
    public synchronized String toJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String entry : entries) {
            if (!first) sb.append(",");
            sb.append("\"");
            sb.append(entry.replace("\\", "\\\\").replace("\"", "\\\""));
            sb.append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
