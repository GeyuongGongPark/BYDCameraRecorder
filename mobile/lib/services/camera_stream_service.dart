import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import '../models/server_config.dart';

/// WebSocket으로 차량 카메라 JPEG 스트림을 수신합니다.
class CameraStreamService {
  final ServerConfig config;
  final int cameraIndex; // 1-based
  final String? sessionCookie;

  WebSocket? _socket;
  StreamController<Uint8List>? _controller;
  bool _closed = false;

  CameraStreamService({
    required this.config,
    required this.cameraIndex,
    this.sessionCookie,
  });

  Stream<Uint8List> get stream {
    _controller ??= StreamController<Uint8List>.broadcast(
      onListen: _connect,
      onCancel: _disconnect,
    );
    return _controller!.stream;
  }

  Future<void> _connect() async {
    if (_closed) return;
    final uri = '${config.wsBaseUrl}/api/cameras/$cameraIndex/stream';
    try {
      final headers = sessionCookie != null
          ? {'Cookie': 'byd_session=$sessionCookie'}
          : null;
      final ws = await WebSocket.connect(uri, headers: headers);
      if (_closed) {
        ws.close();
        return;
      }
      _socket = ws;
      ws.listen(
        (data) {
          if (_closed) return;
          if (data is List<int>) {
            _controller?.add(Uint8List.fromList(data));
          }
        },
        onError: (_) {
          if (!_closed) {
            Future.delayed(const Duration(seconds: 2), _connect);
          }
        },
        onDone: () {
          if (!_closed) {
            Future.delayed(const Duration(seconds: 2), _connect);
          }
        },
      );
    } catch (_) {
      if (!_closed) {
        Future.delayed(const Duration(seconds: 2), _connect);
      }
    }
  }

  void _disconnect() {
    _socket?.close();
    _socket = null;
  }

  void dispose() {
    _closed = true;
    _disconnect();
    _controller?.close();
    _controller = null;
  }
}
