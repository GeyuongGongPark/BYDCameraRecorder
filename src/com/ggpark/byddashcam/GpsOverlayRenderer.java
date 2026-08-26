package com.ggpark.byddashcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.Locale;

/**
 * GPS 정보 및 차량 텔레메트리를 NV21 프레임에 직접 합성하는 렌더러.
 *
 * <p>전략: NV21 전체를 Bitmap으로 변환하지 않고,
 * 오버레이 영역의 Bitmap만 유지합니다.
 * 속도/기어/방향지시등 변화 시에만 Bitmap을 재렌더링하고,
 * 매 프레임에는 Bitmap Y값을 NV21 Y채널에 알파 블렌딩합니다.
 *
 * <p>높이: GPS만 표시 시 56px, 텔레메트리 포함 시 90px. Bitmap은 항상 최대 크기로 생성.
 */
public final class GpsOverlayRenderer {
    private static final int OVERLAY_WIDTH = 220;
    private static final int OVERLAY_HEIGHT_BASE = 56;
    private static final int OVERLAY_HEIGHT_EXTENDED = 112;
    private static final int OVERLAY_PADDING = 8;
    private static final int SPEED_TEXT_SIZE = 32;
    private static final int INFO_TEXT_SIZE = 16;
    private static final int GEAR_TEXT_SIZE = 14;
    /** 오버레이 배경의 NV21 Y값 (반투명 검정) */
    private static final int BG_ALPHA = 140;

    private final Paint speedPaint;
    private final Paint infoPaint;
    private final Paint shadowPaint;
    private final Paint gearActivePaint;
    private final Paint gearInactivePaint;
    private final Paint turnActivePaint;
    private final Paint turnInactivePaint;

    private final Bitmap overlayBitmap;
    private final Canvas overlayCanvas;

    private boolean enabled;
    private boolean useKmh;
    private boolean showCoordinates;

    /** 최신 차량 텔레메트리 */
    private volatile VehicleTelemetry latestTelemetry = VehicleTelemetry.UNAVAILABLE;

    /** 캐싱: 변화 없으면 재렌더링 스킵 */
    private int cachedSpeedInt = Integer.MIN_VALUE;
    private double cachedLat = Double.NaN;
    private double cachedLon = Double.NaN;
    private int cachedGearBlinkFlags = Integer.MIN_VALUE;
    private int cachedLightFlags = Integer.MIN_VALUE;
    private int cachedAccelerator = Integer.MIN_VALUE;
    private int cachedBrake = Integer.MIN_VALUE;

