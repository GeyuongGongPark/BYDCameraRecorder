package com.ggpark.byddashcam;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

public final class StorageRepository {
    public static final String LOCKED_DELETE_MESSAGE =
            "Locked recordings must be unlocked before they can be deleted. "
                    + "Unlock the selected recording, then try again.";

    private static final String TAG = "BYDCamera";
    // Protects a directory that is mid-creation from a concurrent recovery
    // scan; anything genuinely interrupted is older than this within one
    // recovery cycle. The active segment and stitch queue are additionally
    // excluded via the protected set.
    private static final long RECLAIM_MINIMUM_AGE_MILLIS = 30L * 1000L;

    private static final class CachedSegment {
        final long directoryModifiedAtMillis;
        final SegmentInfo segment;

        CachedSegment(long directoryModifiedAtMillis, SegmentInfo segment) {
            this.directoryModifiedAtMillis = directoryModifiedAtMillis;
            this.segment = segment;
        }
    }

    public static final class StorageVolume {
        public final int index;
        public final String label;
        public final File root;

        StorageVolume(int index, String label, File root) {
            this.index = index;
            this.label = label;
            this.root = root;
        }
    }

    public static final class SegmentInfo {
        public final boolean active;
        public final File directory;
        public final boolean incomplete;
        public final boolean locked;
        public final long modifiedAtMillis;
        public final long sizeBytes;

        SegmentInfo(
                File directory,
                long sizeBytes,
                long modifiedAtMillis,
                boolean locked,
                boolean active,
                boolean incomplete) {
            this.directory = directory;
            this.sizeBytes = sizeBytes;
            this.modifiedAtMillis = modifiedAtMillis;
            this.locked = locked;
            this.active = active;
            this.incomplete = incomplete;
        }
    }

    public static final class StorageSnapshot {
        public final long availableBytes;
        public final long lockedBytes;
        public final long recorderBytes;
        public final File recorderRoot;
        public final long totalBytes;

        StorageSnapshot(
                File recorderRoot,
                long totalBytes,
                long availableBytes,
                long recorderBytes,
                long lockedBytes) {
            this.recorderRoot = recorderRoot;
            this.totalBytes = totalBytes;
            this.availableBytes = availableBytes;
            this.recorderBytes = recorderBytes;
            this.lockedBytes = lockedBytes;
        }
    }

    public static final class CleanupResult {
        public final long bytesRemoved;
        public final int groupsRemoved;
        public final boolean limitsSatisfied;

        CleanupResult(int groupsRemoved, long bytesRemoved, boolean limitsSatisfied) {
            this.groupsRemoved = groupsRemoved;
            this.bytesRemoved = bytesRemoved;
            this.limitsSatisfied = limitsSatisfied;
        }
    }

    private static final int MAX_STITCH_ATTEMPTS = 3;

    private final Context context;
    private final Map<String, CachedSegment> finalizedSegmentCache =
            new HashMap<>();
    private final Map<String, Integer> stitchFailureCounts =
            new HashMap<>();

    public StorageRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public CleanupResult cleanup(
            RecorderSettings settings,
            Set<File> protectedDirectories)
            throws IOException {
        File recorderRoot = getRecorderRoot(settings);
        Set<File> protectedDirs =
                protectedDirectories == null
                        ? Collections.<File>emptySet()
                        : protectedDirectories;
        List<SegmentInfo> segments = listSegments(settings, null);
        long now = System.currentTimeMillis();
        long retentionMillis = settings.retentionDays * 24L * 60L * 60L * 1000L;
        int removedCount = 0;
        long removedBytes = 0L;

        for (SegmentInfo segment : segments) {
            if (isDeletable(segment, protectedDirs)
                    && now - segment.modifiedAtMillis > retentionMillis) {
                deleteOwnedSegment(recorderRoot, segment.directory);
                removedCount++;
                removedBytes += segment.sizeBytes;
            }
        }

        // Reclaim space in a safe priority order: interrupted segments with
        // no recoverable chunks first (pure junk), then oldest footage. A
        // segment that still has chunk files is recoverable video and is
        // ranked with normal footage by age. This never discards newer good
        // footage while unplayable junk still occupies the quota.
        List<SegmentInfo> deletionOrder = new ArrayList<>();
        for (SegmentInfo segment : listSegments(settings, null)) {
            if (isDeletable(segment, protectedDirs)) {
                deletionOrder.add(segment);
            }
        }
        Collections.sort(
                deletionOrder,
                new Comparator<SegmentInfo>() {
                    @Override
                    public int compare(SegmentInfo left, SegmentInfo right) {
                        boolean leftJunk = left.incomplete
                                && !SegmentStitcher.hasParts(left.directory);
                        boolean rightJunk = right.incomplete
                                && !SegmentStitcher.hasParts(right.directory);
                        if (leftJunk != rightJunk) {
                            return leftJunk ? -1 : 1;
                        }
                        return Long.compare(
                                left.modifiedAtMillis,
                                right.modifiedAtMillis);
                    }
                });
        for (SegmentInfo segment : deletionOrder) {
            StorageSnapshot snapshot = snapshot(settings);
            long minimumFreeBytes =
                    snapshot.totalBytes * settings.minFreePercent / 100L;
            if (snapshot.recorderBytes <= settings.quotaBytes
                    && snapshot.availableBytes >= minimumFreeBytes) {
                break;
            }
            deleteOwnedSegment(recorderRoot, segment.directory);
            removedCount++;
            removedBytes += segment.sizeBytes;
        }

        StorageSnapshot finalSnapshot = snapshot(settings);
        long finalMinimumFree =
                finalSnapshot.totalBytes * settings.minFreePercent / 100L;
        boolean satisfied =
                finalSnapshot.recorderBytes <= settings.quotaBytes
                        && finalSnapshot.availableBytes >= finalMinimumFree;
        return new CleanupResult(removedCount, removedBytes, satisfied);
    }

