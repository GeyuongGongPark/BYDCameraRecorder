package com.ggpark.byddashcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

public final class SegmentPreviewLoader {
    private static final String TAG = "BYDCamera";
    public static final class Preview {
        public final long durationMillis;
        public final Bitmap[] thumbnails;

        Preview(long durationMillis, Bitmap[] thumbnails) {
            this.durationMillis = durationMillis;
            this.thumbnails = thumbnails;
        }
    }

    private static final int THUMBNAIL_HEIGHT = 90;
    private static final int THUMBNAIL_WIDTH = 120;
    private static final String CAMERA_CACHE_PREFIX = ".preview-camera-";
    private static final String STRIP_CACHE_NAME = ".preview-strip.jpg";

    private SegmentPreviewLoader() {
    }

    public static Preview load(File segmentDirectory) {
        long startedNanos = System.nanoTime();
        int cacheHits = 0;
        Bitmap[] thumbnails = new Bitmap[FrameProcessor.CAMERA_COUNT];
        File combinedVideo =
                RecordingFiles.combinedVideoFile(
                        segmentDirectory);
        long durationMillis =
                readDurationMillis(combinedVideo);
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            File cache =
                    new File(
                            segmentDirectory,
                            CAMERA_CACHE_PREFIX + (index + 1) + ".jpg");
            Bitmap cached =
                    cache.isFile() && cache.length() > 0L
                            ? BitmapFactory.decodeFile(cache.getAbsolutePath())
                            : null;
            if (cached != null) {
                thumbnails[index] = cached;
                cacheHits++;
            }
        }
        Bitmap combinedFrame =
                hasMissingThumbnail(thumbnails)
                        ? loadVideoFrame(combinedVideo, durationMillis)
                        : null;
        if (combinedFrame != null) {
            populateFromCombinedFrame(
                    segmentDirectory,
                    combinedFrame,
                    thumbnails);
            combinedFrame.recycle();
        }
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            if (thumbnails[index] != null) {
                continue;
            }
            File cache =
                    new File(
                            segmentDirectory,
                            CAMERA_CACHE_PREFIX + (index + 1) + ".jpg");
            File video =
                    RecordingFiles.cameraVideoFile(
                            segmentDirectory,
                            index);
            if (!video.isFile() || video.length() == 0L) {
                continue;
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                String duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION);
                long cameraDuration =
                        duration == null ? 0L : Long.parseLong(duration);
                durationMillis = Math.max(durationMillis, cameraDuration);
                Bitmap frame =
                        retriever.getFrameAtTime(
                                Math.max(
                                        100_000L,
                                        cameraDuration * 1000L / 3L),
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    thumbnails[index] = Bitmap.createScaledBitmap(
                            frame,
                            THUMBNAIL_WIDTH,
                            THUMBNAIL_HEIGHT,
                            true);
                    if (thumbnails[index] != frame) {
                        frame.recycle();
                    }
                    writeCache(thumbnails[index], cache);
                }
            } catch (RuntimeException ignored) {
                // The segment card remains usable even if one preview cannot decode.
            } finally {
                releaseRetriever(retriever);
            }
        }
        Log.i(
                TAG,
                "Segment preview: segment="
                        + segmentDirectory.getName()
                        + " cacheHits="
                        + cacheHits
                        + "/"
                        + FrameProcessor.CAMERA_COUNT
                        + " elapsedMs="
                        + ((System.nanoTime() - startedNanos) / 1_000_000L));
        return new Preview(durationMillis, thumbnails);
    }

    public static Bitmap loadCombinedStrip(File segmentDirectory) {
        long startedNanos = System.nanoTime();
        File cache = new File(segmentDirectory, STRIP_CACHE_NAME);
        Bitmap cached =
                cache.isFile() && cache.length() > 0L
                        ? BitmapFactory.decodeFile(cache.getAbsolutePath())
                        : null;
        if (cached != null) {
            Log.i(
                    TAG,
                    "Segment strip cache hit: segment="
                            + segmentDirectory.getName()
                            + " elapsedMs="
                            + ((System.nanoTime() - startedNanos)
                                    / 1_000_000L));
            return cached;
        }
        File video =
                RecordingFiles.combinedVideoFile(
                        segmentDirectory);
        if (!video.isFile() || video.length() == 0L) {
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMillis =
                    duration == null ? 0L : Long.parseLong(duration);
            frame = retriever.getFrameAtTime(
                    Math.max(100_000L, durationMillis * 1000L / 3L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                return null;
            }
            Bitmap strip = Bitmap.createBitmap(
                    THUMBNAIL_WIDTH * FrameProcessor.CAMERA_COUNT,
                    THUMBNAIL_HEIGHT,
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(strip);
            canvas.drawColor(Color.BLACK);
            int quadrantWidth = frame.getWidth() / 2;
            int quadrantHeight = frame.getHeight() / 2;
            for (int position = 0;
                    position < FrameProcessor.CAMERA_COUNT;
                    position++) {
                int sourceLeft = (position % 2) * quadrantWidth;
                int sourceTop = (position / 2) * quadrantHeight;
                canvas.drawBitmap(
                        frame,
                        new Rect(
                                sourceLeft,
                                sourceTop,
                                sourceLeft + quadrantWidth,
                                sourceTop + quadrantHeight),
                        new Rect(
                                position * THUMBNAIL_WIDTH,
                                0,
                                (position + 1) * THUMBNAIL_WIDTH,
                                THUMBNAIL_HEIGHT),
                        null);
            }
            writeCache(strip, cache);
            Log.i(
                    TAG,
                    "Segment strip generated: segment="
                            + segmentDirectory.getName()
                            + " elapsedMs="
                            + ((System.nanoTime() - startedNanos)
                                    / 1_000_000L));
            return strip;
        } catch (RuntimeException ignored) {
            // The phone row remains usable when an old segment cannot decode.
            return null;
        } finally {
            if (frame != null) {
                frame.recycle();
            }
            releaseRetriever(retriever);
        }
    }

    public static byte[] loadCombinedStripJpeg(File segmentDirectory)
            throws IOException {
        File cache = new File(segmentDirectory, STRIP_CACHE_NAME);
        if (cache.isFile() && cache.length() > 0L) {
            return readFile(cache);
        }
        Bitmap strip = loadCombinedStrip(segmentDirectory);
        if (strip == null) {
            return null;
        }
        try {
            if (cache.isFile() && cache.length() > 0L) {
                return readFile(cache);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            strip.compress(Bitmap.CompressFormat.JPEG, 82, output);
            return output.toByteArray();
        } finally {
            strip.recycle();
        }
    }

    private static long readDurationMillis(File video) {
        if (!video.isFile() || video.length() == 0L) {
            return 0L;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return duration == null ? 0L : Long.parseLong(duration);
        } catch (RuntimeException ignored) {
            return 0L;
        } finally {
            releaseRetriever(retriever);
        }
    }

    private static boolean hasMissingThumbnail(Bitmap[] thumbnails) {
        for (Bitmap thumbnail : thumbnails) {
            if (thumbnail == null) {
                return true;
            }
        }
        return false;
    }

    private static Bitmap loadVideoFrame(
            File video,
            long durationMillis) {
        if (!video.isFile() || video.length() == 0L) {
            return null;
        }
        MediaMetadataRetriever retriever =
                new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            return retriever.getFrameAtTime(
                    Math.max(
                            100_000L,
                            durationMillis * 1000L / 3L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            releaseRetriever(retriever);
        }
    }

    private static void releaseRetriever(
            MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Exception ignored) {
            // A broken media file must not prevent other recording rows loading.
        }
    }

    private static void populateFromCombinedFrame(
            File segmentDirectory,
            Bitmap frame,
            Bitmap[] thumbnails) {
        int[] cameraOrder =
                readCombinedCameraOrder(segmentDirectory);
        int quadrantWidth = frame.getWidth() / 2;
        int quadrantHeight = frame.getHeight() / 2;
        for (int position = 0;
                position < FrameProcessor.CAMERA_COUNT;
                position++) {
            int cameraIndex = cameraOrder[position];
            if (thumbnails[cameraIndex] != null) {
                continue;
            }
            Bitmap thumbnail =
                    Bitmap.createBitmap(
                            THUMBNAIL_WIDTH,
                            THUMBNAIL_HEIGHT,
                            Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(thumbnail);
            canvas.drawColor(Color.BLACK);
            int sourceLeft =
                    (position % 2) * quadrantWidth;
            int sourceTop =
                    (position / 2) * quadrantHeight;
            canvas.drawBitmap(
                    frame,
                    new Rect(
                            sourceLeft,
                            sourceTop,
                            sourceLeft + quadrantWidth,
                            sourceTop + quadrantHeight),
                    new Rect(
                            0,
                            0,
                            THUMBNAIL_WIDTH,
                            THUMBNAIL_HEIGHT),
                    null);
            thumbnails[cameraIndex] = thumbnail;
            writeCache(
                    thumbnail,
                    new File(
                            segmentDirectory,
                            CAMERA_CACHE_PREFIX
                                    + (cameraIndex + 1)
                                    + ".jpg"));
        }
    }

    private static int[] readCombinedCameraOrder(
            File segmentDirectory) {
        int[] fallback = new int[]{0, 1, 2, 3};
        File metadata =
                new File(segmentDirectory, "segment.json");
        if (!metadata.isFile()) {
            return fallback;
        }
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(metadata))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        } catch (IOException exception) {
            return fallback;
        }
        String key = "\"combinedCameraOrder\"";
        int keyIndex = json.indexOf(key);
        int opening =
                keyIndex < 0
                        ? -1
                        : json.indexOf("[", keyIndex + key.length());
        int closing =
                opening < 0 ? -1 : json.indexOf("]", opening + 1);
        if (opening < 0 || closing < 0) {
            return fallback;
        }
        String[] values =
                json.substring(opening + 1, closing).split(",");
        if (values.length != FrameProcessor.CAMERA_COUNT) {
            return fallback;
        }
        int[] order =
                new int[FrameProcessor.CAMERA_COUNT];
        boolean[] seen =
                new boolean[FrameProcessor.CAMERA_COUNT];
        try {
            for (int position = 0;
                    position < FrameProcessor.CAMERA_COUNT;
                    position++) {
                int cameraIndex =
                        Integer.parseInt(values[position].trim()) - 1;
                if (cameraIndex < 0
                        || cameraIndex >= FrameProcessor.CAMERA_COUNT
                        || seen[cameraIndex]) {
                    return fallback;
                }
                order[position] = cameraIndex;
                seen[cameraIndex] = true;
            }
        } catch (NumberFormatException exception) {
            return fallback;
        }
        return order;
    }

    private static void writeCache(Bitmap bitmap, File cache) {
        try (FileOutputStream output = new FileOutputStream(cache)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
        } catch (IOException ignored) {
            // Preview decoding remains usable when a cache file cannot be written.
        }
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream((int) Math.min(file.length(), 64 * 1024L));
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }
}
