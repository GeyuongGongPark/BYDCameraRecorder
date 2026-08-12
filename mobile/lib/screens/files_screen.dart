import 'package:flutter/material.dart';
import '../models/recorder_state.dart';
import '../services/api_service.dart';
import 'segment_detail_screen.dart';

class FilesScreen extends StatefulWidget {
  final ApiService api;
  final RecorderState? state;

  const FilesScreen({super.key, required this.api, this.state});

  @override
  State<FilesScreen> createState() => _FilesScreenState();
}

class _FilesScreenState extends State<FilesScreen> {
  List<Segment> get _segments => widget.state?.segments ?? [];

  Future<void> _toggleLock(Segment seg) async {
    try {
      await widget.api.setSegmentLocked(seg.id, !seg.locked);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(e.toString())));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_segments.isEmpty) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.videocam_off, size: 48, color: Colors.white38),
            SizedBox(height: 12),
            Text('저장된 영상이 없습니다',
                style: TextStyle(color: Colors.white54)),
          ],
        ),
      );
    }

    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: _segments.length,
      itemBuilder: (ctx, i) => _segmentTile(_segments[i]),
    );
  }

  Widget _segmentTile(Segment seg) {
    return Card(
      color: const Color(0xFF0D1926),
      margin: const EdgeInsets.only(bottom: 10),
      shape:
          RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      child: ListTile(
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        leading: ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: SizedBox(
            width: 64,
            height: 48,
            child: Stack(
              fit: StackFit.expand,
              children: [
                Container(color: const Color(0xFF050D18)),
                const Center(
                  child:
                      Icon(Icons.play_circle_fill, color: Colors.white30),
                ),
                if (seg.active)
                  Positioned(
                    top: 4,
                    right: 4,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 4, vertical: 1),
                      decoration: BoxDecoration(
                        color: Colors.redAccent,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: const Text('REC',
                          style:
                              TextStyle(color: Colors.white, fontSize: 9)),
                    ),
                  ),
              ],
            ),
          ),
        ),
        title: Text(
          seg.label,
          style: const TextStyle(color: Colors.white, fontSize: 13),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          '${seg.files.length}개 파일',
          style: const TextStyle(color: Colors.white38, fontSize: 11),
        ),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: Icon(
                seg.locked ? Icons.lock : Icons.lock_open,
                color:
                    seg.locked ? const Color(0xFF3DC8FF) : Colors.white38,
                size: 20,
              ),
              onPressed: seg.active ? null : () => _toggleLock(seg),
              tooltip: seg.locked ? '잠금 해제' : '잠금',
            ),
            if (!seg.active)
              IconButton(
                icon: const Icon(Icons.chevron_right, color: Colors.white54),
                onPressed: () => Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => SegmentDetailScreen(
                        api: widget.api, segment: seg),
                  ),
                ),
              ),
          ],
        ),
        onTap: seg.active
            ? null
            : () => Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) =>
                        SegmentDetailScreen(api: widget.api, segment: seg),
                  ),
                ),
      ),
    );
  }
}
