const DEV_PIN = '246810';
const DEV_SESSION = 'dev-persistent-session';
const sessions = new Set([DEV_SESSION]);
let nextAttemptAt = 0;
let recording = false;
let settings = {
  resolution: 'standard',
  volumeIndex: 0,
  quotaGb: 20,
  retentionDays: 21,
  segmentMinutes: 3,
  minFreePercent: 5,
  dateFormat: 'local_short',
  camera1Name: 'Front',
  camera2Name: 'Right',
  camera3Name: 'Rear',
  camera4Name: 'Left',
  camera1FlipHorizontal: false,
  camera2FlipHorizontal: false,
  camera3FlipHorizontal: false,
  camera4FlipHorizontal: false,
  camera1FlipVertical: false,
  camera2FlipVertical: false,
  camera3FlipVertical: false,
  camera4FlipVertical: false,
  fisheyeCropPercent: 0,
  combinedTopLeft: 0,
  combinedTopRight: 1,
  combinedBottomLeft: 2,
  combinedBottomRight: 3
};

const recordings = [
  {
    id: '2026-07-26T05-59-32',
    displayName: '26/07/2026 05:59',
    sizeBytes: 1717987,
    locked: false,
    active: false,
    incomplete: true,
    files: []
  },
  {
    id: '2026-07-26T05-55-32',
    displayName: '26/07/2026 05:55',
    sizeBytes: 94208,
    locked: true,
    active: false,
    incomplete: false,
    files: [
      { name: 'combined.mp4' },
      { name: 'camera-1.mp4' },
      { name: 'camera-2.mp4' },
      { name: 'camera-3.mp4' },
      { name: 'camera-4.mp4' }
    ]
  },
  {
    id: '2026-07-26T05-52-32',
    displayName: '26/07/2026 05:52',
    sizeBytes: 754585,
    locked: false,
    active: false,
    incomplete: false,
    files: [
      { name: 'combined.mp4' },
      { name: 'camera-1.mp4' },
      { name: 'camera-2.mp4' },
      { name: 'camera-3.mp4' },
      { name: 'camera-4.mp4' }
    ]
  }
];

function state() {
  const withDisplayName = (recordingItem) => ({
    ...recordingItem,
    displayName: formatRecordingName(recordingItem.id)
  });
  const activeRecording = recording
    ? [{
        id: '2026-07-26T06-04-32',
        sizeBytes: 524288,
        locked: false,
        active: true,
        incomplete: false,
        files: []
      }]
    : [];
  return {
    recording,
    message: recording ? 'Recording active' : 'Preview active',
    wifiName: 'Garage Wi-Fi',
    settings,
    volumes: [
      { index: 0, label: 'Internal storage · app recordings' },
      { index: 1, label: 'Removable storage · USB test volume' }
    ],
    storage: {
      totalBytes: 10_415_276_032,
      availableBytes: 8_912_683_008,
      recorderBytes: 2_566_780,
      lockedBytes: 94_208
    },
    dateFormats: [
      { id: 'local_short', label: 'Local · 26/07/2026 17:42' },
      { id: 'iso', label: 'ISO · 2026-07-26 17:42' },
      { id: 'month_first', label: 'US · 07/26/2026 5:42 PM' },
      { id: 'day_month_name', label: 'Written · 26 Jul 2026, 17:42' }
    ],
    segments: [...activeRecording, ...recordings].map(withDisplayName)
  };
}

function formatRecordingName(id) {
  const [date, time] = id.split('T');
  const [year, month, day] = date.split('-');
  const [hour, minute] = time.split('-');
  if (settings.dateFormat === 'iso') {
    return `${year}-${month}-${day} ${hour}:${minute}`;
  }
  if (settings.dateFormat === 'month_first') {
    const numericHour = Number(hour);
    const displayHour = numericHour % 12 || 12;
    return `${month}/${day}/${year} ${displayHour}:${minute} ${numericHour >= 12 ? 'PM' : 'AM'}`;
  }
  if (settings.dateFormat === 'day_month_name') {
    const monthName = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][Number(month) - 1];
    return `${day} ${monthName} ${year}, ${hour}:${minute}`;
  }
  return `${day}/${month}/${year} ${hour}:${minute}`;
}

