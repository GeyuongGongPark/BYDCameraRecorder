package com.ggpark.byddashcam;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encodes one continuous H.264 stream and writes every sample to two
 * destinations at once: the final segment file (written under a .live name
 * and renamed into place on a clean stop, so closing a segment is instant)
 * and a sequence of short, individually finalized MP4 chunk files. The codec
 * never restarts between chunks; only the chunk muxer rotates, at a sync
 * frame, so every closed chunk is independently playable. After a process
 * kill the unplayable .live file is discarded and SegmentStitcher rebuilds
 * the final video from the chunks, losing at most the chunk that was open.
 */
public final class AvcMp4Encoder {
    public static final String LIVE_FILE_SUFFIX = ".live";
    private static final String MIME_TYPE = "video/avc";
    private static final String TAG = "BYDCamera";
    private static final long TIMEOUT_US = 10_000L;
    private static final long STALL_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private final byte[] codecInput;
    private final int frameRate;
    private final File partsDirectory;
    private final File finalFile;
    private final File liveFile;
    private final int streamIndex;
    private final String logLabel;
    private MediaCodec codec;
    private MediaMuxer muxer;
    private MediaMuxer liveMuxer;
    private boolean muxerStarted;
    private int trackIndex = -1;
    private int liveTrackIndex = -1;
    private int chunkIndex;
    private MediaFormat outputFormat;
    private boolean chunkRotationRequested;
    private long lastPresentationUs;
    private boolean stopped;
    private boolean liveWriteFailed;
    private long stalledFrameCount;
    private long lastStallLogNanos;

    public AvcMp4Encoder(
            File partsDirectory,
            File finalFile,
            int streamIndex,
            String logLabel,
            int width,
            int height,
            int bitrate,
            int frameRate) throws IOException {
        this.frameRate = frameRate;
        this.partsDirectory = partsDirectory;
        this.finalFile = finalFile;
        this.liveFile = new File(finalFile.getPath() + LIVE_FILE_SUFFIX);
        this.streamIndex = streamIndex;
        this.logLabel = logLabel;
        codecInput = new byte[width * height * 3 / 2];
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createEncoderByType(MIME_TYPE);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        Log.i(
                TAG,
                "Encoder configured: file="
                        + logLabel
                        + " size="
                        + width
                        + "x"
                        + height
                        + " bitrate="
                        + bitrate
                        + " fps="
                        + frameRate
                        + " mime="
                        + MIME_TYPE);
    }

