class Segment {
  final String id;
  final String label;
  final bool locked;
  final bool active;
  final List<String> files;

  const Segment({
    required this.id,
    required this.label,
    required this.locked,
    required this.active,
    required this.files,
  });

  factory Segment.fromJson(Map<String, dynamic> json) => Segment(
        id: json['id'] as String? ?? '',
        label: json['label'] as String? ?? '',
        locked: json['locked'] as bool? ?? false,
        active: json['active'] as bool? ?? false,
        files: (json['files'] as List<dynamic>?)
                ?.map((e) => e as String)
                .toList() ??
            [],
      );
}

class RecorderState {
  final String mode; // NOT_RECORDING, RECORDING, PARKING_STANDBY, PARKING_RECORDING
  final String statusMessage;
  final List<Segment> segments;
  final bool phoneAccessEnabled;

  const RecorderState({
    required this.mode,
    required this.statusMessage,
    required this.segments,
    required this.phoneAccessEnabled,
  });

  bool get isRecording => mode == 'RECORDING';
  bool get isParking =>
      mode == 'PARKING_STANDBY' || mode == 'PARKING_RECORDING';

  factory RecorderState.fromJson(Map<String, dynamic> json) => RecorderState(
        mode: json['mode'] as String? ?? 'NOT_RECORDING',
        statusMessage: json['statusMessage'] as String? ?? '',
        segments: (json['segments'] as List<dynamic>?)
                ?.map((e) => Segment.fromJson(e as Map<String, dynamic>))
                .toList() ??
            [],
        phoneAccessEnabled: json['phoneAccessEnabled'] as bool? ?? false,
      );
}

class SystemSnapshot {
  final double cpuPercent;
  final int memUsedMb;
  final int memTotalMb;
  final int batteryPercent;
  final bool charging;
  final double batteryTempC;

  const SystemSnapshot({
    required this.cpuPercent,
    required this.memUsedMb,
    required this.memTotalMb,
    required this.batteryPercent,
    required this.charging,
    required this.batteryTempC,
  });

  factory SystemSnapshot.fromJson(Map<String, dynamic> json) => SystemSnapshot(
        cpuPercent: (json['cpuPercent'] as num?)?.toDouble() ?? 0,
        memUsedMb: json['memUsedMb'] as int? ?? 0,
        memTotalMb: json['memTotalMb'] as int? ?? 0,
        batteryPercent: json['batteryPercent'] as int? ?? -1,
        charging: json['charging'] as bool? ?? false,
        batteryTempC: (json['batteryTempC'] as num?)?.toDouble() ?? 0,
      );
}
