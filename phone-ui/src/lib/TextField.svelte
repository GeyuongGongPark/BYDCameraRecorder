<script>
  export let label = 'Text value';
  export let maxlength = 32;
  export let onchange = () => {};
  export let value = '';
</script>

<input
  class="text-field"
  type="text"
  {maxlength}
  aria-label={label}
  value={value}
  oninput={(event) => onchange(event.currentTarget.value)}
/>
