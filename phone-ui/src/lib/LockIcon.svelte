<script>
  export let locked = false;
</script>

<svg
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width="1.9"
  stroke-linecap="round"
  stroke-linejoin="round"
  aria-hidden="true"
>
  <rect x="4.5" y="10" width="15" height="11" rx="2.5" />
  {#if locked}
    <path d="M8 10V7a4 4 0 0 1 8 0v3" />
  {:else}
    <path d="M8 10V7a4 4 0 0 1 7.4-2.1" />
  {/if}
  <circle cx="12" cy="15.5" r="1.2" />
</svg>
