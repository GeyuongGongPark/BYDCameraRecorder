package com.ggpark.byddashcam;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SegmentViewerActivity extends Activity {
    public static final String EXTRA_SEGMENT_PATH = "segment_path";
    private static final String TAG = "BYDCamera";
    private IconButton resetZoomButton;
    private PinchPanController videoZoomController;
    private VideoView videoView;
    private TextView selectedDetails;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String requestedPath = getIntent().getStringExtra(EXTRA_SEGMENT_PATH);
        File segment = requestedPath == null ? null : new File(requestedPath);
        if (!isOwnedSegment(segment)) {
            finish();
            return;
        }
        setContentView(buildContent(segment));
        List<File> videos = listVideos(segment);
        if (!videos.isEmpty()) {
            play(videos.get(0));
        }
    }

    @Override
    protected void onStop() {
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onStop();
    }

    private View buildContent(File segment) {
        FrameLayout screen = new FrameLayout(this);
        LinearLayout root = horizontal();
        root.setBackgroundColor(Color.rgb(8, 17, 31));
        root.setPadding(dp(18), dp(14), dp(18), dp(14));

        LinearLayout playerPanel = vertical();
        playerPanel.setBackgroundResource(R.drawable.panel_background);
        playerPanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView title = text(
                RecorderDateTime.formatSegmentName(
                        segment.getName(),
                        RecorderSettings.load(this).dateFormat),
                24,
                true);
        playerPanel.addView(title);

        selectedDetails = text("Select a recording", 14, false);
        selectedDetails.setTextColor(Color.rgb(175, 192, 216));
        playerPanel.addView(selectedDetails);

        FrameLayout videoContainer = new FrameLayout(this);
        videoContainer.setClipChildren(true);
        videoView = new VideoView(this);
        videoView.setBackgroundColor(Color.TRANSPARENT);
        MediaController mediaController = new MediaController(this);
        mediaController.setAnchorView(videoView);
        videoView.setMediaController(mediaController);
        videoZoomController =
                new PinchPanController(videoView);
        videoZoomController.setListener(
                new PinchPanController.Listener() {
                    @Override
                    public void onScaleChanged(float scale) {
                        if (resetZoomButton != null) {
                            resetZoomButton.setVisibility(
                                    scale > 1.01f
                                            ? View.VISIBLE
                                            : View.GONE);
                        }
                    }
                });
        videoView.setOnTouchListener(
                new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View view, MotionEvent event) {
                        return videoZoomController.handleTouch(event);
                    }
                });
        videoContainer.addView(
                videoView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        resetZoomButton = new IconButton(
                this,
                R.drawable.ic_zoom_reset,
                "Reset video zoom",
                IconButton.Tone.DEFAULT);
        resetZoomButton.setVisibility(View.GONE);
        resetZoomButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        resetVideoZoom();
                    }
                });
        FrameLayout.LayoutParams resetZoomParams =
                new FrameLayout.LayoutParams(dp(52), dp(52));
        resetZoomParams.gravity = Gravity.TOP | Gravity.END;
        resetZoomParams.setMargins(0, dp(10), dp(10), 0);
        videoContainer.addView(resetZoomButton, resetZoomParams);
        playerPanel.addView(
                videoContainer,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        root.addView(
                playerPanel,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        3f));

        LinearLayout filePanel = vertical();
        filePanel.setPadding(dp(18), 0, 0, 0);
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("Recording files", 20, true);
        header.addView(
                heading,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        final File selectedSegment = segment;
        header.addView(
                iconButton(
                        R.drawable.ic_copy_path,
                        "Copy recording folder path",
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                copySegmentPath(selectedSegment);
                            }
                        }),
                compactButtonParams());
        header.addView(
                iconButton(
                        R.drawable.ic_share,
                        "Share all finalized recordings",
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                shareAllVideos(selectedSegment);
                            }
                        }),
                compactButtonParams(dp(8)));
        header.addView(
                iconButton(
                        R.drawable.ic_transfer,
                        "Transfer recordings to a phone",
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                startTransfer(selectedSegment);
                            }
                        }),
                compactButtonParams(dp(8)));
        header.addView(
                iconButton(
                        R.drawable.ic_back,
                        "Back to recordings",
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                finish();
                            }
                        }),
                compactButtonParams(dp(8)));
        filePanel.addView(header);

        ScrollView fileScroll = new ScrollView(this);
        fileScroll.setFillViewport(true);
        LinearLayout fileRows = vertical();
        fileRows.setPadding(0, dp(10), 0, dp(10));
        for (final File video : listVideos(segment)) {
            LinearLayout fileRow = horizontal();
            fileRow.setGravity(Gravity.CENTER_VERTICAL);
            fileRow.setBackgroundResource(R.drawable.card_background);
            fileRow.setPadding(dp(10), dp(8), dp(10), dp(8));
            fileRow.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            play(video);
                        }
                    });
            fileRow.addView(
                    iconButton(
                            R.drawable.ic_play,
                            "Play " + video.getName(),
                            IconButton.Tone.DEFAULT,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    play(video);
                                }
                            }),
                    compactButtonParams());

            TextView details = text(
                    video.getName()
                            + "\n"
                            + StorageRepository.formatBytes(video.length()),
                    14,
                    false);
            details.setTextColor(Color.rgb(175, 192, 216));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f);
            detailParams.leftMargin = dp(12);
            fileRow.addView(details, detailParams);

            fileRow.addView(
                    iconButton(
                            R.drawable.ic_open_external,
                            "Open " + video.getName() + " in another app",
                            IconButton.Tone.DEFAULT,
                            new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    openVideoExternally(video);
                                }
                            }),
                    compactButtonParams());
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            fileRows.addView(fileRow, rowParams);
        }
        fileScroll.addView(fileRows);
        filePanel.addView(
                fileScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        root.addView(
                filePanel,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        2f));
        screen.addView(
                root,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        return screen;
    }

    private void startTransfer(File segment) {
        startActivity(new Intent(this, PhoneAccessActivity.class));
    }

    private boolean isOwnedSegment(File segment) {
        if (segment == null || !segment.isDirectory()) {
            return false;
        }
        try {
            String segmentPath = segment.getCanonicalPath();
            File[] volumeRoots = getExternalFilesDirs(null);
            if (volumeRoots == null) {
                return false;
            }
            for (File volumeRoot : volumeRoots) {
                if (volumeRoot == null) {
                    continue;
                }
                File recorderRoot = new File(volumeRoot, "BYDCamera/recordings");
                String recorderPath = recorderRoot.getCanonicalPath();
                File parent = segment.getCanonicalFile().getParentFile();
                if (parent != null
                        && parent.getCanonicalPath().equals(recorderPath)
                        && segmentPath.startsWith(recorderPath + File.separator)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private List<File> listVideos(File segment) {
        return RecordingFiles.listVideos(segment);
    }

    private void play(final File video) {
        resetVideoZoom();
        String details = video.getName()
                + " — "
                + StorageRepository.formatBytes(video.length());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            String width = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (width != null && height != null && duration != null) {
                details += String.format(
                        Locale.US,
                        " — %sx%s — %.1f seconds",
                        width,
                        height,
                        Long.parseLong(duration) / 1000.0);
            }
        } catch (RuntimeException ignored) {
            details += " — metadata unavailable";
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        selectedDetails.setText(details);
        videoView.setVideoURI(Uri.fromFile(video));
        videoView.setOnPreparedListener(
                new MediaPlayer.OnPreparedListener() {
                    @Override
                    public void onPrepared(MediaPlayer mediaPlayer) {
                        mediaPlayer.setLooping(false);
                        videoView.seekTo(100);
                        videoView.start();
                    }
                });
        videoView.setOnErrorListener(
                new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                        Log.e(
                                TAG,
                                "Segment playback failed: what="
                                        + what
                                        + " extra="
                                        + extra
                                        + " file="
                                        + video.getName());
                        showMessage("This recording could not be played");
                        return true;
                    }
                });
        videoView.start();
    }

    private void resetVideoZoom() {
        if (videoZoomController != null) {
            videoZoomController.reset();
        }
    }

    private void copySegmentPath(File segment) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            showMessage("The system clipboard is unavailable");
            return;
        }
        clipboard.setPrimaryClip(
                ClipData.newPlainText(
                        "Recording folder",
                        segment.getAbsolutePath()));
        showMessage(
                "Recording path copied. Paste it into BYD Files.");
    }

    private void openVideoExternally(File video) {
        try {
            Uri uri = RecordingContentProvider.uriFor(
                    video,
                    getExternalFilesDirs(null));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open recording with"));
        } catch (IOException | ActivityNotFoundException exception) {
            showMessage("No compatible external video application is installed");
        }
    }

    private void shareAllVideos(File segment) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File video : listVideos(segment)) {
                uris.add(RecordingContentProvider.uriFor(
                        video,
                        getExternalFilesDirs(null)));
            }
            Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            intent.setType("video/mp4");
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!hasActivityFor(intent)) {
                showMessage(
                        "No installed application can receive multiple videos");
                return;
            }
            startActivity(
                    Intent.createChooser(
                            intent,
                            "Share recordings"));
        } catch (IOException | ActivityNotFoundException exception) {
            showMessage("No compatible application can receive the recordings");
        }
    }

    private boolean hasActivityFor(Intent intent) {
        return !getPackageManager()
                .queryIntentActivities(
                        intent,
                        PackageManager.MATCH_DEFAULT_ONLY)
                .isEmpty();
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        return compactButtonParams(0);
    }

    private LinearLayout.LayoutParams compactButtonParams(int leftMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(50), dp(50));
        params.leftMargin = leftMargin;
        return params;
    }

    private IconButton iconButton(
            int drawableId,
            String accessibilityLabel,
            IconButton.Tone tone,
            View.OnClickListener listener) {
        IconButton button =
                new IconButton(this, drawableId, accessibilityLabel, tone);
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.WHITE);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }
}
