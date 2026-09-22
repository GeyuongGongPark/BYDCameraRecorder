import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../models/server_config.dart';

/// SSE(/api/events) 연결 및 주차 이벤트 로컬 알림 서비스.
///
/// connect() 호출 시 서버와 SSE 스트림을 맺고, 이벤트 수신 시 로컬 알림을
/// 표시합니다. 연결이 끊어지면 reconnectDelay 후 자동 재연결합니다.
/// disconnect()를 호출하면 재연결 루프를 완전히 종료합니다.
class EventStreamService {
  static const _notifChannelId = 'byd_parking';
  static const _notifChannelName = '주차 감시 알림';
  static const _reconnectDelay = Duration(seconds: 5);

  static final FlutterLocalNotificationsPlugin _notifPlugin =
      FlutterLocalNotificationsPlugin();
  static bool _notifInitialized = false;

  final ServerConfig config;
  String? _sessionCookie;

  bool _active = false;
  HttpClient? _httpClient;
  int _notifId = 0;

  EventStreamService(this.config);

  static Future<void> initNotifications() async {
    if (_notifInitialized) return;
    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosSettings = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: false,
      requestSoundPermission: true,
    );
    await _notifPlugin.initialize(
      const InitializationSettings(
        android: androidSettings,
        iOS: iosSettings,
      ),
    );
    _notifInitialized = true;
  }

  void setSessionCookie(String? cookie) {
    _sessionCookie = cookie;
  }

  /// SSE 연결을 시작합니다. 이미 연결 중이면 무시합니다.
  void connect() {
    if (_active) return;
    _active = true;
    _loop();
  }

  /// SSE 연결 및 재연결 루프를 종료합니다.
  void disconnect() {
    _active = false;
    _httpClient?.close(force: true);
    _httpClient = null;
  }

  Future<void> _loop() async {
    while (_active) {
      try {
        await _connect();
      } catch (_) {
        // 연결 실패 또는 스트림 종료 — reconnectDelay 후 재시도
      }
      if (_active) {
        await Future.delayed(_reconnectDelay);
      }
    }
  }

  Future<void> _connect() async {
    final uri = Uri.parse('${config.baseUrl}/api/events');
    final client = HttpClient();
    _httpClient = client;
    client.connectionTimeout = const Duration(seconds: 10);

    final request = await client.getUrl(uri);
    request.headers.set('Accept', 'text/event-stream');
    request.headers.set('Cache-Control', 'no-cache');
    if (_sessionCookie != null) {
      request.headers.set('Cookie', _sessionCookie!);
    }

    final response = await request.close();
    if (response.statusCode != 200) {
      response.drain<void>();
      return;
    }

    final buffer = StringBuffer();
    await for (final chunk in response.transform(utf8.decoder)) {
      if (!_active) break;
      buffer.write(chunk);
      // SSE 이벤트는 빈 줄(\n\n)로 구분됨
      String content = buffer.toString();
      while (content.contains('\n\n')) {
        final idx = content.indexOf('\n\n');
        final block = content.substring(0, idx);
        content = content.substring(idx + 2);
        _handleBlock(block);
      }
      buffer.clear();
      buffer.write(content);
    }
  }

  void _handleBlock(String block) {
    for (final line in block.split('\n')) {
      if (line.startsWith('data: ')) {
        final jsonStr = line.substring(6).trim();
        if (jsonStr.isEmpty) continue;
        try {
          final map = jsonDecode(jsonStr) as Map<String, dynamic>;
          _onEvent(map);
        } catch (_) {}
      }
    }
  }

  void _onEvent(Map<String, dynamic> event) {
    final type = event['type'] as String?;
    if (type == null || type == 'connected' || type == 'heartbeat') return;

    final title = '주차 감시 알림';
    final String body;
    switch (type) {
      case 'impact':
        final g = (event['gForce'] as num?)?.toStringAsFixed(1) ?? '?';
        body = '충격 감지: ${g}G — 주차 녹화 시작';
        break;
      case 'motion':
        body = '카메라 모션 감지 — 주차 녹화 시작';
        break;
      case 'radar':
        final area = event['area'] ?? '?';
        body = '레이더 근접 감지 (구역 $area) — 주차 녹화 시작';
        break;
      case 'door':
        final area = event['area'] ?? '?';
        body = '도어 열림 감지 (구역 $area) — 주차 녹화 시작';
        break;
      case 'window':
        final area = event['area'] ?? '?';
        body = '창문 열림 감지 (구역 $area) — 주차 녹화 시작';
        break;
      case 'alarm':
        body = '차량 알람 감지 — 주차 녹화 시작';
        break;
      default:
        return;
    }

    _showNotification(title, body);
  }

  Future<void> _showNotification(String title, String body) async {
    const androidDetails = AndroidNotificationDetails(
      _notifChannelId,
      _notifChannelName,
      importance: Importance.high,
      priority: Priority.high,
      playSound: true,
    );
    const iosDetails = DarwinNotificationDetails(
      presentAlert: true,
      presentSound: true,
    );
    await _notifPlugin.show(
      _notifId++,
      title,
      body,
      const NotificationDetails(
        android: androidDetails,
        iOS: iosDetails,
      ),
    );
  }
}