    public GpsOverlayRenderer(boolean enabled, boolean useKmh, boolean showCoordinates) {
        this.enabled = enabled;
        this.useKmh = useKmh;
        this.showCoordinates = showCoordinates;

        speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        speedPaint.setColor(Color.WHITE);
        speedPaint.setTypeface(Typeface.MONOSPACE);
        speedPaint.setTextSize(SPEED_TEXT_SIZE);
        speedPaint.setFakeBoldText(true);

        infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        infoPaint.setColor(Color.WHITE);
        infoPaint.setTypeface(Typeface.MONOSPACE);
        infoPaint.setTextSize(INFO_TEXT_SIZE);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setTypeface(Typeface.MONOSPACE);
        shadowPaint.setTextSize(SPEED_TEXT_SIZE);
        shadowPaint.setFakeBoldText(true);

        gearActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gearActivePaint.setColor(Color.WHITE);
        gearActivePaint.setTypeface(Typeface.MONOSPACE);
        gearActivePaint.setTextSize(GEAR_TEXT_SIZE);
        gearActivePaint.setFakeBoldText(true);

        gearInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gearInactivePaint.setColor(Color.argb(120, 180, 180, 180));
        gearInactivePaint.setTypeface(Typeface.MONOSPACE);
        gearInactivePaint.setTextSize(GEAR_TEXT_SIZE);

        turnActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        turnActivePaint.setColor(Color.rgb(255, 200, 0));
        turnActivePaint.setTypeface(Typeface.MONOSPACE);
        turnActivePaint.setTextSize(GEAR_TEXT_SIZE);
        turnActivePaint.setFakeBoldText(true);

        turnInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        turnInactivePaint.setColor(Color.argb(80, 180, 140, 0));
        turnInactivePaint.setTypeface(Typeface.MONOSPACE);
        turnInactivePaint.setTextSize(GEAR_TEXT_SIZE);

        overlayBitmap = Bitmap.createBitmap(
                OVERLAY_WIDTH, OVERLAY_HEIGHT_EXTENDED, Bitmap.Config.ARGB_8888);
        overlayCanvas = new Canvas(overlayBitmap);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setUseKmh(boolean useKmh) {
        if (this.useKmh != useKmh) {
            this.useKmh = useKmh;
            invalidateCache();
        }
    }

    public void setShowCoordinates(boolean showCoordinates) {
        if (this.showCoordinates != showCoordinates) {
            this.showCoordinates = showCoordinates;
            invalidateCache();
        }
    }

    public void updateTelemetry(VehicleTelemetry telemetry) {
        latestTelemetry = telemetry != null ? telemetry : VehicleTelemetry.UNAVAILABLE;
        invalidateCache();
    }

    /**
     * 필요 시 overlayBitmap을 재렌더링하고 activeHeight를 반환합니다.
     * applyToNv21/applyToBitmap 공용.
     */
    private int prepareOverlay(GpsFix fix) {
        VehicleTelemetry telemetry = latestTelemetry;
        boolean hasGps = fix != null && fix.isAvailable();
        boolean hasTelemetry = telemetry.isAvailable();

        int activeHeight = hasTelemetry ? OVERLAY_HEIGHT_EXTENDED : OVERLAY_HEIGHT_BASE;

        double rawSpeedKmh = hasGps ? fix.speedKmh : (hasTelemetry ? telemetry.speedKmh : -1);
        int speedInt = rawSpeedKmh < 0 ? -1
                : (int) (useKmh ? rawSpeedKmh : rawSpeedKmh * 0.621371);
        double lat = hasGps ? fix.latitude : 0.0;
        double lon = hasGps ? fix.longitude : 0.0;
        boolean gpsFresh = hasGps && fix.fresh;
        boolean showCoords = showCoordinates && hasGps;
        int gearBlinkFlags = hasTelemetry ? telemetry.gearBlinkBeltFlags : 0;
        int lightFlagsVal = hasTelemetry ? telemetry.lightFlags : 0;
        int accelerator = hasTelemetry ? telemetry.acceleratorPercent : 0;
        int brake = hasTelemetry ? telemetry.brakePercent : 0;

        boolean needsRender = speedInt != cachedSpeedInt
                || gearBlinkFlags != cachedGearBlinkFlags
                || lightFlagsVal != cachedLightFlags
                || accelerator != cachedAccelerator
                || brake != cachedBrake
                || (showCoords
                        && (Math.abs(lat - cachedLat) > 0.0001
                                || Math.abs(lon - cachedLon) > 0.0001));
        if (needsRender) {
            cachedSpeedInt = speedInt;
            cachedLat = lat;
            cachedLon = lon;
            cachedGearBlinkFlags = gearBlinkFlags;
            cachedLightFlags = lightFlagsVal;
            cachedAccelerator = accelerator;
            cachedBrake = brake;
            renderOverlay(speedInt, lat, lon, gpsFresh, showCoords, telemetry, activeHeight);
        }
        return activeHeight;
    }

    /**
     * GPS 오버레이를 NV21 프레임 우하단에 합성합니다.
     *
     * @param nv21   NV21 바이트 배열 (수정됨)
     * @param width  프레임 너비
     * @param height 프레임 높이
     * @param fix    현재 GPS fix (null이면 스킵)
     */
    public void applyToNv21(byte[] nv21, int width, int height, GpsFix fix) {
        if (!enabled) {
            return;
        }
        int activeHeight = prepareOverlay(fix);
        int offsetX = width - OVERLAY_WIDTH - OVERLAY_PADDING;
        int offsetY = height - activeHeight - OVERLAY_PADDING;
        if (offsetX < 0 || offsetY < 0) {
            return;
        }
        blendToNv21(nv21, width, height, offsetX, offsetY, activeHeight);
    }

    /**
     * GPS 오버레이를 Bitmap 우하단에 합성합니다. 프리뷰 표시에 사용.
     *
     * @param bitmap 수정할 Bitmap (mutable이어야 함)
     * @param fix    현재 GPS fix
     */
    public void applyToBitmap(Bitmap bitmap, GpsFix fix) {
        if (!enabled) {
            return;
        }
        int activeHeight = prepareOverlay(fix);
        int offsetX = bitmap.getWidth() - OVERLAY_WIDTH - OVERLAY_PADDING;
        int offsetY = bitmap.getHeight() - activeHeight - OVERLAY_PADDING;
        if (offsetX < 0 || offsetY < 0) {
            return;
        }
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(overlayBitmap, offsetX, offsetY, null);
    }

    private void renderOverlay(
            int speedInt,
            double lat,
            double lon,
            boolean gpsFresh,
            boolean showCoords,
            VehicleTelemetry telemetry,
            int activeHeight) {
        overlayBitmap.eraseColor(Color.TRANSPARENT);

        // 반투명 배경 (검정)
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.argb(BG_ALPHA, 0, 0, 0));
        overlayCanvas.drawRect(0, 0, OVERLAY_WIDTH, activeHeight, bgPaint);

        // 속도 텍스트 (그림자 + 흰색)
        String unit = useKmh ? "km/h" : "mph";
        String speedText = speedInt < 0
                ? String.format(Locale.US, "--- %s", unit)
                : String.format(Locale.US, "%3d %s", speedInt, unit);

        shadowPaint.setTextSize(SPEED_TEXT_SIZE);
        overlayCanvas.drawText(speedText, OVERLAY_PADDING + 1, SPEED_TEXT_SIZE + 1, shadowPaint);
        overlayCanvas.drawText(speedText, OVERLAY_PADDING, SPEED_TEXT_SIZE, speedPaint);

        // GPS 신호 없음 표시 (GPS가 있으나 stale인 경우)
        if (!gpsFresh) {
            infoPaint.setColor(Color.YELLOW);
            overlayCanvas.drawText("GPS?",
                    OVERLAY_WIDTH - 50, SPEED_TEXT_SIZE, infoPaint);
            infoPaint.setColor(Color.WHITE);
        }

        // 좌표 표시 (GPS가 있을 때만)
        if (showCoords) {
            String coordText = String.format(Locale.US,
                    "%.4f, %.4f", lat, lon);
            overlayCanvas.drawText(coordText,
                    OVERLAY_PADDING, SPEED_TEXT_SIZE + INFO_TEXT_SIZE + 2, infoPaint);
        }

        // 텔레메트리 오버레이 (기어 + 방향지시등)
        if (telemetry.isAvailable()) {
            renderTelemetryRow(telemetry);
        }
    }

