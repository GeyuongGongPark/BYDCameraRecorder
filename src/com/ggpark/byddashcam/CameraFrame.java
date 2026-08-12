package com.ggpark.byddashcam;

public final class CameraFrame {
    public final byte[] data;
    public final int dataSize;
    public final int format;
    public final int height;
    public final long monotonicNanos;
    public final long sourceTimestamp;
    public final int viewIndex;
    public final int width;

    public CameraFrame(
            byte[] data,
            int width,
            int height,
            int format,
            int dataSize,
            int viewIndex,
            long sourceTimestamp,
            long monotonicNanos) {
        this.data = data;
        this.width = width;
        this.height = height;
        this.format = format;
        this.dataSize = dataSize;
        this.viewIndex = viewIndex;
        this.sourceTimestamp = sourceTimestamp;
        this.monotonicNanos = monotonicNanos;
    }
}
