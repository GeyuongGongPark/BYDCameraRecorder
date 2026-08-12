<script>
  let {
    label,
    value = 0,
    minimum = 0,
    maximum = 100,
    onchange = () => {}
  } = $props();

  let dragging = $state(false);
  let track;

  const normalizedValue = () =>
    Math.max(minimum, Math.min(maximum, Number(value) || minimum));

  function updateFromPointer(event) {
    if (!track) {
      return;
    }
    const bounds = track.getBoundingClientRect();
    const fraction = Math.max(
      0,
      Math.min(1, (event.clientX - bounds.left) / Math.max(1, bounds.width))
    );
    onchange(Math.round(minimum + fraction * (maximum - minimum)));
  }

  function beginDrag(event) {
    dragging = true;
    event.currentTarget.setPointerCapture(event.pointerId);
    updateFromPointer(event);
  }

  function moveDrag(event) {
    if (dragging) {
      updateFromPointer(event);
    }
  }

  function endDrag(event) {
    if (!dragging) {
      return;
    }
    updateFromPointer(event);
    dragging = false;
  }

  function handleKeydown(event) {
    const current = normalizedValue();
    if (event.key === 'ArrowLeft' || event.key === 'ArrowDown') {
      event.preventDefault();
      onchange(Math.max(minimum, current - 1));
    } else if (event.key === 'ArrowRight' || event.key === 'ArrowUp') {
      event.preventDefault();
      onchange(Math.min(maximum, current + 1));
    } else if (event.key === 'Home') {
      event.preventDefault();
      onchange(minimum);
    } else if (event.key === 'End') {
      event.preventDefault();
      onchange(maximum);
    }
  }
</script>

<div
  class:dragging
  class="slider-control"
  bind:this={track}
  role="slider"
  aria-label={label}
  aria-valuemin={minimum}
  aria-valuemax={maximum}
  aria-valuenow={normalizedValue()}
  tabindex="0"
  onkeydown={handleKeydown}
  onpointerdown={beginDrag}
  onpointermove={moveDrag}
  onpointerup={endDrag}
  onpointercancel={() => (dragging = false)}
>
  <span
    class="slider-progress"
    style={`width: ${((normalizedValue() - minimum) / (maximum - minimum)) * 100}%`}
  ></span>
  <span
    class="slider-thumb"
    style={`left: ${((normalizedValue() - minimum) / (maximum - minimum)) * 100}%`}
  ></span>
</div>

<style>
  .slider-control {
    position: relative;
    height: 48px;
    cursor: pointer;
    touch-action: none;
    user-select: none;
    outline: none;
  }
  .slider-control::before,
  .slider-progress {
    position: absolute;
    top: 50%;
    left: 0;
    height: 8px;
    border-radius: 999px;
    transform: translateY(-50%);
    content: '';
  }
  .slider-control::before {
    width: 100%;
    background: #223a52;
    box-shadow: inset 0 0 0 1px #31516f;
  }
  .slider-progress {
    background: linear-gradient(90deg, #1ca8e2, #58d3ff);
    box-shadow: 0 0 12px rgb(54 194 250 / 22%);
  }
  .slider-thumb {
    position: absolute;
    top: 50%;
    width: 28px;
    height: 28px;
    border: 2px solid #63d5ff;
    border-radius: 50%;
    background: #0b1c2f;
    box-shadow: 0 3px 12px rgb(0 0 0 / 36%);
    transform: translate(-50%, -50%);
    transition: transform 110ms ease, background-color 110ms ease;
  }
  .slider-control:focus-visible .slider-thumb {
    box-shadow: 0 0 0 4px rgb(70 197 247 / 22%), 0 3px 12px rgb(0 0 0 / 36%);
  }
  .slider-control.dragging .slider-thumb {
    background: #164667;
    transform: translate(-50%, -50%) scale(1.12);
  }
</style>