    /**
     * Handles interrupted segments left behind when the recorder process was
     * killed mid-segment. A segment that still has chunk files is stitched
     * into playable final videos (losing at most the torn last chunk); a
     * segment without chunks predates chunked recording and its files can
     * never be made playable (the MP4 index was never written), so it is
     * deleted. Locked segments are always preserved. This must not touch the
     * active recording or segments being stitched, which callers exclude via
     * the protected set and which the age guard also covers.
     */
    public interface RecoveryListener {
        void onSegmentRecovered(String segmentName);
    }

    public boolean recoverInterruptedSegments(
            RecorderSettings settings,
            Set<File> protectedDirectories,
            RecoveryListener listener)
            throws IOException {
        File recorderRoot = getRecorderRoot(settings);
        Set<File> protectedDirs =
                protectedDirectories == null
                        ? Collections.<File>emptySet()
                        : protectedDirectories;
        int recoveredCount = 0;
        int removedCount = 0;
        long removedBytes = 0L;
        long now = System.currentTimeMillis();
        for (SegmentInfo segment : listSegments(settings, null)) {
            // The age guard prevents any race with a segment that is being
            // created while this scan runs: a live segment directory always
            // has a fresh modification time.
            boolean oldEnough =
                    now - segment.modifiedAtMillis
                            > RECLAIM_MINIMUM_AGE_MILLIS;
            if (segment.locked
                    || segment.active
                    || !oldEnough
                    || protectedDirs.contains(segment.directory)) {
                continue;
            }
            if (!segment.incomplete) {
                // A cleanly closed segment whose background chunk cleanup was
                // killed can retain a stale parts directory; discard it.
                if (SegmentStitcher.hasParts(segment.directory)) {
                    SegmentStitcher.deletePartsQuietly(segment.directory);
                }
                continue;
            }
            if (SegmentStitcher.hasParts(segment.directory)) {
                String key = segment.directory.getAbsolutePath();
                try {
                    SegmentStitcher.stitchSegment(segment.directory);
                    recoveredCount++;
                    synchronized (stitchFailureCounts) {
                        stitchFailureCounts.remove(key);
                    }
                    if (listener != null) {
                        // Per-segment notification so the car interface
                        // refreshes as each recording becomes available
                        // instead of waiting for the whole recovery pass.
                        listener.onSegmentRecovered(
                                segment.directory.getName());
                    }
                } catch (IOException | RuntimeException exception) {
                    int attempts;
                    synchronized (stitchFailureCounts) {
                        Integer previous = stitchFailureCounts.get(key);
                        attempts = (previous == null ? 0 : previous) + 1;
                        stitchFailureCounts.put(key, attempts);
                    }
                    if (attempts >= MAX_STITCH_ATTEMPTS) {
                        // Nothing salvageable, for example a segment killed
                        // before its first chunk closed: every chunk is torn.
                        // Retrying forever would pin a phantom "finalizing"
                        // entry in both interfaces.
                        Log.w(
                                TAG,
                                "Reclaiming segment after "
                                        + attempts
                                        + " failed stitch attempts: "
                                        + segment.directory.getName(),
                                exception);
                        deleteOwnedSegment(recorderRoot, segment.directory);
                        synchronized (stitchFailureCounts) {
                            stitchFailureCounts.remove(key);
                        }
                        removedCount++;
                        removedBytes += segment.sizeBytes;
                    } else {
                        Log.w(
                                TAG,
                                "Interrupted segment could not be stitched yet: "
                                        + segment.directory.getName(),
                                exception);
                    }
                }
            } else {
                Log.w(
                        TAG,
                        "Reclaiming unrecoverable interrupted segment: "
                                + segment.directory.getName()
                                + " bytes="
                                + segment.sizeBytes);
                deleteOwnedSegment(recorderRoot, segment.directory);
                removedCount++;
                removedBytes += segment.sizeBytes;
            }
        }
        if (recoveredCount > 0 || removedCount > 0) {
            Log.i(
                    TAG,
                    "Interrupted-segment recovery: recovered="
                            + recoveredCount
                            + " reclaimed="
                            + removedCount
                            + " bytes="
                            + removedBytes);
        }
        return recoveredCount > 0 || removedCount > 0;
    }

