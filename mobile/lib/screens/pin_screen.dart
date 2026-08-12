import 'package:flutter/material.dart';
import '../models/server_config.dart';
import '../services/api_service.dart';
import '../services/storage_service.dart';

class PinScreen extends StatefulWidget {
  final ServerConfig config;
  final ApiService api;

  const PinScreen({super.key, required this.config, required this.api});

  @override
  State<PinScreen> createState() => _PinScreenState();
}

class _PinScreenState extends State<PinScreen> {
  final _storage = StorageService();
  String _pin = '';
  bool _loading = false;
  String? _error;

  void _append(String digit) {
    if (_pin.length >= 8) return;
    setState(() {
      _pin += digit;
      _error = null;
    });
    if (_pin.length >= 4) _submit();
  }

  void _backspace() {
    if (_pin.isEmpty) return;
    setState(() => _pin = _pin.substring(0, _pin.length - 1));
  }

  Future<void> _submit() async {
    setState(() => _loading = true);
    try {
      final ok = await widget.api.login(_pin);
      if (!mounted) return;
      if (ok) {
        if (widget.api.sessionCookie != null) {
          await _storage.saveSession(
              widget.config, widget.api.sessionCookie!);
        }
        if (!mounted) return;
        Navigator.pop(context, true);
      } else {
        setState(() {
          _pin = '';
          _error = '잘못된 PIN입니다';
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _pin = '';
          _error = '연결 오류: $e';
          _loading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: const Text('PIN 입력', style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 320),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.lock_outline,
                  size: 48, color: Color(0xFF3DC8FF)),
              const SizedBox(height: 16),
              Text(
                '${widget.config.host}:${widget.config.port}',
                style:
                    const TextStyle(color: Colors.white54, fontSize: 13),
              ),
              const SizedBox(height: 24),
              _pinDots(),
              if (_error != null) ...[
                const SizedBox(height: 12),
                Text(_error!,
                    style: const TextStyle(color: Colors.redAccent)),
              ],
              const SizedBox(height: 32),
              if (_loading)
                const CircularProgressIndicator(color: Color(0xFF3DC8FF))
              else
                _numpad(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _pinDots() => Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: List.generate(
          _pin.isEmpty ? 4 : _pin.length,
          (i) => Container(
            margin: const EdgeInsets.symmetric(horizontal: 6),
            width: 14,
            height: 14,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: i < _pin.length
                  ? const Color(0xFF3DC8FF)
                  : const Color(0xFF3D6382),
            ),
          ),
        ),
      );

  Widget _numpad() => Column(
        children: [
          for (final row in [
            ['1', '2', '3'],
            ['4', '5', '6'],
            ['7', '8', '9'],
            ['', '0', '⌫'],
          ])
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: row.map((label) => _key(label)).toList(),
            ),
        ],
      );

  Widget _key(String label) {
    if (label.isEmpty) return const SizedBox(width: 80, height: 64);
    return GestureDetector(
      onTap: () {
        if (label == '⌫') {
          _backspace();
        } else {
          _append(label);
        }
      },
      child: Container(
        width: 80,
        height: 64,
        margin: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: const Color(0xFF0D1926),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: const Color(0xFF3D6382)),
        ),
        alignment: Alignment.center,
        child: Text(
          label,
          style: const TextStyle(
              color: Colors.white, fontSize: 22, fontWeight: FontWeight.w500),
        ),
      ),
    );
  }
}
