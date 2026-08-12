export function createPinchZoom({ getViewport, getZoom, setZoom }) {
  const pointers = new Map();
  let pinchDistance = 0;
  let pinchZoom = 1;
  let anchorContentX = 0;
  let anchorContentY = 0;
  let pendingFrame = 0;

  function activePointers() {
    return Array.from(pointers.values());
  }

  function distance() {
    const active = activePointers();
    if (active.length < 2) {
      return 0;
    }
    return Math.hypot(
      active[0].x - active[1].x,
      active[0].y - active[1].y
    );
  }

  function midpoint() {
    const active = activePointers();
    return {
      x: (active[0].x + active[1].x) / 2,
      y: (active[0].y + active[1].y) / 2
    };
  }

  function pointerDown(event) {
    pointers.set(event.pointerId, {
      x: event.clientX,
      y: event.clientY
    });
    if (event.isTrusted) {
      event.currentTarget.setPointerCapture(event.pointerId);
    }
    if (pointers.size !== 2) {
      return;
    }
    const viewport = getViewport();
    const focus = midpoint();
    const bounds = viewport.getBoundingClientRect();
    pinchDistance = distance();
    pinchZoom = getZoom();
    anchorContentX =
      (viewport.scrollLeft + focus.x - bounds.left) / pinchZoom;
    anchorContentY =
      (viewport.scrollTop + focus.y - bounds.top) / pinchZoom;
  }

  function pointerMove(event) {
    if (!pointers.has(event.pointerId)) {
      return;
    }
    pointers.set(event.pointerId, {
      x: event.clientX,
      y: event.clientY
    });
    if (pointers.size !== 2 || pinchDistance <= 0) {
      return;
    }
    event.preventDefault();
    const requestedZoom =
      pinchZoom * (distance() / pinchDistance);
    if (!Number.isFinite(requestedZoom)) {
      return;
    }
    const nextZoom = Math.max(
      1,
      Math.round(requestedZoom * 100) / 100
    );
    const focus = midpoint();
    const viewport = getViewport();
    const bounds = viewport.getBoundingClientRect();
    const localFocusX = focus.x - bounds.left;
    const localFocusY = focus.y - bounds.top;
    setZoom(nextZoom);
    if (pendingFrame) {
      cancelAnimationFrame(pendingFrame);
    }
    pendingFrame = requestAnimationFrame(() => {
      pendingFrame = 0;
      const currentViewport = getViewport();
      currentViewport.scrollLeft =
        anchorContentX * nextZoom - localFocusX;
      currentViewport.scrollTop =
        anchorContentY * nextZoom - localFocusY;
    });
  }

  function pointerEnd(event) {
    pointers.delete(event.pointerId);
    if (pointers.size < 2) {
      pinchDistance = 0;
      pinchZoom = getZoom();
    }
  }

  function reset() {
    pointers.clear();
    pinchDistance = 0;
    pinchZoom = 1;
    if (pendingFrame) {
      cancelAnimationFrame(pendingFrame);
      pendingFrame = 0;
    }
  }

  return {
    pointerDown,
    pointerEnd,
    pointerMove,
    reset
  };
}
