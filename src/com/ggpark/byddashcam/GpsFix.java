package com.ggpark.byddashcam;

/** GPS 위치 데이터 스냅샷. 불변 객체. */
public final class GpsFix {
    /** GPS 신호 없음을 나타내는 sentinel 값 */
    public static final GpsFix UNAVAILABLE =
            new GpsFix(0.0, 0.0, 0.0, 0.0, 0L, false);

    public final double speedKmh;
    public final double latitude;
    public final double longitude;
    public final double altitude;
    /** fix 시각 (System.currentTimeMillis 기준) */
    public final long fixTimeMs;
    /** fix 나이가 5초 이내이면 true */
    public final boolean fresh;

    public GpsFix(
            double speedKmh,
            double latitude,
            double longitude,
            double altitude,
            long fixTimeMs,
            boolean fresh) {
        this.speedKmh = speedKmh;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.fixTimeMs = fixTimeMs;
        this.fresh = fresh;
    }

    public boolean isAvailable() {
        return this != UNAVAILABLE && fixTimeMs > 0L;
    }
}
