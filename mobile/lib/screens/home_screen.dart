import 'dart:async';
import 'package:flutter/material.dart';
import '../models/recorder_state.dart';
import '../models/server_config.dart';
import '../services/api_service.dart';
import '../services/storage_service.dart';
import 'camera_screen.dart';
import 'files_screen.dart';
import 'pin_screen.dart';

class HomeScreen extends StatefulWidget {
  final ServerConfig config;
  const HomeScreen({super.key, required this.config});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final ApiService _api;
  final _storage = StorageService();

  int _tabIndex = 0;
  RecorderState? _state;
  SystemSnapshot? _system;
  bool _loading = true;
  String? _error;
  Timer? _pollTimer;

  @override
  void initState() {
    super.initState();
    _api = ApiService(widget.config);
    _init();
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    super.dispose();
  }

  Future<void> _init() async {
    final session = await _storage.loadSession(widget.config);
    if (session != null) _api.setSessionCookie(session);

    final authed = await _api.checkAuth();
    if (!authed && mounted) {
      final ok = await Navigator.push<bool>(
        context,
        MaterialPageRoute(
          builder: (_) => PinScreen(config: widget.config, api: _api),
        ),
      );
      if (ok != true) {
        if (mounted) Navigator.pop(context);
        return;
      }
    }
    _startPolling();
  }

