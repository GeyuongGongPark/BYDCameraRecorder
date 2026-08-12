<script>
  import { flip } from 'svelte/animate';

  export let cameraNames = ['Camera 1', 'Camera 2', 'Camera 3', 'Camera 4'];
  export let layout = [0, 1, 2, 3];
  export let onchange = () => {};

  const positions = ['Top left', 'Top right', 'Bottom left', 'Bottom right'];
  let draggingPosition = -1;
  let draggingCameraIndex = -1;
  let targetPosition = -1;
  let previewLayout = [...layout];
  let dragBaseLayout = [...layout];
  let cornerGeometry = [];
  let pendingCommittedLayout = null;
  let pointerStartX = 0;
  let pointerStartY = 0;
  let dragOffsetX = 0;
  let dragOffsetY = 0;
  let dragOriginCenterX = 0;
  let dragOriginCenterY = 0;
  let overlayLeft = 0;
  let overlayTop = 0;
  let overlayWidth = 0;
  let overlayHeight = 0;
  let editor;

  $: if (draggingPosition < 0) {
    const externalLayout = [...layout];
    if (
      pendingCommittedLayout &&
      pendingCommittedLayout.every(
        (cameraIndex, position) => cameraIndex === externalLayout[position]
      )
    ) {
      pendingCommittedLayout = null;
    }
    previewLayout = [
      ...(pendingCommittedLayout || externalLayout)
    ];
  }

  function beginDrag(event, position) {
    event.preventDefault();
    const tileBounds = event.currentTarget.getBoundingClientRect();
    const editorBounds = editor.getBoundingClientRect();
    dragBaseLayout = [...(pendingCommittedLayout || layout)];
    draggingPosition = position;
    draggingCameraIndex = dragBaseLayout[position];
    targetPosition = -1;
    previewLayout = [...dragBaseLayout];
    cornerGeometry = Array.from(
      editor.querySelectorAll('[data-layout-position]')
    ).map((tile) => {
      const bounds = tile.getBoundingClientRect();
      return {
        position: Number(tile.dataset.layoutPosition),
        centerX: bounds.left + bounds.width / 2,
        centerY: bounds.top + bounds.height / 2,
        width: bounds.width,
        height: bounds.height
      };
    });
    pointerStartX = event.clientX;
    pointerStartY = event.clientY;
    dragOffsetX = 0;
    dragOffsetY = 0;
    dragOriginCenterX = tileBounds.left + tileBounds.width / 2;
    dragOriginCenterY = tileBounds.top + tileBounds.height / 2;
    overlayLeft = tileBounds.left - editorBounds.left;
    overlayTop = tileBounds.top - editorBounds.top;
    overlayWidth = tileBounds.width;
    overlayHeight = tileBounds.height;
    if (event.isTrusted) {
      editor.setPointerCapture(event.pointerId);
    }
  }

  function moveDrag(event) {
    if (draggingPosition < 0) {
      return;
    }
    event.preventDefault();
    dragOffsetX = event.clientX - pointerStartX;
    dragOffsetY = event.clientY - pointerStartY;
    const draggedCenterX = dragOriginCenterX + dragOffsetX;
    const draggedCenterY = dragOriginCenterY + dragOffsetY;
    const latchedTarget = cornerGeometry.find(
      (corner) => corner.position === targetPosition
    );
    if (
      latchedTarget &&
      Math.abs(draggedCenterX - latchedTarget.centerX) <= latchedTarget.width * 0.82 &&
      Math.abs(draggedCenterY - latchedTarget.centerY) <= latchedTarget.height * 0.82
    ) {
      return;
    }
    const candidate = cornerGeometry.find(
      (corner) =>
        corner.position !== draggingPosition &&
        Math.abs(draggedCenterX - corner.centerX) <= corner.width * 0.4 &&
        Math.abs(draggedCenterY - corner.centerY) <= corner.height * 0.4
    );
    const nextTargetPosition = Number(candidate?.position);
    const normalizedTarget =
      Number.isInteger(nextTargetPosition) &&
      nextTargetPosition >= 0 &&
      nextTargetPosition < layout.length
        ? nextTargetPosition
        : -1;
    if (normalizedTarget === targetPosition) {
      return;
    }
    targetPosition = normalizedTarget;
    previewLayout = [...dragBaseLayout];
    if (targetPosition >= 0) {
      [previewLayout[draggingPosition], previewLayout[targetPosition]] =
        [previewLayout[targetPosition], previewLayout[draggingPosition]];
    }
  }

  function finishDrag(event) {
    if (draggingPosition < 0) {
      return;
    }
    event.preventDefault();
    const releaseTarget =
      targetPosition >= 0
        ? targetPosition
        : gridPositionNearCenter(
            dragOriginCenterX + event.clientX - pointerStartX,
            dragOriginCenterY + event.clientY - pointerStartY
          );
    const committedLayout =
      releaseTarget >= 0 && releaseTarget !== draggingPosition
        ? [...dragBaseLayout]
        : null;
    if (committedLayout) {
      [committedLayout[draggingPosition], committedLayout[releaseTarget]] =
        [committedLayout[releaseTarget], committedLayout[draggingPosition]];
      pendingCommittedLayout = committedLayout;
    }
    clearDrag(false);
    if (committedLayout) {
      onchange(committedLayout);
    }
  }

  function gridPositionNearCenter(clientX, clientY) {
    const bounds = editor.getBoundingClientRect();
    const cellWidth = bounds.width / 2;
    const cellHeight = bounds.height / 2;
    const column = Math.floor((clientX - bounds.left) / cellWidth);
    const row = Math.floor((clientY - bounds.top) / cellHeight);
    if (column < 0 || column > 1 || row < 0 || row > 1) {
      return -1;
    }
    const centerX = bounds.left + (column + 0.5) * cellWidth;
    const centerY = bounds.top + (row + 0.5) * cellHeight;
    return (
      Math.abs(clientX - centerX) <= cellWidth * 0.42 &&
      Math.abs(clientY - centerY) <= cellHeight * 0.42
    )
      ? row * 2 + column
      : -1;
  }

  function cancelDrag() {
    if (draggingPosition < 0) {
      return;
    }
    clearDrag(true);
  }

  function clearDrag(resetPreview) {
    draggingPosition = -1;
    draggingCameraIndex = -1;
    targetPosition = -1;
    cornerGeometry = [];
    dragOffsetX = 0;
    dragOffsetY = 0;
    if (resetPreview) {
      pendingCommittedLayout = null;
      previewLayout = [...layout];
    }
  }
