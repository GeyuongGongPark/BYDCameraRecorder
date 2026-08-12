package com.ggpark.byddashcam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.Locale;

/**
 * GPS 정보를 NV21 프레임에 직접 합성하는 렌더러.
 *
 * <p>전략: NV21 전체를 Bitmap으로 변환하지 않고,
 * 오버레이 영역(~200x56px)의 Bitmap만 유지합니다.
 * 속도 변화 시에만 Bitmap을 재렌더링하고,
 * 매 프레임에는 Bitmap Y값을 NV21 Y채널에 알파 블렌딩합니다.
 */
public final class GpsOverlayRenderer {
    private static final int OVERLAY_WIDTH = 220;
    private static final int OVERLAY_HEIGHT = 56;
    private static final int OVERLAY_PADDING = 8;
    private static final int SPEED_TEXT_SIZE = 32;
    private static final int INFO_TEXT_SIZE = 16;
    /** 오버레이 배경의 NV21 Y값 (반투명 검정) */
    private static final int BG_ALPHA = 140;

    private final Paint speedPaint;
    private final Paint infoPaint;
    private final Paint shadowPaint;

    private Bitmap overlayBitmap;
    private Canvas overlayCanvas;

    private boolean enabled;
    private boolean useKmh;
    private boolean showCoordinates;

    /** 캐싱: 변화 없으면 재렌더링 스킵 */
    private int cachedSpeedInt = Integer.MIN_VALUE;
    private double cachedLat = Double.NaN;
    private double cachedLon = Double.NaN;

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

        overlayBitmap = Bitmap.createBitmap(
                OVERLAY_WIDTH, OVERLAY_HEIGHT, Bitmap.Config.ARGB_8888);
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

    /**
     * GPS 오버레이를 NV21 프레임 우하단에 합성합니다.
     *
     * @param nv21   NV21 바이트 배열 (수정됨)
     * @param width  프레임 너비
     * @param height 프레임 높이
     * @param fix    현재 GPS fix (null이면 스킵)
     */
    public void applyToNv21(byte[] nv21, int width, int height, GpsFix fix) {
        if (!enabled || fix == null || !fix.isAvailable()) {
            return;
        }

        double displaySpeed = useKmh ? fix.speedKmh : fix.speedKmh * 0.621371;
        int speedInt = (int) displaySpeed;
        double lat = fix.latitude;
        double lon = fix.longitude;

        // 캐싱: 속도나 좌표가 바뀔 때만 Bitmap 재렌더링
        boolean needsRender = speedInt != cachedSpeedInt
                || (showCoordinates
                        && (Math.abs(lat - cachedLat) > 0.0001
                                || Math.abs(lon - cachedLon) > 0.0001));
        if (needsRender) {
            cachedSpeedInt = speedInt;
            cachedLat = lat;
            cachedLon = lon;
            renderOverlay(speedInt, lat, lon, fix.fresh);
        }

        // NV21 프레임에 합성: 우하단 배치
        int offsetX = width - OVERLAY_WIDTH - OVERLAY_PADDING;
        int offsetY = height - OVERLAY_HEIGHT - OVERLAY_PADDING;
        if (offsetX < 0 || offsetY < 0) {
            return;
        }
        blendToNv21(nv21, width, height, offsetX, offsetY);
    }

    private void renderOverlay(int speedInt, double lat, double lon, boolean fresh) {
        overlayBitmap.eraseColor(Color.TRANSPARENT);

        // 반투명 배경 (검정)
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.argb(BG_ALPHA, 0, 0, 0));
        overlayCanvas.drawRect(0, 0, OVERLAY_WIDTH, OVERLAY_HEIGHT, bgPaint);

        // 속도 텍스트 (그림자 + 흰색)
        String unit = useKmh ? "km/h" : "mph";
        String speedText = String.format(Locale.US, "%3d %s", speedInt, unit);

        shadowPaint.setTextSize(SPEED_TEXT_SIZE);
        overlayCanvas.drawText(speedText, OVERLAY_PADDING + 1, SPEED_TEXT_SIZE + 1, shadowPaint);
        overlayCanvas.drawText(speedText, OVERLAY_PADDING, SPEED_TEXT_SIZE, speedPaint);

        // GPS 신호 없음 표시
        if (!fresh) {
            infoPaint.setColor(Color.YELLOW);
            overlayCanvas.drawText("GPS?",
                    OVERLAY_WIDTH - 50, SPEED_TEXT_SIZE, infoPaint);
            infoPaint.setColor(Color.WHITE);
        }

        // 좌표 표시 (선택적)
        if (showCoordinates) {
            String coordText = String.format(Locale.US,
                    "%.4f, %.4f", lat, lon);
            overlayCanvas.drawText(coordText,
                    OVERLAY_PADDING, SPEED_TEXT_SIZE + INFO_TEXT_SIZE + 2, infoPaint);
        }
    }

    /**
     * overlayBitmap의 픽셀을 NV21 Y채널에 알파 블렌딩합니다.
     * UV채널은 반투명 배경 영역에서만 중립값(128)으로 리셋합니다.
     */
    private void blendToNv21(byte[] nv21, int width, int height, int offsetX, int offsetY) {
        int ySize = width * height;
        int[] pixels = new int[OVERLAY_WIDTH * OVERLAY_HEIGHT];
        overlayBitmap.getPixels(pixels, 0, OVERLAY_WIDTH, 0, 0, OVERLAY_WIDTH, OVERLAY_HEIGHT);

        for (int row = 0; row < OVERLAY_HEIGHT && (offsetY + row) < height; row++) {
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
        for (int row = 0; row < OVERLAY_HEIGHT / 2; row++) {
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
    }
}
