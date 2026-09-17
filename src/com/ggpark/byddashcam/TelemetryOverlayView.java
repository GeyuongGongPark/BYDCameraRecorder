package com.ggpark.byddashcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Locale;

/**
 * 차량 화면 native rendering 경로에서 텔레메트리/GPS 정보를 표시하는 오버레이 View.
 * GpsOverlayRenderer와 동일한 시각적 결과를 목표로 합니다.
 *
 * 배치: FrameLayout에 MATCH_PARENT로 추가하면 우하단에 자동 배치됩니다.
 */
public final class TelemetryOverlayView extends View {
    private static final int OVERLAY_WIDTH_DP = 220;
    private static final int OVERLAY_PADDING_DP = 8;
    private static final int SPEED_TEXT_SIZE_DP = 32;
    private static final int INFO_TEXT_SIZE_DP = 16;
    private static final int GEAR_TEXT_SIZE_DP = 14;
    private static final int BG_ALPHA = 140;
    private static final long BLINK_INTERVAL_MS = 500L;
    private static final String[] GEAR_TEXTS = {"[P]", "[R]", "[N]", "[D]"};
    private static final int[] GEAR_BITS = {0x01, 0x02, 0x04, 0x08};

    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gearActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gearInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint turnActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint turnInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean blinkOn = true;
    private final Runnable blinkRunnable = new Runnable() {
        @Override
        public void run() {
            blinkOn = !blinkOn;
            invalidate();
            handler.postDelayed(this, BLINK_INTERVAL_MS);
        }
    };

    // density 기반 크기 — 생성자에서 한 번만 계산
    private float density;
    private float overlayWidth;
    private float overlayPadding;
    private float speedTextSize;
    private float infoTextSize;
    private float gearTextSize;

    // 문자열 재사용
    private final StringBuilder sb = new StringBuilder(32);

    // GPS stale 전용 Paint (infoPaint 색 임시 변경 방지)
    private final Paint gpsStalePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile VehicleTelemetry telemetry = VehicleTelemetry.UNAVAILABLE;
    private volatile GpsFix gpsFix = GpsFix.UNAVAILABLE;
    private boolean enabled = true;
    private boolean useKmh = true;

    public TelemetryOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        density = context.getResources().getDisplayMetrics().density;
        overlayWidth  = OVERLAY_WIDTH_DP   * density;
        overlayPadding = OVERLAY_PADDING_DP * density;
        speedTextSize  = SPEED_TEXT_SIZE_DP * density;
        infoTextSize   = INFO_TEXT_SIZE_DP  * density;
        gearTextSize   = GEAR_TEXT_SIZE_DP  * density;

        speedPaint.setColor(Color.WHITE);
        speedPaint.setTypeface(Typeface.MONOSPACE);
        speedPaint.setTextSize(speedTextSize);
        speedPaint.setFakeBoldText(true);

        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setTypeface(Typeface.MONOSPACE);
        shadowPaint.setTextSize(speedTextSize);
        shadowPaint.setFakeBoldText(true);

        infoPaint.setColor(Color.WHITE);
        infoPaint.setTypeface(Typeface.MONOSPACE);
        infoPaint.setTextSize(infoTextSize);

        gpsStalePaint.setColor(Color.YELLOW);
        gpsStalePaint.setTypeface(Typeface.MONOSPACE);
        gpsStalePaint.setTextSize(infoTextSize);

        gearActivePaint.setColor(Color.WHITE);
        gearActivePaint.setTypeface(Typeface.MONOSPACE);
        gearActivePaint.setTextSize(gearTextSize);
        gearActivePaint.setFakeBoldText(true);

        gearInactivePaint.setColor(Color.argb(120, 180, 180, 180));
        gearInactivePaint.setTypeface(Typeface.MONOSPACE);
        gearInactivePaint.setTextSize(gearTextSize);

