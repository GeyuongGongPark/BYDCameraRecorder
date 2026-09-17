package com.ggpark.byddashcam;

/** 차량 텔레메트리 데이터 스냅샷. 불변 객체. GpsFix 패턴과 동일한 구조. */
public final class VehicleTelemetry {
    /** 텔레메트리 미사용 또는 BYD API 미지원 기기에서 반환되는 sentinel 값 */
    public static final VehicleTelemetry UNAVAILABLE =
            new VehicleTelemetry(0, 0, 0, 0, 0, Integer.MIN_VALUE, -1, -1, -1, -1, -1);

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

    /**
     * BYD API의 getGearboxAutoModeType() 원시 반환값.
     * 매핑 디버깅용. Integer.MIN_VALUE = 디바이스 없음.
     */
    public final int rawGear;

    /** 배터리 잔량 (0-100%). -1 = 미지원 */
    public final int batteryPercent;
    /** 전기 주행 가능 거리 (km). -1 = 미지원 */
    public final int drivingRangeKm;
    /**
     * 에너지 모드. -1 = 미지원.
     * 0=STOP, 1=EV, 2=FORCE_EV, 3=HEV, 4=FUEL, 5=KEEP
     */
    public final int energyMode;
    /**
     * 드라이브 모드. -1 = 미지원.
     * 1=에코, 2=스포츠
     */
    public final int operationMode;
    /**
     * 차량 전원 단계. -1 = 미지원.
     * 0=OFF, 1=ACC, 2=ON(시동)
     */
    public final int powerLevel;

    public VehicleTelemetry(
            int speedKmh,
            int acceleratorPercent,
            int brakePercent,
            int gearBlinkBeltFlags,
            int lightFlags,
            int rawGear,
            int batteryPercent,
            int drivingRangeKm,
            int energyMode,
            int operationMode,
            int powerLevel) {
        this.speedKmh = speedKmh;
        this.acceleratorPercent = acceleratorPercent;
        this.brakePercent = brakePercent;
        this.gearBlinkBeltFlags = gearBlinkBeltFlags;
        this.lightFlags = lightFlags;
        this.rawGear = rawGear;
        this.batteryPercent = batteryPercent;
        this.drivingRangeKm = drivingRangeKm;
        this.energyMode = energyMode;
        this.operationMode = operationMode;
        this.powerLevel = powerLevel;
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

    /**
     * 시동이 켜진 상태인지 반환합니다.
     * BODYWORK_POWER_LEVEL_ON=2, OK=3, FAKE_OK=4 → true
     * OFF=0, ACC=1, INVALID=255 → false
     */
    public boolean isIgnitionOn() {
        return powerLevel >= 2 && powerLevel < 255;
    }

    /** 주행 기어(D/M/S) 또는 후진 기어(R) 상태인지 반환합니다. P/N이나 미확인이면 false. */
    public boolean isDriving() {
        return (gearBlinkBeltFlags & 0x0a) != 0; // bit1=R, bit3=D
    }
}
