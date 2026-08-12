const jsonHeaders = { 'Content-Type': 'application/json' };

export class ApiError extends Error {
  constructor(message, status, retryAfterSeconds = 0) {
    super(message);
    this.name = 'ApiError';
    this.retryAfterSeconds = retryAfterSeconds;
    this.status = status;
  }
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    cache: 'no-store',
    credentials: 'same-origin',
    ...options
  });
  const contentType = response.headers.get('content-type') || '';
  const body = contentType.includes('application/json')
    ? await response.json()
    : await response.text();
  if (!response.ok) {
    const retryAfterSeconds = Number(
      body?.retryAfterSeconds || response.headers.get('retry-after') || 0
    );
    const fallback =
      response.status === 401
        ? 'Authorization is required'
        : response.status === 429
          ? 'Wait before trying the PIN again'
          : `Request failed (${response.status})`;
    throw new ApiError(body?.message || (typeof body === 'string' && body) || fallback, response.status, retryAfterSeconds);
  }
  return body;
}

export const api = {
  authenticate: (pin) =>
    request('./api/auth', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ pin })
    }),
  getAuthentication: () => request('./api/auth'),
  getState: () => request('./api/state'),
  getStatus: () => request('./api/status'),
  setRecording: (enabled) =>
    request('./api/recording', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ enabled })
    }),
  saveSettings: (settings) =>
    request('./api/settings', {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify(settings)
    }),
  requestBackgroundAccess: () =>
    request('./api/background-access', {
      method: 'POST',
      headers: jsonHeaders,
      body: '{}'
    }),
  setLocked: (segmentId, locked) =>
    request(`./api/segments/${encodeURIComponent(segmentId)}/lock`, {
      method: 'POST',
      headers: jsonHeaders,
      body: JSON.stringify({ locked })
    })
};
