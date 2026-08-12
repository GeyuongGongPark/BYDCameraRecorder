<script>
  import { onMount } from 'svelte';

  export let text = '';
  export let title = 'Setting help';

  let open = false;
  let root;

  onMount(() => {
    function dismiss(event) {
      if (open && root && !root.contains(event.target)) {
        open = false;
      }
    }
    function dismissWithKeyboard(event) {
      if (event.key === 'Escape') {
        open = false;
      }
    }
    window.addEventListener('click', dismiss);
    window.addEventListener('keydown', dismissWithKeyboard);
    return () => {
      window.removeEventListener('click', dismiss);
      window.removeEventListener('keydown', dismissWithKeyboard);
    };
  });
</script>

<span class="help-control" bind:this={root}>
  <button
    type="button"
    class="help-button"
    aria-label={`Explain ${title}`}
    aria-expanded={open}
    onclick={() => (open = !open)}
  >
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9"/>
      <path d="M9.7 9a2.45 2.45 0 1 1 3.7 2.1c-.9.55-1.4 1.05-1.4 2.15"/>
      <path d="M12 16.8h.01"/>
    </svg>
  </button>
  {#if open}
    <span class="help-tooltip" role="tooltip">
      <strong>{title}</strong>
      <span>{text}</span>
    </span>
  {/if}
</span>
