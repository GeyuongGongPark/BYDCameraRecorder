package com.ggpark.byddashcam;

import android.graphics.Bitmap;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class FrameProcessor {
    // 이 상수들은 앱 시작 시 VehicleProfileRegistry.activate()를 통해 초기화됩니다.
    // non-final로 선언되어 있지만 init() 이후에는 변경되지 않습니다.
    public static int CAMERA_COUNT = 4;
    public static int SOURCE_CAMERA_HEIGHT = 960;
    public static int SOURCE_CAMERA_WIDTH = 1280;
    public static int SOURCE_HEIGHT = 960;
    public static int SOURCE_WIDTH = SOURCE_CAMERA_WIDTH * CAMERA_COUNT;
    public static final int VENDOR_NV21_FORMAT = 21;
    public static int PREVIEW_CAMERA_HEIGHT = SOURCE_CAMERA_HEIGHT;
    public static int PREVIEW_CAMERA_WIDTH = SOURCE_CAMERA_WIDTH;

    /**
     * VehicleProfile 값으로 정적 상수를 초기화합니다.
     * VehicleProfileRegistry.activate()에서 호출하며, 앱 생애주기에서 한 번만 실행합니다.
     */
    public static void init(VehicleProfile profile) {
        CAMERA_COUNT = profile.cameraCount();
        SOURCE_CAMERA_WIDTH = profile.sourceCameraWidth();
        SOURCE_CAMERA_HEIGHT = profile.sourceCameraHeight();
        SOURCE_WIDTH = profile.sourceCameraWidth() * profile.cameraCount();
        SOURCE_HEIGHT = profile.sourceCameraHeight();
        PREVIEW_CAMERA_WIDTH = profile.sourceCameraWidth();
        PREVIEW_CAMERA_HEIGHT = profile.sourceCameraHeight();
    }

    public static final class ProcessedFrame {
        public final byte[][] cameras;
        public final int cameraHeight;
        public final int cameraWidth;
        public final byte[] combined;
        public final int combinedHeight;
        public final int combinedWidth;
        public final long monotonicNanos;

        ProcessedFrame(
                byte[][] cameras,
                byte[] combined,
                int cameraWidth,
                int cameraHeight,
                long monotonicNanos) {
            this.cameras = cameras;
            this.cameraWidth = cameraWidth;
            this.cameraHeight = cameraHeight;
            this.combined = combined;
            this.combinedWidth = cameraWidth * 2;
            this.combinedHeight = cameraHeight * 2;
            this.monotonicNanos = monotonicNanos;
        }
    }

    public static int cropOffsetX(int cropPercent) {
        return (SOURCE_CAMERA_WIDTH * clampCropPercent(cropPercent) / 100) & ~1;
    }

    public static int cropOffsetY(int cropPercent) {
        return (SOURCE_CAMERA_HEIGHT * clampCropPercent(cropPercent) / 100) & ~1;
    }

    /**
     * Recording output width for one camera. The native profile records the
     * cropped region at its true pixel size instead of upscaling it back to
     * the full camera size, so recordings never contain invented pixels.
     */
    public static int recordingCameraWidth(
            VideoResolution resolution,
            int cropPercent) {
        if (resolution != VideoResolution.NATIVE) {
            return resolution.cameraWidth;
        }
        return SOURCE_CAMERA_WIDTH - cropOffsetX(cropPercent) * 2;
    }

    public static int recordingCameraHeight(
            VideoResolution resolution,
            int cropPercent) {
        if (resolution != VideoResolution.NATIVE) {
            return resolution.cameraHeight;
        }
        return SOURCE_CAMERA_HEIGHT - cropOffsetY(cropPercent) * 2;
    }

    private static int clampCropPercent(int cropPercent) {
        return Math.max(
                0,
                Math.min(
                        RecorderSettings.MAX_CAMERA_CROP_PERCENT,
                        cropPercent));
    }

    private byte[][] cameraFrames;
    private int fisheyeCropPercent;
    private int outputCameraWidth;
    private int outputCameraHeight;
    private boolean[] cameraFlipHorizontal = new boolean[CAMERA_COUNT];
    private boolean[] cameraFlipVertical = new boolean[CAMERA_COUNT];
    private GpsOverlayRenderer gpsOverlayRenderer;
    private GpsFix currentGpsFix = GpsFix.UNAVAILABLE;
    private byte[] combinedFrame;
    private int[] combinedLayout = new int[]{0, 1, 2, 3};
    private byte[] flipScratch;
    private final int[][] previewPixels =
            new int[CAMERA_COUNT][
                    PREVIEW_CAMERA_WIDTH * PREVIEW_CAMERA_HEIGHT];
    private final ArrayDeque<Bitmap[]> previewBitmapPool =
            new ArrayDeque<>();
    private VideoResolution resolution;

    public FrameProcessor() {
        configure(
                VideoResolution.DEFAULT,
                combinedLayout,
                cameraFlipHorizontal,
                cameraFlipVertical,
                fisheyeCropPercent);
    }

    public synchronized void configure(
            VideoResolution requestedResolution,
            int[] requestedCombinedLayout,
            boolean[] requestedCameraFlipHorizontal,
            boolean[] requestedCameraFlipVertical,
            int requestedFisheyeCropPercent) {
        int[] layout = normalizeCombinedLayout(requestedCombinedLayout);
        boolean[] horizontal =
                normalizeCameraFlips(requestedCameraFlipHorizontal);
        boolean[] vertical =
                normalizeCameraFlips(requestedCameraFlipVertical);
        int cropPercent = clampCropPercent(requestedFisheyeCropPercent);
        if (requestedResolution == resolution
                && Arrays.equals(layout, combinedLayout)
                && Arrays.equals(horizontal, cameraFlipHorizontal)
                && Arrays.equals(vertical, cameraFlipVertical)
                && cropPercent == fisheyeCropPercent) {
            return;
        }
        combinedLayout = layout;
        cameraFlipHorizontal = horizontal;
        cameraFlipVertical = vertical;
        fisheyeCropPercent = cropPercent;
        resolution = requestedResolution;
        outputCameraWidth =
                recordingCameraWidth(requestedResolution, cropPercent);
        outputCameraHeight =
                recordingCameraHeight(requestedResolution, cropPercent);
        cameraFrames = new byte[CAMERA_COUNT][
                outputCameraWidth * outputCameraHeight * 3 / 2];
        flipScratch = new byte[
                outputCameraWidth * outputCameraHeight * 3 / 2];
        combinedFrame = new byte[
                outputCameraWidth * outputCameraHeight * 4 * 3 / 2];
    }

    public synchronized int getFisheyeCropPercent() {
        return fisheyeCropPercent;
    }

    public synchronized void setGpsOverlayRenderer(GpsOverlayRenderer renderer) {
        this.gpsOverlayRenderer = renderer;
    }

    public synchronized void updateGpsFix(GpsFix fix) {
        this.currentGpsFix = fix != null ? fix : GpsFix.UNAVAILABLE;
    }

    public synchronized void configureTransforms(
            int[] requestedCombinedLayout,
            boolean[] requestedCameraFlipHorizontal,
            boolean[] requestedCameraFlipVertical,
            int requestedFisheyeCropPercent) {
        configure(
                resolution == null ? VideoResolution.DEFAULT : resolution,
                requestedCombinedLayout,
                requestedCameraFlipHorizontal,
                requestedCameraFlipVertical,
                requestedFisheyeCropPercent);
    }

    public synchronized ProcessedFrame process(
            byte[] source,
            int width,
            int height,
            int format,
            int dataSize,
            long monotonicNanos) {
        int expected = SOURCE_WIDTH * SOURCE_HEIGHT * 3 / 2;
        if (source == null
                || width != SOURCE_WIDTH
                || height != SOURCE_HEIGHT
                || format != VENDOR_NV21_FORMAT
                || dataSize < expected
                || source.length < expected) {
            throw new IllegalArgumentException(
                    "Unexpected AVM frame: width=" + width
                            + " height=" + height
                            + " format=" + format
                            + " dataSize=" + dataSize);
        }

        for (int cameraIndex = 0; cameraIndex < CAMERA_COUNT; cameraIndex++) {
            downsampleChannel(
                    source,
                    cameraIndex,
                    cameraFrames[cameraIndex],
                    outputCameraWidth,
                    outputCameraHeight,
                    fisheyeCropPercent);
            if (cameraFlipHorizontal[cameraIndex]
                    || cameraFlipVertical[cameraIndex]) {
                flipNv21(
                        cameraFrames[cameraIndex],
                        flipScratch,
                        outputCameraWidth,
                        outputCameraHeight,
                        cameraFlipHorizontal[cameraIndex],
                        cameraFlipVertical[cameraIndex]);
                System.arraycopy(
                        flipScratch,
                        0,
                        cameraFrames[cameraIndex],
                        0,
                        flipScratch.length);
            }
            // GPS 오버레이 합성 (flip 이후 적용)
            if (gpsOverlayRenderer != null) {
                gpsOverlayRenderer.applyToNv21(
                        cameraFrames[cameraIndex],
                        outputCameraWidth,
                        outputCameraHeight,
                        currentGpsFix);
            }
        }
        composeCombined(
                cameraFrames,
                combinedFrame,
                outputCameraWidth,
                outputCameraHeight);
        return new ProcessedFrame(
                cameraFrames,
                combinedFrame,
                outputCameraWidth,
                outputCameraHeight,
                monotonicNanos);
    }

    public synchronized Bitmap[] createPreviewBitmapsFromSource(
            byte[] source,
            int width,
            int height,
            int format,
            int dataSize,
            int requestedCropPercent) {
        int expected = SOURCE_WIDTH * SOURCE_HEIGHT * 3 / 2;
        if (source == null
                || width != SOURCE_WIDTH
                || height != SOURCE_HEIGHT
                || format != VENDOR_NV21_FORMAT
                || dataSize < expected
                || source.length < expected) {
            throw new IllegalArgumentException(
                    "Unexpected AVM preview frame: width=" + width
                            + " height=" + height
                            + " format=" + format
                            + " dataSize=" + dataSize);
        }
        int cropPercent =
                requestedCropPercent < 0
                        ? fisheyeCropPercent
                        : clampCropPercent(requestedCropPercent);
        int cropX = cropOffsetX(cropPercent);
        int cropY = cropOffsetY(cropPercent);
        int croppedWidth = SOURCE_CAMERA_WIDTH - cropX * 2;
        int croppedHeight = SOURCE_CAMERA_HEIGHT - cropY * 2;
        int sourceYSize = SOURCE_WIDTH * SOURCE_HEIGHT;
        Bitmap[] bitmaps =
                previewBitmapPool.isEmpty()
                        ? createPreviewBitmapSet()
                        : previewBitmapPool.removeFirst();
        for (int cameraIndex = 0;
                cameraIndex < CAMERA_COUNT;
                cameraIndex++) {
            int[] pixels = previewPixels[cameraIndex];
            int cameraOffsetX = cameraIndex * SOURCE_CAMERA_WIDTH;
            for (int row = 0; row < PREVIEW_CAMERA_HEIGHT; row++) {
                int sampledRow =
                        cameraFlipVertical[cameraIndex]
                                ? PREVIEW_CAMERA_HEIGHT - 1 - row
                                : row;
                int sourceRow =
                        cropY
                                + sampledRow
                                        * croppedHeight
                                        / PREVIEW_CAMERA_HEIGHT;
                for (int column = 0;
                        column < PREVIEW_CAMERA_WIDTH;
                        column++) {
                    int sampledColumn =
                            cameraFlipHorizontal[cameraIndex]
                                    ? PREVIEW_CAMERA_WIDTH - 1 - column
                                    : column;
                    int sourceColumn =
                            cropX
                                    + sampledColumn
                                            * croppedWidth
                                            / PREVIEW_CAMERA_WIDTH;
                    int sourceX = cameraOffsetX + sourceColumn;
                    int y = (source[sourceRow * SOURCE_WIDTH + sourceX] & 0xff) - 16;
                    if (y < 0) {
                        y = 0;
                    }
                    int uvOffset =
                            sourceYSize
                                    + (sourceRow >> 1) * SOURCE_WIDTH
                                    + cameraOffsetX
                                    + (sourceColumn & ~1);
                    int v = (source[uvOffset] & 0xff) - 128;
                    int u = (source[uvOffset + 1] & 0xff) - 128;
                    int luminance = 1192 * y;
                    int red = Math.max(
                            0,
                            Math.min(262143, luminance + 1634 * v));
                    int green = Math.max(
                            0,
                            Math.min(
                                    262143,
                                    luminance - 833 * v - 400 * u));
                    int blue = Math.max(
                            0,
                            Math.min(262143, luminance + 2066 * u));
                    pixels[row * PREVIEW_CAMERA_WIDTH + column] =
                            0xff000000
                                    | ((red << 6) & 0x00ff0000)
                                    | ((green >> 2) & 0x0000ff00)
                                    | ((blue >> 10) & 0x000000ff);
                }
            }
            Bitmap bitmap = bitmaps[cameraIndex];
            bitmap.setPixels(
                    pixels,
                    0,
                    PREVIEW_CAMERA_WIDTH,
                    0,
                    0,
                    PREVIEW_CAMERA_WIDTH,
                    PREVIEW_CAMERA_HEIGHT);
            if (gpsOverlayRenderer != null) {
                gpsOverlayRenderer.applyToBitmap(bitmap, currentGpsFix);
            }
        }
        return bitmaps;
    }

    public synchronized void releasePreviewBitmaps(Bitmap[] bitmaps) {
        if (!isReusablePreviewBitmapSet(bitmaps)) {
            recycleBitmaps(bitmaps);
            return;
        }
        if (previewBitmapPool.size() >= 1) {
            recycleBitmaps(bitmaps);
            return;
        }
        previewBitmapPool.addLast(bitmaps);
    }

    private void composeCombined(
            byte[][] cameras,
            byte[] destination,
            int cameraWidth,
            int cameraHeight) {
        int combinedWidth = cameraWidth * 2;
        int combinedHeight = cameraHeight * 2;
        int destinationYSize = combinedWidth * combinedHeight;
        int sourceYSize = cameraWidth * cameraHeight;

        for (int index = 0; index < CAMERA_COUNT; index++) {
            int cameraIndex = combinedLayout[index];
            int offsetX = (index % 2) * cameraWidth;
            int offsetY = (index / 2) * cameraHeight;
            for (int row = 0; row < cameraHeight; row++) {
                System.arraycopy(
                        cameras[cameraIndex],
                        row * cameraWidth,
                        destination,
                        (offsetY + row) * combinedWidth + offsetX,
                        cameraWidth);
            }

            int destinationUvOffset =
                    destinationYSize
                            + (offsetY / 2) * combinedWidth
                            + offsetX;
            for (int row = 0; row < cameraHeight / 2; row++) {
                System.arraycopy(
                        cameras[cameraIndex],
                        sourceYSize + row * cameraWidth,
                        destination,
                        destinationUvOffset + row * combinedWidth,
                        cameraWidth);
            }
        }
    }

    private Bitmap[] createPreviewBitmapSet() {
        Bitmap[] bitmaps = new Bitmap[CAMERA_COUNT];
        for (int index = 0; index < CAMERA_COUNT; index++) {
            bitmaps[index] =
                    Bitmap.createBitmap(
                            PREVIEW_CAMERA_WIDTH,
                            PREVIEW_CAMERA_HEIGHT,
                            Bitmap.Config.ARGB_8888);
        }
        return bitmaps;
    }

    private boolean isReusablePreviewBitmapSet(Bitmap[] bitmaps) {
        if (bitmaps == null || bitmaps.length != CAMERA_COUNT) {
            return false;
        }
        for (Bitmap bitmap : bitmaps) {
            if (bitmap == null
                    || bitmap.isRecycled()
                    || !bitmap.isMutable()
                    || bitmap.getWidth() != PREVIEW_CAMERA_WIDTH
                    || bitmap.getHeight() != PREVIEW_CAMERA_HEIGHT
                    || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                return false;
            }
        }
        return true;
    }

    private void recycleBitmaps(Bitmap[] bitmaps) {
        if (bitmaps == null) {
            return;
        }
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private int[] normalizeCombinedLayout(int[] source) {
        int[] layout = new int[]{0, 1, 2, 3};
        if (source == null || source.length != CAMERA_COUNT) {
            return layout;
        }
        boolean[] used = new boolean[CAMERA_COUNT];
        for (int position = 0; position < source.length; position++) {
            int cameraIndex = source[position];
            if (cameraIndex < 0
                    || cameraIndex >= CAMERA_COUNT
                    || used[cameraIndex]) {
                return layout;
            }
            used[cameraIndex] = true;
            layout[position] = cameraIndex;
        }
        return layout;
    }

    private boolean[] normalizeCameraFlips(boolean[] source) {
        boolean[] flips = new boolean[CAMERA_COUNT];
        if (source != null) {
            System.arraycopy(
                    source,
                    0,
                    flips,
                    0,
                    Math.min(source.length, CAMERA_COUNT));
        }
        return flips;
    }

    private void flipNv21(
            byte[] source,
            byte[] destination,
            int width,
            int height,
            boolean horizontal,
            boolean vertical) {
        int ySize = width * height;
        for (int destinationRow = 0;
                destinationRow < height;
                destinationRow++) {
            int sourceRow =
                    vertical ? height - 1 - destinationRow : destinationRow;
            for (int destinationColumn = 0;
                    destinationColumn < width;
                    destinationColumn++) {
                int sourceColumn =
                        horizontal
                                ? width - 1 - destinationColumn
                                : destinationColumn;
                destination[destinationRow * width + destinationColumn] =
                        source[sourceRow * width + sourceColumn];
            }
        }

        int chromaHeight = height / 2;
        for (int destinationRow = 0;
                destinationRow < chromaHeight;
                destinationRow++) {
            int sourceRow =
                    vertical
                            ? chromaHeight - 1 - destinationRow
                            : destinationRow;
            for (int destinationColumn = 0;
                    destinationColumn < width;
                    destinationColumn += 2) {
                int sourceColumn =
                        horizontal
                                ? width - 2 - destinationColumn
                                : destinationColumn;
                int sourceOffset =
                        ySize + sourceRow * width + sourceColumn;
                int destinationOffset =
                        ySize + destinationRow * width + destinationColumn;
                destination[destinationOffset] = source[sourceOffset];
                destination[destinationOffset + 1] = source[sourceOffset + 1];
            }
        }
    }

    private void downsampleChannel(
            byte[] source,
            int cameraIndex,
            byte[] destination,
            int outputWidth,
            int outputHeight,
            int cropPercent) {
        int sourceYSize = SOURCE_WIDTH * SOURCE_HEIGHT;
        int destinationYSize = outputWidth * outputHeight;
        int cameraOffsetX = cameraIndex * SOURCE_CAMERA_WIDTH;
        int cropX = cropOffsetX(cropPercent);
        int cropY = cropOffsetY(cropPercent);
        int croppedWidth = SOURCE_CAMERA_WIDTH - cropX * 2;
        int croppedHeight = SOURCE_CAMERA_HEIGHT - cropY * 2;

        if (croppedWidth == outputWidth && croppedHeight == outputHeight) {
            // The cropped region is emitted at its true size, so rows can be
            // copied without per-pixel resampling.
            for (int row = 0; row < outputHeight; row++) {
                System.arraycopy(
                        source,
                        (cropY + row) * SOURCE_WIDTH + cameraOffsetX + cropX,
                        destination,
                        row * outputWidth,
                        outputWidth);
            }
            for (int row = 0; row < outputHeight / 2; row++) {
                System.arraycopy(
                        source,
                        sourceYSize
                                + (cropY / 2 + row) * SOURCE_WIDTH
                                + cameraOffsetX
                                + cropX,
                        destination,
                        destinationYSize + row * outputWidth,
                        outputWidth);
            }
            return;
        }

        for (int row = 0; row < outputHeight; row++) {
            int sourceRow =
                    cropY + row * croppedHeight / outputHeight;
            int sourceOffset =
                    sourceRow * SOURCE_WIDTH + cameraOffsetX + cropX;
            int destinationOffset = row * outputWidth;
            for (int column = 0; column < outputWidth; column++) {
                int sourceColumn =
                        column * croppedWidth / outputWidth;
                destination[destinationOffset + column] =
                        source[sourceOffset + sourceColumn];
            }
        }

        for (int row = 0; row < outputHeight / 2; row++) {
            int sourceUvRow =
                    cropY / 2
                            + row
                                    * (croppedHeight / 2)
                                    / (outputHeight / 2);
            int sourceOffset =
                    sourceYSize
                            + sourceUvRow * SOURCE_WIDTH
                            + cameraOffsetX
                            + cropX;
            int destinationOffset = destinationYSize + row * outputWidth;
            for (int column = 0; column < outputWidth; column += 2) {
                int sourceColumn =
                        column * croppedWidth / outputWidth;
                sourceColumn &= ~1;
                destination[destinationOffset + column] =
                        source[sourceOffset + sourceColumn];
                destination[destinationOffset + column + 1] =
                        source[sourceOffset + sourceColumn + 1];
            }
        }
    }
}