        turnActivePaint.setColor(Color.rgb(255, 200, 0));
        turnActivePaint.setTypeface(Typeface.MONOSPACE);
        turnActivePaint.setTextSize(gearTextSize);
        turnActivePaint.setFakeBoldText(true);

        turnInactivePaint.setColor(Color.argb(80, 180, 140, 0));
        turnInactivePaint.setTypeface(Typeface.MONOSPACE);
        turnInactivePaint.setTextSize(gearTextSize);

        bgPaint.setColor(Color.argb(BG_ALPHA, 0, 0, 0));
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        invalidate();
    }

    public void setUseKmh(boolean useKmh) {
        this.useKmh = useKmh;
        invalidate();
    }

    public void updateTelemetry(VehicleTelemetry telemetry) {
        this.telemetry = telemetry != null ? telemetry : VehicleTelemetry.UNAVAILABLE;
        postInvalidate();
    }

    public void updateGpsFix(GpsFix fix) {
        this.gpsFix = fix != null ? fix : GpsFix.UNAVAILABLE;
        postInvalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(blinkRunnable, BLINK_INTERVAL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(blinkRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!enabled) return;

        VehicleTelemetry t = telemetry;
        GpsFix fix = gpsFix;
        boolean hasGps = fix != null && fix.isAvailable();
        boolean hasTelemetry = t != null && t.isAvailable();

        boolean hasBattery = hasTelemetry && t.batteryPercent >= 0;
        boolean hasEnergyMode = hasTelemetry && t.energyMode >= 0;
        int extraRows = 0;
        if (hasTelemetry) extraRows += 3; // 기어, 방향지시등, 페달/조명
        if (hasBattery || hasEnergyMode) extraRows += 1;

        float overlayBaseHeight = speedTextSize + infoTextSize + overlayPadding * 2;
        float overlayExtHeight = hasTelemetry
                ? overlayBaseHeight + gearTextSize * extraRows + overlayPadding * 2
                : overlayBaseHeight;

        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float offsetX = viewWidth - overlayWidth - overlayPadding;
        float offsetY = viewHeight - overlayExtHeight - overlayPadding;

        if (offsetX < 0 || offsetY < 0) return;

        // 배경
        canvas.drawRect(offsetX, offsetY,
                offsetX + overlayWidth, offsetY + overlayExtHeight, bgPaint);

        // 속도
        double rawSpeedKmh;
        if (hasGps && fix.speedKmh > 0) {
            rawSpeedKmh = fix.speedKmh;
        } else if (hasTelemetry && t.speedKmh > 0) {
            rawSpeedKmh = t.speedKmh;
        } else if (hasGps) {
            rawSpeedKmh = fix.speedKmh;
        } else {
            rawSpeedKmh = -1;
        }
        int speedInt = rawSpeedKmh < 0 ? -1
                : (int) (useKmh ? rawSpeedKmh : rawSpeedKmh * 0.621371);
        String unit = useKmh ? "km/h" : "mph";
        sb.setLength(0);
        if (speedInt < 0) {
            sb.append("--- ").append(unit);
        } else {
            if (speedInt < 100) sb.append(' ');
            if (speedInt < 10) sb.append(' ');
            sb.append(speedInt).append(' ').append(unit);
        }
        String speedText = sb.toString();

        float textX = offsetX + overlayPadding;
        float textY = offsetY + speedTextSize + overlayPadding;

        canvas.drawText(speedText, textX + density, textY + density, shadowPaint);
        canvas.drawText(speedText, textX, textY, speedPaint);

        // GPS stale 표시
        if (hasGps && !fix.fresh) {
            canvas.drawText("GPS?",
                    offsetX + overlayWidth - overlayPadding - gpsStalePaint.measureText("GPS?"),
                    textY, gpsStalePaint);
        }

        if (!hasTelemetry) return;

        // 기어 행
        float gearRowY = offsetY + overlayBaseHeight + gearTextSize;
        float gearX = offsetX + overlayPadding;
        for (int i = 0; i < GEAR_TEXTS.length; i++) {
            boolean active = (t.gearBlinkBeltFlags & GEAR_BITS[i]) != 0;
            canvas.drawText(GEAR_TEXTS[i], gearX, gearRowY,
                    active ? gearActivePaint : gearInactivePaint);
            gearX += gearTextSize * 3.2f;
        }

        // 기어 매핑 디버그: raw API 값 (매핑 미지원 시 ?:X 형태)
        if (!t.isGearKnown() && t.rawGear != Integer.MIN_VALUE) {
            canvas.drawText("?:" + t.rawGear, gearX, gearRowY, turnActivePaint);
        }

        // 방향지시등 행
        float turnRowY = gearRowY + gearTextSize + overlayPadding / 2;
        canvas.drawText("<<",
                offsetX + overlayPadding,
                turnRowY,
                t.isTurnLeftActive() && blinkOn ? turnActivePaint : turnInactivePaint);
        canvas.drawText(">>",
                offsetX + overlayWidth - overlayPadding - gearTextSize * 2.5f,
                turnRowY,
                t.isTurnRightActive() && blinkOn ? turnActivePaint : turnInactivePaint);

        // 액셀/브레이크/전조등 행
        float pedalRowY = turnRowY + gearTextSize + overlayPadding / 2;
        sb.setLength(0);
        sb.append("A:");
        if (t.acceleratorPercent < 10) sb.append(' ');
        sb.append(t.acceleratorPercent).append("% B:");
        if (t.brakePercent < 10) sb.append(' ');
        sb.append(t.brakePercent).append('%');
        canvas.drawText(sb.toString(), offsetX + overlayPadding, pedalRowY, infoPaint);

        String lightText = buildLightText(t.lightFlags);
        if (!lightText.isEmpty()) {
            canvas.drawText(lightText,
                    offsetX + overlayWidth - overlayPadding - infoPaint.measureText(lightText),
                    pedalRowY, turnActivePaint);
        }

        // 배터리 잔량 + 주행 가능 거리 / 에너지·드라이브 모드 행
        if (hasBattery || hasEnergyMode) {
            float infoRowY = pedalRowY + gearTextSize + overlayPadding / 2;
            if (hasBattery) {
                sb.setLength(0);
                sb.append("BAT:");
                if (t.batteryPercent < 100) sb.append(' ');
                if (t.batteryPercent < 10) sb.append(' ');
                sb.append(t.batteryPercent).append('%');
                if (t.drivingRangeKm >= 0) {
                    sb.append(' ').append(t.drivingRangeKm).append("km");
                }
                canvas.drawText(sb.toString(), offsetX + overlayPadding, infoRowY, infoPaint);
            }
            if (hasEnergyMode) {
                String modeText = buildEnergyModeText(t.energyMode, t.operationMode);
                if (!modeText.isEmpty()) {
                    canvas.drawText(modeText,
                            offsetX + overlayWidth - overlayPadding
                                    - infoPaint.measureText(modeText),
                            infoRowY, turnActivePaint);
                }
            }
        }
    }

    private static String buildLightText(int flags) {
        if ((flags & 0x04) != 0) return "[HB]";
        if ((flags & 0x02) != 0) return "[HL]";
        if ((flags & 0x08) != 0) return "[FG]";
        if ((flags & 0x01) != 0) return "[PL]";
        return "";
    }

    private static String buildEnergyModeText(int energyMode, int operationMode) {
        String energy;
        switch (energyMode) {
            case 1: energy = "EV"; break;
            case 2: energy = "EV!"; break;
            case 3: energy = "HEV"; break;
            case 4: energy = "FUEL"; break;
            case 5: energy = "KEEP"; break;
            default: energy = "";
        }
        String op = operationMode == 1 ? "ECO" : operationMode == 2 ? "SPT" : "";
        if (energy.isEmpty() && op.isEmpty()) return "";
        if (energy.isEmpty()) return op;
        if (op.isEmpty()) return energy;
        return energy + " " + op;
    }
}