function send(response, status, type, body, headers = {}) {
  const buffer = Buffer.isBuffer(body) ? body : Buffer.from(body);
  response.writeHead(status, {
    'Content-Type': type,
    'Content-Length': buffer.length,
    'Cache-Control': 'no-store',
    ...headers
  });
  response.end(buffer);
}

function sendJson(response, status, body, headers = {}) {
  send(response, status, 'application/json; charset=utf-8', JSON.stringify(body), headers);
}

function parseBody(request) {
  return new Promise((resolve) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString() || '{}'));
      } catch {
        resolve({});
      }
    });
  });
}

function sessionFrom(request) {
  const cookie = request.headers.cookie || '';
  return cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith('byd_session='))
    ?.slice('byd_session='.length) || '';
}

function cameraSvg(camera) {
  const colors = ['#12d820', '#23cad0', '#ed1854', '#f6df10'];
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 480">
    <rect width="640" height="480" fill="#05090f"/>
    <rect x="${camera * 70}" width="120" height="480" fill="${colors[camera - 1]}"/>
  </svg>`;
}

function recordingPreviewSvg() {
  const colors = ['#12d820', '#23cad0', '#ed1854', '#f6df10'];
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 480 90">
    <rect width="480" height="90" fill="#05090f"/>
    ${colors.map((color, index) =>
      `<g transform="translate(${index * 120} 0)">
        <rect x="4" y="4" width="112" height="82" rx="5" fill="#0b1320"/>
        <rect x="${12 + index * 14}" y="4" width="34" height="82" fill="${color}"/>
        <path d="M4 67 38 34l24 19 22-24 32 38v19H4Z" fill="${color}" opacity=".38"/>
      </g>`
    ).join('')}
  </svg>`;
}

