<script>
  export let confirmLabel = 'Confirm';
  export let message = '';
  export let oncancel = () => {};
  export let onconfirm = () => {};
  export let title = 'Confirm action';
  export let tone = 'default';
</script>

<div class="modal-backdrop confirmation-backdrop" role="presentation">
  <div class="modal confirmation-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirmation-title">
    <div class="confirmation-icon" class:danger={tone === 'danger'} aria-hidden="true">
      <svg viewBox="0 0 24 24"><path d="M12 3 2.8 20h18.4L12 3Z"/><path d="M12 9v5m0 3v.1"/></svg>
    </div>
    <h2 id="confirmation-title">{title}</h2>
    <p>{message}</p>
    <div class="confirmation-actions">
      <button type="button" class="secondary-action" onclick={oncancel}>Cancel</button>
      <button type="button" class:danger-action={tone === 'danger'} class="primary-action" onclick={onconfirm}>{confirmLabel}</button>
    </div>
  </div>
</div>
