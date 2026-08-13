import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:http/http.dart' as http;
import '../models/recorder_state.dart';
import '../models/server_config.dart';

class ApiException implements Exception {
  final int statusCode;
  final String message;
  ApiException(this.statusCode, this.message);

  @override
  String toString() => 'ApiException($statusCode): $message';
}

class ApiService {
  final ServerConfig config;
  String? _sessionCookie;

  ApiService(this.config);

  // ── 인증 ─────────────────────────────────────────────────────────────

  Future<bool> checkAuth() async {
    try {
      final res = await _get('api/auth');
      final json = jsonDecode(res.body) as Map<String, dynamic>;
      return json['authenticated'] as bool? ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<bool> login(String pin) async {
    final res = await _post('api/auth', {'pin': pin});
    if (res.statusCode == 200) {
      _extractAndStoreCookie(res);
      return true;
    }
    return false;
  }

  // ── 상태 ─────────────────────────────────────────────────────────────

  Future<RecorderState> getState() async {
    final res = await _get('api/state');
    _requireOk(res);
    return RecorderState.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<SystemSnapshot> getSystem() async {
    final res = await _get('api/system');
    _requireOk(res);
    return SystemSnapshot.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  // ── 녹화 제어 ─────────────────────────────────────────────────────────

  Future<RecorderState> setRecording(bool enabled) async {
    final res = await _post('api/recording', {'enabled': enabled});
    _requireOk(res);
    return RecorderState.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  // ── 세그먼트 ─────────────────────────────────────────────────────────

  Future<RecorderState> setSegmentLocked(String segmentId, bool locked) async {
    final encoded = Uri.encodeComponent(segmentId);
    final res = await _post('api/segments/$encoded/lock', {'locked': locked});
    _requireOk(res);
    return RecorderState.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<Uint8List> fetchSegmentPreview(String segmentId) async {
    final encoded = Uri.encodeComponent(segmentId);
    final res = await _get('api/segments/$encoded/preview.jpg');
    _requireOk(res);
    return res.bodyBytes;
  }

  /// 파일을 청크 단위로 스트리밍 다운로드합니다. onProgress(received, total) 호출.
  Future<void> downloadSegmentFile(
    String segmentId,
    String fileName,
    String savePath, {
    void Function(int received, int total)? onProgress,
  }) async {
    final encodedId = Uri.encodeComponent(segmentId);
    final encodedFile = Uri.encodeComponent(fileName);
    final uri = Uri.parse(
        '${config.baseUrl}/api/segments/$encodedId/files/$encodedFile');
    final request = http.Request('GET', uri);
    request.headers.addAll(_headers);
    final client = http.Client();
    try {
      final response = await client.send(request);
      if (response.statusCode != 200 && response.statusCode != 206) {
        throw ApiException(response.statusCode, 'Download failed');
      }
      final total = response.contentLength ?? -1;
      int received = 0;
      final sink = File(savePath).openWrite();
      await for (final chunk in response.stream) {
        sink.add(chunk);
        received += chunk.length;
        onProgress?.call(received, total);
      }
      await sink.close();
    } finally {
      client.close();
    }
  }

  Uri cameraJpegUri(int camera) =>
      Uri.parse('${config.baseUrl}/api/cameras/$camera.jpg');

  Uri cameraWsUri(int camera) =>
      Uri.parse('${config.wsBaseUrl}/api/cameras/$camera/stream');

  // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (_sessionCookie != null) 'Cookie': 'byd_session=$_sessionCookie',
      };

  Future<http.Response> _get(String path) =>
      http.get(Uri.parse('${config.baseUrl}/$path'), headers: _headers)
          .timeout(const Duration(seconds: 10));

  Future<http.Response> _post(String path, Object body) =>
      http.post(
        Uri.parse('${config.baseUrl}/$path'),
        headers: _headers,
        body: jsonEncode(body),
      ).timeout(const Duration(seconds: 10));

  void _requireOk(http.Response res) {
    if (res.statusCode != 200) {
      throw ApiException(res.statusCode, res.body);
    }
  }

  void _extractAndStoreCookie(http.Response res) {
    final setCookie = res.headers['set-cookie'];
    if (setCookie == null) return;
    final match = RegExp(r'byd_session=([^;]+)').firstMatch(setCookie);
    if (match != null) {
      _sessionCookie = match.group(1);
    }
  }

  void setSessionCookie(String? cookie) {
    _sessionCookie = cookie;
  }

  String? get sessionCookie => _sessionCookie;
}
