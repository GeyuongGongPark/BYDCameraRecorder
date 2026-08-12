<script>
  import { onDestroy, onMount } from 'svelte';
  import QRCode from 'qrcode';
  import { api, ApiError } from './lib/api.js';
  import ChoicePicker from './lib/ChoicePicker.svelte';
  import { cameraStream, websocketUrl } from './lib/cameraStream.js';
  import CombinedLayoutEditor from './lib/CombinedLayoutEditor.svelte';
  import ConfirmDialog from './lib/ConfirmDialog.svelte';
  import FlipToggle from './lib/FlipToggle.svelte';
  import HelpButton from './lib/HelpButton.svelte';
  import LockIcon from './lib/LockIcon.svelte';
  import LazyImage from './lib/LazyImage.svelte';
  import NumberField from './lib/NumberField.svelte';
  import PinchZoomImage from './lib/PinchZoomImage.svelte';
  import SliderControl from './lib/SliderControl.svelte';
  import TextField from './lib/TextField.svelte';
  import ToggleSwitch from './lib/ToggleSwitch.svelte';
  import VideoPlayer from './lib/VideoPlayer.svelte';
  import { formatBytes, videoUrl } from './lib/format.js';

  const cameraIndexes = [1, 2, 3, 4];
  const resolutionOptions = [
    { value: 'economy', label: 'Economy · 320×240 each · 640×480 combined' },
    { value: 'standard', label: 'Standard · 640×480 each · 1280×960 combined' },
    { value: 'high', label: 'High · 960×720 each · 1920×1440 combined' },
    { value: 'native', label: 'Native · 1280×960 each · 2560×1920 combined' }
  ];
  const settingHelp = {
    volume: 'Chooses the storage volume where new recording folders are written. Existing recordings remain on their current volume.',
    resolution: 'Controls detail and file size for every individual camera file and the combined file. Higher detail uses storage faster.',
    cameraNames: 'Names identify the four physical camera views in previews and the combined-layout editor. New camera MP4 filenames use the valid custom name plus the segment ISO time; invalid filesystem names fall back to Camera-1 through Camera-4.',
    cameraOrientation: 'Flips a physical camera view horizontally, vertically, or in both directions. The saved orientation is applied to live previews and newly created individual and combined recordings. Existing recordings are unchanged.',
    cameraCrop: 'Cuts the same percentage from every edge of all four cameras, then expands each remaining center image to fill its frame. This reduces distorted fisheye edges. The saved transform is used by live previews and new individual and combined recordings.',
    combinedLayout: 'Sets which camera appears in each corner of newly created combined 2×2 videos. Individual camera videos are never reordered.',
    quota: 'Maximum space the recorder may use on the selected volume. Automatic cleanup removes only unlocked recorder-owned recordings.',
    retention: 'Unlocked recordings older than this many days are eligible for automatic cleanup. Locked recordings are always protected.',
    segment: 'Length of each recording folder before a new segment starts. Shorter segments are easier to find but create more files.',
    reserve: 'Recording stops before the selected volume falls below this free-space percentage. This protects the car and other apps from a full disk.',
    date: 'Changes how recording dates are displayed. Recorder folder names keep their filesystem-safe canonical format.'
  };
  let authenticated = null;
  let checkingAuthentication = false;
  let state = null;
  let error = '';
  let busy = false;
  let bulkAction = null;
  let backgroundAccessConfirm = false;
  let confirmLock = null;
  let pin = '';
  let retrySeconds = 0;
  let settingsOpen = false;
  let selectedCamera = null;
  let selectedSegment = null;
  let selectedFileName = '';
  let selectedRecordingIds = [];
  let selectionMode = false;
  let longPressState = null;
  let suppressNextRecordingClick = false;
  let stateRevision = 0;
  let settingsDraft = {};
  let settingsDirty = false;
  let stateRefreshInFlight = false;
  let statusRefreshInFlight = false;
  let fisheyePreviewScale = 1;
  let liveCameraIndexes = cameraIndexes;
  let finalizingProgress = {};
  let finalizeSocket = null;
  let qrCanvas;
  let recordingListElement;
  let recordingWindowStart = 0;
  let recordingWindowEnd = 12;
  let recordingTopSpacer = 0;
  let recordingBottomSpacer = 0;
  let recordingAverageHeight = 190;
  let recordingWindowFrame = 0;
  const recordingRowHeights = new Map();
  const recordingWindowBuffer = 4;
  const qrValue = new URLSearchParams(window.location.search).get('qr');
  const developmentPin = import.meta.env.DEV
    ? import.meta.env.VITE_MOCK_PIN
    : '';

  $: document.body.classList.toggle(
    'modal-open',
    Boolean(settingsOpen || selectedCamera || selectedSegment || confirmLock || bulkAction || backgroundAccessConfirm)
  );
  $: settingsDirty = settingsOpen && state
    ? comparableSettings(settingsDraft) !== comparableSettings(state.settings)
    : false;
  $: selectionMode = selectedRecordingIds.length > 0;
  $: liveCameraIndexes = orderedCameraIndexes(state?.settings);
  $: syncFinalizeSocket(Boolean(authenticated && state?.segments?.some((segment) => segment.finalizing)));
  $: fisheyePreviewScale =
    1 /
    Math.max(
      0.3,
      1 - 2 * (Number(settingsDraft.fisheyeCropPercent) || 0) / 100
    );
  $: if (recordingListElement && state?.segments) {
    state.segments.length;
    queueRecordingWindowUpdate();
  }

  onDestroy(() => {
    document.body.classList.remove('modal-open');
    clearRecordingLongPress();
    window.cancelAnimationFrame(recordingWindowFrame);
    syncFinalizeSocket(false);
  });

  function recordingRowHeight(segment) {
    return recordingRowHeights.get(segment.id) || recordingAverageHeight;
  }

  function recordingHeightBetween(segments, start, end) {
    return segments
      .slice(start, end)
      .reduce((total, segment) => total + recordingRowHeight(segment), 0);
  }

  function queueRecordingWindowUpdate() {
    window.cancelAnimationFrame(recordingWindowFrame);
    recordingWindowFrame = window.requestAnimationFrame(updateRecordingWindow);
  }

  function updateRecordingWindow() {
    recordingWindowFrame = 0;
    const segments = state?.segments || [];
    if (!recordingListElement || segments.length === 0) {
      recordingWindowStart = 0;
      recordingWindowEnd = segments.length;
      recordingTopSpacer = 0;
      recordingBottomSpacer = 0;
      return;
    }
    const listTop =
      window.scrollY + recordingListElement.getBoundingClientRect().top;
    const viewportStart = Math.max(0, window.scrollY - listTop);
    const viewportEnd = viewportStart + window.innerHeight;
    let runningHeight = 0;
    let firstVisible = 0;
    while (
      firstVisible < segments.length &&
      runningHeight + recordingRowHeight(segments[firstVisible]) < viewportStart
    ) {
      runningHeight += recordingRowHeight(segments[firstVisible]);
      firstVisible += 1;
    }
    const start = Math.max(0, firstVisible - recordingWindowBuffer);
    let end = firstVisible;
    let coveredHeight = runningHeight;
    while (end < segments.length && coveredHeight < viewportEnd) {
      coveredHeight += recordingRowHeight(segments[end]);
      end += 1;
    }
    end = Math.min(segments.length, end + recordingWindowBuffer);
    recordingWindowStart = start;
    recordingWindowEnd = end;
    recordingTopSpacer = recordingHeightBetween(segments, 0, start);
    recordingBottomSpacer = recordingHeightBetween(segments, end, segments.length);
    window.requestAnimationFrame(measureVisibleRecordingRows);
  }

  function measureVisibleRecordingRows() {
    if (!recordingListElement) {
      return;
    }
    const measuredRows = Array.from(
      recordingListElement.querySelectorAll('[data-recording-id]')
    );
    let changed = false;
    measuredRows.forEach((row) => {
      const measuredHeight = row.getBoundingClientRect().height + 10;
      const previousHeight = recordingRowHeights.get(row.dataset.recordingId);
      if (!previousHeight || Math.abs(previousHeight - measuredHeight) > 1) {
        recordingRowHeights.set(row.dataset.recordingId, measuredHeight);
        changed = true;
      }
    });
    if (!changed || recordingRowHeights.size === 0) {
      return;
    }
    recordingAverageHeight =
      Array.from(recordingRowHeights.values()).reduce(
        (total, height) => total + height,
        0
      ) / recordingRowHeights.size;
    queueRecordingWindowUpdate();
  }

  function handleRequestError(requestError) {
    if (requestError instanceof ApiError && requestError.status === 401) {
      authenticated = false;
      state = null;
      selectedSegment = null;
      settingsOpen = false;
      error = 'Enter the current PIN shown on the car display.';
      retrySeconds = Math.max(retrySeconds, requestError.retryAfterSeconds);
      return;
    }
    if (requestError instanceof ApiError && requestError.status === 429) {
      retrySeconds = Math.max(1, requestError.retryAfterSeconds);
    }
    error = requestError.message;
  }

  async function checkAuthentication() {
    if (checkingAuthentication) {
      return;
    }
    checkingAuthentication = true;
    try {
      const result = await api.getAuthentication();
      authenticated = result.authenticated;
      if (authenticated) {
        await refresh();
      }
    } catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) {
        authenticated = false;
        handleRequestError(requestError);
      } else {
        authenticated = null;
        error = 'Recorder connection interrupted. Retrying automatically.';
      }
    } finally {
      checkingAuthentication = false;
    }
  }

  async function authenticate() {
    if (!/^\d{6}$/.test(pin) || retrySeconds > 0) {
      return;
    }
    busy = true;
    try {
      await api.authenticate(pin);
      authenticated = true;
      pin = '';
      error = '';
      await refresh();
    } catch (requestError) {
      if (requestError instanceof ApiError && requestError.status === 401) {
        retrySeconds = Math.max(5, requestError.retryAfterSeconds);
        error = 'Incorrect PIN. Try again when the timer reaches zero.';
      } else {
        handleRequestError(requestError);
      }
    } finally {
      busy = false;
    }
  }

  async function refresh() {
    if (!authenticated || busy || stateRefreshInFlight) {
      return;
    }
    const requestRevision = stateRevision;
    stateRefreshInFlight = true;
    try {
      const refreshedState = await api.getState();
      if (requestRevision !== stateRevision) {
        return;
      }
      state = refreshedState;
      selectedRecordingIds = selectedRecordingIds.filter((id) =>
        state.segments.some((segment) => segment.id === id && !segment.active)
      );
      error = '';
      if (selectedSegment) {
        selectedSegment =
          state.segments.find((segment) => segment.id === selectedSegment.id) || null;
        if (
          selectedSegment &&
          !selectedSegment.files.some((file) => file.name === selectedFileName)
        ) {
          selectedFileName = preferredFile(selectedSegment)?.name || '';
        }
      }
    } catch (requestError) {
      if (requestRevision === stateRevision) {
        handleRequestError(requestError);
      }
    } finally {
      stateRefreshInFlight = false;
    }
  }

  let lastStateVersion = 0;

  async function refreshStatus() {
    if (!authenticated || !state || statusRefreshInFlight) {
      return;
    }
    statusRefreshInFlight = true;
    try {
      const status = await api.getStatus();
      const version = Number(status.stateVersion) || 0;
      delete status.stateVersion;
      state = { ...state, ...status };
      error = '';
      // The head unit bumps stateVersion on every recording, finalization,
      // lock, delete, or settings change; refreshing on the cheap 1-second
      // status poll keeps the app current within about a second instead of
      // waiting for the slow full-state timer.
      if (version !== lastStateVersion) {
        lastStateVersion = version;
        refresh();
      }
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      statusRefreshInFlight = false;
    }
  }

  async function setRecording(enabled) {
    beginStateMutation();
    try {
      state = await api.setRecording(enabled);
      error = '';
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      busy = false;
    }
  }

  function openSettings() {
    settingsDraft = { ...state.settings };
    settingsOpen = true;
  }

  function updateSettingsDraft(key, value) {
    settingsDraft = { ...settingsDraft, [key]: value };
  }

  function beginStateMutation() {
    stateRevision += 1;
    busy = true;
  }

  function comparableSettings(value) {
    return JSON.stringify(
      Object.keys(state?.settings || {})
        .sort()
        .map((key) => [key, value?.[key]])
    );
  }

  function preferredFile(segment) {
    return (
      segment.files.find((file) => file.name.toLowerCase().includes('combined')) ||
      segment.files[0] ||
      null
    );
  }

  function cameraName(camera) {
    return state?.settings?.[`camera${camera}Name`] || `Camera ${camera}`;
  }

  // The head unit pushes stitch percentages over a small WebSocket while any
  // recording is being finalized; polling the heavy state endpoint for this
  // would cost the head unit a full serialization pass per update.
  function syncFinalizeSocket(wanted) {
    if (wanted && !finalizeSocket) {
      const socket = new WebSocket(websocketUrl('./api/finalizing/stream'));
      finalizeSocket = socket;
      socket.onmessage = (event) => {
        if (finalizeSocket !== socket) {
          return;
        }
        try {
          const payload = JSON.parse(event.data);
          const next = {};
          (payload.finalizing || []).forEach((entry) => {
            next[entry.id] = entry.percent;
          });
          const hadEntries = Object.keys(finalizingProgress).length > 0;
          finalizingProgress = next;
          if (hadEntries && Object.keys(next).length === 0) {
            refresh();
          }
        } catch {
          // Ignore malformed frames; the next push resynchronizes.
        }
      };
      socket.onclose = () => {
        if (finalizeSocket === socket) {
          finalizeSocket = null;
        }
      };
      socket.onerror = () => socket.close();
    } else if (!wanted && finalizeSocket) {
      const socket = finalizeSocket;
      finalizeSocket = null;
      finalizingProgress = {};
      socket.onclose = null;
      socket.close();
    }
  }

  function finalizePercent(segment) {
    const pushed = finalizingProgress[segment.id];
    if (typeof pushed === 'number') {
      return pushed;
    }
    return typeof segment.finalizingPercent === 'number'
      ? segment.finalizingPercent
      : -1;
  }

  // Live streams carry unflipped native sensor pixels; the saved camera
  // orientation is applied here as a GPU CSS transform instead of being
  // baked into every JPEG on the head unit.
  function flipTransformStyle(camera, currentSettings) {
    if (!currentSettings) {
      return '';
    }
    const x = currentSettings[`camera${camera}FlipHorizontal`] ? -1 : 1;
    const y = currentSettings[`camera${camera}FlipVertical`] ? -1 : 1;
    return x === 1 && y === 1 ? '' : `transform: scale(${x}, ${y})`;
  }

  function editorPreviewStyle(camera, draft, cropScale) {
    const x = draft[`camera${camera}FlipHorizontal`] ? -1 : 1;
    const y = draft[`camera${camera}FlipVertical`] ? -1 : 1;
    return `transform: scale(${cropScale * x}, ${cropScale * y})`;
  }

  function orderedCameraIndexes(currentSettings) {
    if (!currentSettings) {
      return cameraIndexes;
    }
    const order = [
      currentSettings.combinedTopLeft,
      currentSettings.combinedTopRight,
      currentSettings.combinedBottomLeft,
      currentSettings.combinedBottomRight
    ].map((cameraIndex) => Number(cameraIndex) + 1);
    return order.length === cameraIndexes.length &&
      new Set(order).size === cameraIndexes.length &&
      order.every((camera) => cameraIndexes.includes(camera))
      ? order
      : cameraIndexes;
  }

  function recordingPreviewUrl(segment) {
    return `./api/segments/${encodeURIComponent(segment.id)}/preview.jpg`;
  }

  function draftCameraNames() {
    return cameraIndexes.map(
      (camera) => settingsDraft[`camera${camera}Name`] || `Camera ${camera}`
    );
  }

  function draftCombinedLayout() {
    return [
      settingsDraft.combinedTopLeft,
      settingsDraft.combinedTopRight,
      settingsDraft.combinedBottomLeft,
      settingsDraft.combinedBottomRight
    ];
  }

  function updateCombinedLayout(layout) {
    settingsDraft = {
      ...settingsDraft,
      combinedTopLeft: layout[0],
      combinedTopRight: layout[1],
      combinedBottomLeft: layout[2],
      combinedBottomRight: layout[3]
    };
  }

  function updateCameraFlip(camera, direction, checked) {
    settingsDraft = {
      ...settingsDraft,
      [`camera${camera}Flip${direction}`]: checked
    };
  }

  function openSegment(segment) {
    selectedSegment = segment;
    selectedFileName = preferredFile(segment)?.name || '';
  }

  function isRecordingSelected(segment) {
    return selectedRecordingIds.includes(segment.id);
  }

  function toggleRecordingSelection(segment) {
    if (segment.active) {
      return;
    }
    selectedRecordingIds = isRecordingSelected(segment)
      ? selectedRecordingIds.filter((id) => id !== segment.id)
      : [...selectedRecordingIds, segment.id];
  }

  function beginRecordingLongPress(event, segment) {
    if (segment.active || selectionMode) {
      return;
    }
    clearRecordingLongPress();
    longPressState = {
      id: segment.id,
      startX: event.clientX,
      startY: event.clientY,
      timer: window.setTimeout(() => {
        suppressNextRecordingClick = true;
        toggleRecordingSelection(segment);
        longPressState = null;
        window.setTimeout(() => {
          suppressNextRecordingClick = false;
        }, 350);
      }, 520)
    };
  }

  function moveRecordingLongPress(event) {
    if (
      longPressState &&
      (Math.abs(event.clientX - longPressState.startX) > 12 ||
        Math.abs(event.clientY - longPressState.startY) > 12)
    ) {
      clearRecordingLongPress();
    }
  }

  function clearRecordingLongPress() {
    if (longPressState?.timer) {
      window.clearTimeout(longPressState.timer);
    }
    longPressState = null;
  }

  function handleRecordingClick(segment) {
    clearRecordingLongPress();
    if (suppressNextRecordingClick) {
      suppressNextRecordingClick = false;
      return;
    }
    if (selectionMode) {
      toggleRecordingSelection(segment);
    } else {
      openSegment(segment);
    }
  }

  function requestBulkAction(type) {
    const actionCopy = {
      selectAll: {
        title: 'Select all recordings?',
        message: 'Every non-active recording currently shown will be selected.',
        confirmLabel: 'Select all'
      },
      clear: {
        title: 'Clear selection?',
        message: 'No recordings will be changed.',
        confirmLabel: 'Clear'
      },
      lock: {
        title: 'Lock selected recordings?',
        message: 'Locked recordings are protected from automatic cleanup.',
        confirmLabel: 'Lock'
      },
      unlock: {
        title: 'Unlock selected recordings?',
        message: 'Unlocked recordings can be removed by automatic cleanup.',
        confirmLabel: 'Unlock'
      }
    };
    bulkAction = { type, ...actionCopy[type] };
  }

  async function setSelectedRecordingsLocked(ids, locked) {
    if (ids.length === 0) {
      return;
    }
    state = await api.setLocked(ids[0], locked);
    await setSelectedRecordingsLocked(ids.slice(1), locked);
  }

  async function performBulkAction() {
    const action = bulkAction;
    bulkAction = null;
    if (!action) {
      return;
    }
    if (action.type === 'selectAll') {
      selectedRecordingIds = state.segments
        .filter((segment) => !segment.active)
        .map((segment) => segment.id);
      return;
    }
    if (action.type === 'clear') {
      selectedRecordingIds = [];
      return;
    }
    beginStateMutation();
    try {
      await setSelectedRecordingsLocked(
        [...selectedRecordingIds],
        action.type === 'lock'
      );
      selectedRecordingIds = [];
      error = '';
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      busy = false;
    }
  }

  function closeSegment() {
    selectedSegment = null;
    selectedFileName = '';
  }

  async function saveSettings() {
    beginStateMutation();
    try {
      state = await api.saveSettings(settingsDraft);
      settingsOpen = false;
      error = '';
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      busy = false;
    }
  }

  async function toggleLock(segment) {
    const locked = !segment.locked;
    beginStateMutation();
    try {
      state = await api.setLocked(segment.id, locked);
      selectedSegment =
        state.segments.find((candidate) => candidate.id === segment.id) || null;
      error = '';
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      busy = false;
      confirmLock = null;
    }
  }

  async function requestBackgroundAccess() {
    busy = true;
    try {
      state = await api.requestBackgroundAccess();
      backgroundAccessConfirm = false;
      error = '';
    } catch (requestError) {
      handleRequestError(requestError);
    } finally {
      busy = false;
    }
  }

  onMount(() => {
    if (qrValue && qrCanvas) {
      QRCode.toCanvas(qrCanvas, qrValue, {
        errorCorrectionLevel: 'M',
        margin: 2,
        width: 260,
        color: { dark: '#08111f', light: '#ffffff' }
      });
      return undefined;
    }
    checkAuthentication();
    const statusTimer = window.setInterval(() => {
      if (retrySeconds > 0) {
        retrySeconds -= 1;
      }
      if (authenticated === null) {
        checkAuthentication();
      } else {
        refreshStatus();
      }
    }, 1000);
    const stateTimer = window.setInterval(refresh, 10000);
    window.addEventListener('scroll', queueRecordingWindowUpdate, { passive: true });
    window.addEventListener('resize', queueRecordingWindowUpdate);
    return () => {
      window.clearInterval(stateTimer);
      window.clearInterval(statusTimer);
      window.removeEventListener('scroll', queueRecordingWindowUpdate);
      window.removeEventListener('resize', queueRecordingWindowUpdate);
    };
  });
