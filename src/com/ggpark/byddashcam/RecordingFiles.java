package com.ggpark.byddashcam;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class RecordingFiles {
    public static final String COMBINED_VIDEO = "combined.mp4";
    private static final String INDEX_FILE = ".recording-files.properties";
    private static final String MP4_SUFFIX = ".mp4";

    public static final class Layout {
        public final String[] cameraVideos;
        public final String combinedVideo;

        private Layout(String[] cameraVideos, String combinedVideo) {
            this.cameraVideos = cameraVideos;
            this.combinedVideo = combinedVideo;
        }
    }

    private RecordingFiles() {
    }

    public static Layout createLayout(
            File segmentDirectory,
            String[] cameraNames) throws IOException {
        String timestamp = segmentTimestamp(segmentDirectory.getName());
        String[] cameraVideos = new String[FrameProcessor.CAMERA_COUNT];
        Set<String> usedNames = new LinkedHashSet<>();
        usedNames.add(("Combined_" + timestamp + MP4_SUFFIX)
                .toLowerCase(Locale.US));
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            String requested =
                    cameraNames != null && index < cameraNames.length
                            ? cameraNames[index]
                            : null;
            String baseName = safeCameraBaseName(requested, index);
            String fileName = baseName + "_" + timestamp + MP4_SUFFIX;
            int duplicateSuffix = 2;
            while (!usedNames.add(fileName.toLowerCase(Locale.US))) {
                fileName =
                        baseName
                                + "-"
                                + duplicateSuffix
                                + "_"
                                + timestamp
                                + MP4_SUFFIX;
                duplicateSuffix++;
            }
            cameraVideos[index] = fileName;
        }
        Layout layout =
                new Layout(
                        cameraVideos,
                        "Combined_" + timestamp + MP4_SUFFIX);
        writeIndex(segmentDirectory, layout);
        return layout;
    }

    /**
     * Returns the file layout recorded in the segment's index, or null when
     * the index is missing or invalid. Unlike the resolver methods below this
     * does not require the files to exist yet, so it names the outputs a
     * stitch pass must produce.
     */
    public static Layout indexedLayout(File segmentDirectory) {
        return readIndex(segmentDirectory);
    }

    public static String cameraVideo(int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            throw new IllegalArgumentException("Camera index is out of range");
        }
        return "camera-" + (cameraIndex + 1) + ".mp4";
    }

    public static String[] videoNames() {
        String[] names = new String[FrameProcessor.CAMERA_COUNT + 1];
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            names[index] = cameraVideo(index);
        }
        names[FrameProcessor.CAMERA_COUNT] = COMBINED_VIDEO;
        return names;
    }

    public static File cameraVideoFile(
            File segmentDirectory,
            int cameraIndex) {
        if (cameraIndex < 0 || cameraIndex >= FrameProcessor.CAMERA_COUNT) {
            throw new IllegalArgumentException("Camera index is out of range");
        }
        Layout indexed = readIndex(segmentDirectory);
        if (indexed != null) {
            File indexedFile =
                    validChild(
                            segmentDirectory,
                            indexed.cameraVideos[cameraIndex]);
            if (indexedFile != null && indexedFile.isFile()) {
                return indexedFile;
            }
        }
        File legacy =
                new File(
                        segmentDirectory,
                        cameraVideo(cameraIndex));
        if (legacy.isFile()) {
            return legacy;
        }
        List<File> inferred = inferredCameraVideos(segmentDirectory);
        return cameraIndex < inferred.size()
                ? inferred.get(cameraIndex)
                : legacy;
    }

    public static File combinedVideoFile(File segmentDirectory) {
        Layout indexed = readIndex(segmentDirectory);
        if (indexed != null) {
            File indexedFile =
                    validChild(
                            segmentDirectory,
                            indexed.combinedVideo);
            if (indexedFile != null && indexedFile.isFile()) {
                return indexedFile;
            }
        }
        File legacy =
                new File(segmentDirectory, COMBINED_VIDEO);
        if (legacy.isFile()) {
            return legacy;
        }
        File[] children = segmentDirectory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isFile()
                        && isCombinedVideoName(child.getName())) {
                    return child;
                }
            }
        }
        return legacy;
    }

    public static List<File> listVideos(File segmentDirectory) {
        List<File> videos = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            addVideo(
                    videos,
                    seenPaths,
                    cameraVideoFile(segmentDirectory, index));
        }
        addVideo(
                videos,
                seenPaths,
                combinedVideoFile(segmentDirectory));
        File[] children = segmentDirectory.listFiles();
        if (children != null) {
            List<File> remaining = new ArrayList<>();
            for (File child : children) {
                if (child.isFile() && isVideoName(child.getName())) {
                    remaining.add(child);
                }
            }
            Collections.sort(
                    remaining,
                    new Comparator<File>() {
                        @Override
                        public int compare(File left, File right) {
                            return left.getName()
                                    .compareToIgnoreCase(right.getName());
                        }
                    });
            for (File file : remaining) {
                addVideo(videos, seenPaths, file);
            }
        }
        return videos;
    }

    public static boolean isVideoName(String name) {
        return name != null
                && !name.isEmpty()
                && !".".equals(name)
                && !"..".equals(name)
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.toLowerCase(Locale.US).endsWith(MP4_SUFFIX);
    }

    private static void addVideo(
            List<File> videos,
            Set<String> seenPaths,
            File file) {
        if (file != null
                && file.isFile()
                && file.length() > 0L
                && seenPaths.add(file.getAbsolutePath())) {
            videos.add(file);
        }
    }

    private static List<File> inferredCameraVideos(
            File segmentDirectory) {
        List<File> cameras = new ArrayList<>();
        File[] children = segmentDirectory.listFiles();
        if (children == null) {
            return cameras;
        }
        for (File child : children) {
            if (child.isFile()
                    && isVideoName(child.getName())
                    && !isCombinedVideoName(child.getName())) {
                cameras.add(child);
            }
        }
        Collections.sort(
                cameras,
                new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName()
                                .compareToIgnoreCase(right.getName());
                    }
                });
        return cameras;
    }

    private static boolean isCombinedVideoName(String name) {
        String normalized =
                name == null ? "" : name.toLowerCase(Locale.US);
        return COMBINED_VIDEO.equals(normalized)
                || (normalized.startsWith("combined_")
                        && normalized.endsWith(MP4_SUFFIX));
    }

    private static Layout readIndex(File segmentDirectory) {
        Properties properties = new Properties();
        File indexFile = new File(segmentDirectory, INDEX_FILE);
        if (!indexFile.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(indexFile)) {
            properties.load(input);
        } catch (IOException exception) {
            return null;
        }
        String[] cameraVideos =
                new String[FrameProcessor.CAMERA_COUNT];
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            String fileName =
                    properties.getProperty(
                            "camera." + (index + 1));
            if (!isVideoName(fileName)) {
                return null;
            }
            cameraVideos[index] = fileName;
        }
        String combinedVideo =
                properties.getProperty("combined");
        return isVideoName(combinedVideo)
                ? new Layout(cameraVideos, combinedVideo)
                : null;
    }

    private static String safeCameraBaseName(
            String requested,
            int cameraIndex) {
        String fallback = "Camera-" + (cameraIndex + 1);
        if (requested == null) {
            return fallback;
        }
        String candidate = requested.trim();
        if (candidate.isEmpty()
                || ".".equals(candidate)
                || "..".equals(candidate)
                || "Combined".equalsIgnoreCase(candidate)
                || candidate.endsWith(".")
                || candidate.endsWith(" ")) {
            return fallback;
        }
        for (int index = 0; index < candidate.length(); index++) {
            char character = candidate.charAt(index);
            if (character < 32
                    || character == 127
                    || character == '<'
                    || character == '>'
                    || character == ':'
                    || character == '"'
                    || character == '/'
                    || character == '\\'
                    || character == '|'
                    || character == '?'
                    || character == '*') {
                return fallback;
            }
        }
        return candidate;
    }

    private static String segmentTimestamp(String segmentName) {
        if (segmentName != null
                && segmentName.length() >= 19
                && RecorderDateTime.isSegmentName(segmentName)) {
            return segmentName.substring(0, 19);
        }
        return RecorderDateTime.formatDirectoryTimestamp(
                new java.util.Date());
    }

    private static File validChild(
            File segmentDirectory,
            String fileName) {
        if (!isVideoName(fileName)) {
            return null;
        }
        try {
            File directory = segmentDirectory.getCanonicalFile();
            File child =
                    new File(directory, fileName).getCanonicalFile();
            return child.getParentFile() != null
                            && child.getParentFile().equals(directory)
                    ? child
                    : null;
        } catch (IOException exception) {
            return null;
        }
    }

    private static void writeIndex(
            File segmentDirectory,
            Layout layout) throws IOException {
        Properties properties = new Properties();
        for (int index = 0; index < FrameProcessor.CAMERA_COUNT; index++) {
            properties.setProperty(
                    "camera." + (index + 1),
                    layout.cameraVideos[index]);
        }
        properties.setProperty("combined", layout.combinedVideo);
        try (FileOutputStream output =
                     new FileOutputStream(
                             new File(segmentDirectory, INDEX_FILE))) {
            properties.store(
                    output,
                    "BYD Camera Recorder segment files");
        }
    }
}
