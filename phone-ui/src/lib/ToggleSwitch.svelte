<script>
  export let checked = false;
  export let disabled = false;
  export let label = 'Toggle';
  export let onchange = () => {};
  export let tone = 'default';
</script>

<button
  type="button"
  class:checked
  class:recording={tone === 'recording'}
  class="toggle-switch"
  {disabled}
  aria-label={label}
  aria-pressed={checked}
  onclick={() => onchange(!checked)}
>
  <span class="toggle-thumb" aria-hidden="true">
    {#if checked}
      <svg viewBox="0 0 24 24"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>
    {:else}
      <svg viewBox="0 0 24 24"><path d="m8 5 11 7-11 7Z"/></svg>
    {/if}
  </span>
</button>
