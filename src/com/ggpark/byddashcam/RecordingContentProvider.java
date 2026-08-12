package com.ggpark.byddashcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public final class RecordingContentProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ggpark.byddashcam.recordings";
    public static Uri uriFor(File file, File[] volumeRoots) throws IOException {
        File canonical = file.getCanonicalFile();
        for (int index = 0; index < volumeRoots.length; index++) {
            File volumeRoot = volumeRoots[index];
            if (volumeRoot == null) {
                continue;
            }
            File recordingsRoot =
                    new File(volumeRoot, "BYDCamera/recordings").getCanonicalFile();
            File segment = canonical.isDirectory()
                    ? canonical
                    : canonical.getParentFile();
            if (segment == null
                    || segment.getParentFile() == null
                    || !segment.getParentFile().getCanonicalFile().equals(recordingsRoot)
                    || !RecorderDateTime.isSegmentName(segment.getName())) {
                continue;
            }
            Uri.Builder builder = new Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .appendPath("volume")
                    .appendPath(String.valueOf(index))
                    .appendPath(segment.getName());
            if (canonical.isFile()) {
                if (!RecordingFiles.isVideoName(canonical.getName())) {
                    throw new IOException("Only recorder MP4 files may be shared");
                }
                builder.appendPath(canonical.getName());
            }
            return builder.build();
        }
        throw new IOException("Recording path is outside app-owned volumes");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        try {
            return resolve(uri).isDirectory() ? "resource/folder" : "video/mp4";
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Recordings are shared read-only");
        }
        File file = resolve(uri);
        if (!file.isFile()) {
            throw new FileNotFoundException("Folder URIs cannot be opened as files");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                    row.add(file.getName());
                } else if (OpenableColumns.SIZE.equals(column)) {
                    row.add(file.isFile() ? file.length() : null);
                } else {
                    row.add(null);
                }
            }
            return cursor;
        } catch (FileNotFoundException exception) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Recordings are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Recordings are read-only");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Recordings are read-only");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("Unexpected provider authority");
        }
        List<String> parts = uri.getPathSegments();
        if (parts.size() < 3
                || parts.size() > 4
                || !"volume".equals(parts.get(0))
                || !RecorderDateTime.isSegmentName(parts.get(2))) {
            throw new FileNotFoundException("Invalid recording URI");
        }
        int volumeIndex;
        try {
            volumeIndex = Integer.parseInt(parts.get(1));
        } catch (NumberFormatException exception) {
            throw new FileNotFoundException("Invalid volume index");
        }
        File[] volumes = getContext().getExternalFilesDirs(null);
        if (volumes == null
                || volumeIndex < 0
                || volumeIndex >= volumes.length
                || volumes[volumeIndex] == null) {
            throw new FileNotFoundException("Storage volume is unavailable");
        }
        try {
            File root =
                    new File(volumes[volumeIndex], "BYDCamera/recordings")
                            .getCanonicalFile();
            File segment = new File(root, parts.get(2)).getCanonicalFile();
            if (segment.getParentFile() == null
                    || !segment.getParentFile().equals(root)
                    || !segment.isDirectory()) {
                throw new FileNotFoundException("Segment is unavailable");
            }
            if (parts.size() == 3) {
                return segment;
            }
            String videoName = parts.get(3);
            if (!RecordingFiles.isVideoName(videoName)) {
                throw new FileNotFoundException("Invalid video name");
            }
            File video = new File(segment, videoName).getCanonicalFile();
            if (video.getParentFile() == null
                    || !video.getParentFile().equals(segment)
                    || !video.isFile()) {
                throw new FileNotFoundException("Video is unavailable");
            }
            return video;
        } catch (IOException exception) {
            FileNotFoundException notFound =
                    new FileNotFoundException("Cannot resolve recording URI");
            notFound.initCause(exception);
            throw notFound;
        }
    }
}
