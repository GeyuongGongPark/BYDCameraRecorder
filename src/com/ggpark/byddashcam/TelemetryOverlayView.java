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

    private volatile VehicleTelemetry telemetry = VehicleTelemetry.UNAVAILABLE;
    private volatile GpsFix gpsFix = GpsFix.UNAVAILABLE;
    private boolean enabled = true;
    private boolean useKmh = true;

    public TelemetryOverlayView(Context context) {
        super(context);
        setWillNotDraw(false);

        float density = context.getResources().getDisplayMetrics().density;
        float speedSp = SPEED_TEXT_SIZE_DP * density;
        float infoSp = INFO_TEXT_SIZE_DP * density;
        float gearSp = GEAR_TEXT_SIZE_DP * density;

        speedPaint.setColor(Color.WHITE);
        speedPaint.setTypeface(Typeface.MONOSPACE);
        speedPaint.setTextSize(speedSp);
        speedPaint.setFakeBoldText(true);

        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setTypeface(Typeface.MONOSPACE);
        shadowPaint.setTextSize(speedSp);
        shadowPaint.setFakeBoldText(true);

        infoPaint.setColor(Color.WHITE);
        infoPaint.setTypeface(Typeface.MONOSPACE);
        infoPaint.setTextSize(infoSp);

        gearActivePaint.setColor(Color.WHITE);
        gearActivePaint.setTypeface(Typeface.MONOSPACE);
        gearActivePaint.setTextSize(gearSp);
        gearActivePaint.setFakeBoldText(true);

        gearInactivePaint.setColor(Color.argb(120, 180, 180, 180));
        gearInactivePaint.setTypeface(Typeface.MONOSPACE);
        gearInactivePaint.setTextSize(gearSp);

        turnActivePaint.setColor(Color.rgb(255, 200, 0));
        turnActivePaint.setTypeface(Typeface.MONOSPACE);
        turnActivePaint.setTextSize(gearSp);
        turnActivePaint.setFakeBoldText(true);

        turnInactivePaint.setColor(Color.argb(80, 180, 140, 0));
        turnInactivePaint.setTypeface(Typeface.MONOSPACE);
        turnInactivePaint.setTextSize(gearSp);

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

        float density = getResources().getDisplayMetrics().density;
        float overlayWidth = OVERLAY_WIDTH_DP * density;
        float overlayPadding = OVERLAY_PADDING_DP * density;
        float speedTextSize = SPEED_TEXT_SIZE_DP * density;
        float infoTextSize = INFO_TEXT_SIZE_DP * density;
        float gearTextSize = GEAR_TEXT_SIZE_DP * density;

        VehicleTelemetry t = telemetry;
        GpsFix fix = gpsFix;
        boolean hasGps = fix != null && fix.isAvailable();
        boolean hasTelemetry = t != null && t.isAvailable();

        float overlayBaseHeight = speedTextSize + infoTextSize + overlayPadding * 2;
        float overlayExtHeight = hasTelemetry
                ? overlayBaseHeight + gearTextSize * 3 + overlayPadding * 2
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
        String speedText = speedInt < 0
                ? String.format(Locale.US, "--- %s", unit)
                : String.format(Locale.US, "%3d %s", speedInt, unit);

        float textX = offsetX + overlayPadding;
        float textY = offsetY + speedTextSize + overlayPadding;

        canvas.drawText(speedText, textX + density, textY + density, shadowPaint);
        canvas.drawText(speedText, textX, textY, speedPaint);

        // GPS stale 표시
        if (hasGps && !fix.fresh) {
            infoPaint.setColor(Color.YELLOW);
            canvas.drawText("GPS?",
                    offsetX + overlayWidth - overlayPadding - infoPaint.measureText("GPS?"),
                    textY, infoPaint);
            infoPaint.setColor(Color.WHITE);
        }

        if (!hasTelemetry) return;

        // 기어 행
        float gearRowY = offsetY + overlayBaseHeight + gearTextSize;
        String[] gears = {"P", "R", "N", "D"};
        int[] gearBits = {0x01, 0x02, 0x04, 0x08};
        float gearX = offsetX + overlayPadding;
        for (int i = 0; i < gears.length; i++) {
            boolean active = (t.gearBlinkBeltFlags & gearBits[i]) != 0;
            canvas.drawText("[" + gears[i] + "]", gearX, gearRowY,
                    active ? gearActivePaint : gearInactivePaint);
            gearX += gearTextSize * 3.2f;
        }

        // 기어 매핑 디버그: raw API 값 (매핑 미지원 시 ?:X 형태)
        if (!t.isGearKnown() && t.rawGear != Integer.MIN_VALUE) {
            canvas.drawText("?:" + t.rawGear, gearX, gearRowY, turnActivePaint);
        }

        // 방향지시등 행
        float turnRowY = gearRowY + gearTextSize + overlayPadding / 2;
        boolean leftActive = t.isTurnLeftActive();
        boolean rightActive = t.isTurnRightActive();

        canvas.drawText("<<",
                offsetX + overlayPadding,
                turnRowY,
                leftActive && blinkOn ? turnActivePaint : turnInactivePaint);

        canvas.drawText(">>",
                offsetX + overlayWidth - overlayPadding - gearTextSize * 2.5f,
                turnRowY,
                rightActive && blinkOn ? turnActivePaint : turnInactivePaint);

        // 액셀/브레이크/전조등 행
        float pedalRowY = turnRowY + gearTextSize + overlayPadding / 2;
        String pedalText = String.format(Locale.US,
                "A:%2d%% B:%2d%%", t.acceleratorPercent, t.brakePercent);
        canvas.drawText(pedalText, offsetX + overlayPadding, pedalRowY, infoPaint);

        String lightText = buildLightText(t.lightFlags);
        if (!lightText.isEmpty()) {
            float lightX = offsetX + overlayWidth - overlayPadding
                    - infoPaint.measureText(lightText);
            canvas.drawText(lightText, lightX, pedalRowY, turnActivePaint);
        }
    }

    private static String buildLightText(int flags) {
        if ((flags & 0x04) != 0) return "[HB]";
        if ((flags & 0x02) != 0) return "[HL]";
        if ((flags & 0x08) != 0) return "[FG]";
        if ((flags & 0x01) != 0) return "[PL]";
        return "";
    }
}
