export function websocketUrl(path) {
  const url = new URL(path, window.location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.href;
}

export function cameraStream(node, initialOptions) {
  let options = initialOptions;
  let socket = null;
  let reconnectTimer = 0;
  let objectUrl = '';
  let connectionKey = '';
  let destroyed = false;

  function closeSocket() {
    if (reconnectTimer) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = 0;
    }
    if (socket) {
      const closingSocket = socket;
      socket = null;
      closingSocket.onclose = null;
      closingSocket.onerror = null;
      closingSocket.close();
    }
  }

  function releaseObjectUrl() {
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl);
      objectUrl = '';
    }
  }

  function connect() {
    closeSocket();
    if (destroyed || !options?.enabled || !options?.path) {
      return;
    }
    const expectedKey = `${options.path}|${options.enabled}`;
    connectionKey = expectedKey;
    const activeSocket = new WebSocket(websocketUrl(options.path));
    socket = activeSocket;
    activeSocket.binaryType = 'arraybuffer';
    activeSocket.onmessage = (event) => {
      if (
        destroyed ||
        socket !== activeSocket ||
        connectionKey !== expectedKey
      ) {
        return;
      }
      const previousUrl = objectUrl;
      objectUrl = URL.createObjectURL(
        new Blob([event.data], { type: 'image/jpeg' })
      );
      node.onload = () => {
        if (previousUrl) {
          URL.revokeObjectURL(previousUrl);
        }
      };
      node.src = objectUrl;
    };
    activeSocket.onclose = () => {
      if (
        destroyed ||
        socket !== activeSocket ||
        connectionKey !== expectedKey ||
        !options?.enabled
      ) {
        return;
      }
      socket = null;
      reconnectTimer = window.setTimeout(connect, 0);
    };
    activeSocket.onerror = () => {
      activeSocket.close();
    };
  }

  connect();

  return {
    update(nextOptions) {
      const nextKey = nextOptions?.enabled
        ? `${nextOptions.path}|${nextOptions.enabled}`
        : '';
      const currentKey = options?.enabled
        ? `${options.path}|${options.enabled}`
        : '';
      options = nextOptions;
      if (nextKey !== currentKey) {
        connect();
      }
    },
    destroy() {
      destroyed = true;
      closeSocket();
      releaseObjectUrl();
      node.onload = null;
    }
  };
}
