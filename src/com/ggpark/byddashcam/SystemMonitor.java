package com.ggpark.byddashcam;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * CPU, 메모리, 배터리 상태를 수집합니다.
 * /proc/stat, /proc/meminfo, BatteryManager를 사용합니다.
 */
public final class SystemMonitor {
    private static final String TAG = "BYDCamera";

    public static final class Snapshot {
        public final float cpuPercent;
        public final long memUsedMb;
        public final long memTotalMb;
        public final int batteryPercent;
        public final boolean charging;
        public final float batteryTempCelsius;

        Snapshot(float cpuPercent, long memUsedMb, long memTotalMb,
                int batteryPercent, boolean charging, float batteryTempCelsius) {
            this.cpuPercent = cpuPercent;
            this.memUsedMb = memUsedMb;
            this.memTotalMb = memTotalMb;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.batteryTempCelsius = batteryTempCelsius;
        }

        public String toJson() {
            return "{"
                    + "\"cpuPercent\":" + String.format("%.1f", cpuPercent)
                    + ",\"memUsedMb\":" + memUsedMb
                    + ",\"memTotalMb\":" + memTotalMb
                    + ",\"batteryPercent\":" + batteryPercent
                    + ",\"charging\":" + charging
                    + ",\"batteryTempC\":" + String.format("%.1f", batteryTempCelsius)
                    + "}";
        }
    }

    // 이전 CPU 카운터 (delta 계산용)
    private long prevTotal = 0L;
    private long prevIdle  = 0L;

    public Snapshot snapshot(Context context) {
        float cpu = readCpuPercent();
        long[] mem = readMemInfo();
        int[] batt = readBattery(context);
        return new Snapshot(
                cpu,
                mem[0], mem[1],
                batt[0],
                batt[1] == 1,
                batt[2] / 10.0f);
    }

    private float readCpuPercent() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine(); // "cpu  user nice system idle iowait irq softirq"
            if (line == null || !line.startsWith("cpu ")) {
                return 0f;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5) return 0f;
            long user    = Long.parseLong(parts[1]);
            long nice    = Long.parseLong(parts[2]);
            long system  = Long.parseLong(parts[3]);
            long idle    = Long.parseLong(parts[4]);
            long iowait  = parts.length > 5 ? Long.parseLong(parts[5]) : 0L;
            long irq     = parts.length > 6 ? Long.parseLong(parts[6]) : 0L;
            long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0L;

            long totalIdle = idle + iowait;
            long total = user + nice + system + idle + iowait + irq + softirq;

            long deltaTotal = total - prevTotal;
            long deltaIdle  = totalIdle - prevIdle;
            prevTotal = total;
            prevIdle  = totalIdle;

            if (deltaTotal <= 0) return 0f;
            return 100f * (deltaTotal - deltaIdle) / (float) deltaTotal;
        } catch (IOException | NumberFormatException exception) {
            Log.d(TAG, "CPU read failed", exception);
            return 0f;
        }
    }

    /** @return [usedMb, totalMb] */
    private long[] readMemInfo() {
        long totalKb = 0L;
        long availableKb = 0L;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    totalKb = parseMemKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    availableKb = parseMemKb(line);
                }
                if (totalKb > 0 && availableKb > 0) break;
            }
        } catch (IOException exception) {
            Log.d(TAG, "MemInfo read failed", exception);
        }
        long totalMb = totalKb / 1024L;
        long usedMb  = (totalKb - availableKb) / 1024L;
        return new long[]{usedMb, totalMb};
    }

    private long parseMemKb(String line) {
        // "MemTotal:       3906804 kB"
        String[] parts = line.trim().split("\\s+");
        return parts.length >= 2 ? Long.parseLong(parts[1]) : 0L;
    }

    /** @return [percent, charging(1/0), tempTenths] */
    private int[] readBattery(Context context) {
        try {
            Intent intent = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return new int[]{-1, 0, 0};
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int temp   = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int percent = scale > 0 ? (level * 100 / scale) : -1;
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            return new int[]{percent, charging ? 1 : 0, temp};
        } catch (Exception exception) {
            Log.d(TAG, "Battery read failed", exception);
            return new int[]{-1, 0, 0};
        }
    }
}
