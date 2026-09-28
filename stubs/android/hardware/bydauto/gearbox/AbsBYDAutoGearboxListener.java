package android.hardware.bydauto.gearbox;

/**
 * BYD 변속기(Gearbox) 이벤트 추상 리스너 — 컴파일용 스텁.
 * 실제 구현은 BYD 시스템 클래스패스에 있습니다.
 */
public abstract class AbsBYDAutoGearboxListener {
    /** 오토 모드 변속단 변경. type: 1=P, 2=R, 3=N, 4=D, 5=M, 6=S */
    public void onGearboxAutoModeTypeChanged(int type) {}
    /** 현재 기어 변경 */
    public void onCurrentGearChanged(int gear) {}
    /** 수동 모드 단수 변경 */
    public void onGearboxManualModeLevelChanged(int level) {}
    /** 기어박스 상태 변경 */
    public void onGearboxStateChanged(int state) {}
    /** 브레이크 액 레벨 변경 */
    public void onBrakeFluidLevelChanged(int level) {}
    /** 브레이크 페달 상태 변경 */
    public void onBrakePedalStateChanged(int state) {}
    /** 파킹 브레이크 스위치 변경 */
    public void onParkBrakeSwitchChanged(int state) {}
    /** EPB(전자식 파킹 브레이크) 상태 변경 */
    public void onEPBStateChanged(int state) {}
}
