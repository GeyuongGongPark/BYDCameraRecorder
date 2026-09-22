package android.hardware.bydauto.bodywork;

/**
 * BYD 차체(bodywork) 이벤트 추상 리스너 — 컴파일용 스텁.
 * 실제 구현은 BYD 시스템 클래스패스에 있습니다.
 */
public abstract class AbsBYDAutoBodyworkListener {
    /** 차량 알람 상태 변경. state: 1=ON, 0=OFF */
    public void onAlarmStateChanged(int state) {}
    /** 자동 시스템 상태 변경 */
    public void onAutoSystemStateChanged(int state) {}
    /** 배터리 전압 레벨 변경. level: 0=LOW, 1=NORMAL, 2=INVALID */
    public void onBatteryVoltageLevelChanged(int level) {}
    /** 도어 상태 변경. area: 도어 구역 번호, state: 1=OPEN, 0=CLOSED */
    public void onDoorStateChanged(int area, int state) {}
    /** 전원 레벨 변경. level: 0=OFF, 1=ACC, 2=ON, 3=OK */
    public void onPowerLevelChanged(int level) {}
    /** 창문 상태 변경. area: 창문 구역 번호, state: 1=OPEN, 0=CLOSED */
    public void onWindowStateChanged(int area, int state) {}
}