  void _startPolling() {
    _poll();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) => _poll());
  }

  Future<void> _poll() async {
    try {
      final state = await _api.getState();
      SystemSnapshot? system;
      try {
        system = await _api.getSystem();
      } catch (_) {}
      if (mounted) {
        setState(() {
          _state = state;
          _system = system;
          _loading = false;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = e.toString();
        });
      }
    }
  }

  Future<void> _toggleRecording() async {
    if (_state == null) return;
    try {
      final next = await _api.setRecording(!_state!.isRecording);
      setState(() => _state = next);
    } catch (e) {
      _showError(e.toString());
    }
  }

  Future<void> _toggleParking() async {
    if (_state == null) return;
    try {
      final next = await _api.setParkingGuard(!_state!.isParking);
      setState(() => _state = next);
    } catch (e) {
      _showError(e.toString());
    }
  }

  void _showError(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: Text(
          widget.config.host,
          style: const TextStyle(color: Colors.white),
        ),
        iconTheme: const IconThemeData(color: Colors.white),
        actions: [if (_system != null) _systemStatusChip()],
      ),
      body: _loading
          ? const Center(
              child: CircularProgressIndicator(color: Color(0xFF3DC8FF)),
            )
          : _error != null
          ? _errorView()
          : _tabIndex == 0
          ? _dashboardView()
          : FilesScreen(api: _api, state: _state),
      bottomNavigationBar: BottomNavigationBar(
        backgroundColor: const Color(0xFF0A1020),
        selectedItemColor: const Color(0xFF3DC8FF),
        unselectedItemColor: Colors.white38,
        currentIndex: _tabIndex,
        onTap: (i) => setState(() => _tabIndex = i),
        items: const [
          BottomNavigationBarItem(icon: Icon(Icons.dashboard), label: '대시보드'),
          BottomNavigationBarItem(icon: Icon(Icons.folder_open), label: '파일'),
        ],
      ),
    );
  }

  Widget _systemStatusChip() {
    final s = _system!;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Center(
        child: Text(
          '${s.batteryPercent}% ${s.cpuPercent.toStringAsFixed(0)}%CPU',
          style: const TextStyle(color: Colors.white54, fontSize: 12),
        ),
      ),
    );
  }

  Widget _errorView() => Center(
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.signal_wifi_off, size: 48, color: Colors.redAccent),
        const SizedBox(height: 12),
        Text(_error!, style: const TextStyle(color: Colors.white54)),
        const SizedBox(height: 20),
        ElevatedButton(
          style: ElevatedButton.styleFrom(
            backgroundColor: const Color(0xFF143D5A),
          ),
          onPressed: _poll,
          child: const Text('재시도', style: TextStyle(color: Colors.white)),
        ),
      ],
    ),
  );

  Widget _dashboardView() {
    final state = _state!;
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _statusCard(state),
          const SizedBox(height: 16),
          _cameraGrid(),
          const SizedBox(height: 16),
          if (_system != null) _systemCard(),
        ],
      ),
    );
  }

  Widget _statusCard(RecorderState state) {
    final isRec = state.isRecording;
    final isParking = state.isParking;
    final isEvent = state.isParkingRecording;
    final title = isEvent
        ? '이벤트 녹화 중'
        : isRec
        ? '녹화 중'
        : isParking
        ? '주차 감시 중'
        : '정지';
    final icon = isEvent
        ? Icons.warning_amber_rounded
        : isRec
        ? Icons.fiber_manual_record
        : isParking
        ? Icons.local_parking
        : Icons.stop_circle_outlined;
    final color = isEvent
        ? Colors.orangeAccent
        : isRec
        ? Colors.redAccent
        : isParking
        ? Colors.amberAccent
        : Colors.white38;
    return Card(
      color: const Color(0xFF0D1926),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: color),
                const SizedBox(width: 8),
                Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            if (state.statusMessage.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                state.statusMessage,
                style: const TextStyle(color: Colors.white54, fontSize: 12),
              ),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: isRec
                          ? Colors.redAccent.shade700
                          : const Color(0xFF143D5A),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    icon: Icon(isRec ? Icons.stop : Icons.fiber_manual_record),
                    label: Text(isRec ? '녹화 중지' : '녹화 시작'),
                    onPressed: _toggleRecording,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: isParking
                          ? Colors.amber.shade800
                          : const Color(0xFF1A2A1A),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    icon: Icon(isParking
                        ? Icons.local_parking
                        : Icons.local_parking_outlined),
                    label: Text(isParking ? '주차감시 해제' : '주차감시 시작'),
                    onPressed: _toggleParking,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _cameraGrid() => GridView.builder(
    shrinkWrap: true,
    physics: const NeverScrollableScrollPhysics(),
    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
      crossAxisCount: 2,
      mainAxisSpacing: 8,
      crossAxisSpacing: 8,
      childAspectRatio: 4 / 3,
    ),
    itemCount: 4,
    itemBuilder: (ctx, i) => _cameraTile(i + 1),
  );

  Widget _cameraTile(int camera) => GestureDetector(
    onTap: () => Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => CameraScreen(
          config: widget.config,
          camera: camera,
          sessionCookie: _api.sessionCookie,
        ),
      ),
    ),
    child: ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: Container(
        color: const Color(0xFF0A1020),
        child: Stack(
          fit: StackFit.expand,
          children: [
            Image.network(
              _api.cameraJpegUri(camera).toString(),
              fit: BoxFit.cover,
              headers: _api.sessionCookie != null
                  ? {'Cookie': 'byd_session=${_api.sessionCookie}'}
                  : {},
              errorBuilder: (context, e, stack) => const Center(
                child: Icon(Icons.videocam_off,
                    size: 32, color: Colors.white24),
              ),
              loadingBuilder: (_, child, progress) => progress == null
                  ? child
                  : const Center(
                      child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Color(0xFF3DC8FF)),
                    ),
            ),
            Positioned(
              bottom: 0,
              left: 0,
              right: 0,
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.bottomCenter,
                    end: Alignment.topCenter,
                    colors: [
                      Colors.black.withValues(alpha: 0.7),
                      Colors.transparent,
                    ],
                  ),
                ),
                child: Text(
                  'Camera $camera',
                  style: const TextStyle(
                      color: Colors.white70, fontSize: 11),
                ),
              ),
            ),
            const Positioned(
              top: 6,
              right: 6,
              child: Icon(Icons.fullscreen, size: 18, color: Colors.white70),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _systemCard() {
    final s = _system!;
    return Card(
      color: const Color(0xFF0D1926),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '시스템 상태',
              style: TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            _statRow('CPU', '${s.cpuPercent.toStringAsFixed(1)}%'),
            _statRow('RAM', '${s.memUsedMb} / ${s.memTotalMb} MB'),
            _statRow(
              '배터리',
              '${s.batteryPercent}% ${s.charging ? '(충전 중)' : ''} ${s.batteryTempC.toStringAsFixed(1)}°C',
            ),
          ],
        ),
      ),
    );
  }

  Widget _statRow(String label, String value) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 4),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(color: Colors.white54, fontSize: 13),
        ),
        Text(value, style: const TextStyle(color: Colors.white, fontSize: 13)),
      ],
    ),
  );
}