    public File getRecorderRoot(RecorderSettings settings) throws IOException {
        List<StorageVolume> volumes = getVolumes();
        if (volumes.isEmpty()) {
            throw new IOException("No app-owned storage volume is available");
        }
        int index = Math.min(settings.volumeIndex, volumes.size() - 1);
        File root = new File(volumes.get(index).root, "BYDCamera/recordings");
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create recording root: " + root);
        }
        return root;
    }

    public List<StorageVolume> getVolumes() {
        File[] directories = context.getExternalFilesDirs(null);
        List<StorageVolume> result = new ArrayList<>();
        if (directories == null) {
            return result;
        }
        for (int index = 0; index < directories.length; index++) {
            File directory = directories[index];
            if (directory == null) {
                continue;
            }
            boolean removable = Environment.isExternalStorageRemovable(directory);
            String label = String.format(
                    Locale.US,
                    "%s storage: %s",
                    removable ? "Removable" : "Internal",
                    directory.getAbsolutePath());
            result.add(new StorageVolume(index, label, directory));
        }
        return result;
    }

    public List<SegmentInfo> listSegments(RecorderSettings settings) throws IOException {
        return listSegments(settings, null);
    }

    public List<SegmentInfo> listSegments(
            RecorderSettings settings,
            File activeDirectory) throws IOException {
        long startedNanos = System.nanoTime();
        File root = getRecorderRoot(settings);
        File[] children = root.listFiles();
        if (children == null) {
            return new ArrayList<>();
        }
        List<SegmentInfo> result = new ArrayList<>();
        Arrays.sort(
                children,
                new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName().compareTo(right.getName());
                    }
                });
        for (File child : children) {
            if (!child.isDirectory()
                    || !RecorderDateTime.isSegmentName(child.getName())) {
                continue;
            }
            result.add(segmentInfo(child, activeDirectory));
        }
        Collections.sort(
                result,
                new Comparator<SegmentInfo>() {
                    @Override
                    public int compare(SegmentInfo left, SegmentInfo right) {
                        return right.directory.getName().compareTo(
                                left.directory.getName());
                    }
                });
        Log.i(
                TAG,
                "Storage list: segments="
                        + result.size()
                        + " elapsedMs="
                        + elapsedMillis(startedNanos)
                        + " root="
                        + root);
        return result;
    }

    public StorageSnapshot snapshot(RecorderSettings settings) throws IOException {
        long startedNanos = System.nanoTime();
        File root = getRecorderRoot(settings);
        StatFs statFs = new StatFs(root.getAbsolutePath());
        long recorderBytes = 0L;
        long lockedBytes = 0L;
        for (SegmentInfo segment : listSegmentsWithoutSnapshot(root)) {
            recorderBytes += segment.sizeBytes;
            if (segment.locked) {
                lockedBytes += segment.sizeBytes;
            }
        }
        StorageSnapshot snapshot = new StorageSnapshot(
                root,
                statFs.getTotalBytes(),
                statFs.getAvailableBytes(),
                recorderBytes,
                lockedBytes);
        Log.i(
                TAG,
                "Storage snapshot: recorderBytes="
                        + recorderBytes
                        + " lockedBytes="
                        + lockedBytes
                        + " elapsedMs="
                        + elapsedMillis(startedNanos)
                        + " root="
                        + root);
        return snapshot;
    }

    public void setLocked(RecorderSettings settings, File segmentDirectory, boolean locked)
            throws IOException {
        File recorderRoot = getRecorderRoot(settings);
        validateOwnedSegment(recorderRoot, segmentDirectory);
        File marker = new File(segmentDirectory, "locked.marker");
        if (locked) {
            if (!marker.exists() && !marker.createNewFile()) {
                throw new IOException("Cannot create lock marker");
            }
        } else if (marker.exists() && !marker.delete()) {
            throw new IOException("Cannot remove lock marker");
        }
        synchronized (finalizedSegmentCache) {
            finalizedSegmentCache.remove(segmentDirectory.getAbsolutePath());
        }
    }

    public void deleteSegment(
            RecorderSettings settings,
            File segmentDirectory,
            File activeDirectory)
            throws IOException {
        File recorderRoot = getRecorderRoot(settings);
        validateOwnedSegment(recorderRoot, segmentDirectory);
        if (activeDirectory != null && activeDirectory.equals(segmentDirectory)) {
            throw new IOException("The active recording cannot be deleted");
        }
        if (new File(segmentDirectory, "locked.marker").isFile()) {
            throw new IOException(LOCKED_DELETE_MESSAGE);
        }
        deleteOwnedSegment(recorderRoot, segmentDirectory);
    }

    public static String formatBytes(long bytes) {
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private void deleteOwnedSegment(File recorderRoot, File segmentDirectory)
            throws IOException {
        validateOwnedSegment(recorderRoot, segmentDirectory);
        deleteRecursively(segmentDirectory);
        synchronized (finalizedSegmentCache) {
            finalizedSegmentCache.remove(segmentDirectory.getAbsolutePath());
        }
    }

    private void deleteRecursively(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        // A concurrent cleanup pass may have removed the entry already; only
        // a still-existing undeletable path is an error.
        if (!file.delete() && file.exists()) {
            throw new IOException("Cannot delete recorder-owned path: " + file);
        }
    }

    private boolean isDeletable(
            SegmentInfo segment,
            Set<File> protectedDirectories) {
        // Interrupted (incomplete) segments are recorder-owned; they are
        // reclaimable as long as they are neither locked nor protected (the
        // active recording or a segment being stitched). Locked data is never
        // removed automatically.
        return !segment.locked
                && !segment.active
                && !protectedDirectories.contains(segment.directory);
    }

    private List<SegmentInfo> listSegmentsWithoutSnapshot(File root) {
        File[] children = root.listFiles();
        List<SegmentInfo> result = new ArrayList<>();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (!child.isDirectory()
                    || !RecorderDateTime.isSegmentName(child.getName())) {
                continue;
            }
            result.add(segmentInfo(child, null));
        }
        return result;
    }

    private SegmentInfo segmentInfo(
            File directory,
            File activeDirectory) {
        boolean markerPresent =
                new File(directory, "recording.marker").isFile();
        boolean active =
                activeDirectory != null
                        && activeDirectory.equals(directory);
        boolean locked =
                new File(directory, "locked.marker").isFile();
        long modifiedAtMillis = directory.lastModified();
        String key = directory.getAbsolutePath();
        if (!active && !markerPresent) {
            synchronized (finalizedSegmentCache) {
                CachedSegment cached = finalizedSegmentCache.get(key);
                if (cached != null
                        && cached.directoryModifiedAtMillis
                                == modifiedAtMillis
                        && cached.segment.locked == locked) {
                    return cached.segment;
                }
            }
        }
        SegmentInfo segment = new SegmentInfo(
                directory,
                sizeOf(directory),
                modifiedAtMillis,
                locked,
                active,
                markerPresent && !active);
        if (!active && !markerPresent) {
            synchronized (finalizedSegmentCache) {
                finalizedSegmentCache.put(
                        key,
                        new CachedSegment(
                                modifiedAtMillis,
                                segment));
            }
        }
        return segment;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private long sizeOf(File file) {
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    private void validateOwnedSegment(File recorderRoot, File segmentDirectory)
            throws IOException {
        String rootPath = recorderRoot.getCanonicalPath();
        String segmentPath = segmentDirectory.getCanonicalPath();
        File parent = segmentDirectory.getCanonicalFile().getParentFile();
        if (parent == null
                || !parent.getCanonicalPath().equals(rootPath)
                || !RecorderDateTime.isSegmentName(segmentDirectory.getName())
                || !segmentPath.startsWith(rootPath + File.separator)) {
            throw new IOException("Refusing path outside recorder segment root: "
                    + segmentDirectory);
        }
    }
}
