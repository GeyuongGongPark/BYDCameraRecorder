import 'package:flutter/material.dart';
import '../models/server_config.dart';
import '../services/storage_service.dart';
import 'connect_screen.dart';
import 'home_screen.dart';

class ServerListScreen extends StatefulWidget {
  const ServerListScreen({super.key});

  @override
  State<ServerListScreen> createState() => _ServerListScreenState();
}

class _ServerListScreenState extends State<ServerListScreen> {
  final _storage = StorageService();
  List<ServerConfig> _servers = [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final servers = await _storage.loadServers();
    setState(() => _servers = servers);
  }

  void _openConnect() async {
    final config = await Navigator.push<ServerConfig>(
      context,
      MaterialPageRoute(builder: (_) => const ConnectScreen()),
    );
    if (config != null) {
      await _storage.saveServer(config);
      _openHome(config);
    }
  }

  void _openHome(ServerConfig config) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => HomeScreen(config: config)),
    ).then((_) => _load());
  }

  Future<void> _delete(ServerConfig config) async {
    await _storage.removeServer(config);
    await _storage.clearSession(config);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF05080F),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A1020),
        title: const Text('BYD 블랙박스', style: TextStyle(color: Colors.white)),
        actions: [
          IconButton(
            icon: const Icon(Icons.add, color: Colors.white),
            onPressed: _openConnect,
            tooltip: '새 차량 연결',
          ),
        ],
      ),
      body: _servers.isEmpty
          ? _emptyState()
          : ListView.builder(
              itemCount: _servers.length,
              itemBuilder: (ctx, i) => _serverTile(_servers[i]),
            ),
    );
  }

  Widget _emptyState() => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.videocam_off, size: 64, color: Color(0xFF3DC8FF)),
            const SizedBox(height: 16),
            const Text('등록된 차량이 없습니다',
                style: TextStyle(color: Colors.white70, fontSize: 16)),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF143D5A),
                foregroundColor: Colors.white,
                padding:
                    const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
              ),
              icon: const Icon(Icons.add),
              label: const Text('차량 연결'),
              onPressed: _openConnect,
            ),
          ],
        ),
      );

  Widget _serverTile(ServerConfig config) => ListTile(
        tileColor: const Color(0xFF0D1926),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        leading: const Icon(Icons.directions_car, color: Color(0xFF3DC8FF)),
        title: Text(
          '${config.host}:${config.port}',
          style: const TextStyle(color: Colors.white),
        ),
        subtitle: Text(
          config.token.length > 12
              ? '${config.token.substring(0, 12)}…'
              : config.token,
          style: const TextStyle(color: Colors.white38, fontSize: 12),
        ),
        trailing: IconButton(
          icon: const Icon(Icons.delete_outline, color: Colors.white38),
          onPressed: () => _delete(config),
        ),
        onTap: () => _openHome(config),
      );
}