</script>

<div
  class="combined-layout"
  role="group"
  aria-label="Combined video camera layout"
  bind:this={editor}
  onpointermove={moveDrag}
  onpointerup={finishDrag}
  onpointercancel={cancelDrag}
>
  {#each previewLayout as cameraIndex, position (cameraIndex)}
    <button
      type="button"
      class:drag-placeholder={draggingCameraIndex === cameraIndex}
      class:swap-preview={targetPosition >= 0 && (position === draggingPosition || position === targetPosition)}
      data-layout-position={position}
      aria-label={`${positions[position]}: ${cameraNames[cameraIndex]}. Drag to swap.`}
      animate:flip={{ duration: 170 }}
      onpointerdown={(event) => beginDrag(event, position)}
    >
      <span class="tile-content">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h11A2.5 2.5 0 0 1 20 8.5v8a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5v-8Z"/>
          <circle cx="12" cy="12.5" r="3.2"/>
          <path d="m8 6 1-2h6l1 2"/>
        </svg>
        <strong>{cameraNames[cameraIndex] || `Camera ${cameraIndex + 1}`}</strong>
        <small>{positions[position]}</small>
      </span>
    </button>
  {/each}
  {#if draggingPosition >= 0}
    <div
      class:swap-preview={targetPosition >= 0}
      class="layout-drag-overlay"
      style={`left:${overlayLeft}px;top:${overlayTop}px;width:${overlayWidth}px;height:${overlayHeight}px;transform:translate3d(${dragOffsetX}px,${dragOffsetY}px,0)`}
      aria-hidden="true"
    >
      <span class="tile-content">
        <svg viewBox="0 0 24 24">
          <path d="M4 8.5A2.5 2.5 0 0 1 6.5 6h11A2.5 2.5 0 0 1 20 8.5v8a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5v-8Z"/>
          <circle cx="12" cy="12.5" r="3.2"/>
          <path d="m8 6 1-2h6l1 2"/>
        </svg>
        <strong>{cameraNames[draggingCameraIndex] || `Camera ${draggingCameraIndex + 1}`}</strong>
        <small>{positions[draggingPosition]}</small>
      </span>
    </div>
  {/if}
</div>
