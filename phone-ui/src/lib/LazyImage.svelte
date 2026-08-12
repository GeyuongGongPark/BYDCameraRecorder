<script>
  import { onMount } from 'svelte';

  let {
    src,
    alt,
    className = ''
  } = $props();

  let image;
  let visible = $state(false);

  onMount(() => {
    if (typeof IntersectionObserver !== 'function') {
      visible = true;
      return undefined;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          visible = true;
          observer.disconnect();
        }
      },
      { rootMargin: '600px 0px' }
    );
    observer.observe(image);
    return () => observer.disconnect();
  });
</script>

<img
  bind:this={image}
  class={className}
  src={visible ? src : undefined}
  {alt}
  decoding="async"
/>
