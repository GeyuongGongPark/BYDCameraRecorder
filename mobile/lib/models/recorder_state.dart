class Segment {
  final String id;
  final String label;
  final bool locked;
  final bool active;
  final List<String> files;
  final String? eventType;   // 'impact' | 'motion' | null
  final double gForce;
  final bool isPreBuffer;

  const Segment({
    required this.id,
    required this.label,
    required this.locked,
    required this.active,
    required this.files,
    this.eventType,
    this.gForce = 0.0,
    this.isPreBuffer = false,
  });

  /// 이벤트 세그먼트 (충격/모션 감지 녹화, 프리버퍼 제외)
  bool get isEvent => eventType != null && !isPreBuffer;

  factory Segment.fromJson(Map<String, dynamic> json) => Segment(
    id: json['id'] as String? ?? '',
    label: json['label'] as String? ?? '',
    locked: json['locked'] as bool? ?? false,
    active: json['active'] as bool? ?? false,
    files:
        (json['files'] as List<dynamic>?)?.map((e) => e as String).toList() ??
        [],
    eventType: json['eventType'] as String?,
    gForce: (json['gForce'] as num?)?.toDouble() ?? 0.0,
    isPreBuffer: json['isPreBuffer'] as bool? ?? false,
  );
}

class RecorderState {
  // NOT_RECORDING, RECORDING, PARKING_STANDBY, PARKING_RECORDING
  final String mode;
  final String statusMessage;
  final bool recordingActive;
  final bool guardActive;
  final bool eventRecording;
  final List<Segment> segments;
  final bool phoneAccessEnabled;

  const RecorderState({
    required this.mode,
    required this.statusMessage,
    required this.recordingActive,
    required this.guardActive,
    required this.eventRecording,
    required this.segments,
    required this.phoneAccessEnabled,
  });

  bool get isRecording =>
      recordingActive || mode == 'RECORDING' || mode == 'PARKING_RECORDING';
  bool get isParking => guardActive || mode == 'PARKING_STANDBY';
  bool get isParkingRecording => eventRecording || mode == 'PARKING_RECORDING';

  factory RecorderState.fromJson(Map<String, dynamic> json) => RecorderState(
    mode: json['mode'] as String? ?? 'NOT_RECORDING',
    statusMessage:
        json['statusMessage'] as String? ?? json['message'] as String? ?? '',
    recordingActive:
        json['recordingActive'] as bool? ?? json['recording'] as bool? ?? false,
    guardActive: json['guardActive'] as bool? ?? false,
    eventRecording: json['eventRecording'] as bool? ?? false,
    segments:
        (json['segments'] as List<dynamic>?)
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
