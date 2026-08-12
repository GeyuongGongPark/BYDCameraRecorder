import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import '../models/server_config.dart';

/// QR 스캔 또는 수동 URL 입력으로 차량 서버에 연결합니다.
class ConnectScreen extends StatefulWidget {
  const ConnectScreen({super.key});

  @override
  State<ConnectScreen> createState() => _ConnectScreenState();
}

class _ConnectScreenState extends State<ConnectScreen>
    with SingleTickerProviderStateMixin {
  late final TabController _tabs;
  final _urlController = TextEditingController();
  bool _processed = false;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _tabs.dispose();
    _urlController.dispose();
    super.dispose();
  }

  void _handleBarcode(BarcodeCapture capture) {
    if (_processed) return;
    final raw = capture.barcodes.firstOrNull?.rawValue;
    if (raw == null) return;
    final config = ServerConfig.parseUrl(raw);
    if (config != null) {
      _processed = true;
      Navigator.pop(context, config);
    }
  }

  void _handleManualInput() {
    final config = ServerConfig.parseUrl(_urlController.text);
    if (config == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('올바른 URL 형식이 아닙니다')),
      );
      return;
    }
    Navigator.pop(context, config);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: const Text('차량 연결', style: TextStyle(color: Colors.white)),
        iconTheme: const IconThemeData(color: Colors.white),
        bottom: TabBar(
          controller: _tabs,
          labelColor: const Color(0xFF3DC8FF),
          unselectedLabelColor: Colors.white54,
          indicatorColor: const Color(0xFF3DC8FF),
          tabs: const [
            Tab(icon: Icon(Icons.qr_code_scanner), text: 'QR 스캔'),
            Tab(icon: Icon(Icons.edit), text: '직접 입력'),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: [_qrTab(), _manualTab()],
      ),
    );
  }

  Widget _qrTab() => Stack(
        children: [
          MobileScanner(onDetect: _handleBarcode),
          Positioned(
            bottom: 40,
            left: 0,
            right: 0,
            child: Center(
              child: Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Text(
                  '차량 앱의 QR 코드를 스캔하세요',
                  style: TextStyle(color: Colors.white),
                ),
              ),
            ),
          ),
        ],
      );

  Widget _manualTab() => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              '차량 앱에 표시된 URL을 입력하세요\n예: http://192.168.0.10:8765/abc123/',
              style: TextStyle(color: Colors.white54, fontSize: 13),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _urlController,
              style: const TextStyle(color: Colors.white),
              decoration: InputDecoration(
                hintText: 'http://192.168.0.10:8765/token/',
                hintStyle: const TextStyle(color: Colors.white38),
                filled: true,
                fillColor: const Color(0xFF0D1926),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: const BorderSide(color: Color(0xFF3D6382)),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: const BorderSide(color: Color(0xFF3D6382)),
                ),
              ),
              keyboardType: TextInputType.url,
              autocorrect: false,
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF143D5A),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
              onPressed: _handleManualInput,
              child: const Text('연결'),
            ),
          ],
        ),
      );
}
