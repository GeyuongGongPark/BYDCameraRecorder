import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../models/server_config.dart';
import '../services/camera_stream_service.dart';

class CameraScreen extends StatefulWidget {
  final ServerConfig config;
  final int camera; // 1-based

  const CameraScreen({super.key, required this.config, required this.camera});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  late final CameraStreamService _stream;
  Uint8List? _frame;
  bool _connected = false;

  // 핀치줌 상태
  double _scale = 1.0;
  double _baseScale = 1.0;
  Offset _offset = Offset.zero;
  Offset _baseOffset = Offset.zero;

  @override
  void initState() {
    super.initState();
    _stream = CameraStreamService(
        config: widget.config, cameraIndex: widget.camera);
    _stream.stream.listen((frame) {
      if (mounted) {
        setState(() {
          _frame = frame;
          _connected = true;
        });
      }
    });
  }

  @override
  void dispose() {
    _stream.dispose();
    super.dispose();
  }

  void _resetZoom() => setState(() {
        _scale = 1.0;
        _offset = Offset.zero;
      });

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        title: Text('Camera ${widget.camera}',
            style: const TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        actions: [
          if (_scale > 1.01)
            IconButton(
              icon: const Icon(Icons.zoom_out_map, color: Colors.white70),
              onPressed: _resetZoom,
              tooltip: '줌 초기화',
            ),
        ],
      ),
      body: GestureDetector(
        onScaleStart: (d) {
          _baseScale = _scale;
          _baseOffset = _offset;
        },
        onScaleUpdate: (d) {
          setState(() {
            _scale = (_baseScale * d.scale).clamp(1.0, 8.0);
            _offset = _baseOffset + d.focalPointDelta;
          });
        },
        child: Center(
          child: _frame == null
              ? Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const CircularProgressIndicator(
                        color: Color(0xFF3DC8FF)),
                    const SizedBox(height: 12),
                    Text(
                      _connected ? '스트림 수신 중…' : '연결 중…',
                      style: const TextStyle(color: Colors.white54),
                    ),
                  ],
                )
              : Transform(
                  alignment: Alignment.center,
                  transform: Matrix4.identity()
                    ..translateByDouble(_offset.dx, _offset.dy, 0, 1)
                    ..scaleByDouble(_scale, _scale, 1, 1),
                  child: Image.memory(
                    _frame!,
                    gaplessPlayback: true,
                    fit: BoxFit.contain,
                  ),
                ),
        ),
      ),
    );
  }
}
