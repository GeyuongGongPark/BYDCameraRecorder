package com.ggpark.byddashcam;

/** 차량 텔레메트리 데이터 스냅샷. 불변 객체. GpsFix 패턴과 동일한 구조. */
public final class VehicleTelemetry {
    /** 텔레메트리 미사용 또는 BYD API 미지원 기기에서 반환되는 sentinel 값 */
    public static final VehicleTelemetry UNAVAILABLE =
            new VehicleTelemetry(0, 0, 0, 0, 0);

    /** 속도 (km/h, 0-255 클램프) */
    public final int speedKmh;
    /** 가속 페달 깊이 (0-100%) */
    public final int acceleratorPercent;
    /** 브레이크 페달 깊이 (0-100%) */
    public final int brakePercent;
    /**
     * 기어/방향지시등/안전벨트 복합 플래그.
     * bit0=P, bit1=R, bit2=N, bit3=D, bit4=좌회전등, bit5=우회전등, bit6=안전벨트
     */
    public final int gearBlinkBeltFlags;
    /**
     * 조명 상태 플래그.
     * bit0=위치등, bit1=하향등, bit2=상향등, bit3=안개등
     */
    public final int lightFlags;

    public VehicleTelemetry(
            int speedKmh,
            int acceleratorPercent,
            int brakePercent,
            int gearBlinkBeltFlags,
            int lightFlags) {
        this.speedKmh = speedKmh;
        this.acceleratorPercent = acceleratorPercent;
        this.brakePercent = brakePercent;
        this.gearBlinkBeltFlags = gearBlinkBeltFlags;
        this.lightFlags = lightFlags;
    }

    public boolean isAvailable() {
        return this != UNAVAILABLE;
    }

    /** 현재 기어 문자를 반환합니다. 복수 비트 설정 시 우선순위: P > R > N > D */
    public char gearChar() {
        if ((gearBlinkBeltFlags & 0x01) != 0) return 'P';
        if ((gearBlinkBeltFlags & 0x02) != 0) return 'R';
        if ((gearBlinkBeltFlags & 0x04) != 0) return 'N';
        if ((gearBlinkBeltFlags & 0x08) != 0) return 'D';
        return '?';
    }

    public boolean isTurnLeftActive() {
        return (gearBlinkBeltFlags & (1 << 4)) != 0;
    }

    public boolean isTurnRightActive() {
        return (gearBlinkBeltFlags & (1 << 5)) != 0;
    }

    public boolean isGearKnown() {
        return (gearBlinkBeltFlags & 0x0f) != 0;
    }
}
