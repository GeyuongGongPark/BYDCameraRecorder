<script>
  import { createPinchZoom } from './pinchZoom.js';
  import { cameraStream } from './cameraStream.js';
  import ResetZoomIcon from './ResetZoomIcon.svelte';

  export let alt = '';
  export let src = '';
  export let streamPath = '';
  export let flipStyle = '';

  let viewport;
  let zoom = 1;
  const pinchZoom = createPinchZoom({
    getViewport: () => viewport,
    getZoom: () => zoom,
    setZoom: (nextZoom) => (zoom = nextZoom)
  });

  function resetZoom() {
    pinchZoom.reset();
    zoom = 1;
    viewport.scrollLeft = 0;
    viewport.scrollTop = 0;
  }
</script>

<div class="pinch-image-stage">
  <div
    class="pinch-image-viewport"
    bind:this={viewport}
    role="group"
    aria-label={`Pinch with two fingers to zoom ${alt}`}
    onpointerdown={pinchZoom.pointerDown}
    onpointermove={pinchZoom.pointerMove}
    onpointerup={pinchZoom.pointerEnd}
    onpointercancel={pinchZoom.pointerEnd}
  >
    <img
      {src}
      {alt}
      use:cameraStream={{ path: streamPath, enabled: Boolean(streamPath) }}
      style={`width: ${zoom * 100}%;${flipStyle ? ` ${flipStyle}` : ''}`}
    />
  </div>
  {#if zoom > 1.01}
    <button type="button" class="reset-zoom" aria-label="Reset zoom" onclick={resetZoom}>
      <ResetZoomIcon />
    </button>
  {/if}
</div>
