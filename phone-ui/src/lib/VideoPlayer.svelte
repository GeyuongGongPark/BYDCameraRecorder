<script>
  import { createPinchZoom } from './pinchZoom.js';
  import ResetZoomIcon from './ResetZoomIcon.svelte';
  export let label = 'Camera recording';
  export let src = '';

  let currentTime = 0;
  let duration = 0;
  let playing = false;
  let video;
  let videoViewport;
  let zoom = 1;
  const pinchZoom = createPinchZoom({
    getViewport: () => videoViewport,
    getZoom: () => zoom,
    setZoom: (nextZoom) => (zoom = nextZoom)
  });

  function formatTime(seconds) {
    if (!Number.isFinite(seconds)) {
      return '0:00';
    }
    const whole = Math.max(0, Math.floor(seconds));
    return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
  }

  async function togglePlayback() {
    if (video.paused) {
      await video.play();
      playing = true;
    } else {
      video.pause();
      playing = false;
    }
  }

  function seek(event) {
    video.currentTime = Number(event.currentTarget.value);
    currentTime = video.currentTime;
  }

  function resetZoom() {
    pinchZoom.reset();
    zoom = 1;
    if (videoViewport) {
      videoViewport.scrollLeft = 0;
      videoViewport.scrollTop = 0;
    }
  }
</script>

<div class="video-player">
  <div class="video-stage">
    <div
      class="video-viewport"
      role="group"
      aria-label={`Pinch with two fingers to zoom ${label}`}
      bind:this={videoViewport}
      onpointerdown={pinchZoom.pointerDown}
      onpointermove={pinchZoom.pointerMove}
      onpointerup={pinchZoom.pointerEnd}
      onpointercancel={pinchZoom.pointerEnd}
    >
      <!-- svelte-ignore a11y_media_has_caption Camera recordings are intentionally silent. -->
      <video
        bind:this={video}
        playsinline
        preload="metadata"
        {src}
        style={`width: ${zoom * 100}%`}
        onended={() => (playing = false)}
        onloadedmetadata={() => (duration = video.duration || 0)}
        onpause={() => (playing = false)}
        onplay={() => (playing = true)}
        ontimeupdate={() => (currentTime = video.currentTime)}
      ></video>
    </div>
    {#if zoom > 1.01}
      <button type="button" class="reset-zoom" aria-label="Reset zoom" onclick={resetZoom}>
        <ResetZoomIcon />
      </button>
    {/if}
  </div>
  <div class="video-controls">
    <button type="button" aria-label={playing ? `Pause ${label}` : `Play ${label}`} onclick={togglePlayback}>
      {#if playing}
        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>
      {:else}
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 5 11 7-11 7Z"/></svg>
      {/if}
    </button>
    <span>{formatTime(currentTime)}</span>
    <input
      type="range"
      min="0"
      max={Math.max(duration, 0)}
      step="0.1"
      value={currentTime}
      aria-label="Seek {label}"
      oninput={seek}
    />
    <span>{formatTime(duration)}</span>
  </div>
</div>
