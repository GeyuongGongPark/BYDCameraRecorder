<script>
  export let checked = false;
  export let direction = 'horizontal';
  export let label = 'Camera orientation';
  export let onchange = () => {};
</script>

<button
  type="button"
  class:checked
  class:vertical={direction === 'vertical'}
  class="flip-toggle"
  aria-label={`${label}: ${checked ? 'on' : 'off'}`}
  aria-pressed={checked}
  onclick={() => onchange(!checked)}
>
  <span class="flip-thumb" aria-hidden="true">
    {#if direction === 'vertical'}
      <svg viewBox="0 0 48 48">
        <path class="axis" d="M7 24h6m5 0h12m5 0h6"/>
        <path d="m13 19 11-10 11 10H13Zm0 10 11 10 11-10H13Z"/>
        <circle class="marker" cx="20" cy="15" r="2.5"/>
      </svg>
    {:else}
      <svg viewBox="0 0 48 48">
        <path class="axis" d="M24 7v6m0 5v12m0 5v6"/>
        <path d="M19 13 9 24l10 11V13Zm10 0 10 11-10 11V13Z"/>
        <circle class="marker" cx="15" cy="20" r="2.5"/>
      </svg>
    {/if}
  </span>
</button>

<style>
  .flip-toggle {
    position: relative;
    width: 78px;
    height: 44px;
    padding: 4px;
    color: #70d7ff;
    background: #091525;
    border: 1px solid #355674;
    border-radius: 23px;
    transition: background .18s, border-color .18s, transform .12s;
  }

  .flip-toggle:active {
    transform: scale(.94);
  }

  .flip-thumb {
    position: absolute;
    top: 4px;
    left: 4px;
    width: 34px;
    height: 34px;
    display: grid;
    place-items: center;
    background: #14334c;
    border: 1px solid #3dc8ff;
    border-radius: 50%;
    transition: transform .2s cubic-bezier(.2, .8, .2, 1),
      color .18s, background .18s, border-color .18s, box-shadow .18s;
  }

  .checked {
    background: #0c264a;
    border-color: #4376be;
  }

  .checked .flip-thumb {
    color: #dcecff;
    background: #235297;
    border-color: #78aaff;
    box-shadow: 0 0 18px rgba(106, 167, 255, .24);
    transform: translateX(34px);
  }

  svg {
    width: 25px;
    fill: none;
    stroke: currentColor;
    stroke-width: 2.8;
    stroke-linecap: round;
    stroke-linejoin: round;
    transition: transform .2s cubic-bezier(.2, .8, .2, 1);
  }

  .axis {
    color: #9fb3cc;
    stroke-width: 2.4;
  }

  .marker {
    fill: #6aa7ff;
    stroke: none;
  }

  .checked:not(.vertical) svg {
    transform: scaleX(-1);
  }

  .checked.vertical svg {
    transform: scaleY(-1);
  }

  .checked .marker {
    fill: #dcecff;
  }
</style>
