import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/server_config.dart';

class StorageService {
  static const _serversKey = 'saved_servers';
  static const _sessionPrefix = 'session_';

  Future<List<ServerConfig>> loadServers() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getStringList(_serversKey) ?? [];
    return raw.map((s) {
      try {
        return ServerConfig.fromJson(
            jsonDecode(s) as Map<String, dynamic>);
      } catch (_) {
        return null;
      }
    }).whereType<ServerConfig>().toList();
  }

  Future<void> saveServer(ServerConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    final servers = await loadServers();
    final exists = servers.any(
        (s) => s.host == config.host && s.port == config.port);
    if (!exists) {
      servers.insert(0, config);
    }
    await prefs.setStringList(
        _serversKey, servers.map((s) => jsonEncode(s.toJson())).toList());
  }

  Future<void> removeServer(ServerConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    final servers = await loadServers();
    servers.removeWhere(
        (s) => s.host == config.host && s.port == config.port);
    await prefs.setStringList(
        _serversKey, servers.map((s) => jsonEncode(s.toJson())).toList());
  }

  Future<String?> loadSession(ServerConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('$_sessionPrefix${config.host}:${config.port}');
  }

  Future<void> saveSession(ServerConfig config, String sessionCookie) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
        '$_sessionPrefix${config.host}:${config.port}', sessionCookie);
  }

  Future<void> clearSession(ServerConfig config) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove('$_sessionPrefix${config.host}:${config.port}');
  }
}
