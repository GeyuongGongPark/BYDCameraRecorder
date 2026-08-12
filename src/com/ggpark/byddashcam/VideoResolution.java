package com.ggpark.byddashcam;

public enum VideoResolution {
    ECONOMY(
            "economy",
            "Economy · 320×240 each · 640×480 combined",
            320,
            240,
            600_000,
            1_500_000),
    STANDARD(
            "standard",
            "Standard · 640×480 each · 1280×960 combined",
            640,
            480,
            1_200_000,
            3_000_000),
    HIGH(
            "high",
            "High · 960×720 each · 1920×1440 combined",
            960,
            720,
            2_200_000,
            5_000_000),
    NATIVE(
            "native",
            "Native maximum quality · 1280×960 each · 2560×1920 combined",
            1280,
            960,
            8_000_000,
            24_000_000);

    public static final VideoResolution DEFAULT = NATIVE;

    public final int cameraBitrate;
    public final int cameraHeight;
    public final int cameraWidth;
    public final int combinedBitrate;
    public final String id;
    public final String label;

    VideoResolution(
            String id,
            String label,
            int cameraWidth,
            int cameraHeight,
            int cameraBitrate,
            int combinedBitrate) {
        this.id = id;
        this.label = label;
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.cameraBitrate = cameraBitrate;
        this.combinedBitrate = combinedBitrate;
    }

    public int combinedHeight() {
        return cameraHeight * 2;
    }

    public int combinedWidth() {
        return cameraWidth * 2;
    }

    public String dimensionsLabel() {
        return cameraWidth
                + "×"
                + cameraHeight
                + " each\n"
                + combinedWidth()
                + "×"
                + combinedHeight()
                + " combined";
    }

    public static VideoResolution fromId(String id) {
        for (VideoResolution resolution : values()) {
            if (resolution.id.equals(id)) {
                return resolution;
            }
        }
        return DEFAULT;
    }
}
