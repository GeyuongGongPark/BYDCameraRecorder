import { describe, expect, it } from 'vitest';
import { formatBytes, videoUrl } from './format.js';

describe('phone display helpers', () => {
  it('formats storage sizes consistently', () => {
    expect(formatBytes(1536)).toBe('1.5 KB');
  });

  it('encodes segment and file path components', () => {
    expect(videoUrl('2026-07-26T17-42-00', 'camera 1.mp4', true)).toBe(
      './api/segments/2026-07-26T17-42-00/files/camera%201.mp4?download=1'
    );
  });
});
