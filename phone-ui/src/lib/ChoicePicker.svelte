<script>
  import { onMount } from 'svelte';

  export let disabled = false;
  export let label = 'Choose an option';
  export let multiline = false;
  export let onchange = () => {};
  export let options = [];
  export let value;

  let open = false;
  let picker;

  $: selected = options.find((option) => option.value === value) || options[0];

  function choose(option) {
    onchange(option.value);
    open = false;
  }

  onMount(() => {
    function dismiss(event) {
      if (open && picker && !picker.contains(event.target)) {
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

<div class="choice-picker" bind:this={picker}>
  <button
    type="button"
    class="choice-trigger"
    class:multiline
    {disabled}
    aria-label={label}
    aria-expanded={open}
    onclick={() => (open = !open)}
  >
    <span>{selected?.label || 'Unavailable'}</span>
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 9 5 5 5-5"/></svg>
  </button>
  {#if open}
    <div class="choice-menu" role="listbox" aria-label={label}>
      {#each options as option}
        <button
          type="button"
          class:selected={option.value === value}
          role="option"
          aria-selected={option.value === value}
          onclick={() => choose(option)}
        >
          <span>{option.label}</span>
          {#if option.value === value}
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 4 4L19 6"/></svg>
          {/if}
        </button>
      {/each}
    </div>
  {/if}
</div>
