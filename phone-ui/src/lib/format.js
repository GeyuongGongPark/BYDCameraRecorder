export function formatBytes(bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = Math.max(0, Number(bytes) || 0);
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(1)} ${units[unit]}`;
}

export function videoUrl(segmentId, fileName, download = false) {
  const base =
    `./api/segments/${encodeURIComponent(segmentId)}/files/` +
    encodeURIComponent(fileName);
  return download ? `${base}?download=1` : base;
}
