package com.ggpark.byddashcam;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class SegmentRecorder {
    public interface Listener {
        void onRecorderState(String state);
    }

    private static final int FRAME_RATE = 30;
    // Worst-case footage loss on a process kill is one open chunk, so this
    // is the crash-loss bound. Five seconds keeps file counts and stitch
    // overhead trivial while losing almost nothing on a crash.
    private static final long CHUNK_DURATION_NANOS = 5_000_000_000L;
    private static final String TAG = "BYDCamera";

    private final Listener listener;
    private final StorageRepository storageRepository;
    private final ExecutorService stitchExecutor =
            Executors.newSingleThreadExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread =
                                    new Thread(runnable, "byd-segment-stitch");
                            thread.setPriority(Thread.MIN_PRIORITY);
                            return thread;
                        }
                    });
    private final Set<File> pendingStitchDirectories =
            new LinkedHashSet<>();
    private File activeDirectory;
    private List<AvcMp4Encoder> encoders = new ArrayList<>();
    private long segmentStartedNanos;
    private long chunkStartedNanos;
    private volatile RecorderSettings settings;
    private boolean started;

    public SegmentRecorder(StorageRepository storageRepository, Listener listener) {
        this.storageRepository = storageRepository;
        this.listener = listener;
    }

    public File getActiveDirectory() {
        return activeDirectory;
    }

    /**
     * Directories automated cleanup must never delete: the active segment and
     * segments queued for or undergoing background stitching.
     */
    public Set<File> getProtectedDirectories() {
        Set<File> result = new LinkedHashSet<>();
        File active = activeDirectory;
        if (active != null) {
            result.add(active);
        }
        synchronized (pendingStitchDirectories) {
            result.addAll(pendingStitchDirectories);
        }
        return result;
    }

    public boolean isProtectedDirectory(File directory) {
        if (directory == null) {
            return false;
        }
        if (directory.equals(activeDirectory)) {
            return true;
        }
        synchronized (pendingStitchDirectories) {
            return pendingStitchDirectories.contains(directory);
        }
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Applies changed recorder settings to the running recording. The segment
     * length takes effect on the current segment's duration check; storage
     * policy, camera names, and file layout apply from the next segment.
     */
    public void updateSettings(RecorderSettings updatedSettings) {
        if (started && updatedSettings != null) {
            settings = updatedSettings;
        }
    }

    public void start(RecorderSettings requestedSettings) throws IOException {
        if (started) {
            return;
        }
        settings = requestedSettings;
        StorageRepository.CleanupResult cleanup =
                storageRepository.cleanup(settings, getProtectedDirectories());
        if (!cleanup.limitsSatisfied) {
            throw new IOException(
                    "Storage limits cannot be satisfied; locked recordings may consume "
                            + "the quota or free-space reserve");
        }
        openSegment();
        started = true;
    }

    public void offerFrame(FrameProcessor.ProcessedFrame frame) throws IOException {
        if (!started) {
            return;
        }
        long segmentDurationNanos = settings.segmentMinutes * 60L * 1_000_000_000L;
        if (frame.monotonicNanos - segmentStartedNanos >= segmentDurationNanos) {
            rotate();
        } else if (frame.monotonicNanos - chunkStartedNanos
                >= CHUNK_DURATION_NANOS) {
            chunkStartedNanos = frame.monotonicNanos;
            for (AvcMp4Encoder encoder : encoders) {
                encoder.requestChunkRotation();
            }
        }
        long presentationTimeUs =
                Math.max(0L, (frame.monotonicNanos - segmentStartedNanos) / 1000L);
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            encoders.get(index).encodeNv21(frame.cameras[index], presentationTimeUs);
        }
        encoders.get(FrameProcessor.CAMERA_COUNT)
                .encodeNv21(frame.combined, presentationTimeUs);
    }

    public void stop() {
        if (!started && encoders.isEmpty()) {
            return;
        }
        started = false;
        closeActiveSegment(true);
        try {
            storageRepository.cleanup(settings, getProtectedDirectories());
        } catch (IOException exception) {
            Log.e(TAG, "Post-recording cleanup failed", exception);
            listener.onRecorderState(
                    "Recording stopped; cleanup failed: " + exception.getMessage());
            return;
        }
        listener.onRecorderState("Recording stopped and files finalized");
    }

    private void closeActiveSegment(boolean completed) {
        boolean allFinalized = completed && !encoders.isEmpty();
        for (AvcMp4Encoder encoder : encoders) {
            try {
                allFinalized &= encoder.stop();
            } catch (RuntimeException exception) {
                allFinalized = false;
                Log.e(TAG, "Encoder stop failed", exception);
            }
        }
        encoders.clear();
        File closedDirectory = activeDirectory;
        activeDirectory = null;
        if (closedDirectory == null) {
            return;
        }
        closedDirectory.setLastModified(System.currentTimeMillis());
        if (allFinalized) {
            // Every final video was written directly during recording, so a
            // clean close is instant: drop the marker now and discard the
            // crash-safety chunks in the background.
            File marker = new File(closedDirectory, "recording.marker");
            if (marker.exists() && !marker.delete()) {
                Log.w(
                        TAG,
                        "Cannot remove recording marker after clean close: "
                                + marker);
                return;
            }
            discardPartsAsync(closedDirectory);
        } else if (completed) {
            // At least one direct file could not be produced; rebuild the
            // segment from its chunks. The marker stays until the stitch
            // succeeds so a kill remains recoverable.
            enqueueStitch(closedDirectory);
        }
    }

    private void discardPartsAsync(final File segmentDirectory) {
        stitchExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        SegmentStitcher.deletePartsQuietly(segmentDirectory);
                    }
                });
    }

    private void enqueueStitch(final File segmentDirectory) {
        synchronized (pendingStitchDirectories) {
            if (!pendingStitchDirectories.add(segmentDirectory)) {
                return;
            }
        }
        stitchExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        boolean stitched = false;
                        try {
                            SegmentStitcher.stitchSegment(segmentDirectory);
                            stitched = true;
                        } catch (IOException | RuntimeException exception) {
                            Log.e(
                                    TAG,
                                    "Segment stitch failed; recovery will retry: "
                                            + segmentDirectory.getName(),
                                    exception);
                        } finally {
                            synchronized (pendingStitchDirectories) {
                                pendingStitchDirectories.remove(
                                        segmentDirectory);
                            }
                        }
                        if (stitched) {
                            // Refreshes the car and phone interfaces so the
                            // segment stops showing as finalizing.
                            listener.onRecorderState(
                                    "Recording finalized: "
                                            + segmentDirectory.getName());
                        }
                    }
                });
    }

    private File createUniqueSegmentDirectory(File root) throws IOException {
        String baseName = RecorderDateTime.formatDirectoryTimestamp(new Date());
        for (int suffix = 0; suffix < 100; suffix++) {
            String name = suffix == 0 ? baseName : baseName + "-" + suffix;
            File candidate = new File(root, name);
            if (candidate.mkdir()) {
                return candidate;
            }
        }
        throw new IOException("Cannot create a unique segment directory");
    }

    private void openSegment() throws IOException {
        StorageRepository.CleanupResult cleanup =
                storageRepository.cleanup(settings, getProtectedDirectories());
        if (!cleanup.limitsSatisfied) {
            throw new IOException("Storage quota or free-space floor is exhausted");
        }

        File root = storageRepository.getRecorderRoot(settings);
        activeDirectory = createUniqueSegmentDirectory(root);
        File marker = new File(activeDirectory, "recording.marker");
        if (!marker.createNewFile()) {
            throw new IOException("Cannot create active recording marker");
        }
        String[] cameraNames =
                new String[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            cameraNames[index] = settings.cameraName(index);
        }
        RecordingFiles.Layout recordingFiles =
                RecordingFiles.createLayout(
                        activeDirectory,
                        cameraNames);
        writeMetadata(activeDirectory, recordingFiles);

        File partsDirectory =
                SegmentStitcher.partsDirectory(activeDirectory);
        if (!partsDirectory.mkdir() && !partsDirectory.isDirectory()) {
            throw new IOException(
                    "Cannot create chunk directory: " + partsDirectory);
        }
        int cameraWidth = FrameProcessor.recordingCameraWidth(
                settings.resolution,
                settings.fisheyeCropPercent());
        int cameraHeight = FrameProcessor.recordingCameraHeight(
                settings.resolution,
                settings.fisheyeCropPercent());
        List<AvcMp4Encoder> openedEncoders = new ArrayList<>();
        try {
            for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
                    openedEncoders.add(new AvcMp4Encoder(
                        partsDirectory,
                        new File(
                                activeDirectory,
                                recordingFiles.cameraVideos[index]),
                        index,
                        recordingFiles.cameraVideos[index],
                        cameraWidth,
                        cameraHeight,
                        settings.resolution.cameraBitrate,
                        FRAME_RATE));
            }
            openedEncoders.add(new AvcMp4Encoder(
                    partsDirectory,
                    new File(
                            activeDirectory,
                            recordingFiles.combinedVideo),
                    FrameProcessor.CAMERA_COUNT,
                    recordingFiles.combinedVideo,
                    cameraWidth * 2,
                    cameraHeight * 2,
                    settings.resolution.combinedBitrate,
                    FRAME_RATE));
        } catch (IOException | RuntimeException exception) {
            for (AvcMp4Encoder encoder : openedEncoders) {
                encoder.stop();
            }
            throw new IOException("Cannot initialize five video encoders", exception);
        }
        encoders = openedEncoders;
        segmentStartedNanos = System.nanoTime();
        chunkStartedNanos = segmentStartedNanos;
        listener.onRecorderState(
                "Recording "
                        + RecorderDateTime.formatSegmentName(
                                activeDirectory.getName(),
                                settings.dateFormat));
    }

    private void rotate() throws IOException {
        closeActiveSegment(true);
        StorageRepository.CleanupResult cleanup =
                storageRepository.cleanup(settings, getProtectedDirectories());
        if (!cleanup.limitsSatisfied) {
            started = false;
            throw new IOException(
                    "Recording stopped at rotation because storage limits are exhausted");
        }
        openSegment();
    }

    private void writeMetadata(
            File directory,
            RecordingFiles.Layout recordingFiles) throws IOException {
        try (FileWriter writer = new FileWriter(new File(directory, "segment.json"))) {
            writer.write("{\n");
            writer.write("  \"startedAt\": \""
                    + RecorderDateTime.formatMetadataTimestamp(new Date())
                    + "\",\n");
            writer.write("  \"segmentMinutes\": " + settings.segmentMinutes + ",\n");
            writer.write("  \"frameRate\": " + FRAME_RATE + ",\n");
            writer.write("  \"resolutionProfile\": \""
                    + settings.resolution.id
                    + "\",\n");
            writer.write("  \"cameraFiles\": [");
            for (int index = 0;
                    index < FrameProcessor.CAMERA_COUNT;
                    index++) {
                if (index > 0) {
                    writer.write(", ");
                }
                writer.write(
                        PhoneJson.quote(
                                recordingFiles.cameraVideos[index]));
            }
            writer.write("],\n");
            writer.write("  \"combinedFile\": "
                    + PhoneJson.quote(recordingFiles.combinedVideo)
                    + ",\n");
            int cameraWidth = FrameProcessor.recordingCameraWidth(
                    settings.resolution,
                    settings.fisheyeCropPercent());
            int cameraHeight = FrameProcessor.recordingCameraHeight(
                    settings.resolution,
                    settings.fisheyeCropPercent());
            writer.write("  \"cameraSize\": \""
                    + cameraWidth
                    + "x"
                    + cameraHeight
                    + "\",\n");
            writer.write("  \"combinedSize\": \""
                    + (cameraWidth * 2)
                    + "x"
                    + (cameraHeight * 2)
                    + "\",\n");
            writer.write("  \"cameraFlipHorizontal\": ["
                    + settings.cameraFlipHorizontal(0)
                    + ", "
                    + settings.cameraFlipHorizontal(1)
                    + ", "
                    + settings.cameraFlipHorizontal(2)
                    + ", "
                    + settings.cameraFlipHorizontal(3)
                    + "],\n");
            writer.write("  \"cameraFlipVertical\": ["
                    + settings.cameraFlipVertical(0)
                    + ", "
                    + settings.cameraFlipVertical(1)
                    + ", "
                    + settings.cameraFlipVertical(2)
                    + ", "
                    + settings.cameraFlipVertical(3)
                    + "],\n");
            writer.write("  \"fisheyeCropPercent\": "
                    + settings.fisheyeCropPercent()
                    + ",\n");
            writer.write("  \"combinedCameraOrder\": ["
                    + (settings.combinedCameraIndex(0) + 1)
                    + ", "
                    + (settings.combinedCameraIndex(1) + 1)
                    + ", "
                    + (settings.combinedCameraIndex(2) + 1)
                    + ", "
                    + (settings.combinedCameraIndex(3) + 1)
                    + "]\n");
            writer.write("}\n");
        }
    }
}