    private void renderTelemetryRow(VehicleTelemetry telemetry) {
        // 기어 행: [P] [R] [N] [D] - 현재 기어 하이라이트
        int gearRowY = OVERLAY_HEIGHT_BASE + GEAR_TEXT_SIZE + 2;
        String[] gears = {"P", "R", "N", "D"};
        int[] gearBits = {0x01, 0x02, 0x04, 0x08};
        float gearX = OVERLAY_PADDING;
        for (int i = 0; i < gears.length; i++) {
            boolean active = (telemetry.gearBlinkBeltFlags & gearBits[i]) != 0;
            String label = "[" + gears[i] + "]";
            overlayCanvas.drawText(
                    label,
                    gearX,
                    gearRowY,
                    active ? gearActivePaint : gearInactivePaint);
            gearX += GEAR_TEXT_SIZE * 3.2f;
        }

        // 방향지시등 행: << (좌) ... >> (우)
        int turnRowY = OVERLAY_HEIGHT_BASE + GEAR_TEXT_SIZE * 2 + 6;
        boolean leftActive = telemetry.isTurnLeftActive();
        boolean rightActive = telemetry.isTurnRightActive();

        overlayCanvas.drawText(
                "<<",
                OVERLAY_PADDING,
                turnRowY,
                leftActive ? turnActivePaint : turnInactivePaint);

        overlayCanvas.drawText(
                ">>",
                OVERLAY_WIDTH - OVERLAY_PADDING - GEAR_TEXT_SIZE * 2.5f,
                turnRowY,
                rightActive ? turnActivePaint : turnInactivePaint);

        // 액셀/브레이크/전조등 행
        int pedalsRowY = OVERLAY_HEIGHT_BASE + GEAR_TEXT_SIZE * 3 + 10;
        String pedalText = String.format(Locale.US,
                "A:%2d%% B:%2d%%", telemetry.acceleratorPercent, telemetry.brakePercent);
        overlayCanvas.drawText(pedalText, OVERLAY_PADDING, pedalsRowY, infoPaint);

        String lightText = buildLightText(telemetry.lightFlags);
        if (!lightText.isEmpty()) {
            float lightX = OVERLAY_WIDTH - OVERLAY_PADDING
                    - infoPaint.measureText(lightText);
            overlayCanvas.drawText(lightText, lightX, pedalsRowY, turnActivePaint);
        }
    }