</script>

{#if qrValue}
  <main class="qr-only" aria-label="Phone access QR code">
    <canvas bind:this={qrCanvas}></canvas>
  </main>
{:else if authenticated === null}
  <main class="access-shell">
    <section class="panel access-card loading">
      <p class="eyebrow">BYD Camera Recorder</p>
      <h1>Checking phone access</h1>
      <p>Connecting securely to the recorder on this network.</p>
    </section>
  </main>
{:else if !authenticated}
  <main class="access-shell">
    <section class="panel access-card">
      <div class="access-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24"><rect x="6" y="2.5" width="12" height="19" rx="3"/><path d="M9.5 6.5h5M11 18h2"/></svg>
      </div>
      <p class="eyebrow">Private local access</p>
      <h1>Enter the car PIN</h1>
      <p>Use the six-digit PIN shown in Phone app access on the car display.</p>
      {#if developmentPin}
        <div class="development-pin">
          Local development PIN <strong>{developmentPin}</strong>
        </div>
      {/if}
      <form class="pin-form" onsubmit={(event) => { event.preventDefault(); authenticate(); }}>
        <input
          type="password"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          pattern="[0-9][0-9][0-9][0-9][0-9][0-9]"
          aria-label="Six digit phone PIN"
          placeholder="000000"
          value={pin}
          oninput={(event) => (pin = event.currentTarget.value.replace(/\D/g, '').slice(0, 6))}
        />
        <button
          type="submit"
          class="primary-action"
          disabled={busy || pin.length !== 6 || retrySeconds > 0}
        >
          {retrySeconds > 0 ? `Retry in ${retrySeconds}s` : busy ? 'Checking PIN' : 'Open recorder'}
        </button>
      </form>
      {#if error}<div class="error" role="alert">{error}</div>{/if}
    </section>
  </main>
{:else}
  <header class="topbar">
    <div>
      <p class="eyebrow">BYD Camera Recorder</p>
      <h1>{state?.recording ? 'Recording' : 'Ready'}</h1>
    </div>
    <button class="icon-button" aria-label="Open settings" onclick={openSettings}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8.4a3.6 3.6 0 1 0 0 7.2 3.6 3.6 0 0 0 0-7.2Zm8.2 4.8v-2.4l-2.1-.7a7 7 0 0 0-.7-1.7l1-2-1.7-1.7-2 1a7 7 0 0 0-1.7-.7L12.3 2H9.9l-.7 2.1a7 7 0 0 0-1.7.7l-2-1-1.7 1.7 1 2a7 7 0 0 0-.7 1.7l-2.1.7v2.4l2.1.7c.2.6.4 1.2.7 1.7l-1 2 1.7 1.7 2-1c.5.3 1.1.5 1.7.7l.7 2.1h2.4l.7-2.1c.6-.2 1.2-.4 1.7-.7l2 1 1.7-1.7-1-2c.3-.5.5-1.1.7-1.7l2.1-.7Z"/></svg>
    </button>
  </header>

  <main class="app-shell">
    {#if error}<div class="error" role="alert">{error}</div>{/if}
    {#if !state}
      <section class="main-skeleton" aria-label="Loading recorder">
        <div class="panel skeleton-recording">
          <span class="skeleton-block skeleton-toggle"></span>
          <span class="skeleton-copy">
            <i></i>
            <i></i>
          </span>
        </div>
        <div class="skeleton-heading"><i></i><i></i></div>
        <div class="panel skeleton-camera-grid">
          <i></i><i></i><i></i><i></i>
        </div>
        <div class="panel skeleton-storage">
          <i></i><i></i><i></i>
        </div>
        <div class="skeleton-heading"><i></i><i></i></div>
        <div class="skeleton-recordings">
          <div class="panel"><i></i><i></i><b></b></div>
          <div class="panel"><i></i><i></i><b></b></div>
        </div>
      </section>
    {:else}
      <section class:recording={state.recording} class="recording-card panel">
        <ToggleSwitch
          checked={state.recording}
          disabled={busy}
          label={state.recording ? 'Pause recording' : 'Start recording'}
          tone="recording"
          onchange={setRecording}
        />
        <div class="recording-copy">
          <strong class="recording-label">
            <span class="status-dot"></span>
            {state.recording ? 'Recording is on' : 'Recording is off'}
          </strong>
          <p>{state.message}</p>
        </div>
      </section>

      <section>
        <div class="section-heading"><h2>Live cameras</h2><span>Updates automatically</span></div>
        {#if state.wifiName}
          <p class="network-state">
            <strong>{state.wifiName}</strong>
          </p>
        {/if}
        <div class="camera-grid">
          {#each liveCameraIndexes as camera}
            <button
              type="button"
              class="panel camera"
              aria-label={`Open ${cameraName(camera)} live preview`}
              onclick={() => (selectedCamera = camera)}
            >
              <img
                src={`./api/cameras/${camera}.jpg`}
                alt={`${cameraName(camera)} live preview`}
                style={flipTransformStyle(camera, state.settings)}
                use:cameraStream={{
                  path: `./api/cameras/${camera}/stream`,
                  enabled: !settingsOpen && !selectedCamera && !selectedSegment
                }}
              />
              <span class="camera-caption">{cameraName(camera)}</span>
            </button>
          {/each}
        </div>
      </section>

      <section class="panel storage">
        <div><span>Available</span><strong>{formatBytes(state.storage.availableBytes)}</strong></div>
        <div><span>Recorder</span><strong>{formatBytes(state.storage.recorderBytes)}</strong></div>
        <div><span>Locked</span><strong>{formatBytes(state.storage.lockedBytes)}</strong></div>
      </section>

      <section>
        <div class="section-heading">
          <h2>Recordings</h2>
          <span>{state.segments.length} available</span>
        </div>
        {#if selectionMode}
          <div class="bulk-selection-toolbar panel" aria-label="Selected recording actions">
            <strong>{selectedRecordingIds.length} selected</strong>
            <div>
              <button class="icon-button" aria-label="Select all recordings" disabled={busy} onclick={() => requestBulkAction('selectAll')}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="8" y="8" width="12" height="12" rx="2"/><path d="m11 14 2 2 4-5M4 16V6a2 2 0 0 1 2-2h10"/></svg>
              </button>
              <button class="icon-button" aria-label="Clear recording selection" disabled={busy} onclick={() => requestBulkAction('clear')}>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="7" y="7" width="13" height="13" rx="2"/><path d="M4 16V6a2 2 0 0 1 2-2h10M10 10l7 7m0-7-7 7"/></svg>
              </button>
              <button class="icon-button bulk-lock" aria-label="Lock selected recordings" disabled={busy} onclick={() => requestBulkAction('lock')}>
                <LockIcon locked={true} />
              </button>
              <button class="icon-button" aria-label="Unlock selected recordings" disabled={busy} onclick={() => requestBulkAction('unlock')}>
                <LockIcon locked={false} />
              </button>
            </div>
          </div>
        {/if}
        <div class="segment-list" bind:this={recordingListElement}>
          {#if recordingTopSpacer > 0}
            <div class="recording-window-spacer" style={`height: ${recordingTopSpacer}px`}></div>
          {/if}
          {#each state.segments.slice(recordingWindowStart, recordingWindowEnd) as segment (segment.id)}
            <article
              class:unavailable={segment.active || segment.incomplete}
              class:selected={isRecordingSelected(segment)}
              class="segment panel"
              data-recording-id={segment.id}
            >
              <button
                class="segment-summary"
                aria-pressed={selectionMode ? isRecordingSelected(segment) : undefined}
                oncontextmenu={(event) => event.preventDefault()}
                onpointerdown={(event) => beginRecordingLongPress(event, segment)}
                onpointermove={moveRecordingLongPress}
                onpointerup={clearRecordingLongPress}
                onpointercancel={clearRecordingLongPress}
                onclick={() => handleRecordingClick(segment)}
              >
                <span class="segment-copy">
                  <strong>{segment.displayName}</strong>
                  <small>
                    {segment.active ? 'Writing files' : formatBytes(segment.sizeBytes)} ·
                    {segment.active ? 'recording now' : segment.finalizing ? 'finalizing' : segment.incomplete ? 'incomplete' : segment.locked ? 'locked' : 'unlocked'}
                  </small>
                  {#if segment.finalizing}
                    {@const percent = finalizePercent(segment)}
                    <span
                      class="finalize-progress"
                      role="progressbar"
                      aria-label={`Finalizing ${segment.displayName}`}
                      aria-valuemin="0"
                      aria-valuemax="100"
                      aria-valuenow={percent >= 0 ? percent : undefined}
                    >
                      <span class="finalize-progress-track">
                        <span
                          class="finalize-progress-fill"
                          class:indeterminate={percent < 0}
                          style={percent >= 0 ? `width: ${percent}%` : ''}
                        ></span>
                      </span>
                      <strong>{percent >= 0 ? `${percent}%` : '…'}</strong>
                    </span>
                  {/if}
                  {#if !segment.active && !segment.incomplete}
                    <LazyImage
                      className="segment-preview-strip"
                      src={recordingPreviewUrl(segment)}
                      alt={`Four camera previews for ${segment.displayName}`}
                    />
                  {/if}
                </span>
              </button>
              <div class:hidden={selectionMode} class="segment-actions">
                <button
                  class:locked={segment.locked}
                  class="segment-lock"
                  disabled={segment.active || busy}
                  aria-label={segment.locked ? `Unlock ${segment.displayName}` : `Lock ${segment.displayName}`}
                  onclick={() => (confirmLock = segment)}
                >
                  <LockIcon locked={segment.locked} />
                </button>
                <button
                  class="segment-open"
                  aria-label={`Open ${segment.displayName}`}
                  onclick={() => openSegment(segment)}
                >
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7.5h7l2 2h9v9.5H3V7.5Z"/><path d="M3 11h18"/></svg>
                </button>
              </div>
            </article>
          {/each}
          {#if recordingBottomSpacer > 0}
            <div class="recording-window-spacer" style={`height: ${recordingBottomSpacer}px`}></div>
          {/if}
        </div>
      </section>

    {/if}
  </main>

  {#if selectedCamera}
    <div class="modal-backdrop camera-viewer-backdrop">
      <section class="modal camera-viewer-modal">
        <div class="modal-title">
          <div><p class="eyebrow">Live camera</p><h2>{cameraName(selectedCamera)}</h2></div>
          <button class="icon-button" aria-label="Close live camera" onclick={() => (selectedCamera = null)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>
          </button>
        </div>
        <PinchZoomImage
          alt={`${cameraName(selectedCamera)} live preview`}
          src={`./api/cameras/${selectedCamera}.jpg`}
          streamPath={`./api/cameras/${selectedCamera}/stream`}
          flipStyle={flipTransformStyle(selectedCamera, state.settings)}
        />
      </section>
    </div>
  {/if}

  {#if selectedSegment}
    {@const selectedFile = selectedSegment.files.find((file) => file.name === selectedFileName) || preferredFile(selectedSegment)}
    <div class="modal-backdrop">
      <section class="modal segment-detail viewer-modal">
        <div class="modal-title">
          <div><p class="eyebrow">Recording</p><h2>{selectedSegment.displayName}</h2></div>
          <button class="icon-button" aria-label="Close recording" onclick={closeSegment}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>
          </button>
        </div>
        {#if selectedSegment.active}
          <div class="recording-notice">This recording is still being written. Playback and downloads become available after it is finalized.</div>
        {:else if selectedSegment.incomplete}
          <div class="recording-notice interrupted">This recording was interrupted before its MP4 files finalized. It can be retained or locked here, but deletion is available only from the car.</div>
        {/if}
        <div class="viewer-actions" aria-label="Recording actions">
          <button
            class:locked={selectedSegment.locked}
            class="viewer-action viewer-lock"
            disabled={selectedSegment.active || busy}
            aria-label={selectedSegment.locked ? 'Unlock recording' : 'Lock recording'}
            onclick={() => (confirmLock = selectedSegment)}
          >
            <LockIcon locked={selectedSegment.locked} />
          </button>
          {#if selectedFile}
            <a
              class="viewer-action"
              aria-label="Open in external player"
              href={videoUrl(selectedSegment.id, selectedFile.name)}
              target="_blank"
              rel="noopener"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M12 12l7-7"/><path d="M19 13v6H5V5h6"/></svg>
            </a>
            <a
              class="viewer-action"
              aria-label="Download to phone"
              download={selectedFile.name}
              href={videoUrl(selectedSegment.id, selectedFile.name, true)}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 19h14"/></svg>
            </a>
          {:else}
            <button class="viewer-action" aria-label="Open in external player unavailable" disabled>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 5h5v5M12 12l7-7"/><path d="M19 13v6H5V5h6"/></svg>
            </button>
            <button class="viewer-action" aria-label="Download unavailable" disabled>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m0 0 4-4m-4 4-4-4M5 19h14"/></svg>
            </button>
          {/if}
        </div>
        {#if selectedSegment.files.length > 0}
          <div class="viewer-toolbar">
            <ChoicePicker
              label="Camera recording"
              value={selectedFile?.name || ''}
              options={selectedSegment.files.map((file) => ({ value: file.name, label: file.name }))}
              onchange={(value) => (selectedFileName = value)}
            />
          </div>
          {#key selectedFile.name}
            <VideoPlayer label={selectedFile.name} src={videoUrl(selectedSegment.id, selectedFile.name)} />
          {/key}
        {:else if !selectedSegment.active && !selectedSegment.incomplete}
          <div class="recording-notice interrupted">No finalized video files are available for this recording.</div>
        {/if}
      </section>
    </div>
  {/if}

  {#if settingsOpen && state}
    <div class="modal-backdrop">
      <section class="modal settings">
        <div class="modal-title settings-header">
          <div><p class="eyebrow">Phone controls</p><h2>Recorder settings</h2></div>
          <div class="settings-header-actions">
            <button
              class="icon-button settings-save"
              aria-label={settingsDirty ? 'Save settings changes' : 'Settings are already saved'}
              disabled={busy || !settingsDirty}
              onclick={saveSettings}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M5 3h12l2 2v16H5z"/>
                <path d="M8 3v6h8V3M8 21v-7h8v7"/>
              </svg>
            </button>
            <button class="icon-button" aria-label="Close settings" onclick={() => (settingsOpen = false)}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>
            </button>
          </div>
        </div>
        <p class="settings-note">Phone access itself can only be enabled, disabled, or re-keyed from the car display.</p>
        {#if state.backgroundAccessSupported}
          <div
            class:granted={state.backgroundAccessGranted}
            class="settings-group background-access-setting"
          >
          <div>
            <span class="setting-label"><strong>Background recording access</strong></span>
            <small>
              {state.backgroundAccessGranted
                ? 'Allowed by Android'
                : 'Battery optimization may stop the recorder after the car interface closes'}
            </small>
          </div>
          <button
            class:granted={state.backgroundAccessGranted}
            class="background-access-action"
            aria-label="Open background recording access on the car display"
            onclick={() => (backgroundAccessConfirm = true)}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 2.75 19 5.7v5.12c0 4.38-2.77 8.34-7 10.43-4.23-2.09-7-6.05-7-10.43V5.7L12 2.75Z"/>
              <path d="m10 8.6 5.1 3.4-5.1 3.4V8.6Z"/>
            </svg>
          </button>
          </div>
        {/if}
        <label><span class="setting-label"><span>Recording volume</span><HelpButton title="Recording volume" text={settingHelp.volume} /></span>
          <ChoicePicker
            label="Recording volume"
            value={settingsDraft.volumeIndex}
            options={state.volumes.map((volume) => ({ value: volume.index, label: volume.label }))}
            onchange={(value) => updateSettingsDraft('volumeIndex', value)}
          />
        </label>
        <!-- Resolution selection is temporarily hidden; recording always uses
             the native maximum profile. Restore this block to re-enable. -->
        {#if false}
        <label><span class="setting-label"><span>Video resolution</span><HelpButton title="Video resolution" text={settingHelp.resolution} /></span>
          <ChoicePicker
            label="Video resolution"
            multiline={true}
            value={settingsDraft.resolution}
            options={resolutionOptions}
            onchange={(value) => updateSettingsDraft('resolution', value)}
          />
        </label>
        {/if}
        <div class="settings-group">
          <span class="setting-label"><strong>Camera names</strong><HelpButton title="Camera names" text={settingHelp.cameraNames} /></span>
          {#each cameraIndexes as camera}
            <label>Camera {camera}
              <TextField
                label={`Camera ${camera} name`}
                maxlength={32}
                value={settingsDraft[`camera${camera}Name`]}
                onchange={(value) => updateSettingsDraft(`camera${camera}Name`, value)}
              />
            </label>
          {/each}
        </div>
        <div class="settings-group camera-orientation-group">
          <span class="setting-label"><strong>Camera orientation</strong><HelpButton title="Camera orientation" text={settingHelp.cameraOrientation} /></span>
          <small>Enable either flip direction for each camera. Existing recordings are unchanged.</small>
          <div class="orientation-list">
            {#each cameraIndexes as camera}
              <div class="orientation-row">
                <strong>{settingsDraft[`camera${camera}Name`] || `Camera ${camera}`}</strong>
                <div class="orientation-controls">
                  <FlipToggle
                    direction="horizontal"
                    label={`Horizontal flip for ${settingsDraft[`camera${camera}Name`] || `Camera ${camera}`}`}
                    checked={Boolean(settingsDraft[`camera${camera}FlipHorizontal`])}
                    onchange={(checked) => updateCameraFlip(camera, 'Horizontal', checked)}
                  />
                  <FlipToggle
                    direction="vertical"
                    label={`Vertical flip for ${settingsDraft[`camera${camera}Name`] || `Camera ${camera}`}`}
                    checked={Boolean(settingsDraft[`camera${camera}FlipVertical`])}
                    onchange={(checked) => updateCameraFlip(camera, 'Vertical', checked)}
                  />
                </div>
              </div>
            {/each}
          </div>
        </div>
        <div class="settings-group camera-crop-group">
          <span class="setting-label"><strong>Fisheye edge crop</strong><HelpButton title="Fisheye edge crop" text={settingHelp.cameraCrop} /></span>
          <small>0% keeps the full lens. The preview updates as one shared crop is applied to all cameras.</small>
          <div class="fisheye-preview-grid">
            {#each liveCameraIndexes as camera}
              <div class="fisheye-preview-tile">
                <img
                  src={`./api/editor-cameras/${camera}.jpg`}
                  use:cameraStream={{
                    path: `./api/editor-cameras/${camera}/stream`,
                    enabled: settingsOpen
                  }}
                  alt={`Fisheye crop preview for ${settingsDraft[`camera${camera}Name`] || `Camera ${camera}`}`}
                  style={editorPreviewStyle(camera, settingsDraft, fisheyePreviewScale)}
                />
                <strong>{settingsDraft[`camera${camera}Name`] || `Camera ${camera}`}</strong>
              </div>
            {/each}
          </div>
          <div class="fisheye-slider-row">
            <SliderControl
              label="Fisheye edge crop for all cameras"
              value={settingsDraft.fisheyeCropPercent}
              minimum={0}
              maximum={35}
              onchange={(value) => updateSettingsDraft('fisheyeCropPercent', value)}
            />
            <strong>{Number(settingsDraft.fisheyeCropPercent) || 0}%</strong>
          </div>
        </div>
        <div class="settings-group">
          <span class="setting-label"><strong>Combined video layout</strong><HelpButton title="Combined video layout" text={settingHelp.combinedLayout} /></span>
          <small>Drag a camera tile onto another corner to swap them.</small>
          <CombinedLayoutEditor
            cameraNames={draftCameraNames()}
            layout={draftCombinedLayout()}
            onchange={updateCombinedLayout}
          />
        </div>
        <label><span class="setting-label"><span>Recorder quota (GB)</span><HelpButton title="Recorder quota" text={settingHelp.quota} /></span>
          <NumberField label="Recorder quota in gigabytes" min={0.25} max={1000} step={0.25} unit="GB" value={settingsDraft.quotaGb} onchange={(value) => updateSettingsDraft('quotaGb', value)} />
        </label>
        <label><span class="setting-label"><span>Retention (days)</span><HelpButton title="Retention" text={settingHelp.retention} /></span>
          <NumberField label="Retention in days" min={1} max={3650} unit="days" value={settingsDraft.retentionDays} onchange={(value) => updateSettingsDraft('retentionDays', value)} />
        </label>
        <label><span class="setting-label"><span>Segment length (minutes)</span><HelpButton title="Segment length" text={settingHelp.segment} /></span>
          <NumberField label="Segment length in minutes" min={1} max={10} unit="min" value={settingsDraft.segmentMinutes} onchange={(value) => updateSettingsDraft('segmentMinutes', value)} />
        </label>
        <label><span class="setting-label"><span>Minimum free space (%)</span><HelpButton title="Minimum free space" text={settingHelp.reserve} /></span>
          <NumberField label="Minimum free space percentage" min={1} max={25} unit="%" value={settingsDraft.minFreePercent} onchange={(value) => updateSettingsDraft('minFreePercent', value)} />
        </label>
        <label><span class="setting-label"><span>Date display format</span><HelpButton title="Date display format" text={settingHelp.date} /></span>
          <ChoicePicker
            label="Date display format"
            value={settingsDraft.dateFormat}
            options={state.dateFormats.map((format) => ({ value: format.id, label: format.label }))}
            onchange={(value) => updateSettingsDraft('dateFormat', value)}
          />
        </label>
      </section>
    </div>
  {/if}

  {#if backgroundAccessConfirm && state?.backgroundAccessSupported}
    <ConfirmDialog
      title="Open background recording access?"
      message="The car display will open an explanation and Android's battery-optimization control. Confirm the request on the car so recording can continue while its interface is closed. Android force-stop still stops every app."
      confirmLabel="Open on car"
      busy={busy}
      onconfirm={requestBackgroundAccess}
      oncancel={() => (backgroundAccessConfirm = false)}
    />
  {/if}

  {#if confirmLock}
    <ConfirmDialog
      title={confirmLock.locked ? 'Unlock this recording?' : 'Lock this recording?'}
      message={confirmLock.locked
        ? 'Automatic cleanup may remove this recording after it is unlocked.'
        : 'Locked recordings are protected from automatic cleanup.'}
      confirmLabel={confirmLock.locked ? 'Unlock' : 'Lock'}
      oncancel={() => (confirmLock = null)}
      onconfirm={() => toggleLock(confirmLock)}
    />
  {/if}

  {#if bulkAction}
    <ConfirmDialog
      title={bulkAction.title}
      message={bulkAction.message}
      confirmLabel={bulkAction.confirmLabel}
      oncancel={() => (bulkAction = null)}
      onconfirm={performBulkAction}
    />
  {/if}
{/if}
