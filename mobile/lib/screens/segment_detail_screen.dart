import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:open_file/open_file.dart';
import 'package:path_provider/path_provider.dart';
import '../models/recorder_state.dart';
import '../services/api_service.dart';

class SegmentDetailScreen extends StatefulWidget {
  final ApiService api;
  final Segment segment;

  const SegmentDetailScreen(
      {super.key, required this.api, required this.segment});

  @override
  State<SegmentDetailScreen> createState() => _SegmentDetailScreenState();
}

class _SegmentDetailScreenState extends State<SegmentDetailScreen> {
  late Future<Uint8List> _previewFuture;
  final Map<String, _DownloadState> _downloads = {};

  @override
  void initState() {
    super.initState();
    _previewFuture = widget.api.fetchSegmentPreview(widget.segment.id);
  }

  Future<void> _downloadFile(String fileName) async {
    setState(() => _downloads[fileName] = _DownloadState(downloading: true));
    try {
      final dir = await getTemporaryDirectory();
      final savePath = '${dir.path}/$fileName';
      await widget.api.downloadSegmentFile(
        widget.segment.id,
        fileName,
        savePath,
        onProgress: (received, total) {
          if (mounted && total > 0) {
            setState(() => _downloads[fileName] = _DownloadState(
                  downloading: true,
                  progress: received / total,
                ));
          }
        },
      );
      if (!mounted) return;
      setState(() => _downloads[fileName] = _DownloadState(done: true));
      final result = await OpenFile.open(savePath);
      if (result.type != ResultType.done && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('파일을 열 수 없습니다: ${result.message}')),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _downloads.remove(fileName));
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('다운로드 실패: $e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: Text(widget.segment.label,
            style: const TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        actions: [
          IconButton(
            icon: Icon(
              widget.segment.locked ? Icons.lock : Icons.lock_open,
              color: widget.segment.locked
                  ? const Color(0xFF3DC8FF)
                  : Colors.white54,
            ),
            tooltip: widget.segment.locked ? '잠금 해제' : '잠금',
            onPressed: () async {
              try {
                await widget.api
                    .setSegmentLocked(widget.segment.id, !widget.segment.locked);
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
              child: FutureBuilder<Uint8List>(
                future: _previewFuture,
                builder: (ctx, snap) {
                  if (snap.connectionState == ConnectionState.waiting) {
                    return const Center(
                      child: CircularProgressIndicator(
                          color: Color(0xFF3DC8FF)),
                    );
                  }
                  if (snap.hasData) {
                    return Image.memory(snap.data!, fit: BoxFit.contain);
                  }
                  return const Center(
                    child: Icon(Icons.broken_image,
                        size: 48, color: Colors.white24),
                  );
                },
              ),
            ),
          ),
          // 파일 목록
          Expanded(
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: widget.segment.files.length,
              itemBuilder: (ctx, i) => _fileTile(widget.segment.files[i]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _fileTile(String fileName) {
    final isVideo = fileName.endsWith('.mp4');
    final dl = _downloads[fileName];
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: ListTile(
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
        subtitle: dl != null && dl.downloading && dl.progress != null
            ? LinearProgressIndicator(
                value: dl.progress,
                backgroundColor: const Color(0xFF1A2E42),
                valueColor: const AlwaysStoppedAnimation(Color(0xFF3DC8FF)),
              )
            : null,
        trailing: dl != null && dl.downloading
            ? const SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Color(0xFF3DC8FF),
                ),
              )
            : dl != null && dl.done
                ? const Icon(Icons.check_circle,
                    color: Color(0xFF3DC8FF), size: 20)
                : const Icon(Icons.download, color: Colors.white38, size: 20),
        onTap: (dl != null && dl.downloading)
            ? null
            : () => _downloadFile(fileName),
      ),
    );
  }
}

class _DownloadState {
  final bool downloading;
  final bool done;
  final double? progress;

  const _DownloadState({
    this.downloading = false,
    this.done = false,
    this.progress,
  });
}