export async function handleMockApi(request, response, prefix = '/') {
  const url = new URL(request.url, `http://${request.headers.host}`);
  if (!url.pathname.startsWith(prefix)) {
    return false;
  }
  const path = url.pathname.slice(prefix.length);
  if (!path.startsWith('api/')) {
    return false;
  }

  if (path === 'api/auth' && request.method === 'GET') {
    sendJson(response, 200, { authenticated: sessions.has(sessionFrom(request)) });
    return true;
  }
  if (path === 'api/auth' && request.method === 'POST') {
    const now = Date.now();
    if (now < nextAttemptAt) {
      const retryAfterSeconds = Math.max(1, Math.ceil((nextAttemptAt - now) / 1000));
      sendJson(response, 429, { authenticated: false, retryAfterSeconds }, {
        'Retry-After': String(retryAfterSeconds)
      });
      return true;
    }
    const body = await parseBody(request);
    if (body.pin !== DEV_PIN) {
      nextAttemptAt = now + 5000;
      sendJson(response, 401, { authenticated: false, retryAfterSeconds: 5 }, {
        'Retry-After': '5'
      });
      return true;
    }
    const session = DEV_SESSION;
    sessions.add(session);
    nextAttemptAt = 0;
    sendJson(response, 200, { authenticated: true }, {
      'Set-Cookie': `byd_session=${session}; Path=${prefix}; Max-Age=315360000; HttpOnly; SameSite=Strict`
    });
    return true;
  }

  if (!sessions.has(sessionFrom(request))) {
    sendJson(response, 401, { authenticated: false });
    return true;
  }
  if (path === 'api/state' && request.method === 'GET') {
    sendJson(response, 200, state());
    return true;
  }
  if (path === 'api/status' && request.method === 'GET') {
    sendJson(response, 200, {
      recording,
      message: recording ? 'Recording active' : 'Preview active'
    });
    return true;
  }
  if (path === 'api/recording' && request.method === 'POST') {
    recording = Boolean((await parseBody(request)).enabled);
    sendJson(response, 200, state());
    return true;
  }
  if (path === 'api/settings' && request.method === 'POST') {
    const update = await parseBody(request);
    settings = {
      ...settings,
      resolution: update.resolution ?? settings.resolution,
      volumeIndex: update.volumeIndex ?? settings.volumeIndex,
      quotaGb: update.quotaGb ?? settings.quotaGb,
      retentionDays: update.retentionDays ?? settings.retentionDays,
      segmentMinutes: update.segmentMinutes ?? settings.segmentMinutes,
      minFreePercent: update.minFreePercent ?? settings.minFreePercent,
      dateFormat: update.dateFormat ?? settings.dateFormat,
      camera1Name: update.camera1Name ?? settings.camera1Name,
      camera2Name: update.camera2Name ?? settings.camera2Name,
      camera3Name: update.camera3Name ?? settings.camera3Name,
      camera4Name: update.camera4Name ?? settings.camera4Name,
      camera1FlipHorizontal: update.camera1FlipHorizontal ?? settings.camera1FlipHorizontal,
      camera2FlipHorizontal: update.camera2FlipHorizontal ?? settings.camera2FlipHorizontal,
      camera3FlipHorizontal: update.camera3FlipHorizontal ?? settings.camera3FlipHorizontal,
      camera4FlipHorizontal: update.camera4FlipHorizontal ?? settings.camera4FlipHorizontal,
      camera1FlipVertical: update.camera1FlipVertical ?? settings.camera1FlipVertical,
      camera2FlipVertical: update.camera2FlipVertical ?? settings.camera2FlipVertical,
      camera3FlipVertical: update.camera3FlipVertical ?? settings.camera3FlipVertical,
      camera4FlipVertical: update.camera4FlipVertical ?? settings.camera4FlipVertical,
      fisheyeCropPercent:
        update.fisheyeCropPercent ?? settings.fisheyeCropPercent,
      combinedTopLeft: update.combinedTopLeft ?? settings.combinedTopLeft,
      combinedTopRight: update.combinedTopRight ?? settings.combinedTopRight,
      combinedBottomLeft: update.combinedBottomLeft ?? settings.combinedBottomLeft,
      combinedBottomRight: update.combinedBottomRight ?? settings.combinedBottomRight
    };
    sendJson(response, 200, state());
    return true;
  }
  const lockMatch = path.match(/^api\/segments\/([^/]+)\/lock$/);
  if (lockMatch && request.method === 'POST') {
    const recordingId = decodeURIComponent(lockMatch[1]);
    const selected = recordings.find((item) => item.id === recordingId);
    if (selected) {
      selected.locked = Boolean((await parseBody(request)).locked);
    }
    sendJson(response, 200, state());
    return true;
  }
  const videoMatch = path.match(
    /^api\/segments\/([^/]+)\/files\/([^/]+\.mp4)$/
  );
  if (videoMatch && request.method === 'GET') {
    send(response, 200, 'video/mp4', Buffer.alloc(0), {
      'Accept-Ranges': 'bytes',
      'Content-Disposition': 'inline'
    });
    return true;
  }
  const previewMatch = path.match(/^api\/segments\/([^/]+)\/preview\.jpg$/);
  if (previewMatch && request.method === 'GET') {
    send(response, 200, 'image/svg+xml', recordingPreviewSvg());
    return true;
  }
  const cameraMatch = path.match(/^api\/cameras\/([1-4])\.jpg$/);
  if (cameraMatch && request.method === 'GET') {
    send(response, 200, 'image/svg+xml', cameraSvg(Number(cameraMatch[1])));
    return true;
  }
  sendJson(response, 404, { message: 'This mock route is unavailable' });
  return true;
}

export const mockAccessPrefix = '/DEMO1234/';
export const mockPin = DEV_PIN;
