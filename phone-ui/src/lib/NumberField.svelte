<script>
  import { onDestroy } from 'svelte';

  export let label = 'Numeric value';
  export let max = Number.MAX_SAFE_INTEGER;
  export let min = 0;
  export let onchange = () => {};
  export let step = 1;
  export let unit = '';
  export let value = 0;

  const holdStartDelay = 460;
  const maximumRepeatDelay = 260;
  const minimumRepeatDelay = 48;
  const repeatAcceleration = 0.86;

  let heldButton = null;
  let heldDirection = 0;
  let heldPointerId = null;
  let holdTimer;
  let displayedValue = '';
  let pointerClickPending = false;
  let repeatCount = 0;
  let workingValue = Number(value);

  $: if (heldDirection === 0) {
    workingValue = Number(value);
  }
  $: displayedValue = formatValue(workingValue);

  function normalizedValue(nextValue) {
    const parsed = Number(nextValue);
    if (!Number.isFinite(parsed)) {
      return Number(value);
    }
    const stepped = Math.round(parsed / step) * step;
    return Math.max(min, Math.min(max, stepped));
  }

  function updateBy(direction) {
    const base = heldDirection === 0 ? Number(value) : workingValue;
    const nextValue = normalizedValue(base + direction * step);
    if (nextValue === base) {
      return false;
    }
    workingValue = nextValue;
    onchange(nextValue);
    return true;
  }

  function repeatHeldStep() {
    if (heldDirection === 0 || !updateBy(heldDirection)) {
      stopHolding();
      return;
    }
    repeatCount += 1;
    const nextDelay = Math.max(
      minimumRepeatDelay,
      Math.round(maximumRepeatDelay * Math.pow(repeatAcceleration, repeatCount))
    );
    holdTimer = window.setTimeout(repeatHeldStep, nextDelay);
  }

  function startHolding(event, direction) {
    if (event.button !== 0 || event.currentTarget.disabled) {
      return;
    }
    event.preventDefault();
    stopHolding();
    pointerClickPending = true;
    heldButton = event.currentTarget;
    heldPointerId = event.pointerId;
    try {
      heldButton.setPointerCapture?.(event.pointerId);
    } catch (error) {
      // Synthetic test events may not own an active browser pointer.
    }
    heldDirection = direction;
    repeatCount = 0;
    workingValue = Number(value);
    if (updateBy(direction)) {
      holdTimer = window.setTimeout(repeatHeldStep, holdStartDelay);
    }
  }

  function stopHolding() {
    if (holdTimer !== undefined) {
      window.clearTimeout(holdTimer);
      holdTimer = undefined;
    }
    if (
      heldButton &&
      heldPointerId !== null &&
      heldButton.hasPointerCapture?.(heldPointerId)
    ) {
      try {
        heldButton.releasePointerCapture(heldPointerId);
      } catch (error) {
        // Pointer capture may already be released after focus loss.
      }
    }
    heldButton = null;
    heldPointerId = null;
    heldDirection = 0;
    repeatCount = 0;
  }

  function finishPointerInteraction() {
    stopHolding();
    window.setTimeout(() => {
      pointerClickPending = false;
    }, 0);
  }

  function cancelPointerInteraction() {
    pointerClickPending = false;
    stopHolding();
  }

  function handleClick(direction) {
    if (pointerClickPending) {
      pointerClickPending = false;
      return;
    }
    updateBy(direction);
  }

  function formatValue(numeric) {
    const stepText = String(step);
    const decimalPlaces = stepText.includes('.')
      ? stepText.length - stepText.indexOf('.') - 1
      : 0;
    const display = decimalPlaces > 0
      ? numeric.toFixed(decimalPlaces).replace(/\.?0+$/, '')
      : String(Math.round(numeric));
    return unit === '%' ? `${display}%` : unit ? `${display} ${unit}` : display;
  }

  onDestroy(stopHolding);
</script>

<svelte:window
  onpointerup={finishPointerInteraction}
  onpointercancel={cancelPointerInteraction}
  onblur={cancelPointerInteraction}
/>

<div class="number-field">
  <button
    type="button"
    aria-label="Decrease {label}"
    disabled={Number(value) <= min}
    onpointerdown={(event) => startHolding(event, -1)}
    onclick={() => handleClick(-1)}
    oncontextmenu={(event) => event.preventDefault()}
  >
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14"/></svg>
  </button>
  <output
    aria-label={label}
    aria-live="polite"
  >{displayedValue}</output>
  <button
    type="button"
    aria-label="Increase {label}"
    disabled={Number(value) >= max}
    onpointerdown={(event) => startHolding(event, 1)}
    onclick={() => handleClick(1)}
    oncontextmenu={(event) => event.preventDefault()}
  >
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14M12 5v14"/></svg>
  </button>
</div>
