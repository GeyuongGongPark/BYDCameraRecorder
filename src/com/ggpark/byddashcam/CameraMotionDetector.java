package com.ggpark.byddashcam;

/**
 * 연속 카메라 프레임 간 픽셀 차이를 이용한 모션 감지기.
 *
 * NV21(YUV420sp) 포맷에서 Y(밝기) 채널만 사용합니다.
 * 16×16 블록으로 다운샘플링하여 CPU 부하를 최소화합니다.
 *
 * sensitivity 1~5:
 *   1 = 가장 민감 (작은 움직임도 감지)
 *   5 = 가장 둔감 (큰 움직임만 감지)
 */
public final class CameraMotionDetector {

    // 다운샘플링 블록 크기 (픽셀)
    private static final int BLOCK_SIZE = 16;
    // Y 채널 픽셀 차이 임계값 (0~255)
    private static final int PIXEL_DIFF_THRESHOLD = 20;

    // sensitivity별 모션 블록 비율 임계값
    // sensitivity 1: 0.5%, 5: 6.0%
    private static final float[] MOTION_RATIO_BY_SENSITIVITY = {
        0.005f,  // 1 (very sensitive)
        0.015f,  // 2
        0.030f,  // 3 (default)
        0.045f,  // 4
        0.060f,  // 5 (least sensitive)
    };

    private byte[] previousSampled;
    private int sampledWidth;
    private int sampledHeight;
    private float motionRatioThreshold = MOTION_RATIO_BY_SENSITIVITY[2];

    public void setSensitivity(int sensitivity) {
        int clamped = Math.max(1, Math.min(5, sensitivity));
        motionRatioThreshold = MOTION_RATIO_BY_SENSITIVITY[clamped - 1];
    }

    /**
     * 프레임을 제출하여 모션 감지를 수행합니다.
     *
     * @param data   NV21 포맷 프레임 데이터
     * @param width  프레임 가로 픽셀
     * @param height 프레임 세로 픽셀
     * @return 모션이 감지되면 true, 최초 프레임이거나 기준 미달이면 false
     */
    public boolean detect(byte[] data, int width, int height) {
        if (data == null || data.length < width * height) {
            return false;
        }
        // Y 채널(NV21의 첫 width*height 바이트)만 다운샘플링
        int sw = (width + BLOCK_SIZE - 1) / BLOCK_SIZE;
        int sh = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;
        byte[] sampled = new byte[sw * sh];

        for (int by = 0; by < sh; by++) {
            for (int bx = 0; bx < sw; bx++) {
                int sum = 0;
                int count = 0;
                int yStart = by * BLOCK_SIZE;
                int xStart = bx * BLOCK_SIZE;
                int yEnd = Math.min(yStart + BLOCK_SIZE, height);
                int xEnd = Math.min(xStart + BLOCK_SIZE, width);
                for (int py = yStart; py < yEnd; py++) {
                    int rowOffset = py * width;
                    for (int px = xStart; px < xEnd; px++) {
                        sum += data[rowOffset + px] & 0xFF;
                        count++;
                    }
                }
                sampled[by * sw + bx] = (byte) (sum / count);
            }
        }

        if (previousSampled == null
                || sampledWidth != sw
                || sampledHeight != sh) {
            previousSampled = sampled;
            sampledWidth = sw;
            sampledHeight = sh;
            return false;
        }

        int changedBlocks = 0;
        for (int i = 0; i < sampled.length; i++) {
            int diff = Math.abs((sampled[i] & 0xFF) - (previousSampled[i] & 0xFF));
            if (diff > PIXEL_DIFF_THRESHOLD) {
                changedBlocks++;
            }
        }
        previousSampled = sampled;

        return changedBlocks > sampled.length * motionRatioThreshold;
    }

    /** 이전 프레임 버퍼를 초기화합니다 (모드 전환 시 호출). */
    public void reset() {
        previousSampled = null;
    }
}
