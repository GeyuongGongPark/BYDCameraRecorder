class ServerConfig {
  final String host;
  final int port;
  final String token;

  const ServerConfig({
    required this.host,
    required this.port,
    required this.token,
  });

  String get baseUrl => 'http://$host:$port/$token';
  String get wsBaseUrl => 'ws://$host:$port/$token';

  factory ServerConfig.fromJson(Map<String, dynamic> json) => ServerConfig(
        host: json['host'] as String,
        port: json['port'] as int,
        token: json['token'] as String,
      );

  Map<String, dynamic> toJson() => {
        'host': host,
        'port': port,
        'token': token,
      };

  /// BYD 폰앱 URL 파싱: http://host:port/token/
  static ServerConfig? parseUrl(String url) {
    try {
      final uri = Uri.parse(url.trim());
      final parts = uri.path.split('/').where((s) => s.isNotEmpty).toList();
      if (parts.isEmpty) return null;
      return ServerConfig(
        host: uri.host,
        port: uri.port > 0 ? uri.port : 8765,
        token: parts.first,
      );
    } catch (_) {
      return null;
    }
  }
}
