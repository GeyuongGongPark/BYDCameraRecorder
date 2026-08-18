package com.ggpark.byddashcam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.TextureView;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity
        implements CameraRecorderService.UiListener {
    public static final String EXTRA_SHOW_BACKGROUND_ACCESS =
            "show_background_access";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int LOCATION_PERMISSION_REQUEST = 101;
    private static final String TAG = "BYDCamera";
    private static final long GIBIBYTE_BYTES =
            1024L * 1024L * 1024L;
    private static final int RECORDING_ROW_HEIGHT_DP = 150;
    private static final int RECORDING_ROW_BUFFER = 3;
    private static final long PREVIEW_METRICS_WINDOW_MS = 5000L;
    private static final long NATIVE_RENDER_STALE_MS = 2000L;
    private static final long NATIVE_RENDER_KICK_DELAY_MS = 3000L;
    private static final long NATIVE_PREVIEW_WATCHDOG_INTERVAL_MS = 1000L;
    private static final int STORAGE_AUTO_REFRESH_TICKS = 10;
    private static final long FULLSCREEN_BAR_AUTO_HIDE_MS = 3000L;
    private static final long FULLSCREEN_BAR_FADE_MS = 200L;

    private final EditText[] cameraNameInputs =
            new EditText[FrameProcessor.CAMERA_COUNT];
    private final ImageView[] fisheyePreviewViews =
            new ImageView[FrameProcessor.CAMERA_COUNT];
    private final TextView[] fisheyePreviewNameViews =
            new TextView[FrameProcessor.CAMERA_COUNT];
    private final TextView[] cameraNameViews =
            new TextView[FrameProcessor.CAMERA_COUNT];
    private final TextureView[] directPreviewViews =
            new TextureView[FrameProcessor.CAMERA_COUNT];
    private final int[] directPreviewCameraIndexes =
            new int[]{-1, -1, -1, -1};
    private final long[] directPreviewFrameCounts =
            new long[FrameProcessor.CAMERA_COUNT];
    private final long[] directPreviewMetricsStartedAt =
            new long[FrameProcessor.CAMERA_COUNT];
    private final boolean[] directPreviewHasFrame =
            new boolean[FrameProcessor.CAMERA_COUNT];
    private final long[] directPreviewLastFrameAtMs =
            new long[FrameProcessor.CAMERA_COUNT];
    private long fullscreenDirectLastFrameAtMs;
    private long lastNativeAttachAtMs;
    private Boolean lastReportedBitmapPreviewRequired;
    private final Handler nativePreviewWatchdogHandler =
            new Handler(Looper.getMainLooper());
    private final Runnable nativePreviewWatchdog =
            new Runnable() {
                @Override
                public void run() {
                    updateCarBitmapPreviewRequirement();
                    updateNativePreviewAlpha();
                    maybeKickNativePreviewRender();
                    watchdogTickCount++;
                    if (watchdogTickCount % STORAGE_AUTO_REFRESH_TICKS == 0) {
                        // Ambient refresh so storage counters and segment
                        // states stay current between service events; the
                        // segment fingerprint skips the list rebuild when
                        // nothing visible changed.
                        refreshStorage();
                    }
                    nativePreviewWatchdogHandler.postDelayed(
                            this,
                            NATIVE_PREVIEW_WATCHDOG_INTERVAL_MS);
                }
            };
    private final IconStateToggle[] cameraHorizontalFlipToggles =
            new IconStateToggle[FrameProcessor.CAMERA_COUNT];
    private final TextView[] cameraOrientationNameViews =
            new TextView[FrameProcessor.CAMERA_COUNT];
    private final IconStateToggle[] cameraVerticalFlipToggles =
            new IconStateToggle[FrameProcessor.CAMERA_COUNT];
    private final TextView[] combinedLayoutTiles =
            new TextView[FrameProcessor.CAMERA_COUNT];
    private final float[][] combinedDragCornerGeometry =
            new float[FrameProcessor.CAMERA_COUNT][4];
    private float combinedDragCenterOffsetX;
    private float combinedDragCenterOffsetY;
    private int combinedDragTargetPosition = -1;
    private final ImageView[] previewViews =
            new ImageView[FrameProcessor.CAMERA_COUNT];
    private IconButton backgroundAccessButton;
    private boolean backgroundAutomaticPromptHandled;
    private TextView backgroundSettingsStatusView;
    private Bitmap[] displayedPreviewFrames;
    private final ExecutorService segmentPreviewExecutor =
            Executors.newSingleThreadExecutor();
    private FrameLayout cameraOverlay;
    private LinearLayout controlsColumn;
    private boolean controlsColumnAnimating;
    private boolean controlsColumnCollapsed;
    private IconButton controlsVisibilityButton;
    private CameraRecorderService recorderService;
    private ZoomImageView fullscreenCamera;
    private ZoomTextureView fullscreenDirectCamera;
    private int fullscreenDirectCameraIndex = -1;
    private long fullscreenDirectFrameCount;
    private long fullscreenDirectMetricsStartedAt;
    private TextView fullscreenTitle;
    private LinearLayout fullscreenTopBar;
    private final Handler fullscreenBarHandler =
            new Handler(Looper.getMainLooper());
    private final Runnable fullscreenTopBarAutoHide =
            new Runnable() {
                @Override
                public void run() {
                    hideFullscreenTopBar();
                }
            };
    private IconButton resetZoomButton;
    private IconButton settingsSaveButton;
    private IconStateToggle recordingToggle;
    private IconStateToggle parkingToggle;
    private int selectedCameraIndex = -1;
    private boolean serviceBound;
    private TextView stateView;
    private TextView storageAvailableView;
    private TextView storageLockedView;
    private TextView storageLocationView;
    private final TextView[] storagePolicyValueViews =
            new TextView[6];
    private TextView storageRecorderView;
    private Spinner volumeSpinner;
    private Spinner resolutionSpinner;
    private Spinner dateFormatSpinner;
    private NumericStepper quotaStepper;
    private NumericStepper retentionStepper;
    private NumericStepper segmentStepper;
    private NumericStepper minFreeStepper;
    private NumericStepper parkingImpactStepper;
    private NumericStepper parkingDurationStepper;
    private IconCheckbox parkingAutoLockCheckbox;
    private IconCheckbox cameraMotionEnabledCheckbox;
    private NumericStepper cameraMotionSensitivityStepper;
    private IconCheckbox telegramEnabledCheckbox;
    private EditText telegramBotTokenInput;
    private EditText telegramChatIdInput;
    private IconCheckbox mqttEnabledCheckbox;
    private EditText mqttHostInput;
    private NumericStepper mqttPortStepper;
    private EditText mqttUsernameInput;
    private EditText mqttPasswordInput;
    private EditText mqttTopicPrefixInput;
    private IconCheckbox cloudflareEnabledCheckbox;
    private IconCheckbox gpsOverlayEnabledCheckbox;
    private Spinner gpsSpeedUnitSpinner;
    private IconCheckbox gpsShowCoordinatesCheckbox;
    private IconCheckbox gpsTrackEnabledCheckbox;
    private Spinner vehicleModelSpinner;
    private StyledSlider fisheyeCropSlider;
    private TextView fisheyeCropValueView;
    private LinearLayout segmentList;
    private LinearLayout selectionToolbar;
    private final Set<String> selectedRecordingPaths = new LinkedHashSet<>();
    private List<StorageRepository.SegmentInfo> displayedRecordings =
            new ArrayList<>();
    private boolean selectionMode;
    private IconStateToggle phoneAccessToggle;
    private PinDisplay phoneAccessPinView;
    private LinearLayout previewColumn;
    private FrameLayout settingsPhoneQrContainer;
    private WebView settingsPhoneQrView;
    private TextView settingsPhoneUrlView;
    private FrameLayout settingsOverlay;
    private List<StorageRepository.StorageVolume> volumes = new ArrayList<>();
    private RecorderSettings settings;
    private boolean populatingSettings;
    private int[] combinedLayoutDraft = new int[]{0, 1, 2, 3};
    private int segmentPreviewGeneration;
    private RecorderSettings segmentListSettings;
    private int segmentWindowEnd = -1;
    private int segmentWindowStart = -1;
    private View segmentTopSpacer;
    private View segmentBottomSpacer;
    private ScrollView recordingsScroll;
    private boolean initialSegmentsLoaded;
    private String displayedSegmentsFingerprint = "";
    private int watchdogTickCount;
    private ValueAnimator skeletonPulseAnimator;
    private final List<View> skeletonViews = new ArrayList<>();

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    CameraRecorderService.LocalBinder localBinder =
                            (CameraRecorderService.LocalBinder) binder;
                    recorderService = localBinder.getService();
                    serviceBound = true;
                    recorderService.setUiListener(MainActivity.this);
                    recorderService.startPreview();
                    refreshDirectPreviewTextures();
                    refreshVolumes();
                    refreshStorage();
                    refreshSettingsPhoneAccessDetails();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    serviceBound = false;
                    recorderService = null;
                    stateView.setText(getString(R.string.msg_service_disconnected));
                }
            };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.onAttach(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        RecorderStartup.scheduleFallbacks(this, "main activity created");
        settings = RecorderSettings.load(this);
        setContentView(buildContentView());
        populateInputs();
        ensureCameraPermission();
        ensureLocationPermission();
        Intent serviceIntent = new Intent(this, CameraRecorderService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null
                && intent.getBooleanExtra(
                        EXTRA_SHOW_BACKGROUND_ACCESS,
                        false)) {
            showBackgroundAccessDialog(false);
            intent.removeExtra(EXTRA_SHOW_BACKGROUND_ACCESS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBackgroundAccessViews();
        if (!backgroundAutomaticPromptHandled
                && BackgroundAccess.shouldShowAutomaticPrompt(this)) {
            backgroundAutomaticPromptHandled = true;
            showBackgroundAccessDialog(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (recorderService != null) {
            CameraRecorderService.Mode m = recorderService.getMode();
            if (m == CameraRecorderService.Mode.NOT_RECORDING) {
                recorderService.startPreview();
            }
        }
        refreshDirectPreviewTextures();
        nativePreviewWatchdogHandler.removeCallbacks(nativePreviewWatchdog);
        nativePreviewWatchdogHandler.postDelayed(
                nativePreviewWatchdog,
                NATIVE_PREVIEW_WATCHDOG_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        nativePreviewWatchdogHandler.removeCallbacks(nativePreviewWatchdog);
        if (recorderService != null) {
            CameraRecorderService.Mode m = recorderService.getMode();
            if (m == CameraRecorderService.Mode.NOT_RECORDING) {
                recorderService.releasePreview();
            }
        }
    }

    @Override
    protected void onDestroy() {
        clearSkeleton();
        fullscreenBarHandler.removeCallbacks(fullscreenTopBarAutoHide);
        detachFullscreenDirectTexture();
        if (recorderService != null) {
            recorderService.setUiListener(null);
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        segmentPreviewExecutor.shutdownNow();
        if (settingsPhoneQrView != null) {
            settingsPhoneQrView.destroy();
        }
        recyclePreviewFrames(displayedPreviewFrames);
        displayedPreviewFrames = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            clearSelection();
            return;
        }
        if (settingsOverlay != null
                && settingsOverlay.getVisibility() == View.VISIBLE) {
            closeSettingsOverlay();
            return;
        }
        if (cameraOverlay != null && cameraOverlay.getVisibility() == View.VISIBLE) {
            closeCameraOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onPreviewFrames(Bitmap[] frames) {
        for (int position = 0;
                position < previewViews.length;
                position++) {
            int cameraIndex = settings.combinedCameraIndex(position);
            if (cameraIndex >= 0 && cameraIndex < frames.length) {
                previewViews[position].setImageBitmap(frames[cameraIndex]);
            }
        }
        if (selectedCameraIndex >= 0 && selectedCameraIndex < frames.length) {
            fullscreenCamera.setImageBitmap(frames[selectedCameraIndex]);
        }
        for (int cameraIndex = 0;
                cameraIndex < fisheyePreviewViews.length
                        && cameraIndex < frames.length;
                cameraIndex++) {
            ImageView preview = fisheyePreviewViews[cameraIndex];
            if (preview != null) {
                preview.setImageBitmap(frames[cameraIndex]);
            }
        }
        Bitmap[] previousFrames = displayedPreviewFrames;
        displayedPreviewFrames = frames;
        recyclePreviewFrames(previousFrames);
    }

    @Override
    public void onServiceState(
            CameraRecorderService.Mode mode,
            String message) {
        if (settingsOverlay == null
                || settingsOverlay.getVisibility() != View.VISIBLE) {
            settings = RecorderSettings.load(this);
        }
        String statePrefix;
        if (mode == CameraRecorderService.Mode.RECORDING) {
            statePrefix = getString(R.string.state_recording);
        } else if (mode == CameraRecorderService.Mode.PARKING_STANDBY) {
            statePrefix = getString(R.string.state_parking_standby);
        } else if (mode == CameraRecorderService.Mode.PARKING_RECORDING) {
            statePrefix = getString(R.string.state_parking_recording);
        } else {
            statePrefix = getString(R.string.state_not_recording);
        }
        stateView.setText(statePrefix + " — " + message);
        updateRecordingControls(mode);
        refreshStorage();
    }

    @Override
    public void onRecorderSettingsChanged() {
        settings = RecorderSettings.load(this);
        updatePreviewCameraNames();
        if (fullscreenTitle != null && selectedCameraIndex >= 0) {
            fullscreenTitle.setText(settings.cameraName(selectedCameraIndex));
        }
        populateInputs();
        refreshDirectPreviewTextures();
        applyFullscreenDirectTransform();
        showMessage(getString(R.string.msg_settings_updated_phone));
    }

    private View buildContentView() {
        FrameLayout screen = new FrameLayout(this);
        LinearLayout root = vertical();
        root.setBackgroundResource(R.drawable.app_background);
        root.setPadding(dp(20), dp(14), dp(20), dp(16));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundResource(R.drawable.header_background);
        header.setPadding(dp(16), dp(9), dp(12), dp(9));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_camera);
        header.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView title = text("BYD Camera Recorder", 26, true);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        header.addView(title, titleParams);

        stateView = text(getString(R.string.connecting_to_service), 16, false);
        stateView.setTextColor(color(R.color.text_secondary));
        stateView.setGravity(Gravity.END);
        header.addView(
                stateView,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        controlsVisibilityButton = iconButton(
                R.drawable.ic_panel_right_collapse,
                "Hide controls and recordings",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        setControlsColumnCollapsed(
                                !controlsColumnCollapsed);
                    }
                });
        LinearLayout.LayoutParams controlsVisibilityParams =
                new LinearLayout.LayoutParams(dp(48), dp(48));
        controlsVisibilityParams.leftMargin = dp(12);
        header.addView(
                controlsVisibilityButton,
                controlsVisibilityParams);
        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = horizontal();
        previewColumn = vertical();
        previewColumn.setPadding(0, dp(12), dp(6), 0);
        LinearLayout previewGrid = vertical();
        previewGrid.setBackgroundResource(
                R.drawable.camera_grid_background);
        previewGrid.setPadding(dp(5), dp(5), dp(5), dp(5));
        previewGrid.setClipToOutline(true);
        previewGrid.addView(
                createPreviewRow(0, 1),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        previewGrid.addView(
                createPreviewRow(2, 3),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        previewColumn.addView(
                previewGrid,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        body.addView(
                previewColumn,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        3f));

        controlsColumn = vertical();
        controlsColumn.setBackgroundResource(R.drawable.controls_background);
        controlsColumn.setPadding(dp(14), dp(12), dp(14), dp(12));
        controlsColumn.addView(
                buildActionBar(),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        selectionToolbar = buildSelectionToolbar();
        selectionToolbar.setVisibility(View.GONE);
        controlsColumn.addView(
                selectionToolbar,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        recordingsScroll = new ScrollView(this);
        recordingsScroll.setFillViewport(true);
        recordingsScroll.addView(buildControls());
        recordingsScroll
                .getViewTreeObserver()
                .addOnScrollChangedListener(
                        new ViewTreeObserver.OnScrollChangedListener() {
                            @Override
                            public void onScrollChanged() {
                                renderVisibleSegments(false);
                            }
                        });
        controlsColumn.addView(
                recordingsScroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        body.addView(
                controlsColumn,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        2f));
        root.addView(
                body,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));
        screen.addView(
                root,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(
                buildCameraOverlay(),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        screen.addView(
                buildSettingsOverlay(),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        return screen;
    }

    private View buildActionBar() {
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        recordingToggle = new IconStateToggle(
                this,
                R.drawable.ic_play,
                R.drawable.ic_pause,
                getString(R.string.recording_off_label),
                getString(R.string.recording_on_label));
        recordingToggle.setListener(
                new IconStateToggle.Listener() {
                    @Override
                    public void onToggleRequested(boolean recording) {
                        if (recorderService == null) {
                            return;
                        }
                        if (recording) {
                            if (checkCameraPermission()) {
                                settings =
                                        settings.withContinuousRecordingEnabled(true);
                                recorderService.startRecording(settings);
                            }
                        } else {
                            settings =
                                    settings.withContinuousRecordingEnabled(false);
                            recorderService.stopRecording();
                        }
                    }
                });
        actions.addView(
                recordingToggle,
                new LinearLayout.LayoutParams(dp(100), dp(58)));

        parkingToggle = new IconStateToggle(
                this,
                R.drawable.ic_parking,
                R.drawable.ic_parking,
                getString(R.string.parking_start_label),
                getString(R.string.parking_active_label));
        parkingToggle.setListener(
                new IconStateToggle.Listener() {
                    @Override
                    public void onToggleRequested(boolean parking) {
                        if (recorderService == null) {
                            return;
                        }
                        if (parking) {
                            if (checkCameraPermission()) {
                                recorderService.enterParkingMode();
                            }
                        } else {
                            recorderService.exitParkingMode();
                        }
                    }
                });
        LinearLayout.LayoutParams parkingParams =
                new LinearLayout.LayoutParams(dp(100), dp(58));
        parkingParams.leftMargin = dp(8);
        actions.addView(parkingToggle, parkingParams);

        backgroundAccessButton = iconButton(
                R.drawable.ic_background_recording,
                "Allow the recorder to run in the background",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showBackgroundAccessDialog(false);
                    }
                });
        LinearLayout.LayoutParams backgroundParams = toolbarButtonParams();
        backgroundParams.leftMargin = dp(10);
        actions.addView(backgroundAccessButton, backgroundParams);

        IconButton phone = iconButton(
                R.drawable.ic_transfer,
                "Open phone app access",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startActivity(new Intent(
                                MainActivity.this,
                                PhoneAccessActivity.class));
                    }
                });
        LinearLayout.LayoutParams phoneParams = toolbarButtonParams();
        phoneParams.leftMargin = dp(10);
        actions.addView(phone, phoneParams);

        IconButton settingsButton = iconButton(
                R.drawable.ic_settings,
                "Open settings",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showSettingsOverlay();
                    }
                });
        LinearLayout.LayoutParams settingsParams = toolbarButtonParams();
        settingsParams.leftMargin = dp(10);
        actions.addView(settingsButton, settingsParams);

        IconButton refresh = iconButton(
                R.drawable.ic_refresh,
                "Refresh recordings",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        refreshStorage();
                    }
                });
        LinearLayout.LayoutParams refreshParams = toolbarButtonParams();
        refreshParams.leftMargin = dp(10);
        actions.addView(refresh, refreshParams);
        updateRecordingControls(CameraRecorderService.Mode.NOT_RECORDING);
        return actions;
    }

    private View buildControls() {
        LinearLayout controls = vertical();
        controls.setPadding(0, 0, 0, dp(20));

        LinearLayout segmentHeader = horizontal();
        segmentHeader.setGravity(Gravity.CENTER_VERTICAL);
        segmentHeader.addView(
                sectionTitle(getString(R.string.section_recordings)),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        controls.addView(segmentHeader);

        segmentList = vertical();
        controls.addView(segmentList);
        return controls;
    }

    private LinearLayout buildSelectionToolbar() {
        LinearLayout toolbar = horizontal();
        toolbar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, dp(4), 0, dp(8));

        toolbar.addView(
                iconButton(
                        R.drawable.ic_select_all,
                        getString(R.string.select_all),
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                confirmSelectionAction(
                                        getString(R.string.confirm_select_all_title),
                                        getString(R.string.confirm_select_all_message),
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                selectAllRecordings();
                                            }
                                        });
                            }
                        }),
                compactButtonParams());
        LinearLayout.LayoutParams spaced = compactButtonParams();
        spaced.leftMargin = dp(8);
        toolbar.addView(
                iconButton(
                        R.drawable.ic_clear_all,
                        getString(R.string.clear_selection),
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                confirmSelectionAction(
                                        getString(R.string.confirm_clear_title),
                                        getString(R.string.confirm_clear_message),
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                clearSelection();
                                            }
                                        });
                            }
                        }),
                spaced);
        LinearLayout.LayoutParams lockParams = compactButtonParams();
        lockParams.leftMargin = dp(8);
        toolbar.addView(
                iconButton(
                        R.drawable.ic_lock,
                        getString(R.string.lock_selected),
                        IconButton.Tone.LOCKED,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                confirmSelectionAction(
                                        getString(R.string.confirm_lock_title),
                                        getString(R.string.confirm_lock_message),
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                setSelectedRecordingsLocked(true);
                                            }
                                        });
                            }
                        }),
                lockParams);
        LinearLayout.LayoutParams unlockParams = compactButtonParams();
        unlockParams.leftMargin = dp(8);
        toolbar.addView(
                iconButton(
                        R.drawable.ic_unlock,
                        getString(R.string.unlock_selected),
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                confirmSelectionAction(
                                        getString(R.string.confirm_unlock_title),
                                        getString(R.string.confirm_unlock_message),
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                setSelectedRecordingsLocked(false);
                                            }
                                        });
                            }
                        }),
                unlockParams);
        LinearLayout.LayoutParams deleteParams = compactButtonParams();
        deleteParams.leftMargin = dp(8);
        toolbar.addView(
                iconButton(
                        R.drawable.ic_delete,
                        getString(R.string.delete_selected),
                        IconButton.Tone.RECORD,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                requestDeleteSelectedRecordings();
                            }
                        }),
                deleteParams);
        return toolbar;
    }

    private void confirmSelectionAction(
            String title,
            String message,
            final Runnable action) {
        boolean isDelete = title.equals(getString(R.string.confirm_delete_title));
        boolean isLock = title.equals(getString(R.string.confirm_lock_title));
        ConfirmationDialog.show(
                this,
                title,
                message,
                getString(android.R.string.ok),
                isDelete
                        ? ConfirmationDialog.Tone.DANGER
                        : isLock
                                ? ConfirmationDialog.Tone.WARNING
                                : ConfirmationDialog.Tone.DEFAULT,
                action);
    }

    private LinearLayout createPreviewRow(int firstCamera, int secondCamera) {
        LinearLayout row = horizontal();
        row.addView(
                createCameraPanel(firstCamera),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f));
        row.addView(
                createCameraPanel(secondCamera),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f));
        return row;
    }

    private View createCameraPanel(final int previewPosition) {
        FrameLayout panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        panel.setLayoutParams(panelParams);

        int initialCameraIndex =
                settings.combinedCameraIndex(previewPosition);
        ImageView image = new ImageView(this);
        image.setBackgroundColor(Color.BLACK);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showCameraOverlay(
                                settings.combinedCameraIndex(
                                        previewPosition));
                    }
                });
        previewViews[previewPosition] = image;
        panel.addView(
                image,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        final TextureView directPreview = new TextureView(this);
        directPreview.setAlpha(0f);
        directPreview.setOpaque(true);
        directPreview.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showCameraOverlay(
                                settings.combinedCameraIndex(
                                        previewPosition));
                    }
                });
        directPreview.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture texture,
                            int width,
                            int height) {
                        attachDirectPreviewTexture(previewPosition, texture);
                        applyDirectPreviewTransform(previewPosition);
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(
                            SurfaceTexture texture) {
                        if (recorderService != null) {
                            recorderService.detachCarPreviewTexture(
                                    texture,
                                    settings.combinedCameraIndex(
                                            previewPosition));
                        }
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture texture,
                            int width,
                            int height) {
                        applyDirectPreviewTransform(previewPosition);
                    }

                    @Override
                    public void onSurfaceTextureUpdated(
                            SurfaceTexture texture) {
                        if (!directPreviewHasFrame[previewPosition]) {
                            directPreviewHasFrame[previewPosition] = true;
                            directPreview.setAlpha(1f);
                        }
                        recordDirectPreviewFrame(previewPosition);
                    }
                });
        directPreviewViews[previewPosition] = directPreview;
        panel.addView(
                directPreview,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = text(
                settings.cameraName(initialCameraIndex),
                14,
                true);
        label.setTextColor(Color.WHITE);
        label.setBackgroundResource(
                R.drawable.camera_name_tag_background);
        label.setPadding(dp(9), dp(5), dp(9), dp(5));
        cameraNameViews[previewPosition] = label;
        FrameLayout.LayoutParams labelParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START);
        labelParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        panel.addView(label, labelParams);
        return panel;
    }

    private View buildLanguageSelector() {
        LinearLayout section = vertical();
        TextView label = text(getString(R.string.language_section), 18, true);
        label.setPadding(0, dp(12), 0, dp(8));
        section.addView(label);

        LinearLayout buttons = horizontal();
        final String currentLang = LocaleHelper.getLanguage(this);

        TextView enLabel = text("English", 15, "en".equals(currentLang));
        enLabel.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout enRow = horizontal();
        enRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        enRow.setBackgroundResource("en".equals(currentLang)
                ? R.drawable.card_selected_background
                : R.drawable.card_background);
        enRow.setPadding(dp(10), dp(10), dp(14), dp(10));
        enRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LocaleHelper.setLocale(MainActivity.this, "en");
                recreate();
            }
        });
        if ("en".equals(currentLang)) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            enRow.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        enRow.addView(enLabel);
        LinearLayout.LayoutParams enParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(enRow, enParams);

        LinearLayout.LayoutParams koParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        koParams.leftMargin = dp(8);
        TextView koLabel = text("한국어", 15, "ko".equals(currentLang));
        koLabel.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout koRow = horizontal();
        koRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        koRow.setBackgroundResource("ko".equals(currentLang)
                ? R.drawable.card_selected_background
                : R.drawable.card_background);
        koRow.setPadding(dp(10), dp(10), dp(14), dp(10));
        koRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LocaleHelper.setLocale(MainActivity.this, "ko");
                recreate();
            }
        });
        if ("ko".equals(currentLang)) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            koRow.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        koRow.addView(koLabel);
        buttons.addView(koRow, koParams);

        section.addView(buttons);
        return section;
    }

    private View buildSettingsOverlay() {
        settingsOverlay = new FrameLayout(this);
        settingsOverlay.setBackgroundColor(Color.argb(245, 5, 11, 20));
        settingsOverlay.setVisibility(View.GONE);
        settingsOverlay.setClickable(true);

        LinearLayout panel = vertical();
        panel.setBackgroundResource(R.drawable.modal_background);
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.settings_title), 24, true);
        header.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        settingsSaveButton = iconButton(
                R.drawable.ic_save,
                getString(R.string.settings_title),
                IconButton.Tone.SUCCESS,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        saveSettings();
                        refreshStorage();
                        closeSettingsOverlay();
                    }
                });
        settingsSaveButton.setEnabled(false);
        header.addView(settingsSaveButton, toolbarButtonParams());

        IconButton close = iconButton(
                R.drawable.ic_close,
                getString(R.string.settings_title),
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        closeSettingsOverlay();
                    }
                });
        LinearLayout.LayoutParams closeParams = toolbarButtonParams();
        closeParams.leftMargin = dp(10);
        header.addView(close, closeParams);
        panel.addView(header);

        TextView description = text(
                getString(R.string.settings_description),
                14,
                false);
        description.setTextColor(color(R.color.text_secondary));
        description.setPadding(0, dp(4), 0, dp(16));
        panel.addView(description);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout fields = vertical();
        fields.setPadding(dp(6), dp(4), dp(6), dp(16));

        fields.addView(
                buildLanguageSelector(),
                matchWidthWrap(dp(0), dp(14)));

        fields.addView(
                sectionTitle(getString(R.string.section_storage)),
                matchWidthWrap(dp(0), dp(8)));
        fields.addView(
                buildStorageSummary(),
                matchWidthWrap(dp(0), dp(8)));
        fields.addView(
                buildStorageDetails(),
                matchWidthWrap(dp(0), dp(14)));

        phoneAccessToggle = new IconStateToggle(
                this,
                R.drawable.ic_close,
                R.drawable.ic_wifi,
                getString(R.string.phone_access_off_toggle),
                getString(R.string.phone_access_on_toggle),
                false);
        phoneAccessToggle.setListener(
                new IconStateToggle.Listener() {
                    @Override
                    public void onToggleRequested(boolean enabled) {
                        phoneAccessToggle.setChecked(enabled);
                        refreshSettingsPhoneAccessDetails();
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                buildPhoneAccessSetting(),
                matchWidthWrap(dp(0), dp(10)));
        fields.addView(
                buildBackgroundAccessSetting(),
                matchWidthWrap(dp(0), dp(10)));

        volumeSpinner = new Spinner(this);
        volumeSpinner.setBackgroundResource(R.drawable.input_background);
        fields.addView(
                labeledField(getString(R.string.setting_recording_volume), volumeSpinner,
                        R.string.help_recording_volume),
                matchWidthWrap(dp(0), dp(10)));

        resolutionSpinner = new Spinner(this);
        resolutionSpinner.setBackgroundResource(R.drawable.input_background);
        resolutionSpinner.setAdapter(
                new ResolutionAdapter(this, VideoResolution.values()));
        resolutionSpinner.setOnItemSelectedListener(
                settingsSelectionListener(false));
        // Resolution selection is temporarily hidden; recording always uses
        // the native maximum profile (see RecorderSettings). Re-add this row
        // to restore the choice.
        // fields.addView(
        //         labeledField("Video resolution", resolutionSpinner),
        //         matchWidthWrap(dp(0), dp(10)));

        dateFormatSpinner = new Spinner(this);
        dateFormatSpinner.setBackgroundResource(R.drawable.input_background);
        dateFormatSpinner.setAdapter(
                new DateFormatAdapter(this, DisplayDateFormat.values()));
        dateFormatSpinner.setOnItemSelectedListener(
                settingsSelectionListener(false));
        fields.addView(
                labeledField(getString(R.string.setting_date_format), dateFormatSpinner,
                        R.string.help_date_format),
                matchWidthWrap(dp(0), dp(10)));

        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_camera_names),
                        getString(R.string.help_camera_names)));
        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            EditText cameraNameInput = textInput();
            cameraNameInputs[cameraIndex] = cameraNameInput;
            cameraNameInput.addTextChangedListener(
                    new TextWatcher() {
                        @Override
                        public void beforeTextChanged(
                                CharSequence text,
                                int start,
                                int count,
                                int after) {
                        }

                        @Override
                        public void onTextChanged(
                                CharSequence text,
                                int start,
                                int before,
                                int count) {
                            updateCombinedLayoutTiles();
                            updateCameraOrientationNames();
                            updateSettingsSaveState();
                        }

                        @Override
                        public void afterTextChanged(Editable editable) {
                        }
                    });
            fields.addView(
                    labeledFieldWithoutHelp(
                            getString(R.string.camera_number, cameraIndex + 1),
                            cameraNameInput),
                    matchWidthWrap(dp(0), dp(10)));
        }
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_camera_orientation),
                        getString(R.string.help_camera_orientation)));
        TextView orientationHelp = text(
                getString(R.string.orientation_help),
                13,
                false);
        orientationHelp.setTextColor(color(R.color.text_secondary));
        fields.addView(
                orientationHelp,
                matchWidthWrap(dp(0), dp(6)));
        fields.addView(
                buildCameraOrientationEditor(),
                matchWidthWrap(dp(0), dp(14)));
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_fisheye_crop),
                        getString(R.string.help_fisheye_crop)));
        TextView cropHelp = text(
                getString(R.string.fisheye_help),
                13,
                false);
        cropHelp.setTextColor(color(R.color.text_secondary));
        fields.addView(cropHelp, matchWidthWrap(dp(0), dp(6)));
        fields.addView(
                buildCameraCropEditor(),
                matchWidthWrap(dp(0), dp(14)));
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_combined_layout),
                        getString(R.string.help_combined_layout)));
        TextView layoutHelp = text(
                getString(R.string.combined_layout_help),
                13,
                false);
        layoutHelp.setTextColor(color(R.color.text_secondary));
        fields.addView(layoutHelp, matchWidthWrap(dp(0), dp(6)));
        fields.addView(
                buildCombinedLayoutEditor(),
                matchWidthWrap(dp(0), dp(14)));

        quotaStepper = numericStepper(
                new NumericStepper.Specification(
                        "Recorder quota",
                        quotaQuarterUnits(
                                RecorderSettings.MIN_QUOTA_BYTES),
                        quotaQuarterUnits(
                                RecorderSettings.MAX_QUOTA_BYTES),
                        1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return formatQuotaQuarterUnits(value);
                            }
                        }));
        retentionStepper = numericStepper(
                new NumericStepper.Specification(
                        "Retention",
                        RecorderSettings.MIN_RETENTION_DAYS,
                        RecorderSettings.MAX_RETENTION_DAYS,
                        1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return getString(
                                        value == 1
                                                ? R.string.retention_day
                                                : R.string.retention_days,
                                        value);
                            }
                        }));
        segmentStepper = numericStepper(
                new NumericStepper.Specification(
                        "Segment length",
                        RecorderSettings.MIN_SEGMENT_MINUTES,
                        RecorderSettings.MAX_SEGMENT_MINUTES,
                        1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return getString(R.string.segment_minutes, value);
                            }
                        }));
        minFreeStepper = numericStepper(
                new NumericStepper.Specification(
                        "Minimum volume free",
                        RecorderSettings.MIN_MIN_FREE_PERCENT,
                        RecorderSettings.MAX_MIN_FREE_PERCENT,
                        1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return value + "%";
                            }
                        }));
        fields.addView(
                labeledField(getString(R.string.setting_quota), quotaStepper,
                        R.string.help_quota),
                matchWidthWrap(dp(0), dp(10)));
        fields.addView(
                labeledField(getString(R.string.setting_retention), retentionStepper,
                        R.string.help_retention),
                matchWidthWrap(dp(0), dp(10)));
        fields.addView(
                labeledField(getString(R.string.setting_segment), segmentStepper,
                        R.string.help_segment),
                matchWidthWrap(dp(0), dp(10)));
        fields.addView(
                labeledField(getString(R.string.setting_min_free), minFreeStepper,
                        R.string.help_min_free),
                matchWidthWrap(dp(0), dp(10)));

        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_parking_guard),
                        getString(R.string.parking_guard_description)),
                matchWidthWrap(dp(0), dp(8)));
        // 임계값은 0.5G 단위, 1.5G~5.0G → 스텝 값 = G*10 (15~50, step 5)
        parkingImpactStepper = numericStepper(
                new NumericStepper.Specification(
                        "Impact threshold",
                        15,  // 1.5G
                        50,  // 5.0G
                        5,   // 0.5G 단위
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return (value / 10.0f) + "G";
                            }
                        }));
        parkingDurationStepper = numericStepper(
                new NumericStepper.Specification(
                        "Recording duration",
                        ParkingGuardSettings.MIN_RECORDING_SECONDS,
                        ParkingGuardSettings.MAX_RECORDING_SECONDS,
                        30,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return getString(R.string.duration_seconds, value);
                            }
                        }));
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_impact_threshold),
                        parkingImpactStepper),
                matchWidthWrap(dp(0), dp(10)));
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_post_impact_duration),
                        parkingDurationStepper),
                matchWidthWrap(dp(0), dp(10)));
        parkingAutoLockCheckbox = new IconCheckbox(this,
                getString(R.string.parking_auto_lock_label));
        parkingAutoLockCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledField(getString(R.string.setting_parking_auto_lock),
                        parkingAutoLockCheckbox, R.string.help_parking_auto_lock),
                matchWidthWrap(dp(0), dp(10)));

        cameraMotionEnabledCheckbox = new IconCheckbox(this,
                getString(R.string.camera_motion_enabled_label));
        cameraMotionEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledField(getString(R.string.setting_camera_motion_enabled),
                        cameraMotionEnabledCheckbox, R.string.help_camera_motion),
                matchWidthWrap(dp(0), dp(10)));
        cameraMotionSensitivityStepper = numericStepper(
                new NumericStepper.Specification(
                        "Motion sensitivity",
                        1, 5, 1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return String.valueOf(value);
                            }
                        }));
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_camera_motion_sensitivity),
                        cameraMotionSensitivityStepper),
                matchWidthWrap(dp(0), dp(10)));

        // ── Telegram 알림 ──────────────────────────────────────────────
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_telegram),
                        getString(R.string.telegram_description)),
                matchWidthWrap(dp(0), dp(8)));
        telegramEnabledCheckbox = new IconCheckbox(this,
                getString(R.string.telegram_enabled_label));
        telegramEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_notifications_enabled),
                        telegramEnabledCheckbox),
                matchWidthWrap(dp(0), dp(8)));
        telegramBotTokenInput = textInput();
        telegramBotTokenInput.setHint("Bot Token");
        telegramBotTokenInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp("Bot Token", telegramBotTokenInput),
                matchWidthWrap(dp(0), dp(8)));
        telegramChatIdInput = textInput();
        telegramChatIdInput.setHint("Chat ID");
        telegramChatIdInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp("Chat ID", telegramChatIdInput),
                matchWidthWrap(dp(0), dp(10)));

        // ── MQTT (Home Assistant) ──────────────────────────────────────
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_mqtt),
                        getString(R.string.mqtt_description)),
                matchWidthWrap(dp(0), dp(8)));
        mqttEnabledCheckbox = new IconCheckbox(this, getString(R.string.mqtt_enabled_label));
        mqttEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_mqtt_enabled),
                        mqttEnabledCheckbox),
                matchWidthWrap(dp(0), dp(8)));
        mqttHostInput = textInput();
        mqttHostInput.setHint("192.168.1.100");
        mqttHostInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_broker_address),
                        mqttHostInput),
                matchWidthWrap(dp(0), dp(8)));
        mqttPortStepper = numericStepper(
                new NumericStepper.Specification(
                        "Port",
                        1,
                        65535,
                        1,
                        new NumericStepper.ValueFormatter() {
                            @Override
                            public String format(int value) {
                                return String.valueOf(value);
                            }
                        }));
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_port), mqttPortStepper),
                matchWidthWrap(dp(0), dp(8)));
        mqttUsernameInput = textInput();
        mqttUsernameInput.setHint(getString(R.string.hint_optional));
        mqttUsernameInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_username),
                        mqttUsernameInput),
                matchWidthWrap(dp(0), dp(8)));
        mqttPasswordInput = textInput();
        mqttPasswordInput.setHint(getString(R.string.hint_optional));
        mqttPasswordInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mqttPasswordInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_password),
                        mqttPasswordInput),
                matchWidthWrap(dp(0), dp(8)));
        mqttTopicPrefixInput = textInput();
        mqttTopicPrefixInput.setHint("byd");
        mqttTopicPrefixInput.addTextChangedListener(settingsChangeWatcher());
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_topic_prefix),
                        mqttTopicPrefixInput),
                matchWidthWrap(dp(0), dp(10)));

        // ── Cloudflare 터널 ────────────────────────────────────────────
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_cloudflare),
                        getString(R.string.cloudflare_description)),
                matchWidthWrap(dp(0), dp(8)));
        cloudflareEnabledCheckbox = new IconCheckbox(this,
                getString(R.string.cloudflare_enabled_label));
        cloudflareEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_tunnel_enabled),
                        cloudflareEnabledCheckbox),
                matchWidthWrap(dp(0), dp(10)));

        // ── GPS 오버레이 ────────────────────────────────────────────────
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_gps_overlay),
                        getString(R.string.help_gps_overlay)),
                matchWidthWrap(dp(0), dp(8)));
        gpsOverlayEnabledCheckbox = new IconCheckbox(this,
                getString(R.string.gps_overlay_enabled_label));
        gpsOverlayEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_gps_overlay_enabled),
                        gpsOverlayEnabledCheckbox),
                matchWidthWrap(dp(0), dp(8)));
        gpsSpeedUnitSpinner = new Spinner(this);
        gpsSpeedUnitSpinner.setBackgroundResource(R.drawable.input_background);
        gpsSpeedUnitSpinner.setAdapter(
                new SpeedUnitAdapter(this,
                        new String[]{"kmh", "mph"},
                        new String[]{
                                getString(R.string.gps_speed_kmh),
                                getString(R.string.gps_speed_mph)}));
        gpsSpeedUnitSpinner.setOnItemSelectedListener(settingsSelectionListener(false));
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_gps_speed_unit),
                        gpsSpeedUnitSpinner),
                matchWidthWrap(dp(0), dp(8)));
        gpsShowCoordinatesCheckbox = new IconCheckbox(this,
                getString(R.string.gps_show_coordinates_label));
        gpsShowCoordinatesCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_gps_show_coordinates),
                        gpsShowCoordinatesCheckbox),
                matchWidthWrap(dp(0), dp(8)));
        gpsTrackEnabledCheckbox = new IconCheckbox(this,
                getString(R.string.gps_track_enabled_label));
        gpsTrackEnabledCheckbox.setListener(
                new IconCheckbox.Listener() {
                    @Override
                    public void onCheckedChanged(boolean checked) {
                        updateSettingsSaveState();
                    }
                });
        fields.addView(
                labeledFieldWithoutHelp(getString(R.string.setting_gps_track),
                        gpsTrackEnabledCheckbox),
                matchWidthWrap(dp(0), dp(10)));

        // ── 차량 모델 ────────────────────────────────────────────────────
        fields.addView(
                sectionTitleWithHelp(
                        getString(R.string.section_vehicle_model),
                        getString(R.string.help_vehicle_model)),
                matchWidthWrap(dp(0), dp(8)));
        vehicleModelSpinner = new Spinner(this);
        vehicleModelSpinner.setBackgroundResource(R.drawable.input_background);
        vehicleModelSpinner.setAdapter(
                new VehicleProfileAdapter(this,
                        VehicleProfileRegistry.ALL_PROFILES));
        vehicleModelSpinner.setOnItemSelectedListener(settingsSelectionListener(false));
        fields.addView(
                labeledField(getString(R.string.setting_vehicle_model),
                        vehicleModelSpinner, R.string.help_vehicle_model),
                matchWidthWrap(dp(0), dp(10)));

        scroll.addView(fields);
        panel.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        panelParams.setMargins(dp(24), dp(20), dp(24), dp(20));
        settingsOverlay.addView(panel, panelParams);
        return settingsOverlay;
    }

    private View buildCameraOverlay() {
        cameraOverlay = new FrameLayout(this);
        cameraOverlay.setBackgroundColor(Color.BLACK);
        cameraOverlay.setVisibility(View.GONE);

        fullscreenCamera = new ZoomImageView(this);
        fullscreenCamera.setBackgroundColor(Color.BLACK);
        fullscreenCamera.setZoomListener(
                new ZoomImageView.ZoomListener() {
                    @Override
                    public void onZoomChanged(float scale) {
                        if (resetZoomButton != null) {
                            resetZoomButton.setVisibility(
                                    scale > 1.01f ? View.VISIBLE : View.GONE);
                        }
                        if (scale > 1.01f) {
                            hideFullscreenTopBar();
                        }
                    }
                });
        fullscreenCamera.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        toggleFullscreenTopBar();
                    }
                });
        cameraOverlay.addView(
                fullscreenCamera,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenDirectCamera = new ZoomTextureView(this);
        fullscreenDirectCamera.setOpaque(true);
        fullscreenDirectCamera.setAlpha(0f);
        fullscreenDirectCamera.setVisibility(View.GONE);
        fullscreenDirectCamera.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture surface,
                            int width,
                            int height) {
                        attachFullscreenDirectTexture();
                        updateCarBitmapPreviewRequirement();
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(
                            SurfaceTexture surface) {
                        detachFullscreenDirectTexture();
                        updateCarBitmapPreviewRequirement();
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture surface,
                            int width,
                            int height) {
                        applyFullscreenDirectTransform();
                    }

                    @Override
                    public void onSurfaceTextureUpdated(
                            SurfaceTexture surface) {
                        recordFullscreenDirectPreviewFrame();
                    }
                });
        fullscreenDirectCamera.setZoomListener(
                new ZoomTextureView.ZoomListener() {
                    @Override
                    public void onZoomChanged(float scale) {
                        if (resetZoomButton != null) {
                            resetZoomButton.setVisibility(
                                    scale > 1.01f ? View.VISIBLE : View.GONE);
                        }
                        if (scale > 1.01f) {
                            hideFullscreenTopBar();
                        }
                    }
                });
        fullscreenDirectCamera.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        toggleFullscreenTopBar();
                    }
                });
        cameraOverlay.addView(
                fullscreenDirectCamera,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = horizontal();
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), dp(10), dp(18), dp(10));
        topBar.setBackgroundColor(Color.argb(220, 11, 18, 32));
        fullscreenTitle = text("Camera", 22, true);
        topBar.addView(
                fullscreenTitle,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        resetZoomButton = iconButton(
                R.drawable.ic_zoom_reset,
                "Reset zoom",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        fullscreenCamera.resetZoom();
                        fullscreenDirectCamera.resetZoom();
                    }
                });
        resetZoomButton.setVisibility(View.GONE);
        topBar.addView(resetZoomButton, toolbarButtonParams());

        IconButton close = iconButton(
                R.drawable.ic_close,
                "Close camera",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        closeCameraOverlay();
                    }
                });
        LinearLayout.LayoutParams closeParams = toolbarButtonParams();
        closeParams.leftMargin = dp(10);
        topBar.addView(close, closeParams);

        FrameLayout.LayoutParams topBarParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP);
        cameraOverlay.addView(topBar, topBarParams);
        fullscreenTopBar = topBar;
        return cameraOverlay;
    }

    private void showFullscreenTopBar() {
        if (fullscreenTopBar == null) {
            return;
        }
        fullscreenBarHandler.removeCallbacks(fullscreenTopBarAutoHide);
        fullscreenTopBar.animate().cancel();
        fullscreenTopBar.setVisibility(View.VISIBLE);
        fullscreenTopBar.animate()
                .alpha(1f)
                .setDuration(FULLSCREEN_BAR_FADE_MS);
        fullscreenBarHandler.postDelayed(
                fullscreenTopBarAutoHide,
                FULLSCREEN_BAR_AUTO_HIDE_MS);
    }

    private void hideFullscreenTopBar() {
        if (fullscreenTopBar == null
                || fullscreenTopBar.getVisibility() != View.VISIBLE) {
            return;
        }
        fullscreenBarHandler.removeCallbacks(fullscreenTopBarAutoHide);
        fullscreenTopBar.animate().cancel();
        fullscreenTopBar.animate()
                .alpha(0f)
                .setDuration(FULLSCREEN_BAR_FADE_MS)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                fullscreenTopBar.setVisibility(View.GONE);
                            }
                        });
    }

    private void toggleFullscreenTopBar() {
        if (fullscreenTopBar == null) {
            return;
        }
        if (fullscreenTopBar.getVisibility() == View.VISIBLE
                && fullscreenTopBar.getAlpha() > 0.5f) {
            hideFullscreenTopBar();
        } else {
            showFullscreenTopBar();
        }
    }

    private void closeSettingsOverlay() {
        if (recorderService != null) {
            recorderService.setPreviewCropPercentOverride(null);
        }
        settingsOverlay.setVisibility(View.GONE);
        updateCarBitmapPreviewRequirement();
    }

    private void showSettingsOverlay() {
        settings = RecorderSettings.load(this);
        populateInputs();
        refreshVolumes();
        if (recorderService != null) {
            recorderService.setPreviewCropPercentOverride(
                    settings.fisheyeCropPercent());
        }
        settingsOverlay.setVisibility(View.VISIBLE);
        updateCarBitmapPreviewRequirement();
        updateBackgroundAccessViews();
    }

    private View buildBackgroundAccessSetting() {
        LinearLayout card = horizontal();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.card_background);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_background_recording);
        card.addView(
                icon,
                new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout copy = vertical();
        TextView title = text(getString(R.string.background_access_title), 15, true);
        copy.addView(title);
        backgroundSettingsStatusView = text("", 13, false);
        backgroundSettingsStatusView.setTextColor(
                color(R.color.text_secondary));
        copy.addView(backgroundSettingsStatusView);
        LinearLayout.LayoutParams copyParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        copyParams.leftMargin = dp(12);
        card.addView(copy, copyParams);

        card.addView(
                iconButton(
                        R.drawable.ic_background_recording,
                        "Open background recording access",
                        IconButton.Tone.DEFAULT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                showBackgroundAccessDialog(false);
                            }
                        }),
                toolbarButtonParams());
        return card;
    }

    private void showBackgroundAccessDialog(boolean automatic) {
        if (!BackgroundAccess.isRequestSupported(this)) {
            ConfirmationDialog.showInfo(
                    this,
                    getString(R.string.background_access_unsupported_title),
                    getString(R.string.background_access_unsupported_message));
            return;
        }
        if (BackgroundAccess.isGranted(this)) {
            ConfirmationDialog.showInfo(
                    this,
                    getString(R.string.background_access_already_title),
                    getString(R.string.background_access_already_message));
            return;
        }
        String message = getString(R.string.background_access_dialog_message);
        if (automatic) {
            ConfirmationDialog.showWithOption(
                    this,
                    getString(R.string.background_access_dialog_title),
                    message,
                    getString(R.string.background_access_open_settings),
                    ConfirmationDialog.Tone.DEFAULT,
                    getString(R.string.background_access_suppress),
                    new ConfirmationDialog.OptionAction() {
                        @Override
                        public void run(boolean optionChecked) {
                            BackgroundAccess.setAutomaticPromptSuppressed(
                                    MainActivity.this,
                                    optionChecked);
                            BackgroundAccess.request(MainActivity.this);
                        }
                    });
        } else {
            ConfirmationDialog.show(
                    this,
                    getString(R.string.background_access_dialog_title),
                    message,
                    getString(R.string.background_access_open_settings),
                    ConfirmationDialog.Tone.DEFAULT,
                    new Runnable() {
                        @Override
                        public void run() {
                            BackgroundAccess.request(MainActivity.this);
                        }
                    });
        }
    }

    private void updateBackgroundAccessViews() {
        boolean granted = BackgroundAccess.isGranted(this);
        boolean supported = BackgroundAccess.isRequestSupported(this);
        if (backgroundAccessButton != null) {
            backgroundAccessButton.setVisibility(
                    granted || !supported ? View.GONE : View.VISIBLE);
        }
        if (backgroundSettingsStatusView != null) {
            backgroundSettingsStatusView.setText(
                    granted
                            ? getString(R.string.background_access_allowed)
                            : supported
                                    ? getString(R.string.background_access_warning)
                                    : getString(R.string.background_access_unsupported));
            backgroundSettingsStatusView.setTextColor(
                    granted
                            ? color(R.color.success)
                            : supported
                                    ? color(R.color.warning)
                                    : color(R.color.text_secondary));
        }
    }

    private void closeCameraOverlay() {
        fullscreenBarHandler.removeCallbacks(fullscreenTopBarAutoHide);
        detachFullscreenDirectTexture();
        selectedCameraIndex = -1;
        fullscreenCamera.resetZoom();
        fullscreenDirectCamera.resetZoom();
        fullscreenDirectCamera.setVisibility(View.GONE);
        fullscreenCamera.setImageDrawable(null);
        cameraOverlay.setVisibility(View.GONE);
        refreshDirectPreviewTextures();
        updateCarBitmapPreviewRequirement();
    }

    private void showCameraOverlay(int cameraIndex) {
        selectedCameraIndex = cameraIndex;
        fullscreenTitle.setText(settings.cameraName(cameraIndex));
        fullscreenCamera.resetZoom();
        int previewPosition = findPreviewPosition(cameraIndex);
        if (previewPosition >= 0) {
            fullscreenCamera.setImageDrawable(
                    previewViews[previewPosition].getDrawable());
        }
        cameraOverlay.setVisibility(View.VISIBLE);
        if (!FrameSourceFactory.shouldUseFixture(this)) {
            fullscreenDirectCamera.setVisibility(View.VISIBLE);
            attachFullscreenDirectTexture();
        }
        showFullscreenTopBar();
        updateCarBitmapPreviewRequirement();
    }

    private void attachFullscreenDirectTexture() {
        if (recorderService == null
                || selectedCameraIndex < 0
                || fullscreenDirectCamera == null
                || !fullscreenDirectCamera.isAvailable()) {
            return;
        }
        int previewPosition = findPreviewPosition(selectedCameraIndex);
        if (previewPosition >= 0) {
            TextureView mainPreview = directPreviewViews[previewPosition];
            if (mainPreview != null
                    && mainPreview.isAvailable()
                    && directPreviewCameraIndexes[previewPosition]
                            == selectedCameraIndex) {
                recorderService.detachCarPreviewTexture(
                        mainPreview.getSurfaceTexture(),
                        selectedCameraIndex);
                directPreviewCameraIndexes[previewPosition] = -1;
            }
        }
        SurfaceTexture fullscreenTexture =
                fullscreenDirectCamera.getSurfaceTexture();
        if (fullscreenDirectCameraIndex >= 0
                && fullscreenDirectCameraIndex != selectedCameraIndex) {
            recorderService.detachCarPreviewTexture(
                    fullscreenTexture,
                    fullscreenDirectCameraIndex);
        }
        recorderService.attachCarPreviewTexture(
                fullscreenTexture,
                selectedCameraIndex);
        fullscreenDirectCameraIndex = selectedCameraIndex;
        fullscreenDirectLastFrameAtMs = 0L;
        lastNativeAttachAtMs = SystemClock.elapsedRealtime();
        fullscreenDirectCamera.setAlpha(0f);
        applyFullscreenDirectTransform();
    }

    private void detachFullscreenDirectTexture() {
        if (recorderService == null
                || fullscreenDirectCamera == null
                || !fullscreenDirectCamera.isAvailable()
                || fullscreenDirectCameraIndex < 0) {
            fullscreenDirectCameraIndex = -1;
            return;
        }
        recorderService.detachCarPreviewTexture(
                fullscreenDirectCamera.getSurfaceTexture(),
                fullscreenDirectCameraIndex);
        fullscreenDirectCameraIndex = -1;
        fullscreenDirectLastFrameAtMs = 0L;
    }

    private void applyFullscreenDirectTransform() {
        if (fullscreenDirectCamera == null
                || selectedCameraIndex < 0
                || fullscreenDirectCamera.getWidth() <= 0
                || fullscreenDirectCamera.getHeight() <= 0) {
            return;
        }
        float cropScale =
                1f
                        / Math.max(
                                0.2f,
                                1f
                                        - settings.fisheyeCropPercent()
                                                * 2f
                                                / 100f);
        float viewAspect =
                fullscreenDirectCamera.getWidth()
                        / (float) fullscreenDirectCamera.getHeight();
        float cameraAspect =
                FrameProcessor.SOURCE_CAMERA_WIDTH
                        / (float) FrameProcessor.SOURCE_CAMERA_HEIGHT;
        // Contain (letterbox) rather than cover: the fullscreen screen is far
        // wider than the 4:3 camera, so covering would zoom in and cut off the
        // top and bottom of the frame. Containing keeps the entire camera image
        // visible and aspect-correct, matching the small tiles and the phone.
        float containScaleX =
                viewAspect > cameraAspect
                        ? cameraAspect / viewAspect
                        : 1f;
        float containScaleY =
                viewAspect < cameraAspect
                        ? viewAspect / cameraAspect
                        : 1f;
        float scaleX =
                (settings.cameraFlipHorizontal(selectedCameraIndex)
                                ? -1f
                                : 1f)
                        * cropScale
                        * containScaleX;
        float scaleY =
                (settings.cameraFlipVertical(selectedCameraIndex)
                                ? -1f
                                : 1f)
                        * cropScale
                        * containScaleY;
        Matrix transform = new Matrix();
        transform.setScale(
                scaleX,
                scaleY,
                fullscreenDirectCamera.getWidth() / 2f,
                fullscreenDirectCamera.getHeight() / 2f);
        fullscreenDirectCamera.setTransform(transform);
    }

    private void attachDirectPreviewTexture(
            int previewPosition,
            SurfaceTexture texture) {
        if (recorderService == null || texture == null) {
            return;
        }
        int cameraIndex =
                settings.combinedCameraIndex(previewPosition);
        int previousCameraIndex =
                directPreviewCameraIndexes[previewPosition];
        if (previousCameraIndex >= 0
                && previousCameraIndex != cameraIndex) {
            recorderService.detachCarPreviewTexture(
                    texture,
                    previousCameraIndex);
        }
        recorderService.attachCarPreviewTexture(texture, cameraIndex);
        directPreviewCameraIndexes[previewPosition] = cameraIndex;
        directPreviewLastFrameAtMs[previewPosition] = 0L;
        lastNativeAttachAtMs = SystemClock.elapsedRealtime();
    }

    private void refreshDirectPreviewTextures() {
        for (int previewPosition = 0;
                previewPosition < directPreviewViews.length;
                previewPosition++) {
            TextureView preview = directPreviewViews[previewPosition];
            if (preview == null || !preview.isAvailable()) {
                continue;
            }
            int cameraIndex =
                    settings.combinedCameraIndex(previewPosition);
            if (fullscreenDirectCameraIndex == cameraIndex
                    && selectedCameraIndex == cameraIndex
                    && fullscreenDirectCamera != null
                    && fullscreenDirectCamera.isAvailable()) {
                continue;
            }
            attachDirectPreviewTexture(
                    previewPosition,
                    preview.getSurfaceTexture());
            applyDirectPreviewTransform(previewPosition);
        }
        updateCarBitmapPreviewRequirement();
    }

    private void recordDirectPreviewFrame(int previewPosition) {
        long now = SystemClock.elapsedRealtime();
        directPreviewLastFrameAtMs[previewPosition] = now;
        if (directPreviewMetricsStartedAt[previewPosition] == 0L) {
            directPreviewMetricsStartedAt[previewPosition] = now;
        }
        directPreviewFrameCounts[previewPosition]++;
        long elapsed =
                now - directPreviewMetricsStartedAt[previewPosition];
        if (elapsed < PREVIEW_METRICS_WINDOW_MS) {
            return;
        }
        int cameraIndex =
                settings.combinedCameraIndex(previewPosition);
        Log.i(
                TAG,
                String.format(
                        Locale.US,
                        "Direct car preview performance: position=%d camera=%d renderedFps=%.1f",
                        previewPosition + 1,
                        cameraIndex + 1,
                        directPreviewFrameCounts[previewPosition]
                                * 1000f
                                / elapsed));
        directPreviewFrameCounts[previewPosition] = 0L;
        directPreviewMetricsStartedAt[previewPosition] = now;
    }

    private void recordFullscreenDirectPreviewFrame() {
        long now = SystemClock.elapsedRealtime();
        fullscreenDirectLastFrameAtMs = now;
        if (fullscreenDirectCamera != null
                && fullscreenDirectCamera.getAlpha() < 1f) {
            fullscreenDirectCamera.setAlpha(1f);
        }
        if (fullscreenDirectMetricsStartedAt == 0L) {
            fullscreenDirectMetricsStartedAt = now;
        }
        fullscreenDirectFrameCount++;
        long elapsed = now - fullscreenDirectMetricsStartedAt;
        if (elapsed < PREVIEW_METRICS_WINDOW_MS) {
            return;
        }
        Log.i(
                TAG,
                String.format(
                        Locale.US,
                        "Fullscreen direct preview performance: camera=%d renderedFps=%.1f",
                        fullscreenDirectCameraIndex + 1,
                        fullscreenDirectFrameCount * 1000f / elapsed));
        fullscreenDirectFrameCount = 0L;
        fullscreenDirectMetricsStartedAt = now;
    }

    private void applyDirectPreviewTransform(int previewPosition) {
        TextureView preview = directPreviewViews[previewPosition];
        if (preview == null
                || preview.getWidth() <= 0
                || preview.getHeight() <= 0) {
            return;
        }
        int cameraIndex =
                settings.combinedCameraIndex(previewPosition);
        float cropScale =
                1f
                        / Math.max(
                                0.3f,
                                1f
                                        - 2f
                                                * settings.fisheyeCropPercent()
                                                / 100f);
        float scaleX =
                (settings.cameraFlipHorizontal(cameraIndex)
                                ? -1f
                                : 1f)
                        * cropScale;
        float scaleY =
                (settings.cameraFlipVertical(cameraIndex)
                                ? -1f
                                : 1f)
                        * cropScale;
        Matrix transform = new Matrix();
        transform.setScale(
                scaleX,
                scaleY,
                preview.getWidth() / 2f,
                preview.getHeight() / 2f);
        preview.setTransform(transform);
    }

    private void updateCarBitmapPreviewRequirement() {
        if (recorderService == null) {
            return;
        }
        boolean settingsVisible =
                settingsOverlay != null
                        && settingsOverlay.getVisibility() == View.VISIBLE;
        boolean fullscreenVisible =
                cameraOverlay != null
                        && cameraOverlay.getVisibility() == View.VISIBLE;
        boolean required;
        String reason;
        if (FrameSourceFactory.shouldUseFixture(this)) {
            required = true;
            reason = "fixture source";
        } else if (settingsVisible) {
            required = true;
            reason = "settings overlay visible";
        } else if (fullscreenVisible) {
            required = !isFullscreenNativeRendering();
            reason = required
                    ? "fullscreen native view is not rendering"
                    : "fullscreen native view is rendering";
        } else {
            required = !areNativeTilesRendering();
            reason = required
                    ? "native tiles are not rendering"
                    : "native tiles are rendering";
        }
        recorderService.setCarBitmapPreviewRequired(required);
        if (lastReportedBitmapPreviewRequired == null
                || lastReportedBitmapPreviewRequired != required) {
            lastReportedBitmapPreviewRequired = required;
            Log.i(
                    TAG,
                    "Car bitmap preview fallback "
                            + (required ? "enabled" : "disabled")
                            + ": "
                            + reason);
        }
    }

    private boolean isFullscreenNativeRendering() {
        return fullscreenDirectCamera != null
                && fullscreenDirectCamera.isAvailable()
                && fullscreenDirectLastFrameAtMs != 0L
                && SystemClock.elapsedRealtime() - fullscreenDirectLastFrameAtMs
                        <= NATIVE_RENDER_STALE_MS;
    }

    private boolean areNativeTilesRendering() {
        long now = SystemClock.elapsedRealtime();
        for (int previewPosition = 0;
                previewPosition < directPreviewViews.length;
                previewPosition++) {
            TextureView preview = directPreviewViews[previewPosition];
            if (preview == null || !preview.isAvailable()) {
                return false;
            }
            int cameraIndex =
                    settings.combinedCameraIndex(previewPosition);
            boolean lentToFullscreen =
                    fullscreenDirectCameraIndex == cameraIndex
                            && cameraOverlay != null
                            && cameraOverlay.getVisibility() == View.VISIBLE;
            if (lentToFullscreen) {
                continue;
            }
            long lastFrameAt = directPreviewLastFrameAtMs[previewPosition];
            if (lastFrameAt == 0L
                    || now - lastFrameAt > NATIVE_RENDER_STALE_MS) {
                return false;
            }
        }
        return true;
    }

    private void updateNativePreviewAlpha() {
        long now = SystemClock.elapsedRealtime();
        for (int previewPosition = 0;
                previewPosition < directPreviewViews.length;
                previewPosition++) {
            TextureView preview = directPreviewViews[previewPosition];
            if (preview == null) {
                continue;
            }
            long lastFrameAt = directPreviewLastFrameAtMs[previewPosition];
            boolean rendering =
                    lastFrameAt != 0L
                            && now - lastFrameAt <= NATIVE_RENDER_STALE_MS;
            float alpha = rendering ? 1f : 0f;
            if (preview.getAlpha() != alpha) {
                preview.setAlpha(alpha);
                directPreviewHasFrame[previewPosition] = rendering;
            }
        }
        if (fullscreenDirectCamera != null) {
            boolean rendering =
                    fullscreenDirectLastFrameAtMs != 0L
                            && now - fullscreenDirectLastFrameAtMs
                                    <= NATIVE_RENDER_STALE_MS;
            float alpha = rendering ? 1f : 0f;
            if (fullscreenDirectCamera.getAlpha() != alpha) {
                fullscreenDirectCamera.setAlpha(alpha);
            }
        }
    }

    private void maybeKickNativePreviewRender() {
        if (recorderService == null
                || FrameSourceFactory.shouldUseFixture(this)
                || lastNativeAttachAtMs == 0L
                || SystemClock.elapsedRealtime() - lastNativeAttachAtMs
                        < NATIVE_RENDER_KICK_DELAY_MS) {
            return;
        }
        boolean fullscreenVisible =
                cameraOverlay != null
                        && cameraOverlay.getVisibility() == View.VISIBLE;
        boolean stale =
                fullscreenVisible
                        ? fullscreenDirectCamera != null
                                && fullscreenDirectCamera.isAvailable()
                                && !isFullscreenNativeRendering()
                        : !areNativeTilesRendering();
        if (stale && recorderService.kickNativePreviewRender()) {
            Log.w(
                    TAG,
                    "Requested native preview render kick because attached "
                            + "surfaces produced no frames");
        }
    }

    private View labeledField(String label, View field) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 14, false);
        labelView.setTextColor(color(R.color.text_secondary));
        row.addView(
                labelView,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        row.addView(
                field,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f));
        LinearLayout.LayoutParams helpParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        helpParams.leftMargin = dp(8);
        row.addView(
                settingHelpButton(label, settingHelp(label)),
                helpParams);
        return row;
    }

    private View labeledField(String label, View field, int helpResId) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 14, false);
        labelView.setTextColor(color(R.color.text_secondary));
        row.addView(
                labelView,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        row.addView(
                field,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f));
        LinearLayout.LayoutParams helpParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        helpParams.leftMargin = dp(8);
        row.addView(
                settingHelpButton(label, getString(helpResId)),
                helpParams);
        return row;
    }

    private View labeledFieldWithoutHelp(String label, View field) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 14, false);
        labelView.setTextColor(color(R.color.text_secondary));
        row.addView(
                labelView,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        row.addView(
                field,
                new LinearLayout.LayoutParams(
                        0,
                        dp(48),
                        1f));
        return row;
    }

    private NumericStepper numericStepper(
            NumericStepper.Specification specification) {
        NumericStepper stepper =
                new NumericStepper(this, specification);
        stepper.setListener(
                new NumericStepper.Listener() {
                    @Override
                    public void onValueChanged(int value) {
                        updateSettingsSaveState();
                    }
                });
        return stepper;
    }

    private static int quotaQuarterUnits(long quotaBytes) {
        return (int) Math.round(
                quotaBytes * 4.0 / GIBIBYTE_BYTES);
    }

    private static String formatQuotaQuarterUnits(int quarterUnits) {
        int wholeGigabytes = quarterUnits / 4;
        int remainder = quarterUnits % 4;
        if (remainder == 0) {
            return wholeGigabytes + " GB";
        }
        if (remainder == 2) {
            return String.format(
                    Locale.US,
                    "%.1f GB",
                    quarterUnits / 4.0);
        }
        return String.format(
                Locale.US,
                "%.2f GB",
                quarterUnits / 4.0);
    }

    private IconButton settingHelpButton(
            final String title,
            final String explanation) {
        return iconButton(
                R.drawable.ic_help,
                "Explain " + title,
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        ConfirmationDialog.showInfo(
                                MainActivity.this,
                                title,
                                explanation);
                    }
                });
    }

    private String settingHelp(String label) {
        if (label.equals("Camera names")) {
            return "Names identify the physical camera views in car and phone "
                    + "previews. New MP4 filenames use each valid custom name "
                    + "plus the segment ISO time; invalid filesystem names "
                    + "fall back to Camera-1 through Camera-4.";
        }
        if (label.equals("Recording volume")) {
            return "Chooses where new recording folders are written. Existing "
                    + "recordings remain on their current volume.";
        }
        if (label.equals("Video resolution")) {
            return "Controls detail and file size for all individual camera files "
                    + "and the combined file. Higher detail uses storage faster.";
        }
        if (label.equals("Date and time format")) {
            return "Changes displayed recording dates. Folder names keep their "
                    + "filesystem-safe canonical format.";
        }
        if (label.startsWith("Recorder quota")) {
            return "Maximum recorder space on the selected volume. Cleanup removes "
                    + "only unlocked recorder-owned recordings.";
        }
        if (label.startsWith("Retention")) {
            return "Unlocked recordings older than this many days may be removed "
                    + "automatically. Locked recordings stay protected.";
        }
        if (label.startsWith("Segment length")) {
            return "How long each recording folder runs before a new segment starts. "
                    + "Shorter segments create more files.";
        }
        if (label.startsWith("Minimum volume free")) {
            return "Recording stops before free space falls below this percentage, "
                    + "protecting the car and other apps from a full disk.";
        }
        return "Controls this recorder setting.";
    }

    private View buildCombinedLayoutEditor() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setRowCount(2);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setBackgroundResource(R.drawable.input_background);
        for (int position = 0;
                position < FrameProcessor.CAMERA_COUNT;
                position++) {
            TextView tile = text("", 13, true);
            tile.setGravity(Gravity.CENTER);
            tile.setBackgroundResource(R.drawable.panel_background);
            tile.setPadding(dp(10), dp(8), dp(10), dp(8));
            tile.setCompoundDrawablePadding(dp(5));
            tile.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    R.drawable.ic_camera,
                    0,
                    0);
            tile.setOnTouchListener(
                    new View.OnTouchListener() {
                        private int dragPosition = -1;
                        private float startRawX;
                        private float startRawY;

                        @Override
                        public boolean onTouch(View view, MotionEvent event) {
                            switch (event.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    dragPosition =
                                            positionOfCombinedTile(
                                                    (TextView) view);
                                    if (dragPosition < 0) {
                                        return false;
                                    }
                                    startRawX = event.getRawX();
                                    startRawY = event.getRawY();
                                    beginCombinedLayoutDrag(
                                            view,
                                            dragPosition,
                                            event.getRawX(),
                                            event.getRawY());
                                    view.getParent()
                                            .requestDisallowInterceptTouchEvent(true);
                                    return true;
                                case MotionEvent.ACTION_MOVE:
                                    view.setTranslationX(
                                            event.getRawX() - startRawX);
                                    view.setTranslationY(
                                            event.getRawY() - startRawY);
                                    previewCombinedLayoutSwap(
                                            dragPosition,
                                            event.getRawX(),
                                            event.getRawY());
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    previewCombinedLayoutSwap(
                                            dragPosition,
                                            event.getRawX(),
                                            event.getRawY());
                                    finishCombinedLayoutDrag(
                                            view,
                                            dragPosition,
                                            true);
                                    dragPosition = -1;
                                    return true;
                                case MotionEvent.ACTION_CANCEL:
                                    finishCombinedLayoutDrag(
                                            view,
                                            dragPosition,
                                            false);
                                    dragPosition = -1;
                                    return true;
                                default:
                                    return false;
                            }
                        }
                    });
            combinedLayoutTiles[position] = tile;
            grid.addView(
                    tile,
                    combinedTileLayoutParams(position));
        }
        updateCombinedLayoutTiles();
        return grid;
    }

    private View buildCameraOrientationEditor() {
        LinearLayout editor = vertical();
        editor.setBackgroundResource(R.drawable.input_background);
        editor.setPadding(dp(10), dp(8), dp(10), dp(8));
        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            final int index = cameraIndex;
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text("", 14, true);
            name.setPadding(dp(10), 0, 0, 0);
            cameraOrientationNameViews[cameraIndex] = name;
            row.addView(
                    name,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f));

            IconStateToggle horizontal = new IconStateToggle(
                    this,
                    R.drawable.ic_flip_horizontal,
                    R.drawable.ic_flip_horizontal_active,
                    "Horizontal flip is off for camera " + (cameraIndex + 1),
                    "Horizontal flip is on for camera " + (cameraIndex + 1),
                    IconStateToggle.Tone.STANDARD);
            horizontal.setListener(
                    new IconStateToggle.Listener() {
                        @Override
                        public void onToggleRequested(boolean checked) {
                            cameraHorizontalFlipToggles[index]
                                    .setChecked(checked);
                            updateSettingsSaveState();
                        }
                    });
            cameraHorizontalFlipToggles[cameraIndex] = horizontal;
            LinearLayout.LayoutParams toggleParams =
                    new LinearLayout.LayoutParams(dp(104), dp(58));
            toggleParams.leftMargin = dp(10);
            row.addView(horizontal, toggleParams);

            IconStateToggle vertical = new IconStateToggle(
                    this,
                    R.drawable.ic_flip_vertical,
                    R.drawable.ic_flip_vertical_active,
                    "Vertical flip is off for camera " + (cameraIndex + 1),
                    "Vertical flip is on for camera " + (cameraIndex + 1),
                    IconStateToggle.Tone.STANDARD);
            vertical.setListener(
                    new IconStateToggle.Listener() {
                        @Override
                        public void onToggleRequested(boolean checked) {
                            cameraVerticalFlipToggles[index]
                                    .setChecked(checked);
                            updateSettingsSaveState();
                        }
                    });
            cameraVerticalFlipToggles[cameraIndex] = vertical;
            LinearLayout.LayoutParams verticalParams =
                    new LinearLayout.LayoutParams(dp(104), dp(58));
            verticalParams.leftMargin = dp(10);
            row.addView(vertical, verticalParams);

            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (cameraIndex > 0) {
                rowParams.topMargin = dp(8);
            }
            editor.addView(row, rowParams);
        }
        updateCameraOrientationNames();
        return editor;
    }

    private View buildCameraCropEditor() {
        LinearLayout editor = vertical();
        editor.setBackgroundResource(R.drawable.input_background);
        editor.setPadding(dp(10), dp(8), dp(10), dp(8));

        GridLayout previewGrid =
                new AspectGridLayout(this, 16, 9);
        previewGrid.setColumnCount(2);
        previewGrid.setRowCount(2);
        previewGrid.setBackgroundColor(Color.rgb(5, 13, 23));
        for (int cameraIndex = 0;
                cameraIndex < FrameProcessor.CAMERA_COUNT;
                cameraIndex++) {
            FrameLayout viewport = new FrameLayout(this);
            viewport.setClipChildren(true);
            ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackgroundColor(Color.BLACK);
            fisheyePreviewViews[cameraIndex] = preview;
            viewport.addView(
                    preview,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            TextView name = text(
                    settings.cameraName(cameraIndex),
                    13,
                    true);
            name.setTextColor(Color.WHITE);
            name.setBackgroundColor(Color.argb(165, 5, 13, 23));
            name.setPadding(dp(8), dp(4), dp(8), dp(4));
            fisheyePreviewNameViews[cameraIndex] = name;
            FrameLayout.LayoutParams nameParams =
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.START);
            viewport.addView(name, nameParams);
            GridLayout.LayoutParams viewportParams =
                    new GridLayout.LayoutParams(
                            GridLayout.spec(cameraIndex / 2, 1f),
                            GridLayout.spec(cameraIndex % 2, 1f));
            viewportParams.width = 0;
            viewportParams.height = 0;
            viewportParams.setMargins(dp(1), dp(1), dp(1), dp(1));
            previewGrid.addView(viewport, viewportParams);
        }
        editor.addView(
                previewGrid,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout sliderRow = horizontal();
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        fisheyeCropSlider = new StyledSlider(
                this,
                RecorderSettings.MAX_CAMERA_CROP_PERCENT);
        fisheyeCropSlider.setListener(
                new StyledSlider.Listener() {
                    @Override
                    public void onValueChanged(int value) {
                        updateFisheyeCropPreview(value);
                        if (recorderService != null) {
                            recorderService.setPreviewCropPercentOverride(value);
                        }
                        updateSettingsSaveState();
                    }
                });
        sliderRow.addView(
                fisheyeCropSlider,
                new LinearLayout.LayoutParams(0, dp(58), 1f));
        fisheyeCropValueView = text("0%", 16, true);
        fisheyeCropValueView.setGravity(Gravity.CENTER);
        fisheyeCropValueView.setBackgroundResource(
                R.drawable.card_background);
        LinearLayout.LayoutParams valueParams =
                new LinearLayout.LayoutParams(dp(84), dp(48));
        valueParams.leftMargin = dp(10);
        sliderRow.addView(fisheyeCropValueView, valueParams);
        LinearLayout.LayoutParams sliderParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderParams.topMargin = dp(8);
        editor.addView(sliderRow, sliderParams);
        return editor;
    }

    private GridLayout.LayoutParams combinedTileLayoutParams(
            int position) {
        GridLayout.LayoutParams params =
                new GridLayout.LayoutParams(
                        GridLayout.spec(position / 2, 1f),
                        GridLayout.spec(position % 2, 1f));
        params.width = 0;
        params.height = dp(100);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private int positionOfCombinedTile(TextView tile) {
        for (int position = 0;
                position < combinedLayoutTiles.length;
                position++) {
            if (combinedLayoutTiles[position] == tile) {
                return position;
            }
        }
        return -1;
    }

    private View buildStorageSummary() {
        LinearLayout summary = horizontal();
        storageAvailableView =
                addStorageMetric(summary, getString(R.string.storage_available));
        storageRecorderView =
                addStorageMetric(summary, getString(R.string.storage_recorder));
        storageLockedView =
                addStorageMetric(summary, getString(R.string.storage_locked));
        return summary;
    }

    private View buildStorageDetails() {
        LinearLayout details = vertical();
        LinearLayout locationRow = horizontal();
        storageLocationView =
                addStorageDetailCard(
                        locationRow,
                        R.drawable.ic_folder,
                        getString(R.string.storage_location));
        details.addView(
                locationRow,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams policyParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        policyParams.topMargin = dp(8);
        details.addView(buildStoragePolicyTable(), policyParams);
        return details;
    }

    private View buildStoragePolicyTable() {
        LinearLayout card = vertical();
        card.setBackgroundResource(R.drawable.panel_background);
        card.setPadding(dp(13), dp(11), dp(13), dp(12));

        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_storage);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = text(getString(R.string.storage_policy), 14, true);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = dp(10);
        titleRow.addView(title, titleParams);
        card.addView(titleRow);

        LinearLayout table = horizontal();
        String[] labels =
                new String[]{
                    getString(R.string.storage_total),
                    getString(R.string.storage_quota_label),
                    getString(R.string.storage_retention_label),
                    getString(R.string.storage_reserve),
                    getString(R.string.storage_segment),
                    getString(R.string.storage_resolution)
                };
        for (int index = 0; index < labels.length; index++) {
            LinearLayout cell = vertical();
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundResource(R.drawable.input_background);
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            TextView label = text(labels[index].toUpperCase(Locale.US), 10, true);
            label.setTextColor(color(R.color.text_secondary));
            label.setGravity(Gravity.CENTER);
            cell.addView(label);
            TextView value = text(getString(R.string.loading), 13, true);
            value.setGravity(Gravity.CENTER);
            value.setMaxLines(index == labels.length - 1 ? 2 : 1);
            if (index == labels.length - 1) {
                value.setTextSize(11);
            }
            storagePolicyValueViews[index] = value;
            LinearLayout.LayoutParams valueParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.topMargin = dp(4);
            cell.addView(value, valueParams);
            LinearLayout.LayoutParams cellParams =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(78),
                            index == labels.length - 1 ? 2f : 1f);
            cellParams.setMargins(dp(2), 0, dp(2), 0);
            table.addView(cell, cellParams);
        }
        LinearLayout.LayoutParams tableParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tableParams.topMargin = dp(8);
        card.addView(table, tableParams);
        return card;
    }

    private TextView addStorageDetailCard(
            LinearLayout row,
            int iconResource,
            String title) {
        LinearLayout card = horizontal();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.panel_background);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        card.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout copy = vertical();
        TextView titleView = text(title, 14, true);
        titleView.setTextColor(color(R.color.text_primary));
        copy.addView(titleView);
        TextView details = text(getString(R.string.loading), 13, false);
        details.setTextColor(color(R.color.text_secondary));
        details.setLineSpacing(0f, 1.08f);
        LinearLayout.LayoutParams detailTextParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        detailTextParams.topMargin = dp(4);
        copy.addView(details, detailTextParams);
        LinearLayout.LayoutParams copyParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        copyParams.leftMargin = dp(12);
        card.addView(copy, copyParams);
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(0, dp(108), 1f);
        cardParams.setMargins(dp(3), 0, dp(3), 0);
        row.addView(card, cardParams);
        return details;
    }

    private TextView addStorageMetric(
            LinearLayout summary,
            String label) {
        LinearLayout card = vertical();
        card.setGravity(Gravity.CENTER);
        card.setBackgroundResource(R.drawable.panel_background);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        TextView title = text(label.toUpperCase(Locale.US), 11, true);
        title.setTextColor(color(R.color.text_secondary));
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView value = text("—", 18, true);
        value.setGravity(Gravity.CENTER);
        card.addView(value);
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(0, dp(76), 1f);
        cardParams.setMargins(dp(3), 0, dp(3), 0);
        summary.addView(card, cardParams);
        return value;
    }

    private void resetDraggedTile(View view) {
        view.animate().cancel();
        view.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .rotation(0f)
                .setDuration(140L)
                .start();
        view.setElevation(0f);
    }

    private void beginCombinedLayoutDrag(
            View view,
            int sourcePosition,
            float rawX,
            float rawY) {
        combinedDragTargetPosition = -1;
        int[] location = new int[2];
        for (int position = 0;
                position < combinedLayoutTiles.length;
                position++) {
            TextView tile = combinedLayoutTiles[position];
            tile.getLocationOnScreen(location);
            combinedDragCornerGeometry[position][0] =
                    location[0] - tile.getTranslationX() + tile.getWidth() / 2f;
            combinedDragCornerGeometry[position][1] =
                    location[1] - tile.getTranslationY() + tile.getHeight() / 2f;
            combinedDragCornerGeometry[position][2] = tile.getWidth();
            combinedDragCornerGeometry[position][3] = tile.getHeight();
        }
        combinedDragCenterOffsetX =
                combinedDragCornerGeometry[sourcePosition][0] - rawX;
        combinedDragCenterOffsetY =
                combinedDragCornerGeometry[sourcePosition][1] - rawY;
        view.animate().cancel();
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.setAlpha(0.96f);
        view.setElevation(dp(18));
    }

    private void previewCombinedLayoutSwap(
            int sourcePosition,
            float rawX,
            float rawY) {
        int targetPosition =
                findCombinedLayoutTarget(sourcePosition, rawX, rawY);
        if (targetPosition == combinedDragTargetPosition) {
            return;
        }
        resetCombinedLayoutPreviewTarget();
        combinedDragTargetPosition = targetPosition;
        if (targetPosition < 0) {
            return;
        }
        TextView sourceTile = combinedLayoutTiles[sourcePosition];
        TextView targetTile = combinedLayoutTiles[targetPosition];
        int[] sourceLocation = new int[2];
        int[] targetLocation = new int[2];
        sourceTile.getLocationOnScreen(sourceLocation);
        targetTile.getLocationOnScreen(targetLocation);
        float sourceBaseX =
                sourceLocation[0] - sourceTile.getTranslationX();
        float sourceBaseY =
                sourceLocation[1] - sourceTile.getTranslationY();
        float offsetX = sourceBaseX - targetLocation[0];
        float offsetY = sourceBaseY - targetLocation[1];
        targetTile.animate().cancel();
        targetTile.setElevation(dp(8));
        targetTile.animate()
                .translationX(offsetX)
                .translationY(offsetY)
                .scaleX(0.99f)
                .scaleY(0.99f)
                .rotation(0.45f)
                .setDuration(210L)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                wiggleCombinedLayoutTarget(targetTile, -0.45f);
                            }
                        })
                .start();
    }

    private int findCombinedLayoutTarget(
            int sourcePosition,
            float rawX,
            float rawY) {
        float centerX = rawX + combinedDragCenterOffsetX;
        float centerY = rawY + combinedDragCenterOffsetY;
        if (combinedDragTargetPosition >= 0) {
            float[] target =
                    combinedDragCornerGeometry[combinedDragTargetPosition];
            if (Math.abs(centerX - target[0]) <= target[2] * 0.82f
                    && Math.abs(centerY - target[1]) <= target[3] * 0.82f) {
                return combinedDragTargetPosition;
            }
        }
        for (int position = 0;
                position < combinedLayoutTiles.length;
                position++) {
            if (position == sourcePosition) {
                continue;
            }
            float[] target = combinedDragCornerGeometry[position];
            if (Math.abs(centerX - target[0]) <= target[2] * 0.4f
                    && Math.abs(centerY - target[1]) <= target[3] * 0.4f) {
                return position;
            }
        }
        return -1;
    }

    private void wiggleCombinedLayoutTarget(
            final TextView tile,
            final float rotation) {
        if (combinedDragTargetPosition < 0
                || combinedLayoutTiles[combinedDragTargetPosition] != tile) {
            return;
        }
        tile.animate()
                .rotation(rotation)
                .setDuration(240L)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                wiggleCombinedLayoutTarget(
                                        tile,
                                        -rotation);
                            }
                        })
                .start();
    }

    private void finishCombinedLayoutDrag(
            View draggedView,
            int sourcePosition,
            boolean commitPreview) {
        int targetPosition = combinedDragTargetPosition;
        if (commitPreview && targetPosition >= 0) {
            combinedDragTargetPosition = -1;
            TextView sourceTile =
                    combinedLayoutTiles[sourcePosition];
            TextView targetTile =
                    combinedLayoutTiles[targetPosition];
            int[] sourceVisualLocation = new int[2];
            int[] targetVisualLocation = new int[2];
            sourceTile.getLocationOnScreen(sourceVisualLocation);
            targetTile.getLocationOnScreen(targetVisualLocation);
            sourceTile.animate().cancel();
            targetTile.animate().cancel();
            int cameraIndex = combinedLayoutDraft[sourcePosition];
            combinedLayoutDraft[sourcePosition] =
                    combinedLayoutDraft[targetPosition];
            combinedLayoutDraft[targetPosition] = cameraIndex;
            combinedLayoutTiles[sourcePosition] = targetTile;
            combinedLayoutTiles[targetPosition] = sourceTile;
            GridLayout grid = (GridLayout) sourceTile.getParent();
            sourceTile.setTranslationX(0f);
            sourceTile.setTranslationY(0f);
            targetTile.setTranslationX(0f);
            targetTile.setTranslationY(0f);
            sourceTile.setLayoutParams(
                    combinedTileLayoutParams(targetPosition));
            targetTile.setLayoutParams(
                    combinedTileLayoutParams(sourcePosition));
            updateCombinedLayoutTiles();
            updateSettingsSaveState();
            animateCommittedCombinedLayoutSwap(
                    grid,
                    sourceTile,
                    targetTile,
                    sourceVisualLocation,
                    targetVisualLocation);
            return;
        }
        resetCombinedLayoutPreviewTarget();
        resetDraggedTile(draggedView);
    }

    private void animateCommittedCombinedLayoutSwap(
            final GridLayout grid,
            final TextView sourceTile,
            final TextView targetTile,
            final int[] sourceVisualLocation,
            final int[] targetVisualLocation) {
        grid.getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                grid.getViewTreeObserver()
                                        .removeOnPreDrawListener(this);
                                animateTileIntoCommittedPosition(
                                        sourceTile,
                                        sourceVisualLocation);
                                animateTileIntoCommittedPosition(
                                        targetTile,
                                        targetVisualLocation);
                                return true;
                            }
                        });
        grid.requestLayout();
    }

    private void animateTileIntoCommittedPosition(
            final TextView tile,
            int[] previousVisualLocation) {
        int[] committedLocation = new int[2];
        tile.getLocationOnScreen(committedLocation);
        tile.setTranslationX(
                previousVisualLocation[0] - committedLocation[0]);
        tile.setTranslationY(
                previousVisualLocation[1] - committedLocation[1]);
        tile.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .rotation(0f)
                .setDuration(180L)
                .withEndAction(
                        new Runnable() {
                            @Override
                            public void run() {
                                tile.setElevation(0f);
                            }
                        })
                .start();
    }

    private void resetCombinedLayoutPreviewTarget() {
        if (combinedDragTargetPosition < 0) {
            return;
        }
        TextView tile =
                combinedLayoutTiles[combinedDragTargetPosition];
        combinedDragTargetPosition = -1;
        tile.animate().cancel();
        tile.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .rotation(0f)
                .setDuration(140L)
                .start();
        tile.setElevation(0f);
    }

    private void updateCombinedLayoutTiles() {
        String[] corners =
                new String[]{"Top left", "Top right", "Bottom left", "Bottom right"};
        for (int position = 0;
                position < combinedLayoutTiles.length;
                position++) {
            TextView tile = combinedLayoutTiles[position];
            if (tile == null) {
                continue;
            }
            int cameraIndex = combinedLayoutDraft[position];
            String name =
                    cameraNameInputs[cameraIndex] == null
                            ? settings.cameraName(cameraIndex)
                            : cameraNameInputs[cameraIndex]
                                    .getText()
                                    .toString()
                                    .trim();
            if (name.isEmpty()) {
                name = getString(R.string.camera_number, cameraIndex + 1);
            }
            tile.setText(name + "\n" + corners[position]);
        }
    }

    private void updateCameraOrientationNames() {
        for (int cameraIndex = 0;
                cameraIndex < cameraOrientationNameViews.length;
                cameraIndex++) {
            String name =
                    cameraNameInputs[cameraIndex] == null
                            ? settings.cameraName(cameraIndex)
                            : cameraNameInputs[cameraIndex]
                                    .getText()
                                    .toString()
                                    .trim();
            String displayedName =
                    name.isEmpty()
                            ? getString(R.string.camera_number, cameraIndex + 1)
                            : name;
            TextView orientationName =
                    cameraOrientationNameViews[cameraIndex];
            if (orientationName != null) {
                orientationName.setText(displayedName);
            }
            TextView cropPreviewName =
                    fisheyePreviewNameViews[cameraIndex];
            if (cropPreviewName != null) {
                cropPreviewName.setText(displayedName);
            }
        }
    }

    private void updateFisheyeCropPreview(int cropPercent) {
        if (fisheyeCropValueView != null) {
            fisheyeCropValueView.setText(cropPercent + "%");
        }
    }

    private View buildPhoneAccessSetting() {
        LinearLayout card = horizontal();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.panel_background);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout addressBlock = vertical();
        TextView label = text(getString(R.string.phone_access_label), 15, true);
        addressBlock.addView(label);
        settingsPhoneUrlView = text(getString(R.string.phone_access_url_loading), 13, true);
        settingsPhoneUrlView.setTextColor(color(R.color.text_secondary));
        settingsPhoneUrlView.setTextIsSelectable(true);
        settingsPhoneUrlView.setSingleLine(true);
        settingsPhoneUrlView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        settingsPhoneUrlView.setBackgroundResource(R.drawable.input_background);
        settingsPhoneUrlView.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams urlParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(48));
        urlParams.topMargin = dp(7);
        addressBlock.addView(settingsPhoneUrlView, urlParams);
        card.addView(
                addressBlock,
                new LinearLayout.LayoutParams(dp(460), dp(86)));

        card.addView(
                new View(this),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f));

        settingsPhoneQrView = PhoneAccessQrView.create(this);
        settingsPhoneQrContainer = new FrameLayout(this);
        settingsPhoneQrContainer.setBackgroundColor(Color.WHITE);
        settingsPhoneQrContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        settingsPhoneQrContainer.addView(
                settingsPhoneQrView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams qrParams =
                new LinearLayout.LayoutParams(dp(84), dp(84));
        qrParams.leftMargin = dp(16);
        card.addView(settingsPhoneQrContainer, qrParams);

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        controls.addView(
                phoneAccessToggle,
                new LinearLayout.LayoutParams(dp(100), dp(58)));

        phoneAccessPinView = new PinDisplay(this, true);
        phoneAccessPinView.setBackgroundResource(R.drawable.input_background);
        LinearLayout.LayoutParams pinParams =
                new LinearLayout.LayoutParams(dp(210), dp(48));
        pinParams.leftMargin = dp(10);
        controls.addView(phoneAccessPinView, pinParams);

        IconButton regenerate = iconButton(
                R.drawable.ic_refresh,
                "Regenerate phone PIN",
                IconButton.Tone.DEFAULT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        confirmPhonePinRegeneration();
                    }
                });
        LinearLayout.LayoutParams regenerateParams =
                new LinearLayout.LayoutParams(dp(52), dp(52));
        regenerateParams.leftMargin = dp(8);
        controls.addView(regenerate, regenerateParams);
        LinearLayout.LayoutParams helpParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        helpParams.leftMargin = dp(8);
        controls.addView(
                settingHelpButton(
                        getString(R.string.phone_access_label),
                        getString(R.string.help_phone_access)),
                helpParams);
        LinearLayout.LayoutParams controlsParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.leftMargin = dp(16);
        card.addView(controls, controlsParams);
        return card;
    }

    private void refreshSettingsPhoneAccessDetails() {
        if (settingsPhoneUrlView == null
                || settingsPhoneQrContainer == null
                || settingsPhoneQrView == null) {
            return;
        }
        if (recorderService == null || !phoneAccessToggle.isChecked()) {
            settingsPhoneUrlView.setText(getString(R.string.phone_access_is_off));
            settingsPhoneQrContainer.setVisibility(View.GONE);
            return;
        }
        try {
            String url = recorderService.getPhoneAccessUrl();
            settingsPhoneUrlView.setText(url);
            settingsPhoneQrContainer.setVisibility(View.VISIBLE);
            PhoneAccessQrView.load(settingsPhoneQrView, url);
        } catch (IOException exception) {
            settingsPhoneUrlView.setText(getString(R.string.phone_access_unavailable));
            settingsPhoneQrContainer.setVisibility(View.GONE);
        }
    }

    private void confirmPhonePinRegeneration() {
        ConfirmationDialog.show(
                this,
                getString(R.string.confirm_pin_title),
                getString(R.string.confirm_pin_message),
                getString(R.string.action_regenerate),
                ConfirmationDialog.Tone.WARNING,
                new Runnable() {
                    @Override
                    public void run() {
                        if (recorderService == null) {
                            showMessage(getString(R.string.msg_service_unavailable));
                            return;
                        }
                        String pin = recorderService.regeneratePhoneAccessPin();
                        settings = RecorderSettings.load(MainActivity.this);
                        phoneAccessPinView.setPin(pin);
                        showMessage(getString(R.string.msg_pin_regenerated));
                    }
                });
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

    private TextView sectionTitle(String value) {
        TextView title = text(value, 18, true);
        title.setPadding(0, dp(12), 0, dp(5));
        return title;
    }

    private View sectionTitleWithHelp(
            String value,
            String explanation) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                sectionTitle(value),
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));
        row.addView(
                settingHelpButton(value, explanation),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        return row;
    }

    private EditText textInput() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(color(R.color.text_primary));
        input.setHintTextColor(color(R.color.text_secondary));
        input.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        input.setBackgroundResource(R.drawable.input_background);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return input;
    }

    private void updateRecordingControls(CameraRecorderService.Mode mode) {
        if (recordingToggle == null) {
            return;
        }
        boolean recording = mode == CameraRecorderService.Mode.RECORDING;
        boolean parking = mode == CameraRecorderService.Mode.PARKING_STANDBY
                || mode == CameraRecorderService.Mode.PARKING_RECORDING;
        recordingToggle.setChecked(recording);
        recordingToggle.setEnabled(!parking);
        if (parkingToggle != null) {
            parkingToggle.setChecked(parking);
            parkingToggle.setEnabled(!recording);
        }
    }

    private void setControlsColumnCollapsed(final boolean collapsed) {
        if (controlsColumn == null
                || previewColumn == null
                || controlsColumnAnimating
                || controlsColumnCollapsed == collapsed) {
            return;
        }
        controlsColumnCollapsed = collapsed;
        controlsColumnAnimating = true;
        controlsVisibilityButton.setEnabled(false);
        controlsVisibilityButton.setIconResource(
                collapsed
                        ? R.drawable.ic_panel_right_expand
                        : R.drawable.ic_panel_right_collapse);
        controlsVisibilityButton.setContentDescription(
                collapsed
                        ? "Show controls and recordings"
                        : "Hide controls and recordings");

        final LinearLayout.LayoutParams previewParams =
                (LinearLayout.LayoutParams) previewColumn.getLayoutParams();
        final LinearLayout.LayoutParams controlsParams =
                (LinearLayout.LayoutParams) controlsColumn.getLayoutParams();
        final float startPreviewWeight = previewParams.weight;
        final float startControlsWeight = controlsParams.weight;
        final float endPreviewWeight = collapsed ? 5f : 3f;
        final float endControlsWeight = collapsed ? 0f : 2f;
        final float startAlpha = controlsColumn.getAlpha();
        final float endAlpha = collapsed ? 0f : 1f;
        final float startTranslationX = controlsColumn.getTranslationX();
        final float endTranslationX = collapsed ? dp(28) : 0f;
        if (!collapsed) {
            controlsColumn.setVisibility(View.VISIBLE);
        }

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(260L);
        animator.addUpdateListener(
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        float progress =
                                (Float) animation.getAnimatedValue();
                        previewParams.weight =
                                startPreviewWeight
                                        + (endPreviewWeight
                                                - startPreviewWeight)
                                                * progress;
                        controlsParams.weight =
                                startControlsWeight
                                        + (endControlsWeight
                                                - startControlsWeight)
                                                * progress;
                        previewColumn.setLayoutParams(previewParams);
                        controlsColumn.setLayoutParams(controlsParams);
                        controlsColumn.setAlpha(
                                startAlpha
                                        + (endAlpha - startAlpha)
                                                * progress);
                        controlsColumn.setTranslationX(
                                startTranslationX
                                        + (endTranslationX
                                                - startTranslationX)
                                                * progress);
                    }
                });
        animator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (collapsed) {
                            controlsColumn.setVisibility(View.GONE);
                        }
                        controlsColumn.setAlpha(endAlpha);
                        controlsColumn.setTranslationX(endTranslationX);
                        controlsColumnAnimating = false;
                        controlsVisibilityButton.setEnabled(true);
                    }
                });
        animator.start();
    }

    private void recyclePreviewFrames(Bitmap[] frames) {
        if (frames == null) {
            return;
        }
        if (recorderService != null) {
            recorderService.releasePreviewFrames(frames);
            return;
        }
        for (Bitmap frame : frames) {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
        }
    }

    private int findPreviewPosition(int cameraIndex) {
        for (int position = 0;
                position < FrameProcessor.CAMERA_COUNT;
                position++) {
            if (settings.combinedCameraIndex(position) == cameraIndex) {
                return position;
            }
        }
        return -1;
    }

    private void updatePreviewCameraNames() {
        for (int position = 0;
                position < cameraNameViews.length;
                position++) {
            TextView nameView = cameraNameViews[position];
            if (nameView != null) {
                nameView.setText(
                        settings.cameraName(
                                settings.combinedCameraIndex(position)));
            }
        }
    }

    private void populateInputs() {
        populatingSettings = true;
        combinedLayoutDraft = settings.combinedLayout();
        quotaStepper.setValue(quotaQuarterUnits(settings.quotaBytes));
        retentionStepper.setValue(settings.retentionDays);
        segmentStepper.setValue(settings.segmentMinutes);
        minFreeStepper.setValue(settings.minFreePercent);
        if (parkingImpactStepper != null) {
            parkingImpactStepper.setValue(
                    Math.round(settings.parkingImpactThresholdG * 10));
        }
        if (parkingDurationStepper != null) {
            parkingDurationStepper.setValue(settings.parkingRecordingSeconds);
        }
        if (parkingAutoLockCheckbox != null) {
            parkingAutoLockCheckbox.setChecked(settings.parkingAutoLock);
        }
        if (cameraMotionEnabledCheckbox != null) {
            cameraMotionEnabledCheckbox.setChecked(settings.cameraMotionEnabled);
        }
        if (cameraMotionSensitivityStepper != null) {
            cameraMotionSensitivityStepper.setValue(settings.cameraMotionSensitivity);
        }
        if (telegramEnabledCheckbox != null) {
            telegramEnabledCheckbox.setChecked(settings.telegramEnabled);
        }
        if (telegramBotTokenInput != null) {
            telegramBotTokenInput.setText(settings.telegramBotToken);
        }
        if (telegramChatIdInput != null) {
            telegramChatIdInput.setText(settings.telegramChatId);
        }
        if (mqttEnabledCheckbox != null) {
            mqttEnabledCheckbox.setChecked(settings.mqttEnabled);
        }
        if (mqttHostInput != null) {
            mqttHostInput.setText(settings.mqttHost);
        }
        if (mqttPortStepper != null) {
            mqttPortStepper.setValue(settings.mqttPort);
        }
        if (mqttUsernameInput != null) {
            mqttUsernameInput.setText(settings.mqttUsername);
        }
        if (mqttPasswordInput != null) {
            mqttPasswordInput.setText(settings.mqttPassword);
        }
        if (mqttTopicPrefixInput != null) {
            mqttTopicPrefixInput.setText(settings.mqttTopicPrefix);
        }
        if (cloudflareEnabledCheckbox != null) {
            cloudflareEnabledCheckbox.setChecked(settings.cloudflareEnabled);
        }
        if (gpsOverlayEnabledCheckbox != null) {
            gpsOverlayEnabledCheckbox.setChecked(settings.gpsOverlayEnabled);
        }
        if (gpsSpeedUnitSpinner != null) {
            SpeedUnitAdapter adapter = (SpeedUnitAdapter) gpsSpeedUnitSpinner.getAdapter();
            gpsSpeedUnitSpinner.setSelection(adapter.indexOf(settings.gpsSpeedUnit));
        }
        if (gpsShowCoordinatesCheckbox != null) {
            gpsShowCoordinatesCheckbox.setChecked(settings.gpsShowCoordinates);
        }
        if (gpsTrackEnabledCheckbox != null) {
            gpsTrackEnabledCheckbox.setChecked(settings.gpsTrackEnabled);
        }
        if (vehicleModelSpinner != null) {
            VehicleProfileAdapter adapter = (VehicleProfileAdapter) vehicleModelSpinner.getAdapter();
            vehicleModelSpinner.setSelection(adapter.indexOf(settings.vehicleModelId));
        }
        for (int index = 0; index < cameraNameInputs.length; index++) {
            if (cameraNameInputs[index] != null) {
                cameraNameInputs[index].setText(settings.cameraName(index));
            }
            if (cameraHorizontalFlipToggles[index] != null) {
                cameraHorizontalFlipToggles[index]
                        .setChecked(settings.cameraFlipHorizontal(index));
            }
            if (cameraVerticalFlipToggles[index] != null) {
                cameraVerticalFlipToggles[index]
                        .setChecked(settings.cameraFlipVertical(index));
            }
        }
        if (fisheyeCropSlider != null) {
            fisheyeCropSlider.setValue(
                    settings.fisheyeCropPercent());
            updateFisheyeCropPreview(
                    settings.fisheyeCropPercent());
        }
        updatePreviewCameraNames();
        updateCameraOrientationNames();
        updateCombinedLayoutTiles();
        if (phoneAccessToggle != null) {
            phoneAccessToggle.setChecked(settings.phoneAccessEnabled);
        }
        if (phoneAccessPinView != null) {
            if (settings.phoneAccessPin.isEmpty()) {
                phoneAccessPinView.setUnavailableText("pending");
            } else {
                phoneAccessPinView.setPin(settings.phoneAccessPin);
            }
        }
        VideoResolution[] resolutions = VideoResolution.values();
        for (int index = 0; index < resolutions.length; index++) {
            if (resolutions[index] == settings.resolution) {
                resolutionSpinner.setSelection(index);
                break;
            }
        }
        DisplayDateFormat[] formats = DisplayDateFormat.values();
        for (int index = 0; index < formats.length; index++) {
            if (formats[index] == settings.dateFormat) {
                dateFormatSpinner.setSelection(index);
                break;
            }
        }
        populatingSettings = false;
        refreshSettingsPhoneAccessDetails();
        updateSettingsSaveState();
    }

    private void refreshVolumes() {
        if (recorderService == null) {
            return;
        }
        volumes = recorderService.getVolumes();
        VolumeAdapter adapter = new VolumeAdapter(this, volumes);
        volumeSpinner.setAdapter(adapter);
        for (int index = 0; index < volumes.size(); index++) {
            if (volumes.get(index).index == settings.volumeIndex) {
                volumeSpinner.setSelection(index);
                break;
            }
        }
        volumeSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        refreshStorage();
                        updateSettingsSaveState();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        // Keep the persisted volume when no volume is selectable.
                    }
                });
    }

    private AdapterView.OnItemSelectedListener settingsSelectionListener(
            final boolean refreshStorageOnChange) {
        return new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent,
                    View view,
                    int position,
                    long id) {
                if (refreshStorageOnChange) {
                    refreshStorage();
                }
                updateSettingsSaveState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateSettingsSaveState();
            }
        };
    }

    private TextWatcher settingsChangeWatcher() {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateSettingsSaveState();
            }
        };
    }

    private void updateSettingsSaveState() {
        if (settingsSaveButton == null
                || quotaStepper == null
                || retentionStepper == null
                || segmentStepper == null
                || minFreeStepper == null
                || volumeSpinner == null
                || resolutionSpinner == null
                || dateFormatSpinner == null
                || phoneAccessToggle == null
                || populatingSettings) {
            return;
        }
        RecorderSettings draft = readSettings(false);
        settingsSaveButton.setEnabled(!sameSettings(settings, draft));
    }

    private boolean sameSettings(
            RecorderSettings left,
            RecorderSettings right) {
        return left.volumeIndex == right.volumeIndex
                && left.quotaBytes == right.quotaBytes
                && left.retentionDays == right.retentionDays
                && left.segmentMinutes == right.segmentMinutes
                && left.minFreePercent == right.minFreePercent
                && left.resolution == right.resolution
                && left.continuousRecordingEnabled
                        == right.continuousRecordingEnabled
                && left.phoneAccessEnabled == right.phoneAccessEnabled
                && left.dateFormat == right.dateFormat
                && Arrays.equals(left.cameraNames(), right.cameraNames())
                && Arrays.equals(left.combinedLayout(), right.combinedLayout())
                && Arrays.equals(
                        left.cameraFlipHorizontal(),
                        right.cameraFlipHorizontal())
                && Arrays.equals(
                        left.cameraFlipVertical(),
                        right.cameraFlipVertical())
                && left.fisheyeCropPercent()
                        == right.fisheyeCropPercent()
                && Math.round(left.parkingImpactThresholdG * 10)
                        == Math.round(right.parkingImpactThresholdG * 10)
                && left.parkingRecordingSeconds == right.parkingRecordingSeconds
                && left.telegramEnabled == right.telegramEnabled
                && left.telegramBotToken.equals(right.telegramBotToken)
                && left.telegramChatId.equals(right.telegramChatId)
                && left.mqttEnabled == right.mqttEnabled
                && left.mqttHost.equals(right.mqttHost)
                && left.mqttPort == right.mqttPort
                && left.mqttUsername.equals(right.mqttUsername)
                && left.mqttPassword.equals(right.mqttPassword)
                && left.mqttTopicPrefix.equals(right.mqttTopicPrefix)
                && left.cloudflareEnabled == right.cloudflareEnabled
                && left.cameraMotionEnabled == right.cameraMotionEnabled
                && left.cameraMotionSensitivity == right.cameraMotionSensitivity
                && left.gpsOverlayEnabled == right.gpsOverlayEnabled
                && left.gpsSpeedUnit.equals(right.gpsSpeedUnit)
                && left.gpsShowCoordinates == right.gpsShowCoordinates
                && left.gpsTrackEnabled == right.gpsTrackEnabled
                && left.vehicleModelId.equals(right.vehicleModelId);
    }

    private void refreshStorage() {
        if (recorderService == null
                || storagePolicyValueViews[0] == null
                || segmentList == null) {
            return;
        }
        long startedNanos = System.nanoTime();
        try {
            RecorderSettings displayedSettings =
                    settingsOverlay != null
                                    && settingsOverlay.getVisibility()
                                            == View.VISIBLE
                            ? readSettings(false)
                            : settings;
            StorageRepository.StorageSnapshot snapshot =
                    recorderService.getStorageSnapshot(displayedSettings);
            storageAvailableView.setText(
                    StorageRepository.formatBytes(snapshot.availableBytes));
            storageRecorderView.setText(
                    StorageRepository.formatBytes(snapshot.recorderBytes));
            storageLockedView.setText(
                    StorageRepository.formatBytes(snapshot.lockedBytes));
            storageLocationView.setText(
                    snapshot.recorderRoot.getAbsolutePath());
            String[] policyValues =
                    new String[]{
                        StorageRepository.formatBytes(snapshot.totalBytes),
                        StorageRepository.formatBytes(displayedSettings.quotaBytes),
                        displayedSettings.retentionDays + " days",
                        displayedSettings.minFreePercent + "%",
                        displayedSettings.segmentMinutes + " min",
                        displayedSettings.resolution.dimensionsLabel()
                    };
            for (int index = 0;
                    index < storagePolicyValueViews.length;
                    index++) {
                storagePolicyValueViews[index].setText(policyValues[index]);
            }
            populateSegments(displayedSettings);
            Log.i(
                    TAG,
                    "Car storage UI refreshed: recordings="
                            + displayedRecordings.size()
                            + " elapsedMs="
                            + ((System.nanoTime() - startedNanos)
                                    / 1_000_000L));
        } catch (Exception exception) {
            Log.e(TAG, "Car storage UI refresh failed", exception);
            storagePolicyValueViews[0].setText("Storage error");
            for (int index = 1;
                    index < storagePolicyValueViews.length;
                    index++) {
                storagePolicyValueViews[index].setText("Unavailable");
            }
        }
    }

    private void populateSegments(final RecorderSettings displayedSettings)
            throws IOException {
        List<StorageRepository.SegmentInfo> segments =
                recorderService.listSegments(displayedSettings);
        StringBuilder fingerprint = new StringBuilder();
        for (StorageRepository.SegmentInfo segment : segments) {
            fingerprint.append(segment.directory.getName())
                    .append('|')
                    .append(segment.locked)
                    .append('|')
                    .append(segment.active)
                    .append('|')
                    .append(segment.incomplete)
                    .append('|')
                    // The active segment's size grows continuously and is not
                    // displayed on its row, so it is excluded to avoid
                    // rebuilding the list when nothing visible changed.
                    .append(segment.active ? 0L : segment.sizeBytes)
                    .append(';');
        }
        fingerprint.append(displayedSettings.dateFormat.id);
        String rendered = fingerprint.toString();
        boolean firstLoad = !initialSegmentsLoaded;
        displayedRecordings = segments;
        segmentListSettings = displayedSettings;
        initialSegmentsLoaded = true;
        if (!firstLoad && rendered.equals(displayedSegmentsFingerprint)) {
            return;
        }
        displayedSegmentsFingerprint = rendered;
        renderVisibleSegments(true);
    }

    private void renderSegmentSkeleton() {
        if (segmentList == null) {
            return;
        }
        segmentWindowStart = -1;
        segmentWindowEnd = -1;
        recycleSegmentListThumbnails(segmentList);
        segmentList.removeAllViews();
        segmentTopSpacer = null;
        segmentBottomSpacer = null;
        skeletonViews.clear();
        int rowHeight = dp(RECORDING_ROW_HEIGHT_DP);
        int viewportHeight =
                recordingsScroll != null && recordingsScroll.getHeight() > 0
                        ? recordingsScroll.getHeight()
                        : rowHeight * 5;
        int rows = Math.max(3, viewportHeight / rowHeight + 1);
        for (int index = 0; index < rows; index++) {
            segmentList.addView(buildSkeletonCard());
        }
        startSkeletonPulse();
    }

    private View buildSkeletonCard() {
        LinearLayout card = vertical();
        card.setBackgroundResource(R.drawable.card_background);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(RECORDING_ROW_HEIGHT_DP) - dp(12));
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        card.addView(skeletonBlock(dp(180), dp(18), 0));
        card.addView(skeletonBlock(dp(120), dp(13), dp(10)));
        card.addView(skeletonBlock(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
                dp(14)));
        return card;
    }

    private View skeletonBlock(int width, int height, int topMargin) {
        View block = new View(this);
        block.setBackgroundResource(R.drawable.skeleton_block_background);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(width, height);
        params.topMargin = topMargin;
        block.setLayoutParams(params);
        skeletonViews.add(block);
        return block;
    }

    private void startSkeletonPulse() {
        if (skeletonPulseAnimator != null) {
            skeletonPulseAnimator.cancel();
        }
        skeletonPulseAnimator = ValueAnimator.ofFloat(0.4f, 1f);
        skeletonPulseAnimator.setDuration(750L);
        skeletonPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        skeletonPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        skeletonPulseAnimator.addUpdateListener(
                new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        float alpha = (float) animation.getAnimatedValue();
                        for (View block : skeletonViews) {
                            block.setAlpha(alpha);
                        }
                    }
                });
        skeletonPulseAnimator.start();
    }

    private void clearSkeleton() {
        if (skeletonPulseAnimator != null) {
            skeletonPulseAnimator.cancel();
            skeletonPulseAnimator = null;
        }
        skeletonViews.clear();
    }

    private void renderVisibleSegments(boolean force) {
        if (segmentList == null
                || segmentListSettings == null
                || recordingsScroll == null) {
            return;
        }
        if (!initialSegmentsLoaded) {
            renderSegmentSkeleton();
            return;
        }
        clearSkeleton();
        final RecorderSettings displayedSettings = segmentListSettings;
        int rowHeight = dp(RECORDING_ROW_HEIGHT_DP);
        int viewportHeight =
                recordingsScroll.getHeight() > 0
                        ? recordingsScroll.getHeight()
                        : rowHeight * 5;
        int listScrollY =
                Math.max(
                        0,
                        recordingsScroll.getScrollY()
                                - segmentList.getTop());
        int firstVisible = listScrollY / rowHeight;
        int start =
                Math.max(
                        0,
                        firstVisible - RECORDING_ROW_BUFFER);
        int visibleRows =
                (viewportHeight + rowHeight - 1) / rowHeight;
        int end =
                Math.min(
                        displayedRecordings.size(),
                        firstVisible
                                + visibleRows
                                + RECORDING_ROW_BUFFER);
        if (!force
                && start == segmentWindowStart
                && end == segmentWindowEnd) {
            return;
        }
        boolean windowsOverlap =
                !force
                        && segmentTopSpacer != null
                        && segmentWindowStart >= 0
                        && start < segmentWindowEnd
                        && end > segmentWindowStart;
        if (windowsOverlap) {
            // Scrolling: reuse the surviving row views and only add or remove
            // the rows that entered or left the window. Rebuilding every row
            // on each scroll step restarts thumbnail loads and flickers.
            updateSegmentWindowIncrementally(start, end, rowHeight);
            return;
        }
        segmentWindowStart = start;
        segmentWindowEnd = end;
        recycleSegmentListThumbnails(segmentList);
        segmentList.removeAllViews();
        segmentTopSpacer = null;
        segmentBottomSpacer = null;
        ++segmentPreviewGeneration;
        if (displayedRecordings.isEmpty()) {
            TextView empty = text("No recordings yet", 14, false);
            empty.setTextColor(color(R.color.text_secondary));
            segmentList.addView(empty);
            return;
        }
        segmentTopSpacer = new View(this);
        segmentList.addView(
                segmentTopSpacer,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        start * rowHeight));
        for (int index = start; index < end; index++) {
            segmentList.addView(
                    buildSegmentCard(index, displayedSettings, rowHeight));
        }
        segmentBottomSpacer = new View(this);
        segmentList.addView(
                segmentBottomSpacer,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(0, displayedRecordings.size() - end)
                                * rowHeight));
    }

    private void updateSegmentWindowIncrementally(
            int start,
            int end,
            int rowHeight) {
        RecorderSettings displayedSettings = segmentListSettings;
        int oldStart = segmentWindowStart;
        int oldEnd = segmentWindowEnd;
        while (oldStart < start) {
            View card = segmentList.getChildAt(1);
            recycleSegmentListThumbnails(card);
            segmentList.removeViewAt(1);
            oldStart++;
        }
        while (oldEnd > end) {
            int position = 1 + (oldEnd - 1 - oldStart);
            View card = segmentList.getChildAt(position);
            recycleSegmentListThumbnails(card);
            segmentList.removeViewAt(position);
            oldEnd--;
        }
        for (int index = oldStart - 1; index >= start; index--) {
            segmentList.addView(
                    buildSegmentCard(index, displayedSettings, rowHeight),
                    1);
            oldStart = index;
        }
        for (int index = oldEnd; index < end; index++) {
            segmentList.addView(
                    buildSegmentCard(index, displayedSettings, rowHeight),
                    segmentList.getChildCount() - 1);
            oldEnd = index + 1;
        }
        segmentWindowStart = start;
        segmentWindowEnd = end;
        setSpacerHeight(segmentTopSpacer, start * rowHeight);
        setSpacerHeight(
                segmentBottomSpacer,
                Math.max(0, displayedRecordings.size() - end) * rowHeight);
    }

    private void setSpacerHeight(View spacer, int height) {
        if (spacer == null) {
            return;
        }
        ViewGroup.LayoutParams params = spacer.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            spacer.setLayoutParams(params);
        }
    }

    private View buildSegmentCard(
            int index,
            final RecorderSettings displayedSettings,
            int rowHeight) {
        final int previewGeneration = segmentPreviewGeneration;
        {
            final StorageRepository.SegmentInfo segment =
                    displayedRecordings.get(index);
            final LinearLayout card = vertical();
            final String segmentPath = segment.directory.getAbsolutePath();
            final String displayName = RecorderDateTime.formatSegmentName(
                    segment.directory.getName(),
                    displayedSettings.dateFormat);
            boolean finalizing =
                    segment.incomplete
                            && SegmentStitcher.hasParts(segment.directory);
            card.setBackgroundResource(
                    selectedRecordingPaths.contains(segmentPath)
                            ? R.drawable.card_selected_background
                            : R.drawable.card_background);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setContentDescription(
                    segment.active
                            ? getString(R.string.segment_active_desc, displayName)
                            : finalizing
                                    ? getString(R.string.segment_finalizing_desc, displayName)
                                    : segment.incomplete
                                            ? getString(R.string.segment_incomplete_desc, displayName)
                                            : getString(R.string.segment_open_desc, displayName));
            card.setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            if (selectionMode) {
                                toggleRecordingSelection(segment);
                            } else if (!segment.active && !segment.incomplete) {
                                openSegment(segment.directory);
                            }
                        }
                    });
            card.setOnLongClickListener(
                    new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View view) {
                            if (segment.active) {
                                showMessage(getString(R.string.msg_active_cannot_select));
                                return true;
                            }
                            selectionMode = true;
                            selectedRecordingPaths.add(segmentPath);
                            refreshSelectionUi();
                            return true;
                        }
                    });
            LinearLayout.LayoutParams cardParams = matchWidthWrap(dp(0), dp(5));
            cardParams.height = rowHeight - dp(5);
            card.setLayoutParams(cardParams);

            LinearLayout topRow = horizontal();
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            String state = segment.active
                    ? getString(R.string.segment_recording_now)
                    : finalizing
                            ? getString(R.string.segment_finalizing)
                                    + (segment.locked ? getString(R.string.segment_locked_suffix) : "")
                            : segment.incomplete
                                    ? getString(R.string.segment_incomplete)
                                            + (segment.locked ? getString(R.string.segment_locked_suffix) : "")
                                    : segment.locked
                                            ? getString(R.string.segment_locked_state)
                                            : getString(R.string.segment_unlocked);
            final TextView details = text(
                    displayName
                            + "\n"
                            + (segment.active
                                    ? getString(R.string.writing_files)
                                    : StorageRepository.formatBytes(segment.sizeBytes))
                            + " — "
                            + state
                            + (segment.active || segment.incomplete
                                    ? ""
                                    : " — duration loading"),
                    13,
                    false);
            topRow.addView(
                    details,
                    new LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f));

            IconButton lock = iconButton(
                    segment.locked ? R.drawable.ic_lock : R.drawable.ic_unlock,
                    segment.locked
                            ? "Locked segment; tap to unlock"
                            : "Unlocked segment; tap to lock",
                    segment.locked
                            ? IconButton.Tone.LOCKED
                            : IconButton.Tone.DEFAULT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            try {
                                recorderService.setSegmentLocked(
                                        displayedSettings,
                                        segment.directory,
                                        !segment.locked);
                                refreshStorage();
                            } catch (IOException exception) {
                                showMessage(exception.getMessage());
                            }
                        }
                    });
            lock.setEnabled(!segment.active);
            lock.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            topRow.addView(lock, compactButtonParams());

            IconButton disclosure = iconButton(
                    R.drawable.ic_folder_open,
                    segment.incomplete
                            ? "Interrupted recording cannot be opened"
                            : "Open recording",
                    IconButton.Tone.DEFAULT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            if (!segment.active && !segment.incomplete) {
                                openSegment(segment.directory);
                            }
                        }
                    });
            disclosure.setEnabled(!segment.active && !segment.incomplete);
            disclosure.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            LinearLayout.LayoutParams disclosureParams = compactButtonParams();
            disclosureParams.leftMargin = dp(8);
            topRow.addView(disclosure, disclosureParams);
            card.addView(topRow);

            if (segment.active || segment.incomplete) {
                TextView availability = text(
                        segment.active
                                ? getString(R.string.recording_in_progress)
                                : finalizing
                                        ? getString(R.string.finalizing_recording)
                                        : getString(R.string.interrupted_recording),
                        12,
                        false);
                availability.setTextColor(color(R.color.text_secondary));
                availability.setBackgroundResource(R.drawable.input_background);
                availability.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams availabilityParams =
                        matchWidthWrap(dp(0), dp(0));
                availabilityParams.topMargin = dp(6);
                card.addView(availability, availabilityParams);
                if (finalizing) {
                    FinalizingProgressBar progressBar =
                            new FinalizingProgressBar(this);
                    progressBar.setPercentSource(
                            new FinalizingProgressBar.PercentSource() {
                                @Override
                                public int percent() {
                                    return SegmentStitcher.progressPercent(
                                            segment.directory);
                                }
                            });
                    LinearLayout.LayoutParams progressParams =
                            matchWidthWrap(dp(0), dp(0));
                    progressParams.topMargin = dp(8);
                    progressParams.height = dp(16);
                    card.addView(progressBar, progressParams);
                }
            } else {
                final ImageView[] thumbnails =
                        new ImageView[FrameProcessor.CAMERA_COUNT];
                LinearLayout thumbnailRow = horizontal();
                thumbnailRow.setPadding(0, dp(6), 0, 0);
                for (int cameraIndex = 0;
                        cameraIndex < FrameProcessor.CAMERA_COUNT;
                        cameraIndex++) {
                    ImageView thumbnail = new ImageView(this);
                    thumbnail.setBackgroundColor(Color.BLACK);
                    thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumbnails[cameraIndex] = thumbnail;
                    LinearLayout.LayoutParams thumbnailParams =
                            new LinearLayout.LayoutParams(0, dp(82), 1f);
                    thumbnailParams.setMargins(dp(2), 0, dp(2), 0);
                    thumbnailRow.addView(thumbnail, thumbnailParams);
                }
                card.addView(thumbnailRow);
                loadSegmentPreview(
                        segment,
                        details,
                        thumbnails,
                        state,
                        displayName,
                        previewGeneration);
            }
            return card;
        }
    }

    private void toggleRecordingSelection(
            StorageRepository.SegmentInfo recording) {
        if (recording.active) {
            return;
        }
        String path = recording.directory.getAbsolutePath();
        if (!selectedRecordingPaths.add(path)) {
            selectedRecordingPaths.remove(path);
        }
        if (selectedRecordingPaths.isEmpty()) {
            selectionMode = false;
        }
        refreshSelectionUi();
    }

    private void selectAllRecordings() {
        selectedRecordingPaths.clear();
        for (StorageRepository.SegmentInfo recording : displayedRecordings) {
            if (!recording.active) {
                selectedRecordingPaths.add(recording.directory.getAbsolutePath());
            }
        }
        selectionMode = !selectedRecordingPaths.isEmpty();
        refreshSelectionUi();
    }

    private void clearSelection() {
        selectedRecordingPaths.clear();
        selectionMode = false;
        refreshSelectionUi();
    }

    private void setSelectedRecordingsLocked(boolean locked) {
        if (recorderService == null || selectedRecordingPaths.isEmpty()) {
            return;
        }
        try {
            for (StorageRepository.SegmentInfo recording : displayedRecordings) {
                if (selectedRecordingPaths.contains(
                        recording.directory.getAbsolutePath())) {
                    recorderService.setSegmentLocked(
                            settings,
                            recording.directory,
                            locked);
                }
            }
            selectedRecordingPaths.clear();
            selectionMode = false;
            showMessage(
                    locked
                            ? getString(R.string.msg_recordings_locked)
                            : getString(R.string.msg_recordings_unlocked));
            refreshSelectionUi();
            refreshStorage();
        } catch (IOException exception) {
            showMessage(exception.getMessage());
        }
    }

    private void deleteSelectedRecordings() {
        if (recorderService == null || selectedRecordingPaths.isEmpty()) {
            return;
        }
        List<File> directories = new ArrayList<>();
        for (StorageRepository.SegmentInfo recording : displayedRecordings) {
            if (selectedRecordingPaths.contains(
                    recording.directory.getAbsolutePath())) {
                directories.add(recording.directory);
            }
        }
        try {
            recorderService.deleteSegments(settings, directories);
            selectedRecordingPaths.clear();
            selectionMode = false;
            showMessage(getString(R.string.msg_recordings_deleted));
            refreshSelectionUi();
            refreshStorage();
        } catch (IOException exception) {
            showMessage(exception.getMessage());
        }
    }

    private void requestDeleteSelectedRecordings() {
        for (StorageRepository.SegmentInfo recording : displayedRecordings) {
            if (recording.locked
                    && selectedRecordingPaths.contains(
                            recording.directory.getAbsolutePath())) {
                ConfirmationDialog.showInfo(
                        this,
                        getString(R.string.unlock_before_delete_title),
                        StorageRepository.LOCKED_DELETE_MESSAGE);
                return;
            }
        }
        confirmSelectionAction(
                getString(R.string.confirm_delete_title),
                getString(R.string.confirm_delete_message),
                new Runnable() {
                    @Override
                    public void run() {
                        deleteSelectedRecordings();
                    }
                });
    }

    private void refreshSelectionUi() {
        if (selectionToolbar != null) {
            selectionToolbar.setVisibility(
                    selectionMode ? View.VISIBLE : View.GONE);
        }
        renderVisibleSegments(true);
    }

    private void loadSegmentPreview(
            final StorageRepository.SegmentInfo segment,
            final TextView details,
            final ImageView[] thumbnails,
            final String state,
            final String displayName,
            final int previewGeneration) {
        segmentPreviewExecutor.execute(
                new Runnable() {
                    @Override
                    public void run() {
                        final SegmentPreviewLoader.Preview preview =
                                SegmentPreviewLoader.load(segment.directory);
                        runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (previewGeneration != segmentPreviewGeneration
                                                || isFinishing()) {
                                            recycleSegmentThumbnails(
                                                    preview.thumbnails);
                                            return;
                                        }
                                        details.setText(
                                                displayName
                                                        + "\n"
                                                        + StorageRepository.formatBytes(
                                                                segment.sizeBytes)
                                                        + " — "
                                                        + state
                                                        + " — "
                                                        + formatDuration(
                                                                preview.durationMillis));
                                        for (int index = 0;
                                                index < thumbnails.length;
                                                index++) {
                                            if (preview.thumbnails[index] != null) {
                                                thumbnails[index].setTag(
                                                        preview.thumbnails[index]);
                                                thumbnails[index].setImageBitmap(
                                                        preview.thumbnails[index]);
                                            }
                                        }
                                    }
                                });
                    }
                });
    }

    private void recycleSegmentThumbnails(Bitmap[] thumbnails) {
        if (thumbnails == null) {
            return;
        }
        for (Bitmap thumbnail : thumbnails) {
            if (thumbnail != null && !thumbnail.isRecycled()) {
                thumbnail.recycle();
            }
        }
    }

    private void recycleSegmentListThumbnails(View view) {
        if (view instanceof ImageView) {
            Object tag = view.getTag();
            if (tag instanceof Bitmap) {
                Bitmap bitmap = (Bitmap) tag;
                ((ImageView) view).setImageDrawable(null);
                view.setTag(null);
                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                recycleSegmentListThumbnails(group.getChildAt(index));
            }
        }
    }

    private String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        return String.format(
                Locale.US,
                "%d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L);
    }

    private void openSegment(File segmentDirectory) {
        Intent intent = new Intent(this, SegmentViewerActivity.class);
        intent.putExtra(
                SegmentViewerActivity.EXTRA_SEGMENT_PATH,
                segmentDirectory.getAbsolutePath());
        startActivity(intent);
    }

    private RecorderSettings readSettings(boolean showErrors) {
        try {
            int selectedPosition = volumeSpinner.getSelectedItemPosition();
            int volumeIndex =
                    selectedPosition >= 0 && selectedPosition < volumes.size()
                            ? volumes.get(selectedPosition).index
                            : settings.volumeIndex;
            long quotaBytes =
                    quotaStepper.getValue() * GIBIBYTE_BYTES / 4L;
            int retentionDays = retentionStepper.getValue();
            int segmentMinutes = segmentStepper.getValue();
            int minFreePercent = minFreeStepper.getValue();
            int resolutionPosition = resolutionSpinner.getSelectedItemPosition();
            VideoResolution[] resolutions = VideoResolution.values();
            VideoResolution resolution =
                    resolutionPosition >= 0 && resolutionPosition < resolutions.length
                            ? resolutions[resolutionPosition]
                            : settings.resolution;
            int dateFormatPosition = dateFormatSpinner.getSelectedItemPosition();
            DisplayDateFormat[] dateFormats = DisplayDateFormat.values();
            DisplayDateFormat dateFormat =
                    dateFormatPosition >= 0
                                    && dateFormatPosition < dateFormats.length
                            ? dateFormats[dateFormatPosition]
                            : settings.dateFormat;
            String[] cameraNames =
                    new String[FrameProcessor.CAMERA_COUNT];
            boolean[] cameraFlipHorizontal =
                    new boolean[FrameProcessor.CAMERA_COUNT];
            boolean[] cameraFlipVertical =
                    new boolean[FrameProcessor.CAMERA_COUNT];
            for (int index = 0; index < cameraNames.length; index++) {
                cameraNames[index] =
                        cameraNameInputs[index] == null
                                ? settings.cameraName(index)
                                : cameraNameInputs[index].getText().toString();
                cameraFlipHorizontal[index] =
                        cameraHorizontalFlipToggles[index] != null
                                ? cameraHorizontalFlipToggles[index].isChecked()
                                : settings.cameraFlipHorizontal(index);
                cameraFlipVertical[index] =
                        cameraVerticalFlipToggles[index] != null
                                ? cameraVerticalFlipToggles[index].isChecked()
                                : settings.cameraFlipVertical(index);
            }
            int fisheyeCropPercent =
                    fisheyeCropSlider == null
                            ? settings.fisheyeCropPercent()
                            : fisheyeCropSlider.getValue();
            float parkingImpactThresholdG =
                    parkingImpactStepper != null
                            ? parkingImpactStepper.getValue() / 10.0f
                            : settings.parkingImpactThresholdG;
            int parkingRecordingSeconds =
                    parkingDurationStepper != null
                            ? parkingDurationStepper.getValue()
                            : settings.parkingRecordingSeconds;
            return new RecorderSettings(
                    volumeIndex,
                    quotaBytes,
                    true,
                    retentionDays,
                    segmentMinutes,
                    minFreePercent,
                    resolution,
                    settings.continuousRecordingEnabled,
                    phoneAccessToggle != null
                            ? phoneAccessToggle.isChecked()
                            : settings.phoneAccessEnabled,
                    settings.phoneAccessCode,
                    settings.phoneAccessPin,
                    dateFormat,
                    cameraNames,
                    combinedLayoutDraft,
                    cameraFlipHorizontal,
                    cameraFlipVertical,
                    fisheyeCropPercent,
                    vehicleModelSpinner != null
                            ? ((VehicleProfile) vehicleModelSpinner.getSelectedItem()).modelId()
                            : settings.vehicleModelId,
                    gpsOverlayEnabledCheckbox != null
                            ? gpsOverlayEnabledCheckbox.isChecked()
                            : settings.gpsOverlayEnabled,
                    gpsSpeedUnitSpinner != null
                            ? ((SpeedUnitAdapter) gpsSpeedUnitSpinner.getAdapter())
                                    .idAt(gpsSpeedUnitSpinner.getSelectedItemPosition())
                            : settings.gpsSpeedUnit,
                    gpsShowCoordinatesCheckbox != null
                            ? gpsShowCoordinatesCheckbox.isChecked()
                            : settings.gpsShowCoordinates,
                    gpsTrackEnabledCheckbox != null
                            ? gpsTrackEnabledCheckbox.isChecked()
                            : settings.gpsTrackEnabled,
                    parkingImpactThresholdG,
                    parkingRecordingSeconds,
                    parkingAutoLockCheckbox != null
                            ? parkingAutoLockCheckbox.isChecked()
                            : settings.parkingAutoLock,
                    telegramEnabledCheckbox != null
                            ? telegramEnabledCheckbox.isChecked()
                            : settings.telegramEnabled,
                    telegramBotTokenInput != null
                            ? telegramBotTokenInput.getText().toString().trim()
                            : settings.telegramBotToken,
                    telegramChatIdInput != null
                            ? telegramChatIdInput.getText().toString().trim()
                            : settings.telegramChatId,
                    mqttEnabledCheckbox != null
                            ? mqttEnabledCheckbox.isChecked()
                            : settings.mqttEnabled,
                    mqttHostInput != null
                            ? mqttHostInput.getText().toString().trim()
                            : settings.mqttHost,
                    mqttPortStepper != null
                            ? mqttPortStepper.getValue()
                            : settings.mqttPort,
                    mqttUsernameInput != null
                            ? mqttUsernameInput.getText().toString().trim()
                            : settings.mqttUsername,
                    mqttPasswordInput != null
                            ? mqttPasswordInput.getText().toString()
                            : settings.mqttPassword,
                    mqttTopicPrefixInput != null
                            ? mqttTopicPrefixInput.getText().toString().trim()
                            : settings.mqttTopicPrefix,
                    cloudflareEnabledCheckbox != null
                            ? cloudflareEnabledCheckbox.isChecked()
                            : settings.cloudflareEnabled,
                    cameraMotionEnabledCheckbox != null
                            ? cameraMotionEnabledCheckbox.isChecked()
                            : settings.cameraMotionEnabled,
                    cameraMotionSensitivityStepper != null
                            ? cameraMotionSensitivityStepper.getValue()
                            : settings.cameraMotionSensitivity,
                    settings.telemetryEnabled);
        } catch (NumberFormatException exception) {
            if (showErrors) {
                showMessage(getString(R.string.msg_invalid_settings));
            }
            return settings;
        }
    }

    private void saveSettings() {
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null && settingsOverlay != null) {
            inputMethodManager.hideSoftInputFromWindow(
                    settingsOverlay.getWindowToken(),
                    0);
            settingsOverlay.clearFocus();
        }
        settings = readSettings(true);
        settings.save(this);
        if (recorderService != null) {
            recorderService.applyRecorderSettings(settings);
            settings = RecorderSettings.load(this);
        }
        populateInputs();
        refreshDirectPreviewTextures();
        showMessage(getString(R.string.msg_settings_saved));
    }

    private void ensureCameraPermission() {
        if (!FrameSourceFactory.hasRequiredCameraPermissions(this)) {
            requestPermissions(
                    FrameSourceFactory.requiredCameraPermissions(),
                    CAMERA_PERMISSION_REQUEST);
        }
    }

    private void ensureLocationPermission() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        }
    }

    private boolean checkCameraPermission() {
        if (FrameSourceFactory.hasRequiredCameraPermissions(this)) {
            return true;
        }
        ensureCameraPermission();
        showMessage(getString(R.string.msg_camera_permission));
        return false;
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int color(int id) {
        return getResources().getColor(id);
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

    private LinearLayout.LayoutParams matchWidthWrap(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    private LinearLayout.LayoutParams toolbarButtonParams() {
        return new LinearLayout.LayoutParams(dp(58), dp(58));
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(R.color.text_primary));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private static final class ResolutionAdapter extends BaseAdapter {
        private final Context context;
        private final VideoResolution[] resolutions;

        ResolutionAdapter(Context context, VideoResolution[] resolutions) {
            this.context = context;
            this.resolutions = resolutions;
        }

        @Override
        public int getCount() {
            return resolutions.length;
        }

        @Override
        public VideoResolution getItem(int position) {
            return resolutions[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, true);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, false);
        }

        private TextView createRow(int position, View convertView, boolean singleLine) {
            TextView view =
                    convertView instanceof TextView
                            ? (TextView) convertView
                            : new TextView(context);
            view.setText(resolutions[position].label);
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            view.setBackgroundColor(Color.rgb(18, 29, 47));
            view.setSingleLine(singleLine);
            view.setEllipsize(
                    singleLine
                            ? TextUtils.TruncateAt.MIDDLE
                            : TextUtils.TruncateAt.END);
            int padding = Math.round(
                    10 * context.getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, singleLine ? padding * 4 : padding * 2, padding);
            return view;
        }
    }

    private static final class DateFormatAdapter extends BaseAdapter {
        private final Context context;
        private final DisplayDateFormat[] formats;

        DateFormatAdapter(Context context, DisplayDateFormat[] formats) {
            this.context = context;
            this.formats = formats;
        }

        @Override
        public int getCount() {
            return formats.length;
        }

        @Override
        public DisplayDateFormat getItem(int position) {
            return formats[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, true);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, false);
        }

        private TextView createRow(int position, View convertView, boolean singleLine) {
            TextView view =
                    convertView instanceof TextView
                            ? (TextView) convertView
                            : new TextView(context);
            view.setText(formats[position].label);
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            view.setBackgroundColor(Color.rgb(18, 29, 47));
            view.setSingleLine(singleLine);
            view.setEllipsize(TextUtils.TruncateAt.END);
            int padding = Math.round(
                    10 * context.getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, padding * 2, padding);
            return view;
        }
    }

    private static final class VehicleProfileAdapter extends BaseAdapter {
        private final Context context;
        private final java.util.List<VehicleProfile> profiles;

        VehicleProfileAdapter(Context context, java.util.List<VehicleProfile> profiles) {
            this.context = context;
            this.profiles = profiles;
        }

        @Override
        public int getCount() { return profiles.size(); }

        @Override
        public VehicleProfile getItem(int position) { return profiles.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        public int indexOf(String modelId) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).modelId().equals(modelId)) return i;
            }
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, true);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, false);
        }

        private TextView createRow(int position, View convertView, boolean singleLine) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(context);
            view.setText(profiles.get(position).displayName());
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            view.setBackgroundColor(Color.rgb(18, 29, 47));
            view.setSingleLine(singleLine);
            view.setEllipsize(TextUtils.TruncateAt.END);
            int p = Math.round(10 * context.getResources().getDisplayMetrics().density);
            view.setPadding(p, p, singleLine ? p * 4 : p * 2, p);
            return view;
        }
    }

    private static final class SpeedUnitAdapter extends BaseAdapter {
        private final Context context;
        private final String[] ids;
        private final String[] labels;

        SpeedUnitAdapter(Context context, String[] ids, String[] labels) {
            this.context = context;
            this.ids = ids;
            this.labels = labels;
        }

        @Override
        public int getCount() { return labels.length; }

        @Override
        public String getItem(int position) { return labels[position]; }

        @Override
        public long getItemId(int position) { return position; }

        public int indexOf(String id) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(id)) return i;
            }
            return 0;
        }

        public String idAt(int position) {
            return ids[position];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, true);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createRow(position, convertView, false);
        }

        private TextView createRow(int position, View convertView, boolean singleLine) {
            TextView view = convertView instanceof TextView
                    ? (TextView) convertView : new TextView(context);
            view.setText(labels[position]);
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            view.setBackgroundColor(Color.rgb(18, 29, 47));
            view.setSingleLine(singleLine);
            view.setEllipsize(TextUtils.TruncateAt.END);
            int p = Math.round(10 * context.getResources().getDisplayMetrics().density);
            view.setPadding(p, p, singleLine ? p * 4 : p * 2, p);
            return view;
        }
    }

    private static final class VolumeAdapter extends BaseAdapter {
        private final Context context;
        private final List<StorageRepository.StorageVolume> volumes;

        VolumeAdapter(
                Context context,
                List<StorageRepository.StorageVolume> volumes) {
            this.context = context;
            this.volumes = volumes;
        }

        @Override
        public int getCount() {
            return volumes.size();
        }

        @Override
        public StorageRepository.StorageVolume getItem(int position) {
            return volumes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return volumes.get(position).index;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = createRow(position, convertView);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            int padding = dp(10);
            view.setPadding(padding, padding, dp(38), padding);
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = createRow(position, convertView);
            view.setSingleLine(false);
            view.setMaxLines(3);
            view.setEllipsize(TextUtils.TruncateAt.END);
            int padding = dp(12);
            view.setPadding(padding, padding, dp(24), padding);
            return view;
        }

        private TextView createRow(int position, View convertView) {
            TextView view =
                    convertView instanceof TextView
                            ? (TextView) convertView
                            : new TextView(context);
            view.setText(volumes.get(position).label);
            view.setTextColor(Color.WHITE);
            view.setTextSize(13);
            view.setBackgroundColor(Color.rgb(18, 29, 47));
            return view;
        }

        private int dp(int value) {
            return Math.round(
                    value * context.getResources().getDisplayMetrics().density);
        }
    }
}
