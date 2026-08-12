import 'dart:async';
import 'dart:typed_data';
import 'package:web_socket_channel/web_socket_channel.dart';
import '../models/server_config.dart';

/// WebSocket으로 차량 카메라 JPEG 스트림을 수신합니다.
class CameraStreamService {
  final ServerConfig config;
  final int cameraIndex; // 1-based

  WebSocketChannel? _channel;
  StreamController<Uint8List>? _controller;
  bool _closed = false;

  CameraStreamService({required this.config, required this.cameraIndex});

  Stream<Uint8List> get stream {
    _controller ??= StreamController<Uint8List>.broadcast(
      onListen: _connect,
      onCancel: _disconnect,
    );
    return _controller!.stream;
  }

  void _connect() {
    if (_closed) return;
    final uri = '${config.wsBaseUrl}/api/cameras/$cameraIndex/stream';
    _channel = WebSocketChannel.connect(Uri.parse(uri));
    _channel!.stream.listen(
      (data) {
        if (data is List<int> && !_closed) {
          _controller?.add(Uint8List.fromList(data));
        }
      },
      onError: (e) {
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
  }

  void _disconnect() {
    _channel?.sink.close();
    _channel = null;
  }

  void dispose() {
    _closed = true;
    _disconnect();
    _controller?.close();
    _controller = null;
  }
}
