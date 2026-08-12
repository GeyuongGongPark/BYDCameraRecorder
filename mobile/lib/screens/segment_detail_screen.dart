import 'package:flutter/material.dart';
import '../models/recorder_state.dart';
import '../services/api_service.dart';

class SegmentDetailScreen extends StatelessWidget {
  final ApiService api;
  final Segment segment;

  const SegmentDetailScreen(
      {super.key, required this.api, required this.segment});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: Text(segment.label,
            style: const TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        actions: [
          IconButton(
            icon: Icon(
              segment.locked ? Icons.lock : Icons.lock_open,
              color: segment.locked
                  ? const Color(0xFF3DC8FF)
                  : Colors.white54,
            ),
            tooltip: segment.locked ? '잠금 해제' : '잠금',
            onPressed: () async {
              try {
                await api.setSegmentLocked(segment.id, !segment.locked);
                if (context.mounted) Navigator.pop(context);
              } catch (e) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(content: Text(e.toString())));
                }
              }
            },
          ),
        ],
      ),
      body: Column(
        children: [
          // 미리보기
          AspectRatio(
            aspectRatio: 16 / 9,
            child: Container(
              color: Colors.black,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Image.network(
                    api.segmentPreviewUri(segment.id).toString(),
                    fit: BoxFit.contain,
                    errorBuilder: (_, e, stack) => const Center(
                      child: Icon(Icons.broken_image,
                          size: 48, color: Colors.white24),
                    ),
                  ),
                ],
              ),
            ),
          ),
          // 파일 목록
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: segment.files.length,
              itemBuilder: (ctx, i) => _fileTile(segment.files[i]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _fileTile(String fileName) {
    final isVideo = fileName.endsWith('.mp4');
    return ListTile(
      tileColor: const Color(0xFF0D1926),
      shape:
          RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      contentPadding:
          const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      leading: Icon(
        isVideo ? Icons.videocam : Icons.description,
        color: const Color(0xFF3DC8FF),
        size: 20,
      ),
      title: Text(fileName,
          style: const TextStyle(color: Colors.white, fontSize: 13)),
      trailing: const Icon(Icons.download, color: Colors.white38, size: 20),
      onTap: () {
        // TODO: url_launcher로 다운로드 링크 열기
        // api.segmentFileUri(segment.id, fileName).toString() + '?download=1'
      },
    );
  }
}
