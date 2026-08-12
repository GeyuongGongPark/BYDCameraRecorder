package com.ggpark.byddashcam;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Combines the short chunk MP4 files written during recording into the five
 * final segment videos by copying compressed samples (no re-encoding). The
 * same code path finalizes segments after a normal stop and recovers segments
 * left behind by a killed process; in the recovery case only the torn last
 * chunk (at most a few seconds) is lost.
 */
public final class SegmentStitcher {
    public static final String PARTS_DIRECTORY_NAME = "parts";
    private static final String TAG = "BYDCamera";
    private static final int COPY_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final long DEFAULT_FRAME_DURATION_US = 33_333L;

    /**
     * Copied/total byte counters for stitches in flight, keyed by segment
     * directory path, so both interfaces can show finalization progress.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            STITCH_PROGRESS = new java.util.concurrent.ConcurrentHashMap<>();

    private SegmentStitcher() {
    }

    /**
     * Percentage of the running stitch for this segment, 0-99, or -1 when no
     * stitch is currently copying it (queued, waiting for recovery, or done).
     */
    public static int progressPercent(File segmentDirectory) {
        return toPercent(
                STITCH_PROGRESS.get(segmentDirectory.getAbsolutePath()));
    }

    /**
     * Segment directory name to stitch percentage for every stitch currently
     * copying, for push updates to the phone interface.
     */
    public static java.util.Map<String, Integer> activeProgressByName() {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, long[]> entry
                : STITCH_PROGRESS.entrySet()) {
            result.put(
                    new File(entry.getKey()).getName(),
                    toPercent(entry.getValue()));
        }
        return result;
    }

    private static int toPercent(long[] progress) {
        if (progress == null) {
            return -1;
        }
        long total = progress[1];
        if (total <= 0L) {
            return 0;
        }
        return (int) Math.min(99L, progress[0] * 100L / total);
    }

    public static File partsDirectory(File segmentDirectory) {
        return new File(segmentDirectory, PARTS_DIRECTORY_NAME);
    }

    public static boolean hasParts(File segmentDirectory) {
        File[] chunks = partsDirectory(segmentDirectory).listFiles();
        return chunks != null && chunks.length > 0;
    }

    public static String chunkName(int streamIndex, int chunkIndex) {
        return String.format(
                Locale.US,
                "stream-%d-%04d.mp4",
                streamIndex,
                chunkIndex);
    }

    /**
     * Builds every final video that does not exist yet from the recorded
     * chunks, then removes the parts directory and the recording marker.
     * Throws if any stream cannot be produced; the marker is then left in
     * place so a later recovery pass can retry.
     */
    public static void stitchSegment(File segmentDirectory) throws IOException {
        long startedNanos = System.nanoTime();
        RecordingFiles.Layout layout =
                RecordingFiles.indexedLayout(segmentDirectory);
        if (layout == null) {
            throw new IOException(
                    "Segment has no file index: " + segmentDirectory);
        }
        deleteLiveLeftovers(segmentDirectory);
        File parts = partsDirectory(segmentDirectory);
        ByteBuffer copyBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES);
        List<String> pendingTargets = new ArrayList<>();
        List<List<File>> pendingChunks = new ArrayList<>();
        long totalBytes = 0L;
        for (int streamIndex = 0;
                streamIndex <= FrameProcessor.CAMERA_COUNT;
                streamIndex++) {
            String targetName =
                    streamIndex < FrameProcessor.CAMERA_COUNT
                            ? layout.cameraVideos[streamIndex]
                            : layout.combinedVideo;
            File target = new File(segmentDirectory, targetName);
            if (target.isFile() && target.length() > 0L) {
                continue;
            }
            List<File> chunks = listChunks(parts, streamIndex);
            if (chunks.isEmpty()) {
                throw new IOException(
                        "No chunks found for " + targetName);
            }
            for (File chunk : chunks) {
                totalBytes += chunk.length();
            }
            pendingTargets.add(targetName);
            pendingChunks.add(chunks);
        }
        long[] progress = new long[]{0L, totalBytes};
        STITCH_PROGRESS.put(segmentDirectory.getAbsolutePath(), progress);
        long copiedBytes = 0L;
        try {
            for (int work = 0; work < pendingTargets.size(); work++) {
                String targetName = pendingTargets.get(work);
                File target = new File(segmentDirectory, targetName);
                File temporary = new File(
                        segmentDirectory,
                        targetName + ".stitching");
                // A stitch killed mid-copy leaves a stale temporary, and
                // MediaMuxer does not truncate existing files; remove it so
                // the retry writes a clean file.
                if (temporary.exists() && !temporary.delete()) {
                    throw new IOException(
                            "Cannot remove stale stitch temporary: "
                                    + temporary);
                }
                try {
                    copiedBytes += stitchStream(
                            pendingChunks.get(work),
                            temporary,
                            copyBuffer,
                            progress);
                    if (!temporary.renameTo(target)) {
                        throw new IOException(
                                "Cannot move stitched video into place: "
                                        + target);
                    }
                } finally {
                    if (temporary.exists() && !temporary.delete()) {
                        Log.w(TAG, "Stale stitch temporary left: " + temporary);
                    }
                }
            }
        } finally {
            STITCH_PROGRESS.remove(segmentDirectory.getAbsolutePath());
        }
        deleteParts(parts);
        File marker = new File(segmentDirectory, "recording.marker");
        if (marker.exists() && !marker.delete()) {
            throw new IOException(
                    "Cannot remove recording marker after stitch: " + marker);
        }
        segmentDirectory.setLastModified(System.currentTimeMillis());
        Log.i(
                TAG,
                "Segment stitched: "
                        + segmentDirectory.getName()
                        + " bytes="
                        + copiedBytes
                        + " elapsedMs="
                        + (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static List<File> listChunks(File parts, final int streamIndex) {
        List<File> chunks = new ArrayList<>();
        File[] children = parts.listFiles();
        if (children == null) {
            return chunks;
        }
        String prefix = "stream-" + streamIndex + "-";
        for (File child : children) {
            if (child.isFile()
                    && child.getName().startsWith(prefix)
                    && child.getName().endsWith(".mp4")) {
                chunks.add(child);
            }
        }
        Collections.sort(
                chunks,
                new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName().compareTo(right.getName());
                    }
                });
        return chunks;
    }

    /**
     * Copies all video samples from the chunk files into one MP4. A chunk
     * that cannot be opened or read (the one torn by a process kill) is
     * skipped with a warning. Returns the number of sample bytes copied.
     */
    private static long stitchStream(
            List<File> chunks,
            File output,
            ByteBuffer copyBuffer,
            long[] progress) throws IOException {
        MediaMuxer muxer = null;
        int trackIndex = -1;
        long offsetUs = 0L;
        long copiedBytes = 0L;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean anyChunkCopied = false;
        try {
            for (File chunk : chunks) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(chunk.getAbsolutePath());
                    MediaFormat format = selectVideoTrack(extractor);
                    if (format == null) {
                        Log.w(TAG, "Chunk has no video track: " + chunk);
                        continue;
                    }
                    if (muxer == null) {
                        muxer = new MediaMuxer(
                                output.getAbsolutePath(),
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        trackIndex = muxer.addTrack(format);
                        muxer.start();
                    }
                    long firstSampleUs = -1L;
                    long lastSampleUs = 0L;
                    while (true) {
                        int size = extractor.readSampleData(copyBuffer, 0);
                        if (size < 0) {
                            break;
                        }
                        long sampleUs = extractor.getSampleTime();
                        if (firstSampleUs < 0L) {
                            firstSampleUs = sampleUs;
                        }
                        lastSampleUs = sampleUs;
                        info.set(
                                0,
                                size,
                                offsetUs + (sampleUs - firstSampleUs),
                                (extractor.getSampleFlags()
                                        & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                                        : 0);
                        muxer.writeSampleData(trackIndex, copyBuffer, info);
                        copiedBytes += size;
                        progress[0] += size;
                        extractor.advance();
                    }
                    if (firstSampleUs >= 0L) {
                        anyChunkCopied = true;
                        offsetUs += lastSampleUs
                                - firstSampleUs
                                + frameDurationUs(format);
                    }
                } catch (IOException | RuntimeException exception) {
                    // A torn chunk from a process kill is expected; keep the
                    // remaining footage.
                    Log.w(
                            TAG,
                            "Skipping unreadable chunk: " + chunk,
                            exception);
                } finally {
                    extractor.release();
                }
            }
        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Stitch muxer stop failed: " + output, exception);
                }
                muxer.release();
            }
        }
        if (!anyChunkCopied) {
            throw new IOException(
                    "No readable chunks produced samples for: " + output);
        }
        return copiedBytes;
    }

    private static MediaFormat selectVideoTrack(MediaExtractor extractor) {
        for (int track = 0; track < extractor.getTrackCount(); track++) {
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                extractor.selectTrack(track);
                return format;
            }
        }
        return null;
    }

    private static long frameDurationUs(MediaFormat format) {
        try {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                int frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                if (frameRate > 0) {
                    return 1_000_000L / frameRate;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the default frame duration.
        }
        return DEFAULT_FRAME_DURATION_US;
    }

    /**
     * Removes the crash-safety chunks of a cleanly closed segment. Failures
     * only leave reclaimable junk behind, so they are logged, not raised.
     */
    public static void deletePartsQuietly(File segmentDirectory) {
        try {
            deleteParts(partsDirectory(segmentDirectory));
        } catch (IOException exception) {
            Log.w(
                    TAG,
                    "Chunk cleanup failed; recovery will reclaim it: "
                            + segmentDirectory.getName(),
                    exception);
        }
    }

    /**
     * Discards unplayable .live files left by a killed recording; the final
     * videos are rebuilt from the chunks instead.
     */
    private static void deleteLiveLeftovers(File segmentDirectory) {
        File[] children = segmentDirectory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isFile()
                    && child.getName().endsWith(AvcMp4Encoder.LIVE_FILE_SUFFIX)
                    && !child.delete()) {
                Log.w(TAG, "Cannot delete stale live file: " + child);
            }
        }
    }

    private static void deleteParts(File parts) throws IOException {
        File[] children = parts.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.delete() && child.exists()) {
                    throw new IOException("Cannot delete chunk: " + child);
                }
            }
        }
        if (!parts.delete() && parts.exists()) {
            throw new IOException("Cannot delete parts directory: " + parts);
        }
    }
}