    public boolean encodeNv21(byte[] nv21, long presentationTimeUs) {
        if (stopped || nv21.length != codecInput.length) {
            return false;
        }
        int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex < 0) {
            stalledFrameCount++;
            long nowNanos = System.nanoTime();
            if (lastStallLogNanos == 0L
                    || nowNanos - lastStallLogNanos
                            >= STALL_LOG_INTERVAL_NANOS) {
                lastStallLogNanos = nowNanos;
                Log.w(
                        TAG,
                        "Encoder input stalled: file="
                                + logLabel
                                + " framesDroppedTotal="
                                + stalledFrameCount);
            }
            drain(false);
            return false;
        }
        convertNv21ToNv12(nv21, codecInput);
        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
        if (inputBuffer == null || inputBuffer.capacity() < codecInput.length) {
            throw new IllegalStateException("Encoder input buffer is unavailable or too small");
        }
        inputBuffer.clear();
        inputBuffer.put(codecInput);
        long safePresentationUs = Math.max(lastPresentationUs + 1L, presentationTimeUs);
        lastPresentationUs = safePresentationUs;
        codec.queueInputBuffer(
                inputIndex,
                0,
                codecInput.length,
                safePresentationUs,
                0);
        drain(false);
        return true;
    }

    /**
     * Asks the encoder to close the current chunk file and continue in a new
     * one. The switch happens at the next sync frame so the new chunk starts
     * decodable; encoding itself is uninterrupted.
     */
    public void requestChunkRotation() {
        chunkRotationRequested = true;
    }

    /**
     * Stops the encoder, closes both outputs, and moves the live file into
     * its final name. Returns true when the final video is complete and in
     * place; on false the caller must fall back to stitching the chunks.
     */
    public boolean stop() {
        if (stopped) {
            return false;
        }
        stopped = true;
        try {
            int inputIndex = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inputIndex >= 0) {
                long endTimeUs =
                        lastPresentationUs + Math.max(1L, 1_000_000L / frameRate);
                codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        endTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                drain(true);
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // Preserve cleanup of the remaining codec and muxer resources.
                }
                codec.release();
                codec = null;
            }
            closeCurrentMuxer();
        }
        boolean liveClosed = closeLiveMuxer();
        if (liveClosed && !liveWriteFailed && liveFile.isFile()) {
            if (liveFile.renameTo(finalFile)) {
                return true;
            }
            Log.w(
                    TAG,
                    "Cannot move finished video into place: " + finalFile);
        }
        if (liveFile.exists() && !liveFile.delete()) {
            Log.w(TAG, "Stale live video left behind: " + liveFile);
        }
        return false;
    }

    private void convertNv21ToNv12(byte[] source, byte[] destination) {
        int ySize = source.length * 2 / 3;
        System.arraycopy(source, 0, destination, 0, ySize);
        for (int index = ySize; index + 1 < source.length; index += 2) {
            destination[index] = source[index + 1];
            destination[index + 1] = source[index];
        }
    }

    private void openNextChunkMuxer() {
        if (outputFormat == null) {
            throw new IllegalStateException(
                    "Encoder output format is not available yet");
        }
        File chunkFile = new File(
                partsDirectory,
                SegmentStitcher.chunkName(streamIndex, chunkIndex));
        chunkIndex++;
        try {
            muxer = new MediaMuxer(
                    chunkFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cannot open chunk muxer: " + chunkFile, exception);
        }
        trackIndex = muxer.addTrack(outputFormat);
        muxer.start();
        muxerStarted = true;
    }

    private void closeCurrentMuxer() {
        if (muxer == null) {
            return;
        }
        if (muxerStarted) {
            try {
                muxer.stop();
            } catch (RuntimeException ignored) {
                // An interrupted stream may not have a complete MP4 footer.
            }
        }
        muxer.release();
        muxer = null;
        muxerStarted = false;
        trackIndex = -1;
    }

    private void openLiveMuxer() {
        try {
            if (liveFile.exists() && !liveFile.delete()) {
                throw new IOException(
                        "Cannot remove stale live file: " + liveFile);
            }
            liveMuxer = new MediaMuxer(
                    liveFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            liveTrackIndex = liveMuxer.addTrack(outputFormat);
            liveMuxer.start();
        } catch (IOException | RuntimeException exception) {
            // The chunk stream remains authoritative; recovery stitches the
            // final video when the direct copy cannot be produced.
            Log.w(TAG, "Direct segment file unavailable: " + liveFile, exception);
            liveWriteFailed = true;
            releaseLiveMuxer(false);
        }
    }

    private boolean closeLiveMuxer() {
        if (liveMuxer == null) {
            return false;
        }
        return releaseLiveMuxer(true);
    }

    private boolean releaseLiveMuxer(boolean finish) {
        MediaMuxer closing = liveMuxer;
        liveMuxer = null;
        liveTrackIndex = -1;
        if (closing == null) {
            return false;
        }
        boolean finished = false;
        if (finish) {
            try {
                closing.stop();
                finished = true;
            } catch (RuntimeException exception) {
                Log.w(TAG, "Live muxer stop failed: " + logLabel, exception);
            }
        }
        closing.release();
        return finished;
    }

    private void drain(boolean endOfStream) {
        int idleAttempts = 0;
        while (true) {
            int outputIndex = codec.dequeueOutputBuffer(
                    bufferInfo,
                    endOfStream ? TIMEOUT_US : 0L);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idleAttempts >= 20) {
                    return;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputFormat != null) {
                    throw new IllegalStateException("Encoder output format changed twice");
                }
                outputFormat = codec.getOutputFormat();
                Log.i(TAG, "Encoder output format: " + outputFormat);
                openNextChunkMuxer();
                openLiveMuxer();
            } else if (outputIndex >= 0) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                if (outputBuffer == null) {
                    throw new IllegalStateException("Encoder output buffer is unavailable");
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }
                if (bufferInfo.size > 0) {
                    if (!muxerStarted) {
                        throw new IllegalStateException(
                                "Encoded output arrived before muxer format");
                    }
                    boolean syncFrame =
                            (bufferInfo.flags
                                    & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (chunkRotationRequested && syncFrame) {
                        chunkRotationRequested = false;
                        closeCurrentMuxer();
                        openNextChunkMuxer();
                    }
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                    if (liveMuxer != null) {
                        try {
                            outputBuffer.position(bufferInfo.offset);
                            outputBuffer.limit(
                                    bufferInfo.offset + bufferInfo.size);
                            liveMuxer.writeSampleData(
                                    liveTrackIndex,
                                    outputBuffer,
                                    bufferInfo);
                        } catch (RuntimeException exception) {
                            Log.w(
                                    TAG,
                                    "Direct segment write failed; chunks "
                                            + "remain authoritative: "
                                            + logLabel,
                                    exception);
                            liveWriteFailed = true;
                            releaseLiveMuxer(false);
                        }
                    }
                }
                boolean reachedEnd =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                codec.releaseOutputBuffer(outputIndex, false);
                idleAttempts = 0;
                if (reachedEnd) {
                    return;
                }
            }
        }
    }

}