    /** lightFlags 비트를 가장 우선순위 높은 전조등 상태 텍스트로 변환. */
    private static String buildLightText(int flags) {
        if ((flags & 0x04) != 0) return "[HB]";  // 상향등
        if ((flags & 0x02) != 0) return "[HL]";  // 하향등
        if ((flags & 0x08) != 0) return "[FG]";  // 안개등
        if ((flags & 0x01) != 0) return "[PL]";  // 위치등
        return "";
    }

    /**
     * overlayBitmap의 픽셀을 NV21 Y채널에 알파 블렌딩합니다.
     * UV채널은 반투명 배경 영역에서만 중립값(128)으로 리셋합니다.
     */
    private void blendToNv21(
            byte[] nv21,
            int width,
            int height,
            int offsetX,
            int offsetY,
            int activeHeight) {
        int ySize = width * height;
        int[] pixels = new int[OVERLAY_WIDTH * activeHeight];
        overlayBitmap.getPixels(pixels, 0, OVERLAY_WIDTH, 0, 0, OVERLAY_WIDTH, activeHeight);

        for (int row = 0; row < activeHeight && (offsetY + row) < height; row++) {
            int nv21Row = offsetY + row;
            for (int col = 0; col < OVERLAY_WIDTH && (offsetX + col) < width; col++) {
                int pixel = pixels[row * OVERLAY_WIDTH + col];
                int alpha = (pixel >> 24) & 0xff;
                if (alpha == 0) {
                    continue;
                }
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                // RGB → Y (BT.601)
                int srcY = (66 * r + 129 * g + 25 * b + 128) / 256 + 16;
                srcY = Math.max(16, Math.min(235, srcY));

                int nv21Col = offsetX + col;
                int yIndex = nv21Row * width + nv21Col;
                int dstY = nv21[yIndex] & 0xff;
                // 알파 블렌딩
                nv21[yIndex] = (byte) ((srcY * alpha + dstY * (255 - alpha)) / 255);
            }
        }

        // UV 채널: 배경 영역을 중립(128,128)으로 리셋하여 탈색 방지
        for (int row = 0; row < activeHeight / 2; row++) {
            int uvRow = (offsetY / 2) + row;
            if (uvRow * 2 + 1 >= height) {
                break;
            }
            for (int col = 0; col < OVERLAY_WIDTH; col += 2) {
                int uvCol = offsetX + col;
                if (uvCol + 1 >= width) {
                    break;
                }
                int pixel = pixels[row * 2 * OVERLAY_WIDTH + col];
                int alpha = (pixel >> 24) & 0xff;
                if (alpha < 64) {
                    continue;
                }
                int uvIndex = ySize + uvRow * width + uvCol;
                if (uvIndex + 1 < nv21.length) {
                    nv21[uvIndex] = (byte) 128;
                    nv21[uvIndex + 1] = (byte) 128;
                }
            }
        }
    }

    private void invalidateCache() {
        cachedSpeedInt = Integer.MIN_VALUE;
        cachedLat = Double.NaN;
        cachedLon = Double.NaN;
        cachedGearBlinkFlags = Integer.MIN_VALUE;
        cachedLightFlags = Integer.MIN_VALUE;
        cachedAccelerator = Integer.MIN_VALUE;
        cachedBrake = Integer.MIN_VALUE;
    }
}
